const DEFAULT_SETTINGS = {
  serverUrl: "",
  sharedSecret: "",
  deviceId: "phone1"
};

const STATUS_POLL_MS = 2500;
const STATUS_WAIT_ATTEMPTS = 10;

const $ = (id) => document.getElementById(id);

const phoneInput = $("phoneNumber");
const callForm = $("callForm");
const callButton = $("callButton");
const feedback = $("feedback");
const cancelButton = $("cancelRequest");
const badge = $("connectionBadge");
const serverUrlInput = $("serverUrl");
const secretInput = $("sharedSecret");
const deviceIdInput = $("deviceId");
const saveButton = $("saveSettings");
const testButton = $("testConnection");
const toggleSecretButton = $("toggleSecret");
const generateSecretButton = $("generateSecret");

let currentSettings = { ...DEFAULT_SETTINGS };
let busy = false;
let settingsDirty = false;
let activeRequest = null;

function storageGet(defaults) {
  return new Promise((resolve, reject) => {
    chrome.storage.local.get(defaults, (items) => {
      const error = chrome.runtime.lastError;
      if (error) reject(new Error(error.message));
      else resolve(items);
    });
  });
}

function storageSet(items) {
  return new Promise((resolve, reject) => {
    chrome.storage.local.set(items, () => {
      const error = chrome.runtime.lastError;
      if (error) reject(new Error(error.message));
      else resolve();
    });
  });
}

async function persistActiveRequest(request) {
  const value = request ? {
    commandId: request.commandId,
    phone: request.phone,
    settings: request.settings
  } : null;
  try {
    await storageSet({ activeRequest: value });
  } catch (_) {
    // The request still runs; persistence only restores popup status after closing.
  }
}

async function clearActiveRequest() {
  activeRequest = null;
  cancelButton.hidden = true;
  await persistActiveRequest(null);
  refreshUi();
}

function setFeedback(message, kind = "pending") {
  feedback.textContent = message;
  feedback.dataset.kind = kind;
  feedback.hidden = !message;
}

function setBusy(value) {
  busy = value;
  callButton.disabled = value || settingsDirty || !hasValidSavedSettings() || Boolean(activeRequest);
  saveButton.disabled = value;
  testButton.disabled = value;
  callButton.querySelector("span:last-child").textContent = value
    ? "Please wait…"
    : "Call from my phone";
}

function formSettings() {
  return {
    serverUrl: normalizeServerUrl(serverUrlInput.value),
    sharedSecret: secretInput.value.trim(),
    deviceId: normalizeDeviceId(deviceIdInput.value)
  };
}

function normalizeServerUrl(value) {
  let url;
  try {
    url = new URL(String(value || "").trim());
  } catch (_) {
    throw new Error("Enter the deployed Google Apps Script URL ending in /exec.");
  }
  if (url.protocol !== "https:" || url.hostname !== "script.google.com" || !url.pathname.endsWith("/exec")) {
    throw new Error("The relay URL must be an HTTPS Google Apps Script /exec URL.");
  }
  url.search = "";
  url.hash = "";
  return url.toString().replace(/\/$/, "");
}

function normalizeDeviceId(value) {
  const id = String(value || "").trim();
  if (!/^[A-Za-z0-9._-]{1,64}$/.test(id)) {
    throw new Error("Device ID must be 1–64 letters, numbers, dots, underscores or dashes.");
  }
  return id;
}

function normalizePhone(value) {
  let number = String(value || "").trim().replace(/[\s().-]/g, "");
  if (!/^\+?\d+$/.test(number)) {
    throw new Error("Enter a phone number using digits and an optional leading +.");
  }
  if (number.startsWith("00")) number = `+${number.slice(2)}`;
  const digitCount = number.replace(/\D/g, "").length;
  if (digitCount < 6 || digitCount > 15) {
    throw new Error("Enter between 6 and 15 digits. Add the country code if needed.");
  }
  return number;
}

function hasValidSavedSettings() {
  return Boolean(
    currentSettings.serverUrl &&
    currentSettings.sharedSecret &&
    currentSettings.deviceId
  );
}

function refreshUi() {
  const configured = hasValidSavedSettings();
  const ready = configured && !settingsDirty && !activeRequest;
  badge.textContent = settingsDirty
    ? "Unsaved settings"
    : (activeRequest ? "Call pending" : (configured ? "Ready" : "Setup needed"));
  badge.classList.toggle("badge-ready", ready);
  badge.classList.toggle("badge-off", !ready);
  callButton.disabled = busy || !ready;
}

