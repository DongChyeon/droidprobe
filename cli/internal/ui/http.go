package ui

import (
	"context"
	"embed"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"io/fs"
	"mime"
	"net"
	"net/http"
	"net/url"
	"time"

	"github.com/DongChyeon/droidprobe/cli/internal/proto"
)

//go:embed static
var static embed.FS

const (
	// The app times calls out after 10 s; this only guards against a hung app.
	callTimeout  = 60 * time.Second
	pingInterval = 20 * time.Second
	maxBodyBytes = 1 << 20
)

func (s *Server) Handler() http.Handler {
	sub, _ := fs.Sub(static, "static")
	mux := http.NewServeMux()
	mux.HandleFunc("GET /api/hello", s.handleHello)
	mux.HandleFunc("GET /api/list", s.handleList)
	mux.HandleFunc("GET /api/describe", s.handleDescribe)
	mux.HandleFunc("POST /api/call", s.handleCall)
	mux.HandleFunc("GET /api/stream", s.handleStream)
	mux.HandleFunc("GET /api/status", s.handleStatus)
	mux.HandleFunc("GET /api/bus", s.handleBus)
	mux.HandleFunc("POST /api/subscribe", s.handleSubscribe)
	mux.HandleFunc("POST /api/unsubscribe", s.handleUnsubscribe)
	mux.Handle("GET /", http.FileServerFS(sub))
	return guard(mux)
}

// guard rejects requests whose Host or Origin is not loopback, so a web page the
// developer happens to visit cannot reach the dashboard through DNS rebinding or CSRF.
func guard(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if !loopbackHost(r.Host) {
			writeError(w, http.StatusForbidden, "FORBIDDEN", "host "+r.Host+" is not loopback")
			return
		}
		if o := r.Header.Get("Origin"); o != "" {
			u, err := url.Parse(o)
			if err != nil || !loopbackHost(u.Host) {
				writeError(w, http.StatusForbidden, "FORBIDDEN", "cross-origin request from "+o)
				return
			}
		}
		w.Header().Set("X-Content-Type-Options", "nosniff")
		w.Header().Set("Referrer-Policy", "no-referrer")
		w.Header().Set("Content-Security-Policy", "default-src 'self'; style-src 'self'; script-src 'self'; connect-src 'self'; img-src 'self' data:")
		next.ServeHTTP(w, r)
	})
}

func loopbackHost(hostport string) bool {
	host, _, err := net.SplitHostPort(hostport)
	if err != nil {
		host = hostport
	}
	return host == "127.0.0.1" || host == "localhost"
}

type apiError struct {
	Code    string `json:"code"`
	Message string `json:"message"`
}

func writeError(w http.ResponseWriter, status int, code, msg string) {
	writeJSON(w, status, map[string]apiError{"error": {Code: code, Message: msg}})
}

func writeJSON(w http.ResponseWriter, status int, v any) {
	w.Header().Set("Content-Type", "application/json; charset=utf-8")
	w.Header().Set("Cache-Control", "no-store")
	w.WriteHeader(status)
	json.NewEncoder(w).Encode(v)
}

func writeRaw(w http.ResponseWriter, data json.RawMessage) {
	if len(data) == 0 {
		data = json.RawMessage("null")
	}
	w.Header().Set("Content-Type", "application/json; charset=utf-8")
	w.Header().Set("Cache-Control", "no-store")
	w.Write(data)
}

