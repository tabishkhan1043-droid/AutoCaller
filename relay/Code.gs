/**
 * AutoCaller free relay for Google Apps Script.
 *
 * Set Script Property SHARED_SECRET before deploying. The Chrome extension
 * and Android app must use that same secret and device ID.
 *
 * API:
 *   GET  ?action=health&auth=...
 *   GET  ?action=poll&auth=...&deviceId=phone1
 *   GET  ?action=status&auth=...&commandId=...
 *   POST { action: "enqueue", auth, deviceId, phone }
 *   POST { action: "cancel", auth, deviceId, commandId, confirmed: true }
 *   POST { action: "ack", auth, commandId, status, result: { phone } }
 */

const CONFIG = Object.freeze({
  secretProperty: "SHARED_SECRET",
  queueProperty: "AUTOCALLER_QUEUE_V1",
  recentProperty: "AUTOCALLER_RECENT_V1",
  nextPollMs: 5000,
  // Call delivery is one-shot: avoid redialing if Android places a call but
  // its ACK is lost. The item expires instead of being automatically replayed.
  leaseMs: 24 * 60 * 60 * 1000,
  queueTtlMs: 24 * 60 * 60 * 1000,
  recentTtlMs: 6 * 60 * 60 * 1000,
  maxQueueItems: 25,
  maxRecentItems: 50
});

function doGet(e) {
  const params = (e && e.parameter) || {};
  if (!isAuthorized_(params.auth)) return json_({ ok: false, error: "Unauthorized. Check the shared secret." });

  const action = String(params.action || "");
  if (action === "health") {
    return json_({ ok: true, service: "AutoCaller relay", version: 1 });
  }
  if (action === "poll") return poll_(params.deviceId);
  if (action === "status") return getStatus_(params.commandId);
  return json_({ ok: false, error: "Unsupported action." });
}

function doPost(e) {
  let body;
  try {
    const raw = e && e.postData && e.postData.contents;
    body = JSON.parse(raw || "{}");
  } catch (_) {
    return json_({ ok: false, error: "Request body must be valid JSON." });
  }

  if (!body || !isAuthorized_(body.auth)) {
    return json_({ ok: false, error: "Unauthorized. Check the shared secret." });
  }

  const action = String(body.action || "");
  if (action === "enqueue") return enqueue_(body);
  if (action === "cancel") return cancel_(body);
  if (action === "ack") return acknowledge_(body);
  return json_({ ok: false, error: "Unsupported action." });
}

function poll_(rawDeviceId) {
  const deviceId = normalizeDeviceId_(rawDeviceId);
  if (!deviceId) return json_({ ok: false, error: "Missing or invalid deviceId." });

  const lock = LockService.getScriptLock();
  if (!lock.tryLock(5000)) return json_({ ok: false, error: "Relay is busy. Retry shortly." });
  try {
    const now = Date.now();
    const queue = pruneQueue_(readArrayProperty_(CONFIG.queueProperty), now);
    const item = queue.find((entry) =>
      entry.deviceId === deviceId && Number(entry.leaseUntil || 0) <= now
    );

    let commands = [];
    if (item) {
      item.leaseUntil = now + CONFIG.leaseMs;
      commands = [{
        id: item.id,
        action: "call",
        payload: {
          phone: item.phone,
          orderUrl: "",
          autoRedial: false,
          maxRedial: 0
        },
        createdAt: item.createdAt
      }];
    }

    writeArrayProperty_(CONFIG.queueProperty, queue);
    return json_({
      ok: true,
      commands: commands,
      nextPollMs: CONFIG.nextPollMs,
      serverTime: now
    });
  } catch (_) {
    return json_({ ok: false, error: "Could not read the call queue." });
  } finally {
    lock.releaseLock();
  }
}

