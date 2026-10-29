package com.m2smartremote.server;

import android.content.Context;
import android.util.Log;

import com.m2smartremote.BuildConfig;
import com.m2smartremote.core.ActionResult;
import com.m2smartremote.core.ApiError;
import com.m2smartremote.core.Clock;
import com.m2smartremote.core.Command;
import com.m2smartremote.core.CommandRouter;
import com.m2smartremote.core.Crypto;
import com.m2smartremote.core.InputBackend;
import com.m2smartremote.core.OriginPolicy;
import com.m2smartremote.core.PairingManager;
import com.m2smartremote.core.PointerAction;
import com.m2smartremote.core.Protocol;
import com.m2smartremote.core.RateLimiter;
import com.m2smartremote.core.RequestAuthenticator;
import com.m2smartremote.core.Session;
import com.m2smartremote.core.SessionManager;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Map;

import fi.iki.elonen.NanoHTTPD;

/**
 * Embedded HTTP server bound to the projector's LAN address only.
 *
 * <p>Every request passes, in order: peer-address check, rate limit, Host check (anti DNS
 * rebinding), then per-route checks: Sec-Fetch-Site / Origin / Content-Type / body size, and
 * finally HMAC request authentication for everything except the UI files, /api/hello and
 * /api/pair. Only fixed, allowlisted actions can be reached.
 */
public final class RemoteServer extends NanoHTTPD {
    private static final String TAG = "M2Remote";
    private static final int MAX_CONNECTIONS = 12;
    private static final int SOCKET_READ_TIMEOUT_MS = 15_000;
    private static final int MAX_BODY_BYTES = 4096;
    private static final String MIME_JSON = "application/json; charset=utf-8";

    /** Called (on an HTTP thread) when a paired phone asks to stop the server. */
    public interface StopHandler {
        void onStopRequested();
    }

    private final String boundIp;
    private final int port;
    private final OriginPolicy policy;
    private final StaticAssets assets;
    private final PairingManager pairing;
    private final SessionManager sessions;
    private final RequestAuthenticator authenticator;
    private final CommandRouter router;
    private final InputBackend input;
    private final StopHandler stopHandler;

    // ~40 req/s sustained per phone covers 30 Hz touchpad movement plus button presses.
    private final RateLimiter requestLimiter = new RateLimiter(Clock.SYSTEM, 80, 40);
    private final RateLimiter pairPerIpLimiter = new RateLimiter(Clock.SYSTEM, 5, 5 / 60.0);
    private final RateLimiter pairGlobalLimiter = new RateLimiter(Clock.SYSTEM, 20, 20 / 60.0);

    private volatile boolean stopping;

    public RemoteServer(Context context, String boundIp, int port, PairingManager pairing,
                        SessionManager sessions, RequestAuthenticator authenticator,
                        CommandRouter router, InputBackend input, StopHandler stopHandler) {
        super(boundIp, port);
        this.boundIp = boundIp;
        this.port = port;
        this.policy = new OriginPolicy(boundIp, port);
        this.assets = new StaticAssets(context.getAssets());
        this.pairing = pairing;
        this.sessions = sessions;
        this.authenticator = authenticator;
        this.router = router;
        this.input = input;
        this.stopHandler = stopHandler;
        setAsyncRunner(new BoundedAsyncRunner(MAX_CONNECTIONS));
    }

    public void startServer() throws IOException {
        start(SOCKET_READ_TIMEOUT_MS, true);
    }

    public void stopServer() {
        stopping = true;
        stop();
    }

    public String boundIp() {
        return boundIp;
    }

    public int port() {
        return port;
    }

    @Override
    protected boolean useGzipWhenAccepted(Response r) {
        return false; // payloads are tiny; save CPU on the RK3128
    }

    @Override
    public Response serve(IHTTPSession session) {
        Response response;
        try {
            response = handle(session);
        } catch (BadRequest e) {
            response = error(e.error, e.detail);
            response.closeConnection(true);
        } catch (Exception e) {
            Log.e(TAG, "Request failed: " + e.getClass().getSimpleName());
            response = error(ApiError.INTERNAL, null);
            response.closeConnection(true);
        }
        addSecurityHeaders(response);
        return response;
    }

