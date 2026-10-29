package com.m2smartremote.core;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/** The complete allowlist of discrete commands. Nothing outside this enum can be executed. */
public enum Command {
    UP(Kind.NAVIGATION),
    DOWN(Kind.NAVIGATION),
    LEFT(Kind.NAVIGATION),
    RIGHT(Kind.NAVIGATION),
    OK(Kind.NAVIGATION),
    BACK(Kind.GLOBAL),
    HOME(Kind.GLOBAL),
    /** No MENU key can be injected without privileges; this long-presses the focused item. */
    MENU(Kind.NAVIGATION),
    RECENTS(Kind.GLOBAL),
    NOTIFICATIONS(Kind.GLOBAL),
    VOLUME_UP(Kind.AUDIO),
    VOLUME_DOWN(Kind.AUDIO),
    MUTE(Kind.AUDIO),
    MEDIA_PLAY_PAUSE(Kind.MEDIA),
    MEDIA_NEXT(Kind.MEDIA),
    MEDIA_PREVIOUS(Kind.MEDIA),
    MEDIA_STOP(Kind.MEDIA),
    MEDIA_REWIND(Kind.MEDIA),
    MEDIA_FAST_FORWARD(Kind.MEDIA);

    public enum Kind {
        /** Needs the accessibility service (focus search, node actions, gestures). */
        NAVIGATION,
        /** Needs the accessibility service (performGlobalAction). */
        GLOBAL,
        /** AudioManager stream volume; works without accessibility. */
        AUDIO,
        /** AudioManager media-key dispatch to the active media session; no accessibility. */
        MEDIA
    }

    public final Kind kind;

    Command(Kind kind) {
        this.kind = kind;
    }

    public boolean requiresAccessibility() {
        return kind == Kind.NAVIGATION || kind == Kind.GLOBAL;
    }

    private static final Map<String, Command> BY_NAME;

    static {
        Map<String, Command> map = new HashMap<>();
        for (Command c : values()) {
            map.put(c.name(), c);
        }
        BY_NAME = Collections.unmodifiableMap(map);
    }

    /** Exact, case-sensitive lookup. Returns null for anything not on the allowlist. */
    public static Command parse(String name) {
        return name == null ? null : BY_NAME.get(name);
    }
}
