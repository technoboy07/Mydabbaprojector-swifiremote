package com.m2smartremote.input;

import android.accessibilityservice.AccessibilityService;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Point;
import android.graphics.Rect;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction;

import com.m2smartremote.core.ActionResult;
import com.m2smartremote.core.ApiError;
import com.m2smartremote.core.Box;
import com.m2smartremote.core.Direction;
import com.m2smartremote.core.DirectionalFinder;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

/**
 * D-pad emulation through the accessibility node tree. Must only be used from the
 * AccessibilityController worker thread.
 *
 * <p>An accessibility service cannot inject KEYCODE_DPAD_* events, so navigation works in two
 * layers:
 * <ol>
 *   <li><b>Native focus</b>: if the app has an input-focused view, ask the app's own FocusFinder
 *       for the next view ({@code focusSearch}) and move focus there with ACTION_FOCUS. This is
 *       identical to hardware D-pad behaviour, but fails when the app is in touch mode.</li>
 *   <li><b>Virtual focus</b>: otherwise, pick the next clickable/focusable node geometrically
 *       (same scoring as Android's FocusFinder) and draw our own highlight around it. OK then
 *       clicks that node.</li>
 * </ol>
 * Apps that draw their UI without accessibility nodes (games, some video players, Unity/GL apps)
 * expose nothing to navigate; touchpad mode is the fallback there.
 */
final class FocusNavigator {
    private static final int MAX_NODES = 1200;
    private static final int MAX_PARENT_HOPS = 6;

    private final AccessibilityService service;
    private final OverlayManager overlay;
    private final PointerController pointer;
    private final WindowManager windowManager;

    private AccessibilityNodeInfo virtual;
    private final Rect virtualRect = new Rect();

    FocusNavigator(AccessibilityService service, OverlayManager overlay, PointerController pointer) {
        this.service = service;
        this.overlay = overlay;
        this.pointer = pointer;
        this.windowManager = (WindowManager) service.getSystemService(Context.WINDOW_SERVICE);
    }

    // ---- events ---------------------------------------------------------------------------

    void onWindowChanged() {
        clearVirtual();
    }

    /** Native focus moved (e.g. by the physical remote); follow it. Takes ownership of node. */
    void onNativeFocus(AccessibilityNodeInfo node) {
        try {
            if (node.isVisibleToUser()) {
                setVirtual(node, false);
            }
        } finally {
            node.recycle();
        }
    }

    void release() {
        clearVirtual();
    }

    // ---- navigation -----------------------------------------------------------------------

    ActionResult navigate(Direction dir) {
        AccessibilityNodeInfo root = service.getRootInActiveWindow();
        if (root == null) {
            return ActionResult.fail(ApiError.ACTION_FAILED, "No app window is active on the projector.");
        }
        try {
            boolean haveVirtual = virtualValid(root.getWindowId());
            AccessibilityNodeInfo inputFocus = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
            try {
                if (inputFocus != null && (!haveVirtual || boundsOf(inputFocus).equals(virtualRect))) {
                    AccessibilityNodeInfo next = inputFocus.focusSearch(toViewDirection(dir));
                    if (next != null) {
                        try {
                            if (next.isVisibleToUser() && next.performAction(AccessibilityNodeInfo.ACTION_FOCUS)) {
                                setVirtual(next, true);
                                return ActionResult.ok();
                            }
                        } finally {
                            next.recycle();
                        }
                    }
                }
                Rect source = haveVirtual ? new Rect(virtualRect)
                        : inputFocus != null ? boundsOf(inputFocus) : null;
                return geometric(root, dir, source, true);
            } finally {
                if (inputFocus != null) {
                    inputFocus.recycle();
                }
            }
        } finally {
            root.recycle();
        }
    }

