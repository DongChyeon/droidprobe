// droidprobe dashboard. Talks only to the local probe ui backend (/api/*).

const MAX_ROWS = 2000;

// ---------- helpers ----------

function h(tag, attrs, ...children) {
  const el = document.createElement(tag);
  for (const [k, v] of Object.entries(attrs || {})) {
    if (v == null || v === false) continue;
    if (k === 'class') el.className = v;
    else if (k === 'text') el.textContent = v;
    else if (k.startsWith('on')) el.addEventListener(k.slice(2), v);
    else if (k in el && typeof v !== 'string') el[k] = v;
    else el.setAttribute(k, v === true ? '' : v);
  }
  for (const c of children.flat(Infinity)) {
    if (c == null || c === false) continue;
    el.append(c instanceof Node ? c : document.createTextNode(String(c)));
  }
  return el;
}

function clear(el, ...children) {
  el.replaceChildren(...children.flat(Infinity).filter((c) => c != null && c !== false));
  return el;
}

function escapeHTML(s) {
  return s.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;');
}

// Pretty JSON with light syntax colouring; input is escaped before tokens are wrapped.
function jsonPre(value, cls = 'json') {
  const pre = h('pre', { class: cls });
  const text = typeof value === 'string' ? value : JSON.stringify(value, null, 2);
  if (text === undefined) { pre.textContent = 'undefined'; return pre; }
  pre.innerHTML = escapeHTML(text).replace(
    /("(\\u[a-fA-F0-9]{4}|\\[^u]|[^\\"])*"(\s*:)?|\b(true|false|null)\b|-?\d+(?:\.\d+)?(?:[eE][+-]?\d+)?)/g,
    (m) => {
      let c = 'j-num';
      if (m.startsWith('"')) c = m.trimEnd().endsWith(':') ? 'j-key' : 'j-str';
      else if (/true|false|null/.test(m)) c = 'j-lit';
      return `<span class="${c}">${m}</span>`;
    });
  return pre;
}

function bodyPre(body) {
  if (body == null) return h('div', { class: 'dim', text: '(none)' });
  try {
    const t = body.trim();
    if (t.startsWith('{') || t.startsWith('[')) return jsonPre(JSON.parse(t));
  } catch { /* not JSON */ }
  return h('pre', { class: 'box', text: body });
}

function fmtTime(ts) {
  if (ts == null) return '';
  const d = new Date(ts);
  if (isNaN(d)) return String(ts);
  const p = (n, w = 2) => String(n).padStart(w, '0');
  return `${p(d.getHours())}:${p(d.getMinutes())}:${p(d.getSeconds())}.${p(d.getMilliseconds(), 3)}`;
}

function ago(ms) {
  const s = Math.max(0, Math.round((Date.now() - ms) / 1000));
  if (s < 60) return `${s}s ago`;
  if (s < 3600) return `${Math.floor(s / 60)}m ago`;
  return `${Math.floor(s / 3600)}h ago`;
}

function shortClass(c) {
  if (!c) return '';
  const i = c.lastIndexOf('.');
  return i >= 0 ? c.slice(i + 1) : c;
}

function urlPath(u) {
  try { const x = new URL(u); return { path: x.pathname + x.search, host: x.host }; } catch { return { path: u || '', host: '' }; }
}

function debounce(fn, ms) {
  let t;
  return (...a) => { clearTimeout(t); t = setTimeout(() => fn(...a), ms); };
}

// ---------- API ----------

async function api(path, body) {
  const opt = body === undefined ? {} : {
    method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body),
  };
  const res = await fetch(path, opt);
  const text = await res.text();
  let json = null;
  try { json = text ? JSON.parse(text) : null; } catch { /* keep null */ }
  if (!res.ok) {
    const err = new Error(json?.error?.message || res.statusText);
    err.code = json?.error?.code || String(res.status);
    throw err;
  }
  return json;
}

const call = (command, input) => api('/api/call', { command, input: input || {} });

function errText(e) { return e.code ? `${e.code}: ${e.message}` : String(e.message || e); }

// ---------- bus ----------
// The page keeps exactly one EventSource (/api/bus). Browsers allow six HTTP/1.1
// connections per origin, so one EventSource per stream would starve fetch().

const bus = { id: null, subs: new Map(), shared: new Map(), next: 0 };

// Sub is one server subscription shared by every panel that opened the same
// cmd and input. It keeps what it delivered since the last reset so a panel
// that joins later gets the same replay the server would send.
class Sub {
  constructor(cmd, input, key) {
    Object.assign(this, { cmd, input, key, id: 'c' + (++bus.next), handlers: new Set(), log: [], started: false, isLive: false });
  }
  attach() {
    if (!bus.id) return;
    api('/api/subscribe', { bus: bus.id, sub: this.id, cmd: this.cmd, input: this.input }).catch((e) => {
      // A stale bus id resolves itself: the next "bus" event re-attaches everything.
      if (e.code !== 'UNKNOWN_BUS') this.deliver({ type: 'error', data: { code: e.code, message: e.message } });
    });
  }
  each(f) { for (const hd of [...this.handlers]) f(hd); }
  deliver(m) {
    switch (m.type) {
      case 'reset':
        this.log = []; this.started = true; this.isLive = false;
        this.each((hd) => hd.reset?.());
        break;
      case 'live':
        this.isLive = true;
        this.each((hd) => hd.live?.());
        break;
      case 'record':
      case 'marker':
        this.log.push(m);
        if (this.log.length > MAX_ROWS + 100) this.log.splice(0, this.log.length - MAX_ROWS);
        this.each((hd) => this.replay(hd, m));
        break;
      case 'end':
      case 'error':
        this.forget();
        this.each((hd) => (m.type === 'end' ? hd.end?.() : hd.error?.(m.data)));
        break;
    }
  }
  replay(hd, m) {
    if (m.type === 'record') hd.record?.(m.data, m.psid);
    else hd.marker?.(m.data);
  }
  add(hd) {
    this.handlers.add(hd);
    if (!this.started) return;
    hd.reset?.();
    for (const m of this.log) this.replay(hd, m);
    if (this.isLive) hd.live?.();
  }
  remove(hd) {
    this.handlers.delete(hd);
    if (this.handlers.size || !bus.subs.has(this.id)) return;
    this.forget();
    if (bus.id) api('/api/unsubscribe', { bus: bus.id, sub: this.id }).catch(() => {});
  }
  forget() {
    bus.subs.delete(this.id);
    if (bus.shared.get(this.key) === this) bus.shared.delete(this.key);
  }
}

