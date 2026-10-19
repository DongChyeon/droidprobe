// Command probe talks to the droidprobe SDK inside a debuggable Android app over adb.
package main

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net"
	"os"
	"os/exec"
	"os/signal"
	"path/filepath"
	"strconv"
	"strings"
	"syscall"
	"time"

	"github.com/DongChyeon/droidprobe/cli/internal/adb"
	"github.com/DongChyeon/droidprobe/cli/internal/proto"
	"github.com/DongChyeon/droidprobe/cli/internal/watch"
)

var version = "0.1.0"

const (
	exitOK          = 0
	exitApp         = 1
	exitUsage       = 2
	exitConn        = 3
	exitTimeout     = 4
	exitInterrupted = 130

	helloTimeout = 10 * time.Second
	// The app times calls out after 10 s by default; this only guards against a hung app.
	callTimeout    = 60 * time.Second
	cancelTimeout  = 2 * time.Second
	cleanupTimeout = 5 * time.Second
)

const usageText = `usage:
  probe devices [--json] [--prune]
  probe list
  probe describe <command>
  probe call <command> [--input JSON | --input-file FILE|-] [--since CURSOR]
  probe watch <command> [--input JSON | --input-file FILE|-] [--since CURSOR]
                        [--limit N] [--timeout DURATION] [--until JQ]
  probe cursor
  probe ui [--port PORT] [--open] [--allow-actions]

target flags:
  -s SERIAL     device serial (default: $ANDROID_SERIAL, or the only device)
  -p PACKAGE    app package (default: the only app with a probe socket)
  --adb PATH    adb binary (default: adb on PATH, else $ANDROID_HOME/platform-tools/adb)
  --version     print the version

exit codes: 0 ok, 1 app error, 2 usage, 3 connection, 4 timeout or --until unmet
`

type env struct {
	stdout   io.Writer
	stderr   io.Writer
	stdin    io.Reader
	getenv   func(string) string
	lookPath func(string) (string, error)
	newADB   func(path string) adb.Runner
	dial     func(ctx context.Context, addr string) (net.Conn, error)
}

func main() {
	ctx, stop := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	code := run(ctx, os.Args[1:], &env{
		stdout:   os.Stdout,
		stderr:   os.Stderr,
		stdin:    os.Stdin,
		getenv:   os.Getenv,
		lookPath: exec.LookPath,
		newADB:   func(path string) adb.Runner { return adb.Exec{Path: path} },
		dial: func(ctx context.Context, addr string) (net.Conn, error) {
			var d net.Dialer
			return d.DialContext(ctx, "tcp", addr)
		},
	})
	stop()
	os.Exit(code)
}

type exitError struct {
	code int
	msg  string
}

func (e *exitError) Error() string { return e.msg }

func fail(code int, format string, a ...any) error {
	return &exitError{code: code, msg: fmt.Sprintf(format, a...)}
}

type options struct {
	serial, pkg, adbPath string
	json, version, help  bool
	prune                bool
	input, inputFile     string
	inputSet, fileSet    bool
	since, until         string
	limit                int
	timeout              time.Duration
	port                 string
	open, allowActions   bool
	args                 []string
}

var valueFlags = map[string]bool{
	"s": true, "p": true, "adb": true, "input": true, "input-file": true,
	"since": true, "limit": true, "timeout": true, "until": true, "port": true,
}

func parseArgs(args []string) (*options, error) {
	o := &options{timeout: 30 * time.Second, port: "7070"}
	for i := 0; i < len(args); i++ {
		a := args[i]
		if a == "--" {
			o.args = append(o.args, args[i+1:]...)
			break
		}
		if len(a) < 2 || a[0] != '-' {
			o.args = append(o.args, a)
			continue
		}
		name, val, hasVal := strings.Cut(strings.TrimLeft(a, "-"), "=")
		if valueFlags[name] && !hasVal {
			if i+1 >= len(args) {
				return nil, fail(exitUsage, "flag %s needs a value", a)
			}
			i++
			val = args[i]
		}
		switch name {
		case "s":
			o.serial = val
		case "p":
			o.pkg = val
		case "adb":
			o.adbPath = val
		case "input":
			o.input, o.inputSet = val, true
		case "input-file":
			o.inputFile, o.fileSet = val, true
		case "since":
			o.since = val
		case "until":
			o.until = val
		case "limit":
			n, err := strconv.Atoi(val)
			if err != nil || n <= 0 {
				return nil, fail(exitUsage, "--limit must be a positive integer")
			}
			o.limit = n
		case "timeout":
			d, err := time.ParseDuration(val)
			if err != nil || d < 0 {
				return nil, fail(exitUsage, "--timeout must be a duration such as 30s or 2m (0 disables it)")
			}
			o.timeout = d
		case "port":
			o.port = val
		case "open":
			o.open = true
		case "allow-actions":
			o.allowActions = true
		case "json":
			o.json = true
		case "prune":
			o.prune = true
		case "version":
			o.version = true
		case "h", "help":
			o.help = true
		default:
			return nil, fail(exitUsage, "unknown flag %s", a)
		}
	}
	return o, nil
}

