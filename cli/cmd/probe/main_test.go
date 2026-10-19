package main

import (
	"bufio"
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"net"
	"strconv"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/DongChyeon/droidprobe/cli/internal/adb"
)

type fakeADB struct {
	mu       sync.Mutex
	devices  string
	procUnix string
	procErr  error
	forwards string
	port     int
	calls    []string
}

func (f *fakeADB) Run(_ context.Context, args ...string) ([]byte, error) {
	f.mu.Lock()
	defer f.mu.Unlock()
	joined := strings.Join(args, " ")
	f.calls = append(f.calls, joined)
	switch {
	case joined == "devices":
		return []byte(f.devices), nil
	case strings.HasSuffix(joined, "shell cat /proc/net/unix"):
		return []byte(f.procUnix), f.procErr
	case strings.Contains(joined, "forward tcp:0"):
		return []byte(strconv.Itoa(f.port) + "\n"), nil
	case joined == "forward --list":
		return []byte(f.forwards), nil
	}
	return nil, nil
}

func (f *fakeADB) called(sub string) bool {
	f.mu.Lock()
	defer f.mu.Unlock()
	for _, c := range f.calls {
		if strings.Contains(c, sub) {
			return true
		}
	}
	return false
}

type wireReq struct {
	ID       string          `json:"id"`
	Op       string          `json:"op"`
	Protocol string          `json:"protocol"`
	Command  string          `json:"command"`
	Input    json.RawMessage `json:"input"`
	Target   string          `json:"target"`
}

// fakeApp speaks docs/protocol.md on a loopback listener.
type fakeApp struct {
	t        *testing.T
	ln       net.Listener
	protocol string
	noHello  bool
	// result returns data or an error code for call/list/describe.
	result func(req wireReq) (any, string)
	// events are sent for any watch; tail is "end", "error:<CODE>", "close" or "" (wait for cancel).
	events []any
	tail   string
	// noSince makes describe report an input schema without `since`.
	noSince bool

	mu       sync.Mutex
	requests []wireReq
	cancels  []string
	psid     string
	conns    []net.Conn
}

// newFakeApp applies configure before serving so the fixture is never mutated concurrently.
func newFakeApp(t *testing.T, configure func(*fakeApp)) *fakeApp {
	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	a := &fakeApp{t: t, ln: ln, protocol: "1.0", psid: "a1f3c09e"}
	if configure != nil {
		configure(a)
	}
	t.Cleanup(func() { ln.Close() })
	go func() {
		for {
			c, err := ln.Accept()
			if err != nil {
				return
			}
			go a.serve(c)
		}
	}()
	return a
}

func (a *fakeApp) port() int { return a.ln.Addr().(*net.TCPAddr).Port }

// restart drops every connection, as a killed app would, and comes back as a new process.
func (a *fakeApp) restart(psid string) {
	a.mu.Lock()
	defer a.mu.Unlock()
	a.psid = psid
	for _, c := range a.conns {
		c.Close()
	}
	a.conns = nil
}