// openStream subscribes on the bus; the returned close() drops this panel's handlers.
function openStream(cmd, input, on) {
  input = input || {};
  const key = cmd + '\n' + JSON.stringify(input);
  let sub = bus.shared.get(key);
  if (!sub) {
    sub = new Sub(cmd, input, key);
    bus.shared.set(key, sub);
    bus.subs.set(sub.id, sub);
    sub.attach();
  }
  sub.add(on);
  return { close: () => sub.remove(on) };
}

// ---------- global state ----------

const app = {
  status: { state: 'connecting' },
  commands: null,
  listeners: new Set(),
  get allowActions() { return !!this.status.allowActions; },
  get psid() { return this.status.processStartId; },
  emit(type, data) { for (const l of this.listeners) l(type, data); },
};

// Schemas only change with a new app process; the cache is cleared on new-process.
const describeCache = new Map();

let commandsPromise = null;
function loadCommands(force) {
  if (!commandsPromise || force) {
    commandsPromise = api('/api/list').then((r) => { app.commands = r.commands || []; return app.commands; })
      .catch((e) => { commandsPromise = null; throw e; });
  }
  return commandsPromise;
}

function markerRow(m, cols) {
  return h('tr', { class: 'marker' }, h('td', { colspan: cols, text: `new process ${m.to}  (was ${m.from})` }));
}

// emptyRow keeps a placeholder row in a table body exactly while it has no other rows.
function emptyRow(tbody, cols, text) {
  const ph = tbody.querySelector('tr.placeholder');
  const others = tbody.children.length - (ph ? 1 : 0);
  if (others && ph) ph.remove();
  if (!others && !ph) tbody.append(h('tr', { class: 'placeholder' }, h('td', { colspan: cols, class: 'empty', text })));
}

function toolbar(title, ...items) {
  return h('div', { class: 'toolbar' }, h('span', { class: 'title', text: title }), ...items);
}

// ---------- header ----------

function renderHeader() {
  const s = app.status;
  const conn = document.getElementById('conn');
  conn.className = 'conn ' + s.state;
  document.getElementById('conn-text').textContent =
    s.state === 'connected' ? 'connected' : s.state === 'disconnected' ? 'disconnected, retrying' : 'connecting';
  const t = document.getElementById('target');
  clear(t);
  if (s.serial) t.append(h('b', { text: s.serial }));
  if (s.package) t.append('  ', h('b', { text: s.package }));
  if (s.pid) t.append(`  pid ${s.pid}`);
  if (s.processStartId) t.append(`  process ${s.processStartId}`);
  if (s.sdk) t.append(`  sdk ${s.sdk}`);
  const mode = document.getElementById('mode');
  mode.className = 'mode' + (s.allowActions ? ' actions' : '');
  mode.textContent = s.allowActions ? 'Actions enabled' : 'Read-only';
  mode.title = s.allowActions
    ? 'State-changing commands (mocks, probe.config, startActivity, ...) are allowed'
    : 'State-changing commands are blocked; restart with probe ui --allow-actions to allow them';
  const banner = document.getElementById('banner');
  if (s.state !== 'connected') {
    banner.hidden = false;
    banner.className = 'banner';
    banner.textContent = (s.state === 'disconnected' ? 'Connection to the app lost. Retrying every second. ' : 'Waiting for the app. ')
      + (s.error || '');
  } else {
    banner.hidden = true;
  }
}

// Resolves on the first status event so panels know the mode before they render controls.
function openBus() {
  let ready;
  const first = new Promise((r) => { ready = r; });
  const es = new EventSource('/api/bus');
  // Each (re)connection is a new server bus; its subscriptions are gone, so attach them again.
  es.addEventListener('bus', (e) => {
    bus.id = JSON.parse(e.data).bus;
    for (const sub of bus.subs.values()) sub.attach();
  });
  es.addEventListener('error', () => { bus.id = null; });
  es.addEventListener('stream', (e) => {
    const m = JSON.parse(e.data);
    bus.subs.get(m.sub)?.deliver(m);
  });
  es.addEventListener('status', (e) => {
    const prev = app.status;
    app.status = JSON.parse(e.data);
    // A tab left open across a restart with a different --allow-actions keeps controls built for the old mode.
    if (prev.allowActions !== undefined && prev.allowActions !== app.status.allowActions) {
      location.reload();
      return;
    }
    renderHeader();
    if (app.status.state === 'connected' && (prev.state !== 'connected' || prev.processStartId !== app.status.processStartId)) {
      app.emit('connected', app.status);
    }
    ready();
  });
  es.addEventListener('new-process', (e) => {
    loadCommands(true).catch(() => {});
    describeCache.clear();
    app.emit('new-process', JSON.parse(e.data));
  });
  return first;
}

// ---------- Overview ----------