function enqueue_(body) {
  const phone = normalizePhone_(body.phone);
  const deviceId = normalizeDeviceId_(body.deviceId);
  if (!phone) return json_({ ok: false, error: "Enter a valid phone number (6–15 digits; optional leading +)." });
  if (!deviceId) return json_({ ok: false, error: "Missing or invalid deviceId." });

  const lock = LockService.getScriptLock();
  if (!lock.tryLock(5000)) return json_({ ok: false, error: "Relay is busy. Retry shortly." });
  try {
    const now = Date.now();
    const queue = pruneQueue_(readArrayProperty_(CONFIG.queueProperty), now);
    if (queue.some((entry) => entry.deviceId === deviceId)) {
      writeArrayProperty_(CONFIG.queueProperty, queue);
      return json_({ ok: false, error: "A call is already waiting or in progress for this device." });
    }
    if (queue.length >= CONFIG.maxQueueItems) {
      writeArrayProperty_(CONFIG.queueProperty, queue);
      return json_({ ok: false, error: "The relay queue is full. Try again after a pending call is handled." });
    }

    const command = {
      id: Utilities.getUuid(),
      deviceId: deviceId,
      phone: phone,
      createdAt: now,
      leaseUntil: 0
    };
    queue.push(command);
    writeArrayProperty_(CONFIG.queueProperty, queue);
    return json_({ ok: true, commandId: command.id, state: "queued" });
  } catch (_) {
    return json_({ ok: false, error: "Could not queue this call." });
  } finally {
    lock.releaseLock();
  }
}

function cancel_(body) {
  const commandId = String(body.commandId || "").trim();
  const deviceId = normalizeDeviceId_(body.deviceId);
  if (!commandId || commandId.length > 100 || !deviceId) {
    return json_({ ok: false, error: "Invalid cancellation request." });
  }
  if (body.confirmed !== true) {
    return json_({ ok: false, error: "Cancellation requires explicit confirmation." });
  }

  const lock = LockService.getScriptLock();
  if (!lock.tryLock(5000)) return json_({ ok: false, error: "Relay is busy. Retry shortly." });
  try {
    const now = Date.now();
    const queue = pruneQueue_(readArrayProperty_(CONFIG.queueProperty), now);
    const index = queue.findIndex((entry) => entry.id === commandId);
    if (index < 0) return json_({ ok: false, error: "This request was already handled or has expired." });

    const command = queue[index];
    if (command.deviceId !== deviceId) return json_({ ok: false, error: "Device ID does not match this request." });
    const wasDelivered = Number(command.leaseUntil || 0) > 0;
    queue.splice(index, 1);
    const recent = pruneRecent_(readArrayProperty_(CONFIG.recentProperty), now);
    recent.unshift({
      id: command.id,
      deviceId: command.deviceId,
      phone: command.phone,
      status: "cancelled",
      completedAt: now
    });
    writeArrayProperty_(CONFIG.queueProperty, queue);
    writeArrayProperty_(CONFIG.recentProperty, recent.slice(0, CONFIG.maxRecentItems));
    return json_({ ok: true, state: "cancelled", wasDelivered: wasDelivered });
  } catch (_) {
    return json_({ ok: false, error: "Could not cancel this request." });
  } finally {
    lock.releaseLock();
  }
}

function acknowledge_(body) {
  const commandId = String(body.commandId || "").trim();
  const status = String(body.status || "").toLowerCase();
  const result = body.result || {};
  const phone = normalizePhone_(result.phone) || "";
  if (!commandId || commandId.length > 100 || (status !== "done" && status !== "error")) {
    return json_({ ok: false, error: "Invalid ACK." });
  }

  const lock = LockService.getScriptLock();
  if (!lock.tryLock(5000)) return json_({ ok: false, error: "Relay is busy. Retry shortly." });
  try {
    const now = Date.now();
    const queue = pruneQueue_(readArrayProperty_(CONFIG.queueProperty), now);
    const index = queue.findIndex((entry) => entry.id === commandId);
    const recent = pruneRecent_(readArrayProperty_(CONFIG.recentProperty), now);

    // ACKs are idempotent: Android may retry after a lost response.
    if (index < 0 && recent.some((entry) => entry.id === commandId)) {
      return json_({ ok: true, duplicate: true });
    }
    if (index < 0) return json_({ ok: false, error: "Unknown or expired command." });

    const command = queue.splice(index, 1)[0];
    recent.unshift({
      id: command.id,
      deviceId: command.deviceId,
      phone: phone || command.phone,
      status: status,
      completedAt: now
    });
    writeArrayProperty_(CONFIG.queueProperty, queue);
    writeArrayProperty_(CONFIG.recentProperty, recent.slice(0, CONFIG.maxRecentItems));
    return json_({ ok: true });
  } catch (_) {
    return json_({ ok: false, error: "Could not acknowledge the call." });
  } finally {
    lock.releaseLock();
  }
}