    private ActionResult geometric(AccessibilityNodeInfo root, Direction dir, Rect source,
                                   boolean allowScroll) {
        List<AccessibilityNodeInfo> nodes = new ArrayList<>();
        List<Box> boxes = new ArrayList<>();
        collectCandidates(root, nodes, boxes);
        try {
            if (nodes.isEmpty()) {
                return ActionResult.fail(ApiError.ACTION_FAILED,
                        "Nothing on this screen is selectable with the D-pad. Try touchpad mode.");
            }
            int idx = source == null ? DirectionalFinder.findFirst(boxes)
                    : DirectionalFinder.findNext(toBox(source), boxes, dir);
            if (idx >= 0) {
                AccessibilityNodeInfo target = nodes.get(idx);
                if (target.isFocusable()) {
                    target.performAction(AccessibilityNodeInfo.ACTION_FOCUS);
                }
                setVirtual(target, true);
                return ActionResult.ok();
            }
        } finally {
            recycleAll(nodes);
        }
        if (allowScroll && scroll(root, dir)) {
            SystemClock.sleep(300); // let the list settle before searching again
            AccessibilityNodeInfo fresh = service.getRootInActiveWindow();
            if (fresh != null) {
                try {
                    Rect newSource = source;
                    if (virtualValid(fresh.getWindowId())) {
                        newSource = new Rect(virtualRect);
                    }
                    return geometric(fresh, dir, newSource, false);
                } finally {
                    fresh.recycle();
                }
            }
        }
        return ActionResult.ok("Reached the edge of the screen.");
    }

    ActionResult select() {
        return activate(AccessibilityNodeInfo.ACTION_CLICK, false);
    }

    ActionResult longPress() {
        return activate(AccessibilityNodeInfo.ACTION_LONG_CLICK, true);
    }

    private ActionResult activate(int action, boolean longPress) {
        AccessibilityNodeInfo root = service.getRootInActiveWindow();
        if (root == null) {
            return ActionResult.fail(ApiError.ACTION_FAILED, "No app window is active on the projector.");
        }
        AccessibilityNodeInfo target = null;
        try {
            if (virtualValid(root.getWindowId())) {
                target = AccessibilityNodeInfo.obtain(virtual);
            } else {
                target = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
            }
            if (target == null) {
                if (pointer.isVisible()) {
                    return longPress ? pointer.longPress() : pointer.tap();
                }
                return ActionResult.fail(ApiError.ACTION_FAILED,
                        "Nothing is selected yet. Press an arrow first.");
            }
            Rect bounds = boundsOf(target);
            AccessibilityNodeInfo cur = AccessibilityNodeInfo.obtain(target);
            for (int hop = 0; cur != null && hop < MAX_PARENT_HOPS; hop++) {
                boolean supports = longPress ? cur.isLongClickable() : cur.isClickable();
                if (supports) {
                    boolean done = cur.performAction(action);
                    cur.recycle();
                    cur = null;
                    if (done) {
                        return ActionResult.ok();
                    }
                    break;
                }
                AccessibilityNodeInfo parent = cur.getParent();
                cur.recycle();
                cur = parent;
            }
            if (cur != null) {
                cur.recycle();
            }
            // No node accepted the action: fall back to touching the item's centre.
            if (AccessibilityController.get().canDispatchGestures() && !bounds.isEmpty()) {
                return longPress ? pointer.longPressAt(bounds.exactCenterX(), bounds.exactCenterY())
                        : pointer.tapAt(bounds.exactCenterX(), bounds.exactCenterY());
            }
            return ActionResult.fail(ApiError.ACTION_FAILED, longPress
                    ? "This item has no options menu."
                    : "This item cannot be activated remotely.");
        } finally {
            if (target != null) {
                target.recycle();
            }
            root.recycle();
        }
    }

    // ---- text -----------------------------------------------------------------------------

