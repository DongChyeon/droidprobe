package ui

import (
	"context"
	"crypto/rand"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"sync"
	"time"
)

// bus is one browser tab's single SSE connection. Browsers allow six HTTP/1.1
// connections per origin, so a page holding one EventSource per stream starves
// its own fetches; the bus carries status and every subscribed stream instead.
type bus struct {
	id   string
	out  chan sseEvent
	done chan struct{}

	mu   sync.Mutex
	subs map[string]context.CancelFunc // nil once the bus has closed
	next int
}

type busMsg struct {
	Sub  string          `json:"sub"`
	Type string          `json:"type"` // reset, live, record, marker, end or error
	Data json.RawMessage `json:"data,omitempty"`
	PSID string          `json:"psid,omitempty"`
}

func newBusID() string {
	b := make([]byte, 12)
	rand.Read(b)
	return hex.EncodeToString(b)
}

func (s *Server) lookupBus(id string) *bus {
	s.mu.Lock()
	defer s.mu.Unlock()
	return s.buses[id]
}

// handleBus streams "bus" {bus: id} first, then "status", "new-process" and
// "stream" events; each stream event is a busMsg for one subscription.
func (s *Server) handleBus(w http.ResponseWriter, r *http.Request) {
	f, ok := startSSE(w)
	if !ok {
		return
	}
	b := &bus{id: newBusID(), out: make(chan sseEvent, 1024), done: make(chan struct{}), subs: map[string]context.CancelFunc{}}
	s.mu.Lock()
	s.buses[b.id] = b
	s.mu.Unlock()
	statusCh, st := s.subscribeStatus()
	defer func() {
		s.unsubscribeStatus(statusCh)
		s.mu.Lock()
		delete(s.buses, b.id)
		s.mu.Unlock()
		b.close()
	}()

	hello, _ := json.Marshal(map[string]string{"bus": b.id})
	writeSSE(w, sseEvent{name: "bus", data: hello})
	stb, _ := json.Marshal(st)
	writeSSE(w, sseEvent{name: "status", data: stb})
	f.Flush()

	ping := time.NewTicker(pingInterval)
	defer ping.Stop()
	for {
		var ev sseEvent
		select {
		case e, ok := <-statusCh:
			if !ok {
				return
			}
			ev = e
		case ev = <-b.out:
		case <-ping.C:
			if _, err := io.WriteString(w, ": ping\n\n"); err != nil {
				return
			}
			f.Flush()
			continue
		case <-r.Context().Done():
			return
		}
		if writeSSE(w, ev) != nil {
			return
		}
		f.Flush()
	}
}

func (b *bus) close() {
	b.mu.Lock()
	defer b.mu.Unlock()
	close(b.done)
	for _, cancel := range b.subs {
		cancel()
	}
	b.subs = nil
}

func (b *bus) add(id string) (string, context.Context, error) {
	b.mu.Lock()
	defer b.mu.Unlock()
	if b.subs == nil {
		return "", nil, fmt.Errorf("bus %s is closed", b.id)
	}
	if id == "" {
		b.next++
		id = fmt.Sprintf("s%d", b.next)
	}
	if _, dup := b.subs[id]; dup {
		return "", nil, fmt.Errorf("subscription %s already exists", id)
	}
	ctx, cancel := context.WithCancel(context.Background())
	b.subs[id] = cancel
	return id, ctx, nil
}

func (b *bus) remove(id string) bool {
	b.mu.Lock()
	defer b.mu.Unlock()
	cancel, ok := b.subs[id]
	if ok {
		cancel()
		delete(b.subs, id)
	}
	return ok
}

func (b *bus) emit(ctx context.Context, sub string, e entry) bool {
	data, _ := json.Marshal(busMsg{Sub: sub, Type: e.kind, Data: e.data, PSID: e.psid})
	select {
	case b.out <- sseEvent{name: "stream", data: data}:
		return true
	case <-ctx.Done():
	case <-b.done:
	}
	return false
}

// forward relays one hub to the bus: reset, history, live, then live entries.
// If the hub drops it for falling behind, it subscribes again and replays history.
func (b *bus) forward(ctx context.Context, h *hub, sub string) {
	for {
		ch, hist := h.subscribe()
		ok := b.emit(ctx, sub, entry{kind: "reset"})
		for _, e := range hist {
			ok = ok && b.emit(ctx, sub, e)
		}
		ok = ok && b.emit(ctx, sub, entry{kind: "live"})
		dropped := false
		for ok && !dropped {
			select {
			case e, open := <-ch:
				if !open {
					dropped = true
					break
				}
				ok = b.emit(ctx, sub, e)
				if e.final() {
					b.remove(sub)
					return
				}
			case <-ctx.Done():
				ok = false
			}
		}
		h.unsubscribe(ch)
		if !ok {
			return
		}
	}
}

func (s *Server) handleSubscribe(w http.ResponseWriter, r *http.Request) {
	var req struct {
		Bus   string          `json:"bus"`
		Sub   string          `json:"sub"`
		Cmd   string          `json:"cmd"`
		Input json.RawMessage `json:"input"`
	}
	if !readBody(w, r, &req) {
		return
	}
	if req.Cmd == "" {
		writeError(w, http.StatusBadRequest, "BAD_REQUEST", "cmd is required")
		return
	}
	raw := string(req.Input)
	if raw == "null" {
		raw = ""
	}
	input, err := canonicalInput(raw)
	if err != nil {
		writeError(w, http.StatusBadRequest, "BAD_REQUEST", err.Error())
		return
	}
	b := s.lookupBus(req.Bus)
	if b == nil {
		writeError(w, http.StatusNotFound, "UNKNOWN_BUS", "no open bus "+req.Bus+"; reconnect /api/bus")
		return
	}
	id, ctx, err := b.add(req.Sub)
	if err != nil {
		writeError(w, http.StatusConflict, "BAD_REQUEST", err.Error())
		return
	}
	go b.forward(ctx, s.hubFor(req.Cmd, input), id)
	writeJSON(w, http.StatusOK, map[string]string{"sub": id})
}

func (s *Server) handleUnsubscribe(w http.ResponseWriter, r *http.Request) {
	var req struct {
		Bus string `json:"bus"`
		Sub string `json:"sub"`
	}
	if !readBody(w, r, &req) {
		return
	}
	b := s.lookupBus(req.Bus)
	if b == nil {
		writeError(w, http.StatusNotFound, "UNKNOWN_BUS", "no open bus "+req.Bus)
		return
	}
	writeJSON(w, http.StatusOK, map[string]bool{"removed": b.remove(req.Sub)})
}