func (a *fakeApp) serve(c net.Conn) {
	defer c.Close()
	a.mu.Lock()
	a.conns = append(a.conns, c)
	psid := a.psid
	a.mu.Unlock()
	if a.noHello {
		return
	}
	var wmu sync.Mutex
	send := func(v any) {
		b, _ := json.Marshal(v)
		wmu.Lock()
		c.Write(append(b, '\n'))
		wmu.Unlock()
	}
	stops := map[string]chan struct{}{}
	var smu sync.Mutex
	r := bufio.NewReader(c)
	for {
		line, err := r.ReadBytes('\n')
		if err != nil {
			return
		}
		var req wireReq
		json.Unmarshal(line, &req)
		a.mu.Lock()
		a.requests = append(a.requests, req)
		a.mu.Unlock()
		switch req.Op {
		case "hello":
			send(map[string]any{"id": req.ID, "type": "result", "data": map[string]any{
				"protocol": a.protocol, "sdk": "0.1.0", "package": "dev.droidprobe.sample", "pid": 1,
				"processStartId": psid, "headCursor": psid + ":7"}})
		case "cancel":
			a.mu.Lock()
			a.cancels = append(a.cancels, req.Target)
			a.mu.Unlock()
			smu.Lock()
			if ch := stops[req.Target]; ch != nil {
				close(ch)
				delete(stops, req.Target)
			}
			smu.Unlock()
			send(map[string]any{"id": req.ID, "type": "result", "data": map[string]any{}})
		case "watch":
			stop := make(chan struct{})
			smu.Lock()
			stops[req.ID] = stop
			smu.Unlock()
			go func(id string) {
				for _, ev := range a.events {
					select {
					case <-stop:
						send(map[string]any{"id": id, "type": "end"})
						return
					default:
					}
					send(map[string]any{"id": id, "type": "event", "data": ev})
				}
				switch {
				case a.tail == "end":
					send(map[string]any{"id": id, "type": "end"})
				case strings.HasPrefix(a.tail, "error:"):
					send(map[string]any{"id": id, "type": "error", "error": map[string]any{"code": strings.TrimPrefix(a.tail, "error:"), "message": "boom"}})
				case a.tail == "close":
					c.Close()
				default:
					<-stop
					send(map[string]any{"id": id, "type": "end"})
				}
			}(req.ID)
		case "describe":
			props := map[string]any{"since": map[string]any{"type": "string"}}
			if a.noSince {
				props = map[string]any{}
			}
			send(map[string]any{"id": req.ID, "type": "result", "data": map[string]any{
				"name": req.Command, "kind": "call", "input": map[string]any{"type": "object", "properties": props}}})
		default:
			data, code := any(nil), ""
			if a.result != nil {
				data, code = a.result(req)
			}
			if code != "" {
				send(map[string]any{"id": req.ID, "type": "error", "error": map[string]any{"code": code, "message": "boom"}})
			} else {
				send(map[string]any{"id": req.ID, "type": "result", "data": data})
			}
		}
	}
}

func (a *fakeApp) cancelCount() int {
	a.mu.Lock()
	defer a.mu.Unlock()
	return len(a.cancels)
}

func (a *fakeApp) lastRequest(op string) wireReq {
	a.mu.Lock()
	defer a.mu.Unlock()
	for i := len(a.requests) - 1; i >= 0; i-- {
		if a.requests[i].Op == op {
			return a.requests[i]
		}
	}
	return wireReq{}
}

const oneDevice = "List of devices attached\nemulator-5554\tdevice\n"
const sampleUnix = "Num       RefCount Protocol Flags    Type St Inode Path\n" +
	"0000000000000000: 00000002 00000000 00010000 0001 01 12345 @dev.droidprobe.sample.probe\n" +
	"0000000000000000: 00000002 00000000 00010000 0001 01 12346 @jdwp-control\n"

type result struct {
	code           int
	stdout, stderr string
}

func runWith(t *testing.T, f *fakeADB, args ...string) result {
	return runCtx(context.Background(), t, f, args...)
}

func runCtx(ctx context.Context, t *testing.T, f *fakeADB, args ...string) result {
	t.Helper()
	var out, errb bytes.Buffer
	code := run(ctx, args, &env{
		stdout:   &out,
		stderr:   &errb,
		stdin:    strings.NewReader(""),
		getenv:   func(string) string { return "" },
		lookPath: func(string) (string, error) { return "/fake/adb", nil },
		newADB:   func(string) adb.Runner { return f },
		dial: func(ctx context.Context, addr string) (net.Conn, error) {
			var d net.Dialer
			return d.DialContext(ctx, "tcp", addr)
		},
	})
	return result{code, out.String(), errb.String()}
}

func setup(t *testing.T, configure func(*fakeApp)) (*fakeADB, *fakeApp) {
	app := newFakeApp(t, configure)
	return &fakeADB{devices: oneDevice, procUnix: sampleUnix, port: app.port()}, app
}

func assertRemoved(t *testing.T, f *fakeADB, port int) {
	t.Helper()
	if !f.called("forward --remove tcp:" + strconv.Itoa(port)) {
		t.Fatalf("forward tcp:%d was not removed; calls: %v", port, f.calls)
	}
}

