# Auto Caller — Android Background Call Service

A companion Android app for the Shopify Auto Caller system. A Chrome
Extension on the user's PC sends a "call this number" command to a
Google Apps Script Web App (cloud relay). This Android app polls the
relay, retrieves the command, and places the call from the device's
own SIM card.

> This app is **not** a user-facing dialer. It runs as a foreground
> service that listens for commands and executes them. After initial
> setup, the user never needs to open the app.

---

## System Architecture

```
┌───────────────────────┐        ┌────────────────────────┐       ┌──────────────────────┐
│  Chrome Extension     │   POST │ Google Apps Script     │  GET  │  Auto Caller (this   │
│  (Shopify Admin)      ├──────►│  Web App (relay)       │◄──────┤  Android app)        │
└───────────────────────┘        └────────────────────────┘       └──────────┬───────────┘
                                                                          │ ACTION_CALL
                                                                          ▼
                                                                ┌─────────────────┐
                                                                │ Android dialer  │
                                                                │ (SIM call)      │
                                                                └─────────────────┘
```

1. Chrome Extension extracts phone number from Shopify order page.
2. Extension POSTs to Apps Script Web App.
3. Apps Script enqueues the command.
4. This Android app polls (`GET ?action=poll`).
5. App receives the command, fires `ACTION_CALL` with `tel:{phone}`.
6. App ACKs back (`POST action=ack`) so the relay clears the queue.

---

## Tech Stack

- **Language**: Java 11 (source/target compatibility)
- **Min SDK**: 26 (Android 8.0)
- **Target SDK**: 34 (Android 14)
- **Build**: Gradle 8.0 + AGP 8.1.4
- **Libraries**:
  - `androidx.appcompat:appcompat:1.6.1`
  - `androidx.core:core:1.12.0`
  - `com.google.android.material:material:1.11.0` (required for Material3 theme)
  - `com.squareup.okhttp3:okhttp:4.12.0`
  - `com.google.code.gson:gson:2.10.1`

No Kotlin source. No third-party code beyond the libraries above.

---

## Cloud Relay API

### Poll — `GET`

```
{BASE_URL}?action=poll&auth={SECRET}&deviceId={DEVICE_ID}
```

Response:

```json
{
  "ok": true,
  "commands": [
    {
      "id": "uuid-string",
      "action": "call",
      "payload": {
        "phone": "+923001234567",
        "orderUrl": "https://admin.shopify.com/store/x/orders/123",
        "autoRedial": false,
        "maxRedial": 0
      },
      "createdAt": 1759999999999
    }
  ],
  "nextPollMs": 3000,
  "boost": true,
  "serverTime": 1759999999999
}
```

The app **honors `nextPollMs`** between polls. If the field is absent
or invalid, falls back to 3s. If a network error occurs, backs off to
15s.

### Ack — `POST`

```
POST {BASE_URL}
Content-Type: application/json

{
  "action": "ack",
  "auth": "{SECRET}",
  "commandId": "uuid-string",
  "status": "done",        // or "error"
  "result": { "phone": "+923001234567" }
}
```

The app ACKs each command after execution whether the call succeeded
(`done`) or failed (`error`).

---

## Project Structure

```
AutoCaller/
├── settings.gradle
├── build.gradle                    (project-level)
├── gradle.properties
├── README.md
├── .gitignore
└── app/
    ├── build.gradle                (app-level)
    ├── proguard-rules.pro
    └── src/main/
        ├── AndroidManifest.xml
        ├── java/com/autocaller/app/
        │   ├── Constants.java          — keys, IDs, intervals
        │   ├── Prefs.java              — SharedPreferences wrapper
        │   ├── Logger.java             — 50-event ring buffer
        │   ├── CloudClient.java        — OkHttp + Gson poll/ack
        │   ├── CallExecutor.java       — ACTION_CALL intent logic
        │   ├── CallerService.java       — foreground service + poller
        │   ├── BootReceiver.java        — restart on device boot
        │   └── MainActivity.java        — single-screen UI
        └── res/
            ├── layout/activity_main.xml
            ├── values/{strings,colors,themes}.xml
            ├── drawable/{ic_notification,
            │              ic_launcher_foreground,
            │              ic_launcher_background}.xml
            ├── mipmap-anydpi-v26/{ic_launcher,
            │                       ic_launcher_round}.xml
            └── xml/{backup_rules,data_extraction_rules}.xml
```

---

## Build & Install

### Requirements

- Android Studio **Iguana** (2023.2.1) or newer
- JDK 17 (Android Studio bundles this; no manual install needed)
- Android SDK Platform 34 (installed via SDK Manager)
- Build Tools 34.0.0
- A physical Android device (min API 26) for testing — emulator
  cannot place real calls

### Build