// appCall sends one request on the shared connection and writes the result or an error.
func (s *Server) appCall(w http.ResponseWriter, r *http.Request, op, command string, input json.RawMessage) (json.RawMessage, bool) {
	c := s.current()
	if c == nil {
		writeError(w, http.StatusServiceUnavailable, "DISCONNECTED", "not connected to the app; retrying")
		return nil, false
	}
	ctx, cancel := context.WithTimeout(r.Context(), callTimeout)
	defer cancel()
	data, err := c.Client.Call(ctx, op, command, input)
	if err == nil {
		return data, true
	}
	var ae *proto.AppError
	var ce *proto.ConnError
	switch {
	case errors.As(err, &ae):
		status := http.StatusUnprocessableEntity
		if ae.Code == "TIMEOUT" {
			status = http.StatusGatewayTimeout
		}
		writeError(w, status, ae.Code, ae.Message)
	case errors.As(err, &ce):
		writeError(w, http.StatusServiceUnavailable, "DISCONNECTED", ce.Error())
	case errors.Is(err, context.DeadlineExceeded):
		writeError(w, http.StatusGatewayTimeout, "TIMEOUT", fmt.Sprintf("no response within %s", callTimeout))
	default:
		writeError(w, http.StatusBadGateway, "INTERNAL", err.Error())
	}
	return nil, false
}

func (s *Server) handleHello(w http.ResponseWriter, r *http.Request) {
	c := s.current()
	if c == nil {
		writeError(w, http.StatusServiceUnavailable, "DISCONNECTED", "not connected to the app; retrying")
		return
	}
	writeJSON(w, http.StatusOK, struct {
		proto.Hello
		Serial       string `json:"serial"`
		AllowActions bool   `json:"allowActions"`
	}{c.Hello, s.opts.Serial, s.opts.AllowActions})
}

// handleList adds readOnly to every command so the page applies the same rule as /api/call.
func (s *Server) handleList(w http.ResponseWriter, r *http.Request) {
	data, ok := s.appCall(w, r, "list", "", nil)
	if !ok {
		return
	}
	var top map[string]json.RawMessage
	var cmds []map[string]json.RawMessage
	if json.Unmarshal(data, &top) != nil || json.Unmarshal(top["commands"], &cmds) != nil {
		writeRaw(w, data)
		return
	}
	for _, c := range cmds {
		var name, kind string
		json.Unmarshal(c["name"], &name)
		json.Unmarshal(c["kind"], &kind)
		c["readOnly"], _ = json.Marshal(kind == "stream" || ReadOnly(name))
	}
	top["commands"], _ = json.Marshal(cmds)
	b, _ := json.Marshal(top)
	writeRaw(w, b)
}

func (s *Server) handleDescribe(w http.ResponseWriter, r *http.Request) {
	cmd := r.URL.Query().Get("cmd")
	if cmd == "" {
		writeError(w, http.StatusBadRequest, "BAD_REQUEST", "cmd is required")
		return
	}
	if data, ok := s.appCall(w, r, "describe", cmd, nil); ok {
		writeRaw(w, data)
	}
}

// readBody decodes a JSON POST body; requiring the JSON media type keeps plain
// cross-site form posts out even before the Origin check.
func readBody(w http.ResponseWriter, r *http.Request, v any) bool {
	if mt, _, _ := mime.ParseMediaType(r.Header.Get("Content-Type")); mt != "application/json" {
		writeError(w, http.StatusUnsupportedMediaType, "BAD_REQUEST", "Content-Type must be application/json")
		return false
	}
	body, err := io.ReadAll(io.LimitReader(r.Body, maxBodyBytes))
	if err != nil || json.Unmarshal(body, v) != nil {
		writeError(w, http.StatusBadRequest, "BAD_REQUEST", "body must be a JSON object")
		return false
	}
	return true
}

func (s *Server) handleCall(w http.ResponseWriter, r *http.Request) {
	var req struct {
		Command string          `json:"command"`
		Input   json.RawMessage `json:"input"`
	}
	if !readBody(w, r, &req) {
		return
	}
	if req.Command == "" {
		writeError(w, http.StatusBadRequest, "BAD_REQUEST", `body must be {"command": string, "input": object}`)
		return
	}
	var input json.RawMessage
	if len(req.Input) > 0 && string(req.Input) != "null" {
		in, err := canonicalInput(string(req.Input))
		if err != nil {
			writeError(w, http.StatusBadRequest, "BAD_REQUEST", err.Error())
			return
		}
		if string(in) != "{}" {
			input = in
		}
	}
	if !s.opts.AllowActions && !ReadOnly(req.Command) {
		writeError(w, http.StatusForbidden, "READ_ONLY", req.Command+" may change app state; restart probe ui with --allow-actions to run it")
		return
	}
	if data, ok := s.appCall(w, r, "call", req.Command, input); ok {
		writeRaw(w, data)
	}
}

