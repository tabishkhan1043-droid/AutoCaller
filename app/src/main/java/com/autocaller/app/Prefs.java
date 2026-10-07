// ============================================================
// File: app/src/main/java/com/autocaller/app/Prefs.java
// Auto Caller — SharedPreferences wrapper.
//
// Provides type-safe accessors for the four primary settings
// (server URL, shared secret, device ID, enabled) and the
// "calls today" counter (auto-resets at midnight).
// ============================================================
package com.autocaller.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.TextUtils;
import android.util.Log;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public final class Prefs {

    private static final String TAG = Constants.TAG + ":Prefs";
    private static final String DAY_FMT = "yyyy-MM-dd";

    private final SharedPreferences sp;

    public Prefs(Context context) {
        this.sp = context.getApplicationContext()
                .getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE);
    }

    // ---- Server URL --------------------------------------------
    public String getServerUrl() {
        return sp.getString(Constants.KEY_SERVER_URL, "").trim();
    }
    public void setServerUrl(String url) {
        String trimmed = url == null ? "" : url.trim();
        sp.edit().putString(Constants.KEY_SERVER_URL, trimmed).apply();
    }

    // ---- Shared secret -----------------------------------------
    public String getSharedSecret() {
        return sp.getString(Constants.KEY_SHARED_SECRET, "");
    }
    public void setSharedSecret(String secret) {
        sp.edit().putString(Constants.KEY_SHARED_SECRET,
                secret == null ? "" : secret.trim()).apply();
    }

    // ---- Device ID ---------------------------------------------
    public String getDeviceId() {
        return sp.getString(Constants.KEY_DEVICE_ID, Constants.DEFAULT_DEVICE_ID);
    }
    public void setDeviceId(String id) {
        String trimmed = (id == null || id.trim().isEmpty())
                ? Constants.DEFAULT_DEVICE_ID
                : id.trim();
        sp.edit().putString(Constants.KEY_DEVICE_ID, trimmed).apply();
    }

    // ---- Master enabled toggle --------------------------------
    public boolean isEnabled() {
        return sp.getBoolean(Constants.KEY_ENABLED, false);
    }
    public void setEnabled(boolean enabled) {
        sp.edit().putBoolean(Constants.KEY_ENABLED, enabled).apply();
    }

    // ---- Calls-today counter ----------------------------------
    public synchronized int getCallsToday() {
        String today = new SimpleDateFormat(DAY_FMT, Locale.US).format(new Date());
        String storedDay = sp.getString(Constants.KEY_CALLS_DAY, "");
        if (storedDay.equals(today)) {
            return sp.getInt(Constants.KEY_CALLS_COUNT, 0);
        }
        // Day rolled over (or first run) — counter resets to zero.
        return 0;
    }

    public synchronized int incrementCallsToday() {
        String today = new SimpleDateFormat(DAY_FMT, Locale.US).format(new Date());
        String storedDay = sp.getString(Constants.KEY_CALLS_DAY, "");

        int count;
        if (storedDay.equals(today)) {
            count = sp.getInt(Constants.KEY_CALLS_COUNT, 0) + 1;
        } else {
            // New day — start fresh.
            count = 1;
            sp.edit().putString(Constants.KEY_CALLS_DAY, today).apply();
        }
        sp.edit().putInt(Constants.KEY_CALLS_COUNT, count).apply();
        Log.i(TAG, "Calls today incremented to " + count + " (day=" + today + ")");
        return count;
    }

    // ---- Convenience -------------------------------------------
    public boolean isConfigured() {
        return !TextUtils.isEmpty(getServerUrl())
                && !TextUtils.isEmpty(getSharedSecret());
    }

    public String describe() {
        return "url=" + getServerUrl()
                + " deviceId=" + getDeviceId()
                + " enabled=" + isEnabled()
                + " configured=" + isConfigured();
    }
}
