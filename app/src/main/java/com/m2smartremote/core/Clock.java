package com.m2smartremote.core;

/** Time source, injectable so expiry logic can be unit-tested. */
public interface Clock {
    long nowMillis();

    Clock SYSTEM = new Clock() {
        @Override
        public long nowMillis() {
            return System.currentTimeMillis();
        }
    };
}