1. Open Android Studio → **Open** → select the `AutoCaller/` folder.
2. Wait for Gradle sync to finish.
3. Plug in your Android device, enable **USB Debugging** in
   Developer Options.
4. Select the device in the toolbar dropdown.
5. Click ▶ **Run** (or `Shift+F10`).

The first build will take ~1-2 minutes (Gradle download + dependency
resolution).

### Build a release APK

```bash
cd AutoCaller/
./gradlew assembleRelease
# APK at app/build/outputs/apk/release/app-release.apk
# (signed with debug key by default — see app/build.gradle signingConfig)
```

```bash
./gradlew assembleDebug
# APK at app/build/outputs/apk/debug/app-debug.apk
```

---

## First-time Setup (on the phone)

1. **Install the app** — see Build section.
2. **Open Auto Caller**.
3. The first-launch dialog asks for **battery-optimization
   exemption**. Tap **Grant** and confirm on the system prompt. This
   is critical for the polling service to survive Doze mode.
4. Tap **Request Permissions** and grant:
   - **CALL_PHONE** (required to place calls)
   - **POST_NOTIFICATIONS** (Android 13+; required for the foreground
     notification)
5. In the **Server URL** field, paste your Google Apps Script Web App
   URL. It looks like:
   `https://script.google.com/macros/s/AKfycby.../exec`
6. In **Shared Secret**, paste the same secret string the Chrome
   Extension / Apps Script uses.
7. Set **Device ID** to a unique label per phone (e.g. `phone1`).
8. Tap **Save Settings**.
9. Toggle **Service Enabled** to ON.
10. (Optional) Tap **Send Test Call** to verify end-to-end.

After step 9, the foreground notification "Auto Caller • Running"
appears. The app polls the relay every `nextPollMs` milliseconds (or
3s default). You can close the app — the service keeps running.

---

## Day-to-day operation

- **No user interaction needed**. The polling service runs in the
  background.
- The persistent notification ("Auto Caller • Running") must remain
  visible — that is the foreground-service requirement. If you swipe
  it away, the system will eventually kill the service.
- **After device reboot**, the service auto-starts if you had it
  enabled (via the BootReceiver).
- To check what the app is doing, open it and tap **View Logs (last
  50)** — a dialog with the last 50 events appears, with a **Copy
  Logs** button for sharing.

---

## Diagnostics

| Symptom | Likely cause | Fix |
|---|---|---|
| Notification shows "Not configured" | Server URL or secret empty | Open app, fill fields, Save |
| No calls placed | `CALL_PHONE` permission denied | Tap "Request Permissions" |
| Service dies after a few minutes | Battery optimization not exempt | Tap "Battery Settings", find Auto Caller, allow background |
| Poll error: "HTTP 401/403" | Wrong shared secret | Verify secret matches Apps Script |
| Poll error: "Network" | No internet on phone | Check Wi-Fi / mobile data |
| Calls today counter stuck at 0 | Service not running | Verify the persistent notification is visible |

---

## Code-Quality Notes

- All network calls run on a background thread (the service's
  "AutoCaller-Poller" thread). UI thread is never blocked.
- Every network call is wrapped in try/catch and logs to logcat
  with tag `AutoCaller` (format: `AutoCaller: <message>`).
- No hardcoded URLs or secrets — all read from SharedPreferences.
- The polling loop **never crashes** on:
  - No network (logs + sleeps 15s + retries)
  - Invalid JSON (logs + sleeps 15s + retries)
  - Missing fields in command payload (logs + ACKs with status=error)
  - Empty phone field (logs + ACKs with status=error)
- The in-memory log buffer holds the last 50 events and is
  accessible via the **View Logs** button. A **Copy Logs** button is
  in the dialog.

---

## Security Notes

- The `shared_secret` is stored in plain SharedPreferences. For
  high-security deployments, replace `Prefs.java` with
  `EncryptedSharedPreferences` from `androidx.security:security-crypto`.
- HTTP (cleartext) traffic is allowed via
  `android:usesCleartextTraffic="true"`. Apps Script uses HTTPS by
  default, so this is only a fallback for self-hosted relays.
- The `CALL_PHONE` permission is granted at runtime; the app cannot
  place calls without explicit user consent.

---

## Compatibility

| Android version | API | Status |
|---|---|---|
| 8.0 – 8.1 | 26-27 | ✅ Fully supported (foreground service + wake lock) |
| 9 | 28 | ✅ |
| 10 | 29 | ✅ (battery exemption recommended) |
| 11 | 30 | ✅ |
| 12 | 31 | ✅ (POST_NOTIFICATIONS not yet enforced) |
| 13 | 33 | ✅ (POST_NOTIFICATIONS required) |
| 14 | 34 | ✅ (FOREGROUND_SERVICE_PHONE_CALL type required) |

---

## License

Private / proprietary. Companion to the Shopify Auto Caller system.
Not for redistribution.