    private Response handle(IHTTPSession session) throws BadRequest, IOException {
        String remoteIp = session.getRemoteIpAddress();
        if (!OriginPolicy.isPrivateIpv4(remoteIp)) {
            throw new BadRequest(ApiError.FORBIDDEN_NETWORK, null);
        }
        if (stopping) {
            throw new BadRequest(ApiError.SERVER_UNAVAILABLE, null);
        }
        if (!requestLimiter.tryAcquire(remoteIp)) {
            throw new BadRequest(ApiError.RATE_LIMITED, null);
        }
        Map<String, String> headers = session.getHeaders();
        if (!policy.hostAllowed(headers.get("host"))) {
            throw new BadRequest(ApiError.FORBIDDEN_ORIGIN, "Open the address shown on the projector.");
        }

        String path = session.getUri();
        Method method = session.getMethod();

        if (StaticAssets.isAsset(path)) {
            if (method != Method.GET && method != Method.HEAD) {
                throw new BadRequest(ApiError.METHOD_NOT_ALLOWED, null);
            }
            return serveAsset(path);
        }
        if (!path.startsWith("/api/")) {
            throw new BadRequest(ApiError.NOT_FOUND, null);
        }
        if (!OriginPolicy.fetchSiteAllowed(headers.get("sec-fetch-site"))) {
            throw new BadRequest(ApiError.FORBIDDEN_ORIGIN, null);
        }

        if (method == Method.GET) {
            if (!policy.originAllowed(headers.get("origin"), false)) {
                throw new BadRequest(ApiError.FORBIDDEN_ORIGIN, null);
            }
            if ("/api/hello".equals(path)) {
                return hello();
            }
            if ("/api/status".equals(path)) {
                Session s = authenticate("GET", path, headers, new byte[0]);
                return status(s);
            }
            if (isPostRoute(path)) {
                throw new BadRequest(ApiError.METHOD_NOT_ALLOWED, null);
            }
            throw new BadRequest(ApiError.NOT_FOUND, null);
        }

        if (method != Method.POST) {
            throw new BadRequest(ApiError.METHOD_NOT_ALLOWED, null);
        }
        if (!isPostRoute(path)) {
            throw new BadRequest(ApiError.NOT_FOUND, null);
        }
        // A browser always sends Origin on POST. Requiring it plus a JSON content type means a
        // cross-site form or no-cors fetch can never reach the handlers below.
        if (!policy.originAllowed(headers.get("origin"), true)) {
            throw new BadRequest(ApiError.FORBIDDEN_ORIGIN, null);
        }
        String contentType = headers.get("content-type");
        if (contentType == null || !contentType.toLowerCase().startsWith("application/json")) {
            throw new BadRequest(ApiError.UNSUPPORTED_MEDIA_TYPE, null);
        }
        byte[] body = readBody(session, headers);

        if ("/api/pair".equals(path)) {
            return pair(remoteIp, body);
        }
        Session s = authenticate("POST", path, headers, body);
        JSONObject json = parseJson(body);
        if ("/api/command".equals(path)) {
            return command(json);
        }
        if ("/api/pointer".equals(path)) {
            return pointer(json);
        }
        if ("/api/text".equals(path)) {
            return text(json);
        }
        if ("/api/session/revoke".equals(path)) {
            sessions.revoke(s.id);
            Log.i(TAG, "Phone revoked its own session");
            return ok(new JSONObject());
        }
        if ("/api/server/stop".equals(path)) {
            return stopServerRequest(json);
        }
        throw new BadRequest(ApiError.NOT_FOUND, null);
    }

    private static boolean isPostRoute(String path) {
        return "/api/pair".equals(path) || "/api/command".equals(path)
                || "/api/pointer".equals(path) || "/api/text".equals(path)
                || "/api/session/revoke".equals(path) || "/api/server/stop".equals(path);
    }

    // ---- handlers -------------------------------------------------------------------------

    private Response hello() throws BadRequest {
        JSONObject o = new JSONObject();
        put(o, "app", "M2 Smart Remote");
        put(o, "version", BuildConfig.VERSION_NAME);
        put(o, "pairingOpen", pairing.isWindowOpen());
        return ok(o);
    }