const overview = {
  init(el) {
    this.cards = {
      app: this.card('App'), process: this.card('Process'),
      activities: this.card('Activities', true), fragments: this.card('Fragments'), transitions: this.card('Lifecycle transitions'),
    };
    this.transitions = [];
    clear(el,
      toolbar('Overview', h('span', { class: 'meta', id: 'ov-meta' }), h('span', { class: 'spacer' }),
        h('button', { text: 'Refresh', onclick: () => this.refresh() })),
      h('div', { class: 'cards wide' }, Object.values(this.cards).map((c) => c.el)));
    this.refreshSoon = debounce(() => this.refresh(), 300);
    app.listeners.add((type) => { if (type === 'connected' || type === 'new-process') this.refreshSoon(); });
    // lifecycle.watch only triggers refreshes; the list comes from the journaled
    // lifecycle.* events, which include what happened before the page opened.
    openStream('lifecycle.watch', {}, { record: () => this.refreshSoon() });
    openStream('events.watch', {}, {
      reset: () => { this.transitions = []; this.renderTransitions(); },
      record: (ev) => {
        if (ev.source !== 'lifecycle') return;
        this.transitions.unshift({ ts: ev.ts, kind: ev.name.split('.')[1], ...ev.params });
        this.transitions.length = Math.min(this.transitions.length, 100);
        this.renderTransitionsSoon();
      },
      marker: (m) => { this.transitions.unshift({ marker: m }); this.renderTransitionsSoon(); },
    });
    this.renderTransitionsSoon = debounce(() => this.renderTransitions(), 50);
    this.renderTransitions();
    this.refresh();
  },
  card(title, span2) {
    const meta = h('span', { class: 'meta' });
    const body = h('div', { class: 'body' });
    return { el: h('div', { class: 'card' + (span2 ? ' span2' : '') }, h('header', {}, h('h2', { text: title }), meta), body), body, meta };
  },
  async refresh() {
    const [info, proc, acts, frags] = await Promise.allSettled([
      call('probe.info'), call('lifecycle.process'), call('lifecycle.activities'), call('lifecycle.fragments'),
    ]);
    document.getElementById('ov-meta').textContent = 'updated ' + fmtTime(Date.now());
    this.renderApp(info);
    this.renderProcess(proc);
    this.renderActivities(acts);
    this.renderFragments(frags);
  },
  failed(card, r) {
    const e = r.reason;
    let msg = errText(e);
    if (e.code === 'UNKNOWN_COMMAND') msg = 'Not available: the app does not include probe-lifecycle.';
    else if (/no resumed Activity/.test(e.message)) msg = 'No resumed Activity (the app is in the background).';
    clear(card.body, h('div', { class: 'empty', text: msg }));
  },
  kv(pairs) {
    return h('dl', { class: 'kv' }, pairs.filter(([, v]) => v !== undefined).map(([k, v]) => [h('dt', { text: k }), h('dd', { text: v === null ? 'null' : String(v) })]));
  },
  renderApp(r) {
    if (r.status === 'rejected') return this.failed(this.cards.app, r);
    const d = r.value;
    const s = app.status;
    clear(this.cards.app.body, this.kv([
      ['device', s.serial], ['package', d.package], ['pid', d.pid], ['processStartId', d.processStartId],
      ['sdk', d.sdk], ['protocol', d.protocol], ['headCursor', d.headCursor], ['recording', d.recording],
      ['networkCapacity', d.config?.networkCapacity], ['eventCapacity', d.config?.eventCapacity], ['bodyLimitBytes', d.config?.bodyLimitBytes],
    ]));
  },
  renderProcess(r) {
    if (r.status === 'rejected') return this.failed(this.cards.process, r);
    const d = r.value;
    const up = d.uptimeMs != null ? `${Math.round(d.uptimeMs / 1000)} s` : undefined;
    clear(this.cards.process.body, this.kv([
      ['processName', d.processName], ['pid', d.pid], ['importance', `${d.importanceName} (${d.importance})`],
      ['foreground', d.isForeground], ['uptime', up], ['lastTrimLevel', d.lastTrimLevel], ['appStandbyBucket', d.appStandbyBucket],
    ]));
  },
  renderActivities(r) {
    const c = this.cards.activities;
    if (r.status === 'rejected') return this.failed(c, r);
    const list = r.value || [];
    const resumed = list.find((a) => a.resumed);
    c.meta.textContent = resumed ? 'resumed: ' + shortClass(resumed.class) : 'nothing resumed';
    c.body.className = 'body flush';
    if (!list.length) return clear(c.body, h('div', { class: 'empty', text: 'No activities.' }));
    clear(c.body, h('table', {},
      h('thead', {}, h('tr', {}, ['task', 'activity', 'state', 'intent', 'extras'].map((t) => h('th', { class: t === 'task' ? 'num' : null, text: t })))),
      h('tbody', {}, list.map((a) => h('tr', { class: a.resumed ? 'sel' : '' },
        h('td', { class: 'num', text: a.taskId }),
        h('td', { class: 'mono', title: a.class, text: shortClass(a.class) + (a.isFinishing ? ' (finishing)' : '') }),
        h('td', {}, h('span', { class: 'badge' + (a.resumed ? ' mocked' : ''), text: a.state })),
        h('td', { class: 'mono dim', text: a.intent?.action || '' }),
        h('td', { class: 'mono dim', text: (a.intent?.extraKeys || []).join(', ') }))))));
  },
  renderFragments(r) {
    const c = this.cards.fragments;
    if (r.status === 'rejected') return this.failed(c, r);
    const d = r.value || {};
    c.meta.textContent = d.class ? 'in ' + shortClass(d.class) : '';
    const tree = (frags) => h('ul', { class: 'tree' }, frags.map((f) => h('li', {},
      h('span', { class: 'cls', title: f.class, text: shortClass(f.class) }), ' ',
      f.tag ? h('span', { class: 'dim', text: `#${f.tag} ` }) : null,
      h('span', { class: 'badge', text: f.state }), ' ',
      f.isVisible ? null : h('span', { class: 'dim', text: 'hidden ' }),
      f.argumentsKeys?.length ? h('span', { class: 'dim', text: `args: ${f.argumentsKeys.join(', ')}` }) : null,
      f.children?.length ? tree(f.children) : null)));
    clear(c.body, d.fragments?.length ? tree(d.fragments) : h('div', { class: 'empty', text: 'No fragments in the resumed Activity.' }));
  },
  renderTransitions() {
    const c = this.cards.transitions;
    c.body.className = 'body flush';
    if (!this.transitions.length) return clear(c.body, h('div', { class: 'empty', text: 'No lifecycle.* events journaled yet.' }));
    clear(c.body, h('table', {}, h('tbody', {}, this.transitions.map((t) => t.marker ? markerRow(t.marker, 4) : h('tr', {},
      h('td', { class: 'time', text: fmtTime(t.ts) }),
      h('td', { class: 'dim', text: t.kind }),
      h('td', { class: 'mono trunc', title: t.class, text: shortClass(t.class) }),
      h('td', { class: 'mono', text: `${t.from ?? '-'} → ${t.to}` }))))));
  },
};

