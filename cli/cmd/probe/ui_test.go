package main

import (
	"bufio"
	"bytes"
	"context"
	"encoding/json"
	"io"
	"net"
	"net/http"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/DongChyeon/droidprobe/cli/internal/adb"
	"github.com/DongChyeon/droidprobe/cli/internal/ui"
)

type lockedBuf struct {
	mu sync.Mutex
	b  bytes.Buffer
}

func (l *lockedBuf) Write(p []byte) (int, error) {
	l.mu.Lock()
	defer l.mu.Unlock()
	return l.b.Write(p)
}

// startUI runs `probe ui --port 0` and returns its URL and a stop func returning the exit code.
func startUI(t *testing.T, f *fakeADB, args ...string) (string, func() int) {
	t.Helper()
	pr, pw := io.Pipe()
	ctx, cancel := context.WithCancel(context.Background())
	done := make(chan int, 1)
	go func() {
		code := run(ctx, append([]string{"ui", "--port", "0"}, args...), &env{
			stdout:   pw,
			stderr:   &lockedBuf{},
			stdin:    strings.NewReader(""),
			getenv:   func(string) string { return "" },
			lookPath: func(string) (string, error) { return "/fake/adb", nil },
			newADB:   func(string) adb.Runner { return f },
			dial: func(ctx context.Context, addr string) (net.Conn, error) {
				var d net.Dialer
				return d.DialContext(ctx, "tcp", addr)
			},
		})
		pw.Close()
		done <- code
	}()
	line, err := bufio.NewReader(pr).ReadString('\n')
	if err != nil {
		cancel()
		t.Fatalf("no url line: %v (exit %d)", err, <-done)
	}
	go io.Copy(io.Discard, pr)
	var out struct {
		URL string `json:"url"`
	}
	if err := json.Unmarshal([]byte(line), &out); err != nil || !strings.HasPrefix(out.URL, "http://127.0.0.1:") {
		t.Fatalf("url line %q", line)
	}
	t.Cleanup(cancel)
	stop := func() int {
		cancel()
		select {
		case code := <-done:
			return code
		case <-time.After(5 * time.Second):
			t.Fatal("probe ui did not stop")
			return -1
		}
	}
	waitFor(t, func() bool {
		resp, err := http.Get(out.URL + "/api/hello")
		if err != nil {
			return false
		}
		resp.Body.Close()
		return resp.StatusCode == http.StatusOK
	})
	return out.URL, stop
}

func waitFor(t *testing.T, cond func() bool) {
	t.Helper()
	deadline := time.Now().Add(5 * time.Second)
	for !cond() {
		if time.Now().After(deadline) {
			t.Fatal("condition not met within 5s")
		}
		time.Sleep(20 * time.Millisecond)
	}
}

func postCall(t *testing.T, url, command string) (int, string) {
	t.Helper()
	body, _ := json.Marshal(map[string]any{"command": command, "input": map[string]any{"path": "/x"}})
	resp, err := http.Post(url+"/api/call", "application/json", bytes.NewReader(body))
	if err != nil {
		t.Fatal(err)
	}
	defer resp.Body.Close()
	b, _ := io.ReadAll(resp.Body)
	return resp.StatusCode, string(b)
}

func (a *fakeApp) calls() []string {
	a.mu.Lock()
	defer a.mu.Unlock()
	var cmds []string
	for _, r := range a.requests {
		if r.Op == "call" {
			cmds = append(cmds, r.Command)
		}
	}
	return cmds
}

