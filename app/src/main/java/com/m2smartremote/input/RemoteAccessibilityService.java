package com.m2smartremote.input;

import android.accessibilityservice.AccessibilityService;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import com.m2smartremote.RemoteApp;

/**
 * The accessibility service the user enables manually in Settings > Accessibility.
 * It is the only component that can act on other apps' UI. It never reads or reports screen
 * content to the phone; it only performs the allowlisted actions it is asked to.
 */
public final class RemoteAccessibilityService extends AccessibilityService {
    private static final String TAG = "M2Remote";

    private OverlayManager overlay;
    private PointerController pointer;
    private FocusNavigator navigator;

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        overlay = new OverlayManager(this);
        overlay.setShowFocusHighlight(RemoteApp.get().prefs().showFocusHighlight());
        pointer = new PointerController(this, overlay);
        navigator = new FocusNavigator(this, overlay, pointer);
        AccessibilityController.get().attach(this);
        Log.i(TAG, "Accessibility service connected");
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (navigator == null) {
            return;
        }
        switch (event.getEventType()) {
            case AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED:
                AccessibilityController.get().onWindowChanged();
                break;
            case AccessibilityEvent.TYPE_VIEW_FOCUSED:
                AccessibilityNodeInfo source = event.getSource();
                if (source != null) {
                    AccessibilityController.get().onNativeFocus(source);
                }
                break;
            default:
                break;
        }
    }

    @Override
    public void onInterrupt() {
        // Nothing to interrupt: no spoken feedback or long-running actions.
    }

    @Override
    public boolean onUnbind(android.content.Intent intent) {
        shutdown();
        return super.onUnbind(intent);
    }

    @Override
    public void onDestroy() {
        shutdown();
        super.onDestroy();
    }

    private void shutdown() {
        AccessibilityController.get().detach(this);
        if (navigator != null) {
            final FocusNavigator n = navigator;
            AccessibilityController.get().post(new Runnable() {
                @Override
                public void run() {
                    n.release();
                }
            });
        }
        if (overlay != null) {
            overlay.destroy();
        }
        Log.i(TAG, "Accessibility service disconnected");
    }

    OverlayManager overlay() {
        return overlay;
    }

    PointerController pointer() {
        return pointer;
    }

    FocusNavigator navigator() {
        return navigator;
    }
}
