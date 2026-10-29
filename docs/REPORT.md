# Technical report

## 1. Input-injection decision

| Option | What it can do on Android 7.1.2 without root | Verdict |
|---|---|---|
| **A. Accessibility service** | `performGlobalAction` (Back, Home, Recents, Notifications). Read the active window's node tree and act on nodes (focus, click, long-click, scroll, set text, paste). **`dispatchGesture` (API 24+)**: inject touch taps/swipes at screen coordinates. `TYPE_ACCESSIBILITY_OVERLAY` windows without `SYSTEM_ALERT_WINDOW`. It **cannot** inject `KeyEvent`s, move the system mouse pointer, or see apps that expose no nodes | **Chosen** for navigation, touchpad and text |
| AudioManager | Stream volume and mute; `dispatchMediaKeyEvent` delivers media keys to the active media session. No permission needed | **Chosen** for volume and media |
| B. ADB `input …` | Real key events (DPAD, ENTER, MENU), taps, text. Only the `shell` user may do this. An APK cannot call `input` itself; it would need adbd reachable over TCP plus an RSA-authorised ADB client inside the app | **Development only**: `tools/adb-remote.sh` runs on your PC with an allowlist. Nothing ADB-related is exposed on Wi-Fi by the app |
| C. Privileged injection | `Instrumentation.sendKeySync`, `InputManager.injectInputEvent` and `/dev/uinput` require `INJECT_EVENTS` (signature-level), a platform-signed APK, or root | **Not used.** It would need firmware signing keys or rooting, which you haven't approved and which this release doesn't need |

**Consequences:** the D-pad is *emulated*, using the app's own focus search where it works and
geometric "virtual focus" otherwise. The touchpad is *touch emulation* with a drawn cursor, not a
mouse. MENU is implemented as a long-press and labelled **Options** in the UI so it isn't advertised
as a real MENU key.

## 2. Architecture

```
app/src/main/java/com/m2smartremote/
├── RemoteApp.java              process singletons (prefs, pairing, sessions, authenticator, status)
├── MainActivity.java           projector status + QR/code pairing screen (opens the pairing window)
├── SettingsActivity.java       port, start-on-boot, highlight, paired phones, revoke, new code
├── RemoteService.java          foreground service: owns server, follows IP changes, WifiLock
├── BootReceiver.java           restarts the service after boot / app update
├── NetworkUtils.java           picks the private IPv4 of wlan*/eth* (never a public address)
├── Prefs.java, PrefsSessionStore.java, ServerStatus.java, QrCodes.java
├── core/                       pure Java, unit-tested, no Android imports
│   ├── Protocol.java, Crypto.java              key derivation + request signing
│   ├── PairingManager.java                     one-time 80-bit code, window, rotation
│   ├── SessionManager.java, Session.java, SessionStore.java, NonceCache.java
│   ├── RequestAuthenticator.java               HMAC + timestamp window + replay check
│   ├── OriginPolicy.java, RateLimiter.java     Host/Origin/peer checks, token buckets
│   ├── Command.java, PointerAction.java, Direction.java, TextInput.java   allowlists
│   ├── CommandRouter.java, InputBackend.java, AudioBackend.java, ActionResult.java, ApiError.java
│   └── DirectionalFinder.java, Box.java        port of Android FocusFinder scoring
├── server/
│   ├── RemoteServer.java       NanoHTTPD subclass: security pipeline + routes
│   ├── BoundedAsyncRunner.java max 12 concurrent connections
│   └── StaticAssets.java       fixed path→asset map (no filesystem access)
└── input/
    ├── RemoteAccessibilityService.java  the user-enabled service
    ├── AccessibilityController.java     InputBackend; serialises work on one worker thread
    ├── FocusNavigator.java              native focus → virtual focus → scroll; OK; text
    ├── PointerController.java           cursor + dispatchGesture tap/double/long/swipe
    ├── OverlayManager.java, OverlayView.java   highlight + cursor; window removed when idle
    └── AudioController.java             AudioBackend
app/src/main/assets/www/  index.html, styles.css, app.js, crypto.js, icon.svg, manifest.webmanifest
app/src/test/java/com/m2smartremote/core/  5 test classes, 43 tests
tools/  crypto-selftest.js, adb-remote.sh
```

The user's suggested layout was kept, with two changes. Everything security-relevant lives in
`core/` with no Android dependencies, so it runs as plain JVM unit tests. Android input handling is
split into navigator/pointer/overlay classes instead of one large controller.

