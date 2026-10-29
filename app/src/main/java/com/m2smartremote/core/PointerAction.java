package com.m2smartremote.core;

/** Allowlisted touchpad actions. */
public enum PointerAction {
    MOVE, TAP, DOUBLE_TAP, LONG_PRESS, SCROLL, HIDE;

    public static PointerAction parse(String name) {
        if (name == null) {
            return null;
        }
        for (PointerAction a : values()) {
            if (a.name().equals(name)) {
                return a;
            }
        }
        return null;
    }
}
