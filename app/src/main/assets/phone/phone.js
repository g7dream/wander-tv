/* Wander TV phone controller.
 *
 * One dependency-free script. The TV serves this page over plain HTTP and talks to it over a WebSocket at
 * ws://<tv>/ws using the JSON protocol in SPEC.md section 5. Nothing here talks to any other host.
 *
 * Layout of this file:
 *   1. constants and small helpers      6. controller rendering (top bar, quest, field, picker, cards, rescue, dock)
 *   2. Lumo the moth (inline SVG)       7. touchpad (pointer events, rAF batching, inertia, scroll wheel)
 *   3. app state                        8. toasts, celebration confetti, reconnect overlay, seat sheet
 *   4. connection                       9. viewport and keyboard handling
 *   5. join screen                     10. boot
 */
(function () {
  'use strict';

  /* ============================================================ 1. constants and helpers */

  var COLORS = [
    { name: 'Sun', hex: '#FFB547', rgb: '255, 181, 71' },
    { name: 'Berry', hex: '#FF5D8F', rgb: '255, 93, 143' },
    { name: 'Mint', hex: '#3DDC97', rgb: '61, 220, 151' },
    { name: 'Sky', hex: '#5AB4FF', rgb: '90, 180, 255' },
  ];
  var MODES = ['home', 'browse', 'cards', 'quest_picker', 'quest_intro', 'rescue', 'companion'];
  var STORE_KEY = 'wander.phone.v1';

  var TAP_MS = 250;            // a tap is shorter than this...
  var TAP_SLOP = 10;           // ...and moves less than this many CSS px
  var STEP_PX = 54;            // finger travel per arrow-key step when the TV is in a focus-based screen
  var RAIL_GAIN = 2.4;         // page px per finger px on the scroll wheel
  var RAIL_PAGE = 420;         // tapping an end of the wheel jumps about one screen
  var WHEEL_DETENT = 34;       // a haptic tick every this many px of wheel travel (Android)
  var MAX_BUFFERED = 16 * 1024; // above this, coalesce touchpad deltas instead of queueing more frames

  var PING_MS = 20000;
  var SILENCE_MS = 47000;      // nothing heard for this long (two missed pongs): the socket is dead
  var CONNECT_TIMEOUT_MS = 6000;
  var WELCOME_TIMEOUT_MS = 6000;
  var BACKOFF_MS = [250, 700, 1500, 3000, 5000, 8000];

  var STARTERS = ['fastest animals on Earth', 'how many moons does Saturn have', 'deepest ocean creatures'];

  function $(id) { return document.getElementById(id); }

  function el(tag, cls, text) {
    var n = document.createElement(tag);
    if (cls) n.className = cls;
    if (text != null) n.textContent = text;
    return n;
  }

  function str(v, max) {
    if (typeof v !== 'string') return '';
    return max && v.length > max ? v.slice(0, max) : v;
  }

  function int(v, fallback) {
    var n = Math.floor(Number(v));
    return isFinite(n) ? n : fallback;
  }

  function clamp(v, lo, hi) { return v < lo ? lo : v > hi ? hi : v; }

  function colorIndex(v) { var n = int(v, 0); return ((n % 4) + 4) % 4; }

  function isObj(v) { return !!v && typeof v === 'object' && !Array.isArray(v); }

  function now() { return window.performance && performance.now ? performance.now() : Date.now(); }

  function r1(v) { var n = Math.round(v * 10) / 10; return n === 0 ? 0 : n; }

  function setText(node, text) { if (node.textContent !== text) node.textContent = text; }

  /** First user-perceived character, so emoji names get a proper initial. */
  function initial(name) { var chars = Array.from(String(name || '?').trim()); return (chars[0] || '?').toUpperCase(); }

  function cleanName(s) { return Array.from(String(s || '').replace(/\s+/g, ' ').trim()).slice(0, 16).join(''); }

  function hostOf(url) {
    try { return new URL(url).hostname.replace(/^www\./, ''); } catch (e) { return ''; }
  }

  function lighten(hex, amount) {
    var n = parseInt(hex.slice(1), 16);
    var r = (n >> 16) & 255, g = (n >> 8) & 255, b = n & 255;
    var k = amount == null ? 0.4 : amount;
    return 'rgb(' + Math.round(r + (255 - r) * k) + ',' + Math.round(g + (255 - g) * k) + ',' + Math.round(b + (255 - b) * k) + ')';
  }

  function vibrate(pattern) {
    try {
      if (typeof navigator.vibrate !== 'function') return; // iOS has no vibration API
      // Chrome refuses (and logs an error) before the first tap on the page, e.g. right after an auto-rejoin.
      if (navigator.userActivation && !navigator.userActivation.hasBeenActive) return;
      navigator.vibrate(pattern);
    } catch (e) { /* not supported */ }
  }

  var reduceMotion = window.matchMedia ? window.matchMedia('(prefers-reduced-motion: reduce)') : { matches: false };

  /** localStorage can throw (private mode, blocked storage): every access is guarded. */
  var store = {
    read: function () {
      try {
        var v = JSON.parse(localStorage.getItem(STORE_KEY) || 'null');
        return isObj(v) ? v : {};
      } catch (e) { return {}; }
    },
    write: function (patch) {
      try { localStorage.setItem(STORE_KEY, JSON.stringify(Object.assign(store.read(), patch))); } catch (e) { /* ignore */ }
    },
  };

  /* ============================================================ 2. Lumo the moth */

  // A fuzzy cream moth with big glossy eyes, feathery antennae and amber-to-berry wings with mint and sky
  // eye-spots. Each copy gets its own gradient ids: a gradient defined inside a hidden SVG stops painting.
  var lumoSeq = 0;

  function lumoMarkup(mood, simple) {
    var id = 'lumo' + (++lumoSeq);
    var edge = ' stroke="#120E1C" stroke-opacity=".3" stroke-width="1.4" stroke-linejoin="round"';
    var wing =
      '<path d="M73 60C62 44 40 26 20 24C8 23 4 34 9 46C15 60 36 70 72 70Z" fill="url(#' + id + 'f)"' + edge + '/>' +
      '<path d="M73 70C56 72 34 80 30 96C27 108 40 116 52 110C63 104 70 90 75 78Z" fill="url(#' + id + 'h)"' + edge + '/>' +
      (simple ? '' : '<path d="M70 63C55 52 38 40 18 32M70 67C52 62 32 56 12 47M72 76C60 84 48 94 40 106" fill="none" stroke="#FFF3DC" stroke-opacity=".3" stroke-width="1.2" stroke-linecap="round"/>') +
      '<circle cx="32" cy="43" r="8.5" fill="#3DDC97"/><circle cx="32" cy="43" r="4" fill="#120E1C"/>' +
      (simple ? '' : '<circle cx="30.6" cy="41.6" r="1.4" fill="#F7EFE2"/>') +
      '<circle cx="46" cy="100" r="5.5" fill="#5AB4FF"/><circle cx="46" cy="100" r="2.3" fill="#120E1C"/>';

    var fuzz = function (shape) {
      return simple ? '' : shape.replace('/>', ' fill="none" stroke="#F7EFE2" stroke-width="4" stroke-dasharray=".6 2.7" stroke-linecap="round"/>');
    };

    // Moods match the TV companion: curious (default), happy (^ ^), sleepy (dozing, eyes shut), concerned (worried brows).
    var eyes;
    if (mood === 'happy') {
      eyes = '<path d="M67.5 52.5Q73 44.5 78.5 52.5M81.5 52.5Q87 44.5 92.5 52.5" fill="none" stroke="#1B1529" stroke-width="3.2" stroke-linecap="round"/>';
    } else if (mood === 'sleepy') {
      eyes = '<path d="M67 50Q73 55.5 79 50M81 50Q87 55.5 93 50" fill="none" stroke="#1B1529" stroke-width="2.8" stroke-linecap="round"/>' +
        '<path d="M66.4 49.2l-1.8-1.6M93.6 49.2l1.8-1.6" stroke="#1B1529" stroke-width="1.6" stroke-linecap="round"/>';
    } else {
      eyes =
        '<ellipse cx="73" cy="50" rx="6" ry="7" fill="url(#' + id + 'e)"/><ellipse cx="87" cy="50" rx="6" ry="7" fill="url(#' + id + 'e)"/>' +
        '<circle cx="71" cy="47" r="2.3" fill="#fff"/><circle cx="85" cy="47" r="2.3" fill="#fff"/>' +
        '<circle cx="75.2" cy="53.2" r="1" fill="#fff" opacity=".75"/><circle cx="89.2" cy="53.2" r="1" fill="#fff" opacity=".75"/>';
      if (mood === 'concerned') {
        eyes += '<path d="M66.5 41.5L76.5 38.6M83.5 38.6L93.5 41.5" fill="none" stroke="#2A2140" stroke-width="2.2" stroke-linecap="round"/>';
      }
    }
    var mouth = mood === 'happy'
      ? '<path d="M75.5 58.5Q80 64.5 84.5 58.5" fill="#2A2140" stroke="#2A2140" stroke-width="1.2" stroke-linejoin="round"/>'
      : mood === 'sleepy'
        ? '<ellipse cx="80" cy="60.4" rx="1.8" ry="1.4" fill="#2A2140"/>'
        : mood === 'concerned'
          ? '<path d="M76.8 61Q78.4 59.4 80 61Q81.6 62.6 83.2 61" fill="none" stroke="#2A2140" stroke-width="1.6" stroke-linecap="round"/>'
          : '<path d="M77 59.5Q80 62.2 83 59.5" fill="none" stroke="#2A2140" stroke-width="1.6" stroke-linecap="round"/>';

    return '<svg class="lumo lumo-' + mood + (simple ? ' lumo-simple' : '') + '" viewBox="0 0 160 128" aria-hidden="true" focusable="false">' +
      '<defs>' +
      '<linearGradient id="' + id + 'f" x1="0" y1="0" x2="1" y2="1"><stop offset="0" stop-color="#FFB547"/><stop offset=".55" stop-color="#FF8A6B"/><stop offset="1" stop-color="#FF5D8F"/></linearGradient>' +
      '<linearGradient id="' + id + 'h" x1="0" y1="0" x2=".6" y2="1"><stop offset="0" stop-color="#FF5D8F"/><stop offset="1" stop-color="#FF8F5C"/></linearGradient>' +
      '<radialGradient id="' + id + 'b" cx=".42" cy=".34" r=".78"><stop offset="0" stop-color="#FFFCF5"/><stop offset="1" stop-color="#E8D9C2"/></radialGradient>' +
      '<radialGradient id="' + id + 'e" cx=".35" cy=".3" r=".8"><stop offset="0" stop-color="#4A3D6E"/><stop offset=".6" stop-color="#1B1529"/><stop offset="1" stop-color="#0B0812"/></radialGradient>' +
      '</defs>' +
      '<g class="lumo-wings">' + wing + '<g transform="translate(160 0) scale(-1 1)">' + wing + '</g></g>' +
      '<g class="lumo-antennae" fill="none" stroke-linecap="round">' +
      (simple ? '' : '<path d="M74 33C70 24 64 16 54 11M86 33C90 24 96 16 106 11" stroke="#F7EFE2" stroke-opacity=".55" stroke-width="7" stroke-dasharray="1.1 2.3"/>') +
      '<path d="M75 37C71 26 64 16 52 10M85 37C89 26 96 16 108 10" stroke="#F7EFE2" stroke-width="' + (simple ? 3 : 2) + '"/>' +
      '<circle cx="52" cy="10" r="2.6" fill="#F7EFE2" stroke="none"/><circle cx="108" cy="10" r="2.6" fill="#F7EFE2" stroke="none"/>' +
      '</g>' +
      '<g class="lumo-body">' +
      '<ellipse cx="80" cy="82" rx="12.5" ry="24" fill="url(#' + id + 'b)"/>' + fuzz('<ellipse cx="80" cy="82" rx="12.8" ry="24.3"/>') +
      (simple ? '' : '<path d="M70.5 84Q80 89 89.5 84M71.5 93Q80 97.5 88.5 93" fill="none" stroke="#D6C3A6" stroke-width="1.5" stroke-linecap="round"/>') +
      '<ellipse cx="80" cy="65" rx="15" ry="7.5" fill="#FBF5EA"/>' + fuzz('<ellipse cx="80" cy="65" rx="15.3" ry="7.8"/>') +
      '<circle cx="80" cy="50" r="16" fill="url(#' + id + 'b)"/>' + fuzz('<circle cx="80" cy="50" r="16.4"/>') +
      eyes +
      '<circle cx="68.4" cy="59" r="2.8" fill="#FF5D8F" opacity=".45"/><circle cx="91.6" cy="59" r="2.8" fill="#FF5D8F" opacity=".45"/>' +
      mouth +
      '</g></svg>';
  }

  function mountLumo(node) {
    var size = int(node.getAttribute('data-lumo-size'), 0);
    if (size > 0) node.style.width = size + 'px';
    node.innerHTML = lumoMarkup(node.getAttribute('data-lumo') || 'curious', node.getAttribute('data-lumo-simple') === '1');
  }

  /* ============================================================ 3. app state */

  function emptyTv() {
    return {
      mode: 'home', title: '', url: '', checking: false, ai: true,
      field: { focused: false, label: '' }, cards: null, quest: null, rescue: null, quests: [],
    };
  }

  var app = {
    screen: 'join',
    me: { pid: null, name: '', color: 0 },
    pickColor: 0,
    joined: false,        // we want a seat: keep (re)connecting until we get one or the user leaves
    welcomed: false,      // the current socket has been welcomed
    everWelcomed: false,  // this page load has had a seat at least once
    rejected: false,
    greeting: 'join',     // what to say when the next welcome lands: join | rejoin | reconnect
    replaced: false,      // our seat moved to another tab or window of this phone: wait for a tap, don't fight it
    players: [],
    known: Object.create(null), // pid -> { name, color } for everyone seen tonight (never pruned)
    tv: emptyTv(),
    typeOverride: false,  // user chose "Search instead" while a page field is focused
    lastFieldKey: '',
    questKey: '',
    questOpen: null,      // null = automatic, else the user's choice for the current quest
    submitLockUntil: 0,
    lastToast: { text: '', at: 0 },
  };

  /** Parses a TV state message defensively: every field may be missing or malformed. */
  function normState(m) {
    var f = isObj(m.field) ? m.field : {};
    var c = isObj(m.cards) ? m.cards : null;
    var q = isObj(m.quest) && (str(m.quest.id) || str(m.quest.title)) ? m.quest : null;
    var r = isObj(m.rescue) ? m.rescue : null;
    return {
      mode: MODES.indexOf(m.mode) >= 0 ? m.mode : 'home',
      title: str(m.title, 200).trim(),
      url: str(m.url, 2048),
      checking: m.checking === true,
      ai: m.ai !== false,
      field: { focused: f.focused === true, label: str(f.label, 60).trim() },
      cards: c ? { index: Math.max(0, int(c.index, 0)), count: Math.max(0, int(c.count, 0)), title: str(c.title, 120).trim() } : null,
      quest: q ? {
        id: str(q.id, 80),
        title: str(q.title, 80).trim(),
        prompt: str(q.prompt, 400).trim(),
        emoji: str(q.emoji, 16),
        goal: clamp(int(q.goal, 1), 1, 12),
        complete: q.complete === true,
        found: (Array.isArray(q.found) ? q.found : []).filter(isObj).slice(0, 24).map(function (x) {
          return { item: str(x.item, 60).trim() || 'Something', pid: str(x.pid, 64), evidence: str(x.evidence, 200).trim() };
        }),
        // The TV's own scoreboard (it includes the remote, "The couch"). Null from a TV that doesn't send it.
        players: Array.isArray(q.players) ? q.players.filter(isObj).slice(0, 8).map(normPlayer).filter(function (p) { return !!p.pid; }) : null,
      } : null,
      rescue: r ? {
        headline: str(r.headline, 220).trim(),
        detail: str(r.detail, 200).trim(),
        options: (Array.isArray(r.options) ? r.options : []).filter(isObj).slice(0, 2).map(function (o) {
          return { label: str(o.label, 80).trim() || 'Try this', detail: str(o.detail, 160).trim(), kind: str(o.kind, 16) };
        }),
      } : null,
      quests: (Array.isArray(m.quests) ? m.quests : []).filter(isObj).slice(0, 12).map(function (x) {
        return { id: str(x.id, 80), title: str(x.title, 60).trim() || 'Quest', emoji: str(x.emoji, 16) || '\ud83e\udded', prompt: str(x.prompt, 300).trim() };
      }).filter(function (x) { return !!x.id; }),
    };
  }

  function normPlayer(p) {
    return { pid: str(p.pid, 64), name: str(p.name, 32).trim() || 'Player', color: colorIndex(p.color), score: Math.max(0, int(p.score, 0)) };
  }

  function playerBy(pid) {
    for (var i = 0; i < app.players.length; i++) if (app.players[i].pid === pid) return app.players[i];
    return null;
  }

  var COUCH_PID = 'couch';      // the TV remote plays quests as this player
  var NEUTRAL_HEX = '#B9AECC';  // for a finder nobody can name any more

  /** Everyone this page has seen tonight, so finds by a phone that has since left keep their name and color. */
  function remember(list) {
    (list || []).forEach(function (p) { if (p.pid) app.known[p.pid] = { name: p.name, color: p.color }; });
  }

  /**
   * Name and color for whoever found something: the TV's scoreboard first (it includes the remote), then
   * the phones on the couch now, then anyone seen earlier. Returns { name, hex, me }.
   */
  function finderOf(pid) {
    if (pid && pid === app.me.pid) return { name: 'You', hex: COLORS[app.me.color].hex, me: true };
    var q = app.tv.quest;
    var p = null;
    if (q && q.players) for (var i = 0; i < q.players.length && !p; i++) if (q.players[i].pid === pid) p = q.players[i];
    p = p || playerBy(pid) || app.known[pid] || null;
    if (p) return { name: p.name, hex: COLORS[colorIndex(p.color)].hex, me: false };
    if (pid === COUCH_PID) return { name: 'The couch', hex: NEUTRAL_HEX, me: false };
    return { name: 'Someone', hex: NEUTRAL_HEX, me: false };
  }

  /* ============================================================ 4. connection */

  var net = { ws: null, attempt: 0, retryTimer: 0, connectTimer: 0, welcomeTimer: 0, tickTimer: 0, lastRx: 0, lastPing: 0, pongSeen: false };

  function wsUrl() { return (location.protocol === 'https:' ? 'wss://' : 'ws://') + location.host + '/ws'; }

  function rawSend(ws, obj) {
    try {
      if (ws && ws.readyState === 1) { ws.send(JSON.stringify(obj)); return true; }
    } catch (e) { /* socket is going away; onclose will follow */ }
    return false;
  }

  /** Sends a command once we have a seat. Returns false while disconnected. */
  function send(obj) { return app.welcomed ? rawSend(net.ws, obj) : false; }

  /** Like send, but tells the person when it could not go out (for deliberate button presses). */
  function sendOrNudge(obj) {
    if (send(obj)) return true;
    toast('Not connected to the TV yet. Hang on a second...', 'bad');
    return false;
  }

  function helloMessage() {
    var saved = store.read();
    // Only claim the remembered seat when it is the same person: same name and color as last time.
    var pid = saved.pid && saved.name === app.me.name && saved.color === app.me.color ? String(saved.pid) : null;
    return { t: 'hello', name: app.me.name, color: app.me.color, pid: pid };
  }

  function connect() {
    clearTimeout(net.retryTimer);
    net.retryTimer = 0;
    if (!app.joined || app.replaced || net.ws) return;
    var ws;
    try { ws = new WebSocket(wsUrl()); } catch (e) { scheduleRetry(); return; }
    net.ws = ws;
    net.pongSeen = false;
    app.welcomed = false;
    setConn('wait');
    net.connectTimer = setTimeout(function () { if (net.ws === ws && ws.readyState !== 1) drop(ws); }, CONNECT_TIMEOUT_MS);
    ws.onopen = function () {
      if (net.ws !== ws) return;
      clearTimeout(net.connectTimer);
      net.lastRx = Date.now();
      rawSend(ws, helloMessage());
      net.welcomeTimer = setTimeout(function () { if (net.ws === ws && !app.welcomed) drop(ws); }, WELCOME_TIMEOUT_MS);
    };
    ws.onmessage = function (ev) {
      if (net.ws !== ws) return;
      net.lastRx = Date.now();
      if (typeof ev.data !== 'string') return;
      var msg;
      try { msg = JSON.parse(ev.data); } catch (e) { return; }
      if (!isObj(msg) || typeof msg.t !== 'string') return;
      try { onMessage(msg); } catch (err) { if (window.console) console.error('wander: could not handle', msg.t, err); }
    };
    ws.onclose = function (ev) {
      if (net.ws !== ws) return;
      // 4000: the TV gave our seat to a newer connection with the same pid and name, i.e. this phone in
      // another tab (a re-scanned QR code opens one). Reconnecting would take it back and the two tabs
      // would kick each other forever, so wait for the person to choose this tab instead.
      if (ev && ev.code === 4000 && app.welcomed) replaced();
      else lost();
    };
    ws.onerror = function () { /* onclose always follows */ };
  }

  /** Our seat moved to another tab or window of this phone. Park here until asked to take it back. */
  function replaced() {
    clearTimeout(net.connectTimer);
    clearTimeout(net.welcomeTimer);
    clearTimeout(net.retryTimer);
    net.retryTimer = 0;
    stopTicking();
    var ws = net.ws;
    net.ws = null;
    if (ws) detach(ws);
    app.welcomed = false;
    app.replaced = true;
    app.greeting = 'reconnect';
    pad.cancel();
    setConn('wait');
    if (app.screen === 'ctrl') reconnectUi.show(0);
  }

  /** "Use this phone here": take the seat back from the other tab (which then parks itself). */
  function reclaim() {
    if (!app.replaced) return;
    app.replaced = false;
    net.attempt = 0;
    reconnectUi.update();
    retryNow();
  }

  function detach(ws) {
    ws.onopen = ws.onmessage = ws.onclose = ws.onerror = null;
    try { ws.close(); } catch (e) { /* already closed */ }
  }

  function drop(ws) {
    if (net.ws === ws) lost();
    else detach(ws);
  }

  /** The socket is gone (closed, errored, timed out or silent). Decide what the person sees and retry. */
  function lost() {
    clearTimeout(net.connectTimer);
    clearTimeout(net.welcomeTimer);
    stopTicking();
    var ws = net.ws;
    net.ws = null;
    if (ws) detach(ws);
    var hadSeat = app.welcomed;
    app.welcomed = false;
    pad.cancel();
    setConn('wait');
    if (!app.joined || app.rejected) return;
    if (hadSeat) app.greeting = 'reconnect';
    scheduleRetry();
    if (app.screen === 'ctrl') reconnectUi.show(hadSeat ? 900 : 0);
    else joinUi.searching();
  }

  function scheduleRetry() {
    if (net.retryTimer || !app.joined || app.replaced) return;
    var delay = BACKOFF_MS[Math.min(net.attempt, BACKOFF_MS.length - 1)] + Math.random() * 300;
    net.attempt++;
    net.retryTimer = setTimeout(function () { net.retryTimer = 0; connect(); }, delay);
    reconnectUi.update();
  }

  function retryNow() {
    if (!app.joined || app.rejected || app.replaced) return;
    clearTimeout(net.retryTimer);
    net.retryTimer = 0;
    if (!net.ws) connect();
  }

  /** Leaves on purpose: no reconnecting. */
  function disconnect() {
    app.joined = false;
    app.replaced = false;
    clearTimeout(net.retryTimer);
    clearTimeout(net.connectTimer);
    clearTimeout(net.welcomeTimer);
    net.retryTimer = 0;
    stopTicking();
    var ws = net.ws;
    net.ws = null;
    if (ws) detach(ws);
    app.welcomed = false;
    pad.cancel();
  }

  /** Keepalive: ping every 20 s, and treat a long silence as a dead socket (phones sleep, Wi-Fi roams). */
  function startTicking() {
    stopTicking();
    net.lastPing = Date.now();
    net.tickTimer = setInterval(function () {
      var ws = net.ws;
      if (!ws || !app.welcomed) return;
      var t = Date.now();
      // Only a TV that has answered a ping before can be judged by its silence.
      if (net.pongSeen && t - net.lastRx > SILENCE_MS) { drop(ws); return; }
      if (t - net.lastPing >= PING_MS) { net.lastPing = t; rawSend(ws, { t: 'ping' }); }
    }, 5000);
  }

  function stopTicking() { clearInterval(net.tickTimer); net.tickTimer = 0; }

  /** Called when the phone wakes up or the tab comes back: sockets often die silently while asleep. */
  function wake() {
    // A tab whose seat moved to another tab stays parked until tapped, or the two would take turns.
    if (!app.joined || app.rejected || app.replaced) return;
    var ws = net.ws;
    if (!ws) { net.attempt = Math.min(net.attempt, 1); retryNow(); return; }
    if (app.welcomed && net.pongSeen && Date.now() - net.lastRx > 15000) {
      rawSend(ws, { t: 'ping' });
      setTimeout(function () { if (net.ws === ws && Date.now() - net.lastRx > 15000) drop(ws); }, 2500);
    }
  }

  var connDot = $('conn');
  function setConn(state) { if (connDot.getAttribute('data-state') !== state) connDot.setAttribute('data-state', state); }

  function onMessage(m) {
    switch (m.t) {
      case 'welcome': onWelcome(m); break;
      case 'reject': onReject(m); break;
      case 'players': onPlayers(m.players); break;
      case 'state': onState(m); break;
      case 'toast': toast(str(m.text, 200), m.kind); break;
      case 'celebrate': celebrateUi.show(str(m.text, 80).trim(), m.color); break;
      case 'pong': net.pongSeen = true; break;
      default: break;
    }
  }

  function onWelcome(m) {
    clearTimeout(net.welcomeTimer);
    var asked = app.me.color;
    var color = colorIndex(m.color);
    var name = str(m.name, 32).trim() || app.me.name || COLORS[color].name;
    var pid = str(m.pid, 64) || null;
    var greeting = app.greeting;
    app.me = { pid: pid, name: name, color: color };
    app.welcomed = true;
    app.everWelcomed = true;
    app.rejected = false;
    app.replaced = false;
    app.greeting = 'reconnect';
    net.attempt = 0;
    store.write({ pid: pid, name: name, color: color, draft: null });
    applyMe();
    setConn('live');
    var overlayWasUp = !rcEl.hidden;
    reconnectUi.hide();
    joinUi.busy(false);
    startTicking();
    showScreen('ctrl');
    if (greeting === 'join') {
      if (color !== asked) toast(COLORS[asked].name + ' was taken, so you\u2019re ' + COLORS[color].name + ' tonight.', 'info');
      else toast('You\u2019re on the couch, ' + name + '!', 'good');
      vibrate(18);
    } else if (greeting === 'rejoin') {
      toast('Welcome back, ' + name + '.', 'good');
    } else if (overlayWasUp) {
      toast('Back on the couch.', 'good'); // a blip too short to notice stays silent
    }
  }

  function onReject(m) {
    var full = m.reason === 'full';
    app.rejected = true;
    disconnect();
    reconnectUi.hide();
    sheetUi.hide();
    showScreen('join');
    joinUi.busy(false);
    if (full) {
      joinUi.message('The couch is full tonight.', 'Four phones are already playing. Hand one over, or try again when a seat frees up.', 'bad');
    } else {
      joinUi.message('The TV didn\u2019t catch that.', 'Give it another go in a moment.', 'bad');
    }
    joinUi.label('Try again');
    vibrate([15, 60, 15]);
  }

  function onPlayers(list) {
    if (!Array.isArray(list)) return;
    app.players = list.filter(isObj).slice(0, 8).map(normPlayer);
    remember(app.players);
    var me = playerBy(app.me.pid);
    if (me && (me.name !== app.me.name || me.color !== app.me.color)) {
      app.me.name = me.name;
      app.me.color = me.color;
      store.write({ name: me.name, color: me.color });
      applyMe();
    }
    renderPlayers();
    if (app.screen === 'ctrl') renderQuest();
  }

  function onState(m) {
    var prev = app.tv;
    var tv = normState(m);
    app.tv = tv;
    if (tv.quest) remember(tv.quest.players);
    if (tv.checking) app.submitLockUntil = 0;
    // Gentle haptics for things that happen on the TV and need this person's attention.
    if (tv.mode === 'rescue' && prev.mode !== 'rescue') vibrate([12, 50, 12]);
    render();
  }

  /* ============================================================ 5. join screen */

  var joinEl = $('join');
  var ctrlEl = $('ctrl');
  var appEl = $('app');
  var joinForm = $('join-form');
  var nameInput = $('join-name');
  var joinBtn = $('join-btn');
  var joinBtnText = $('join-btn-text');
  var joinMsg = $('join-msg');
  var swatchEls = Array.prototype.slice.call(document.querySelectorAll('.swatch'));

  var joinUi = {
    customLabel: '',
    init: function () {
      var saved = store.read();
      var draft = isObj(saved.draft) ? saved.draft : null;
      var name = draft ? str(draft.name, 32) : str(saved.name, 32);
      var color = draft ? draft.color : saved.color;
      nameInput.value = cleanName(name);
      // A random first color, so four new phones do not all start on Sun.
      joinUi.select(typeof color === 'number' ? colorIndex(color) : Math.floor(Math.random() * 4), true);
      joinUi.refresh();
    },
    select: function (i, quiet) {
      app.pickColor = i;
      swatchEls.forEach(function (s) {
        var on = int(s.getAttribute('data-color'), -1) === i;
        s.setAttribute('aria-checked', on ? 'true' : 'false');
        s.tabIndex = on ? 0 : -1;
      });
      if (app.screen === 'join') setMeVars(i);
      if (!quiet) vibrate(8);
      joinUi.refresh();
    },
    refresh: function () {
      var busy = joinBtn.getAttribute('data-busy') === '1';
      var name = cleanName(nameInput.value) || COLORS[app.pickColor].name;
      setText(joinBtnText, busy ? 'Finding your seat...' : joinUi.customLabel || 'Join as ' + name);
    },
    label: function (text) { joinUi.customLabel = text; joinUi.refresh(); },
    busy: function (on) {
      joinBtn.setAttribute('data-busy', on ? '1' : '0');
      joinBtn.setAttribute('aria-busy', on ? 'true' : 'false');
      if (on) joinUi.customLabel = '';
      joinUi.refresh();
    },
    message: function (title, text, kind) {
      var titleEl = $('join-msg-title');
      var isNew = joinMsg.hidden || titleEl.textContent !== title;
      setText(titleEl, title);
      setText($('join-msg-text'), text);
      joinMsg.setAttribute('data-kind', kind || 'info');
      joinMsg.hidden = false;
      if (!isNew) return;
      // On short phones the message sits below the fold: bring it and the button into view (once).
      setTimeout(function () {
        try { joinBtn.scrollIntoView({ block: 'end', behavior: reduceMotion.matches ? 'auto' : 'smooth' }); } catch (e) { joinBtn.scrollIntoView(false); }
      }, 60);
    },
    clear: function () { joinMsg.hidden = true; },
    searching: function () {
      if (net.attempt >= 3) {
        joinUi.message('Still looking for the TV...', 'Make sure this phone is on the same Wi-Fi as the TV, and that Wander is open on it.', 'info');
      }
    },
  };

  swatchEls.forEach(function (s) {
    s.addEventListener('click', function () { joinUi.select(int(s.getAttribute('data-color'), 0)); });
    s.addEventListener('keydown', function (e) {
      var d = e.key === 'ArrowRight' || e.key === 'ArrowDown' ? 1 : e.key === 'ArrowLeft' || e.key === 'ArrowUp' ? -1 : 0;
      if (!d) return;
      e.preventDefault();
      var next = (app.pickColor + d + 4) % 4;
      joinUi.select(next);
      swatchEls[next].focus();
    });
  });

  nameInput.addEventListener('input', function () {
    if (joinUi.customLabel) joinUi.customLabel = '';
    joinUi.refresh();
  });

  joinForm.addEventListener('submit', function (e) {
    e.preventDefault();
    var color = app.pickColor;
    var name = cleanName(nameInput.value) || COLORS[color].name;
    if (joinBtn.getAttribute('data-busy') === '1') {
      // Still looking for the TV: try again now, with whatever name and color are picked at this moment.
      if (!app.welcomed) { app.me = { pid: null, name: name, color: color }; net.attempt = 0; }
      nameInput.blur();
      retryNow();
      return;
    }
    app.me = { pid: null, name: name, color: color };
    app.joined = true;
    app.rejected = false;
    app.replaced = false;
    app.greeting = 'join';
    net.attempt = 0;
    store.write({ draft: { name: name, color: color } });
    joinUi.clear();
    joinUi.busy(true);
    nameInput.blur();
    connect();
  });

  /* ============================================================ 6. controller rendering */

  var nowLabel = $('now-label');
  var nowTitle = $('now-title');
  var couchEl = $('couch');
  var meBtn = $('me-btn');
  var meName = $('me-name');

  var questEl = $('quest');
  var questHead = $('quest-head');
  var questEmoji = $('quest-emoji');
  var questKicker = $('quest-kicker');
  var questTitle = $('quest-title');
  var questPips = $('quest-pips');
  var questPrompt = $('quest-prompt');
  var questFound = $('quest-found');
  var questBoard = $('quest-board');

  var omni = $('omni');
  var omniForm = $('omni-form');
  var omniInput = $('omni-input');
  var omniSend = $('omni-send');
  var omniTag = $('omni-tag');
  var omniLabel = $('omni-label');
  var omniExit = $('omni-exit');
  var omniFill = $('omni-fill');
  var omniBack = $('omni-back');
  var suggestEl = $('suggest');

  var pickerEl = $('picker');
  var pickerTitle = $('picker-title');
  var pickerList = $('picker-list');

  var padZone = $('pad-zone');
  var cardsEl = $('cards');
  var rescueEl = $('rescue');
  var submitBtn = $('quest-submit');
  var submitText = $('qs-text');
  var dockCards = $('dock-cards');

  function showScreen(s) {
    if (app.screen === s) return;
    app.screen = s;
    appEl.setAttribute('data-screen', s);
    joinEl.hidden = s !== 'join';
    ctrlEl.hidden = s !== 'ctrl';
    if (s === 'join') { setMeVars(app.pickColor); joinUi.refresh(); }
    if (s === 'ctrl') { applyMe(); render(); }
  }

  function setMeVars(color) {
    var c = COLORS[colorIndex(color)];
    appEl.style.setProperty('--me', c.hex);
    appEl.style.setProperty('--me-rgb', c.rgb);
  }

  function applyMe() {
    setMeVars(app.me.color);
    setText(meName, app.me.name || 'You');
    meBtn.setAttribute('aria-label', 'You are ' + (app.me.name || 'on the couch') + '. Open your seat');
    sheetUi.render();
  }

  function render() {
    if (app.screen !== 'ctrl') return;
    appEl.setAttribute('data-mode', app.tv.mode);
    renderTop();
    renderPlayers();
    renderQuest();
    renderOmni();
    renderPicker();
    renderMain();
    renderSubmit();
    renderDock();
  }

  function renderTop() {
    var tv = app.tv;
    var label = 'On the TV';
    var title = 'Home screen';
    switch (tv.mode) {
      case 'browse': label = 'Browsing'; title = tv.title || hostOf(tv.url) || 'A web page'; break;
      case 'cards': label = 'Cards'; title = tv.title || 'Field guide cards'; break;
      case 'quest_picker': label = 'Quests'; title = 'Choosing a family quest'; break;
      case 'quest_intro':
        // The TV shows its quest-complete finale in this mode too, flagged by quest.complete.
        label = tv.quest && tv.quest.complete ? 'Quest complete' : 'New quest';
        title = tv.quest && tv.quest.title ? tv.quest.title : 'Get ready';
        break;
      case 'rescue': label = 'Lumo'; title = 'Has an idea for you'; break;
      case 'companion': label = 'Lumo'; title = 'Lumo\u2019s menu is open'; break;
      default: break;
    }
    setText(nowLabel, label);
    setText(nowTitle, title);
  }

  var playersSig = '';
  function renderPlayers() {
    var others = app.players.filter(function (p) { return p.pid !== app.me.pid; }).slice(0, 3);
    var sig = JSON.stringify(others.map(function (p) { return [p.name, p.color]; }));
    if (sig !== playersSig) {
      playersSig = sig;
      couchEl.textContent = '';
      others.forEach(function (p) {
        var face = el('span', 'couch-face', initial(p.name));
        face.style.setProperty('--c', COLORS[p.color].hex);
        face.title = p.name;
        couchEl.appendChild(face);
      });
      couchEl.setAttribute('aria-label', others.length ? 'Also on the couch: ' + others.map(function (p) { return p.name; }).join(', ') : 'Nobody else yet');
    }
    sheetUi.render();
  }

  var questSig = '';
  function renderQuest() {
    var tv = app.tv;
    var q = tv.quest;
    var show = !!q && tv.mode !== 'rescue' && tv.mode !== 'quest_picker';
    questEl.hidden = !show;
    if (!show) return;

    var key = q.id + '|' + q.title;
    if (key !== app.questKey) { app.questKey = key; app.questOpen = null; }
    // Open by default where there is room; folded while browsing so the touchpad stays big.
    var auto = tv.mode !== 'browse' && tv.mode !== 'cards';
    var open = app.questOpen == null ? auto : app.questOpen;
    questEl.setAttribute('data-open', open ? 'true' : 'false');
    questHead.setAttribute('aria-expanded', open ? 'true' : 'false');
    questEl.setAttribute('data-complete', q.complete ? '1' : '0');

    var found = q.found.length;
    setText(questEmoji, q.emoji || '\ud83e\udded');
    setText(questTitle, q.title || 'Family quest');
    setText(questKicker, q.complete ? 'Quest complete!' : 'Quest \u00b7 ' + Math.min(found, q.goal) + ' of ' + q.goal);
    setText(questPrompt, q.prompt);
    questHead.setAttribute('aria-label', (q.title || 'Family quest') + ', ' + Math.min(found, q.goal) + ' of ' + q.goal + ' found. ' + (open ? 'Fold' : 'Show details'));

    // The TV's scoreboard when it sends one (the remote's points included), else the phones on the couch.
    var board = q.players || app.players;
    var sig = JSON.stringify([q.goal, q.found, board, app.players, app.me]);
    if (sig === questSig) return;
    questSig = sig;

    questPips.textContent = '';
    for (var i = 0; i < Math.min(q.goal, 8); i++) {
      var pip = el('span', 'pip');
      if (i < found) {
        pip.setAttribute('data-on', '1');
        pip.style.setProperty('--c', finderOf(q.found[i].pid).hex);
      }
      questPips.appendChild(pip);
    }

    questFound.textContent = '';
    if (!found) {
      questFound.appendChild(el('li', 'found-empty', 'Nothing found yet. Open a page that has one, then tap \u201cThis page has it!\u201d'));
    }
    q.found.forEach(function (f) {
      var by = finderOf(f.pid);
      var row = el('li', 'found-row');
      row.style.setProperty('--c', by.hex);
      row.appendChild(el('span', 'found-dot'));
      row.appendChild(el('span', 'found-item', f.item));
      row.appendChild(el('span', 'found-by', by.name));
      if (f.evidence) row.appendChild(el('span', 'found-ev', '\u201c' + f.evidence + '\u201d'));
      questFound.appendChild(row);
    });

    questBoard.textContent = '';
    var ranked = board.slice().sort(function (a, b) { return b.score - a.score; });
    var lead = ranked.length > 1 && ranked[0].score > 0 && ranked[0].score > ranked[1].score ? ranked[0].pid : null;
    ranked.forEach(function (p) {
      var chip = el('span', 'score-chip');
      chip.style.setProperty('--c', COLORS[p.color].hex);
      if (p.pid === app.me.pid) chip.setAttribute('data-me', '1');
      if (p.pid === lead) chip.setAttribute('data-lead', '1');
      chip.appendChild(el('i'));
      chip.appendChild(document.createTextNode(p.pid === app.me.pid ? 'You ' : p.name + ' '));
      chip.appendChild(el('b', null, String(p.score)));
      questBoard.appendChild(chip);
    });
    questBoard.hidden = ranked.length === 0;
  }

  questHead.addEventListener('click', function () {
    var open = questEl.getAttribute('data-open') !== 'true';
    app.questOpen = open;
    renderQuest();
  });

  /* ---- search field / typing into the page */

  var omniFocused = false;
  var omniBlurTimer = 0;

  function omniKind() { return omni.getAttribute('data-kind'); }

  function renderOmni() {
    var f = app.tv.field;
    var key = f.focused ? 'f:' + f.label : '';
    if (key !== app.lastFieldKey) app.typeOverride = false; // a new field got focus: offer typing again
    app.lastFieldKey = key;
    var kind = f.focused && !app.typeOverride ? 'type' : 'go';
    if (omniKind() !== kind) {
      omni.setAttribute('data-kind', kind);
      if (kind === 'type') {
        omniInput.placeholder = 'Type here, it goes to the page';
        omniInput.setAttribute('enterkeyhint', 'send');
        omniInput.setAttribute('autocapitalize', 'sentences');
        omniSend.textContent = 'Send';
        omniSend.setAttribute('aria-label', 'Send to the page and press Enter');
        pulse();
        vibrate(8);
      } else {
        omniInput.placeholder = 'Search or type a web address';
        omniInput.setAttribute('enterkeyhint', 'go');
        omniInput.setAttribute('autocapitalize', 'off');
        omniSend.textContent = 'Go';
        omniSend.setAttribute('aria-label', 'Open on the TV');
      }
      omniInput.setAttribute('aria-label', kind === 'type' ? 'Type into the page field: ' + (f.label || 'text box') : 'Search or type a web address');
    }
    setText(omniLabel, f.label || 'a text box');
    omniTag.hidden = kind !== 'type';
    omniFill.hidden = kind !== 'type';
    omniBack.hidden = !(f.focused && app.typeOverride);
    renderSuggest();
  }

  function pulse() {
    omni.classList.remove('pulse');
    void omni.offsetWidth; // restart the animation
    omni.classList.add('pulse');
  }

  function shake() {
    omni.classList.remove('shake');
    void omni.offsetWidth;
    omni.classList.add('shake');
    vibrate([10, 40, 10]);
  }

  var sentTimer = 0;
  function flashSent() {
    omni.classList.add('sent');
    var label = omniSend.textContent;
    omniSend.textContent = '\u2713';
    clearTimeout(sentTimer);
    sentTimer = setTimeout(function () {
      omni.classList.remove('sent');
      omniSend.textContent = omniKind() === 'type' ? 'Send' : label === '\u2713' ? 'Go' : label;
    }, 900);
    vibrate(10);
  }

  function updateHasText() { omni.classList.toggle('has-text', omniInput.value.trim().length > 0); }

  function remember(text) {
    var recent = (store.read().recent || []).filter(function (s) { return typeof s === 'string' && s !== text; });
    recent.unshift(text);
    store.write({ recent: recent.slice(0, 6) });
  }

  function submitOmni(pressEnter) {
    var text = omniInput.value.replace(/\s+/g, ' ').trim().slice(0, 500);
    if (omniKind() === 'type') {
      if (!text && !pressEnter) { shake(); return; }
      // Empty + Send just presses Enter in the page (e.g. to submit a form that is already filled in).
      var msg = text ? { t: 'type', text: text, submit: pressEnter } : { t: 'key', key: 'enter' };
      if (sendOrNudge(msg)) {
        omniInput.value = '';
        flashSent();
        if (pressEnter) omniInput.blur();
      }
    } else {
      if (!text) { shake(); omniInput.focus(); return; }
      if (sendOrNudge({ t: 'go', text: text })) {
        remember(text);
        omniInput.value = '';
        flashSent();
        omniInput.blur();
      }
    }
    updateHasText();
    renderSuggest();
  }

  omniForm.addEventListener('submit', function (e) { e.preventDefault(); submitOmni(true); });
  omniFill.addEventListener('mousedown', function (e) { e.preventDefault(); }); // keep the keyboard up
  omniFill.addEventListener('click', function () { submitOmni(false); });
  omniInput.addEventListener('input', function () { updateHasText(); renderSuggest(); });
  omniInput.addEventListener('focus', function () {
    clearTimeout(omniBlurTimer);
    omniFocused = true;
    omni.classList.add('focused');
    renderSuggest();
  });
  omniInput.addEventListener('blur', function () {
    // Delay so a tap on a suggestion chip still lands before the chips disappear.
    omniBlurTimer = setTimeout(function () {
      omniFocused = false;
      omni.classList.remove('focused');
      renderSuggest();
    }, 180);
  });
  omniExit.addEventListener('click', function () {
    app.typeOverride = true;
    renderOmni();
    omniInput.focus();
  });
  omniBack.addEventListener('click', function () {
    app.typeOverride = false;
    renderOmni();
    omniInput.focus();
  });

  var suggestSig = '';
  function renderSuggest() {
    var show = omniFocused && omniKind() === 'go' && !omniInput.value.trim() && !omni.hidden;
    if (!show) { suggestEl.hidden = true; return; }
    var recent = (store.read().recent || []).filter(function (s) { return typeof s === 'string' && s; }).slice(0, 4);
    var items = recent.length ? recent : STARTERS;
    var sig = (recent.length ? 'r' : 's') + items.join('\n');
    if (sig !== suggestSig) {
      suggestSig = sig;
      suggestEl.textContent = '';
      suggestEl.appendChild(el('span', 'suggest-label', recent.length ? 'Recent' : 'Try one'));
      items.forEach(function (text) {
        var chip = el('button', 'chip', text);
        chip.type = 'button';
        chip.setAttribute('aria-label', 'Search for ' + text);
        chip.addEventListener('click', function () {
          if (sendOrNudge({ t: 'go', text: text })) {
            remember(text);
            flashSent();
            omniInput.blur();
          }
        });
        suggestEl.appendChild(chip);
      });
    }
    suggestEl.hidden = false;
  }
  suggestEl.addEventListener('mousedown', function (e) { e.preventDefault(); });

  /* ---- quest picker */

  var pickerSig = '';
  function renderPicker() {
    var tv = app.tv;
    var layout = tv.mode === 'quest_picker' ? 'list' : tv.mode === 'home' && (!tv.quest || tv.quest.complete) ? 'row' : '';
    pickerEl.hidden = !layout;
    if (!layout) return;
    pickerEl.setAttribute('data-layout', layout);
    setText(pickerTitle, tv.quest && tv.quest.complete ? 'Ready for another quest?' : 'Start a family quest');
    var sig = JSON.stringify([tv.quests, tv.ai]);
    if (sig === pickerSig) return;
    pickerSig = sig;
    pickerList.textContent = '';
    pickerList.appendChild(questTile('lumo', null, 'Lumo\u2019s pick',
      tv.ai ? 'Made from your recent pages' : 'A surprise from Lumo\u2019s notebook', true));
    tv.quests.forEach(function (q) { pickerList.appendChild(questTile(q.id, q.emoji, q.title, q.prompt, false)); });
  }

  function questTile(id, emoji, title, prompt, isLumo) {
    var b = el('button', 'qtile' + (isLumo ? ' qtile-lumo' : ''));
    b.type = 'button';
    if (isLumo) {
      var moth = el('span', 'qtile-moth');
      moth.innerHTML = lumoMarkup('curious', true);
      b.appendChild(moth);
    } else {
      b.appendChild(el('span', 'qtile-emoji', emoji));
    }
    b.appendChild(el('span', 'qtile-title', title));
    b.appendChild(el('span', 'qtile-prompt', prompt));
    b.setAttribute('aria-label', 'Start quest: ' + title + '. ' + prompt);
    b.addEventListener('click', function () {
      if (b.getAttribute('data-busy') === '1') return;
      if (sendOrNudge({ t: 'quest_start', id: id })) {
        b.setAttribute('data-busy', '1');
        vibrate(12);
        setTimeout(function () { b.removeAttribute('data-busy'); }, 1500);
      }
    });
    return b;
  }

  /* ---- main area: touchpad, cards or rescue */

  function renderMain() {
    var tv = app.tv;
    var rescue = tv.mode === 'rescue' && !!tv.rescue;
    var cards = tv.mode === 'cards';
    var list = tv.mode === 'quest_picker';
    var hideOmni = rescue || cards || list;
    if (hideOmni && document.activeElement === omniInput) omniInput.blur();
    omni.hidden = hideOmni;
    rescueEl.hidden = !rescue;
    cardsEl.hidden = !cards;
    padZone.hidden = rescue || cards || list;
    pad.setMode(tv.mode === 'browse' ? 'pointer' : 'focus');
    if (cards) renderCards();
    else lastCard = { index: -1, count: -1 }; // a new deck starts without a flip animation
    if (rescue) renderRescue();
  }

  var cardsCard = $('cards-card');
  var cardsTitle = $('cards-title');
  var cardsCount = $('cards-count');
  var cardsNo = $('cards-no');
  var cardsDots = $('cards-dots');
  var cardsPrev = $('cards-prev');
  var cardsNext = $('cards-next');
  var cardsRead = $('cards-read');
  var lastCard = { index: -1, count: -1 };

  function renderCards() {
    var c = app.tv.cards;
    var count = c ? c.count : 0;
    var idx = count ? clamp(c.index, 0, count - 1) : 0;
    if (!count) {
      setText(cardsTitle, 'Lumo is making cards...');
      setText(cardsCount, 'Reading the page for the good bits');
      setText(cardsNo, 'No. \u2026');
    } else {
      setText(cardsTitle, c.title || 'Card ' + (idx + 1));
      setText(cardsCount, 'Card ' + (idx + 1) + ' of ' + count);
      setText(cardsNo, 'No. ' + (idx + 1 < 10 ? '0' : '') + (idx + 1));
    }
    cardsPrev.disabled = !count || idx <= 0;
    cardsNext.disabled = !count || idx >= count - 1;
    cardsRead.disabled = !count;
    if (count !== lastCard.count) {
      cardsDots.textContent = '';
      for (var i = 0; i < Math.min(count, 10); i++) cardsDots.appendChild(el('span', 'cards-dot'));
    }
    Array.prototype.forEach.call(cardsDots.children, function (d, i) { d.setAttribute('data-on', i === idx ? '1' : '0'); });
    if (lastCard.count === count && lastCard.index >= 0 && idx !== lastCard.index) {
      var cls = idx > lastCard.index ? 'flip-next' : 'flip-prev';
      cardsCard.classList.remove('flip-next', 'flip-prev');
      void cardsCard.offsetWidth;
      cardsCard.classList.add(cls);
    }
    lastCard = { index: idx, count: count };
  }

  function flipCard(dir) {
    var btn = dir > 0 ? cardsNext : cardsPrev;
    if (btn.disabled) return;
    if (sendOrNudge({ t: 'key', key: dir > 0 ? 'right' : 'left' })) vibrate(8);
  }

  cardsPrev.addEventListener('click', function () { flipCard(-1); });
  cardsNext.addEventListener('click', function () { flipCard(1); });
  var readTimer = 0;
  cardsRead.addEventListener('click', function () {
    if (!sendOrNudge({ t: 'read' })) return;
    vibrate(10);
    cardsRead.classList.add('reading');
    clearTimeout(readTimer);
    readTimer = setTimeout(function () { cardsRead.classList.remove('reading'); }, 3500);
  });
  $('cards-close').addEventListener('click', function () { if (sendOrNudge({ t: 'key', key: 'back' })) vibrate(8); });

  // Swipe the card itself to flip, like a real deck.
  (function () {
    var start = null;
    cardsCard.addEventListener('pointerdown', function (e) { start = { id: e.pointerId, x: e.clientX, y: e.clientY, t: now() }; });
    cardsCard.addEventListener('pointerup', function (e) {
      if (!start || start.id !== e.pointerId) return;
      var dx = e.clientX - start.x, dy = e.clientY - start.y, dt = now() - start.t;
      start = null;
      if (Math.abs(dx) > 44 && Math.abs(dx) > Math.abs(dy) * 1.3 && dt < 800) flipCard(dx < 0 ? 1 : -1);
    });
    cardsCard.addEventListener('pointercancel', function () { start = null; });
  })();

  // Compass for a family quest, telescope for a curious detour, crescent moon for winding down (as on the TV).
  var RESCUE_ICONS = { quest: '\ud83e\udded', detour: '\ud83d\udd2d', wind_down: '\ud83c\udf19' };

  var rescueSig = '';
  function renderRescue() {
    var r = app.tv.rescue;
    setText($('rescue-headline'), r.headline || 'Want to try something different?');
    var detail = $('rescue-detail');
    setText(detail, r.detail);
    detail.hidden = !r.detail;
    var sig = JSON.stringify(r.options);
    if (sig === rescueSig) return;
    rescueSig = sig;
    var box = $('rescue-options');
    box.textContent = '';
    var icons = ['\u2728', '\ud83e\udded'];
    r.options.forEach(function (o, i) {
      var b = el('button', 'rescue-opt');
      b.type = 'button';
      // The TV's icon for this kind of option; by position only when the TV doesn't say which kind it is.
      var kindIcon = Object.prototype.hasOwnProperty.call(RESCUE_ICONS, o.kind) ? RESCUE_ICONS[o.kind] : '';
      var icon = el('span', 'rescue-opt-icon', kindIcon || icons[i] || '\u2728');
      icon.setAttribute('aria-hidden', 'true');
      var text = el('span', 'rescue-opt-text');
      text.appendChild(el('span', 'rescue-opt-label', o.label));
      if (o.detail) text.appendChild(el('span', 'rescue-opt-detail', o.detail));
      b.appendChild(icon);
      b.appendChild(text);
      b.insertAdjacentHTML('beforeend', '<svg viewBox="0 0 24 24" aria-hidden="true"><path d="M9 5l7 7-7 7"/></svg>');
      b.addEventListener('click', function () { chooseRescue(i); });
      box.appendChild(b);
    });
  }

  var rescueLockUntil = 0;
  function chooseRescue(choice) {
    if (Date.now() < rescueLockUntil) return;
    if (sendOrNudge({ t: 'rescue', choice: choice })) {
      rescueLockUntil = Date.now() + 1200;
      vibrate(12);
    }
  }
  $('rescue-fine').addEventListener('click', function () { chooseRescue(-1); });

  /* ---- quest submit and dock */

  var submitTimer = 0;
  function renderSubmit() {
    var tv = app.tv;
    var q = tv.quest;
    var show = !!q && !q.complete && (tv.mode === 'browse' || tv.mode === 'cards');
    submitBtn.hidden = !show;
    if (!show) return;
    var locked = Date.now() < app.submitLockUntil;
    submitBtn.disabled = tv.checking || locked;
    setText(submitText, tv.checking ? 'Lumo is checking this page...' : locked ? 'Sending to Lumo...' : 'This page has it!');
    submitBtn.setAttribute('aria-busy', submitBtn.disabled ? 'true' : 'false');
  }

  submitBtn.addEventListener('click', function () {
    if (submitBtn.disabled) return;
    if (!sendOrNudge({ t: 'quest_submit' })) return;
    vibrate([10, 30, 20]);
    app.submitLockUntil = Date.now() + 2500;
    renderSubmit();
    clearTimeout(submitTimer);
    submitTimer = setTimeout(renderSubmit, 2600);
  });

  function renderDock() {
    var inCards = app.tv.mode === 'cards';
    dockCards.setAttribute('aria-pressed', inCards ? 'true' : 'false');
    dockCards.setAttribute('aria-label', inCards ? 'Close the cards' : 'Make it cards');
  }

  Array.prototype.forEach.call(document.querySelectorAll('.dock-btn[data-key]'), function (b) {
    b.addEventListener('click', function () {
      if (sendOrNudge({ t: 'key', key: b.getAttribute('data-key') })) vibrate(8);
    });
  });
  dockCards.addEventListener('click', function () {
    var msg = app.tv.mode === 'cards' ? { t: 'key', key: 'back' } : { t: 'cards' };
    if (sendOrNudge(msg)) vibrate(10);
  });
  Array.prototype.forEach.call(document.querySelectorAll('.zoom-btn'), function (b) {
    b.addEventListener('click', function () {
      if (sendOrNudge({ t: 'zoom', delta: int(b.getAttribute('data-zoom'), 1) > 0 ? 1 : -1 })) vibrate(8);
    });
  });

  /* ============================================================ 7. touchpad */

  // Browse mode ("pointer"): one finger moves the TV cursor (relative deltas, batched once per animation
  // frame), a quick touch taps, two fingers scroll the page with momentum, and the wheel on the right edge
  // scrolls with one thumb. Every other mode is focus-based on the TV ("focus"): a swipe becomes arrow-key
  // steps and a tap presses Enter, like a TV remote's touch surface.
  var pad = (function () {
    var padEl = $('pad');
    var spot = $('pad-spot');
    var rail = $('rail');
    var track = $('rail-track');
    var hintTitle = $('pad-hint-title');
    var hintSub = $('pad-hint-sub');

    var mode = 'pointer';
    var pts = new Map();     // pointerId -> { x, y, sx, sy }
    var gesture = 'idle';    // idle | pending | move | swipe | scroll | done
    var t0 = 0;
    var held = { x: 0, y: 0 };    // movement held back while we still might be a tap
    var acc = { x: 0, y: 0 };     // swipe travel not yet turned into steps
    var steps = 0;
    var out = { mx: 0, my: 0, sx: 0, sy: 0 };
    var samples = [];        // recent scroll movement for momentum: { t, dx, dy }
    var frame = 0;
    var glideFrame = 0;
    var rect = null;
    var spotPos = null;
    var railPt = null;
    var wheel = 0;
    var detent = 0;

    function setMode(m) {
      if (m === mode) return;
      mode = m;
      padEl.setAttribute('data-mode', m);
      cancel();
      if (m === 'pointer') {
        hintTitle.textContent = 'Slide to move the pointer';
        hintSub.textContent = 'Tap to click \u00b7 Two fingers to scroll';
        padEl.setAttribute('aria-label', 'Touchpad. Slide to move the pointer, tap to click, two fingers to scroll.');
      } else {
        hintTitle.textContent = 'Swipe to move around';
        hintSub.textContent = 'Tap to choose';
        padEl.setAttribute('aria-label', 'Touchpad. Swipe to move between items on the TV, tap to choose.');
      }
    }

    function schedule() { if (!frame) frame = requestAnimationFrame(flush); }

    /** One network message per kind per frame, with fractional remainders carried over. */
    function flush() {
      frame = 0;
      if (spotPos && rect) {
        var x = spotPos.x - rect.left, y = spotPos.y - rect.top;
        spot.style.transform = 'translate3d(' + x + 'px,' + y + 'px,0)';
        padEl.style.setProperty('--px', x + 'px');
        padEl.style.setProperty('--py', y + 'px');
        spotPos = null;
      }
      if (!app.welcomed) { out.mx = out.my = out.sx = out.sy = 0; return; }
      var ws = net.ws;
      if (ws && ws.bufferedAmount > MAX_BUFFERED) { schedule(); return; } // congested Wi-Fi: coalesce
      var dx, dy;
      if (out.mx || out.my) {
        dx = r1(out.mx); dy = r1(out.my);
        if (dx || dy) { send({ t: 'move', dx: dx, dy: dy }); out.mx -= dx; out.my -= dy; }
      }
      if (out.sx || out.sy) {
        dx = r1(out.sx); dy = r1(out.sy);
        if (dx || dy) { send({ t: 'scroll', dx: dx, dy: dy }); out.sx -= dx; out.sy -= dy; }
      }
    }

    function queueMove(dx, dy) { out.mx += dx; out.my += dy; schedule(); }

    function queueScroll(dx, dy, fromFinger) {
      out.sx += dx; out.sy += dy;
      if (fromFinger) {
        var t = now();
        samples.push({ t: t, dx: dx, dy: dy });
        while (samples.length && t - samples[0].t > 120) samples.shift();
      }
      schedule();
    }

    function moveSpot(x, y) { spotPos = { x: x, y: y }; schedule(); }

    function stopGlide() { if (glideFrame) cancelAnimationFrame(glideFrame); glideFrame = 0; }

    /** Momentum after a flick: the page keeps coasting and slows down, like scrolling on the phone. */
    function coast() {
      var t = now();
      var recent = samples.filter(function (s) { return t - s.t < 100; });
      samples = [];
      if (recent.length < 2) return;
      var span = Math.max(16, t - recent[0].t);
      var vx = 0, vy = 0;
      recent.forEach(function (s) { vx += s.dx; vy += s.dy; });
      vx = clamp(vx / span, -4, 4);
      vy = clamp(vy / span, -4, 4);
      if (Math.abs(vy) < 0.25 && Math.abs(vx) < 0.25) return;
      stopGlide();
      var last = now();
      var step = function () {
        var tt = now();
        var dt = Math.min(48, tt - last);
        last = tt;
        var decay = Math.pow(0.94, dt / 16.7);
        vx *= decay; vy *= decay;
        queueScroll(vx * dt, vy * dt, false);
        glideFrame = Math.abs(vx) < 0.03 && Math.abs(vy) < 0.03 ? 0 : requestAnimationFrame(step);
      };
      glideFrame = requestAnimationFrame(step);
    }

    /** Eased scroll by a fixed amount (tapping an end of the wheel). */
    function glide(dy, ms) {
      stopGlide();
      var start = now(), sent = 0;
      var step = function () {
        var k = Math.min(1, (now() - start) / ms);
        var target = dy * (1 - Math.pow(1 - k, 3));
        queueScroll(0, target - sent, false);
        sent = target;
        glideFrame = k < 1 ? requestAnimationFrame(step) : 0;
      };
      glideFrame = requestAnimationFrame(step);
    }

    function ripple(x, y) {
      if (!rect) return;
      var r = el('span', 'ripple');
      r.style.left = (x - rect.left) + 'px';
      r.style.top = (y - rect.top) + 'px';
      padEl.appendChild(r);
      setTimeout(function () { if (r.parentNode) r.parentNode.removeChild(r); }, 500);
    }

    function flash(dir) {
      var f = el('span', 'step-flash');
      f.setAttribute('data-dir', dir);
      padEl.appendChild(f);
      setTimeout(function () { if (f.parentNode) f.parentNode.removeChild(f); }, 340);
    }

    function stepKey(dir) {
      send({ t: 'key', key: dir });
      steps++;
      vibrate(6);
      flash(dir);
    }

    /** Turns swipe travel into arrow steps along the dominant axis. */
    function stepCheck() {
      for (var guard = 0; guard < 3; guard++) {
        var ax = Math.abs(acc.x), ay = Math.abs(acc.y);
        if (ax < STEP_PX && ay < STEP_PX) return;
        if (ax >= ay) {
          stepKey(acc.x > 0 ? 'right' : 'left');
          acc.x -= (acc.x > 0 ? 1 : -1) * STEP_PX;
          acc.y = 0;
        } else {
          stepKey(acc.y > 0 ? 'down' : 'up');
          acc.y -= (acc.y > 0 ? 1 : -1) * STEP_PX;
          acc.x = 0;
        }
      }
    }

    function tap(x, y) {
      if (mode === 'pointer') send({ t: 'tap' });
      else send({ t: 'key', key: 'enter' });
      vibrate(10);
      ripple(x, y);
    }

    function blurField() {
      var a = document.activeElement;
      if (a && a.tagName === 'INPUT') a.blur();
    }

    function onDown(e) {
      if (e.pointerType === 'mouse' && e.button !== 0) return;
      e.preventDefault();
      stopGlide();
      blurField();
      if (pts.size >= 2) return; // a third finger changes nothing
      try { padEl.setPointerCapture(e.pointerId); } catch (err) { /* older WebKit */ }
      rect = padEl.getBoundingClientRect();
      pts.set(e.pointerId, { x: e.clientX, y: e.clientY, sx: e.clientX, sy: e.clientY });
      if (pts.size === 1) {
        gesture = 'pending';
        t0 = now();
        held.x = held.y = 0;
        acc.x = acc.y = 0;
        steps = 0;
        samples = [];
        padEl.classList.add('active');
      } else {
        // Second finger: in browse mode this is a scroll; drop any movement we were holding back.
        gesture = mode === 'pointer' ? 'scroll' : 'done';
        held.x = held.y = 0;
        samples = [];
      }
      moveSpot(e.clientX, e.clientY);
    }

    function onMove(e) {
      var p = pts.get(e.pointerId);
      if (!p) return;
      e.preventDefault();
      var dx = e.clientX - p.x, dy = e.clientY - p.y;
      p.x = e.clientX;
      p.y = e.clientY;
      if (!dx && !dy) return;
      moveSpot(e.clientX, e.clientY);
      switch (gesture) {
        case 'pending':
          held.x += dx;
          held.y += dy;
          if (Math.hypot(p.x - p.sx, p.y - p.sy) >= TAP_SLOP || now() - t0 > TAP_MS) {
            if (mode === 'pointer') { gesture = 'move'; queueMove(held.x, held.y); }
            else { gesture = 'swipe'; acc.x = held.x; acc.y = held.y; stepCheck(); }
            held.x = held.y = 0;
          }
          break;
        case 'move':
          queueMove(dx, dy);
          break;
        case 'swipe':
          acc.x += dx;
          acc.y += dy;
          stepCheck();
          break;
        case 'scroll': {
          // Each finger reports its own movement: averaging gives the two-finger drag. Natural direction:
          // fingers up = page moves up = scroll down, same as scrolling on the phone itself.
          var k = 1 / pts.size;
          queueScroll(-dx * k, -dy * k, true);
          break;
        }
        default:
          break;
      }
    }

    function onUp(e, cancelled) {
      var p = pts.get(e.pointerId);
      if (!p) return;
      pts.delete(e.pointerId);
      try { padEl.releasePointerCapture(e.pointerId); } catch (err) { /* already released */ }
      if (!cancelled && pts.size === 0) {
        var dt = now() - t0;
        var ddx = p.x - p.sx, ddy = p.y - p.sy;
        if (gesture === 'pending' && dt < TAP_MS && Math.hypot(ddx, ddy) < TAP_SLOP) {
          tap(p.x, p.y);
        } else if (gesture === 'swipe' && steps === 0 && dt < 320 && Math.max(Math.abs(ddx), Math.abs(ddy)) > 24) {
          // A quick flick shorter than a full step still moves one item.
          stepKey(Math.abs(ddx) >= Math.abs(ddy) ? (ddx > 0 ? 'right' : 'left') : (ddy > 0 ? 'down' : 'up'));
        } else if (gesture === 'scroll') {
          coast();
        }
      }
      if (pts.size === 0) {
        padEl.classList.add('used'); // the hint has done its job: fade it back
        gesture = 'idle';
        padEl.classList.remove('active');
      }
    }

    function cancel() {
      pts.forEach(function (_, id) { try { padEl.releasePointerCapture(id); } catch (err) { /* ignore */ } });
      pts.clear();
      gesture = 'idle';
      held.x = held.y = acc.x = acc.y = 0;
      out.mx = out.my = out.sx = out.sy = 0;
      samples = [];
      railPt = null;
      stopGlide();
      padEl.classList.remove('active');
      rail.classList.remove('active');
    }

    padEl.addEventListener('pointerdown', onDown);
    padEl.addEventListener('pointermove', onMove);
    padEl.addEventListener('pointerup', function (e) { onUp(e, false); });
    padEl.addEventListener('pointercancel', function (e) { onUp(e, true); });
    padEl.addEventListener('lostpointercapture', function (e) { if (pts.has(e.pointerId)) onUp(e, true); });
    padEl.addEventListener('contextmenu', function (e) { e.preventDefault(); });
    // Belt and braces for older iOS: stop the page from scrolling, zooming or bouncing under the pad.
    padEl.addEventListener('touchstart', function (e) { if (e.cancelable) e.preventDefault(); }, { passive: false });
    padEl.addEventListener('touchmove', function (e) { if (e.cancelable) e.preventDefault(); }, { passive: false });

    // The scroll wheel: drag like a scrollbar (thumb down = page down), tap an end to jump a page.
    rail.addEventListener('pointerdown', function (e) {
      e.stopPropagation();
      e.preventDefault();
      if (railPt || pts.size) return;
      stopGlide();
      blurField();
      try { rail.setPointerCapture(e.pointerId); } catch (err) { /* ignore */ }
      var r = rail.getBoundingClientRect();
      railPt = { id: e.pointerId, y: e.clientY, sy: e.clientY, t: now(), top: r.top, h: r.height };
      samples = [];
      detent = 0;
      rail.classList.add('active');
      padEl.classList.add('active');
    });
    rail.addEventListener('pointermove', function (e) {
      if (!railPt || railPt.id !== e.pointerId) return;
      e.stopPropagation();
      e.preventDefault();
      var dy = e.clientY - railPt.y;
      railPt.y = e.clientY;
      if (!dy) return;
      queueScroll(0, dy * RAIL_GAIN, true);
      wheel += dy;
      track.style.setProperty('--wheel', wheel.toFixed(1) + 'px');
      detent += Math.abs(dy);
      if (detent >= WHEEL_DETENT) { detent = 0; vibrate(3); }
    });
    function railEnd(e, cancelled) {
      if (!railPt || railPt.id !== e.pointerId) return;
      e.stopPropagation();
      var pt = railPt;
      railPt = null;
      try { rail.releasePointerCapture(e.pointerId); } catch (err) { /* ignore */ }
      rail.classList.remove('active');
      if (!pts.size) padEl.classList.remove('active');
      if (cancelled) return;
      if (now() - pt.t < TAP_MS && Math.abs(e.clientY - pt.sy) < TAP_SLOP) {
        var upper = e.clientY - pt.top < pt.h / 2;
        glide(upper ? -RAIL_PAGE : RAIL_PAGE, 260);
        vibrate(8);
      } else {
        coast();
      }
    }
    rail.addEventListener('pointerup', function (e) { railEnd(e, false); });
    rail.addEventListener('pointercancel', function (e) { railEnd(e, true); });
    rail.addEventListener('lostpointercapture', function (e) { railEnd(e, true); });

    return { setMode: setMode, cancel: cancel };
  })();

  /* ============================================================ 8. toasts, celebrate, reconnect, sheet */

  var toastsEl = $('toasts');

  function toast(text, kind) {
    text = String(text || '').trim();
    if (!text) return;
    kind = kind === 'good' || kind === 'bad' ? kind : 'info';
    var t = Date.now();
    if (text === app.lastToast.text && t - app.lastToast.at < 1500) return;
    app.lastToast = { text: text, at: t };

    var node = el('div', 'toast toast-' + kind);
    node.setAttribute('role', kind === 'bad' ? 'alert' : 'status');
    var icon = el('span', 'toast-icon');
    icon.setAttribute('aria-hidden', 'true');
    if (kind === 'info') icon.innerHTML = lumoMarkup('curious', true);
    else icon.textContent = kind === 'good' ? '\u2713' : '!';
    node.appendChild(icon);
    node.appendChild(el('span', 'toast-text', text));
    toastsEl.appendChild(node);
    while (toastsEl.children.length > 2) toastsEl.removeChild(toastsEl.firstElementChild);

    requestAnimationFrame(function () { requestAnimationFrame(function () { node.classList.add('in'); }); });
    var kill = function () {
      if (!node.parentNode || node.classList.contains('out')) return;
      node.classList.remove('in');
      node.classList.add('out');
      setTimeout(function () { if (node.parentNode) node.parentNode.removeChild(node); }, 300);
    };
    setTimeout(kill, kind === 'bad' ? 4200 : 3000);
    node.addEventListener('click', kill);
    if (kind === 'bad') vibrate([12, 60, 12]);
  }

  /** Paper confetti on a canvas: a burst from the middle plus two side cannons, in the scorer's color. */
  var confetti = (function () {
    var canvas = $('confetti');
    var ctx = null;
    var parts = [];
    var frameId = 0;
    var last = 0;
    var W = 0, H = 0;

    function resize() {
      var dpr = Math.min(2, window.devicePixelRatio || 1);
      W = canvas.clientWidth || window.innerWidth;
      H = canvas.clientHeight || window.innerHeight;
      canvas.width = Math.round(W * dpr);
      canvas.height = Math.round(H * dpr);
      try { ctx = canvas.getContext('2d'); } catch (e) { ctx = null; }
      if (ctx) ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
    }

    function spawn(n, x, y, angle, spread, vMin, vMax, palette) {
      for (var i = 0; i < n; i++) {
        var a = angle + (Math.random() - 0.5) * spread;
        var v = vMin + Math.random() * (vMax - vMin);
        parts.push({
          x: x, y: y, vx: Math.cos(a) * v, vy: Math.sin(a) * v,
          w: 6 + Math.random() * 7, h: 4 + Math.random() * 5,
          r: Math.random() * 6.283, vr: (Math.random() - 0.5) * 14,
          tilt: Math.random() * 6.283, vt: 4 + Math.random() * 8,
          color: palette[(Math.random() * palette.length) | 0],
          round: Math.random() < 0.22, life: 0, ttl: 1.9 + Math.random() * 1.1,
        });
      }
    }

    function burst(hex) {
      if (reduceMotion.matches) return;
      resize();
      if (!ctx) return;
      var palette = [hex, hex, hex, lighten(hex, 0.45), '#F7EFE2', '#FFB547'];
      spawn(70, W / 2, H * 0.42, -Math.PI / 2, Math.PI * 2, 220, 720, palette);
      spawn(40, -8, H * 0.88, -Math.PI / 3.2, 0.55, 750, 1250, palette);
      spawn(40, W + 8, H * 0.88, -Math.PI + Math.PI / 3.2, 0.55, 750, 1250, palette);
      if (parts.length > 450) parts.splice(0, parts.length - 450);
      if (!frameId) { last = now(); frameId = requestAnimationFrame(step); }
    }

    function step() {
      var t = now();
      var dt = Math.min(0.04, (t - last) / 1000);
      last = t;
      ctx.clearRect(0, 0, W, H);
      var alive = [];
      for (var i = 0; i < parts.length; i++) {
        var p = parts[i];
        p.life += dt;
        if (p.life > p.ttl || p.y > H + 40) continue;
        p.vy = Math.min(p.vy + 1500 * dt, 560); // paper falls slowly once it loses its kick
        p.vx *= 1 - 1.4 * dt;
        p.x += (p.vx + Math.sin(p.life * 7 + p.tilt) * 30) * dt;
        p.y += p.vy * dt;
        p.r += p.vr * dt;
        p.tilt += p.vt * dt;
        ctx.globalAlpha = Math.min(1, (p.ttl - p.life) / 0.5);
        ctx.fillStyle = p.color;
        ctx.save();
        ctx.translate(p.x, p.y);
        ctx.rotate(p.r);
        ctx.scale(1, Math.cos(p.tilt));
        if (p.round) { ctx.beginPath(); ctx.arc(0, 0, p.w / 2.6, 0, 6.283); ctx.fill(); }
        else ctx.fillRect(-p.w / 2, -p.h / 2, p.w, p.h);
        ctx.restore();
        alive.push(p);
      }
      ctx.globalAlpha = 1;
      parts = alive;
      frameId = parts.length ? requestAnimationFrame(step) : 0;
      if (!frameId) ctx.clearRect(0, 0, W, H);
    }

    function stop() {
      if (frameId) cancelAnimationFrame(frameId);
      frameId = 0;
      parts = [];
      if (ctx) ctx.clearRect(0, 0, W, H);
    }

    return { burst: burst, stop: stop };
  })();

  var celEl = $('celebrate');
  var celText = $('celebrate-text');
  var celLumo = celEl.querySelector('.celebrate-lumo');
  var celebrateUi = {
    timer: 0,
    show: function (text, color) {
      var c = COLORS[colorIndex(color)];
      celEl.style.setProperty('--cel-rgb', c.rgb);
      setText(celText, text || 'Nice find!');
      var len = celText.textContent.length;
      celText.style.fontSize = len > 40 ? '28px' : len > 22 ? '36px' : '';
      clearTimeout(celebrateUi.timer);
      celEl.classList.remove('leaving');
      celEl.hidden = false;
      [celText, celLumo].forEach(function (n) { n.style.animation = 'none'; void n.offsetWidth; n.style.animation = ''; });
      confetti.burst(c.hex);
      vibrate([30, 50, 30, 50, 120]);
      celebrateUi.timer = setTimeout(celebrateUi.hide, 2800);
    },
    hide: function () {
      if (celEl.hidden || celEl.classList.contains('leaving')) return;
      clearTimeout(celebrateUi.timer);
      celEl.classList.add('leaving');
      celebrateUi.timer = setTimeout(function () {
        celEl.hidden = true;
        celEl.classList.remove('leaving');
        confetti.stop();
      }, 340);
    },
  };
  celEl.addEventListener('click', celebrateUi.hide);

  var rcEl = $('reconnect');
  var reconnectUi = {
    timer: 0,
    show: function (delay) {
      clearTimeout(reconnectUi.timer);
      reconnectUi.timer = setTimeout(function () {
        if (app.welcomed || !app.joined || app.rejected) return;
        if (document.activeElement && document.activeElement.tagName === 'INPUT') document.activeElement.blur();
        rcEl.hidden = false;
        reconnectUi.update();
      }, delay || 0);
    },
    hide: function () {
      clearTimeout(reconnectUi.timer);
      rcEl.hidden = true;
    },
    update: function () {
      if (rcEl.hidden) return;
      var retryBtn = $('rc-retry');
      if (app.replaced) {
        setText($('rc-title'), 'You\u2019re connected in another tab');
        setText($('rc-detail'), 'Your seat moved to another tab or window on this phone. Close that one, or use this one instead.');
        setText(retryBtn, 'Use this phone here');
        retryBtn.hidden = false;
        return;
      }
      setText(retryBtn, 'Try now');
      var rejoining = app.greeting === 'rejoin';
      setText($('rc-title'), rejoining ? 'Rejoining as ' + app.me.name + '...' : 'Reconnecting to the TV...');
      var n = net.attempt;
      setText($('rc-detail'), n >= 4
        ? 'Still looking. Is this phone on the same Wi-Fi as the TV, and is Wander open on it?'
        : rejoining ? 'Finding your seat on the couch.' : 'Hang tight. Lumo is fluttering back.');
      $('rc-retry').hidden = n < 3;
    },
  };
  $('rc-retry').addEventListener('click', function () {
    if (app.replaced) { reclaim(); return; }
    net.attempt = 1;
    retryNow();
    reconnectUi.update();
  });
  $('rc-leave').addEventListener('click', leave);

  var sheetEl = $('sheet');
  var sheetUi = {
    sig: '',
    show: function () {
      sheetUi.sig = '';
      sheetUi.render();
      sheetEl.hidden = false;
      $('sheet-close').focus();
    },
    hide: function () { sheetEl.hidden = true; },
    render: function () {
      var me = playerBy(app.me.pid);
      var score = me ? me.score : 0;
      setText($('sheet-name'), app.me.name || 'You');
      setText($('sheet-sub'), COLORS[app.me.color].name + ' \u00b7 ' + score + (score === 1 ? ' point' : ' points'));
      var sig = JSON.stringify([app.players, app.me.pid]);
      if (sig === sheetUi.sig) return;
      sheetUi.sig = sig;
      var list = $('sheet-roster');
      list.textContent = '';
      var everyone = app.players.length ? app.players : [{ pid: app.me.pid, name: app.me.name, color: app.me.color, score: 0 }];
      everyone.forEach(function (p) {
        var li = el('li');
        li.style.setProperty('--c', COLORS[p.color].hex);
        li.appendChild(el('i'));
        var name = el('span', 'r-name', p.name + ' ');
        if (p.pid === app.me.pid) name.appendChild(el('span', 'r-you', '(you)'));
        li.appendChild(name);
        li.appendChild(el('span', 'r-score', String(p.score)));
        list.appendChild(li);
      });
    },
  };
  meBtn.addEventListener('click', sheetUi.show);
  sheetEl.addEventListener('click', function (e) {
    var t = e.target;
    if (t && t.getAttribute && t.getAttribute('data-close') === '1') sheetUi.hide();
  });
  $('sheet-switch').addEventListener('click', leave);
  document.addEventListener('keydown', function (e) {
    if (e.key === 'Escape' && !sheetEl.hidden) sheetUi.hide();
  });

  /** Gives up the seat on purpose and goes back to the join screen, keeping name and color filled in. */
  function leave() {
    disconnect();
    app.rejected = false;
    app.everWelcomed = false;
    app.players = [];
    app.tv = emptyTv();
    reconnectUi.hide();
    sheetUi.hide();
    celebrateUi.hide();
    setConn('wait');
    joinUi.clear();
    joinUi.busy(false);
    joinUi.customLabel = '';
    showScreen('join');
    joinUi.init();
  }

  /* ============================================================ 9. viewport and keyboard */

  // The layout height follows the window, except when the on-screen keyboard shrinks it while typing:
  // then everything stays put (no jump) and the keyboard simply covers the bottom of the touchpad.
  // Height breakpoints are classes computed from this stable size (see "small and short screens" in the CSS),
  // so a keyboard that resizes the window cannot flip them mid-typing either.
  var stable = { h: 0, w: 0 };
  var shrinkTimer = 0;
  function isTyping() {
    var a = document.activeElement;
    return !!a && (a.tagName === 'INPUT' || a.tagName === 'TEXTAREA');
  }
  function applySize(h, w) {
    stable.h = h;
    stable.w = w;
    appEl.style.setProperty('--app-h', h + 'px');
    appEl.style.setProperty('--quest-max', Math.round(h * 0.38) + 'px');
    appEl.classList.toggle('h880', h <= 880);
    appEl.classList.toggle('h700', h <= 700);
    appEl.classList.toggle('h600', h <= 600);
    appEl.classList.toggle('sideways', w > h && h <= 520);
  }
  function syncHeight() {
    var h = window.innerHeight, w = window.innerWidth;
    var shrunk = stable.h && w === stable.w && h < stable.h - 90;
    if (shrunk && isTyping()) return; // keyboard is up: keep everything where it is
    if (shrunk) {
      // Not typing any more but still short: usually the keyboard is still sliding away. Look again shortly
      // and only accept the smaller size if it sticks.
      if (!shrinkTimer) {
        shrinkTimer = setTimeout(function () {
          shrinkTimer = 0;
          var hh = window.innerHeight, ww = window.innerWidth;
          if (!isTyping() && (ww !== stable.w || hh < stable.h - 90)) applySize(hh, ww);
        }, 450);
      }
      return;
    }
    clearTimeout(shrinkTimer);
    shrinkTimer = 0;
    applySize(h, w);
  }
  window.addEventListener('resize', syncHeight);
  window.addEventListener('orientationchange', function () { setTimeout(syncHeight, 300); });
  document.addEventListener('focusout', function () {
    // iOS can leave a fixed layout scrolled after the keyboard closes; put it back.
    setTimeout(function () {
      if (isTyping()) return;
      if (window.scrollY || document.documentElement.scrollTop) window.scrollTo(0, 0);
      syncHeight();
    }, 80);
  });

  // No pinch zoom or double-tap zoom anywhere: this is a remote, not a document.
  document.addEventListener('gesturestart', function (e) { e.preventDefault(); });
  document.addEventListener('touchmove', function (e) {
    if (e.touches && e.touches.length > 1 && e.cancelable) e.preventDefault();
  }, { passive: false });

  document.addEventListener('visibilitychange', function () {
    if (document.visibilityState === 'visible') wake();
    else pad.cancel();
  });
  window.addEventListener('pageshow', function (e) { if (e.persisted) wake(); });
  window.addEventListener('online', wake);
  window.addEventListener('focus', wake);

  /* ============================================================ 10. boot */

  Array.prototype.forEach.call(document.querySelectorAll('[data-lumo]'), mountLumo);
  syncHeight();
  joinUi.init();
  // Colors are set by now; let transitions run from the next frame on (no fade from the default color).
  requestAnimationFrame(function () { requestAnimationFrame(function () { appEl.classList.remove('booting'); }); });

  (function resume() {
    var saved = store.read();
    if (saved.pid && typeof saved.name === 'string' && saved.name && typeof saved.color === 'number') {
      // We had a seat before this reload: go straight back to the remote and quietly rejoin.
      app.me = { pid: String(saved.pid), name: cleanName(saved.name) || COLORS[colorIndex(saved.color)].name, color: colorIndex(saved.color) };
      app.joined = true;
      app.greeting = 'rejoin';
      showScreen('ctrl');
      reconnectUi.show(0);
      connect();
    } else {
      setMeVars(app.pickColor);
    }
  })();
})();