## 3. API

All responses are JSON: `{"ok":true, "serverTime":<ms>, ...}` or
`{"ok":false, "serverTime":<ms>, "error":{"code":"…","message":"…"}}`.

| Method & path | Auth | Request body | Success fields |
|---|---|---|---|
| `GET /`, `/index.html`, `/styles.css`, `/app.js`, `/crypto.js`, `/icon.svg`, `/manifest.webmanifest` | none | — | static files |
| `GET /api/hello` | none | — | `app`, `version`, `pairingOpen` |
| `POST /api/pair` | pairing proof | `{"clientNonce":hex32, "proof":hex64, "name":"≤40 chars"}` | `sessionId`, `serverNonce`, `confirm`, `expiresAt`, `idleTimeoutSec` |
| `GET /api/status` | signed | — | `accessibility.{enabled,gestures}`, `session.{name,expiresAt}`, `version` |
| `POST /api/command` | signed | `{"cmd":"UP"}`, one of the commands below | optional `detail` |
| `POST /api/pointer` | signed | `{"action":"MOVE\|TAP\|DOUBLE_TAP\|LONG_PRESS\|SCROLL\|HIDE","dx":int,"dy":int}`, \|d\| ≤ 2000 | — |
| `POST /api/text` | signed | `{"text":"≤500 chars"}`; `""` clears the field | optional `detail` |
| `POST /api/session/revoke` | signed | `{}` | — |
| `POST /api/server/stop` | signed | `{"confirm":"STOP"}` | — |

Commands: `UP DOWN LEFT RIGHT OK BACK HOME MENU RECENTS NOTIFICATIONS VOLUME_UP VOLUME_DOWN MUTE
MEDIA_PLAY_PAUSE MEDIA_NEXT MEDIA_PREVIOUS MEDIA_STOP MEDIA_REWIND MEDIA_FAST_FORWARD` (exact,
case-sensitive).

Error codes: `UNAUTHORIZED` 401, `SESSION_EXPIRED` 401, `STALE_TIMESTAMP` 401, `REPLAYED_REQUEST`
401, `PAIRING_CLOSED` 403, `PAIRING_FAILED` 403, `FORBIDDEN_ORIGIN` 403, `FORBIDDEN_NETWORK` 403,
`NOT_FOUND` 404, `METHOD_NOT_ALLOWED` 405, `INVALID_INPUT` 400, `UNSUPPORTED_COMMAND` 400,
`UNSUPPORTED_MEDIA_TYPE` 415, `PAYLOAD_TOO_LARGE` 413, `ACTION_FAILED` 422, `RATE_LIMITED` 429,
`ACCESSIBILITY_DISABLED` 503, `SERVER_UNAVAILABLE` 503, `INTERNAL` 500. "Device offline" is
detected client-side (the request fails) and shown as the red **Offline** pill.

### Signed requests

```
X-M2R-Session:   sessionId (32 hex)
X-M2R-Time:      phone's estimate of projector time, ms (accepted within ±30 s)
X-M2R-Nonce:     16 random bytes, hex (each accepted once)
X-M2R-Signature: hex HMAC-SHA256(sessionKey,
                   "m2r-req-v1\n" + METHOD + "\n" + path + "\n" + time + "\n" + nonce + "\n" + hex(SHA-256(body)))
```

### Pairing

```
code       = 16 Crockford-base32 chars shown on the projector (80 bits)   — never transmitted
proof      = HMAC(code, "m2r-pair-v1|" + clientNonce)
sessionKey = HMAC(code, "m2r-session-v1|" + sessionId + "|" + clientNonce + "|" + serverNonce)
confirm    = HMAC(sessionKey, "m2r-confirm-v1|" + sessionId)              — phone verifies the projector
```

## 4. Security trade-offs

**What is protected**

- **No reusable secret ever crosses the network.** A passive sniffer sees the proof, nonces and
  signatures. Recovering the code from the proof means brute-forcing 80 bits, and the code is single
  use anyway.
- **Forgery and replay.** Every command is HMAC-signed with a per-session key, bound to method, path
  and body. Nonces are single use and timestamps must be within 30 s.
- **CSRF and other websites.** A foreign page can't read the key from this origin's storage, so it
  can't sign. POSTs also require a matching `Origin` and `application/json`, and no CORS headers are
  ever sent.