async function loadSettings() {
  try {
    const stored = await storageGet({ ...DEFAULT_SETTINGS, activeRequest: null });
    currentSettings = {
      serverUrl: stored.serverUrl || "",
      sharedSecret: stored.sharedSecret || "",
      deviceId: stored.deviceId || "phone1"
    };
    settingsDirty = false;
    serverUrlInput.value = currentSettings.serverUrl;
    secretInput.value = currentSettings.sharedSecret;
    deviceIdInput.value = currentSettings.deviceId;

    const savedRequest = stored.activeRequest;
    if (savedRequest && savedRequest.commandId && savedRequest.settings) {
      activeRequest = {
        commandId: savedRequest.commandId,
        phone: savedRequest.phone || "the number",
        settings: savedRequest.settings
      };
      cancelButton.hidden = false;
      setFeedback("Found a previous call request. Checking its status…", "pending");
      refreshUi();
      await refreshSavedRequestStatus();
    } else {
      activeRequest = null;
      cancelButton.hidden = true;
      refreshUi();
    }
  } catch (error) {
    setFeedback(`Could not read Chrome storage: ${error.message}`, "error");
  }
}

async function saveSettings(showMessage = true) {
  const settings = formSettings();
  if (!settings.sharedSecret) throw new Error("Enter a shared secret or generate one.");
  await storageSet(settings);
  currentSettings = settings;
  settingsDirty = false;
  refreshUi();
  if (showMessage) {
    setFeedback("Settings saved in this Chrome profile. Enter the same values in the Android app.", "success");
  }
  return settings;
}

function actionUrl(action, settings, extra = {}) {
  const url = new URL(settings.serverUrl);
  url.searchParams.set("action", action);
  url.searchParams.set("auth", settings.sharedSecret);
  for (const [key, value] of Object.entries(extra)) {
    url.searchParams.set(key, String(value));
  }
  return url.toString();
}

async function readJsonResponse(response) {
  const text = await response.text();
  let data;
  try {
    data = JSON.parse(text);
  } catch (_) {
    throw new Error("Relay did not return JSON. Check that the Web App is deployed for access by anyone.");
  }
  if (!response.ok) throw new Error(`Relay HTTP ${response.status}.`);
  if (!data || data.ok !== true) {
    throw new Error((data && data.error) || "Relay rejected the request.");
  }
  return data;
}

async function getFromRelay(action, settings, extra = {}) {
  const response = await fetch(actionUrl(action, settings, extra), {
    method: "GET",
    cache: "no-store",
    credentials: "omit",
    redirect: "follow"
  });
  return readJsonResponse(response);
}

async function postToRelay(body, settings) {
  const response = await fetch(settings.serverUrl, {
    method: "POST",
    headers: { "Content-Type": "text/plain;charset=UTF-8" },
    body: JSON.stringify({ ...body, auth: settings.sharedSecret }),
    cache: "no-store",
    credentials: "omit",
    redirect: "follow"
  });
  return readJsonResponse(response);
}

async function refreshSavedRequestStatus() {
  const request = activeRequest;
  if (!request) return;
  try {
    const result = await getFromRelay("status", request.settings, {
      commandId: request.commandId
    });
    if (activeRequest !== request) return;
    if (result.state === "completed") {
      await clearActiveRequest();
      if (result.status === "done") {
        setFeedback(`Android started the outgoing call to ${request.phone}. Whether it connected depends on the phone and mobile network.`, "success");
      } else if (result.status === "cancelled") {
        setFeedback("This call request was cancelled.", "pending");
      } else {
        setFeedback(`Android could not start the call to ${request.phone}. Check CALL_PHONE permission and the Android app logs.`, "error");
      }
      return;
    }
    if (result.state === "unknown") {
      await clearActiveRequest();
      setFeedback("The saved request expired or is no longer in the relay. Check the Android phone before trying again.", "error");
      return;
    }
    cancelButton.hidden = false;
    setFeedback("A previous request is still waiting for Android. Check the phone or clear the request below.", "pending");
  } catch (error) {
    setFeedback(`Could not check the previous request: ${error.message}`, "error");
    cancelButton.hidden = false;
  }
}

async function testConnection() {
  setBusy(true);
  setFeedback("Checking the relay…", "pending");
  try {
    const settings = await saveSettings(false);
    const result = await getFromRelay("health", settings);
    setFeedback(`Relay connected (${result.service || "AutoCaller"}). Android must use the same URL, secret and device ID.`, "success");
  } catch (error) {
    setFeedback(error.message, "error");
  } finally {
    setBusy(false);
    refreshUi();
  }
}

function delay(ms) {
  return new Promise((resolve) => setTimeout(resolve, ms));
}