func run(ctx context.Context, args []string, e *env) int {
	err := dispatch(ctx, args, e)
	if err == nil {
		return exitOK
	}
	code, msg := classify(ctx, err)
	if msg != "" {
		fmt.Fprintln(e.stderr, "probe:", msg)
	}
	if code == exitUsage {
		var ee *exitError
		if errors.As(err, &ee) && strings.HasPrefix(ee.msg, "usage") {
			fmt.Fprint(e.stderr, usageText)
		}
	}
	return code
}

func classify(ctx context.Context, err error) (int, string) {
	var ee *exitError
	var ae *proto.AppError
	var ce *proto.ConnError
	switch {
	case errors.As(err, &ee):
		return ee.code, ee.msg
	case errors.As(err, &ae):
		if ae.Code == "TIMEOUT" {
			return exitTimeout, ae.Error()
		}
		return exitApp, ae.Error()
	case errors.As(err, &ce):
		return exitConn, ce.Error()
	case ctx.Err() != nil:
		return exitInterrupted, "interrupted"
	default:
		return exitConn, err.Error()
	}
}

func dispatch(ctx context.Context, args []string, e *env) error {
	o, err := parseArgs(args)
	if err != nil {
		return err
	}
	if o.version {
		fmt.Fprintln(e.stdout, version)
		return nil
	}
	if o.help {
		fmt.Fprint(e.stdout, usageText)
		return nil
	}
	if len(o.args) == 0 {
		return fail(exitUsage, "usage: no command given")
	}
	cmd, rest := o.args[0], o.args[1:]
	wantArgs := map[string]int{"ui": 0, "devices": 0, "list": 0, "cursor": 0, "describe": 1, "call": 1, "watch": 1}
	n, known := wantArgs[cmd]
	if !known {
		return fail(exitUsage, "usage: unknown command %q", cmd)
	}
	if len(rest) != n {
		return fail(exitUsage, "usage: %s takes %d argument(s), got %d", cmd, n, len(rest))
	}
	if cmd == "watch" && o.until != "" {
		// Compile before connecting so a bad expression never touches the device.
		if _, err := watch.Compile(o.until); err != nil {
			return fail(exitUsage, "--until: %v", err)
		}
	}
	var input json.RawMessage
	if cmd == "call" || cmd == "watch" {
		if input, err = readInput(o, e.stdin); err != nil {
			return err
		}
	}
	runner, err := resolveADB(o.adbPath, e)
	if err != nil {
		return err
	}
	a := adb.Client{Runner: e.newADB(runner)}
	if cmd == "devices" {
		return devices(ctx, a, o, e)
	}
	if cmd == "ui" {
		return serveUI(ctx, a, o, e)
	}

	s, err := connect(ctx, a, o, e)
	if err != nil {
		return err
	}
	defer s.close()

	if o.since != "" && (cmd == "call" || cmd == "watch") {
		if input, err = s.dropUndeclaredSince(ctx, rest[0], input, e); err != nil {
			return err
		}
	}

	switch cmd {
	case "cursor":
		fmt.Fprintln(e.stdout, s.hello.HeadCursor)
		return nil
	case "list":
		return s.callAndPrint(ctx, "list", "", nil, e)
	case "describe":
		return s.callAndPrint(ctx, "describe", rest[0], nil, e)
	case "call":
		return s.callAndPrint(ctx, "call", rest[0], input, e)
	default:
		return s.watch(ctx, rest[0], input, o, e)
	}
}

func resolveADB(flagPath string, e *env) (string, error) {
	if flagPath != "" {
		return flagPath, nil
	}
	if p, err := e.lookPath("adb"); err == nil {
		return p, nil
	}
	for _, root := range []string{e.getenv("ANDROID_HOME"), e.getenv("ANDROID_SDK_ROOT")} {
		if root == "" {
			continue
		}
		p := filepath.Join(root, "platform-tools", "adb")
		if _, err := os.Stat(p); err == nil {
			return p, nil
		}
	}
	return "", fail(exitConn, "adb not found: put it on PATH, set ANDROID_HOME or pass --adb")
}