func TestUIReadOnlyRejectsStateChangingCalls(t *testing.T) {
	f, app := setup(t, func(a *fakeApp) {
		a.result = func(req wireReq) (any, string) {
			if req.Op == "list" {
				return map[string]any{"commands": []any{
					map[string]any{"name": "network.mock.add", "kind": "call"},
					map[string]any{"name": "checkout.state.watch", "kind": "stream"},
					map[string]any{"name": "probe.info", "kind": "call"},
				}}, ""
			}
			return map[string]any{"ok": true}, ""
		}
	})
	url, stop := startUI(t, f)

	for _, cmd := range []string{"network.mock.add", "network.mock.remove", "network.mock.clear", "probe.config",
		"checkout.invoke", "lifecycle.startActivity", "custom.reset"} {
		if code, body := postCall(t, url, cmd); code != http.StatusForbidden || !strings.Contains(body, "READ_ONLY") {
			t.Errorf("%s: %d %s", cmd, code, body)
		}
	}
	for _, cmd := range []string{"probe.info", "checkout.state", "network.records", "network.record", "network.mock.list",
		"network.mock.templates", "lifecycle.activities", "lifecycle.fragments", "lifecycle.process"} {
		if code, body := postCall(t, url, cmd); code != http.StatusOK {
			t.Errorf("%s: %d %s", cmd, code, body)
		}
	}
	if got := app.calls(); len(got) != 9 || strings.Contains(strings.Join(got, ","), "mock.add") {
		t.Fatalf("app saw calls %v", got)
	}

	resp, err := http.Get(url + "/api/list")
	if err != nil {
		t.Fatal(err)
	}
	var list struct {
		Commands []struct {
			Name     string `json:"name"`
			ReadOnly bool   `json:"readOnly"`
		} `json:"commands"`
	}
	json.NewDecoder(resp.Body).Decode(&list)
	resp.Body.Close()
	ro := map[string]bool{}
	for _, c := range list.Commands {
		ro[c.Name] = c.ReadOnly
	}
	if ro["network.mock.add"] || !ro["checkout.state.watch"] || !ro["probe.info"] {
		t.Fatalf("readOnly flags %v", ro)
	}

	req, _ := http.NewRequest("GET", url+"/api/list", nil)
	req.Host = "attacker.example:80"
	if resp, err := http.DefaultClient.Do(req); err != nil || resp.StatusCode != http.StatusForbidden {
		t.Fatalf("foreign Host: %v %v", resp, err)
	}
	req, _ = http.NewRequest("POST", url+"/api/call", strings.NewReader(`{"command":"probe.info"}`))
	req.Header.Set("Content-Type", "application/json")
	req.Header.Set("Origin", "https://attacker.example")
	if resp, err := http.DefaultClient.Do(req); err != nil || resp.StatusCode != http.StatusForbidden {
		t.Fatalf("foreign Origin: %v %v", resp, err)
	}

	if code := stop(); code != exitOK {
		t.Fatalf("exit %d", code)
	}
	assertRemoved(t, f, app.port())

	f2, app2 := setup(t, func(a *fakeApp) {
		a.result = func(wireReq) (any, string) { return map[string]any{"id": "m-1"}, "" }
	})
	url2, stop2 := startUI(t, f2, "--allow-actions")
	if code, body := postCall(t, url2, "network.mock.add"); code != http.StatusOK || body != `{"id":"m-1"}` {
		t.Fatalf("with --allow-actions: %d %s", code, body)
	}
	if req := app2.lastRequest("call"); req.Command != "network.mock.add" || string(req.Input) != `{"path":"/x"}` {
		t.Fatalf("request %+v", req)
	}
	stop2()
}

func TestUICallKeepsAppErrorCodes(t *testing.T) {
	f, _ := setup(t, func(a *fakeApp) {
		a.result = func(wireReq) (any, string) { return nil, "INVALID_INPUT" }
	})
	url, stop := startUI(t, f)
	defer stop()
	if code, body := postCall(t, url, "network.record"); code != http.StatusUnprocessableEntity || !strings.Contains(body, `"code":"INVALID_INPUT"`) {
		t.Fatalf("%d %s", code, body)
	}
}

type sseMsg struct{ event, data string }

func readSSE(r *bufio.Reader) (sseMsg, error) {
	var m sseMsg
	for {
		line, err := r.ReadString('\n')
		if err != nil {
			return m, err
		}
		line = strings.TrimRight(line, "\n")
		switch {
		case line == "":
			if m.data != "" {
				if m.event == "" {
					m.event = "message"
				}
				return m, nil
			}
		case strings.HasPrefix(line, "event: "):
			m.event = strings.TrimPrefix(line, "event: ")
		case strings.HasPrefix(line, "data: "):
			m.data = strings.TrimPrefix(line, "data: ")
		}
	}
}

func openSSE(t *testing.T, ctx context.Context, url string) (*bufio.Reader, io.Closer) {
	t.Helper()
	req, _ := http.NewRequestWithContext(ctx, "GET", url, nil)
	resp, err := http.DefaultClient.Do(req)
	if err != nil || resp.StatusCode != http.StatusOK {
		t.Fatalf("GET %s: %v %v", url, resp, err)
	}
	return bufio.NewReader(resp.Body), resp.Body
}

// nextEvent skips messages until one named want arrives.
func nextEvent(t *testing.T, r *bufio.Reader, want string) sseMsg {
	t.Helper()
	for {
		m, err := readSSE(r)
		if err != nil {
			t.Fatalf("waiting for %q: %v", want, err)
		}
		if m.event == want {
			return m
		}
	}
}