    private Response pair(String remoteIp, byte[] body) throws BadRequest {
        if (!pairPerIpLimiter.tryAcquire(remoteIp) || !pairGlobalLimiter.tryAcquire("all")) {
            throw new BadRequest(ApiError.RATE_LIMITED, "Too many pairing attempts. Wait a minute.");
        }
        JSONObject json = parseJson(body);
        String clientNonce = json.optString("clientNonce", null);
        String proof = json.optString("proof", null);
        String name = sanitizeName(json.optString("name", "Phone"));

        PairingManager.Result result = pairing.verifyAndConsume(clientNonce, proof);
        if (!result.ok()) {
            Log.i(TAG, "Pairing attempt rejected: " + result.error.name());
            throw new BadRequest(result.error, null);
        }
        String sessionId = Crypto.toHex(Crypto.randomBytes(16));
        String serverNonce = Crypto.toHex(Crypto.randomBytes(16));
        byte[] key = Protocol.sessionKey(result.code, sessionId, clientNonce, serverNonce);
        Session s = sessions.create(sessionId, key, name, remoteIp);
        Log.i(TAG, "Paired a new phone from " + remoteIp);

        JSONObject o = new JSONObject();
        put(o, "sessionId", sessionId);
        put(o, "serverNonce", serverNonce);
        put(o, "confirm", Crypto.toHex(Protocol.confirm(key, sessionId)));
        put(o, "expiresAt", sessions.expiresAt(s));
        put(o, "idleTimeoutSec", SessionManager.IDLE_TIMEOUT_MS / 1000);
        return ok(o);
    }

    private Response status(Session s) throws BadRequest {
        JSONObject o = new JSONObject();
        JSONObject a11y = new JSONObject();
        put(a11y, "enabled", input.isAvailable());
        put(a11y, "gestures", input.isAvailable() && input.canDispatchGestures());
        put(o, "accessibility", a11y);
        JSONObject sess = new JSONObject();
        put(sess, "name", s.name);
        put(sess, "expiresAt", sessions.expiresAt(s));
        put(o, "session", sess);
        put(o, "version", BuildConfig.VERSION_NAME);
        return ok(o);
    }

    private Response command(JSONObject json) throws BadRequest {
        Command c = Command.parse(json.optString("cmd", null));
        if (c == null) {
            throw new BadRequest(ApiError.UNSUPPORTED_COMMAND, null);
        }
        return result(router.command(c));
    }

    private Response pointer(JSONObject json) throws BadRequest {
        PointerAction action = PointerAction.parse(json.optString("action", null));
        if (action == null) {
            throw new BadRequest(ApiError.UNSUPPORTED_COMMAND, null);
        }
        int dx = intField(json, "dx");
        int dy = intField(json, "dy");
        return result(router.pointer(action, dx, dy));
    }

    private Response text(JSONObject json) throws BadRequest {
        Object text = json.opt("text");
        if (!(text instanceof String)) {
            throw new BadRequest(ApiError.INVALID_INPUT, "text must be a string.");
        }
        return result(router.text((String) text));
    }

    private Response stopServerRequest(JSONObject json) throws BadRequest {
        if (!"STOP".equals(json.optString("confirm", null))) {
            throw new BadRequest(ApiError.INVALID_INPUT, "Send {\"confirm\":\"STOP\"} to stop the server.");
        }
        Log.i(TAG, "Stop requested by a paired phone");
        stopping = true;
        stopHandler.onStopRequested();
        return ok(new JSONObject());
    }

    // ---- helpers --------------------------------------------------------------------------

    private Session authenticate(String method, String path, Map<String, String> headers,
                                 byte[] body) throws BadRequest {
        RequestAuthenticator.Result r = authenticator.verify(method, path, headers, body);
        if (!r.ok()) {
            throw new BadRequest(r.error, null);
        }
        return r.session;
    }

    private static byte[] readBody(IHTTPSession session, Map<String, String> headers)
            throws BadRequest, IOException {
        if (headers.containsKey("transfer-encoding")) {
            throw new BadRequest(ApiError.INVALID_INPUT, "Chunked bodies are not accepted.");
        }
        String lengthHeader = headers.get("content-length");
        int length;
        try {
            length = lengthHeader == null ? 0 : Integer.parseInt(lengthHeader.trim());
        } catch (NumberFormatException e) {
            throw new BadRequest(ApiError.INVALID_INPUT, null);
        }
        if (length < 0) {
            throw new BadRequest(ApiError.INVALID_INPUT, null);
        }
        if (length > MAX_BODY_BYTES) {
            throw new BadRequest(ApiError.PAYLOAD_TOO_LARGE, null);
        }
        byte[] body = new byte[length];
        InputStream in = session.getInputStream();
        int read = 0;
        while (read < length) {
            int n = in.read(body, read, length - read);
            if (n < 0) {
                throw new BadRequest(ApiError.INVALID_INPUT, "Truncated body.");
            }
            read += n;
        }
        return body;
    }

    private static JSONObject parseJson(byte[] body) throws BadRequest {
        try {
            String s = new String(body, Crypto.UTF8).trim();
            if (!s.startsWith("{")) {
                throw new BadRequest(ApiError.INVALID_INPUT, "Body must be a JSON object.");
            }
            return new JSONObject(s);
        } catch (JSONException e) {
            throw new BadRequest(ApiError.INVALID_INPUT, "Malformed JSON.");
        }
    }

