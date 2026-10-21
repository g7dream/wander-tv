/*
 * Wander TV page helper.
 *
 * BrowseLayer injects this into every page (when it starts, when it first paints, when it finishes).
 * Running it twice is harmless. It does two jobs:
 *   1. Tells the TV when a text field gains or loses focus, through the one-method WanderBridge
 *      interface, so phones can offer their keyboard ("Maya, type into: Search").
 *   2. Offers small helpers the TV calls with evaluateJavascript: scroll under the cursor, page
 *      up/down, type into the focused field, check what is under the cursor, and pull out readable text.
 *
 * Plain ES5 on purpose: older Fire OS WebViews must run it. Nothing here leaves the TV.
 */
(function () {
  'use strict';

  var VERSION = 3;
  if (window.__wander && window.__wander.v === VERSION) {
    window.__wander.check();
    return;
  }

  var bridge = window.WanderBridge;
  var W = { v: VERSION };

  // ---------------------------------------------------------------- text fields

  var TEXT_TYPES = { text: 1, search: 1, email: 1, url: 1, tel: 1, number: 1, password: 1 };

  function isEditable(el) {
    if (!el || el.nodeType !== 1) return false;
    if (el.isContentEditable) return true;
    var tag = el.tagName;
    if (tag === 'TEXTAREA') return !el.readOnly && !el.disabled;
    if (tag === 'INPUT') {
      var t = String(el.type || 'text').toLowerCase();
      return TEXT_TYPES.hasOwnProperty(t) && !el.readOnly && !el.disabled;
    }
    return false;
  }

  /** document.activeElement, followed into open shadow roots and same-origin frames. */
  function deepActive() {
    var el = document.activeElement;
    for (var i = 0; el && i < 8; i++) {
      if (el.shadowRoot && el.shadowRoot.activeElement) {
        el = el.shadowRoot.activeElement;
        continue;
      }
      if (el.tagName === 'IFRAME' || el.tagName === 'FRAME') {
        var inner = null;
        try {
          inner = el.contentDocument && el.contentDocument.activeElement;
        } catch (e) {
          inner = null; // cross-origin frame: we can't see in, and that's fine
        }
        if (inner && inner !== el.contentDocument.body) {
          el = inner;
          continue;
        }
      }
      break;
    }
    return el;
  }

  function squash(s) {
    return String(s == null ? '' : s).replace(/[\s ]+/g, ' ').replace(/^ | $/g, '');
  }

  function attr(el, name) {
    return el.getAttribute ? el.getAttribute(name) : null;
  }

  function labelFor(el) {
    var t = attr(el, 'aria-label') || attr(el, 'placeholder');
    if (!t) {
      var by = attr(el, 'aria-labelledby');
      var ref = by ? (el.ownerDocument || document).getElementById(by.split(' ')[0]) : null;
      if (ref) t = ref.textContent;
    }
    if (!t && el.labels && el.labels.length) t = el.labels[0].textContent;
    if (!t) t = attr(el, 'title');
    if (!t && el.type === 'search') t = 'Search';
    if (!t && el.name) {
      var n = String(el.name);
      t = /^(q|query|search|search_query|s|k|p)$/i.test(n) ? 'Search' : n.replace(/[_\-\[\]]+/g, ' ');
    }
    t = squash(t).slice(0, 40);
    return t || 'text box';
  }

  var lastKey = null;
  function report() {
    var el = deepActive();
    var on = isEditable(el);
    var label = on ? labelFor(el) : null;
    var key = on ? 'on:' + label : 'off';
    if (key === lastKey) return;
    lastKey = key;
    try {
      if (bridge && bridge.onField) bridge.onField(on, label);
    } catch (e) {
      // the bridge is optional; the page must never notice it
    }
  }

  var pending = false;
  function soon() {
    if (pending) return;
    pending = true;
    setTimeout(function () {
      pending = false;
      report();
    }, 0);
  }

  document.addEventListener('focusin', soon, true);
  document.addEventListener('focusout', soon, true);
  window.addEventListener('pageshow', function () {
    lastKey = null;
    soon();
  });
  W.check = soon;

  // ---------------------------------------------------------------- geometry

  /** The visible part of the page in CSS px, in the coordinates elementFromPoint expects. */
  function viewport() {
    var v = window.visualViewport;
    if (v && v.width) return { x: v.offsetLeft, y: v.offsetTop, w: v.width, h: v.height };
    var de = document.documentElement;
    return { x: 0, y: 0, w: window.innerWidth || de.clientWidth, h: window.innerHeight || de.clientHeight };
  }

  /** (fx, fy) are fractions of the TV's WebView, 0..1. */
  function point(fx, fy) {
    var v = viewport();
    return { x: v.x + fx * v.w, y: v.y + fy * v.h, w: v.w, h: v.h };
  }

  // ---------------------------------------------------------------- scrolling

  var SMOOTH_OK = 'scrollBehavior' in document.documentElement.style;

  function overflows(el, vertical) {
    var cs;
    try {
      cs = window.getComputedStyle(el);
    } catch (e) {
      return false;
    }
    var o = vertical ? cs.overflowY : cs.overflowX;
    if (o !== 'auto' && o !== 'scroll' && o !== 'overlay') return false;
    return vertical ? el.scrollHeight > el.clientHeight + 1 : el.scrollWidth > el.clientWidth + 1;
  }

  function hasRoom(el, dx, dy) {
    if (dy > 0 && el.scrollTop + el.clientHeight < el.scrollHeight - 1) return true;
    if (dy < 0 && el.scrollTop > 0) return true;
    if (dx > 0 && el.scrollLeft + el.clientWidth < el.scrollWidth - 1) return true;
    if (dx < 0 && el.scrollLeft > 0) return true;
    return false;
  }

  function parentOf(el) {
    if (el.parentElement) return el.parentElement;
    var p = el.parentNode;
    return p && p.host ? p.host : null; // step out of a shadow root
  }

  /** The nearest scrollable ancestor under (x, y) that can still move in (dx, dy); null = the window. */
  function scrollerAt(x, y, dx, dy) {
    var root = document.scrollingElement || document.documentElement;
    var el = document.elementFromPoint(x, y);
    for (var i = 0; el && el !== root && i < 64; i++) {
      if (((dy && overflows(el, true)) || (dx && overflows(el, false))) && hasRoom(el, dx, dy)) return el;
      el = parentOf(el);
    }
    return null;
  }

  function scrollTarget(target, dx, dy, smooth) {
    if (SMOOTH_OK && target.scrollBy) {
      try {
        target.scrollBy({ left: dx, top: dy, behavior: smooth ? 'smooth' : 'instant' });
        return;
      } catch (e) {
        try {
          target.scrollBy({ left: dx, top: dy, behavior: smooth ? 'smooth' : 'auto' });
          return;
        } catch (e2) {
          // fall through to the old-school way
        }
      }
    }
    if (target === window) {
      window.scrollBy(dx, dy);
    } else {
      target.scrollLeft += dx;
      target.scrollTop += dy;
    }
  }

  /** Scrolls whatever is under the cursor by (fdx, fdy), given as fractions of the visible page. */
  W.scrollAt = function (fx, fy, fdx, fdy, smooth) {
    var p = point(fx, fy);
    var dx = fdx * p.w;
    var dy = fdy * p.h;
    var el = scrollerAt(p.x, p.y, dx, dy);
    scrollTarget(el || window, dx, dy, !!smooth);
    return el ? 'element' : 'window';
  };

  /** Page up (dir -1) or down (dir 1) by 80% of whatever scrolls under the cursor. */
  W.page = function (fx, fy, dir) {
    var p = point(fx, fy);
    var el = scrollerAt(p.x, p.y, 0, dir);
    var h = el ? el.clientHeight : p.h;
    scrollTarget(el || window, 0, dir * h * 0.8, true);
    return el ? 'element' : 'window';
  };

  // ---------------------------------------------------------------- what's under the cursor

  var CLICKABLE = 'a[href],button,select,summary,label,video,audio,[role=button],[role=link],[role=tab],' +
    '[role=menuitem],[role=option],[role=checkbox],[role=radio],[role=switch],[onclick],input:not([type=hidden])';

  /** 0 = nothing special, 1 = something clickable, 2 = a text field. */
  W.hit = function (fx, fy) {
    var p = point(fx, fy);
    var el = document.elementFromPoint(p.x, p.y);
    if (!el) return 0;
    if (isEditable(el)) return 2;
    if (el.closest) {
      var field = el.closest('textarea,input,[contenteditable]');
      if (field && isEditable(field)) return 2;
      if (el.closest(CLICKABLE)) return 1;
    }
    try {
      if (window.getComputedStyle(el).cursor === 'pointer') return 1;
    } catch (e) {
      // detached or odd element: treat as plain
    }
    return 0;
  };

  // ---------------------------------------------------------------- typing

  function makeEvent(type, init, Ctor) {
    var ev;
    try {
      ev = new Ctor(type, init);
    } catch (e) {
      ev = document.createEvent('Event');
      ev.initEvent(type, init.bubbles !== false, !!init.cancelable);
    }
    return ev;
  }

  function fire(el, type) {
    el.dispatchEvent(makeEvent(type, { bubbles: true }, window.Event));
  }

  function fireInput(el, data) {
    var Ctor = window.InputEvent || window.Event;
    el.dispatchEvent(makeEvent('input', { bubbles: true, inputType: 'insertText', data: data }, Ctor));
  }

  function fixed(ev, name, value) {
    try {
      Object.defineProperty(ev, name, { get: function () { return value; } });
    } catch (e) {
      // some engines freeze these; the key/code fields still say Enter
    }
  }

  /** Dispatches one Enter key event; returns false when the page called preventDefault(). */
  function enter(el, type) {
    var ev = makeEvent(type, { key: 'Enter', code: 'Enter', bubbles: true, cancelable: true }, window.KeyboardEvent || window.Event);
    fixed(ev, 'keyCode', 13);
    fixed(ev, 'which', 13);
    if (type === 'keypress') fixed(ev, 'charCode', 13);
    if (!('key' in ev) || ev.key !== 'Enter') fixed(ev, 'key', 'Enter');
    return el.dispatchEvent(ev);
  }

  /**
   * Presses Enter the way a real keyboard would: keydown, keypress, then (unless the page handled it)
   * the form's own submission via requestSubmit(), falling back to submit(); keyup last.
   */
  function pressEnter(el) {
    var form = el.form || (el.closest ? el.closest('form') : null);
    var how = 'keys';
    if (enter(el, 'keydown') && enter(el, 'keypress') && form && el.tagName !== 'TEXTAREA') {
      if (typeof form.requestSubmit === 'function') {
        try {
          form.requestSubmit();
          how = 'requestSubmit';
        } catch (e) {
          how = 'keys';
        }
      }
      if (how === 'keys') {
        try {
          form.submit();
          how = 'submit';
        } catch (e2) {
          how = 'keys';
        }
      }
    }
    enter(el, 'keyup');
    return how;
  }

  function setValue(el, value) {
    var win = (el.ownerDocument && el.ownerDocument.defaultView) || window;
    var proto = el.tagName === 'TEXTAREA' ? win.HTMLTextAreaElement.prototype : win.HTMLInputElement.prototype;
    var desc = Object.getOwnPropertyDescriptor(proto, 'value');
    // The prototype's setter, not el.value: React and friends watch the instance property and only
    // notice a change made underneath them.
    if (desc && desc.set) desc.set.call(el, value);
    else el.value = value;
  }

  /**
   * Makes the focused field say [text], replacing what was there: the phone's text box mirrors the
   * field, so sending the whole text again (even on every keystroke) never doubles it, and a stale
   * query in a search box doesn't get glued onto the new one. Then presses Enter when [submit].
   * Returns 'none' when no text field has focus, 'frame' when focus is inside a cross-origin frame
   * we can't reach (the TV then types with real key events instead).
   */
  W.type = function (text, submit) {
    var el = deepActive();
    if (el && (el.tagName === 'IFRAME' || el.tagName === 'FRAME')) return 'frame';
    if (!isEditable(el)) return 'none';
    text = String(text);
    if (el.isContentEditable) {
      var doc = el.ownerDocument || document;
      var host = el;
      while (host.parentElement && host.parentElement.isContentEditable) host = host.parentElement;
      var done = false;
      try {
        host.focus();
        var range = doc.createRange();
        range.selectNodeContents(host);
        var sel = (doc.defaultView || window).getSelection();
        sel.removeAllRanges();
        sel.addRange(range);
        // insertText keeps the editor's own undo stack and change tracking happy.
        done = text.length ? doc.execCommand('insertText', false, text) : doc.execCommand('delete', false, null);
      } catch (e) {
        done = false;
      }
      if (!done) {
        host.textContent = text;
        fireInput(host, text);
      }
      el = host;
    } else {
      setValue(el, text);
      try {
        el.setSelectionRange(text.length, text.length);
      } catch (x) {
        // email/number inputs have no selection API
      }
      fireInput(el, text);
      fire(el, 'change');
    }
    return submit ? pressEnter(el) : 'typed';
  };

  // ---------------------------------------------------------------- fullscreen video

  /** The fullscreen video, else the one playing, else the biggest one on the page. */
  function mainVideo() {
    var fs = document.fullscreenElement || document.webkitFullscreenElement;
    if (fs) {
      if (fs.tagName === 'VIDEO') return fs;
      var inner = fs.querySelector && fs.querySelector('video');
      if (inner) return inner;
    }
    var all = document.querySelectorAll('video');
    var best = null;
    var bestArea = 0;
    for (var i = 0; i < all.length; i++) {
      var v = all[i];
      if (!v.paused && !v.ended) return v;
      var r = v.getBoundingClientRect();
      var area = r.width * r.height;
      if (area > bestArea) {
        best = v;
        bestArea = area;
      }
    }
    return best;
  }

  /** Remote media keys while a video is fullscreen: 'toggle', 'back' (-10 s) or 'fwd' (+10 s). */
  W.media = function (action) {
    var v = mainVideo();
    if (!v) return 'none';
    try {
      if (action === 'toggle') {
        if (v.paused) {
          var p = v.play();
          if (p && p['catch']) p['catch'](function () {});
        } else {
          v.pause();
        }
      } else if (action === 'back') {
        v.currentTime = Math.max(0, v.currentTime - 10);
      } else if (action === 'fwd') {
        var end = isFinite(v.duration) ? v.duration : v.currentTime + 10;
        v.currentTime = Math.min(end, v.currentTime + 10);
      }
    } catch (e) {
      return 'error';
    }
    return v.paused ? 'paused' : 'playing';
  };

  // ---------------------------------------------------------------- readable text

  var BLOCKS = 'p,li,h1,h2,h3,h4,h5,h6,blockquote,pre,td,th,dd,dt,figcaption,caption,div,section,article,tr';
  var JUNK = 'script,style,noscript,template,iframe,svg,canvas,form,button,input,select,textarea,nav,header,' +
    'footer,aside,[role=navigation],[role=banner],[role=contentinfo],[role=complementary],[aria-hidden=true],' +
    '[hidden],.mw-editsection,.navbox,.reflist,.references,.reference,#toc,.toc,.sidebar,.ad,.ads,.advert';

  function meta(names) {
    for (var i = 0; i < names.length; i++) {
      var m = document.querySelector('meta[property="' + names[i] + '"],meta[name="' + names[i] + '"]');
      var c = m && m.getAttribute('content');
      if (c && squash(c)) return squash(c);
    }
    return null;
  }

  /** Text of a disposable subtree, with line breaks between blocks so sentences don't run together. */
  function textOf(root) {
    var junk = root.querySelectorAll(JUNK);
    for (var i = junk.length - 1; i >= 0; i--) {
      if (junk[i].parentNode) junk[i].parentNode.removeChild(junk[i]);
    }
    var doc = root.ownerDocument || document;
    var blocks = root.querySelectorAll(BLOCKS);
    for (var j = 0; j < blocks.length; j++) blocks[j].appendChild(doc.createTextNode('\n'));
    var brs = root.querySelectorAll('br');
    for (var k = 0; k < brs.length; k++) {
      if (brs[k].parentNode) brs[k].parentNode.insertBefore(doc.createTextNode('\n'), brs[k]);
    }
    return root.textContent || '';
  }

  function headingsOf(root) {
    var out = [];
    var hs = root.querySelectorAll('h2,h3');
    for (var i = 0; i < hs.length && out.length < 16; i++) {
      var t = squash(hs[i].textContent);
      if (t) out.push(t.slice(0, 120));
    }
    return out;
  }

  /** An inert copy of an HTML string: no scripts run, nothing loads. */
  function inert(html) {
    var d = document.implementation.createHTMLDocument('');
    d.body.innerHTML = html || '';
    return d.body;
  }

  /** Best guess at the main content when Readability isn't available: the wordiest landmark. */
  function mainRoot() {
    var picks = document.querySelectorAll('main,[role=main],article,#content,#main-content,#main,.post,.entry-content');
    var best = null;
    var bestLen = 0;
    for (var i = 0; i < picks.length; i++) {
      var len = (picks[i].textContent || '').length;
      if (len > bestLen) {
        best = picks[i];
        bestLen = len;
      }
    }
    return best && bestLen > 500 ? best : document.body || document.documentElement;
  }

  /**
   * Readable text of the page as a JSON string: {url, title, text, headings, siteName, via}.
   * Uses Readability when the TV managed to inject it (old engines can't parse it), otherwise a careful
   * fallback that keeps the wordiest landmark and drops menus, footers and reference lists.
   */
  W.extract = function (maxChars) {
    var cap = maxChars || 30000;
    var out = {
      url: location.href,
      title: document.title || '',
      text: '',
      headings: [],
      siteName: meta(['og:site_name', 'application-name']),
      via: 'fallback'
    };
    var art = null;
    var Reader = window.__wanderReadability; // the TV injects Readability.js wrapped, so it never touches page globals
    if (typeof Reader === 'function') {
      try {
        art = new Reader(document.cloneNode(true), { charThreshold: 400 }).parse();
      } catch (e) {
        art = null;
      }
    }
    try {
      if (art && art.content && squash(art.textContent).length > 140) {
        out.via = 'readability';
        out.title = squash(art.title) || out.title;
        if (art.siteName) out.siteName = squash(art.siteName);
        var body = null;
        try {
          body = inert(art.content);
        } catch (e2) {
          body = null; // Trusted Types pages refuse innerHTML; Readability's plain text still works
        }
        if (body) {
          out.headings = headingsOf(body);
          out.text = textOf(body).slice(0, cap);
        } else {
          out.headings = headingsOf(document.body || document.documentElement);
          out.text = String(art.textContent).slice(0, cap);
        }
      } else {
        var root = mainRoot();
        if (root) {
          var copy = root.cloneNode(true);
          out.headings = headingsOf(copy);
          out.text = textOf(copy).slice(0, cap);
        }
      }
    } catch (e3) {
      out.via = 'innertext';
      out.text = String((document.body && document.body.innerText) || '').slice(0, cap);
    }
    return JSON.stringify(out);
  };

  try {
    Object.defineProperty(window, '__wander', { value: W, configurable: true, writable: true, enumerable: false });
  } catch (e) {
    window.__wander = W;
  }
  report();
})();
