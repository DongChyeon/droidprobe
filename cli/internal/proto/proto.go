// Package proto speaks the droidprobe NDJSON wire protocol (docs/protocol.md).
package proto

import (
	"bufio"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"strconv"
	"strings"
	"sync"
	"sync/atomic"
)

// Version is the protocol version this client speaks.
const Version = "1.0"

// AppError is an `error` response sent by the app.
type AppError struct {
	Code    string `json:"code"`
	Message string `json:"message"`
}

func (e *AppError) Error() string { return e.Code + ": " + e.Message }

// ConnError means the connection failed or closed before a response arrived.
type ConnError struct{ Err error }

func (e *ConnError) Error() string { return "connection: " + e.Err.Error() }
func (e *ConnError) Unwrap() error { return e.Err }

// ErrEnd is returned by Stream.Next when the app ends the stream.
var ErrEnd = errors.New("stream ended")

type Hello struct {
	Protocol       string `json:"protocol"`
	SDK            string `json:"sdk"`
	Package        string `json:"package"`
	PID            int    `json:"pid"`
	ProcessStartID string `json:"processStartId"`
	HeadCursor     string `json:"headCursor"`
}

type request struct {
	ID       string          `json:"id"`
	Op       string          `json:"op"`
	Protocol string          `json:"protocol,omitempty"`
	Command  string          `json:"command,omitempty"`
	Input    json.RawMessage `json:"input,omitempty"`
	Target   string          `json:"target,omitempty"`
}

type response struct {
	ID    string          `json:"id"`
	Type  string          `json:"type"`
	Data  json.RawMessage `json:"data"`
	Error *AppError       `json:"error"`
}

type sub struct {
	ch       chan response
	stop     chan struct{}
	stopOnce sync.Once
}

// Client multiplexes concurrent requests over one connection.
type Client struct {
	conn   io.ReadWriteCloser
	wmu    sync.Mutex
	mu     sync.Mutex
	subs   map[string]*sub
	nextID atomic.Uint64
	done   chan struct{}
	err    error
}

func NewClient(conn io.ReadWriteCloser) *Client {
	c := &Client{conn: conn, subs: map[string]*sub{}, done: make(chan struct{})}
	go c.readLoop()
	return c
}

func (c *Client) Close() error { return c.conn.Close() }

// Done is closed once the connection has failed or been closed.
func (c *Client) Done() <-chan struct{} { return c.done }

func (c *Client) readLoop() {
	r := bufio.NewReader(c.conn)
	var err error
	for {
		var line []byte
		line, err = r.ReadBytes('\n')
		if len(line) > 0 && strings.TrimSpace(string(line)) != "" {
			var resp response
			if jerr := json.Unmarshal(line, &resp); jerr != nil {
				err = fmt.Errorf("malformed response: %v", jerr)
				break
			}
			c.dispatch(resp)
		}
		if err != nil {
			break
		}
	}
	if err == io.EOF {
		err = errors.New("closed by peer")
	}
	c.err = err
	close(c.done)
}

func (c *Client) dispatch(resp response) {
	c.mu.Lock()
	s := c.subs[resp.ID]
	if s != nil && resp.Type != "event" {
		delete(c.subs, resp.ID)
	}
	c.mu.Unlock()
	if s == nil {
		return
	}
	select {
	case s.ch <- resp:
	case <-s.stop:
	}
}

func (c *Client) connErr() error { return &ConnError{Err: c.err} }

func (c *Client) send(req request) (string, *sub, error) {
	req.ID = strconv.FormatUint(c.nextID.Add(1), 10)
	s := &sub{ch: make(chan response, 16), stop: make(chan struct{})}
	c.mu.Lock()
	c.subs[req.ID] = s
	c.mu.Unlock()
	line, err := json.Marshal(req)
	if err != nil {
		c.forget(req.ID, s)
		return "", nil, err
	}
	c.wmu.Lock()
	_, err = c.conn.Write(append(line, '\n'))
	c.wmu.Unlock()
	if err != nil {
		c.forget(req.ID, s)
		return "", nil, &ConnError{Err: err}
	}
	return req.ID, s, nil
}

func (c *Client) forget(id string, s *sub) {
	c.mu.Lock()
	if c.subs[id] == s {
		delete(c.subs, id)
	}
	c.mu.Unlock()
	s.stopOnce.Do(func() { close(s.stop) })
}

func (c *Client) roundTrip(ctx context.Context, req request) (json.RawMessage, error) {
	id, s, err := c.send(req)
	if err != nil {
		return nil, err
	}
	select {
	case resp := <-s.ch:
		return c.result(resp)
	case <-c.done:
		select {
		case resp := <-s.ch:
			return c.result(resp)
		default:
			return nil, c.connErr()
		}
	case <-ctx.Done():
		c.forget(id, s)
		return nil, ctx.Err()
	}
}

func (c *Client) result(resp response) (json.RawMessage, error) {
	switch {
	case resp.Type == "error" && resp.Error != nil:
		return nil, resp.Error
	case resp.Type == "result":
		return resp.Data, nil
	default:
		return nil, &ConnError{Err: fmt.Errorf("unexpected response type %q", resp.Type)}
	}
}

// Hello must be the first request on a connection.
func (c *Client) Hello(ctx context.Context) (Hello, error) {
	data, err := c.roundTrip(ctx, request{Op: "hello", Protocol: Version})
	if err != nil {
		return Hello{}, err
	}
	var h Hello
	if err := json.Unmarshal(data, &h); err != nil {
		return Hello{}, &ConnError{Err: fmt.Errorf("malformed hello: %v", err)}
	}
	return h, nil
}

// Call sends a single-response op (list, describe, call).
func (c *Client) Call(ctx context.Context, op, command string, input json.RawMessage) (json.RawMessage, error) {
	return c.roundTrip(ctx, request{Op: op, Command: command, Input: input})
}

// Stream is one running watch.
type Stream struct {
	c  *Client
	id string
	s  *sub
}

func (c *Client) Watch(command string, input json.RawMessage) (*Stream, error) {
	id, s, err := c.send(request{Op: "watch", Command: command, Input: input})
	if err != nil {
		return nil, err
	}
	return &Stream{c: c, id: id, s: s}, nil
}

// Next returns the next event's data, ErrEnd, an *AppError or a *ConnError.
func (st *Stream) Next(ctx context.Context) (json.RawMessage, error) {
	select {
	case resp := <-st.s.ch:
		return st.decode(resp)
	case <-st.c.done:
		select {
		case resp := <-st.s.ch:
			return st.decode(resp)
		default:
			return nil, st.c.connErr()
		}
	case <-ctx.Done():
		return nil, ctx.Err()
	}
}

func (st *Stream) decode(resp response) (json.RawMessage, error) {
	switch resp.Type {
	case "event":
		return resp.Data, nil
	case "end":
		return nil, ErrEnd
	case "error":
		if resp.Error != nil {
			return nil, resp.Error
		}
	}
	return nil, &ConnError{Err: fmt.Errorf("unexpected response type %q", resp.Type)}
}

// Cancel stops delivery locally and asks the app to stop the watch.
func (st *Stream) Cancel(ctx context.Context) error {
	st.c.forget(st.id, st.s)
	_, err := st.c.roundTrip(ctx, request{Op: "cancel", Target: st.id})
	return err
}

// Major returns the major component of a "major.minor" version.
func Major(v string) string {
	major, _, _ := strings.Cut(v, ".")
	return major
}