async function waitForAndroid(commandId, phone, settings) {
  for (let attempt = 0; attempt < STATUS_WAIT_ATTEMPTS; attempt += 1) {
    await delay(STATUS_POLL_MS);
    const result = await getFromRelay("status", settings, { commandId });
    if (result.state === "completed") {
      await clearActiveRequest();
      if (result.status === "done") {
        setFeedback(`Android started the outgoing call to ${phone}. Whether it connects depends on your phone and mobile network.`, "success");
      } else if (result.status === "cancelled") {
        setFeedback("This call request was cancelled.", "pending");
      } else {
        setFeedback(`Android could not start the call to ${phone}. Check CALL_PHONE permission and the Android app logs.`, "error");
      }
      return;
    }
    if (result.state === "unknown") {
      await clearActiveRequest();
      setFeedback("The relay no longer has this request. Check the Android app and try again.", "error");
      return;
    }
  }
  cancelButton.hidden = false;
  setFeedback("The request is still waiting. Check that Auto Caller is enabled and online on your Android phone. If it has not reached the phone, you can cancel it below.", "pending");
}

async function placeCall(event) {
  event.preventDefault();
  if (busy || activeRequest) return;

  let phone;
  let settings;
  try {
    phone = normalizePhone(phoneInput.value);
    settings = await saveSettings(false);
  } catch (error) {
    setFeedback(error.message, "error");
    if (!currentSettings.sharedSecret) $("settingsDetails").open = true;
    return;
  }

  setBusy(true);
  setFeedback("Sending the request to your Android phone…", "pending");
  try {
    const result = await postToRelay({
      action: "enqueue",
      phone,
      deviceId: settings.deviceId
    }, settings);
    if (!result.commandId) throw new Error("Relay accepted the request but returned no command ID.");
    activeRequest = { commandId: result.commandId, settings, phone };
    await persistActiveRequest(activeRequest);
    refreshUi();
    setFeedback(`Request queued for ${phone}. Waiting for Android…`, "pending");
    await waitForAndroid(result.commandId, phone, settings);
  } catch (error) {
    setFeedback(error.message, "error");
    if (activeRequest) cancelButton.hidden = false;
  } finally {
    setBusy(false);
    refreshUi();
  }
}

async function cancelPendingRequest() {
  if (!activeRequest || busy) return;
  const confirmed = window.confirm(
    "Clear this call request from the relay? If it was already delivered to Android, the call may already have been placed. Check the phone/call log before retrying."
  );
  if (!confirmed) return;

  const request = activeRequest;
  cancelButton.disabled = true;
  setBusy(true);
  try {
    const result = await postToRelay({
      action: "cancel",
      commandId: request.commandId,
      deviceId: request.settings.deviceId,
      confirmed: true
    }, request.settings);
    await clearActiveRequest();
    setFeedback(result.wasDelivered
      ? "Relay request cleared, but it had already reached Android. Check the call log before placing another call."
      : "Request cancelled before Android picked it up.", result.wasDelivered ? "error" : "success");
  } catch (error) {
    setFeedback(error.message, "error");
  } finally {
    cancelButton.disabled = false;
    setBusy(false);
    refreshUi();
  }
}

function generateSecret() {
  const bytes = new Uint8Array(32);
  crypto.getRandomValues(bytes);
  secretInput.value = Array.from(bytes, (byte) => byte.toString(16).padStart(2, "0")).join("");
  secretInput.type = "text";
  toggleSecretButton.textContent = "Hide";
  toggleSecretButton.setAttribute("aria-label", "Hide shared secret");
  settingsDirty = true;
  refreshUi();
  setFeedback("Random secret generated. Save it, then copy the same value into Apps Script and the Android app.", "pending");
}

saveButton.addEventListener("click", async () => {
  try {
    await saveSettings();
  } catch (error) {
    setFeedback(error.message, "error");
    $("settingsDetails").open = true;
  }
});

testButton.addEventListener("click", testConnection);
cancelButton.addEventListener("click", cancelPendingRequest);
callForm.addEventListener("submit", placeCall);

toggleSecretButton.addEventListener("click", () => {
  const visible = secretInput.type === "password";
  secretInput.type = visible ? "text" : "password";
  toggleSecretButton.textContent = visible ? "Hide" : "Show";
  toggleSecretButton.setAttribute("aria-label", visible ? "Hide shared secret" : "Show shared secret");
});

generateSecretButton.addEventListener("click", generateSecret);

for (const input of [serverUrlInput, secretInput, deviceIdInput]) {
  input.addEventListener("input", () => {
    settingsDirty = true;
    refreshUi();
  });
}

loadSettings();
