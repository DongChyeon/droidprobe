package ui

import (
	"context"
	"encoding/json"
	"errors"
	"strings"
	"sync"
	"time"

	"github.com/DongChyeon/droidprobe/cli/internal/proto"
)

const (
	describeTimeout = 10 * time.Second
	cancelTimeout   = 2 * time.Second
)

// entry is one retained or live item of a hub: a record, a new-process
// marker, or the final end or error.
type entry struct {
	kind string
	id   int64
	psid string
	data json.RawMessage
}

func (e entry) final() bool { return e.kind == "end" || e.kind == "error" }

// hub is one app watch shared by every browser that asked for the same
// command and input. It keeps the last HistoryLimit entries for late subscribers.
type hub struct {
	s     *Server
	cmd   string
	input json.RawMessage

	mu     sync.Mutex
	hist   []entry
	next   int64
	subs   map[chan entry]struct{}
	cancel context.CancelFunc // non-nil while a pump owns the watch
	runID  int
	cursor string // last event cursor, used to resume without gaps
	since  int    // 0 unknown, 1 the input schema declares since, -1 it does not
}

func canonicalInput(raw string) (json.RawMessage, error) {
	if strings.TrimSpace(raw) == "" {
		return json.RawMessage("{}"), nil
	}
	var obj map[string]any
	d := json.NewDecoder(strings.NewReader(raw))
	d.UseNumber()
	if err := d.Decode(&obj); err != nil || obj == nil || d.More() {
		return nil, errors.New("input must be a JSON object")
	}
	b, _ := json.Marshal(obj) // map keys marshal sorted, so equal inputs share a hub
	return b, nil
}

func (s *Server) hubFor(cmd string, input json.RawMessage) *hub {
	key := cmd + "\n" + string(input)
	s.mu.Lock()
	defer s.mu.Unlock()
	h := s.hubs[key]
	if h == nil {
		h = &hub{s: s, cmd: cmd, input: input, subs: map[chan entry]struct{}{}}
		s.hubs[key] = h
	}
	return h
}

// subscribe returns the retained history and a channel for later entries. The
// channel closes after a final event or when the subscriber falls behind.
func (h *hub) subscribe() (chan entry, []entry) {
	ch := make(chan entry, 256)
	h.mu.Lock()
	h.subs[ch] = struct{}{}
	hist := append([]entry(nil), h.hist...)
	h.mu.Unlock()
	h.ensure(h.s.current())
	return ch, hist
}

func (h *hub) unsubscribe(ch chan entry) {
	h.mu.Lock()
	defer h.mu.Unlock()
	if _, ok := h.subs[ch]; !ok {
		return
	}
	delete(h.subs, ch)
	close(ch)
	if len(h.subs) == 0 {
		h.stopLocked()
	}
}

func (h *hub) stopLocked() {
	if h.cancel != nil {
		h.cancel()
		h.cancel = nil
		h.runID++
	}
}

// abandon forgets a pump whose connection died so the next connection can start a fresh one.
func (h *hub) abandon() {
	h.mu.Lock()
	h.stopLocked()
	h.mu.Unlock()
}

func (h *hub) ensure(c *Conn) {
	if c == nil {
		return
	}
	h.mu.Lock()
	defer h.mu.Unlock()
	if h.cancel != nil || len(h.subs) == 0 {
		return
	}
	ctx, cancel := context.WithCancel(context.Background())
	h.cancel = cancel
	h.runID++
	go h.pump(ctx, c, h.runID)
}

func (h *hub) pump(ctx context.Context, c *Conn, id int) {
	st, err := c.Client.Watch(h.cmd, h.watchInput(ctx, c))
	if err != nil {
		h.stopped(id, err)
		return
	}
	psid := c.Hello.ProcessStartID
	for {
		data, err := st.Next(ctx)
		if err != nil {
			if ctx.Err() != nil {
				cctx, cancel := context.WithTimeout(context.Background(), cancelTimeout)
				st.Cancel(cctx)
				cancel()
				return
			}
			h.stopped(id, err)
			return
		}
		h.record(id, psid, data)
	}
}

// watchInput resumes after the last seen cursor of this process, or replays the
// app's whole retained journal for a new process, when the command takes since.
func (h *hub) watchInput(ctx context.Context, c *Conn) json.RawMessage {
	var obj map[string]json.RawMessage
	json.Unmarshal(h.input, &obj)
	if _, ok := obj["since"]; ok {
		return h.input
	}
	h.mu.Lock()
	known, cursor := h.since, h.cursor
	h.mu.Unlock()
	if known == 0 {
		dctx, cancel := context.WithTimeout(ctx, describeTimeout)
		data, err := c.Client.Call(dctx, "describe", h.cmd, nil)
		cancel()
		if err != nil {
			return h.input
		}
		var d struct {
			Input struct {
				Properties map[string]json.RawMessage `json:"properties"`
			} `json:"input"`
		}
		known = -1
		if json.Unmarshal(data, &d) == nil {
			if _, ok := d.Input.Properties["since"]; ok {
				known = 1
			}
		}
		h.mu.Lock()
		h.since = known
		h.mu.Unlock()
	}
	if known != 1 {
		return h.input
	}
	psid := c.Hello.ProcessStartID
	if !strings.HasPrefix(cursor, psid+":") {
		cursor = psid + ":0"
	}
	if obj == nil {
		obj = map[string]json.RawMessage{}
	}
	obj["since"], _ = json.Marshal(cursor)
	b, _ := json.Marshal(obj)
	return b
}

func (h *hub) record(id int, psid string, data json.RawMessage) {
	var c struct {
		Cursor string `json:"cursor"`
	}
	json.Unmarshal(data, &c)
	h.mu.Lock()
	defer h.mu.Unlock()
	if id != h.runID {
		return
	}
	if c.Cursor != "" {
		h.cursor = c.Cursor
	}
	h.appendLocked(entry{kind: "record", psid: psid, data: data})
}

func (h *hub) marker(data []byte) {
	h.mu.Lock()
	defer h.mu.Unlock()
	h.appendLocked(entry{kind: "marker", data: data})
}

func (h *hub) appendLocked(ev entry) {
	h.next++
	ev.id = h.next
	h.hist = append(h.hist, ev)
	if limit := h.s.opts.HistoryLimit; len(h.hist) > limit+limit/4 {
		h.hist = append([]entry(nil), h.hist[len(h.hist)-limit:]...)
	}
	h.fanoutLocked(ev)
}

func (h *hub) fanoutLocked(ev entry) {
	for ch := range h.subs {
		select {
		case ch <- ev:
		default:
			// The browser fell behind; EventSource reconnects and replays history.
			delete(h.subs, ch)
			close(ch)
		}
	}
	if len(h.subs) == 0 {
		h.stopLocked()
	}
}

// stopped handles the end of a watch. end and app errors are final for every
// subscriber; connection errors are not, since the next connection resumes it.
func (h *hub) stopped(id int, err error) {
	var ae *proto.AppError
	var final entry
	switch {
	case errors.Is(err, proto.ErrEnd):
		final = entry{kind: "end", data: json.RawMessage("{}")}
	case errors.As(err, &ae):
		b, _ := json.Marshal(ae)
		final = entry{kind: "error", data: b}
	}
	h.mu.Lock()
	defer h.mu.Unlock()
	if id != h.runID {
		return
	}
	h.cancel = nil
	if !final.final() {
		return
	}
	for ch := range h.subs {
		select {
		case ch <- final:
		default:
		}
		delete(h.subs, ch)
		close(ch)
	}
}
