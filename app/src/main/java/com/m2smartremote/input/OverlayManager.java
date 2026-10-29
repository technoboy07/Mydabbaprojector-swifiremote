package com.m2smartremote.input;

import android.content.Context;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Gravity;
import android.view.WindowManager;

/**
 * Owns the accessibility overlay window (TYPE_ACCESSIBILITY_OVERLAY needs no SYSTEM_ALERT_WINDOW
 * permission). The window is untouchable and is removed whenever nothing is drawn, so it never
 * adds a composition layer on top of video playback for longer than a few seconds.
 */
final class OverlayManager {
    private static final String TAG = "M2Remote";
    private static final long FOCUS_HIDE_MS = 6000;
    private static final long CURSOR_HIDE_MS = 8000;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final WindowManager windowManager;
    private final OverlayView view;
    private boolean attached;
    private volatile boolean showFocusHighlight = true;

    private final Runnable hideFocus = new Runnable() {
        @Override
        public void run() {
            view.setFocus(null);
            detachIfIdle();
        }
    };

    private final Runnable hideCursor = new Runnable() {
        @Override
        public void run() {
            view.setCursor(false, 0, 0);
            detachIfIdle();
        }
    };

    OverlayManager(Context context) {
        windowManager = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
        view = new OverlayView(context);
    }

    void setShowFocusHighlight(boolean show) {
        showFocusHighlight = show;
    }

    void showFocus(final Rect rect) {
        if (!showFocusHighlight) {
            return;
        }
        final Rect copy = new Rect(rect);
        main.post(new Runnable() {
            @Override
            public void run() {
                if (ensureAttached()) {
                    view.setFocus(copy);
                    main.removeCallbacks(hideFocus);
                    main.postDelayed(hideFocus, FOCUS_HIDE_MS);
                }
            }
        });
    }

    void clearFocus() {
        main.post(hideFocus);
    }

    void showCursor(final float x, final float y) {
        main.post(new Runnable() {
            @Override
            public void run() {
                if (ensureAttached()) {
                    view.setCursor(true, x, y);
                    main.removeCallbacks(hideCursor);
                    main.postDelayed(hideCursor, CURSOR_HIDE_MS);
                }
            }
        });
    }

    void hideCursor() {
        main.post(hideCursor);
    }

    void destroy() {
        main.post(new Runnable() {
            @Override
            public void run() {
                main.removeCallbacks(hideFocus);
                main.removeCallbacks(hideCursor);
                detach();
            }
        });
    }

    private boolean ensureAttached() {
        if (attached) {
            return true;
        }
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.START;
        lp.setTitle("M2 Remote overlay");
        try {
            windowManager.addView(view, lp);
            attached = true;
        } catch (RuntimeException e) {
            Log.w(TAG, "Overlay unavailable: " + e.getClass().getSimpleName());
        }
        return attached;
    }

    private void detachIfIdle() {
        if (!view.isShowingAnything()) {
            detach();
        }
    }

    private void detach() {
        if (!attached) {
            return;
        }
        try {
            windowManager.removeViewImmediate(view);
        } catch (RuntimeException ignored) {
            // already removed by the system
        }
        attached = false;
    }
}
