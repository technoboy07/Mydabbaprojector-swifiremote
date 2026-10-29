package com.m2smartremote.core;

/** Outcome of an input action. {@code detail} is a short, user-facing explanation. */
public final class ActionResult {
    public final ApiError error;
    public final String detail;

    private ActionResult(ApiError error, String detail) {
        this.error = error;
        this.detail = detail;
    }

    public static ActionResult ok() {
        return new ActionResult(null, null);
    }

    public static ActionResult ok(String detail) {
        return new ActionResult(null, detail);
    }

    public static ActionResult fail(ApiError error, String detail) {
        return new ActionResult(error, detail);
    }

    public boolean isOk() {
        return error == null;
    }
}