func readInput(o *options, stdin io.Reader) (json.RawMessage, error) {
	if o.inputSet && o.fileSet {
		return nil, fail(exitUsage, "usage: pass --input or --input-file, not both")
	}
	var raw []byte
	switch {
	case o.inputSet:
		raw = []byte(o.input)
	case o.fileSet:
		var err error
		if o.inputFile == "-" {
			raw, err = io.ReadAll(stdin)
		} else {
			raw, err = os.ReadFile(o.inputFile)
		}
		if err != nil {
			return nil, fail(exitUsage, "--input-file: %v", err)
		}
	}
	obj := map[string]json.RawMessage{}
	if len(bytes.TrimSpace(raw)) > 0 {
		if err := json.Unmarshal(raw, &obj); err != nil || obj == nil {
			return nil, fail(exitUsage, "input must be a JSON object: %s", strings.TrimSpace(string(raw)))
		}
	}
	if o.since != "" {
		obj["since"], _ = json.Marshal(o.since)
	}
	if len(obj) == 0 {
		return nil, nil
	}
	return json.Marshal(obj)
}

func pickSerial(ctx context.Context, a adb.Client, o *options, e *env) (string, error) {
	if o.serial != "" {
		return o.serial, nil
	}
	if s := e.getenv("ANDROID_SERIAL"); s != "" {
		return s, nil
	}
	ds, err := a.Devices(ctx)
	if err != nil {
		return "", fail(exitConn, "%v", err)
	}
	var serials []string
	for _, d := range ds {
		if d.State == "device" {
			serials = append(serials, d.Serial)
		}
	}
	switch len(serials) {
	case 0:
		return "", fail(exitConn, "no device connected (see `adb devices`)")
	case 1:
		return serials[0], nil
	}
	var b strings.Builder
	b.WriteString("several devices; pass -s SERIAL or set ANDROID_SERIAL. Candidates:")
	for _, s := range serials {
		b.WriteString("\n  " + s)
	}
	for _, p := range adb.SameDevicePairs(serials) {
		fmt.Fprintf(&b, "\nnote: %s and %s are the same physical device (USB and wireless debugging); either works", p[0], p[1])
	}
	return "", fail(exitUsage, "%s", b.String())
}

func pickPackage(ctx context.Context, a adb.Client, serial string, o *options) (string, error) {
	if o.pkg != "" {
		return o.pkg, nil
	}
	pkgs, err := a.ProbePackages(ctx, serial)
	if err != nil {
		return "", fail(exitConn, "cannot read /proc/net/unix on %s (%v); pass -p PACKAGE", serial, err)
	}
	switch len(pkgs) {
	case 0:
		return "", fail(exitConn, "no app with a probe socket found on %s; start a debug build with the SDK, or pass -p PACKAGE", serial)
	case 1:
		return pkgs[0], nil
	}
	return "", fail(exitUsage, "several apps with a probe socket on %s; pass -p PACKAGE. Candidates:\n  %s", serial, strings.Join(pkgs, "\n  "))
}

type session struct {
	client *proto.Client
	hello  proto.Hello
	close  func()
}

func connect(ctx context.Context, a adb.Client, o *options, e *env) (*session, error) {
	serial, err := pickSerial(ctx, a, o, e)
	if err != nil {
		return nil, err
	}
	pkg, err := pickPackage(ctx, a, serial, o)
	if err != nil {
		return nil, err
	}
	port, err := a.Forward(ctx, serial, pkg)
	if err != nil {
		return nil, fail(exitConn, "%v", err)
	}
	removeForward := func() {
		// The signal context may already be cancelled; cleanup must still run.
		cctx, cancel := context.WithTimeout(context.Background(), cleanupTimeout)
		defer cancel()
		if err := a.RemoveForward(cctx, serial, port); err != nil {
			fmt.Fprintf(e.stderr, "probe: warning: could not remove forward tcp:%d: %v\n", port, err)
		}
	}
	conn, err := e.dial(ctx, "127.0.0.1:"+strconv.Itoa(port))
	if err != nil {
		removeForward()
		return nil, fail(exitConn, "connect to %s on %s: %v", pkg, serial, err)
	}
	client := proto.NewClient(conn)
	s := &session{client: client, close: func() { client.Close(); removeForward() }}

	hctx, cancel := context.WithTimeout(ctx, helloTimeout)
	defer cancel()
	h, err := client.Hello(hctx)
	if err != nil {
		s.close()
		if ctx.Err() != nil {
			return nil, ctx.Err()
		}
		return nil, fail(exitConn, "no hello from %s on %s (%v); is the app running a debug build with the SDK?", pkg, serial, err)
	}
	if proto.Major(h.Protocol) != proto.Major(proto.Version) {
		s.close()
		return nil, fail(exitConn, "protocol mismatch: app speaks %s, this CLI speaks %s", h.Protocol, proto.Version)
	}
	s.hello = h
	return s, nil
}

