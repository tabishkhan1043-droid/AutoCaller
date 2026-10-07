// ============================================================
// File: app/src/main/java/com/autocaller/app/CallExecutor.java
// Auto Caller — submits outgoing calls to Android Telecom.
//
// Returns a structured result that the service uses to ACK the
// command back to the relay (status "done" or "error").
// ============================================================
package com.autocaller.app;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.Context;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.telecom.TelecomManager;
import android.text.TextUtils;
import android.util.Log;

import androidx.core.content.ContextCompat;

public final class CallExecutor {

    private static final String TAG = Constants.TAG + ":CallExec";

    private final Context context;
    private final Prefs prefs;

    public CallExecutor(Context context, Prefs prefs) {
        this.context = context.getApplicationContext();
        this.prefs = prefs;
    }

    /**
     * Place a call to the given phone number.
     * Precondition: CALL_PHONE runtime permission must be granted.
     *
     * @param phone E.164 or local phone string (e.g. +923001234567)
     * @return Result with status and human-readable result string.
     */
    public Result placeCall(String phone) {
        if (TextUtils.isEmpty(phone)) {
            Logger.get().log("CallExecutor: phone empty — ignoring");
            return new Result(Status.ERROR, "");
        }
        String sanitized = sanitizePhone(phone);
        if (sanitized.isEmpty()) {
            Logger.get().log("CallExecutor: phone sanitized to empty — input=" + phone);
            return new Result(Status.ERROR, phone);
        }

        if (!hasCallPermission()) {
            Logger.get().log("CallExecutor: CALL_PHONE not granted — cannot place call");
            return new Result(Status.ERROR, sanitized);
        }

        return submitCallToTelecom(sanitized);
    }

    /** Same path used by the "Send Test Call" button in MainActivity. */
    public Result placeTestCall(String phone) {
        return placeCall(phone);
    }

    // ---- Internals ---------------------------------------------
    private boolean hasCallPermission() {
        return ContextCompat.checkSelfPermission(context,
                Manifest.permission.CALL_PHONE)
                == PackageManager.PERMISSION_GRANTED;
    }

    @SuppressLint("MissingPermission")
    private Result submitCallToTelecom(String phone) {
        try {
            TelecomManager telecom = (TelecomManager)
                    context.getSystemService(Context.TELECOM_SERVICE);
            if (telecom == null) {
                throw new IllegalStateException("Android Telecom service is unavailable");
            }

            // Submit directly to Android Telecom instead of trying to launch
            // an Activity from a background service (restricted on recent Android).
            telecom.placeCall(Uri.fromParts("tel", phone, null), new Bundle());

            int count = prefs.incrementCallsToday();
            Logger.get().log("Call request submitted to Telecom for " + phone
                    + " — calls today=" + count);
            Log.i(TAG, "AutoCaller: call submitted to Telecom → " + phone);
            return new Result(Status.DONE, phone);
        } catch (SecurityException se) {
            Logger.get().log("CallExecutor: SecurityException — " + se.getMessage());
            Log.e(TAG, "AutoCaller: SecurityException placing call", se);
            return new Result(Status.ERROR, phone);
        } catch (Exception e) {
            Logger.get().log("CallExecutor: unexpected — " + e.getMessage());
            Log.e(TAG, "AutoCaller: call failed", e);
            return new Result(Status.ERROR, phone);
        }
    }

    /** Strips whitespace, parentheses, dashes; keeps + prefix. */
    private String sanitizePhone(String raw) {
        if (raw == null) return "";
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (i == 0 && c == '+') {
                out.append(c);
            } else if (c >= '0' && c <= '9') {
                out.append(c);
            }
            // everything else (spaces, dashes, parens) is dropped
        }
        return out.toString();
    }

    // ---- Result holders ---------------------------------------
    public enum Status {
        DONE("done"),
        ERROR("error");

        private final String wire;
        Status(String s) { this.wire = s; }
        public String wire() { return wire; }
    }

    public static final class Result {
        public final Status status;
        public final String phone;
        public Result(Status status, String phone) {
            this.status = status;
            this.phone = phone;
        }
    }
}
