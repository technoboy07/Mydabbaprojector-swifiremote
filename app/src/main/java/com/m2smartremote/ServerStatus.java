package com.m2smartremote;

import android.os.Handler;
import android.os.Looper;

import java.util.concurrent.CopyOnWriteArrayList;

/** Observable server state; listeners are always called on the main thread. */
public final class ServerStatus {
    public enum State { STOPPED, WAITING_FOR_NETWORK, RUNNING, ERROR }

    public interface Listener {
        void onServerStatusChanged(ServerStatus status);
    }

    private final Handler main = new Handler(Looper.getMainLooper());
    private final CopyOnWriteArrayList<Listener> listeners = new CopyOnWriteArrayList<>();

    private volatile State state = State.STOPPED;
    private volatile String ip;
    private volatile int port;
    private volatile String message;

    public State state() {
        return state;
    }

    public String ip() {
        return ip;
    }

    public int port() {
        return port;
    }

    public String message() {
        return message;
    }

    public String url() {
        return state == State.RUNNING ? "http://" + ip + ":" + port + "/" : null;
    }

    void set(State state, String ip, int port, String message) {
        this.state = state;
        this.ip = ip;
        this.port = port;
        this.message = message;
        main.post(new Runnable() {
            @Override
            public void run() {
                for (Listener l : listeners) {
                    l.onServerStatusChanged(ServerStatus.this);
                }
            }
        });
    }

    public void addListener(Listener l) {
        listeners.add(l);
    }

    public void removeListener(Listener l) {
        listeners.remove(l);
    }
}
