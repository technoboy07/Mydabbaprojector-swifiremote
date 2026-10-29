package com.m2smartremote;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Restarts the server after a reboot or APK update, if the user left it enabled. */
public final class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        if (!Intent.ACTION_BOOT_COMPLETED.equals(action)
                && !Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)) {
            return;
        }
        Prefs prefs = RemoteApp.get().prefs();
        if (prefs.startOnBoot() && prefs.serverEnabled()) {
            RemoteService.start(context);
        }
    }
}