// ---------- Network ----------

function statusClass(r) {
  if (r.error || r.status == null) return 'st-err';
  return 'st-' + String(r.status)[0];
}

const network = {
  init(el) {
    this.rows = [];
    this.selected = null;
    this.filter = h('input', { type: 'search', placeholder: 'Filter: url, method, status', oninput: debounce(() => this.render(), 120) });
    this.onlyMocked = h('input', { type: 'checkbox', onchange: () => this.render() });
    this.onlyErrors = h('input', { type: 'checkbox', onchange: () => this.render() });
    this.meta = h('span', { class: 'meta' });
    this.tbody = h('tbody');
    this.detail = h('div', { class: 'detail', hidden: true });
    clear(el,
      toolbar('Network', this.filter, h('label', { class: 'check' }, this.onlyMocked, 'mocked'),
        h('label', { class: 'check' }, this.onlyErrors, 'errors'), h('span', { class: 'spacer' }), this.meta,
        h('button', { text: 'Clear view', onclick: () => { this.rows = []; this.render(); } })),
      h('div', { class: 'split' },
        h('div', { class: 'scroll' }, h('table', {},
          h('thead', {}, h('tr', {}, h('th', { text: 'time' }), h('th', { text: 'method' }), h('th', { text: 'path' }),
            h('th', { class: 'num', text: 'status' }), h('th', { class: 'num', text: 'ms' }), h('th', { text: '' }))),
          this.tbody)),
        this.detail));
    openStream('network.watch', {}, {
      reset: () => { this.rows = []; this.render(); },
      record: (r, psid) => this.add({ r, psid }),
      marker: (m) => this.add({ marker: m }),
      error: (e) => { this.meta.textContent = 'network.watch: ' + errText(e); },
    });
  },
  add(item) {
    this.rows.push(item);
    if (this.rows.length > MAX_ROWS) this.rows.splice(0, this.rows.length - MAX_ROWS);
    document.getElementById('count-network').textContent = this.rows.filter((x) => x.r).length || '';
    if (this.matches(item)) this.tbody.prepend(this.row(item));
    this.updateMeta();
  },
  matches(item) {
    if (item.marker) return true;
    const r = item.r;
    if (this.onlyMocked.checked && !r.mocked) return false;
    if (this.onlyErrors.checked && !(r.error || r.status == null || r.status >= 400)) return false;
    const q = this.filter.value.trim().toLowerCase();
    if (!q) return true;
    return `${r.method} ${r.url} ${r.status ?? ''} ${r.error ?? ''}`.toLowerCase().includes(q);
  },
  row(item) {
    if (item.marker) return markerRow(item.marker, 6);
    const r = item.r;
    const { path, host } = urlPath(r.url);
    const tr = h('tr', { class: 'click' + (this.selected === item ? ' sel' : ''), onclick: () => this.select(item, tr) },
      h('td', { class: 'time', text: fmtTime(r.ts) }),
      h('td', { class: 'mono', text: r.method }),
      h('td', { class: 'mono trunc', title: r.url, text: path || host }),
      h('td', { class: 'num ' + statusClass(r), text: r.error ? 'failed' : r.status }),
      h('td', { class: 'num', text: r.durationMs ?? '' }),
      h('td', {}, r.mocked ? h('span', { class: 'badge mocked', title: r.mockRuleId || '', text: 'mocked' }) : null));
    return tr;
  },
  render() {
    clear(this.tbody, this.rows.filter((x) => this.matches(x)).reverse().map((x) => this.row(x)));
    this.updateMeta();
  },
  updateMeta() {
    emptyRow(this.tbody, 6, 'No requests recorded in this process yet.');
    const n = this.rows.filter((x) => x.r).length;
    this.meta.textContent = `${this.tbody.querySelectorAll('tr.click').length} of ${n}`;
  },
  async select(item, tr) {
    this.selected = item;
    this.tbody.querySelectorAll('tr.sel').forEach((x) => x.classList.remove('sel'));
    tr.classList.add('sel');
    let r = item.r;
    let note = null;
    if (item.psid === app.psid) {
      try { r = await call('network.record', { id: r.id }); } catch (e) { note = 'network.record failed: ' + errText(e) + '; showing the streamed record.'; }
    } else {
      note = 'From an earlier process; showing the streamed record (bodies over 4096 chars omitted).';
    }
    if (this.selected !== item) return;
    this.showDetail(r, note);
  },
  showDetail(r, note) {
    const headers = (hs) => hs && Object.keys(hs).length
      ? h('dl', { class: 'kv' }, Object.entries(hs).map(([k, v]) => [h('dt', { text: k }), h('dd', { text: Array.isArray(v) ? v.join(', ') : v })]))
      : h('div', { class: 'dim', text: '(none)' });
    const body = (b, size, truncated, omitted) => {
      if (truncated) return h('div', { class: 'dim', text: `Not stored: larger than bodyLimitBytes (${size ?? 'unknown'} bytes).` });
      if (b == null && omitted) return h('div', { class: 'dim', text: 'Omitted from the stream (over 4096 chars).' });
      if (b == null && size) return h('div', { class: 'dim', text: `${size} bytes, not textual.` });
      return bodyPre(b);
    };
    this.detail.hidden = false;
    clear(this.detail,
      h('button', { class: 'close', text: 'Close', onclick: () => { this.detail.hidden = true; this.selected = null; this.tbody.querySelectorAll('tr.sel').forEach((x) => x.classList.remove('sel')); } }),
      h('div', { class: 'url' }, h('b', { text: r.method + ' ' }), r.url),
      note ? h('div', { class: 'desc', text: note }) : null,
      h('h3', { text: 'General' }),
      h('dl', { class: 'kv' },
        h('dt', { text: 'status' }), h('dd', { class: statusClass(r), text: r.error ? 'failed' : r.status }),
        h('dt', { text: 'duration' }), h('dd', { text: `${r.durationMs ?? '?'} ms` }),
        h('dt', { text: 'time' }), h('dd', { text: r.ts }),
        h('dt', { text: 'id / cursor' }), h('dd', { text: `${r.id}  ${r.cursor}` }),
        r.mocked ? [h('dt', { text: 'mocked by' }), h('dd', { text: r.mockRuleId || 'yes' })] : null,
        r.error ? [h('dt', { text: 'error' }), h('dd', { class: 'st-err', text: r.error })] : null),
      h('h3', { text: 'Request headers' }), headers(r.requestHeaders),
      h('h3', { text: `Request body ${r.requestBodySize ? `(${r.requestBodySize} bytes)` : ''}` }),
      body(r.requestBody, r.requestBodySize, r.requestBodyTruncated, r.bodyOmitted),
      h('h3', { text: 'Response headers' }), headers(r.responseHeaders),
      h('h3', { text: `Response body ${r.responseBodySize ? `(${r.responseBodySize} bytes)` : ''}` }),
      body(r.responseBody, r.responseBodySize, r.responseBodyTruncated, r.bodyOmitted));
  },
};