    private static int intField(JSONObject json, String name) throws BadRequest {
        Object v = json.opt(name);
        if (v == null) {
            return 0;
        }
        if (!(v instanceof Number)) {
            throw new BadRequest(ApiError.INVALID_INPUT, name + " must be a number.");
        }
        double d = ((Number) v).doubleValue();
        if (Double.isNaN(d) || Math.abs(d) > CommandRouter.MAX_POINTER_DELTA) {
            throw new BadRequest(ApiError.INVALID_INPUT, name + " is out of range.");
        }
        return (int) Math.round(d);
    }

    private static String sanitizeName(String name) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < name.length() && sb.length() < 40; i++) {
            char c = name.charAt(i);
            if (!Character.isISOControl(c)) {
                sb.append(c);
            }
        }
        String s = sb.toString().trim();
        return s.isEmpty() ? "Phone" : s;
    }

    private Response serveAsset(String path) throws IOException, BadRequest {
        StaticAssets.Asset asset = assets.get(path);
        if (asset == null) {
            throw new BadRequest(ApiError.NOT_FOUND, null);
        }
        Response r = newFixedLengthResponse(Response.Status.OK, asset.mime,
                new ByteArrayInputStream(asset.bytes), asset.bytes.length);
        // Revalidate on each load so an APK update is picked up immediately.
        r.addHeader("Cache-Control", "no-cache");
        return r;
    }

    private Response result(ActionResult r) {
        if (r.isOk()) {
            JSONObject o = new JSONObject();
            if (r.detail != null) {
                try {
                    o.put("detail", r.detail);
                } catch (JSONException ignored) {
                    // cannot happen for a String value
                }
            }
            return ok(o);
        }
        return error(r.error, r.detail);
    }

    private static Response ok(JSONObject o) {
        try {
            o.put("ok", true);
            o.put("serverTime", System.currentTimeMillis());
        } catch (JSONException ignored) {
            // cannot happen for primitive values
        }
        Response r = newFixedLengthResponse(Response.Status.OK, MIME_JSON, o.toString());
        r.addHeader("Cache-Control", "no-store");
        return r;
    }

    private static Response error(ApiError error, String detail) {
        JSONObject o = new JSONObject();
        try {
            JSONObject e = new JSONObject();
            e.put("code", error.name());
            e.put("message", detail != null ? detail : error.defaultMessage);
            o.put("ok", false);
            o.put("error", e);
            o.put("serverTime", System.currentTimeMillis());
        } catch (JSONException ignored) {
            // cannot happen for primitive values
        }
        Response r = newFixedLengthResponse(statusFor(error.httpStatus), MIME_JSON, o.toString());
        r.addHeader("Cache-Control", "no-store");
        return r;
    }

    private static Response.IStatus statusFor(final int code) {
        for (Response.Status s : Response.Status.values()) {
            if (s.getRequestStatus() == code) {
                return s;
            }
        }
        // NanoHTTPD 2.3.1 has no 422/429 constants.
        return new Response.IStatus() {
            @Override
            public String getDescription() {
                return code + (code == 429 ? " Too Many Requests" : code == 422 ? " Unprocessable Entity" : " Error");
            }

            @Override
            public int getRequestStatus() {
                return code;
            }
        };
    }

    private static void addSecurityHeaders(Response r) {
        r.addHeader("Content-Security-Policy", "default-src 'none'; script-src 'self'; "
                + "style-src 'self'; img-src 'self' data:; connect-src 'self'; manifest-src 'self'; "
                + "frame-ancestors 'none'; base-uri 'none'; form-action 'none'");
        r.addHeader("X-Content-Type-Options", "nosniff");
        r.addHeader("X-Frame-Options", "DENY");
        r.addHeader("Referrer-Policy", "no-referrer");
        r.addHeader("Cross-Origin-Resource-Policy", "same-origin");
        // Deliberately no Access-Control-Allow-* headers: cross-origin reads are never allowed.
    }

    private static void put(JSONObject o, String k, Object v) throws BadRequest {
        try {
            o.put(k, v);
        } catch (JSONException e) {
            throw new BadRequest(ApiError.INTERNAL, null);
        }
    }

    private static final class BadRequest extends Exception {
        final ApiError error;
        final String detail;

        BadRequest(ApiError error, String detail) {
            super(error.name(), null, false, false);
            this.error = error;
            this.detail = detail;
        }
    }
}
