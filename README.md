# AutoCaller — Chrome Extension + Android SIM Caller

AutoCaller lets you type a phone number in a Chrome Extension and request an outgoing call from a paired Android phone's SIM.

The relay is a **Google Apps Script Web App**: no paid VPS or always-on computer is needed. Apps Script has free usage quotas, which Google can change or enforce. The phone call itself is made through the mobile carrier, so normal SIM-plan / call charges may apply.

## How it works

```text
Chrome Extension popup                 Free relay                  Android phone
(type number, press Call)  ──HTTPS──►  Google Apps Script  ◄──poll── Auto Caller app
                                                                      │
                                                                      ▼
                                                          Android Telecom → SIM call
```

1. Type a number in the extension and press **Call from my phone**. Typing alone does not place a call.
2. The extension queues one call request for the selected Device ID.
3. The Android app polls the relay every five seconds while its service is enabled.
4. Android Telecom submits the call through the phone's SIM. The app reports that the request was submitted; it cannot tell whether the other person answered.

Only one pending call is allowed per Device ID. Use a number with a country code (for example, `+923001234567`) for reliable dialing. The Android phone needs a working SIM, mobile service, internet access for the relay, and the required permission.

## 1. Set up the free relay

1. Open [script.google.com](https://script.google.com) while signed in to your Google account and create a new Apps Script project.
2. Replace the starter contents with `relay/Code.gs` from this repository and save.
3. In **Project Settings → Script properties**, add:
   - Property: `SHARED_SECRET`
   - Value: a long random secret of your choice (64 hexadecimal characters is a good format).

   Keep this secret private. The extension and Android app both use the same value. Do not put it in a public repository or share it in chat.
4. Select **Deploy → New deployment → Web app**:
   - **Execute as:** Me
   - **Who has access:** Anyone
5. Approve Google's authorization prompt, deploy, and copy the Web App URL ending in `/exec`.

The deployment must allow anonymous access so the phone and extension can reach it; the shared secret protects the relay actions. If your Google Workspace administrator does not allow **Anyone**, use a personal Google account or ask the administrator to allow Apps Script web apps. Apps Script usage remains subject to current Google quotas and may be throttled if heavily used.

When changing `Code.gs` later, create a new deployment version (or edit the deployment to use a new version). Changing the `SHARED_SECRET` script property does not require a new deployment.

## 2. Install and configure the Chrome Extension

This repository contains an unpacked Chrome Extension; it is not published in the Chrome Web Store.

1. In Chrome, open `chrome://extensions` and turn **Developer mode** on.
2. Click **Load unpacked** and select this repository's `extension/` folder.
3. Open the AutoCaller extension popup and expand **Connection & pairing**.
4. Enter the Apps Script `/exec` URL, the `SHARED_SECRET`, and a Device ID such as `phone1`.
5. Click **Save settings**, then **Test relay**. If desired, use **Generate a random secret** and copy that same value into Apps Script Script Properties and the Android app.

The extension stores these settings in Chrome's local extension storage. Its permissions are limited to local storage and the Google Apps Script hosts used by the relay.

## 3. Build and configure the Android app

### Build

Requirements: Android Studio, JDK 17, Android SDK Platform 34, and a physical Android phone for SIM-call testing.

```bash
./gradlew assembleDebug
```

The debug APK is written to `app/build/outputs/apk/debug/app-debug.apk`. You can also open this repository in Android Studio and run the `app` configuration on a connected phone.

### First-time setup on the phone

1. Install and open **Auto Caller**.
2. Grant **CALL_PHONE** and, on Android 13+, **POST_NOTIFICATIONS** when prompted.
3. Enter the same Apps Script `/exec` URL, shared secret, and Device ID used in the extension.
4. Tap **Save Settings**, then turn **Service Enabled** on.
5. Keep the persistent Auto Caller notification enabled. Allow background activity / disable battery optimization for the app if your phone manufacturer otherwise stops its polling service.
6. Check the app's status and **View Logs** if a request does not arrive.

Android may ask you to grant the battery-optimization exemption through its own Settings screen. Phone UI and permission labels vary by manufacturer.

## 4. Place a call

1. Click the AutoCaller icon in Chrome.
2. Type a number, preferably in international format.
3. Click **Call from my phone**.
4. Keep the Android phone online with the Auto Caller service enabled. The extension waits briefly for the phone to acknowledge the request and reports whether Android Telecom accepted it.

The call is made by the Android device and its carrier. A successful acknowledgement means Android accepted the call request—not that the destination connected. Carrier charges, country dialing rules, roaming, blocked numbers, and SIM settings still apply.

## Relay API

The Apps Script Web App implements the API already used by the Android app:

- `GET ?action=health&auth=...` — extension connection check.
- `POST { "action":"enqueue", "auth":"...", "phone":"+923001234567", "deviceId":"phone1" }` — add a call request.
- `GET ?action=poll&auth=...&deviceId=phone1` — Android fetches one pending request. The response sets `nextPollMs` to 5000.
- `POST { "action":"ack", "auth":"...", "commandId":"...", "status":"done", "result":{"phone":"..."} }` — Android acknowledges the request.
- `POST { "action":"cancel", "auth":"...", "commandId":"...", "deviceId":"phone1", "confirmed":true }` — explicitly clear a request after the extension warns that it may already have reached the phone.
- `GET ?action=status&auth=...&commandId=...` — extension checks the request status.

Pending commands expire after 24 hours and are not automatically replayed after the Android phone has received them, to avoid accidental duplicate calls if an ACK is lost. Recent acknowledgements are retained for up to six hours so the extension can display the result. If a request remains stuck, the extension can clear it after a warning; check the phone's call log before retrying because it may already have been dialed. The relay stores queue data in Apps Script Script Properties and does not intentionally log phone numbers.

## Project structure

```text
AutoCaller/
├── app/                         Android Java app
├── extension/                   Chrome Extension (Manifest V3)
├── relay/Code.gs                Google Apps Script serverless relay
├── relay/test-relay.cjs         Local relay smoke tests (Node.js)
├── README.md
└── gradlew
```

## Checks

```bash
node relay/test-relay.cjs
node --check extension/popup.js
./gradlew assembleDebug
```

The first two checks run locally without deploying anything. Building the Android app requires the Android SDK and may download Gradle/dependencies the first time.

## Security and limitations

- The shared secret is a bearer credential. Keep it private and use a long random value. Requests travel over HTTPS.
- The Android app stores the shared secret in local app preferences; the extension stores it in Chrome local storage. Protect access to those devices and profiles.
- The relay's pending queue and recent call status are stored in Script Properties. Do not use this as a high-volume call center; Apps Script free quotas and provider rules apply.
- The app only sends a call when a request is explicitly submitted from the extension (or when you use **Send Test Call** inside the app). Use it only for calls you are authorized to make and follow local laws and carrier terms.
- This project does not provide free telephone service. It automates a call through the Android phone's own SIM and cannot remove carrier fees.
