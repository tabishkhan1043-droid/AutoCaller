// ============================================================
// File: app/src/main/java/com/autocaller/app/MainActivity.java
// Auto Caller — single-screen UI.
//
// Lets the user enter server URL, shared secret, device ID;
// toggle the service; send test calls; restart the service;
// request permissions; view in-memory logs.
// ============================================================
package com.autocaller.app;

import android.Manifest;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.provider.Settings;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.text.format.DateUtils;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

public final class MainActivity extends AppCompatActivity {

    private static final int REQ_PERMS = 0xA1;
    private static final int REQ_BATTERY = 0xA2;

    private Prefs prefs;
    private CallExecutor executor;

    // ---- UI refs -------------------------------------------------
    private TextView tvServiceStatus;
    private TextView tvLastPoll;
    private TextView tvCallsToday;
    private EditText etServerUrl;
    private EditText etSecret;
    private EditText etDeviceId;
    private Button btnToggleSecret;
    private Button btnSave;
    private Switch swEnabled;
    private Button btnTestCall;
    private Button btnRestart;
    private TextView tvPermStatus;
    private Button btnPerms;
    private Button btnLogs;
    private Button btnBattery;

    private boolean secretVisible = false;
    private long lastPollTs = 0L;

    // ---- Broadcast receiver for service-status updates ---------
    private final BroadcastReceiver statusReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent == null) return;
            if (!Constants.ACTION_STATUS_BROADCAST.equals(intent.getAction())) return;
            lastPollTs = intent.getLongExtra(Constants.EXTRA_LAST_POLL_TS, 0L);
            int calls = intent.getIntExtra(Constants.EXTRA_CALLS_TODAY, 0);
            boolean running = intent.getBooleanExtra(Constants.EXTRA_SERVICE_RUNNING, false);
            renderStatus(running, lastPollTs, calls);
        }
    };

    // ---- Lifecycle ---------------------------------------------
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        prefs = new Prefs(this);
        executor = new CallExecutor(this, prefs);

        bindViews();
        loadPrefsIntoFields();
        wireListeners();
        refreshPermissionStatus();
        renderStatus(prefs.isEnabled() && prefs.isConfigured(), 0L, prefs.getCallsToday());

        // Battery optimization exemption — request once on first launch.
        maybeRequestBatteryExemption();

        // Ensure service is running if enabled & configured.
        if (prefs.isEnabled() && prefs.isConfigured()) {
            ensureServiceRunning();
        }
    }

    @Override
    protected void onStart() {
        super.onStart();
        IntentFilter f = new IntentFilter(Constants.ACTION_STATUS_BROADCAST);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(statusReceiver, f,
                    Context.RECEIVER_NOT_EXPORTED);
        } else {
            // On pre-13 we still want the package-private broadcast.
            registerReceiver(statusReceiver, f);
        }
    }

    @Override
    protected void onStop() {
        try { unregisterReceiver(statusReceiver); } catch (Exception ignored) {}
        super.onStop();
    }

    // ---- View binding ------------------------------------------
    private void bindViews() {
        tvServiceStatus = findViewById(R.id.tvServiceStatus);
        tvLastPoll     = findViewById(R.id.tvLastPoll);
        tvCallsToday   = findViewById(R.id.tvCallsToday);
        etServerUrl    = findViewById(R.id.etServerUrl);
        etSecret       = findViewById(R.id.etSecret);
        etDeviceId     = findViewById(R.id.etDeviceId);
        btnToggleSecret = findViewById(R.id.btnToggleSecret);
        btnSave        = findViewById(R.id.btnSave);
        swEnabled      = findViewById(R.id.swEnabled);
        btnTestCall    = findViewById(R.id.btnTestCall);
        btnRestart     = findViewById(R.id.btnRestart);
        tvPermStatus   = findViewById(R.id.tvPermStatus);
        btnPerms       = findViewById(R.id.btnPerms);
        btnLogs        = findViewById(R.id.btnLogs);
        btnBattery     = findViewById(R.id.btnBattery);
    }

    // ---- Load prefs into inputs --------------------------------
    private void loadPrefsIntoFields() {
        etServerUrl.setText(prefs.getServerUrl());
        etSecret.setText(prefs.getSharedSecret());
        etDeviceId.setText(prefs.getDeviceId());
        swEnabled.setChecked(prefs.isEnabled());

        // Append a small text-change listener so the user sees their edits
        // reflected in the status preview before saving.
        TextWatcher w = new SimpleTextWatcher() {
            @Override public void afterTextChanged(Editable s) {
                renderStatus(prefs.isEnabled() && prefs.isConfigured(),
                        lastPollTs, prefs.getCallsToday());
            }
        };
        etServerUrl.addTextChangedListener(w);
        etSecret.addTextChangedListener(w);
    }

    // ---- Listeners ---------------------------------------------
    private void wireListeners() {
        btnToggleSecret.setOnClickListener(v -> {
            secretVisible = !secretVisible;
            applySecretVisibility();
        });

        btnSave.setOnClickListener(v -> onSave());

        swEnabled.setOnCheckedChangeListener((button, isChecked) -> {
            // Persist the toggle immediately so boot receiver can pick it up.
            prefs.setEnabled(isChecked);
            if (isChecked) {
                if (prefs.isConfigured()) {
                    ensureServiceRunning();
                } else {
                    toast(getString(R.string.toast_save_first));
                }
            } else {
                stopService(new Intent(this, CallerService.class));
                renderStatus(false, lastPollTs, prefs.getCallsToday());
            }
        });

        btnTestCall.setOnClickListener(v -> onTestCall());
        btnRestart.setOnClickListener(v -> onRestartService());
        btnPerms.setOnClickListener(v -> onRequestPermissions());
        btnLogs.setOnClickListener(v -> onShowLogs());
        btnBattery.setOnClickListener(v -> openBatterySettings());
    }

    // ---- Save ---------------------------------------------------
    private void onSave() {
        prefs.setServerUrl(etServerUrl.getText().toString());
        prefs.setSharedSecret(etSecret.getText().toString());
        prefs.setDeviceId(etDeviceId.getText().toString());
        prefs.setEnabled(swEnabled.isChecked());

        Logger.get().log("Settings saved — " + prefs.describe());
        toast(getString(R.string.toast_saved));

        if (swEnabled.isChecked() && prefs.isConfigured()) {
            ensureServiceRunning();
        } else if (swEnabled.isChecked() && !prefs.isConfigured()) {
            toast(getString(R.string.toast_save_first));
        } else {
            stopService(new Intent(this, CallerService.class));
        }
        renderStatus(prefs.isEnabled() && prefs.isConfigured(),
                lastPollTs, prefs.getCallsToday());
    }

    // ---- Test call --------------------------------------------
    private void onTestCall() {
        if (!hasCallPermission()) {
            toast(getString(R.string.toast_call_perm_required));
            onRequestPermissions();
            return;
        }
        // Prompt for phone number via an EditText inside an AlertDialog.
        final EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_PHONE);
        input.setHint(R.string.hint_test_call_phone);
        new AlertDialog.Builder(this)
                .setTitle(R.string.dialog_test_call_title)
                .setView(input)
                .setPositiveButton(R.string.btn_call,
                        (d, w) -> {
                            String phone = input.getText().toString().trim();
                            if (phone.isEmpty()) {
                                toast(getString(R.string.toast_phone_empty));
                                return;
                            }
                            CallExecutor.Result r = executor.placeTestCall(phone);
                            toast(r.status == CallExecutor.Status.DONE
                                    ? getString(R.string.toast_call_done, r.phone)
                                    : getString(R.string.toast_call_failed, r.phone));
                        })
                .setNegativeButton(R.string.btn_cancel, null)
                .show();
    }

    // ---- Restart service --------------------------------------
    private void onRestartService() {
        Logger.get().log("User tapped Restart Service");
        stopService(new Intent(this, CallerService.class));
        if (prefs.isEnabled() && prefs.isConfigured()) {
            ensureServiceRunning();
            toast(getString(R.string.toast_service_restarted));
        } else {
            toast(getString(R.string.toast_save_first));
        }
    }

    // ---- Permissions ------------------------------------------
    private void onRequestPermissions() {
        String[] needed = neededPermissions();
        if (needed.length == 0) {
            toast(getString(R.string.toast_perms_all_granted));
            return;
        }
        ActivityCompat.requestPermissions(this, needed, REQ_PERMS);
    }

    private String[] neededPermissions() {
        java.util.List<String> list = new java.util.ArrayList<>();
        if (!hasPermission(Manifest.permission.CALL_PHONE)) {
            list.add(Manifest.permission.CALL_PHONE);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && !hasPermission(Manifest.permission.POST_NOTIFICATIONS)) {
            list.add(Manifest.permission.POST_NOTIFICATIONS);
        }
        return list.toArray(new String[0]);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode,
                                           @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        refreshPermissionStatus();
        if (requestCode == REQ_PERMS) {
            boolean allGranted = true;
            for (int r : grantResults) {
                if (r != PackageManager.PERMISSION_GRANTED) {
                    allGranted = false;
                    break;
                }
            }
            toast(allGranted
                    ? getString(R.string.toast_perms_granted)
                    : getString(R.string.toast_perms_denied));
        }
    }

    private boolean hasPermission(String perm) {
        return ContextCompat.checkSelfPermission(this, perm)
                == PackageManager.PERMISSION_GRANTED;
    }

    private boolean hasCallPermission() {
        return hasPermission(Manifest.permission.CALL_PHONE);
    }

    private void refreshPermissionStatus() {
        StringBuilder sb = new StringBuilder();
        sb.append(getString(R.string.perm_call_phone,
                hasPermission(Manifest.permission.CALL_PHONE)
                        ? getString(R.string.perm_granted)
                        : getString(R.string.perm_missing)));
        sb.append('\n');
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            sb.append(getString(R.string.perm_post_notif,
                    hasPermission(Manifest.permission.POST_NOTIFICATIONS)
                            ? getString(R.string.perm_granted)
                            : getString(R.string.perm_missing)));
            sb.append('\n');
        }
        sb.append(getString(R.string.perm_battery,
                isBatteryExempted()
                        ? getString(R.string.perm_granted)
                        : getString(R.string.perm_missing)));
        tvPermStatus.setText(sb.toString());
    }

    // ---- Battery optimization ----------------------------------
    private boolean isBatteryExempted() {
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        if (pm == null) return false;
        return pm.isIgnoringBatteryOptimizations(getPackageName());
    }

    private void maybeRequestBatteryExemption() {
        if (isBatteryExempted()) return;
        boolean askedBefore = prefs.isEnabled() /* crude flag */
                || getSharedPreferences("autocaller_internal", MODE_PRIVATE)
                    .getBoolean("battery_asked", false);
        if (askedBefore) {
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle(R.string.battery_dialog_title)
                .setMessage(R.string.battery_dialog_msg)
                .setPositiveButton(R.string.btn_grant,
                        (d, w) -> {
                            try {
                                Intent it = new Intent(
                                        Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
                                it.setData(Uri.parse("package:" + getPackageName()));
                                startActivityForResult(it, REQ_BATTERY);
                            } catch (Exception e) {
                                Logger.get().log("Battery dialog intent failed — "
                                        + e.getMessage());
                                openBatterySettings();
                            }
                        })
                .setNegativeButton(R.string.btn_later, null)
                .show();
        getSharedPreferences("autocaller_internal", MODE_PRIVATE)
                .edit().putBoolean("battery_asked", true).apply();
    }

    private void openBatterySettings() {
        try {
            Intent it = new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS);
            startActivity(it);
        } catch (Exception e) {
            // Fallback to app-details settings page.
            try {
                Intent it = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
                it.setData(Uri.parse("package:" + getPackageName()));
                startActivity(it);
            } catch (Exception ignored) { /* truly nothing more we can do */ }
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_BATTERY) {
            refreshPermissionStatus();
        }
    }

    // ---- Service control ---------------------------------------
    private void ensureServiceRunning() {
        Intent it = new Intent(this, CallerService.class);
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ContextCompat.startForegroundService(this, it);
            } else {
                startService(it);
            }
        } catch (Exception e) {
            Logger.get().log("ensureServiceRunning failed — " + e.getMessage());
        }
    }

    // ---- Status render ----------------------------------------
    private void renderStatus(boolean running, long lastPoll, int calls) {
        tvServiceStatus.setText(running
                ? getString(R.string.status_running)
                : getString(R.string.status_stopped));
        tvServiceStatus.setTextColor(ContextCompat.getColor(this,
                running ? R.color.status_ok : R.color.status_bad));
        tvCallsToday.setText(getString(R.string.calls_today, calls));
        if (lastPoll > 0) {
            CharSequence ago = DateUtils.getRelativeTimeSpanString(
                    lastPoll,
                    System.currentTimeMillis(),
                    DateUtils.SECOND_IN_MILLIS,
                    DateUtils.FORMAT_ABBREV_RELATIVE);
            tvLastPoll.setText(getString(R.string.last_poll, ago));
        } else {
            tvLastPoll.setText(getString(R.string.last_poll,
                    getString(R.string.never)));
        }
    }

    // ---- Logs dialog ------------------------------------------
    private void onShowLogs() {
        String logs = Logger.get().snapshot();
        if (logs.isEmpty()) logs = getString(R.string.logs_empty);
        // Use a TextView so the user can scroll and select.
        TextView tv = new TextView(this);
        tv.setText(logs);
        tv.setTextIsSelectable(true);
        tv.setPadding(48, 32, 48, 32);
        tv.setTextSize(13);
        new AlertDialog.Builder(this)
                .setTitle(R.string.dialog_logs_title)
                .setView(tv)
                .setPositiveButton(R.string.btn_copy,
                        (d, w) -> copyToClipboard(logs))
                .setNeutralButton(R.string.btn_clear,
                        (d, w) -> Logger.get().clear())
                .setNegativeButton(R.string.btn_close, null)
                .show();
    }

    private void copyToClipboard(String text) {
        try {
            int sdk = Build.VERSION.SDK_INT;
            android.content.ClipboardManager cm = (android.content.ClipboardManager)
                    getSystemService(CLIPBOARD_SERVICE);
            if (cm == null) return;
            android.content.ClipData clip = android.content.ClipData
                    .newPlainText("AutoCaller Logs", text);
            cm.setPrimaryClip(clip);
            toast(getString(R.string.toast_logs_copied));
        } catch (Exception e) {
            Logger.get().log("Clipboard copy failed — " + e.getMessage());
        }
    }

    // ---- Misc helpers ------------------------------------------
    private void applySecretVisibility() {
        int sel = etSecret.getSelectionEnd();
        if (secretVisible) {
            etSecret.setInputType(InputType.TYPE_CLASS_TEXT
                    | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD);
            btnToggleSecret.setText(R.string.btn_hide);
        } else {
            etSecret.setInputType(InputType.TYPE_CLASS_TEXT
                    | InputType.TYPE_TEXT_VARIATION_PASSWORD);
            btnToggleSecret.setText(R.string.btn_show);
        }
        if (sel >= 0 && sel <= etSecret.getText().length()) {
            try {
                etSecret.setSelection(sel);
            } catch (Exception ignored) { /* bounds changed; let default */ }
        }
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }

    /** Sub-interface so we don't have to implement all three TextWatcher methods inline. */
    private static abstract class SimpleTextWatcher implements TextWatcher {
        @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
        @Override public void onTextChanged(CharSequence s, int a, int b, int c) { }
    }
}
