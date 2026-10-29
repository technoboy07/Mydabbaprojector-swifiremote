/*
 * M2 Smart Remote — phone UI.
 *
 * Security model (see docs/REPORT.md):
 *  - The pairing code is shown only on the projector screen and is never transmitted. The phone
 *    proves knowledge of it with an HMAC and both sides derive a session key from it.
 *  - Every later request is signed: HMAC(key, method, path, server-time, nonce, sha256(body)).
 *  - The session key lives in sessionStorage (cleared with the tab) unless the user opts into
 *    "Remember this phone", which uses localStorage.
 *
 * Input model: every button press is one discrete, idempotent command. The server holds no
 * "key down" state, so a lost pointerup can never leave a key stuck on the projector. Hold-to-repeat
 * is driven here and stops on release, cancel, tab switch, blur or any network error.
 */
(function () {
  'use strict';

  var C = window.M2Crypto;
  var STORE_KEY = 'm2r.session.v1';
  var SETTINGS_KEY = 'm2r.settings.v1';
  var REPEAT_DELAY_MS = 400;
  var REPEAT_INTERVAL_MS = 130;
  var MAX_HOLD_MS = 20000;
  var MOVE_MIN_INTERVAL_MS = 30;
  var SCROLL_INTERVAL_MS = 150;
  var STATUS_POLL_MS = 5000;
  var TAP_MAX_MS = 280;
  var TAP_SLOP_PX = 9;
  var LONG_PRESS_MS = 650;
  var DOUBLE_TAP_WAIT_MS = 260;

  var DEFAULT_SETTINGS = { sens: 1.2, scroll: 1.5, tap: true, double: false, haptics: true, mode: 'dpad' };

  var $ = function (sel) { return document.querySelector(sel); };
  var $$ = function (sel) { return Array.prototype.slice.call(document.querySelectorAll(sel)); };

  var session = null;      // { sid, key (hex) }
  var keyBytes = null;
  var timeOffset = 0;      // serverTime - Date.now()
  var settings = loadSettings();
  var pollTimer = null;
  var online = null;

  // ---- storage -------------------------------------------------------------------------

  function safeGet(storage, key) {
    try { return storage.getItem(key); } catch (e) { return null; }
  }
  function safeSet(storage, key, value) {
    try { storage.setItem(key, value); } catch (e) { /* private mode */ }
  }
  function safeRemove(storage, key) {
    try { storage.removeItem(key); } catch (e) { /* ignore */ }
  }

  function loadSession() {
    var raw = safeGet(sessionStorage, STORE_KEY) || safeGet(localStorage, STORE_KEY);
    if (!raw) return null;
    try {
      var s = JSON.parse(raw);
      if (/^[0-9a-f]{32}$/.test(s.sid) && /^[0-9a-f]{64}$/.test(s.key)) return s;
    } catch (e) { /* fall through */ }
    clearSession();
    return null;
  }

  function saveSession(s, remember) {
    clearSession();
    safeSet(remember ? localStorage : sessionStorage, STORE_KEY, JSON.stringify(s));
  }

  function clearSession() {
    safeRemove(sessionStorage, STORE_KEY);
    safeRemove(localStorage, STORE_KEY);
  }

  function loadSettings() {
    var s = {};
    try { s = JSON.parse(safeGet(localStorage, SETTINGS_KEY) || '{}') || {}; } catch (e) { s = {}; }
    var out = {};
    Object.keys(DEFAULT_SETTINGS).forEach(function (k) {
      out[k] = (k in s && typeof s[k] === typeof DEFAULT_SETTINGS[k]) ? s[k] : DEFAULT_SETTINGS[k];
    });
    return out;
  }

  function saveSettings() {
    safeSet(localStorage, SETTINGS_KEY, JSON.stringify(settings));
  }

  // ---- networking ------------------------------------------------------------------------

  function ApiError(code, message, status) {
    this.code = code;
    this.message = message;
    this.status = status;
  }

  function updateOffset(serverTime, t0, t1) {
    if (typeof serverTime !== 'number') return;
    if (t1 - t0 > 1500) return; // too noisy to be useful
    timeOffset = serverTime - (t0 + t1) / 2;
  }

  function fetchWithTimeout(url, opts, timeoutMs) {
    var ctrl = typeof AbortController !== 'undefined' ? new AbortController() : null;
    if (ctrl) opts.signal = ctrl.signal;
    var timer = setTimeout(function () { if (ctrl) ctrl.abort(); }, timeoutMs);
    return fetch(url, opts).then(function (r) {
      clearTimeout(timer);
      return r;
    }, function (e) {
      clearTimeout(timer);
      throw e;
    });
  }

  function parseResponse(res, t0) {
    var t1 = Date.now();
    return res.json().catch(function () { return null; }).then(function (json) {
      if (json) updateOffset(json.serverTime, t0, t1);
      setOnline(true);
      if (json && json.ok) return json;
      var err = json && json.error ? json.error : { code: 'SERVER_UNAVAILABLE', message: 'Unexpected response.' };
      throw new ApiError(err.code, err.message, res.status);
    });
  }

  function networkFailure() {
    setOnline(false);
    stopAllRepeats();
    throw new ApiError('OFFLINE', 'Cannot reach the projector. Check that both are on the same Wi-Fi.', 0);
  }

  function signedRequest(method, path, body, retried) {
    if (!session) return Promise.reject(new ApiError('UNAUTHORIZED', 'Not paired.', 401));
    var bodyStr = body ? JSON.stringify(body) : '';
    var time = String(Math.round(Date.now() + timeOffset));
    var nonce = C.randomHex(16);
    var canonical = 'm2r-req-v1\n' + method + '\n' + path + '\n' + time + '\n' + nonce + '\n' +
      C.toHex(C.sha256(C.utf8(bodyStr)));
    var headers = {
      'X-M2R-Session': session.sid,
      'X-M2R-Time': time,
      'X-M2R-Nonce': nonce,
      'X-M2R-Signature': C.toHex(C.hmac(keyBytes, C.utf8(canonical)))
    };
    var opts = { method: method, headers: headers, cache: 'no-store', credentials: 'omit' };
    if (method === 'POST') {
      headers['Content-Type'] = 'application/json';
      opts.body = bodyStr;
    }
    var t0 = Date.now();
    return fetchWithTimeout(path, opts, 5000).then(function (res) {
      return parseResponse(res, t0);
    }, networkFailure).catch(function (e) {
      if (e instanceof ApiError) {
        if (e.code === 'STALE_TIMESTAMP' && !retried) {
          return signedRequest(method, path, body, true); // offset was just corrected
        }
        if (e.code === 'SESSION_EXPIRED') {
          loggedOut('This phone is no longer paired. Pair again.');
        }
      }
      throw e;
    });
  }

  function plainRequest(method, path, body) {
    var opts = { method: method, cache: 'no-store', credentials: 'omit', headers: {} };
    if (body) {
      opts.headers['Content-Type'] = 'application/json';
      opts.body = JSON.stringify(body);
    }
    var t0 = Date.now();
    return fetchWithTimeout(path, opts, 6000).then(function (res) {
      return parseResponse(res, t0);
    }, networkFailure);
  }

  // ---- pairing ---------------------------------------------------------------------------

  var ALPHABET = '0123456789ABCDEFGHJKMNPQRSTVWXYZ';

  function normalizeCode(input) {
    var out = '';
    var s = String(input || '').toUpperCase();
    for (var i = 0; i < s.length; i++) {
      var c = s.charAt(i);
      if (c === '-' || c === ' ') continue;
      if (c === 'O') c = '0';
      else if (c === 'I' || c === 'L') c = '1';
      if (ALPHABET.indexOf(c) < 0) return null;
      out += c;
    }
    return out.length === 16 ? out : null;
  }

  function formatCode(raw) {
    var s = String(raw || '').toUpperCase().replace(/[^0-9A-Z]/g, '').slice(0, 16);
    return s.replace(/(.{4})(?=.)/g, '$1-');
  }

  function deviceName() {
    var m = /Android[^;)]*;\s*([^;)]+?)(?:\s+Build|\))/.exec(navigator.userAgent);
    var model = m && m[1] && m[1] !== 'K' ? m[1].trim() : '';
    return (model ? model : 'Phone') + ' (Chrome)';
  }

  function pair(rawCode, remember) {
    var code = normalizeCode(rawCode);
    if (!code) return Promise.reject(new ApiError('INVALID_INPUT', 'The code has 16 letters and digits.', 0));
    var codeKey = C.utf8(code);
    var clientNonce = C.randomHex(16);
    var proof = C.toHex(C.hmac(codeKey, C.utf8('m2r-pair-v1|' + clientNonce)));
    return plainRequest('POST', '/api/pair', { clientNonce: clientNonce, proof: proof, name: deviceName() })
      .then(function (r) {
        if (!/^[0-9a-f]{32}$/.test(r.sessionId) || !/^[0-9a-f]{32}$/.test(r.serverNonce)) {
          throw new ApiError('INVALID_INPUT', 'Unexpected pairing response.', 0);
        }
        var key = C.hmac(codeKey, C.utf8('m2r-session-v1|' + r.sessionId + '|' + clientNonce + '|' + r.serverNonce));
        var confirm = C.toHex(C.hmac(key, C.utf8('m2r-confirm-v1|' + r.sessionId)));
        if (confirm !== r.confirm) {
          throw new ApiError('UNAUTHORIZED', 'The projector failed verification. Do not use this network.', 0);
        }
        session = { sid: r.sessionId, key: C.toHex(key) };
        keyBytes = key;
        saveSession(session, remember);
      });
  }

  // ---- UI state ---------------------------------------------------------------------------

  function setPill(kind, text) {
    var pill = $('#status-pill');
    pill.className = 'pill' + (kind ? ' ' + kind : '');
    $('#status-text').textContent = text;
  }

  function setOnline(value) {
    if (online === value) return;
    online = value;
    if (!value) setPill('bad', 'Offline');
  }

  var toastTimer = null;
  var lastToast = { text: '', at: 0 };
  function toast(text) {
    var now = Date.now();
    if (text === lastToast.text && now - lastToast.at < 2500) return;
    lastToast = { text: text, at: now };
    var el = $('#toast');
    el.textContent = text;
    el.hidden = false;
    clearTimeout(toastTimer);
    toastTimer = setTimeout(function () { el.hidden = true; }, 2800);
  }

  function haptic(ms) {
    if (!settings.haptics || !navigator.vibrate) return;
    try { navigator.vibrate(ms || 8); } catch (e) { /* unsupported */ }
  }

  function showPairView(message) {
    $('#remote-view').hidden = true;
    $('#open-settings').hidden = true;
    $('#pair-view').hidden = false;
    closeSheets();
    $('#pair-error').textContent = message || '';
    setPill('', 'Not paired');
    stopPolling();
    plainRequest('GET', '/api/hello').then(function (r) {
      setPill(r.pairingOpen ? 'warn' : '', r.pairingOpen ? 'Ready to pair' : 'Open app on projector');
    }, function () { /* pill already shows offline */ });
  }

  function showRemoteView() {
    $('#pair-view').hidden = true;
    $('#remote-view').hidden = false;
    $('#open-settings').hidden = false;
    setMode(settings.mode);
    startPolling();
  }

  function loggedOut(message) {
    stopAllRepeats();
    session = null;
    keyBytes = null;
    clearSession();
    showPairView(message);
  }

  function refreshStatus() {
    if (!session || document.hidden) return;
    signedRequest('GET', '/api/status').then(function (r) {
      var a11y = r.accessibility || {};
      $('#a11y-banner').hidden = !!a11y.enabled;
      $('#gesture-note').hidden = !a11y.enabled || !!a11y.gestures;
      if (a11y.enabled) setPill('ok', 'Connected');
      else setPill('warn', 'Accessibility off');
    }, function () { /* pill updated by error handlers */ });
  }

  function startPolling() {
    stopPolling();
    refreshStatus();
    pollTimer = setInterval(refreshStatus, STATUS_POLL_MS);
  }

  function stopPolling() {
    if (pollTimer) clearInterval(pollTimer);
    pollTimer = null;
  }

  function reportError(e) {
    if (!(e instanceof ApiError)) return;
    switch (e.code) {
      case 'RATE_LIMITED':
      case 'SESSION_EXPIRED':
        return;
      case 'ACCESSIBILITY_DISABLED':
        $('#a11y-banner').hidden = false;
        setPill('warn', 'Accessibility off');
        toast('Turn on the M2 Smart Remote accessibility service on the projector.');
        return;
      default:
        toast(e.message || e.code);
    }
  }

  // ---- commands & hold-to-repeat -----------------------------------------------------------

  var inflight = 0;

  function sendCommand(cmd) {
    inflight++;
    return signedRequest('POST', '/api/command', { cmd: cmd }).then(function (r) {
      if (r.detail && /^(VOLUME|MUTE)/.test(cmd)) toast(r.detail);
      return r;
    }).catch(function (e) {
      reportError(e);
      stopAllRepeats();
    }).then(function () { inflight--; });
  }

  var repeats = new Map(); // element -> { timer, interval, started }

  function startPress(el) {
    if (repeats.has(el)) return;
    var cmd = el.getAttribute('data-cmd');
    var state = { timer: null, interval: null, started: Date.now() };
    repeats.set(el, state);
    el.classList.add('pressed');
    haptic();
    sendCommand(cmd);
    if (el.hasAttribute('data-repeat')) {
      state.timer = setTimeout(function () {
        state.interval = setInterval(function () {
          if (Date.now() - state.started > MAX_HOLD_MS) { endPress(el); return; }
          if (inflight >= 2) return; // never queue up a backlog of presses
          sendCommand(cmd);
        }, REPEAT_INTERVAL_MS);
      }, REPEAT_DELAY_MS);
    }
  }

  function endPress(el) {
    var state = repeats.get(el);
    el.classList.remove('pressed');
    if (!state) return;
    clearTimeout(state.timer);
    clearInterval(state.interval);
    repeats.delete(el);
  }

  function stopAllRepeats() {
    repeats.forEach(function (_, el) { endPress(el); });
  }

  function bindCommandButtons() {
    $$('[data-cmd]').forEach(function (el) {
      el.addEventListener('pointerdown', function (e) {
        if (e.pointerType === 'mouse' && e.button !== 0) return;
        e.preventDefault();
        try { el.setPointerCapture(e.pointerId); } catch (err) { /* ignore */ }
        startPress(el);
      });
      ['pointerup', 'pointercancel', 'lostpointercapture'].forEach(function (t) {
        el.addEventListener(t, function () { endPress(el); });
      });
      el.addEventListener('contextmenu', function (e) { e.preventDefault(); });
      // Keyboard / switch-access activation (click with no pointer).
      el.addEventListener('click', function (e) {
        if (e.detail === 0) sendCommand(el.getAttribute('data-cmd'));
      });
    });
    document.addEventListener('visibilitychange', function () {
      if (document.hidden) {
        stopAllRepeats();
        stopPolling();
      } else if (session) {
        startPolling();
      }
    });
    window.addEventListener('blur', stopAllRepeats);
    window.addEventListener('pagehide', stopAllRepeats);
    window.addEventListener('offline', function () { stopAllRepeats(); setOnline(false); });
  }

  // ---- touchpad --------------------------------------------------------------------------

  function sendPointer(action, dx, dy) {
    // Taps, long-presses and scrolls act at the cursor, so let queued movement land first.
    var ready = action === 'MOVE' ? Promise.resolve() : waitForMoves();
    return ready.then(function () {
      return signedRequest('POST', '/api/pointer', { action: action, dx: dx || 0, dy: dy || 0 });
    }).catch(function (e) { reportError(e); });
  }

  function movesPending() {
    return move.busy || move.scheduled || Math.trunc(move.dx) !== 0 || Math.trunc(move.dy) !== 0;
  }

  function waitForMoves() {
    return new Promise(function (resolve) {
      var deadline = Date.now() + 600;
      (function check() {
        if (!movesPending() || Date.now() > deadline) resolve();
        else setTimeout(check, 15);
      })();
    });
  }

  var move = { dx: 0, dy: 0, busy: false, last: 0, scheduled: false };
  function queueMove(dx, dy) {
    move.dx += dx;
    move.dy += dy;
    flushMove();
  }
  function flushMove() {
    if (move.busy || move.scheduled) return;
    var wait = MOVE_MIN_INTERVAL_MS - (Date.now() - move.last);
    if (wait > 0) {
      move.scheduled = true;
      setTimeout(function () { move.scheduled = false; flushMove(); }, wait);
      return;
    }
    var dx = Math.trunc(move.dx), dy = Math.trunc(move.dy);
    if (dx === 0 && dy === 0) return;
    dx = Math.max(-2000, Math.min(2000, dx));
    dy = Math.max(-2000, Math.min(2000, dy));
    move.dx -= dx;
    move.dy -= dy;
    move.busy = true;
    move.last = Date.now();
    sendPointer('MOVE', dx, dy).then(function () {
      move.busy = false;
      flushMove(); // latest-wins: send whatever accumulated meanwhile
    });
  }

  var scroll = { dx: 0, dy: 0, timer: null };
  function queueScroll(dx, dy) {
    scroll.dx += dx;
    scroll.dy += dy;
    if (!scroll.timer) scroll.timer = setTimeout(flushScroll, SCROLL_INTERVAL_MS);
  }
  function flushScroll() {
    scroll.timer = null;
    var dx = Math.round(scroll.dx), dy = Math.round(scroll.dy);
    if (Math.abs(dx) < 10 && Math.abs(dy) < 10) return;
    scroll.dx = 0;
    scroll.dy = 0;
    sendPointer('SCROLL', Math.max(-1500, Math.min(1500, dx)), Math.max(-1500, Math.min(1500, dy)));
  }

  var pendingTap = null;
  function handleTap() {
    haptic(6);
    if (!settings.double) { sendPointer('TAP'); return; }
    if (pendingTap) {
      clearTimeout(pendingTap);
      pendingTap = null;
      sendPointer('DOUBLE_TAP');
      return;
    }
    pendingTap = setTimeout(function () { pendingTap = null; sendPointer('TAP'); }, DOUBLE_TAP_WAIT_MS);
  }

  function bindTouchpad() {
    var pad = $('#touchpad');
    var pointers = new Map();
    var g = null; // current gesture

    function reset() {
      if (g && g.longTimer) clearTimeout(g.longTimer);
      g = null;
      pointers.clear();
      pad.classList.remove('active');
    }

    pad.addEventListener('pointerdown', function (e) {
      e.preventDefault();
      try { pad.setPointerCapture(e.pointerId); } catch (err) { /* ignore */ }
      pointers.set(e.pointerId, { x: e.clientX, y: e.clientY });
      pad.classList.add('active');
      if (pointers.size === 1) {
        g = { mode: 'move', start: Date.now(), moved: 0, longFired: false, longTimer: null };
        g.longTimer = setTimeout(function () {
          if (g && g.mode === 'move' && g.moved < TAP_SLOP_PX) {
            g.longFired = true;
            haptic(20);
            sendPointer('LONG_PRESS');
          }
        }, LONG_PRESS_MS);
      } else if (g) {
        g.mode = 'scroll';
        clearTimeout(g.longTimer);
      }
    });

    pad.addEventListener('pointermove', function (e) {
      var p = pointers.get(e.pointerId);
      if (!p || !g) return;
      e.preventDefault();
      var dx = e.clientX - p.x, dy = e.clientY - p.y;
      p.x = e.clientX;
      p.y = e.clientY;
      if (g.mode === 'move' && pointers.size === 1) {
        g.moved += Math.abs(dx) + Math.abs(dy);
        if (g.moved >= TAP_SLOP_PX) clearTimeout(g.longTimer);
        var speed = Math.sqrt(dx * dx + dy * dy);
        var f = settings.sens * (1 + Math.min(speed / 14, 1.6)); // gentle acceleration
        queueMove(dx * f, dy * f);
      } else if (g.mode === 'scroll') {
        var n = pointers.size || 1;
        queueScroll(dx * settings.scroll * 2 / n, dy * settings.scroll * 2 / n);
      }
    });

    function up(e) {
      if (!pointers.has(e.pointerId)) return;
      pointers.delete(e.pointerId);
      if (!g) return;
      if (pointers.size === 0) {
        var wasTap = g.mode === 'move' && !g.longFired && g.moved < TAP_SLOP_PX &&
          Date.now() - g.start < TAP_MAX_MS && e.type === 'pointerup';
        if (g.mode === 'scroll') flushScroll();
        if (wasTap && settings.tap) handleTap();
        reset();
      }
    }
    pad.addEventListener('pointerup', up);
    pad.addEventListener('pointercancel', up);
    pad.addEventListener('lostpointercapture', up);
    pad.addEventListener('contextmenu', function (e) { e.preventDefault(); });

    // One-finger scroll strip for one-handed use.
    var strip = $('#scroll-strip');
    var stripPos = null;
    strip.addEventListener('pointerdown', function (e) {
      e.preventDefault();
      try { strip.setPointerCapture(e.pointerId); } catch (err) { /* ignore */ }
      stripPos = { id: e.pointerId, y: e.clientY };
      strip.classList.add('active');
    });
    strip.addEventListener('pointermove', function (e) {
      if (!stripPos || stripPos.id !== e.pointerId) return;
      e.preventDefault();
      queueScroll(0, (e.clientY - stripPos.y) * settings.scroll * 2);
      stripPos.y = e.clientY;
    });
    ['pointerup', 'pointercancel', 'lostpointercapture'].forEach(function (t) {
      strip.addEventListener(t, function () {
        if (stripPos) flushScroll();
        stripPos = null;
        strip.classList.remove('active');
      });
    });

    $('#btn-click').addEventListener('click', function () { haptic(); sendPointer('TAP'); });
  }

  // ---- mode tabs, sheets, settings ---------------------------------------------------------

  function setMode(mode) {
    settings.mode = mode === 'touch' ? 'touch' : 'dpad';
    saveSettings();
    var touch = settings.mode === 'touch';
    $('#tab-dpad').setAttribute('aria-selected', String(!touch));
    $('#tab-touch').setAttribute('aria-selected', String(touch));
    $('#panel-dpad').hidden = touch;
    $('#panel-touch').hidden = !touch;
    stopAllRepeats();
    if (!touch && session) sendPointer('HIDE');
  }

  function closeSheets() {
    $('#keyboard-sheet').hidden = true;
    $('#settings-sheet').hidden = true;
  }

  var liveTimer = null;
  function sendText(text) {
    return signedRequest('POST', '/api/text', { text: text }).then(function (r) {
      if (r.detail) toast(r.detail);
    }, reportError);
  }

  function bindSheets() {
    $('#open-keyboard').addEventListener('click', function () {
      $('#settings-sheet').hidden = true;
      $('#keyboard-sheet').hidden = false;
      $('#text-input').focus();
    });
    $('#close-keyboard').addEventListener('click', closeSheets);
    $('#text-form').addEventListener('submit', function (e) {
      e.preventDefault();
      clearTimeout(liveTimer);
      sendText($('#text-input').value).then(function () { toast('Text sent'); });
    });
    $('#text-input').addEventListener('input', function () {
      if (!$('#live-typing').checked) return;
      clearTimeout(liveTimer);
      liveTimer = setTimeout(function () { sendText($('#text-input').value); }, 450);
    });
    $('#clear-text').addEventListener('click', function () {
      $('#text-input').value = '';
      sendText('');
    });

    $('#open-settings').addEventListener('click', function () {
      $('#keyboard-sheet').hidden = true;
      $('#settings-sheet').hidden = !$('#settings-sheet').hidden;
    });
    $('#close-settings').addEventListener('click', closeSheets);

    var sens = $('#sens'), sc = $('#scroll-speed');
    sens.value = settings.sens;
    sc.value = settings.scroll;
    $('#sens-out').textContent = Number(settings.sens).toFixed(1) + '×';
    $('#scroll-out').textContent = Number(settings.scroll).toFixed(1) + '×';
    sens.addEventListener('input', function () {
      settings.sens = Number(sens.value);
      $('#sens-out').textContent = settings.sens.toFixed(1) + '×';
      saveSettings();
    });
    sc.addEventListener('input', function () {
      settings.scroll = Number(sc.value);
      $('#scroll-out').textContent = settings.scroll.toFixed(1) + '×';
      saveSettings();
    });
    [['#opt-tap', 'tap'], ['#opt-double', 'double'], ['#opt-haptics', 'haptics']].forEach(function (pair) {
      var el = $(pair[0]);
      el.checked = settings[pair[1]];
      el.addEventListener('change', function () { settings[pair[1]] = el.checked; saveSettings(); });
    });

    $('#btn-disconnect').addEventListener('click', function () {
      signedRequest('POST', '/api/session/revoke', {}).catch(function () { /* revoke locally anyway */ })
        .then(function () { loggedOut('Disconnected. Pair again to use the remote.'); });
    });
    $('#btn-stop').addEventListener('click', function () {
      if (!window.confirm('Stop the remote server on the projector? You will need the projector itself to start it again.')) return;
      signedRequest('POST', '/api/server/stop', { confirm: 'STOP' }).then(function () {
        closeSheets();
        stopPolling();
        setPill('bad', 'Server stopped');
        toast('Remote server stopped on the projector.');
      }, reportError);
    });

    $('#tab-dpad').addEventListener('click', function () { setMode('dpad'); });
    $('#tab-touch').addEventListener('click', function () { setMode('touch'); });
    $('#status-pill').addEventListener('click', function () { if (session) refreshStatus(); });
  }

  function bindPairing() {
    var input = $('#pair-code');
    input.addEventListener('input', function () {
      var formatted = formatCode(input.value);
      if (formatted !== input.value) input.value = formatted;
    });
    $('#pair-form').addEventListener('submit', function (e) {
      e.preventDefault();
      doPair(input.value, $('#pair-remember').checked);
    });
  }

  function doPair(code, remember) {
    var btn = $('#pair-submit');
    btn.disabled = true;
    $('#pair-error').textContent = '';
    pair(code, remember).then(function () {
      haptic(15);
      $('#pair-code').value = '';
      showRemoteView();
      toast('Paired. This phone now controls the projector.');
    }, function (e) {
      $('#pair-error').textContent = e.message || 'Pairing failed.';
    }).then(function () { btn.disabled = false; });
  }

  // ---- boot ------------------------------------------------------------------------------

  function pairFromHash() {
    var hash = location.hash;
    if (hash.indexOf('#pair=') !== 0) return false;
    var code = decodeURIComponent(hash.slice(6));
    history.replaceState(null, '', location.pathname);
    stopAllRepeats();
    showPairView('');
    $('#pair-code').value = formatCode(code);
    doPair(code, false);
    return true;
  }

  function init() {
    bindCommandButtons();
    bindTouchpad();
    bindSheets();
    bindPairing();
    document.addEventListener('gesturestart', function (e) { e.preventDefault(); });
    document.addEventListener('dblclick', function (e) { e.preventDefault(); });

    // QR codes carry the one-time code in the URL fragment, which browsers never send to the
    // server. Remove it from the address bar and history immediately.
    window.addEventListener('hashchange', pairFromHash);
    if (pairFromHash()) return;

    session = loadSession();
    if (session) {
      keyBytes = C.fromHex(session.key);
      showRemoteView();
    } else {
      showPairView('');
    }
  }

  init();
})();
