package com.m2smartremote;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import com.m2smartremote.core.PairingManager;
import com.m2smartremote.core.SessionManager;
import com.m2smartremote.input.AccessibilityController;

/**
 * Projector-side status and pairing screen. Pairing is only accepted while this screen is in the
 * foreground, so pairing requires someone who can see the projector image.
 */
public final class MainActivity extends Activity implements ServerStatus.Listener,
        PairingManager.Listener, SessionManager.Listener {

    private final Handler main = new Handler(Looper.getMainLooper());
    private RemoteApp app;

    private TextView serverStatus;
    private TextView address;
    private TextView accessibilityStatus;
    private TextView sessionCount;
    private TextView pairingCode;
    private TextView pairingHint;
    private TextView pairingExpiry;
    private ImageView qr;
    private Button toggleServer;
    private Button openAccessibility;

    private String renderedQrContent;

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            refreshPairing();
            refreshAccessibility();
            main.postDelayed(this, 1000);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        app = RemoteApp.get();

        serverStatus = findViewById(R.id.server_status);
        address = findViewById(R.id.address);
        accessibilityStatus = findViewById(R.id.accessibility_status);
        sessionCount = findViewById(R.id.session_count);
        pairingCode = findViewById(R.id.pairing_code);
        pairingHint = findViewById(R.id.pairing_hint);
        pairingExpiry = findViewById(R.id.pairing_expiry);
        qr = findViewById(R.id.qr);
        toggleServer = findViewById(R.id.toggle_server);
        openAccessibility = findViewById(R.id.open_accessibility);

        toggleServer.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (app.serverStatus().state() == ServerStatus.State.STOPPED) {
                    app.prefs().setServerEnabled(true);
                    RemoteService.start(MainActivity.this);
                } else {
                    RemoteService.stop(MainActivity.this);
                }
            }
        });
        openAccessibility.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openAccessibilitySettings();
            }
        });
        findViewById(R.id.open_settings).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivity(new Intent(MainActivity.this, SettingsActivity.class));
            }
        });
        findViewById(R.id.revoke_all).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                app.sessions().revokeAll();
                Toast.makeText(MainActivity.this, "All phones disconnected", Toast.LENGTH_SHORT).show();
            }
        });

        if (app.prefs().serverEnabled() && app.serverStatus().state() == ServerStatus.State.STOPPED) {
            RemoteService.start(this);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        app.pairing().setWindowOpen(true);
        app.serverStatus().addListener(this);
        app.pairing().addListener(this);
        app.sessions().addListener(this);
        refreshAll();
        main.post(tick);
    }

    @Override
    protected void onPause() {
        app.pairing().setWindowOpen(false);
        app.serverStatus().removeListener(this);
        app.pairing().removeListener(this);
        app.sessions().removeListener(this);
        main.removeCallbacks(tick);
        super.onPause();
    }

    @Override
    public void onServerStatusChanged(ServerStatus status) {
        refreshAll();
    }

    @Override
    public void onPairingCodeChanged() {
        main.post(new Runnable() {
            @Override
            public void run() {
                refreshPairing();
            }
        });
    }

    @Override
    public void onSessionsChanged() {
        main.post(new Runnable() {
            @Override
            public void run() {
                refreshSessions();
            }
        });
    }

    private void refreshAll() {
        ServerStatus st = app.serverStatus();
        switch (st.state()) {
            case RUNNING:
                serverStatus.setText("Running");
                address.setText(st.ip() + " : " + st.port());
                toggleServer.setText(R.string.stop_server);
                break;
            case WAITING_FOR_NETWORK:
                serverStatus.setText("Waiting for network");
                address.setText("Connect the projector to Wi-Fi");
                toggleServer.setText(R.string.stop_server);
                break;
            case ERROR:
                serverStatus.setText("Error");
                address.setText(st.message());
                toggleServer.setText(R.string.stop_server);
                break;
            case STOPPED:
            default:
                serverStatus.setText("Stopped");
                address.setText("—");
                toggleServer.setText(R.string.start_server);
                break;
        }
        refreshAccessibility();
        refreshSessions();
        refreshPairing();
    }

    private void refreshAccessibility() {
        boolean connected = AccessibilityController.get().isAvailable();
        boolean enabled = AccessibilityController.isEnabledInSettings(this);
        if (connected) {
            accessibilityStatus.setText("On");
            openAccessibility.setText(R.string.accessibility_settings);
        } else if (enabled) {
            accessibilityStatus.setText("Enabled, starting…");
        } else {
            accessibilityStatus.setText("Off — needed for D-pad, Back, Home, touchpad and text");
            openAccessibility.setText(R.string.enable_accessibility);
        }
    }

    private void refreshSessions() {
        int n = app.sessions().count();
        sessionCount.setText(n == 0 ? "None" : String.valueOf(n));
    }

    private void refreshPairing() {
        ServerStatus st = app.serverStatus();
        if (st.state() != ServerStatus.State.RUNNING) {
            qr.setImageDrawable(null);
            qr.setVisibility(View.INVISIBLE);
            renderedQrContent = null;
            pairingCode.setText("— — — —");
            pairingHint.setText("Start the server to pair a phone.");
            pairingExpiry.setText("");
            return;
        }
        String code = app.pairing().currentCode();
        String content = st.url() + "#pair=" + code;
        if (!content.equals(renderedQrContent)) {
            Bitmap bmp = QrCodes.encode(content, getResources().getDimensionPixelSize(R.dimen.qr_size));
            qr.setImageBitmap(bmp);
            qr.setVisibility(View.VISIBLE);
            renderedQrContent = content;
        }
        pairingCode.setText(PairingManager.format(code));
        pairingHint.setText("Scan with your phone camera, or open " + st.url()
                + " in Chrome and type the code.");
        long secs = Math.max(0, (app.pairing().expiresAt() - System.currentTimeMillis()) / 1000);
        pairingExpiry.setText(String.format(java.util.Locale.US,
                "Code changes in %d:%02d and after each pairing", secs / 60, secs % 60));
    }

    private void openAccessibilitySettings() {
        Toast.makeText(this, "Find \"M2 Smart Remote\" and switch it on", Toast.LENGTH_LONG).show();
        try {
            startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
        } catch (ActivityNotFoundException e) {
            try {
                startActivity(new Intent(Settings.ACTION_SETTINGS));
            } catch (ActivityNotFoundException e2) {
                Toast.makeText(this, "No Settings app found. See docs/INSTALL.md for the adb method.",
                        Toast.LENGTH_LONG).show();
            }
        }
    }
}
