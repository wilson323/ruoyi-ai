// Local override regression checks; does not execute the hook dispatcher.
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const crypto = require('node:crypto');
const file = path.join(__dirname, 'hook-handler.cjs');
const source = fs.readFileSync(file, 'utf8');
const prefix = source.slice(0, source.indexOf('// Safe require with stdout suppression'));
function harness(installed, fail = false) {
  const calls = []; const writes = []; const messages = [];
  const fakeFs = { existsSync: () => installed, mkdirSync: (...x) => writes.push(x), writeFileSync: (...x) => writes.push(x) };
  const context = vm.createContext({ __dirname, console, process: { cwd: () => '/project', execPath: '/node', env: {}, stdout: { isTTY: true }, stderr: { write: x => messages.push(x) } }, require: name => {
    if (name === 'path') return path;
    if (name === 'fs') return fakeFs;
    if (name === 'os') return { homedir: () => '/fake-home' };
    if (name === 'child_process') return { spawn: (...x) => { calls.push(x); if (fail) throw new Error('unavailable'); return { on: () => {}, unref: () => {} }; } };
    throw new Error('Unexpected module: ' + name);
  } });
  vm.runInContext(prefix, context);
  return { context, calls, writes, messages };
}
let h = harness(false);
assert.equal(vm.runInContext("spawnDetachedHookRefresh('refresh-funnel')", h.context), false);
assert.equal(h.calls.length, 0);
assert.match(h.messages.join(''), /CLI unavailable/);
h = harness(true);
assert.equal(vm.runInContext("spawnDetachedHookRefresh('refresh-advisor')", h.context), true);
assert.equal(h.calls.length, 1); assert.equal(h.calls[0][0], '/node');
assert.equal(h.calls[0][1][1], 'hooks'); assert.equal(h.calls[0][1][2], 'refresh-advisor');
assert.ok(!JSON.stringify(h.calls).includes('npx'));
for (const installed of [false, true]) {
 h = harness(installed);
 vm.runInContext('firstRunAutoEnableIfEligible()', h.context);
 assert.equal(h.calls.length, 0); assert.equal(h.writes.length, 0);
 assert.match(h.messages.join(''), /explicit user configuration/);
}
h = harness(true, true);
assert.equal(vm.runInContext("spawnDetachedHookRefresh('refresh-funnel')", h.context), false);
assert.match(h.messages.join(''), /failed to start/);
assert.equal(h.writes.length, 0);
console.log('PASS: absent CLI skips; installed CLI reused; spawn failure explicit; no automatic preferences/global writes.');
console.log('UNSIGNED_LOCAL_OVERRIDE_SHA256=' + crypto.createHash('sha256').update(source).digest('hex'));
console.log('Upstream signed manifest preserved; this digest is local test evidence, not an upstream signature.');
