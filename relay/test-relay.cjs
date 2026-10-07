#!/usr/bin/env node
"use strict";

// Small dependency-free smoke tests for Code.gs using mocked Apps Script APIs.
const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const vm = require("node:vm");

const properties = new Map([["SHARED_SECRET", "test-secret-for-local-tests"]]);
let uuidCounter = 0;

const context = {
  PropertiesService: {
    getScriptProperties() {
      return {
        getProperty: (key) => properties.has(key) ? properties.get(key) : null,
        setProperty: (key, value) => { properties.set(key, String(value)); },
        deleteProperty: (key) => { properties.delete(key); }
      };
    }
  },
  LockService: {
    getScriptLock() {
      return { tryLock: () => true, releaseLock: () => {} };
    }
  },
  Utilities: { getUuid: () => `test-command-${++uuidCounter}` },
  ContentService: {
    MimeType: { JSON: "application/json" },
    createTextOutput(text) {
      return {
        text,
        setMimeType() { return this; }
      };
    }
  },
  console,
  Date,
  JSON,
  String,
  Number,
  Array,
  Object,
  Math
};

const source = fs.readFileSync(path.join(__dirname, "Code.gs"), "utf8");
vm.createContext(context);
vm.runInContext(source, context, { filename: "Code.gs" });

function invoke(response) {
  return JSON.parse(response.text);
}
function get(action, extra = {}, auth = "test-secret-for-local-tests") {
  return invoke(context.doGet({ parameter: { action, auth, ...extra } }));
}
function post(body) {
  return invoke(context.doPost({ postData: { contents: JSON.stringify(body) } }));
}

assert.equal(get("health", {}, "wrong-secret").ok, false, "reject incorrect secret");
assert.equal(get("health").service, "AutoCaller relay", "health check succeeds");

const queued = post({
  action: "enqueue",
  auth: "test-secret-for-local-tests",
  deviceId: "phone1",
  phone: "+92 300-1234567"
});
assert.equal(queued.ok, true, "valid number is queued");
assert.ok(queued.commandId, "enqueue returns an ID");
assert.equal(post({
  action: "enqueue",
  auth: "test-secret-for-local-tests",
  deviceId: "phone1",
  phone: "+923009999999"
}).ok, false, "only one pending call per device");

assert.equal(get("poll", { deviceId: "phone2" }).commands.length, 0, "device queue is isolated");
const polled = get("poll", { deviceId: "phone1" });
assert.equal(polled.commands.length, 1, "target device receives one command");
assert.equal(polled.commands[0].payload.phone, "+923001234567", "number is normalized");
assert.equal(get("status", { commandId: queued.commandId }).state, "in_progress", "poll leases command");

assert.equal(post({
  action: "ack",
  auth: "test-secret-for-local-tests",
  commandId: queued.commandId,
  status: "done",
  result: { phone: "+923001234567" }
}).ok, true, "ACK clears command");
const status = get("status", { commandId: queued.commandId });
assert.equal(status.state, "completed", "status is retained for the extension");
assert.equal(status.status, "done", "completion status is returned");
assert.equal(post({
  action: "ack",
  auth: "test-secret-for-local-tests",
  commandId: queued.commandId,
  status: "done",
  result: { phone: "+923001234567" }
}).duplicate, true, "duplicate ACK is idempotent");

assert.equal(post({
  action: "enqueue",
  auth: "test-secret-for-local-tests",
  deviceId: "phone1",
  phone: "123"
}).ok, false, "reject too-short phone numbers");

const cancellable = post({
  action: "enqueue",
  auth: "test-secret-for-local-tests",
  deviceId: "phone2",
  phone: "+923001112233"
});
assert.equal(post({
  action: "cancel",
  auth: "test-secret-for-local-tests",
  deviceId: "phone2",
  commandId: cancellable.commandId,
  confirmed: true
}).wasDelivered, false, "queued request can be cancelled before delivery");
assert.equal(get("status", { commandId: cancellable.commandId }).status, "cancelled", "cancellation status is visible");

const delivered = post({
  action: "enqueue",
  auth: "test-secret-for-local-tests",
  deviceId: "phone3",
  phone: "+923001112244"
});
get("poll", { deviceId: "phone3" });
assert.equal(post({
  action: "cancel",
  auth: "test-secret-for-local-tests",
  deviceId: "phone3",
  commandId: delivered.commandId,
  confirmed: true
}).wasDelivered, true, "cancelling a delivered request warns the extension");

console.log("Relay smoke tests passed.");
