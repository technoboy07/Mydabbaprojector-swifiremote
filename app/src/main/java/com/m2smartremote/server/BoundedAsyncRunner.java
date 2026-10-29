package com.m2smartremote.server;

import java.util.ArrayList;
import java.util.List;

import fi.iki.elonen.NanoHTTPD;

/**
 * NanoHTTPD's default runner starts an unbounded number of threads. This one caps concurrent
 * connections so a misbehaving client cannot exhaust the projector's ~1 GB of RAM.
 */
final class BoundedAsyncRunner implements NanoHTTPD.AsyncRunner {
    private final int maxConnections;
    private final List<NanoHTTPD.ClientHandler> running = new ArrayList<>();
    private long counter;

    BoundedAsyncRunner(int maxConnections) {
        this.maxConnections = maxConnections;
    }

    @Override
    public void exec(NanoHTTPD.ClientHandler handler) {
        synchronized (running) {
            if (running.size() >= maxConnections) {
                handler.close();
                return;
            }
            running.add(handler);
        }
        Thread t = new Thread(handler, "m2r-http-" + (++counter));
        t.setDaemon(true);
        t.start();
    }

    @Override
    public void closed(NanoHTTPD.ClientHandler handler) {
        synchronized (running) {
            running.remove(handler);
        }
    }

    @Override
    public void closeAll() {
        List<NanoHTTPD.ClientHandler> copy;
        synchronized (running) {
            copy = new ArrayList<>(running);
            running.clear();
        }
        for (NanoHTTPD.ClientHandler h : copy) {
            h.close();
        }
    }
}