func startSSE(w http.ResponseWriter) (http.Flusher, bool) {
	f, ok := w.(http.Flusher)
	if !ok {
		writeError(w, http.StatusInternalServerError, "INTERNAL", "streaming unsupported")
		return nil, false
	}
	w.Header().Set("Content-Type", "text/event-stream")
	w.Header().Set("Cache-Control", "no-store")
	w.WriteHeader(http.StatusOK)
	return f, true
}

func writeSSE(w io.Writer, ev sseEvent) error {
	var err error
	if ev.name != "" {
		_, err = fmt.Fprintf(w, "event: %s\n", ev.name)
	}
	if ev.id > 0 && err == nil {
		_, err = fmt.Fprintf(w, "id: %d\n", ev.id)
	}
	if err == nil {
		_, err = fmt.Fprintf(w, "data: %s\n\n", ev.data)
	}
	return err
}

// handleStream relays one watch: "reset", the retained history, "live", then
// records as default events, "marker" on a new process, and a final "end" or "error".
func (s *Server) handleStream(w http.ResponseWriter, r *http.Request) {
	q := r.URL.Query()
	cmd := q.Get("cmd")
	if cmd == "" {
		writeError(w, http.StatusBadRequest, "BAD_REQUEST", "cmd is required")
		return
	}
	input, err := canonicalInput(q.Get("input"))
	if err != nil {
		writeError(w, http.StatusBadRequest, "BAD_REQUEST", err.Error())
		return
	}
	f, ok := startSSE(w)
	if !ok {
		return
	}
	h := s.hubFor(cmd, input)
	ch, hist := h.subscribe()
	defer h.unsubscribe(ch)

	writeSSE(w, sseEvent{name: "reset", data: []byte("{}")})
	for _, ev := range hist {
		writeSSE(w, streamEvent(ev))
	}
	writeSSE(w, sseEvent{name: "live", data: []byte("{}")})
	f.Flush()

	ping := time.NewTicker(pingInterval)
	defer ping.Stop()
	for {
		select {
		case ev, ok := <-ch:
			if !ok {
				return
			}
			if writeSSE(w, streamEvent(ev)) != nil {
				return
			}
			f.Flush()
			if ev.final() {
				return
			}
		case <-ping.C:
			if _, err := io.WriteString(w, ": ping\n\n"); err != nil {
				return
			}
			f.Flush()
		case <-r.Context().Done():
			return
		}
	}
}

// streamEvent renders a hub entry for /api/stream: records are default events.
func streamEvent(e entry) sseEvent {
	if e.kind == "record" {
		b, _ := json.Marshal(map[string]any{"psid": e.psid, "data": e.data})
		return sseEvent{id: e.id, data: b}
	}
	return sseEvent{name: e.kind, id: e.id, data: e.data}
}

func (s *Server) handleStatus(w http.ResponseWriter, r *http.Request) {
	f, ok := startSSE(w)
	if !ok {
		return
	}
	ch, st := s.subscribeStatus()
	defer s.unsubscribeStatus(ch)
	b, _ := json.Marshal(st)
	writeSSE(w, sseEvent{name: "status", data: b})
	f.Flush()
	ping := time.NewTicker(pingInterval)
	defer ping.Stop()
	for {
		select {
		case ev, ok := <-ch:
			if !ok || writeSSE(w, ev) != nil {
				return
			}
			f.Flush()
		case <-ping.C:
			if _, err := io.WriteString(w, ": ping\n\n"); err != nil {
				return
			}
			f.Flush()
		case <-r.Context().Done():
			return
		}
	}
}