    ActionResult setText(String text) {
        AccessibilityNodeInfo root = service.getRootInActiveWindow();
        if (root == null) {
            return ActionResult.fail(ApiError.ACTION_FAILED, "No app window is active on the projector.");
        }
        AccessibilityNodeInfo field = null;
        try {
            field = findEditable(root);
            if (field == null) {
                return ActionResult.fail(ApiError.ACTION_FAILED,
                        "No text field found. Select a search box on the projector first.");
            }
            Bundle args = new Bundle();
            args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text);
            if (field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) {
                Bundle sel = new Bundle();
                sel.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, text.length());
                sel.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, text.length());
                field.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, sel);
                return ActionResult.ok();
            }
            if (text.isEmpty()) {
                return ActionResult.fail(ApiError.ACTION_FAILED, "This text field cannot be cleared remotely.");
            }
            // Fallback for fields that ignore SET_TEXT: select everything and paste.
            ClipboardManager cm = (ClipboardManager) service.getSystemService(Context.CLIPBOARD_SERVICE);
            cm.setPrimaryClip(ClipData.newPlainText("M2 Remote", text));
            field.performAction(AccessibilityNodeInfo.ACTION_FOCUS);
            CharSequence existing = field.getText();
            Bundle all = new Bundle();
            all.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, 0);
            all.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT,
                    existing == null ? 0 : existing.length());
            field.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, all);
            if (field.performAction(AccessibilityNodeInfo.ACTION_PASTE)) {
                return ActionResult.ok("Pasted through the clipboard.");
            }
            return ActionResult.fail(ApiError.ACTION_FAILED,
                    "This app's text field does not accept remote text.");
        } finally {
            if (field != null) {
                field.recycle();
            }
            root.recycle();
        }
    }

    private AccessibilityNodeInfo findEditable(AccessibilityNodeInfo root) {
        AccessibilityNodeInfo focus = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
        if (focus != null) {
            if (focus.isEditable()) {
                return focus;
            }
            focus.recycle();
        }
        if (virtualValid(root.getWindowId()) && virtual.isEditable()) {
            return AccessibilityNodeInfo.obtain(virtual);
        }
        // Last resort: the first visible editable field on screen (e.g. a search box).
        ArrayDeque<AccessibilityNodeInfo> queue = new ArrayDeque<>();
        queue.add(AccessibilityNodeInfo.obtain(root));
        AccessibilityNodeInfo found = null;
        int visited = 0;
        while (!queue.isEmpty()) {
            AccessibilityNodeInfo n = queue.poll();
            if (found == null && visited++ < MAX_NODES) {
                if (n.isEditable() && n.isVisibleToUser() && n.isEnabled()) {
                    found = n;
                    continue;
                }
                for (int i = 0; i < n.getChildCount(); i++) {
                    AccessibilityNodeInfo c = n.getChild(i);
                    if (c != null) {
                        queue.add(c);
                    }
                }
            }
            n.recycle();
        }
        return found;
    }

    // ---- helpers --------------------------------------------------------------------------

    private void collectCandidates(AccessibilityNodeInfo root, List<AccessibilityNodeInfo> nodes,
                                   List<Box> boxes) {
        Point size = new Point();
        windowManager.getDefaultDisplay().getRealSize(size);
        Rect screen = new Rect(0, 0, size.x, size.y);
        long maxArea = (long) size.x * size.y * 9 / 10;

        ArrayDeque<AccessibilityNodeInfo> queue = new ArrayDeque<>();
        queue.add(AccessibilityNodeInfo.obtain(root));
        int visited = 0;
        Rect r = new Rect();
        while (!queue.isEmpty()) {
            AccessibilityNodeInfo n = queue.poll();
            if (visited++ >= MAX_NODES) {
                n.recycle();
                continue;
            }
            for (int i = 0; i < n.getChildCount(); i++) {
                AccessibilityNodeInfo c = n.getChild(i);
                if (c != null) {
                    queue.add(c);
                }
            }
            n.getBoundsInScreen(r);
            boolean actionable = n.isClickable() || n.isFocusable() || n.isLongClickable() || n.isCheckable();
            long area = (long) r.width() * r.height();
            if (actionable && n.isVisibleToUser() && n.isEnabled() && !r.isEmpty()
                    && Rect.intersects(r, screen) && area < maxArea) {
                nodes.add(n);
                boxes.add(new Box(r.left, r.top, r.right, r.bottom));
            } else {
                n.recycle();
            }
        }
    }

    private boolean scroll(AccessibilityNodeInfo root, Direction dir) {
        AccessibilityNodeInfo scrollable = null;
        if (virtual != null) {
            AccessibilityNodeInfo cur = virtual.getParent();
            for (int hop = 0; cur != null && hop < 12; hop++) {
                if (cur.isScrollable()) {
                    scrollable = cur;
                    break;
                }
                AccessibilityNodeInfo parent = cur.getParent();
                cur.recycle();
                cur = parent;
            }
            if (scrollable == null && cur != null) {
                cur.recycle();
            }
        }
        if (scrollable == null) {
            scrollable = largestScrollable(root);
        }
        if (scrollable == null) {
            return false;
        }
        try {
            AccessibilityAction directional = directionalScroll(dir);
            if (scrollable.getActionList().contains(directional)
                    && scrollable.performAction(directional.getId())) {
                return true;
            }
            return scrollable.performAction(dir.isForward()
                    ? AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
                    : AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD);
        } finally {
            scrollable.recycle();
        }
    }

    private AccessibilityNodeInfo largestScrollable(AccessibilityNodeInfo root) {
        ArrayDeque<AccessibilityNodeInfo> queue = new ArrayDeque<>();
        queue.add(AccessibilityNodeInfo.obtain(root));
        AccessibilityNodeInfo best = null;
        long bestArea = 0;
        int visited = 0;
        Rect r = new Rect();
        while (!queue.isEmpty()) {
            AccessibilityNodeInfo n = queue.poll();
            if (visited++ >= MAX_NODES) {
                n.recycle();
                continue;
            }
            for (int i = 0; i < n.getChildCount(); i++) {
                AccessibilityNodeInfo c = n.getChild(i);
                if (c != null) {
                    queue.add(c);
                }
            }
            n.getBoundsInScreen(r);
            long area = (long) r.width() * r.height();
            if (n.isScrollable() && n.isVisibleToUser() && area > bestArea) {
                if (best != null) {
                    best.recycle();
                }
                best = n;
                bestArea = area;
            } else {
                n.recycle();
            }
        }
        return best;
    }

    private boolean virtualValid(int activeWindowId) {
        if (virtual == null) {
            return false;
        }
        if (virtual.getWindowId() != activeWindowId || !virtual.refresh() || !virtual.isVisibleToUser()) {
            clearVirtual();
            return false;
        }
        virtual.getBoundsInScreen(virtualRect);
        return !virtualRect.isEmpty();
    }

    private void setVirtual(AccessibilityNodeInfo node, boolean highlight) {
        if (virtual != null) {
            virtual.recycle();
        }
        virtual = AccessibilityNodeInfo.obtain(node);
        virtual.getBoundsInScreen(virtualRect);
        if (highlight) {
            overlay.showFocus(virtualRect);
        }
    }

    private void clearVirtual() {
        if (virtual != null) {
            virtual.recycle();
            virtual = null;
        }
        virtualRect.setEmpty();
        overlay.clearFocus();
    }

    private static Rect boundsOf(AccessibilityNodeInfo n) {
        Rect r = new Rect();
        n.getBoundsInScreen(r);
        return r;
    }

    private static Box toBox(Rect r) {
        return new Box(r.left, r.top, r.right, r.bottom);
    }

    private static int toViewDirection(Direction dir) {
        switch (dir) {
            case UP:
                return View.FOCUS_UP;
            case DOWN:
                return View.FOCUS_DOWN;
            case LEFT:
                return View.FOCUS_LEFT;
            case RIGHT:
            default:
                return View.FOCUS_RIGHT;
        }
    }

    private static AccessibilityAction directionalScroll(Direction dir) {
        switch (dir) {
            case UP:
                return AccessibilityAction.ACTION_SCROLL_UP;
            case DOWN:
                return AccessibilityAction.ACTION_SCROLL_DOWN;
            case LEFT:
                return AccessibilityAction.ACTION_SCROLL_LEFT;
            case RIGHT:
            default:
                return AccessibilityAction.ACTION_SCROLL_RIGHT;
        }
    }

    private static void recycleAll(List<AccessibilityNodeInfo> nodes) {
        for (AccessibilityNodeInfo n : nodes) {
            n.recycle();
        }
        nodes.clear();
    }
}