// ---------- Events ----------

const events = {
  init(el) {
    this.rows = [];
    this.sources = new Map(); // source -> enabled
    this.filter = h('input', { type: 'search', placeholder: 'Filter by name', oninput: debounce(() => this.render(), 120) });
    this.chips = h('div', { class: 'chips' });
    this.meta = h('span', { class: 'meta' });
    this.tbody = h('tbody');
    clear(el,
      toolbar('Events', this.filter, this.chips, h('span', { class: 'spacer' }), this.meta,
        h('button', { text: 'Clear view', onclick: () => { this.rows = []; this.render(); } })),
      h('div', { class: 'scroll' }, h('table', {},
        h('thead', {}, h('tr', {}, h('th', { text: 'time' }), h('th', { text: 'source' }), h('th', { text: 'name' }), h('th', { text: 'params' }))),
        this.tbody)));
    openStream('events.watch', {}, {
      reset: () => { this.rows = []; this.render(); },
      record: (ev) => this.add({ ev }),
      marker: (m) => this.add({ marker: m }),
      error: (e) => { this.meta.textContent = 'events.watch: ' + errText(e); },
    });
  },
  srcName(ev) { return ev.source ?? '(none)'; },
  add(item) {
    this.rows.push(item);
    if (this.rows.length > MAX_ROWS) this.rows.splice(0, this.rows.length - MAX_ROWS);
    document.getElementById('count-events').textContent = this.rows.filter((x) => x.ev).length || '';
    if (item.ev && !this.sources.has(this.srcName(item.ev))) {
      this.sources.set(this.srcName(item.ev), true);
      this.renderChips();
    }
    if (this.matches(item)) this.tbody.prepend(this.row(item));
    this.updateMeta();
  },
  renderChips() {
    clear(this.chips, [...this.sources.entries()].sort().map(([s, on]) => h('button', {
      class: 'chip ' + (on ? 'on' : 'off'), text: s, title: 'Click to toggle, double-click to show only this source',
      onclick: () => { this.sources.set(s, !this.sources.get(s)); this.renderChips(); this.render(); },
      ondblclick: () => { for (const k of this.sources.keys()) this.sources.set(k, k === s); this.renderChips(); this.render(); },
    })));
  },
  matches(item) {
    if (item.marker) return true;
    if (this.sources.get(this.srcName(item.ev)) === false) return false;
    const q = this.filter.value.trim().toLowerCase();
    return !q || item.ev.name.toLowerCase().includes(q);
  },
  row(item) {
    if (item.marker) return markerRow(item.marker, 4);
    const ev = item.ev;
    const params = ev.params && Object.keys(ev.params).length ? JSON.stringify(ev.params) : '';
    const tr = h('tr', { class: 'click', onclick: () => {
      if (tr.nextSibling?.classList.contains('expanded')) { tr.nextSibling.remove(); return; }
      tr.after(h('tr', { class: 'expanded' }, h('td', { colspan: 4 }, jsonPre(ev))));
    } },
    h('td', { class: 'time', text: fmtTime(ev.ts) }),
    h('td', {}, h('span', { class: 'badge', text: this.srcName(ev) })),
    h('td', { class: 'mono', text: ev.name }),
    h('td', { class: 'mono trunc dim', title: params, text: params }));
    return tr;
  },
  render() {
    clear(this.tbody, this.rows.filter((x) => this.matches(x)).reverse().map((x) => this.row(x)));
    this.updateMeta();
  },
  updateMeta() {
    emptyRow(this.tbody, 4, 'No events recorded in this process yet.');
    this.meta.textContent = `${this.tbody.querySelectorAll('tr.click').length} of ${this.rows.filter((x) => x.ev).length}`;
  },
};

// ---------- State ----------

