// ============================================================
// File: app/src/main/java/com/autocaller/app/CallerService.java
// Auto Caller — foreground service that polls the relay and
// submits calls through Android Telecom.
//
// Lifecycle:
//   onStartCommand → startForeground → spawn Poller thread
//   Poller thread loops until enabled=false or service stopped
//   onDestroy → wake-lock release, broadcast final status
// ============================================================
package com.autocaller.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.IBinder;
import android.os.PowerManager;
import android.text.TextUtils;
import android.util.Log;

import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;

import java.util.concurrent.atomic.AtomicBoolean;

public final class CallerService extends Service {

    private static final String TAG = Constants.TAG + ":Service";

    private Prefs prefs;
    private CloudClient cloud;
    private CallExecutor executor;
    private PowerManager.WakeLock wakeLock;

    private final AtomicBoolean running = new AtomicBoolean(false);
    private Thread pollerThread;
    private volatile long lastPollTs = 0L;
    private volatile boolean lastPollOk = false;

    // ---- Service lifecycle ------------------------------------
    @Override
    public void onCreate() {
        super.onCreate();
        prefs = new Prefs(this);
        cloud = new CloudClient();
        executor = new CallExecutor(this, prefs);
        createNotificationChannel();
        acquireWakeLock();
        Logger.get().log("CallerService created — " + prefs.describe());
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.i(TAG, "AutoCaller: onStartCommand");
        startForeground(Constants.NOTIFICATION_ID_SERVICE,
                buildNotification());

        if (!running.compareAndSet(false, true)) {
            // Already running — just refresh notification.
            return START_STICKY;
        }
        startPoller();
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null; // not bindable
    }

    @Override
    public void onDestroy() {
        running.set(false);
        if (pollerThread != null) {
            pollerThread.interrupt();
            pollerThread = null;
        }
        releaseWakeLock();
        Logger.get().log("CallerService destroyed");
        super.onDestroy();
    }

    // ---- Poller thread ----------------------------------------
    private void startPoller() {
        pollerThread = new Thread(this::pollLoop,
                "AutoCaller-Poller");
        pollerThread.setDaemon(true);
        pollerThread.start();
    }

    private void pollLoop() {
        Log.i(TAG, "AutoCaller: poller started");
        Logger.get().log("Poller thread started");

        while (running.get() && !Thread.currentThread().isInterrupted()) {
            if (!prefs.isEnabled()) {
                Logger.get().log("Service disabled in prefs — stopping poller");
                updateNotification(false);
                broadcastStatus();
                stopSelf();
                return;
            }

            if (!prefs.isConfigured()) {
                Logger.get().log("Not configured — waiting 15s before retry");
                updateNotification(false);
                broadcastStatus();
                sleepQuiet(Constants.ERROR_BACKOFF_MS);
                continue;
            }

            long nextSleep = doOnePollCycle();
            broadcastStatus();
            sleepQuiet(nextSleep);
        }
    }

    /** Performs one poll + execute + ack round. Returns ms to wait. */
    private long doOnePollCycle() {
        String url = prefs.getServerUrl();
        String auth = prefs.getSharedSecret();
        String deviceId = prefs.getDeviceId();

        CloudClient.PollResult result = cloud.poll(url, auth, deviceId);
        lastPollTs = System.currentTimeMillis();
        lastPollOk = result.ok;

        if (!result.ok) {
            Logger.get().log("Poll error: " + result.error);
            Log.w(TAG, "AutoCaller: poll error — " + result.error);
            updateNotification(false);
            return Constants.ERROR_BACKOFF_MS;
        }

        Logger.get().log("Poll ok — " + result.commands.size()
                + " command(s), nextPollMs=" + result.nextPollMs);

        // Process commands sequentially — we never place more than one
        // call at a time and the device's telephony stack can only
        // handle one call anyway.
        for (CloudClient.Command cmd : result.commands) {
            try {
                processCommand(cmd);
            } catch (Throwable t) {
                Logger.get().log("Command threw: " + cmd.id + " — " + t.getMessage());
                Log.e(TAG, "AutoCaller: command crashed", t);
                // Still try to ACK as error so the relay clears it.
                sendAck(cmd.id, CallExecutor.Status.ERROR, "");
            }
        }

        updateNotification(true);
        return result.nextPollMs;
    }

