package com.m2smartremote.core;

/** Stable error codes returned to the phone as {"ok":false,"error":{"code":...}}. */
public enum ApiError {
    UNAUTHORIZED(401, "Request is not signed by a paired phone."),
    SESSION_EXPIRED(401, "This phone's session has expired or was revoked. Pair again."),
    STALE_TIMESTAMP(401, "Request time is outside the allowed window."),
    REPLAYED_REQUEST(401, "Request was already processed."),
    PAIRING_CLOSED(403, "Open M2 Smart Remote on the projector to pair."),
    PAIRING_FAILED(403, "Pairing code is wrong or has expired."),
    FORBIDDEN_ORIGIN(403, "Request origin or host is not allowed."),
    FORBIDDEN_NETWORK(403, "Only devices on the local network may connect."),
    NOT_FOUND(404, "Not found."),
    METHOD_NOT_ALLOWED(405, "Method not allowed."),
    INVALID_INPUT(400, "Invalid request."),
    UNSUPPORTED_COMMAND(400, "Command is not supported."),
    UNSUPPORTED_MEDIA_TYPE(415, "Content-Type must be application/json."),
    PAYLOAD_TOO_LARGE(413, "Request body is too large."),
    ACTION_FAILED(422, "The projector could not perform that action here."),
    RATE_LIMITED(429, "Too many requests. Slow down."),
    ACCESSIBILITY_DISABLED(503, "Enable the M2 Smart Remote accessibility service on the projector."),
    SERVER_UNAVAILABLE(503, "Remote server is shutting down."),
    INTERNAL(500, "Internal error.");

    public final int httpStatus;
    public final String defaultMessage;

    ApiError(int httpStatus, String defaultMessage) {
        this.httpStatus = httpStatus;
        this.defaultMessage = defaultMessage;
    }
}
