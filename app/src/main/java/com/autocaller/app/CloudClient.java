// ============================================================
// File: app/src/main/java/com/autocaller/app/CloudClient.java
// Auto Caller — HTTP + JSON layer for the Google Apps Script relay.
//
// All network calls are synchronous; CallerService runs them on
// a background thread. OkHttp + Gson are used for the wire layer.
// ============================================================
package com.autocaller.app;

import android.text.TextUtils;
import android.util.Log;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;

import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

public final class CloudClient {

    private static final String TAG = Constants.TAG + ":Cloud";

    public static final MediaType JSON =
            MediaType.get("application/json; charset=utf-8");

    private final Gson gson = new Gson();
    private final OkHttpClient http = new OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(20, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build();

    // ---- Poll request -------------------------------------------
    public PollResult poll(String baseUrl, String auth, String deviceId) {
        if (TextUtils.isEmpty(baseUrl)) {
            return PollResult.error("Server URL is empty");
        }
        String url = buildPollUrl(baseUrl, auth, deviceId);
        Request req = new Request.Builder()
                .url(url)
                .get()
                .header("User-Agent", "AutoCaller/1.0 (Android)")
                .header("Accept", "application/json")
                .build();

        try (Response resp = http.newCall(req).execute()) {
            String body = resp.body() != null ? resp.body().string() : "";
            if (!resp.isSuccessful()) {
                return PollResult.error("HTTP " + resp.code() + " — " + truncate(body));
            }
            return parsePoll(body);
        } catch (IOException e) {
            return PollResult.error("Network: " + e.getMessage());
        } catch (JsonSyntaxException e) {
            return PollResult.error("Invalid JSON: " + e.getMessage());
        } catch (Exception e) {
            return PollResult.error("Unexpected: " + e.getMessage());
        }
    }

    // ---- ACK request -------------------------------------------
    public boolean ack(String baseUrl, String auth, String commandId,
                       String status, String phone) {
        if (TextUtils.isEmpty(baseUrl)) {
            Log.w(TAG, "AutoCaller: ACK skipped — empty URL");
            return false;
        }

        JsonObject payload = new JsonObject();
        payload.addProperty(Constants.FIELD_ACTION, Constants.ACTION_ACK);
        payload.addProperty(Constants.FIELD_AUTH, auth == null ? "" : auth);
        payload.addProperty(Constants.FIELD_COMMAND_ID, commandId == null ? "" : commandId);
        payload.addProperty(Constants.FIELD_STATUS, status == null ? "done" : status);

        JsonObject result = new JsonObject();
        result.addProperty(Constants.FIELD_PHONE, phone == null ? "" : phone);
        payload.add(Constants.FIELD_RESULT, result);

        String json;
        try {
            json = gson.toJson(payload);
        } catch (Exception e) {
            Log.e(TAG, "AutoCaller: ACK serialization failed — " + e.getMessage());
            return false;
        }

        RequestBody body = RequestBody.create(json, JSON);
        Request req = new Request.Builder()
                .url(baseUrl)
                .post(body)
                .header("User-Agent", "AutoCaller/1.0 (Android)")
                .header("Content-Type", "application/json; charset=utf-8")
                .header("Accept", "application/json")
                .build();

        try (Response resp = http.newCall(req).execute()) {
            String respBody = resp.body() != null ? resp.body().string() : "";
            if (!resp.isSuccessful()) {
                Log.w(TAG, "AutoCaller: ACK HTTP " + resp.code()
                        + " for cmd=" + commandId);
                return false;
            }
            // Apps Script returns HTTP 200 for application-level errors, so
            // honor an explicit { "ok": false } instead of treating it as an ACK.
            if (!respBody.trim().isEmpty()) {
                try {
                    JsonObject ackResult = JsonParser.parseString(respBody).getAsJsonObject();
                    if (ackResult.has("ok") && !ackResult.get("ok").getAsBoolean()) {
                        Log.w(TAG, "AutoCaller: ACK rejected for cmd=" + commandId);
                        return false;
                    }
                } catch (Exception ignored) {
                    // A non-JSON 2xx response remains compatible with simple relays.
                }
            }
            return true;
        } catch (IOException e) {
            Log.e(TAG, "AutoCaller: ACK network failure — " + e.getMessage());
            return false;
        } catch (Exception e) {
            Log.e(TAG, "AutoCaller: ACK unexpected — " + e.getMessage());
            return false;
        }
    }

    // ---- Internals ---------------------------------------------
    private String buildPollUrl(String baseUrl, String auth, String deviceId) {
        // The base URL already has a query string (?action=poll ...) by
        // convention — Apps Script Web Apps route via path segments.
        // We append our parameters defensively.
        StringBuilder sb = new StringBuilder(baseUrl);
        char sep = baseUrl.contains("?") ? '&' : '?';
        sb.append(sep).append("action=").append(Constants.ACTION_POLL);
        sb.append('&').append("auth=").append(encode(auth));
        sb.append('&').append("deviceId=").append(encode(deviceId));
        return sb.toString();
    }

    private String encode(String s) {
        if (s == null) return "";
        return android.net.Uri.encode(s);
    }

    private String truncate(String s) {
        if (s == null) return "";
        return s.length() > 200 ? s.substring(0, 200) + "…" : s;
    }

    @SuppressWarnings("unchecked")
    private PollResult parsePoll(String body) throws JsonSyntaxException {
        if (body == null || body.trim().isEmpty()) {
            return PollResult.okEmpty();
        }
        JsonObject root = JsonParser.parseString(body).getAsJsonObject();
        boolean ok = root.has("ok") && root.get("ok").getAsBoolean();
        if (!ok) {
            String err = root.has("error")
                    ? root.get("error").getAsString()
                    : "server returned ok=false";
            return PollResult.error(err);
        }

        long nextPollMs = Constants.DEFAULT_POLL_INTERVAL_MS;
        if (root.has("nextPollMs")
                && !root.get("nextPollMs").isJsonNull()) {
            try {
                long v = root.get("nextPollMs").getAsLong();
                if (v > 0 && v <= Constants.MAX_POLL_INTERVAL_MS) {
                    nextPollMs = v;
                } else if (v > Constants.MAX_POLL_INTERVAL_MS) {
                    nextPollMs = Constants.MAX_POLL_INTERVAL_MS;
                }
            } catch (Exception ignored) { /* default */ }
        }

        List<Command> commands;
        if (!root.has("commands") || root.get("commands").isJsonNull()) {
            commands = Collections.emptyList();
        } else {
            commands = gson.fromJson(root.get("commands"),
                    new com.google.gson.reflect.TypeToken<List<Command>>() {}.getType());
            if (commands == null) commands = Collections.emptyList();
        }

        return new PollResult(true, "", commands, nextPollMs);
    }

    // ---- Result holder classes --------------------------------
    public static final class PollResult {
        public final boolean ok;
        public final String error;
        public final List<Command> commands;
        public final long nextPollMs;

        private PollResult(boolean ok, String error,
                           List<Command> commands, long nextPollMs) {
            this.ok = ok;
            this.error = error;
            this.commands = commands;
            this.nextPollMs = nextPollMs;
        }

        static PollResult okEmpty() {
            return new PollResult(true, "", Collections.emptyList(),
                    Constants.DEFAULT_POLL_INTERVAL_MS);
        }

        static PollResult error(String msg) {
            return new PollResult(false, msg, Collections.emptyList(),
                    Constants.ERROR_BACKOFF_MS);
        }
    }

    /** Shape of a single command from the relay. */
    public static final class Command {
        public String id;
        public String action;
        public Payload payload;
        public long createdAt;

        @Override public String toString() {
            return "Command{id=" + id
                    + ", action=" + action
                    + ", payload=" + (payload == null ? "null" : payload.toString())
                    + "}";
        }
    }

    public static final class Payload {
        public String phone;
        public String orderUrl;
        public boolean autoRedial;
        public int maxRedial;

        @Override public String toString() {
            return "Payload{phone=" + phone
                    + ", orderUrl=" + orderUrl
                    + ", autoRedial=" + autoRedial
                    + ", maxRedial=" + maxRedial
                    + "}";
        }
    }
}
