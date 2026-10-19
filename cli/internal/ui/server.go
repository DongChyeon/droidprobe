// Package ui serves the local web dashboard behind `probe ui`. It keeps one
// connection to the app and multiplexes browser requests onto it.
package ui

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"net"
	"net/http"
	"strings"
	"sync"
	"time"

	"github.com/DongChyeon/droidprobe/cli/internal/proto"
)

// Conn is one established app connection; Close also removes its adb forward.
type Conn struct {
	Client *proto.Client
	Hello  proto.Hello
	Close  func()
}

type Options struct {
	// Dial connects and completes hello. It is retried every RetryInterval until it succeeds.
	Dial          func(ctx context.Context) (*Conn, error)
	AllowActions  bool
	HistoryLimit  int
	RetryInterval time.Duration
	Serial        string
	Logf          func(format string, a ...any)
}

// Status is what /api/status reports.
type Status struct {
	State          string `json:"state"` // connecting, connected or disconnected
	Serial         string `json:"serial,omitempty"`
	Package        string `json:"package,omitempty"`
	PID            int    `json:"pid,omitempty"`
	ProcessStartID string `json:"processStartId,omitempty"`
	SDK            string `json:"sdk,omitempty"`
	Protocol       string `json:"protocol,omitempty"`
	Error          string `json:"error,omitempty"`
	Connects       int    `json:"connects"`
	AllowActions   bool   `json:"allowActions"`
	Since          string `json:"since"`
}

type sseEvent struct {
	name string
	id   int64
	data []byte
}

type Server struct {
	opts Options

	mu         sync.Mutex
	conn       *Conn
	status     Status
	lastPSID   string
	statusSubs map[chan sseEvent]struct{}
	hubs       map[string]*hub
	buses      map[string]*bus
}

func New(opts Options) *Server {
	if opts.HistoryLimit <= 0 {
		opts.HistoryLimit = 2000
	}
	if opts.RetryInterval <= 0 {
		opts.RetryInterval = time.Second
	}
	if opts.Logf == nil {
		opts.Logf = func(string, ...any) {}
	}
	return &Server{
		opts:       opts,
		status:     Status{State: "connecting", Serial: opts.Serial, AllowActions: opts.AllowActions, Since: now()},
		statusSubs: map[chan sseEvent]struct{}{},
		hubs:       map[string]*hub{},
		buses:      map[string]*bus{},
	}
}

func now() string { return time.Now().UTC().Format(time.RFC3339Nano) }

// Listen binds a loopback TCP address; anything but 127.0.0.1 is refused because
// the dashboard can drive the app without further authentication.
func Listen(addr string) (net.Listener, error) {
	host, _, err := net.SplitHostPort(addr)
	if err != nil {
		return nil, err
	}
	if host != "127.0.0.1" {
		return nil, fmt.Errorf("refusing to listen on %q: probe ui binds 127.0.0.1 only", host)
	}
	return net.Listen("tcp", addr)
}

// Serve runs the connection manager and the HTTP server until ctx is done.
func (s *Server) Serve(ctx context.Context, ln net.Listener) error {
	hs := &http.Server{Handler: s.Handler(), ReadHeaderTimeout: 10 * time.Second}
	runDone := make(chan struct{})
	go func() {
		s.Run(ctx)
		close(runDone)
	}()
	errc := make(chan error, 1)
	go func() { errc <- hs.Serve(ln) }()
	var err error
	select {
	case <-ctx.Done():
	case err = <-errc:
	}
	hs.Close()
	<-runDone
	if errors.Is(err, http.ErrServerClosed) {
		err = nil
	}
	return err
}

// Run keeps one app connection alive until ctx is done, then closes it.
func (s *Server) Run(ctx context.Context) {
	for {
		c, err := s.opts.Dial(ctx)
		if ctx.Err() != nil {
			if c != nil {
				c.Close()
			}
			return
		}
		if err != nil {
			s.setStatus(func(st *Status) {
				if st.State == "connected" || st.Connects > 0 {
					st.State = "disconnected"
				}
				st.Error = err.Error()
			})
		} else {
			s.attach(c)
			select {
			case <-c.Client.Done():
			case <-ctx.Done():
			}
			s.detach(c)
			if ctx.Err() != nil {
				return
			}
		}
		select {
		case <-ctx.Done():
			return
		case <-time.After(s.opts.RetryInterval):
		}
	}
}