- **DNS rebinding.** The `Host` header must equal the bound `IP:port`.
- **Exposure.** The server binds only the private LAN IPv4 (never `0.0.0.0` or a public address),
  rejects non-private peers, and doesn't listen at all without a LAN. There is no port forwarding
  and no UPnP.
- **Abuse limits.** 40 req/s per IP (burst 80), pairing 5/min per IP and 20/min globally, the code
  rotates after 5 failures, 4 KB bodies, 500-char text, 12 connections, 15 s socket timeout.
- **No generic execution.** Fixed command enums, no shell, no file access, no package
  installation, no reflection.
- **Pairing needs physical presence.** The code is accepted only while its screen is shown on the
  projector.
- **Sessions** expire after 12 h idle or 30 days absolute, can be revoked individually or all at
  once, and are capped at 5 (oldest evicted).
- **Logs** contain no codes, keys or signatures. App data is excluded from backups.
- **The phone page** uses a strict CSP (no inline script/style, `connect-src 'self'`) and never
  uses `innerHTML`.

**What is NOT protected (plain HTTP)**

- **Confidentiality.** Anyone on the same Wi-Fi can see which buttons you press and **any text you
  send**. Don't send passwords.
- **An active attacker on the LAN** (ARP spoofing) can serve you a modified page *before or during
  pairing* and steal the code you type. QR pairing is slightly better (the code stays in the URL
  fragment), but a modified page could still read it. HMAC signing protects later commands
  in transit, not the page itself.
- **Session keys at rest** are stored unencrypted in the app's private storage on the projector, and
  in the phone browser's storage.
- **Denial of service** from a LAN device (connection flooding) is limited but not prevented.

**Why not HTTPS in v1?** It would need either a shared private key bundled in the APK (worthless) or
an on-device self-signed certificate. That certificate triggers full-page Chrome warnings on every
visit and can't be pinned from a plain web page, and generating X.509 certificates on Android 7
without BouncyCastle adds a lot of code. The signed-request design gives integrity and replay
protection without that friction. If you need confidentiality, see the extensions below.

## 5. Known limitations

- **No real key events.** D-pad emulation relies on the foreground app exposing accessibility
  nodes. Launchers and standard Android UIs work. **OpenGL/Unity games and some streaming apps
  expose nothing**, and TV apps that listen only for `KEYCODE_DPAD_*` may not respond to node focus.
- **No system mouse pointer.** The touchpad draws its own cursor and injects touch gestures. Apps
  that ignore touch won't respond to taps.
- **MENU** is a long-press, not a menu key. **Enter/IME actions** are not available (API 30+ only).
- **Text** works in standard `EditText` fields. WebViews and custom keyboards often ignore it. The
  clipboard fallback briefly puts the text on the projector's clipboard.
- **Media keys** go to the app holding the active media session. With no active session, Android 7
  may route them to the last media app or ignore them.
- **The focus highlight and cursor** hide after 6–8 s of inactivity. This removes the overlay window
  so it doesn't add a composition layer during video playback.
- **Firmware-specific:** `dispatchGesture` and the overlay are standard on API 24+, but vendor
  builds can break them. This hasn't been verified on the GD0302R31X2 yet; run the matrix in
  [TESTING.md](TESTING.md).

## 6. Future extension

- **Add a command:** add an enum value in `core/Command.java`, handle it in `CommandRouter`, add a
  button with `data-cmd="NAME"` in `index.html`, and add a test in `PolicyAndRoutingTest`.
- **Real D-pad keys via a custom IME:** an `InputMethodService` can `sendDownUpKeyEvent(DPAD_*)` and
  `performEditorAction(IME_ACTION_SEARCH)` to the focused app. This is legitimate and works in
  WebViews, but only while a text field has the input connection. It would fix text and Enter in
  most apps.
- **ADB development mode in-app:** connect to `127.0.0.1:5555` with an RSA-authorised ADB client and
  run only allowlisted `input keyevent N` commands. This gives real keys everywhere, but requires
  the user to enable network ADB, which exposes a full shell to the LAN. Off by default, behind a
  warning.
- **Encrypted transport:** generate a per-device self-signed certificate on first run, show its
  SHA-256 fingerprint on the projector, and serve HTTPS on a second port. Alternatively, build a
  small phone APK that pins the fingerprint from the QR code. That also unlocks WebCrypto and
  service workers (installable PWA).
- **WebSocket** for lower touchpad latency (`nanohttpd-websocket` 2.3.1 is compatible). Sign each
  message the same way.
