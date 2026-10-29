package com.m2smartremote.core;

final class FakeClock implements Clock {
    long now = 1_700_000_000_000L;

    @Override
    public long nowMillis() {
        return now;
    }

    void advance(long ms) {
        now += ms;
    }
}