    private void processCommand(CloudClient.Command cmd) {
        if (cmd == null || TextUtils.isEmpty(cmd.id)) {
            Logger.get().log("Skipping malformed command (no id)");
            return;
        }
        if (cmd.payload == null || TextUtils.isEmpty(cmd.payload.phone)) {
            Logger.get().log("Command " + cmd.id + " has no phone — ACK error");
            sendAck(cmd.id, CallExecutor.Status.ERROR, "");
            return;
        }
        if (!"call".equalsIgnoreCase(cmd.action)) {
            Logger.get().log("Command " + cmd.id + " action '" + cmd.action
                    + "' not supported — ACK error");
            sendAck(cmd.id, CallExecutor.Status.ERROR, cmd.payload.phone);
            return;
        }

        Logger.get().log("Executing call command " + cmd.id
                + " → " + cmd.payload.phone);
        CallExecutor.Result r = executor.placeCall(cmd.payload.phone);
        sendAck(cmd.id, r.status, r.phone);
    }

    private void sendAck(String commandId, CallExecutor.Status status, String phone) {
        String url = prefs.getServerUrl();
        String auth = prefs.getSharedSecret();
        boolean ok = cloud.ack(url, auth, commandId, status.wire(), phone);
        Logger.get().log("ACK " + commandId + " status=" + status.wire()
                + (ok ? " ok" : " FAILED"));
    }

    // ---- Status broadcast -------------------------------------
    private void broadcastStatus() {
        Intent it = new Intent(Constants.ACTION_STATUS_BROADCAST);
        it.setPackage(getPackageName());
        it.putExtra(Constants.EXTRA_LAST_POLL_TS, lastPollTs);
        it.putExtra(Constants.EXTRA_CALLS_TODAY, prefs.getCallsToday());
        it.putExtra(Constants.EXTRA_SERVICE_RUNNING, running.get());
        sendBroadcast(it);
    }

    // ---- Notification -----------------------------------------
    private Notification buildNotification() {
        Intent openIntent = new Intent(this, MainActivity.class)
                .setAction(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_LAUNCHER)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                        | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent pi = PendingIntent.getActivity(this, 0, openIntent,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

        boolean configured = prefs.isConfigured();
        String title = getString(R.string.notif_title);
        String text = configured
                ? (lastPollOk
                    ? getString(R.string.notif_text_running)
                    : getString(R.string.notif_text_running))
                : getString(R.string.notif_text_not_configured);

        return new NotificationCompat.Builder(this, Constants.CHANNEL_ID_SERVICE)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(title)
                .setContentText(text)
                .setOngoing(true)
                .setContentIntent(pi)
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setShowWhen(false)
                .build();
    }

    private void updateNotification(boolean ok) {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm == null) return;
        Notification n = buildNotification();
        nm.notify(Constants.NOTIFICATION_ID_SERVICE, n);
    }

    private void createNotificationChannel() {
        NotificationManager nm =
                (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm == null) return;
        if (nm.getNotificationChannel(Constants.CHANNEL_ID_SERVICE) != null) return;
        NotificationChannel ch = new NotificationChannel(
                Constants.CHANNEL_ID_SERVICE,
                getString(R.string.notif_channel_name),
                NotificationManager.IMPORTANCE_LOW);
        ch.setDescription(getString(R.string.notif_channel_desc));
        ch.setShowBadge(false);
        ch.enableVibration(false);
        ch.enableLights(false);
        nm.createNotificationChannel(ch);
    }

    // ---- WakeLock ---------------------------------------------
    @SuppressWarnings("deprecation")
    private void acquireWakeLock() {
        try {
            PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
            if (pm == null) return;
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,
                    "AutoCaller:Poller");
            wakeLock.setReferenceCounted(false);
            wakeLock.acquire(/* 24 hours cap */ 24L * 60L * 60L * 1000L);
            Logger.get().log("WakeLock acquired (PARTIAL_WAKE_LOCK)");
        } catch (Exception e) {
            Logger.get().log("WakeLock acquire failed — " + e.getMessage());
            Log.e(TAG, "AutoCaller: wakelock failed", e);
        }
    }

    private void releaseWakeLock() {
        if (wakeLock != null && wakeLock.isHeld()) {
            try {
                wakeLock.release();
                Logger.get().log("WakeLock released");
            } catch (Exception ignored) { /* noop */ }
        }
        wakeLock = null;
    }

    // ---- Misc -------------------------------------------------
    private void sleepQuiet(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }

    // ---- Boot-time starter helper -----------------------------
    public static void startIfEnabled(Context context) {
        Prefs p = new Prefs(context);
        if (p.isEnabled() && p.isConfigured()) {
            Logger.get().log("Starting CallerService (enabled=true on boot)");
            Intent it = new Intent(context, CallerService.class);
            ContextCompat.startForegroundService(context, it);
        } else {
            Log.i(TAG, "AutoCaller: not starting service — "
                    + (p.isEnabled() ? "not configured" : "disabled"));
        }
    }
}