// dropUndeclaredSince removes the --since value for commands whose input schema
// has no `since`: the SDK rejects unknown input fields.
func (s *session) dropUndeclaredSince(ctx context.Context, command string, input json.RawMessage, e *env) (json.RawMessage, error) {
	cctx, cancel := context.WithTimeout(ctx, callTimeout)
	defer cancel()
	data, err := s.client.Call(cctx, "describe", command, nil)
	if err != nil {
		return nil, err
	}
	var d struct {
		Input struct {
			Properties map[string]json.RawMessage `json:"properties"`
		} `json:"input"`
	}
	if json.Unmarshal(data, &d) == nil {
		if _, ok := d.Input.Properties["since"]; ok {
			return input, nil
		}
	}
	fmt.Fprintf(e.stderr, "probe: warning: %s does not take since; ignoring --since\n", command)
	obj := map[string]json.RawMessage{}
	json.Unmarshal(input, &obj)
	delete(obj, "since")
	if len(obj) == 0 {
		return nil, nil
	}
	return json.Marshal(obj)
}

func (s *session) callAndPrint(ctx context.Context, op, command string, input json.RawMessage, e *env) error {
	cctx, cancel := context.WithTimeout(ctx, callTimeout)
	defer cancel()
	data, err := s.client.Call(cctx, op, command, input)
	if err != nil {
		if errors.Is(err, context.DeadlineExceeded) && ctx.Err() == nil {
			return fail(exitTimeout, "no response within %s", callTimeout)
		}
		return err
	}
	return printJSON(e.stdout, data)
}

func (s *session) watch(ctx context.Context, command string, input json.RawMessage, o *options, e *env) error {
	var until *watch.Until
	if o.until != "" {
		until, _ = watch.Compile(o.until)
	}
	wctx, cancel := ctx, context.CancelFunc(func() {})
	if o.timeout > 0 {
		wctx, cancel = context.WithTimeout(ctx, o.timeout)
	}
	defer cancel()

	st, err := s.client.Watch(command, input)
	if err != nil {
		return err
	}
	stopStream := func() {
		cctx, cancel := context.WithTimeout(context.Background(), cancelTimeout)
		defer cancel()
		st.Cancel(cctx)
	}
	warned := false
	for count := 0; ; {
		data, err := st.Next(wctx)
		switch {
		case errors.Is(err, proto.ErrEnd):
			if until != nil {
				return fail(exitTimeout, "stream ended before --until matched")
			}
			return nil
		case err != nil:
			if wctx.Err() != nil {
				stopStream()
				if ctx.Err() == nil {
					return fail(exitTimeout, "timed out after %s", o.timeout)
				}
			}
			return err
		}
		if err := printJSON(e.stdout, data); err != nil {
			stopStream()
			return err
		}
		count++
		if until != nil {
			ok, merr := until.Match(data)
			if merr != nil && !warned {
				fmt.Fprintf(e.stderr, "probe: warning: --until: %v\n", merr)
				warned = true
			}
			if ok {
				stopStream()
				return nil
			}
		}
		if o.limit > 0 && count >= o.limit {
			stopStream()
			if until != nil {
				return fail(exitTimeout, "--limit %d reached before --until matched", o.limit)
			}
			return nil
		}
	}
}

func printJSON(w io.Writer, data json.RawMessage) error {
	var buf bytes.Buffer
	if len(data) == 0 {
		data = json.RawMessage("null")
	}
	if err := json.Compact(&buf, data); err != nil {
		return &proto.ConnError{Err: fmt.Errorf("malformed data: %v", err)}
	}
	buf.WriteByte('\n')
	_, err := w.Write(buf.Bytes())
	return err
}

func devices(ctx context.Context, a adb.Client, o *options, e *env) error {
	ds, err := a.Devices(ctx)
	if err != nil {
		return fail(exitConn, "%v", err)
	}
	// Pruning is opt-in: a forward may belong to a probe still running elsewhere.
	if fs, err := a.Forwards(ctx); err == nil {
		stale := 0
		for _, f := range fs {
			if !strings.HasPrefix(f.Remote, "localabstract:") || !strings.HasSuffix(f.Remote, adb.SocketSuffix) {
				continue
			}
			port, perr := strconv.Atoi(strings.TrimPrefix(f.Local, "tcp:"))
			if perr != nil {
				continue
			}
			if !o.prune {
				stale++
			} else if err := a.RemoveForward(ctx, f.Serial, port); err == nil {
				fmt.Fprintf(e.stderr, "probe: removed forward %s %s -> %s\n", f.Serial, f.Local, f.Remote)
			}
		}
		if stale > 0 {
			fmt.Fprintf(e.stderr, "probe: %d probe forward(s) exist, possibly left by killed runs; `probe devices --prune` removes them\n", stale)
		}
	}
	if o.json {
		if ds == nil {
			ds = []adb.Device{}
		}
		out, _ := json.Marshal(ds)
		return printJSON(e.stdout, out)
	}
	for _, d := range ds {
		fmt.Fprintf(e.stdout, "%s\t%s\n", d.Serial, d.State)
	}
	return nil
}
