package com.m2smartremote;

import android.app.Activity;
import android.os.Bundle;
import android.text.format.DateUtils;
import android.view.View;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import com.m2smartremote.core.Session;
import com.m2smartremote.input.AccessibilityController;

import java.util.List;

/** Port, start-on-boot, focus highlight, paired-phone list and revocation. */
public final class SettingsActivity extends Activity {
    private RemoteApp app;
    private EditText port;
    private TextView sessions;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);
        app = RemoteApp.get();

        port = findViewById(R.id.port);
        sessions = findViewById(R.id.sessions);
        port.setText(String.valueOf(app.prefs().port()));

        findViewById(R.id.save_port).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                savePort();
            }
        });

        CheckBox boot = findViewById(R.id.start_on_boot);
        boot.setChecked(app.prefs().startOnBoot());
        boot.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton b, boolean checked) {
                app.prefs().setStartOnBoot(checked);
            }
        });

        CheckBox highlight = findViewById(R.id.show_highlight);
        highlight.setChecked(app.prefs().showFocusHighlight());
        highlight.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton b, boolean checked) {
                app.prefs().setShowFocusHighlight(checked);
                AccessibilityController.get().setShowFocusHighlight(checked);
            }
        });

        findViewById(R.id.revoke_all).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                app.sessions().revokeAll();
                refreshSessions();
                Toast.makeText(SettingsActivity.this, "All phones disconnected", Toast.LENGTH_SHORT).show();
            }
        });

        findViewById(R.id.rotate_code).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                app.pairing().rotate();
                Toast.makeText(SettingsActivity.this, "New pairing code generated", Toast.LENGTH_SHORT).show();
            }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshSessions();
    }

    private void savePort() {
        int value;
        try {
            value = Integer.parseInt(port.getText().toString().trim());
        } catch (NumberFormatException e) {
            value = -1;
        }
        if (!Prefs.isValidPort(value)) {
            port.setError("Use a port between " + Prefs.MIN_PORT + " and " + Prefs.MAX_PORT);
            return;
        }
        app.prefs().setPort(value);
        if (app.serverStatus().state() != ServerStatus.State.STOPPED) {
            // The service re-binds on the next reconcile; trigger it now.
            RemoteService.start(this);
        }
        Toast.makeText(this, "Port saved. Paired phones must open the new address.", Toast.LENGTH_LONG).show();
    }

    private void refreshSessions() {
        List<Session> list = app.sessions().list();
        if (list.isEmpty()) {
            sessions.setText("No phones are paired.");
            return;
        }
        StringBuilder sb = new StringBuilder();
        long now = System.currentTimeMillis();
        for (Session s : list) {
            sb.append("• ").append(s.name).append(" (").append(s.ip).append(") — last used ")
                    .append(DateUtils.getRelativeTimeSpanString(s.lastSeenAt(), now,
                            DateUtils.MINUTE_IN_MILLIS))
                    .append('\n');
        }
        sessions.setText(sb.toString().trim());
    }
}
