package com.m2smartremote;

import android.annotation.TargetApi;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.ConnectivityManager;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;

import com.m2smartremote.core.CommandRouter;
import com.m2smartremote.input.AccessibilityController;
import com.m2smartremote.input.AudioController;
import com.m2smartremote.server.RemoteServer;

import java.io.IOException;

/**
 * Foreground service that owns the HTTP server. Being a foreground service keeps it alive while
 * other apps are in front. It re-binds the server whenever the LAN address changes (DHCP renewal,
 * Wi-Fi reconnect, switching to Ethernet) and stops listening when there is no LAN at all.
 */
public final class RemoteService extends Service {
    private static final String TAG = "M2Remote";
    private static final String ACTION_START = "com.m2smartremote.action.START";
    private static final String ACTION_STOP = "com.m2smartremote.action.STOP";
    private static final String CHANNEL_ID = "remote";
    private static final int NOTIFICATION_ID = 1;
    private static final long RECHECK_INTERVAL_MS = 30_000;
    private static final long NETWORK_SETTLE_MS = 1500;

    private final Handler main = new Handler(Looper.getMainLooper());
    private RemoteServer server;
    private CommandRouter router;
    private WifiManager.WifiLock wifiLock;
    private boolean receiverRegistered;

    public static void start(Context context) {
        context.startService(new Intent(context, RemoteService.class).setAction(ACTION_START));
    }

    public static void stop(Context context) {
        context.startService(new Intent(context, RemoteService.class).setAction(ACTION_STOP));
    }

    private final BroadcastReceiver connectivityReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            main.removeCallbacks(reconcile);
            main.postDelayed(reconcile, NETWORK_SETTLE_MS);
        }
    };

    private final Runnable reconcile = new Runnable() {
        @Override
        public void run() {
            reconcileServer();
        }
    };

    private final Runnable periodicCheck = new Runnable() {
        @Override
        public void run() {
            reconcileServer();
            main.postDelayed(this, RECHECK_INTERVAL_MS);
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        router = new CommandRouter(AccessibilityController.get(), new AudioController(this));
        WifiManager wifi = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        if (wifi != null) {
            wifiLock = wifi.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "m2r:server");
            wifiLock.setReferenceCounted(false);
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            RemoteApp.get().prefs().setServerEnabled(false);
            stopSelf();
            return START_NOT_STICKY;
        }
        startForeground(NOTIFICATION_ID, buildNotification());
        if (!receiverRegistered) {
            registerReceiver(connectivityReceiver, new IntentFilter(ConnectivityManager.CONNECTIVITY_ACTION));
            receiverRegistered = true;
        }
        if (wifiLock != null && !wifiLock.isHeld()) {
            wifiLock.acquire();
        }
        main.removeCallbacks(periodicCheck);
        main.post(periodicCheck);
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        main.removeCallbacksAndMessages(null);
        if (receiverRegistered) {
            unregisterReceiver(connectivityReceiver);
            receiverRegistered = false;
        }
        stopServer();
        if (wifiLock != null && wifiLock.isHeld()) {
            wifiLock.release();
        }
        status().set(ServerStatus.State.STOPPED, null, 0, null);
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void reconcileServer() {
        int port = RemoteApp.get().prefs().port();
        String ip = NetworkUtils.findLanIpv4();
        if (ip == null) {
            if (server != null) {
                Log.i(TAG, "LAN lost; server stopped until the network returns");
            }
            stopServer();
            status().set(ServerStatus.State.WAITING_FOR_NETWORK, null, port, "Waiting for Wi-Fi or Ethernet");
            updateNotification();
            return;
        }
        if (server != null && server.isAlive() && server.boundIp().equals(ip) && server.port() == port) {
            return;
        }
        stopServer();
        RemoteApp app = RemoteApp.get();
        RemoteServer s = new RemoteServer(this, ip, port, app.pairing(), app.sessions(),
                app.authenticator(), router, AccessibilityController.get(),
                new RemoteServer.StopHandler() {
                    @Override
                    public void onStopRequested() {
                        main.postDelayed(new Runnable() {
                            @Override
                            public void run() {
                                RemoteApp.get().prefs().setServerEnabled(false);
                                stopSelf();
                            }
                        }, 300);
                    }
                });
        try {
            s.startServer();
            server = s;
            Log.i(TAG, "Listening on " + ip + ":" + port);
            status().set(ServerStatus.State.RUNNING, ip, port, null);
        } catch (IOException e) {
            Log.w(TAG, "Could not bind " + ip + ":" + port + ": " + e.getClass().getSimpleName());
            status().set(ServerStatus.State.ERROR, ip, port,
                    "Port " + port + " is already in use. Choose another port in Settings.");
        }
        updateNotification();
    }

    private void stopServer() {
        if (server != null) {
            server.stopServer();
            server = null;
        }
    }

    private ServerStatus status() {
        return RemoteApp.get().serverStatus();
    }

    private void updateNotification() {
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        nm.notify(NOTIFICATION_ID, buildNotification());
    }

    private Notification buildNotification() {
        Notification.Builder b;
        if (Build.VERSION.SDK_INT >= 26) {
            b = newBuilderWithChannel();
        } else {
            b = new Notification.Builder(this);
        }
        ServerStatus st = status();
        String text;
        switch (st.state()) {
            case RUNNING:
                text = "Open " + st.url() + " on your phone";
                break;
            case WAITING_FOR_NETWORK:
                text = "Waiting for Wi-Fi or Ethernet";
                break;
            case ERROR:
                text = st.message();
                break;
            default:
                text = "Starting…";
                break;
        }
        int piFlags = PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE;
        PendingIntent open = PendingIntent.getActivity(this, 0,
                new Intent(this, MainActivity.class), piFlags);
        PendingIntent stop = PendingIntent.getService(this, 1,
                new Intent(this, RemoteService.class).setAction(ACTION_STOP), piFlags);
        return b.setSmallIcon(R.drawable.ic_notification)
                .setContentTitle("M2 Smart Remote is on")
                .setContentText(text)
                .setContentIntent(open)
                .setOngoing(true)
                .setShowWhen(false)
                .addAction(0, "Stop", stop)
                .build();
    }

    @TargetApi(26)
    private Notification.Builder newBuilderWithChannel() {
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        nm.createNotificationChannel(new NotificationChannel(CHANNEL_ID, "Remote server",
                NotificationManager.IMPORTANCE_LOW));
        return new Notification.Builder(this, CHANNEL_ID);
    }
}