func TestUIStreamRelaysAndCancelsOnDisconnect(t *testing.T) {
	f, app := setup(t, func(a *fakeApp) {
		a.events = events(3)
	})
	url, stop := startUI(t, f)
	defer stop()

	ctx, cancel := context.WithCancel(context.Background())
	r, body := openSSE(t, ctx, url+"/api/stream?cmd=events.watch&input=%7B%7D")
	nextEvent(t, r, "live")
	for i := 1; i <= 3; i++ {
		m := nextEvent(t, r, "message")
		var rec struct {
			PSID string         `json:"psid"`
			Data map[string]int `json:"data"`
		}
		if json.Unmarshal([]byte(m.data), &rec); rec.PSID != "a1f3c09e" || rec.Data["n"] != i {
			t.Fatalf("event %d: %s", i, m.data)
		}
	}
	// The fake declares since, so a fresh watch replays the whole journal of this process.
	if in := string(app.lastRequest("watch").Input); in != `{"since":"a1f3c09e:0"}` {
		t.Fatalf("watch input %s", in)
	}
	if app.cancelCount() != 0 {
		t.Fatal("cancelled while the browser was still reading")
	}
	cancel()
	body.Close()
	waitFor(t, func() bool { return app.cancelCount() == 1 })
}

func TestUIStreamEndsWithFinalEvent(t *testing.T) {
	for tail, want := range map[string]string{"end": "end", "error:STALE_CURSOR": "error"} {
		f, _ := setup(t, func(a *fakeApp) {
			a.events, a.tail = events(1), tail
		})
		url, stop := startUI(t, f)
		r, body := openSSE(t, context.Background(), url+"/api/stream?cmd=events.watch")
		m := nextEvent(t, r, want)
		if want == "error" && !strings.Contains(m.data, "STALE_CURSOR") {
			t.Fatalf("error event %s", m.data)
		}
		if _, err := readSSE(r); err != io.EOF {
			t.Fatalf("%s: stream still open after the final event: %v", tail, err)
		}
		body.Close()
		stop()
	}
}

func TestUIReconnectMarksNewProcess(t *testing.T) {
	f, app := setup(t, nil)
	url, stop := startUI(t, f)
	defer stop()
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()

	status, _ := openSSE(t, ctx, url+"/api/status")
	var st ui.Status
	json.Unmarshal([]byte(nextEvent(t, status, "status").data), &st)
	if st.State != "connected" || st.ProcessStartID != "a1f3c09e" || st.Connects != 1 {
		t.Fatalf("initial status %+v", st)
	}
	stream, _ := openSSE(t, ctx, url+"/api/stream?cmd=events.watch")
	nextEvent(t, stream, "live")
	waitFor(t, func() bool { return app.lastRequest("watch").ID != "" })

	app.restart("b2c3d4e5")
	m := nextEvent(t, status, "new-process")
	if !strings.Contains(m.data, `"from":"a1f3c09e"`) || !strings.Contains(m.data, `"to":"b2c3d4e5"`) {
		t.Fatalf("new-process %s", m.data)
	}
	json.Unmarshal([]byte(nextEvent(t, status, "status").data), &st)
	if st.State != "connected" || st.ProcessStartID != "b2c3d4e5" || st.Connects != 2 {
		t.Fatalf("status after restart %+v", st)
	}
	if m := nextEvent(t, stream, "marker"); !strings.Contains(m.data, "b2c3d4e5") {
		t.Fatalf("marker %s", m.data)
	}
	// The active stream is restarted on the new connection, resuming from the new process.
	waitFor(t, func() bool { return string(app.lastRequest("watch").Input) == `{"since":"b2c3d4e5:0"}` })

	// A late tab gets the marker from the backend history.
	late, _ := openSSE(t, ctx, url+"/api/stream?cmd=events.watch")
	nextEvent(t, late, "reset")
	if m := nextEvent(t, late, "marker"); !strings.Contains(m.data, "b2c3d4e5") {
		t.Fatalf("history marker %s", m.data)
	}
}

