package proto

import (
	"bufio"
	"context"
	"encoding/json"
	"errors"
	"net"
	"testing"
	"time"
)

func pipe(t *testing.T) (*Client, *bufio.Reader, net.Conn) {
	a, b := net.Pipe()
	c := NewClient(a)
	t.Cleanup(func() { c.Close(); b.Close() })
	return c, bufio.NewReader(b), b
}

func readReq(t *testing.T, r *bufio.Reader) map[string]any {
	t.Helper()
	line, err := r.ReadBytes('\n')
	if err != nil {
		t.Fatal(err)
	}
	var m map[string]any
	if err := json.Unmarshal(line, &m); err != nil {
		t.Fatal(err)
	}
	return m
}

func TestConcurrentRequestsAreDemultiplexed(t *testing.T) {
	c, r, srv := pipe(t)
	type res struct {
		data string
		err  error
	}
	results := make(chan res, 2)
	for _, cmd := range []string{"a", "b"} {
		go func() {
			d, err := c.Call(context.Background(), "call", cmd, nil)
			results <- res{string(d), err}
		}()
	}
	first, second := readReq(t, r), readReq(t, r)
	// Answer in reverse order; each caller must still get its own reply.
	srv.Write([]byte(`{"id":"` + second["id"].(string) + `","type":"result","data":"` + second["command"].(string) + `"}` + "\n"))
	srv.Write([]byte(`{"id":"` + first["id"].(string) + `","type":"error","error":{"code":"INTERNAL","message":"x"}}` + "\n"))
	got := map[string]bool{}
	for range 2 {
		rr := <-results
		var ae *AppError
		switch {
		case errors.As(rr.err, &ae):
			got["err:"+ae.Code] = true
		case rr.err == nil:
			got[rr.data] = true
		}
	}
	if !got["err:INTERNAL"] || !(got[`"a"`] || got[`"b"`]) {
		t.Fatalf("got %v", got)
	}
	if first["id"] == second["id"] {
		t.Fatal("ids must be unique")
	}
}

func TestHelloSendsProtocol(t *testing.T) {
	c, r, srv := pipe(t)
	done := make(chan Hello)
	go func() {
		h, _ := c.Hello(context.Background())
		done <- h
	}()
	req := readReq(t, r)
	if req["op"] != "hello" || req["protocol"] != Version {
		t.Fatalf("req %v", req)
	}
	srv.Write([]byte(`{"id":"` + req["id"].(string) + `","type":"result","data":{"protocol":"1.0","headCursor":"ab:1","extra":true}}` + "\n"))
	if h := <-done; h.HeadCursor != "ab:1" {
		t.Fatalf("hello %+v", h)
	}
}

func TestCloseBeforeReplyIsConnError(t *testing.T) {
	c, r, srv := pipe(t)
	go func() { readReq(t, r); srv.Close() }()
	_, err := c.Hello(context.Background())
	var ce *ConnError
	if !errors.As(err, &ce) {
		t.Fatalf("got %v", err)
	}
}

func TestWatchEventsEndAndCancel(t *testing.T) {
	c, r, srv := pipe(t)
	st, errc := (*Stream)(nil), make(chan error, 1)
	go func() {
		var err error
		st, err = c.Watch("events.watch", json.RawMessage(`{"since":"ab:1"}`))
		errc <- err
	}()
	req := readReq(t, r)
	if err := <-errc; err != nil {
		t.Fatal(err)
	}
	id := req["id"].(string)
	go srv.Write([]byte(`{"id":"` + id + `","type":"event","data":{"n":1}}` + "\n"))
	ctx, cancel := context.WithTimeout(context.Background(), 2*time.Second)
	defer cancel()
	if d, err := st.Next(ctx); err != nil || string(d) != `{"n":1}` {
		t.Fatalf("got %s, %v", d, err)
	}
	cancelled := make(chan error, 1)
	go func() { cancelled <- st.Cancel(ctx) }()
	creq := readReq(t, r)
	if creq["op"] != "cancel" || creq["target"] != id {
		t.Fatalf("cancel %v", creq)
	}
	// A late event for the cancelled stream must not block the reader.
	srv.Write([]byte(`{"id":"` + id + `","type":"event","data":{}}` + "\n"))
	srv.Write([]byte(`{"id":"` + id + `","type":"end"}` + "\n"))
	srv.Write([]byte(`{"id":"` + creq["id"].(string) + `","type":"result","data":{}}` + "\n"))
	if err := <-cancelled; err != nil {
		t.Fatal(err)
	}
}

func TestMajor(t *testing.T) {
	if Major("1.0") != "1" || Major("2") != "2" || Major("10.3") != "10" {
		t.Fatal("major")
	}
}
