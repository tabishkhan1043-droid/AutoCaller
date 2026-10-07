// ============================================================
// File: app/src/main/java/com/autocaller/app/Logger.java
// Auto Caller — in-memory ring buffer of recent log events.
//
// A 50-entry circular buffer accessible from anywhere in the
// process (UI thread or service threads) and from the
// foreground service. MainActivity shows it as a dialog with
// a Copy button for debugging.
// ============================================================
package com.autocaller.app;

import android.util.Log;

import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.Date;
import java.util.Deque;
import java.util.Locale;

public final class Logger {

    private static final String TAG = Constants.TAG;
    private static final int CAPACITY = 50;

    private static final Logger INSTANCE = new Logger();

    private final Deque<String> buffer = new ArrayDeque<>(CAPACITY);
    private final SimpleDateFormat ts =
            new SimpleDateFormat("HH:mm:ss.SSS", Locale.US);

    private Logger() { /* singleton */ }

    public static Logger get() {
        return INSTANCE;
    }

    /** Append a single formatted line; trims oldest when full. */
    public synchronized void log(String message) {
        String line = ts.format(new Date()) + "  " + message;
        if (buffer.size() >= CAPACITY) {
            buffer.pollFirst();
        }
        buffer.addLast(line);
        Log.i(TAG, "AutoCaller: " + message);
    }

    public synchronized void log(String message, Throwable t) {
        log(message + " — " + (t == null ? "null" : t.getClass().getSimpleName()
                + ": " + t.getMessage()));
    }

    /** Returns newline-joined snapshot of the buffer (oldest → newest). */
    public synchronized String snapshot() {
        StringBuilder sb = new StringBuilder(Math.max(256, buffer.size() * 64));
        for (String line : buffer) {
            sb.append(line).append('\n');
        }
        return sb.toString();
    }

    public synchronized void clear() {
        buffer.clear();
        Log.i(TAG, "AutoCaller: log buffer cleared");
    }
}
