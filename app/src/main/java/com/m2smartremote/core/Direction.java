package com.m2smartremote.core;

public enum Direction {
    UP, DOWN, LEFT, RIGHT;

    public boolean isHorizontal() {
        return this == LEFT || this == RIGHT;
    }

    /** True for the directions that map to ACTION_SCROLL_FORWARD. */
    public boolean isForward() {
        return this == DOWN || this == RIGHT;
    }
}