const state = {
  init(el) {
    this.grid = h('div', { class: 'cards' });
    this.cards = new Map();
    clear(el, toolbar('State', h('span', { class: 'meta', text: 'live values of every *.state.watch command' })), this.grid);
    this.load();
    setInterval(() => { for (const c of this.cards.values()) if (c.changed) c.meta.textContent = 'changed ' + ago(c.changed); }, 1000);
    app.listeners.add((type) => { if (type === 'new-process' || type === 'connected') this.load(); });
  },
  async load() {
    let cmds;
    try { cmds = await loadCommands(); } catch (e) {
      if (!this.cards.size) clear(this.grid, h('div', { class: 'empty', text: errText(e) }));
      return;
    }
    const watches = cmds.filter((c) => c.name.endsWith('.state.watch'));
    if (!watches.length) {
      clear(this.grid, h('div', { class: 'empty', text: 'The app exposes no *.state.watch commands. Register one with probe.state("name", flow).' }));
      return;
    }
    if (!this.cards.size) clear(this.grid);
    for (const c of watches) if (!this.cards.has(c.name)) this.add(c);
  },
  add(c) {
    const meta = h('span', { class: 'meta', text: 'waiting' });
    const body = h('div', { class: 'body' });
    const el = h('div', { class: 'card' },
      h('header', {}, h('h2', { title: c.name, text: c.name.replace(/\.state\.watch$/, '') }), meta),
      c.description ? h('div', { class: 'body desc', text: c.description }) : null, body);
    const card = { el, meta, body, changed: null };
    this.cards.set(c.name, card);
    this.grid.append(el);
    openStream(c.name, {}, {
      record: (v) => {
        card.changed = Date.now();
        meta.textContent = 'changed just now';
        clear(body, jsonPre(v));
        el.classList.remove('flash'); void el.offsetWidth; el.classList.add('flash');
      },
      end: () => { meta.textContent = 'stream ended'; },
      error: (e) => clear(body, h('pre', { class: 'box error', text: errText(e) })),
    });
  },
};

// ---------- Commands ----------

function schemaType(s) {
  let t = s?.type;
  if (Array.isArray(t)) { t = t.filter((x) => x !== 'null'); t = t.length === 1 ? t[0] : null; }
  if (!t && s?.properties) t = 'object';
  return t;
}

// buildForm renders inputs for a schema subset; get() returns the value or undefined when empty.
function buildForm(schema, name, required) {
  const type = schemaType(schema);
  const desc = schema?.description;
  const labelEl = name != null ? h('label', { title: name }, name, required ? h('span', { class: 'req', text: ' *' }) : null) : null;
  const wrap = (input) => name == null ? input
    : h('div', {}, h('div', { class: 'field' }, labelEl, input), desc ? h('div', { class: 'field' }, h('span'), h('div', { class: 'help', text: desc })) : null);

  if (type === 'object' && schema.properties && Object.keys(schema.properties).length) {
    const req = new Set(schema.required || []);
    const kids = Object.entries(schema.properties).map(([k, s]) => [k, buildForm(s, k, req.has(k))]);
    const el = name == null
      ? h('div', { class: 'form' }, kids.map(([, f]) => f.el))
      : h('fieldset', {}, h('legend', { text: name + (required ? ' *' : '') }), desc ? h('div', { class: 'help', text: desc }) : null, kids.map(([, f]) => f.el));
    return {
      el,
      get() {
        const out = {};
        for (const [k, f] of kids) { const v = f.get(); if (v !== undefined) out[k] = v; }
        return Object.keys(out).length || name == null ? out : undefined;
      },
    };
  }
  if (schema?.enum) {
    const sel = h('select', {}, h('option', { value: '', text: '(unset)' }), schema.enum.map((v) => h('option', { value: JSON.stringify(v), text: String(v) })));
    return { el: wrap(sel), get: () => sel.value === '' ? undefined : JSON.parse(sel.value) };
  }
  if (type === 'string') {
    const inp = h('input', { type: 'text' });
    return { el: wrap(inp), get: () => inp.value === '' ? undefined : inp.value };
  }
  if (type === 'integer' || type === 'number') {
    const inp = h('input', { type: 'number', step: type === 'integer' ? '1' : 'any' });
    return { el: wrap(inp), get: () => inp.value === '' ? undefined : Number(inp.value) };
  }
  if (type === 'boolean') {
    const sel = h('select', {}, h('option', { value: '', text: '(unset)' }), h('option', { value: 'true', text: 'true' }), h('option', { value: 'false', text: 'false' }));
    return { el: wrap(sel), get: () => sel.value === '' ? undefined : sel.value === 'true' };
  }
  // Arrays, open objects and mixed types fall back to raw JSON.
  const ta = h('textarea', { rows: name == null ? 6 : 3, placeholder: name == null ? '{}' : 'JSON' });
  return {
    el: wrap(ta),
    get() {
      if (ta.value.trim() === '') return undefined;
      try { return JSON.parse(ta.value); } catch (e) { throw new Error(`${name ?? 'input'}: invalid JSON (${e.message})`); }
    },
  };
}