func TestUIBindsLoopbackOnly(t *testing.T) {
	for _, port := range []string{"0.0.0.0:0", "192.168.1.10:7070", "localhost:0", "[::]:0"} {
		f := &fakeADB{devices: oneDevice}
		r := runWith(t, f, "ui", "--port", port)
		if r.code != exitUsage || !strings.Contains(r.stderr, "127.0.0.1") {
			t.Errorf("%s: got %d %q", port, r.code, r.stderr)
		}
		if len(f.calls) != 0 {
			t.Errorf("%s: adb called: %v", port, f.calls)
		}
	}
	ln, err := ui.Listen("127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	ln.Close()
}

func (a *fakeApp) watchCount() int {
	a.mu.Lock()
	defer a.mu.Unlock()
	n := 0
	for _, r := range a.requests {
		if r.Op == "watch" {
			n++
		}
	}
	return n
}

func postJSON(t *testing.T, url string, v any) (int, string) {
	t.Helper()
	body, _ := json.Marshal(v)
	resp, err := http.Post(url, "application/json", bytes.NewReader(body))
	if err != nil {
		t.Fatal(err)
	}
	defer resp.Body.Close()
	b, _ := io.ReadAll(resp.Body)
	return resp.StatusCode, string(b)
}

// openBus returns the bus reader and its id; the first two events are "bus" and "status".
func openBus(t *testing.T, ctx context.Context, url string) (*bufio.Reader, string) {
	t.Helper()
	r, _ := openSSE(t, ctx, url+"/api/bus")
	m, err := readSSE(r)
	if err != nil || m.event != "bus" {
		t.Fatalf("first bus event %+v %v", m, err)
	}
	var b struct {
		Bus string `json:"bus"`
	}
	json.Unmarshal([]byte(m.data), &b)
	if m, _ := readSSE(r); m.event != "status" || !strings.Contains(m.data, `"state":"connected"`) {
		t.Fatalf("second bus event %+v", m)
	}
	return r, b.Bus
}

type busMsg struct {
	Sub  string          `json:"sub"`
	Type string          `json:"type"`
	Data json.RawMessage `json:"data"`
	PSID string          `json:"psid"`
}

func TestUIBusSharesWatchAndCancelsOnLastUnsubscribe(t *testing.T) {
	f, app := setup(t, func(a *fakeApp) {
		a.events = events(2)
	})
	url, stop := startUI(t, f)
	defer stop()
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	r, id := openBus(t, ctx, url)

	for _, sub := range []string{"a", "b"} {
		code, body := postJSON(t, url+"/api/subscribe", map[string]any{"bus": id, "sub": sub, "cmd": "events.watch", "input": map[string]any{}})
		if code != http.StatusOK || body != `{"sub":"`+sub+`"}`+"\n" {
			t.Fatalf("subscribe %s: %d %s", sub, code, body)
		}
	}
	got := map[string][]string{}
	for len(got["a"]) < 4 || len(got["b"]) < 4 {
		m := nextEvent(t, r, "stream")
		var bm busMsg
		json.Unmarshal([]byte(m.data), &bm)
		kind := bm.Type
		if kind == "record" {
			kind += string(bm.Data) + bm.PSID
		}
		got[bm.Sub] = append(got[bm.Sub], kind)
	}
	// "live" lands between the records or after them depending on timing; the rest is fixed.
	for sub, kinds := range got {
		joined := strings.Replace(strings.Join(kinds, ","), "live,", "", 1)
		joined = strings.TrimSuffix(joined, ",live")
		if joined != `reset,record{"n":1}a1f3c09e,record{"n":2}a1f3c09e` {
			t.Fatalf("sub %s got %v", sub, kinds)
		}
	}
	if n := app.watchCount(); n != 1 {
		t.Fatalf("two subscriptions opened %d app watches", n)
	}

	if code, body := postCall(t, url, "network.mock.add"); code != http.StatusForbidden {
		t.Fatalf("read-only rule with a bus open: %d %s", code, body)
	}
	if code, _ := postJSON(t, url+"/api/subscribe", map[string]any{"bus": id, "sub": "a", "cmd": "events.watch"}); code != http.StatusConflict {
		t.Fatalf("duplicate sub id: %d", code)
	}
	if code, _ := postJSON(t, url+"/api/subscribe", map[string]any{"bus": "nope", "cmd": "events.watch"}); code != http.StatusNotFound {
		t.Fatalf("unknown bus: %d", code)
	}

	if code, body := postJSON(t, url+"/api/unsubscribe", map[string]any{"bus": id, "sub": "a"}); code != http.StatusOK || !strings.Contains(body, "true") {
		t.Fatalf("unsubscribe a: %d %s", code, body)
	}
	time.Sleep(200 * time.Millisecond)
	if app.cancelCount() != 0 {
		t.Fatal("app watch cancelled while sub b still listens")
	}
	postJSON(t, url+"/api/unsubscribe", map[string]any{"bus": id, "sub": "b"})
	waitFor(t, func() bool { return app.cancelCount() == 1 })
}

func TestUIBusDisconnectCleansUpItsSubscriptions(t *testing.T) {
	f, app := setup(t, nil)
	url, stop := startUI(t, f)
	defer stop()
	ctx, cancel := context.WithCancel(context.Background())
	r, id := openBus(t, ctx, url)
	for _, cmd := range []string{"events.watch", "checkout.state.watch"} {
		if code, body := postJSON(t, url+"/api/subscribe", map[string]any{"bus": id, "cmd": cmd}); code != http.StatusOK {
			t.Fatalf("subscribe %s: %d %s", cmd, code, body)
		}
	}
	nextEvent(t, r, "stream")
	waitFor(t, func() bool { return app.watchCount() == 2 })
	cancel()
	waitFor(t, func() bool { return app.cancelCount() == 2 })
	if code, _ := postJSON(t, url+"/api/subscribe", map[string]any{"bus": id, "cmd": "events.watch"}); code != http.StatusNotFound {
		t.Fatalf("subscribe on a closed bus: %d", code)
	}
}
