package com.m2smartremote;

import android.content.Context;
import android.content.SharedPreferences;

/** User settings. Credentials are stored separately by {@link PrefsSessionStore}. */
public final class Prefs {
    public static final int DEFAULT_PORT = 8765;
    public static final int MIN_PORT = 1024;
    public static final int MAX_PORT = 65535;

    private static final String FILE = "settings";
    private static final String KEY_PORT = "port";
    private static final String KEY_START_ON_BOOT = "start_on_boot";
    private static final String KEY_SERVER_ENABLED = "server_enabled";
    private static final String KEY_SHOW_HIGHLIGHT = "show_focus_highlight";

    private final SharedPreferences sp;

    Prefs(Context context) {
        sp = context.getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    public int port() {
        int p = sp.getInt(KEY_PORT, DEFAULT_PORT);
        return isValidPort(p) ? p : DEFAULT_PORT;
    }

    public void setPort(int port) {
        sp.edit().putInt(KEY_PORT, port).apply();
    }

    public static boolean isValidPort(int port) {
        return port >= MIN_PORT && port <= MAX_PORT;
    }

    public boolean startOnBoot() {
        return sp.getBoolean(KEY_START_ON_BOOT, true);
    }

    public void setStartOnBoot(boolean value) {
        sp.edit().putBoolean(KEY_START_ON_BOOT, value).apply();
    }

    /** The user's last explicit choice: true after Start, false after Stop. */
    public boolean serverEnabled() {
        return sp.getBoolean(KEY_SERVER_ENABLED, true);
    }

    public void setServerEnabled(boolean value) {
        sp.edit().putBoolean(KEY_SERVER_ENABLED, value).apply();
    }

    public boolean showFocusHighlight() {
        return sp.getBoolean(KEY_SHOW_HIGHLIGHT, true);
    }

    public void setShowFocusHighlight(boolean value) {
        sp.edit().putBoolean(KEY_SHOW_HIGHLIGHT, value).apply();
    }
}