func TestTwoDevicesNeedSerial(t *testing.T) {
	f, app := setup(t, func(a *fakeApp) {
		a.result = func(wireReq) (any, string) { return map[string]any{"commands": []any{}}, "" }
	})
	f.devices = "List of devices attached\nemulator-5554\tdevice\nR58M\tdevice\n"

	r := runWith(t, f, "list")
	if r.code != exitUsage || !strings.Contains(r.stderr, "emulator-5554") || !strings.Contains(r.stderr, "R58M") {
		t.Fatalf("got %d, stderr %q", r.code, r.stderr)
	}
	if f.called("forward") {
		t.Fatal("forwarded despite ambiguity")
	}

	r = runWith(t, f, "-s", "R58M", "list")
	if r.code != exitOK || r.stdout != "{\"commands\":[]}\n" {
		t.Fatalf("got %d, stdout %q, stderr %q", r.code, r.stdout, r.stderr)
	}
	if !f.called("-s R58M forward tcp:0 localabstract:dev.droidprobe.sample.probe") {
		t.Fatalf("calls: %v", f.calls)
	}
	assertRemoved(t, f, app.port())
}

func TestDuplicateWirelessSerialIsExplained(t *testing.T) {
	f, _ := setup(t, nil)
	f.devices = "List of devices attached\nR5CT10ABCDE\tdevice\nadb-R5CT10ABCDE-7i8s2x._adb-tls-connect._tcp\tdevice\n"
	r := runWith(t, f, "list")
	if r.code != exitUsage || !strings.Contains(r.stderr, "same physical device") {
		t.Fatalf("got %d, stderr %q", r.code, r.stderr)
	}
}

func TestPackageDiscovery(t *testing.T) {
	f, _ := setup(t, nil)
	f.procUnix = sampleUnix + "0: 2 0 10000 1 1 9 @com.other.probe\n"
	if r := runWith(t, f, "list"); r.code != exitUsage || !strings.Contains(r.stderr, "com.other") {
		t.Fatalf("several apps: %d %q", r.code, r.stderr)
	}
	f.procUnix = ""
	if r := runWith(t, f, "list"); r.code != exitConn || !strings.Contains(r.stderr, "-p") {
		t.Fatalf("no app: %d %q", r.code, r.stderr)
	}
	f.procErr = errors.New("permission denied")
	if r := runWith(t, f, "list"); r.code != exitConn || !strings.Contains(r.stderr, "-p") {
		t.Fatalf("unreadable: %d %q", r.code, r.stderr)
	}
}

func TestHelloFailures(t *testing.T) {
	f, app := setup(t, func(a *fakeApp) {
		a.protocol = "2.0"
	})
	if r := runWith(t, f, "list"); r.code != exitConn || !strings.Contains(r.stderr, "protocol mismatch") {
		t.Fatalf("mismatch: %d %q", r.code, r.stderr)
	}
	assertRemoved(t, f, app.port())

	f2, app2 := setup(t, func(a *fakeApp) {
		a.noHello = true
	})
	if r := runWith(t, f2, "cursor"); r.code != exitConn {
		t.Fatalf("closed before hello: %d %q", r.code, r.stderr)
	}
	assertRemoved(t, f2, app2.port())
}

func TestMinorVersionIsCompatible(t *testing.T) {
	f, _ := setup(t, func(a *fakeApp) {
		a.protocol = "1.7"
	})
	if r := runWith(t, f, "cursor"); r.code != exitOK || r.stdout != "a1f3c09e:7\n" {
		t.Fatalf("got %d %q %q", r.code, r.stdout, r.stderr)
	}
}

func TestCallPrintsCompactDataAndMergesSince(t *testing.T) {
	f, app := setup(t, func(a *fakeApp) {
		a.result = func(req wireReq) (any, string) {
			var in map[string]any
			json.Unmarshal(req.Input, &in)
			return in, ""
		}
	})
	r := runWith(t, f, "call", "echo", "--input", `{ "x": 1 }`, "--since", "a1f3c09e:3")
	if r.code != exitOK || r.stdout != `{"since":"a1f3c09e:3","x":1}`+"\n" {
		t.Fatalf("got %d %q %q", r.code, r.stdout, r.stderr)
	}
	if req := app.lastRequest("call"); req.Command != "echo" {
		t.Fatalf("request %+v", req)
	}
	assertRemoved(t, f, app.port())
}

