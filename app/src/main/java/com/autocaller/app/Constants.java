// ============================================================
// File: app/src/main/java/com/autocaller/app/Constants.java
// Auto Caller — application-wide constants.
//
// Centralised keys for SharedPreferences, notification IDs,
// channel IDs, intent extras and broadcast actions.
// ============================================================
package com.autocaller.app;

public final class Constants {

    private Constants() {
        // No instances.
    }

    // ---- General ------------------------------------------------
    public static final String TAG = "AutoCaller";

    // ---- SharedPreferences --------------------------------------
    public static final String PREFS_NAME = "autocaller_prefs";

    public static final String KEY_SERVER_URL    = "server_url";
    public static final String KEY_SHARED_SECRET = "shared_secret";
    public static final String KEY_DEVICE_ID     = "device_id";
    public static final String KEY_ENABLED       = "enabled";

    /** Day-of-month string (ISO yyyy-MM-dd) the counter was last reset. */
    public static final String KEY_CALLS_DAY     = "calls_day";
    /** Number of calls placed on the day stored in KEY_CALLS_DAY. */
    public static final String KEY_CALLS_COUNT   = "calls_count";

    public static final String DEFAULT_DEVICE_ID = "phone1";

    // ---- Foreground service / notifications --------------------
    public static final int NOTIFICATION_ID_SERVICE = 1001;
    public static final String CHANNEL_ID_SERVICE = "autocaller_service";

    // ---- Intent actions (broadcasts) --------------------------
    public static final String ACTION_STATUS_BROADCAST =
            "com.autocaller.app.STATUS";
    public static final String EXTRA_LAST_POLL_TS    = "last_poll_ts";
    public static final String EXTRA_CALLS_TODAY     = "calls_today";
    public static final String EXTRA_SERVICE_RUNNING = "service_running";

    // ---- Cloud relay request shape -----------------------------
    public static final String ACTION_POLL = "poll";
    public static final String ACTION_ACK  = "ack";

    public static final String FIELD_ACTION    = "action";
    public static final String FIELD_AUTH      = "auth";
    public static final String FIELD_DEVICE_ID = "deviceId";
    public static final String FIELD_COMMAND_ID = "commandId";
    public static final String FIELD_STATUS    = "status";
    public static final String FIELD_RESULT    = "result";
    public static final String FIELD_PHONE     = "phone";

    // ---- Polling fallbacks --------------------------------------
    /** Used when server does not return nextPollMs or returns invalid value. */
    public static final long DEFAULT_POLL_INTERVAL_MS = 3000L;
    /** Used on network / JSON errors to avoid hammering the server. */
    public static final long ERROR_BACKOFF_MS = 15000L;
    /** Sanity cap — never trust a server-provided interval larger than this. */
    public static final long MAX_POLL_INTERVAL_MS = 5L * 60L * 1000L; // 5 min
}