const commands = {
  init(el) {
    this.search = h('input', { type: 'search', placeholder: 'Search commands', oninput: () => this.renderList() });
    this.list = h('div', { class: 'scroll' });
    this.pane = h('div', { class: 'cmdpane' });
    clear(el, h('div', { class: 'split' },
      h('div', { class: 'cmdlist' }, h('div', { class: 'toolbar' }, this.search), this.list),
      this.pane));
    this.load();
    app.listeners.add((type) => { if (type === 'new-process' || type === 'connected') this.load(); });
  },
  async load() {
    try { this.cmds = await loadCommands(); } catch (e) { clear(this.list, h('div', { class: 'empty', text: errText(e) })); return; }
    this.renderList();
    if (!this.current) clear(this.pane, h('div', { class: 'empty', text: 'Select a command.' }));
  },
  renderList() {
    const q = this.search.value.trim().toLowerCase();
    const groups = new Map();
    for (const c of this.cmds || []) {
      if (q && !c.name.toLowerCase().includes(q)) continue;
      const g = c.name.includes('.') ? c.name.slice(0, c.name.indexOf('.')) : '(app)';
      if (!groups.has(g)) groups.set(g, []);
      groups.get(g).push(c);
    }
    clear(this.list, [...groups.entries()].map(([g, cs]) => [
      h('div', { class: 'group', text: g }),
      cs.map((c) => h('div', { class: 'item' + (this.current === c.name ? ' sel' : ''), title: c.description || c.name, onclick: () => this.select(c) },
        h('span', { text: c.name }),
        c.kind === 'stream' ? h('span', { class: 'badge stream', text: 'stream' }) : null,
        c.readOnly ? null : h('span', { class: 'badge action', text: 'action' }))),
    ]));
  },
  async select(c) {
    this.current = c.name;
    this.renderList();
    if (this.es) { this.es.close(); this.es = null; }
    clear(this.pane, h('div', { class: 'empty', text: 'Loading schema...' }));
    let d;
    try {
      d = describeCache.get(c.name);
      if (!d) {
        d = await api('/api/describe?cmd=' + encodeURIComponent(c.name));
        describeCache.set(c.name, d);
      }
    } catch (e) {
      clear(this.pane, h('pre', { class: 'box error', text: errText(e) }));
      return;
    }
    if (this.current !== c.name) return;
    const blocked = !c.readOnly && !app.allowActions;
    const form = buildForm(d.input || {}, null, false);
    const raw = h('textarea', { rows: 6, hidden: true });
    const out = h('div');
    let rawMode = false;
    const input = () => {
      if (!rawMode) return form.get() || {};
      if (raw.value.trim() === '') return {};
      return JSON.parse(raw.value);
    };
    const toggle = h('button', { text: 'Edit as JSON', onclick: () => {
      rawMode = !rawMode;
      if (rawMode) { try { raw.value = JSON.stringify(form.get() || {}, null, 2); } catch { /* keep */ } }
      form.el.hidden = rawMode; raw.hidden = !rawMode;
      toggle.textContent = rawMode ? 'Use form' : 'Edit as JSON';
    } });
    const run = h('button', { class: 'primary', text: c.kind === 'stream' ? 'Watch' : 'Run', disabled: blocked });
    const stop = h('button', { text: 'Stop', hidden: true, onclick: () => { this.es?.close(); this.es = null; stop.hidden = true; run.disabled = false; } });
    run.addEventListener('click', async () => {
      let value;
      try { value = input(); } catch (e) { clear(out, h('pre', { class: 'box error', text: e.message })); return; }
      if (c.kind === 'stream') return this.watch(c.name, value, out, run, stop);
      run.disabled = true;
      const t0 = performance.now();
      try {
        const r = await call(c.name, value);
        clear(out, h('div', { class: 'result-meta', text: `result in ${Math.round(performance.now() - t0)} ms` }), jsonPre(r));
      } catch (e) {
        clear(out, h('div', { class: 'result-meta', text: 'error' }), h('pre', { class: 'box error', text: errText(e) }));
      } finally { run.disabled = blocked; }
    });
    clear(this.pane,
      h('h2', {}, d.name, h('span', { class: 'badge' + (d.kind === 'stream' ? ' stream' : ''), text: d.kind }),
        c.readOnly ? h('span', { class: 'badge', text: 'read-only' }) : h('span', { class: 'badge action', text: 'action' })),
      d.description ? h('div', { class: 'desc', text: d.description }) : null,
      blocked ? h('div', { class: 'hint', text: 'This command may change app state. Restart with `probe ui --allow-actions` to run it.' }) : null,
      form.el, raw,
      h('div', { class: 'actions' }, run, stop, toggle),
      out,
      h('details', {}, h('summary', { class: 'desc', text: 'Schema' }), jsonPre({ input: d.input, output: d.output })));
  },
  watch(name, input, out, run, stop) {
    const rows = h('tbody');
    let n = 0;
    clear(out, h('div', { class: 'result-meta', text: 'watching...' }), h('div', { class: 'scroll' }, h('table', {}, rows)));
    run.disabled = true; stop.hidden = false;
    const meta = out.firstChild;
    this.es = openStream(name, input, {
      reset: () => { clear(rows); n = 0; },
      record: (v) => {
        n++;
        meta.textContent = `${n} event(s)`;
        rows.prepend(h('tr', {}, h('td', { class: 'time', text: fmtTime(Date.now()) }), h('td', {}, jsonPre(v))));
        while (rows.children.length > 200) rows.lastChild.remove();
      },
      marker: (m) => rows.prepend(markerRow(m, 2)),
      end: () => { meta.textContent = `${n} event(s); stream ended`; stop.hidden = true; run.disabled = false; },
      error: (e) => { clear(out, h('pre', { class: 'box error', text: errText(e) })); stop.hidden = true; run.disabled = false; },
    });
  },
};

// ---------- Mocks ----------

