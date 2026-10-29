package com.m2smartremote;

import android.app.Application;

import com.m2smartremote.core.Clock;
import com.m2smartremote.core.Crypto;
import com.m2smartremote.core.PairingManager;
import com.m2smartremote.core.RequestAuthenticator;
import com.m2smartremote.core.SessionManager;

/** Process-wide singletons shared by the activities, the server service and the HTTP server. */
public final class RemoteApp extends Application {
    /** A pairing code is valid for 10 minutes, then rotates even if unused. */
    private static final long PAIRING_CODE_TTL_MS = 10 * 60 * 1000;

    private static RemoteApp instance;

    private Prefs prefs;
    private PairingManager pairing;
    private SessionManager sessions;
    private RequestAuthenticator authenticator;
    private final ServerStatus serverStatus = new ServerStatus();

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        prefs = new Prefs(this);
        pairing = new PairingManager(Clock.SYSTEM, Crypto.secureRandom(), PAIRING_CODE_TTL_MS);
        sessions = new SessionManager(Clock.SYSTEM, new PrefsSessionStore(this));
        authenticator = new RequestAuthenticator(Clock.SYSTEM, sessions);
    }

    public static RemoteApp get() {
        return instance;
    }

    public Prefs prefs() {
        return prefs;
    }

    public PairingManager pairing() {
        return pairing;
    }

    public SessionManager sessions() {
        return sessions;
    }

    public RequestAuthenticator authenticator() {
        return authenticator;
    }

    public ServerStatus serverStatus() {
        return serverStatus;
    }
}