func TestCallErrorCodes(t *testing.T) {
	cases := map[string]int{
		"UNKNOWN_COMMAND": exitApp, "INVALID_INPUT": exitApp, "STALE_CURSOR": exitApp,
		"CURSOR_EVICTED": exitApp, "MOCK_REJECTED": exitApp, "SETUP_FAILED": exitApp,
		"INTERNAL": exitApp, "BAD_REQUEST": exitApp, "TIMEOUT": exitTimeout,
	}
	for code, want := range cases {
		f, app := setup(t, func(a *fakeApp) {
			a.result = func(wireReq) (any, string) { return nil, code }
		})
		r := runWith(t, f, "call", "x")
		if r.code != want || !strings.Contains(r.stderr, code) || r.stdout != "" {
			t.Errorf("%s: got %d %q", code, r.code, r.stderr)
		}
		assertRemoved(t, f, app.port())
	}
}

func TestUsageErrorsNeverTouchADB(t *testing.T) {
	for _, args := range [][]string{
		{"call", "x", "--input", "{bad"},
		{"call", "x", "--input", "[1]"},
		{"call", "x", "--input", "{}", "--input-file", "f"},
		{"watch", "x", "--until", ".a ==="},
		{"call"},
		{"bogus"},
		{"watch", "x", "--limit", "0"},
		{"list", "--nope"},
	} {
		f := &fakeADB{devices: oneDevice}
		if r := runWith(t, f, args...); r.code != exitUsage {
			t.Errorf("%v: got %d %q", args, r.code, r.stderr)
		}
		if len(f.calls) != 0 {
			t.Errorf("%v: adb called: %v", args, f.calls)
		}
	}
}

func events(n int) []any {
	var evs []any
	for i := 1; i <= n; i++ {
		evs = append(evs, map[string]any{"n": i})
	}
	return evs
}

func TestWatchUntilMatches(t *testing.T) {
	f, app := setup(t, func(a *fakeApp) {
		a.events = events(5)
	})
	r := runWith(t, f, "watch", "events.watch", "--until", ".n == 3")
	if r.code != exitOK || r.stdout != "{\"n\":1}\n{\"n\":2}\n{\"n\":3}\n" {
		t.Fatalf("got %d %q %q", r.code, r.stdout, r.stderr)
	}
	if app.cancelCount() != 1 {
		t.Fatalf("cancels: %d", app.cancelCount())
	}
	if req := app.lastRequest("cancel"); req.Target != app.lastRequest("watch").ID {
		t.Fatalf("cancel target %q", req.Target)
	}
	assertRemoved(t, f, app.port())
}

func TestWatchLimit(t *testing.T) {
	f, app := setup(t, func(a *fakeApp) {
		a.events = events(5)
	})
	r := runWith(t, f, "watch", "events.watch", "--limit", "2")
	if r.code != exitOK || strings.Count(r.stdout, "\n") != 2 || app.cancelCount() != 1 {
		t.Fatalf("got %d %q cancels %d", r.code, r.stdout, app.cancelCount())
	}
	assertRemoved(t, f, app.port())
}

func TestWatchUntilUnmetAtLimit(t *testing.T) {
	f, app := setup(t, func(a *fakeApp) {
		a.events = events(5)
	})
	r := runWith(t, f, "watch", "events.watch", "--limit", "2", "--until", ".n == 9")
	if r.code != exitTimeout || app.cancelCount() != 1 {
		t.Fatalf("got %d %q cancels %d", r.code, r.stderr, app.cancelCount())
	}
	assertRemoved(t, f, app.port())
}

func TestWatchUntilUnmetAtEnd(t *testing.T) {
	f, _ := setup(t, func(a *fakeApp) {
		a.events, a.tail = events(2), "end"
	})
	if r := runWith(t, f, "watch", "events.watch", "--until", ".n == 9"); r.code != exitTimeout {
		t.Fatalf("got %d %q", r.code, r.stderr)
	}
	f2, _ := setup(t, func(a *fakeApp) {
		a.events, a.tail = events(2), "end"
	})
	if r := runWith(t, f2, "watch", "events.watch"); r.code != exitOK || strings.Count(r.stdout, "\n") != 2 {
		t.Fatalf("plain end: %d %q", r.code, r.stdout)
	}
}