const mocks = {
  init(el) {
    const ro = !app.allowActions;
    this.tbody = h('tbody');
    this.msg = h('span', { class: 'meta' });
    const f = this.f = {
      method: h('select', {}, ['', 'GET', 'POST', 'PUT', 'PATCH', 'DELETE', 'HEAD'].map((m) => h('option', { value: m, text: m || 'any method' }))),
      path: h('input', { type: 'text', placeholder: 'path glob, e.g. /orders/*/confirm  (* one segment, ** any)' }),
      mode: h('select', { onchange: () => this.syncMode() },
        h('option', { value: 'static', text: 'status + body' }), h('option', { value: 'template', text: 'template' }), h('option', { value: 'failure', text: 'failure' })),
      status: h('input', { type: 'number', value: '200', placeholder: 'status' }),
      contentType: h('input', { type: 'text', value: 'application/json', placeholder: 'Content-Type' }),
      body: h('textarea', { rows: 3, placeholder: 'response body' }),
      template: h('select'),
      vars: h('textarea', { rows: 2, placeholder: 'vars JSON, e.g. {"status":403,"code":"OUT_OF_STOCK"}' }),
      failure: h('select', {}, h('option', { value: 'timeout', text: 'timeout' }), h('option', { value: 'disconnect', text: 'disconnect' })),
      delay: h('input', { type: 'number', placeholder: 'delay ms', min: '0' }),
      times: h('input', { type: 'number', placeholder: 'times (blank = unlimited)', min: '1' }),
      persist: h('input', { type: 'checkbox' }),
    };
    this.staticRow = h('div', { class: 'row' }, f.status, f.contentType);
    this.bodyRow = h('div', { class: 'row' }, f.body);
    this.templateRow = h('div', { class: 'row' }, f.template, h('div', { class: 'grow' }, f.vars));
    this.failureRow = h('div', { class: 'row' }, f.failure);
    const add = h('button', { class: 'primary', text: 'Add mock', onclick: () => this.add() });
    const form = h('div', { class: 'mockform' },
      h('div', { class: 'row' }, f.method, f.path, f.mode),
      this.staticRow, this.bodyRow, this.templateRow, this.failureRow,
      h('div', { class: 'row' }, f.delay, f.times, h('label', { class: 'check' }, f.persist, 'persist across restarts'), h('span', { class: 'grow' }), add));
    if (ro) form.querySelectorAll('input,select,textarea,button').forEach((x) => { x.disabled = true; });
    this.clearBtn = h('button', { class: 'danger', text: 'Clear all', disabled: ro, onclick: () => this.clearAll() });
    clear(el,
      toolbar('Mocks', this.msg, h('span', { class: 'spacer' }), h('button', { text: 'Refresh', onclick: () => this.refresh() }), this.clearBtn),
      ro ? h('div', { class: 'hintbar', text: 'Read-only: adding, removing and clearing mocks needs `probe ui --allow-actions`. The rule list is still shown.' }) : null,
      form,
      h('div', { class: 'scroll' }, h('table', {},
        h('thead', {}, h('tr', {}, ['id', 'method', 'path', 'response', 'delay', 'times', 'persist', ''].map((t) => h('th', { text: t })))),
        this.tbody)));
    this.syncMode();
    this.refresh();
    call('network.mock.templates').then((ts) => {
      clear(f.template, (ts || []).map((t) => h('option', { value: t.name, title: t.description || '', text: `${t.name}${t.description ? ' — ' + t.description : ''}` })));
    }).catch(() => clear(f.template, h('option', { value: '', text: '(no templates)' })));
    app.listeners.add((type) => { if (type === 'new-process' || type === 'connected') this.refresh(); });
  },
  syncMode() {
    const m = this.f.mode.value;
    this.staticRow.hidden = m !== 'static';
    this.bodyRow.hidden = m !== 'static';
    this.templateRow.hidden = m !== 'template';
    this.failureRow.hidden = m !== 'failure';
  },
  async refresh() {
    try {
      const rules = await call('network.mock.list');
      this.msg.textContent = `${rules.length} rule(s)`;
      if (!rules.length) return clear(this.tbody, h('tr', {}, h('td', { colspan: 8, class: 'dim', text: 'No mock rules.' })));
      clear(this.tbody, rules.map((r) => h('tr', {},
        h('td', { class: 'mono', text: r.id }),
        h('td', { class: 'mono', text: r.method || 'any' }),
        h('td', { class: 'mono trunc', title: r.path, text: r.path }),
        h('td', { class: 'mono', text: r.failure ? `failure: ${r.failure}` : `${r.status ?? ''}${r.template ? ` via ${r.template}` : ''}` }),
        h('td', { class: 'num', text: r.delayMs || '' }),
        h('td', { class: 'num', text: r.times == null ? '∞' : `${r.remaining ?? '?'} / ${r.times}` }),
        h('td', { text: r.persist ? 'yes' : '' }),
        h('td', {}, h('button', { class: 'danger', text: 'Remove', disabled: !app.allowActions, onclick: () => this.remove(r.id) })))));
    } catch (e) {
      this.msg.textContent = errText(e);
    }
  },
  async add() {
    const f = this.f;
    const rule = { path: f.path.value.trim() };
    if (f.method.value) rule.method = f.method.value;
    try {
      if (f.mode.value === 'static') {
        rule.status = Number(f.status.value || 200);
        if (f.contentType.value.trim()) rule.headers = { 'Content-Type': f.contentType.value.trim() };
        if (f.body.value) rule.body = f.body.value;
      } else if (f.mode.value === 'template') {
        rule.template = f.template.value;
        if (f.vars.value.trim()) rule.vars = JSON.parse(f.vars.value);
      } else {
        rule.failure = f.failure.value;
      }
    } catch (e) { this.msg.textContent = 'vars: invalid JSON'; return; }
    if (f.delay.value) rule.delayMs = Number(f.delay.value);
    if (f.times.value) rule.times = Number(f.times.value);
    if (f.persist.checked) rule.persist = true;
    try {
      const r = await call('network.mock.add', rule);
      this.msg.textContent = `added ${r.id}`;
      this.refresh();
    } catch (e) { this.msg.textContent = errText(e); }
  },
  async remove(id) {
    try { await call('network.mock.remove', { id }); this.refresh(); } catch (e) { this.msg.textContent = errText(e); }
  },
  async clearAll() {
    if (!confirm('Remove every mock rule, including persisted ones?')) return;
    try { const r = await call('network.mock.clear'); this.msg.textContent = `removed ${r.removed}`; this.refresh(); } catch (e) { this.msg.textContent = errText(e); }
  },
};

// ---------- routing ----------

const panels = { overview, network, events, state, commands, mocks };
const started = new Set();

function route() {
  const name = (location.hash || '#overview').slice(1);
  const key = panels[name] ? name : 'overview';
  for (const a of document.querySelectorAll('#nav a')) a.classList.toggle('active', a.dataset.panel === key);
  for (const p of Object.keys(panels)) document.getElementById('panel-' + p).classList.toggle('active', p === key);
  if (!started.has(key)) {
    started.add(key);
    panels[key].init(document.getElementById('panel-' + key));
  }
}

async function start() {
  await openBus();
  window.addEventListener('hashchange', route);
  route();
  // Network and events keep recording while other panels are open.
  for (const k of ['network', 'events']) {
    if (!started.has(k)) { started.add(k); panels[k].init(document.getElementById('panel-' + k)); }
  }
}

start();