function getStatus_(rawCommandId) {
  const commandId = String(rawCommandId || "").trim();
  if (!commandId || commandId.length > 100) return json_({ ok: false, error: "Missing or invalid commandId." });

  const lock = LockService.getScriptLock();
  if (!lock.tryLock(5000)) return json_({ ok: false, error: "Relay is busy. Retry shortly." });
  try {
    const now = Date.now();
    const queue = pruneQueue_(readArrayProperty_(CONFIG.queueProperty), now);
    const pending = queue.find((entry) => entry.id === commandId);
    if (pending) {
      writeArrayProperty_(CONFIG.queueProperty, queue);
      return json_({
        ok: true,
        state: Number(pending.leaseUntil || 0) > now ? "in_progress" : "queued"
      });
    }

    const recent = pruneRecent_(readArrayProperty_(CONFIG.recentProperty), now);
    writeArrayProperty_(CONFIG.recentProperty, recent);
    const completed = recent.find((entry) => entry.id === commandId);
    if (completed) {
      return json_({
        ok: true,
        state: "completed",
        status: completed.status,
        phone: completed.phone
      });
    }
    return json_({ ok: true, state: "unknown" });
  } catch (_) {
    return json_({ ok: false, error: "Could not read call status." });
  } finally {
    lock.releaseLock();
  }
}

function normalizePhone_(raw) {
  let phone = String(raw || "").trim().replace(/[\s().-]/g, "");
  if (!/^\+?\d+$/.test(phone)) return "";
  if (phone.startsWith("00")) phone = "+" + phone.slice(2);
  const digits = phone.replace(/\D/g, "").length;
  return digits >= 6 && digits <= 15 ? phone : "";
}

function normalizeDeviceId_(raw) {
  const value = String(raw || "").trim();
  return /^[A-Za-z0-9._-]{1,64}$/.test(value) ? value : "";
}

function isAuthorized_(candidate) {
  const expected = PropertiesService.getScriptProperties().getProperty(CONFIG.secretProperty) || "";
  const supplied = String(candidate || "");
  if (!expected || supplied.length !== expected.length) return false;
  let difference = 0;
  for (let i = 0; i < expected.length; i += 1) {
    difference |= expected.charCodeAt(i) ^ supplied.charCodeAt(i);
  }
  return difference === 0;
}

function readArrayProperty_(key) {
  const raw = PropertiesService.getScriptProperties().getProperty(key);
  if (!raw) return [];
  try {
    const value = JSON.parse(raw);
    return Array.isArray(value) ? value : [];
  } catch (_) {
    return [];
  }
}

function writeArrayProperty_(key, value) {
  const properties = PropertiesService.getScriptProperties();
  if (!value || value.length === 0) {
    properties.deleteProperty(key);
    return;
  }
  properties.setProperty(key, JSON.stringify(value));
}

function pruneQueue_(queue, now) {
  return queue.filter((entry) => {
    const createdAt = Number(entry && entry.createdAt || 0);
    return entry && entry.id && entry.deviceId && entry.phone &&
      createdAt > 0 && now - createdAt < CONFIG.queueTtlMs;
  });
}

function pruneRecent_(recent, now) {
  return recent.filter((entry) => {
    const completedAt = Number(entry && entry.completedAt || 0);
    return entry && entry.id && completedAt > 0 && now - completedAt < CONFIG.recentTtlMs;
  }).slice(0, CONFIG.maxRecentItems);
}

function json_(value) {
  return ContentService
    .createTextOutput(JSON.stringify(value))
    .setMimeType(ContentService.MimeType.JSON);
}