func TestWatchTimeout(t *testing.T) {
	f, app := setup(t, func(a *fakeApp) {
		a.events = events(1)
	})
	start := time.Now()
	r := runWith(t, f, "watch", "events.watch", "--timeout", "200ms", "--until", ".n == 9")
	if r.code != exitTimeout || time.Since(start) > 3*time.Second {
		t.Fatalf("got %d %q after %s", r.code, r.stderr, time.Since(start))
	}
	if app.cancelCount() != 1 {
		t.Fatalf("cancels: %d", app.cancelCount())
	}
	assertRemoved(t, f, app.port())
}

func TestWatchStreamErrorAndDisconnect(t *testing.T) {
	f, app := setup(t, func(a *fakeApp) {
		a.tail = "error:STALE_CURSOR"
	})
	if r := runWith(t, f, "watch", "events.watch", "--since", "dead:1"); r.code != exitApp {
		t.Fatalf("stream error: %d %q", r.code, r.stderr)
	}
	if in := string(app.lastRequest("watch").Input); in != `{"since":"dead:1"}` {
		t.Fatalf("input %s", in)
	}
	f2, app2 := setup(t, func(a *fakeApp) {
		a.events, a.tail = events(1), "close"
	})
	if r := runWith(t, f2, "watch", "events.watch"); r.code != exitConn {
		t.Fatalf("disconnect: %d %q", r.code, r.stderr)
	}
	assertRemoved(t, f2, app2.port())
}

func TestInterruptRemovesForward(t *testing.T) {
	f, app := setup(t, nil)
	ctx, cancel := context.WithCancel(context.Background())
	time.AfterFunc(200*time.Millisecond, cancel)
	r := runCtx(ctx, t, f, "watch", "events.watch", "--timeout", "0")
	if r.code != exitInterrupted {
		t.Fatalf("got %d %q", r.code, r.stderr)
	}
	if app.cancelCount() != 1 {
		t.Fatalf("cancels: %d", app.cancelCount())
	}
	assertRemoved(t, f, app.port())
}

func TestDevicesReportsForwardsAndPrunesOnRequest(t *testing.T) {
	forwards := "emulator-5554 tcp:5001 localabstract:dev.droidprobe.sample.probe\nemulator-5554 tcp:5002 tcp:8080\n"
	f := &fakeADB{devices: oneDevice, forwards: forwards}
	r := runWith(t, f, "devices", "--json")
	if r.code != exitOK || r.stdout != `[{"serial":"emulator-5554","state":"device"}]`+"\n" {
		t.Fatalf("got %d %q", r.code, r.stdout)
	}
	if f.called("--remove") || !strings.Contains(r.stderr, "1 probe forward(s)") {
		t.Fatalf("default run: calls %v, stderr %q", f.calls, r.stderr)
	}

	f = &fakeADB{devices: oneDevice, forwards: forwards}
	if r := runWith(t, f, "devices", "--prune"); r.code != exitOK || r.stdout != "emulator-5554\tdevice\n" {
		t.Fatalf("prune: %d %q", r.code, r.stdout)
	}
	if !f.called("-s emulator-5554 forward --remove tcp:5001") || f.called("tcp:5002") {
		t.Fatalf("calls: %v", f.calls)
	}
}

func TestSinceDroppedWhenCommandLacksIt(t *testing.T) {
	f, app := setup(t, func(a *fakeApp) {
		a.noSince = true
		a.result = func(wireReq) (any, string) { return map[string]any{}, "" }
	})
	r := runWith(t, f, "call", "checkout.state", "--input", `{"x":1}`, "--since", "a1f3c09e:3")
	if r.code != exitOK || !strings.Contains(r.stderr, "ignoring --since") {
		t.Fatalf("got %d %q", r.code, r.stderr)
	}
	if in := string(app.lastRequest("call").Input); in != `{"x":1}` {
		t.Fatalf("input %s", in)
	}
}
