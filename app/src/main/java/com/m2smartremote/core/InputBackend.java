package com.m2smartremote.core;

/** Input actions that need the accessibility service. Implemented by AccessibilityController. */
public interface InputBackend {
    /** True when the accessibility service is enabled and connected. */
    boolean isAvailable();

    /** True when the service may dispatch gestures (touchpad taps, swipes). */
    boolean canDispatchGestures();

    ActionResult navigate(Direction direction);

    ActionResult select();

    ActionResult longPressFocused();

    ActionResult globalAction(Command command);

    ActionResult pointer(PointerAction action, int dx, int dy);

    ActionResult setText(String text);
}