func (s *Server) attach(c *Conn) {
	h := c.Hello
	s.mu.Lock()
	prev := s.lastPSID
	s.conn = c
	s.lastPSID = h.ProcessStartID
	s.status = Status{
		State: "connected", Serial: s.opts.Serial, Package: h.Package, PID: h.PID,
		ProcessStartID: h.ProcessStartID, SDK: h.SDK, Protocol: h.Protocol,
		Connects: s.status.Connects + 1, AllowActions: s.opts.AllowActions, Since: now(),
	}
	hubs := s.hubList()
	s.mu.Unlock()

	s.opts.Logf("connected to %s pid %d (process %s)", h.Package, h.PID, h.ProcessStartID)
	if prev != "" && prev != h.ProcessStartID {
		marker, _ := json.Marshal(map[string]string{"from": prev, "to": h.ProcessStartID, "ts": now()})
		for _, hb := range hubs {
			hb.marker(marker)
		}
		s.broadcast(sseEvent{name: "new-process", data: marker})
	}
	s.broadcastStatus()
	for _, hb := range hubs {
		hb.ensure(c)
	}
}

func (s *Server) detach(c *Conn) {
	s.mu.Lock()
	if s.conn == c {
		s.conn = nil
	}
	s.status.State = "disconnected"
	s.status.Error = ""
	s.status.Since = now()
	hubs := s.hubList()
	s.mu.Unlock()
	for _, hb := range hubs {
		hb.abandon()
	}
	c.Close()
	s.opts.Logf("connection lost; retrying every %s", s.opts.RetryInterval)
	s.broadcastStatus()
}

func (s *Server) hubList() []*hub {
	hubs := make([]*hub, 0, len(s.hubs))
	for _, h := range s.hubs {
		hubs = append(hubs, h)
	}
	return hubs
}

func (s *Server) current() *Conn {
	s.mu.Lock()
	defer s.mu.Unlock()
	return s.conn
}

func (s *Server) setStatus(f func(*Status)) {
	s.mu.Lock()
	before := s.status
	f(&s.status)
	changed := s.status != before
	if changed {
		s.status.Since = now()
	}
	s.mu.Unlock()
	if changed {
		s.broadcastStatus()
	}
}

func (s *Server) snapshot() Status {
	s.mu.Lock()
	defer s.mu.Unlock()
	return s.status
}

func (s *Server) broadcastStatus() {
	b, _ := json.Marshal(s.snapshot())
	s.broadcast(sseEvent{name: "status", data: b})
}

func (s *Server) broadcast(ev sseEvent) {
	s.mu.Lock()
	defer s.mu.Unlock()
	for ch := range s.statusSubs {
		select {
		case ch <- ev:
		default:
			// A stalled browser is dropped; EventSource reconnects and gets a fresh snapshot.
			delete(s.statusSubs, ch)
			close(ch)
		}
	}
}

func (s *Server) subscribeStatus() (chan sseEvent, Status) {
	ch := make(chan sseEvent, 32)
	s.mu.Lock()
	defer s.mu.Unlock()
	s.statusSubs[ch] = struct{}{}
	return ch, s.status
}

func (s *Server) unsubscribeStatus(ch chan sseEvent) {
	s.mu.Lock()
	defer s.mu.Unlock()
	if _, ok := s.statusSubs[ch]; ok {
		delete(s.statusSubs, ch)
		close(ch)
	}
}

var readOnlySuffixes = []string{".state", ".records", ".record", ".list", ".templates", ".activities", ".fragments", ".process"}

// ReadOnly reports whether a call command may run without --allow-actions.
func ReadOnly(command string) bool {
	if command == "probe.info" {
		return true
	}
	for _, suf := range readOnlySuffixes {
		if strings.HasSuffix(command, suf) {
			return true
		}
	}
	return false
}
