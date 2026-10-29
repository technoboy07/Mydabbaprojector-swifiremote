package com.m2smartremote.input;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.content.ComponentName;
import android.content.Context;
import android.os.Handler;
import android.os.HandlerThread;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.Log;
import android.view.accessibility.AccessibilityNodeInfo;

import com.m2smartremote.core.ActionResult;
import com.m2smartremote.core.ApiError;
import com.m2smartremote.core.Command;
import com.m2smartremote.core.Direction;
import com.m2smartremote.core.InputBackend;
import com.m2smartremote.core.PointerAction;

import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Bridge between HTTP threads and the accessibility service. All node-tree work is serialized on
 * one worker thread so rapid repeated presses are processed strictly in order.
 */
public final class AccessibilityController implements InputBackend {
    private static final String TAG = "M2Remote";
    private static final long ACTION_TIMEOUT_MS = 2500;

    private static final AccessibilityController INSTANCE = new AccessibilityController();

    private final Handler worker;
    private volatile RemoteAccessibilityService service;

    private AccessibilityController() {
        HandlerThread thread = new HandlerThread("m2r-input");
        thread.start();
        worker = new Handler(thread.getLooper());
    }

    public static AccessibilityController get() {
        return INSTANCE;
    }

    void attach(RemoteAccessibilityService s) {
        service = s;
    }

    void detach(RemoteAccessibilityService s) {
        if (service == s) {
            service = null;
        }
    }

    void post(Runnable r) {
        worker.post(r);
    }

    /** True when the user has switched the service on in Settings (it may not be bound yet). */
    public static boolean isEnabledInSettings(Context context) {
        String enabled = Settings.Secure.getString(context.getContentResolver(),
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        if (enabled == null) {
            return false;
        }
        ComponentName me = new ComponentName(context, RemoteAccessibilityService.class);
        TextUtils.SimpleStringSplitter splitter = new TextUtils.SimpleStringSplitter(':');
        splitter.setString(enabled);
        for (String s : splitter) {
            ComponentName cn = ComponentName.unflattenFromString(s);
            if (me.equals(cn)) {
                return true;
            }
        }
        return false;
    }

    public void setShowFocusHighlight(boolean show) {
        RemoteAccessibilityService s = service;
        if (s != null) {
            s.overlay().setShowFocusHighlight(show);
        }
    }

    @Override
    public boolean isAvailable() {
        return service != null;
    }

    @Override
    public boolean canDispatchGestures() {
        RemoteAccessibilityService s = service;
        if (s == null) {
            return false;
        }
        AccessibilityServiceInfo info = s.getServiceInfo();
        return info != null
                && (info.getCapabilities() & AccessibilityServiceInfo.CAPABILITY_CAN_PERFORM_GESTURES) != 0;
    }

    @Override
    public ActionResult navigate(final Direction direction) {
        return run(new Task() {
            @Override
            ActionResult exec(RemoteAccessibilityService s) {
                return s.navigator().navigate(direction);
            }
        });
    }

    @Override
    public ActionResult select() {
        return run(new Task() {
            @Override
            ActionResult exec(RemoteAccessibilityService s) {
                return s.navigator().select();
            }
        });
    }

    @Override
    public ActionResult longPressFocused() {
        return run(new Task() {
            @Override
            ActionResult exec(RemoteAccessibilityService s) {
                return s.navigator().longPress();
            }
        });
    }

    @Override
    public ActionResult globalAction(final Command command) {
        final int action;
        switch (command) {
            case BACK:
                action = AccessibilityService.GLOBAL_ACTION_BACK;
                break;
            case HOME:
                action = AccessibilityService.GLOBAL_ACTION_HOME;
                break;
            case RECENTS:
                action = AccessibilityService.GLOBAL_ACTION_RECENTS;
                break;
            case NOTIFICATIONS:
                action = AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS;
                break;
            default:
                return ActionResult.fail(ApiError.UNSUPPORTED_COMMAND, null);
        }
        return run(new Task() {
            @Override
            ActionResult exec(RemoteAccessibilityService s) {
                s.navigator().onWindowChanged();
                return s.performGlobalAction(action) ? ActionResult.ok()
                        : ActionResult.fail(ApiError.ACTION_FAILED,
                        "This projector's firmware does not support " + command.name() + ".");
            }
        });
    }

    @Override
    public ActionResult pointer(final PointerAction action, final int dx, final int dy) {
        final RemoteAccessibilityService s = service;
        if (s == null) {
            return ActionResult.fail(ApiError.ACCESSIBILITY_DISABLED, null);
        }
        // Cursor movement is cheap and latency-sensitive, so it skips the worker queue.
        if (action == PointerAction.MOVE) {
            s.pointer().move(dx, dy);
            return ActionResult.ok();
        }
        if (action == PointerAction.HIDE) {
            s.pointer().hide();
            return ActionResult.ok();
        }
        return run(new Task() {
            @Override
            ActionResult exec(RemoteAccessibilityService svc) {
                switch (action) {
                    case TAP:
                        return svc.pointer().tap();
                    case DOUBLE_TAP:
                        return svc.pointer().doubleTap();
                    case LONG_PRESS:
                        return svc.pointer().longPress();
                    case SCROLL:
                        return svc.pointer().scroll(dx, dy);
                    default:
                        return ActionResult.fail(ApiError.UNSUPPORTED_COMMAND, null);
                }
            }
        });
    }

    @Override
    public ActionResult setText(final String text) {
        return run(new Task() {
            @Override
            ActionResult exec(RemoteAccessibilityService s) {
                return s.navigator().setText(text);
            }
        });
    }

    void onWindowChanged() {
        worker.post(new Runnable() {
            @Override
            public void run() {
                RemoteAccessibilityService s = service;
                if (s != null) {
                    s.navigator().onWindowChanged();
                }
            }
        });
    }

    /** Takes ownership of {@code node}. */
    void onNativeFocus(final AccessibilityNodeInfo node) {
        worker.post(new Runnable() {
            @Override
            public void run() {
                RemoteAccessibilityService s = service;
                if (s != null) {
                    s.navigator().onNativeFocus(node);
                } else {
                    node.recycle();
                }
            }
        });
    }

    private abstract static class Task {
        abstract ActionResult exec(RemoteAccessibilityService s);
    }

    private ActionResult run(final Task task) {
        final RemoteAccessibilityService s = service;
        if (s == null) {
            return ActionResult.fail(ApiError.ACCESSIBILITY_DISABLED, null);
        }
        FutureTask<ActionResult> future = new FutureTask<>(new Callable<ActionResult>() {
            @Override
            public ActionResult call() {
                RemoteAccessibilityService current = service;
                if (current == null) {
                    return ActionResult.fail(ApiError.ACCESSIBILITY_DISABLED, null);
                }
                return task.exec(current);
            }
        });
        worker.post(future);
        try {
            return future.get(ACTION_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            future.cancel(false);
            return ActionResult.fail(ApiError.ACTION_FAILED, "The projector is busy. Try again.");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return ActionResult.fail(ApiError.INTERNAL, null);
        } catch (ExecutionException e) {
            Log.e(TAG, "Input action failed: " + e.getCause().getClass().getSimpleName());
            return ActionResult.fail(ApiError.ACTION_FAILED, "The action failed on this screen.");
        }
    }
}
