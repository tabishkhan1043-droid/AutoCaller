// ============================================================
// File: app/src/main/java/com/autocaller/app/BootReceiver.java
// Auto Caller — restarts the foreground service after device boot.
//
// Receives BOOT_COMPLETED / QUICKBOOT_POWERON. If the user
// previously enabled the service in prefs, it is restarted so
// polling resumes without manual app launch.
// ============================================================
package com.autocaller.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

public final class BootReceiver extends BroadcastReceiver {

    private static final String TAG = Constants.TAG + ":Boot";

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent != null ? intent.getAction() : null;
        Log.i(TAG, "AutoCaller: boot received — " + action);

        if (action == null) return;
        if (!action.equals(Intent.ACTION_BOOT_COMPLETED)
                && !action.equals("android.intent.action.QUICKBOOT_POWERON")
                && !action.equals("com.htc.intent.action.QUICKBOOT_POWERON")) {
            return;
        }

        Logger.get().log("BootReceiver fired: " + action);
        try {
            // Delegates the actual service-start decision (enabled & configured)
            // to CallerService.startIfEnabled, which calls
            // ContextCompat.startForegroundService under the hood.
            CallerService.startIfEnabled(context);
        } catch (Exception e) {
            Log.e(TAG, "AutoCaller: failed to start service on boot", e);
            Logger.get().log("BootReceiver failed — " + e.getMessage());
        }
    }
}
