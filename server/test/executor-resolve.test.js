'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
const { applyJobResolve } = require('../lib/executor-resolve');

function interruptedJob() {
  return {
    id: 'INT-1', status: 'INTERRUPTED', leaseToken: 'abc', claimedBy: 'u1',
    deviceId: 'd1', claimedAt: 1, heartbeatAt: 1, irreversibleAt: 2,
    attempts: 1, payload: { actions: { like: true } }
  };
}

test('mark_succeeded', () => {
  const job = interruptedJob();
  const r = applyJobResolve(job, { action: 'mark_succeeded', note: 'ok', username: 'admin', now: 100 });
  assert.equal(r.ok, true);
  assert.equal(job.status, 'SUCCEEDED');
  assert.equal(job.resolveAction, 'mark_succeeded');
  assert.equal(job.resolvedBy, 'admin');
  assert.equal(job.leaseToken, undefined);
});

test('requeue clears lease fields', () => {
  const job = interruptedJob();
  const r = applyJobResolve(job, { action: 'requeue', username: 'admin', now: 100 });
  assert.equal(r.ok, true);
  assert.equal(job.status, 'QUEUED');
  assert.equal(job.claimedBy, undefined);
  assert.equal(job.irreversibleAt, undefined);
  assert.equal(job.attempts, 1);
});

test('fail does not invent replacement flag', () => {
  const job = interruptedJob();
  const r = applyJobResolve(job, { action: 'fail', note: 'bad', username: 'admin', now: 100 });
  assert.equal(r.ok, true);
  assert.equal(job.status, 'FAILED');
  assert.equal(job.lastError, 'bad');
  assert.equal(job.createReplacement, undefined);
});

test('reject non-interrupted', () => {
  const job = { status: 'QUEUED' };
  const r = applyJobResolve(job, { action: 'requeue', username: 'a', now: 1 });
  assert.equal(r.ok, false);
  assert.equal(r.statusCode, 409);
});
