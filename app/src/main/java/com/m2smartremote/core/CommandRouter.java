package com.m2smartremote.core;

/** Maps validated, allowlisted requests onto the input and audio backends. */
public final class CommandRouter {
    /** Largest pointer delta accepted in one request, in screen pixels. */
    public static final int MAX_POINTER_DELTA = 2000;

    private final InputBackend input;
    private final AudioBackend audio;

    public CommandRouter(InputBackend input, AudioBackend audio) {
        this.input = input;
        this.audio = audio;
    }

    public ActionResult command(Command c) {
        if (c == null) {
            return ActionResult.fail(ApiError.UNSUPPORTED_COMMAND, null);
        }
        if (c.requiresAccessibility() && !input.isAvailable()) {
            return ActionResult.fail(ApiError.ACCESSIBILITY_DISABLED, null);
        }
        switch (c) {
            case UP:
                return input.navigate(Direction.UP);
            case DOWN:
                return input.navigate(Direction.DOWN);
            case LEFT:
                return input.navigate(Direction.LEFT);
            case RIGHT:
                return input.navigate(Direction.RIGHT);
            case OK:
                return input.select();
            case MENU:
                return input.longPressFocused();
            case BACK:
            case HOME:
            case RECENTS:
            case NOTIFICATIONS:
                return input.globalAction(c);
            case VOLUME_UP:
            case VOLUME_DOWN:
            case MUTE:
                return audio.volume(c);
            case MEDIA_PLAY_PAUSE:
            case MEDIA_NEXT:
            case MEDIA_PREVIOUS:
            case MEDIA_STOP:
            case MEDIA_REWIND:
            case MEDIA_FAST_FORWARD:
                return audio.mediaKey(c);
            default:
                return ActionResult.fail(ApiError.UNSUPPORTED_COMMAND, null);
        }
    }

    public ActionResult pointer(PointerAction action, int dx, int dy) {
        if (action == null) {
            return ActionResult.fail(ApiError.UNSUPPORTED_COMMAND, null);
        }
        if (Math.abs(dx) > MAX_POINTER_DELTA || Math.abs(dy) > MAX_POINTER_DELTA) {
            return ActionResult.fail(ApiError.INVALID_INPUT, "Pointer delta out of range.");
        }
        if (!input.isAvailable()) {
            return ActionResult.fail(ApiError.ACCESSIBILITY_DISABLED, null);
        }
        boolean needsGesture = action != PointerAction.MOVE && action != PointerAction.HIDE;
        if (needsGesture && !input.canDispatchGestures()) {
            return ActionResult.fail(ApiError.ACTION_FAILED,
                    "Gesture dispatch is not available on this device.");
        }
        return input.pointer(action, dx, dy);
    }

    public ActionResult text(String raw) {
        String text = TextInput.sanitize(raw);
        if (text == null) {
            return ActionResult.fail(ApiError.INVALID_INPUT,
                    "Text must be at most " + TextInput.MAX_LENGTH + " characters.");
        }
        if (!input.isAvailable()) {
            return ActionResult.fail(ApiError.ACCESSIBILITY_DISABLED, null);
        }
        return input.setText(text);
    }
}
