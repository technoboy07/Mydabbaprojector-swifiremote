package com.m2smartremote.input;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.content.Context;
import android.graphics.Path;
import android.graphics.Point;
import android.view.WindowManager;

import com.m2smartremote.core.ActionResult;
import com.m2smartremote.core.ApiError;

/**
 * Virtual mouse: a cursor drawn in the overlay plus taps/swipes injected with
 * {@link AccessibilityService#dispatchGesture} (API 24+). Android 7 offers no unprivileged way to
 * move the system mouse pointer, so this is a touch-emulating pointer, not a real mouse.
 */
final class PointerController {
    private static final long TAP_MS = 40;
    private static final long DOUBLE_TAP_GAP_MS = 110;
    private static final long LONG_PRESS_MS = 750;
    private static final long SWIPE_MS = 280;

    private final AccessibilityService service;
    private final OverlayManager overlay;
    private final WindowManager windowManager;

    private float x = -1;
    private float y = -1;
    private boolean visible;

    PointerController(AccessibilityService service, OverlayManager overlay) {
        this.service = service;
        this.overlay = overlay;
        this.windowManager = (WindowManager) service.getSystemService(Context.WINDOW_SERVICE);
    }

    synchronized void move(int dx, int dy) {
        Point size = screenSize();
        if (x < 0 || y < 0) {
            x = size.x / 2f;
            y = size.y / 2f;
        }
        x = clamp(x + dx, 0, size.x - 1);
        y = clamp(y + dy, 0, size.y - 1);
        visible = true;
        overlay.showCursor(x, y);
    }

    synchronized void hide() {
        visible = false;
        overlay.hideCursor();
    }

    synchronized boolean isVisible() {
        return visible;
    }

    ActionResult tap() {
        float[] p = currentPosition();
        return tapAt(p[0], p[1]);
    }

    ActionResult doubleTap() {
        float[] p = currentPosition();
        Path path = point(p[0], p[1]);
        GestureDescription g = new GestureDescription.Builder()
                .addStroke(new GestureDescription.StrokeDescription(path, 0, TAP_MS))
                .addStroke(new GestureDescription.StrokeDescription(path, TAP_MS + DOUBLE_TAP_GAP_MS, TAP_MS))
                .build();
        return dispatch(g);
    }

    ActionResult longPress() {
        float[] p = currentPosition();
        return longPressAt(p[0], p[1]);
    }

    /** Swipes from the cursor in the direction the fingers moved ("natural" scrolling). */
    ActionResult scroll(int dx, int dy) {
        float[] p = currentPosition();
        Point size = screenSize();
        float ex = clamp(p[0] + dx, 1, size.x - 2);
        float ey = clamp(p[1] + dy, 1, size.y - 2);
        float sx = clamp(p[0], 1, size.x - 2);
        float sy = clamp(p[1], 1, size.y - 2);
        if (Math.abs(ex - sx) < 2 && Math.abs(ey - sy) < 2) {
            return ActionResult.ok();
        }
        Path path = new Path();
        path.moveTo(sx, sy);
        path.lineTo(ex, ey);
        return dispatch(new GestureDescription.Builder()
                .addStroke(new GestureDescription.StrokeDescription(path, 0, SWIPE_MS))
                .build());
    }

    ActionResult tapAt(float px, float py) {
        return dispatch(new GestureDescription.Builder()
                .addStroke(new GestureDescription.StrokeDescription(point(px, py), 0, TAP_MS))
                .build());
    }

    ActionResult longPressAt(float px, float py) {
        return dispatch(new GestureDescription.Builder()
                .addStroke(new GestureDescription.StrokeDescription(point(px, py), 0, LONG_PRESS_MS))
                .build());
    }

    private synchronized float[] currentPosition() {
        if (x < 0 || y < 0) {
            Point size = screenSize();
            x = size.x / 2f;
            y = size.y / 2f;
        }
        visible = true;
        overlay.showCursor(x, y);
        return new float[]{x, y};
    }

    private ActionResult dispatch(GestureDescription gesture) {
        boolean accepted = service.dispatchGesture(gesture, null, null);
        return accepted ? ActionResult.ok()
                : ActionResult.fail(ApiError.ACTION_FAILED, "The projector rejected the gesture.");
    }

    private Path point(float px, float py) {
        Point size = screenSize();
        Path path = new Path();
        path.moveTo(clamp(px, 0, size.x - 1), clamp(py, 0, size.y - 1));
        return path;
    }

    private Point screenSize() {
        Point p = new Point();
        windowManager.getDefaultDisplay().getRealSize(p);
        return p;
    }

    private static float clamp(float v, float min, float max) {
        return Math.max(min, Math.min(max, v));
    }
}
