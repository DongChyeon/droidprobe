package main

import (
	"bufio"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"net"
	"net/http"
	"os/exec"
	"runtime"
	"strings"
	"syscall"
	"time"

	"github.com/DongChyeon/droidprobe/cli/internal/adb"
	"github.com/DongChyeon/droidprobe/cli/internal/ui"
)

// serveUI runs the local dashboard until interrupted; stopping it is the normal way out, so it exits 0.
func serveUI(ctx context.Context, a adb.Client, o *options, e *env) error {
	addr := o.port
	if !strings.Contains(addr, ":") {
		addr = "127.0.0.1:" + addr
	}
	ln, err := ui.Listen(addr)
	if err != nil {
		if errors.Is(err, syscall.EADDRINUSE) {
			if running := runningUI(addr); running != "" {
				return fail(exitUsage, "probe ui is already running at http://%s (%s); open it, stop it, or pass --port", addr, running)
			}
			return fail(exitUsage, "%s is in use; pass --port", addr)
		}
		return fail(exitUsage, "--port: %v", err)
	}
	defer ln.Close()

	serial, err := pickSerial(ctx, a, o, e)
	if err != nil {
		return err
	}
	o.serial = serial
	if o.pkg == "" {
		// With no app running yet, keep retrying discovery; several candidates need -p now.
		if _, err := pickPackage(ctx, a, serial, o); err != nil {
			var ee *exitError
			if errors.As(err, &ee) && ee.code == exitUsage {
				return err
			}
		}
	}

	logf := func(format string, args ...any) { fmt.Fprintf(e.stderr, "probe ui: "+format+"\n", args...) }
	srv := ui.New(ui.Options{
		Serial:       serial,
		AllowActions: o.allowActions,
		Logf:         logf,
		Dial: func(ctx context.Context) (*ui.Conn, error) {
			s, err := connect(ctx, a, o, e)
			if err != nil {
				return nil, err
			}
			if o.pkg == "" {
				o.pkg = s.hello.Package // stay on this app across restarts
			}
			return &ui.Conn{Client: s.client, Hello: s.hello, Close: s.close}, nil
		},
	})

	url := "http://" + ln.Addr().(*net.TCPAddr).String()
	line, _ := json.Marshal(map[string]any{"url": url, "allowActions": o.allowActions})
	fmt.Fprintln(e.stdout, string(line))
	mode := "read-only; pass --allow-actions to enable state-changing commands"
	if o.allowActions {
		mode = "actions enabled"
	}
	logf("serving %s (%s); Ctrl-C stops", url, mode)
	if o.open {
		openBrowser(url, logf)
	}
	return srv.Serve(ctx, ln)
}

// runningUI describes the probe ui already serving addr, or returns "" if something else holds the port.
func runningUI(addr string) string {
	client := &http.Client{Timeout: time.Second}
	resp, err := client.Get("http://" + addr + "/api/status")
	if err != nil {
		return ""
	}
	defer resp.Body.Close()
	sc := bufio.NewScanner(resp.Body)
	for sc.Scan() {
		data, ok := strings.CutPrefix(sc.Text(), "data: ")
		if !ok {
			continue
		}
		var s struct {
			Serial       string `json:"serial"`
			Package      string `json:"package"`
			AllowActions bool   `json:"allowActions"`
		}
		if json.Unmarshal([]byte(data), &s) != nil {
			return ""
		}
		mode := "read-only"
		if s.AllowActions {
			mode = "actions enabled"
		}
		return strings.TrimSpace(strings.Join([]string{s.Serial, s.Package, mode}, " "))
	}
	return ""
}

func openBrowser(url string, logf func(string, ...any)) {
	name := "xdg-open"
	if runtime.GOOS == "darwin" {
		name = "open"
	}
	if err := exec.Command(name, url).Start(); err != nil {
		logf("could not open a browser: %v", err)
	}
}
