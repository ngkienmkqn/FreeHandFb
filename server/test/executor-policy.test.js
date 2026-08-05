'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
const { classifyFailure, normalizeActions, remainingActions, canExecutorRetry } = require('../lib/executor-policy');

test('account failure is routed to another account', () => {
  const f = classifyFailure({ code: 'FACEBOOK_ACTION_BLOCK' });
  assert.equal(f.category, 'ACCOUNT'); assert.equal(f.retryScope, 'OTHER_ACCOUNT'); assert.equal(f.retryable, true);
});
test('dead target is never retried', () => {
  const f = classifyFailure({ code: 'TARGET_POST_UNAVAILABLE' });
  assert.equal(f.category, 'TARGET'); assert.equal(f.retryable, false); assert.equal(f.retryScope, 'NONE');
});
test('partial success retries only unfinished comment', () => {
  const actions = { like: { required: true, status: 'CONFIRMED' }, comment: { required: true, status: 'FAILED' } };
  assert.deepEqual(remainingActions(actions), { like: false, comment: true });
});
test('legacy boolean actions are normalized', () => {
  assert.deepEqual(normalizeActions({ like: true, comment: false }), {
    like: { required: true, status: 'PENDING' }, comment: { required: false, status: 'SKIPPED' }
  });
});
test('failed account and device cannot reclaim replacement', () => {
  const retry = { excludedAccounts: ['a'], excludedDevices: ['d1'] };
  assert.equal(canExecutorRetry(retry, 'a', 'd2'), false);
  assert.equal(canExecutorRetry(retry, 'b', 'd1'), false);
  assert.equal(canExecutorRetry(retry, 'b', 'd2'), true);
});
test('infrastructure failure may retry without blaming account or group', () => {
  const f = classifyFailure({ code: 'CHECKPOINT_REJECTED' });
  assert.equal(f.category, 'INFRASTRUCTURE'); assert.equal(f.retryScope, 'SAME_OR_OTHER');
});
test('all confirmed actions leave nothing to retry', () => {
  assert.deepEqual(remainingActions({
    like: { required: true, status: 'ALREADY_DONE' }, comment: { required: true, status: 'CONFIRMED' }
  }), { like: false, comment: false });
});
test('uncertain comment remains unresolved and must not be treated as success', () => {
  assert.deepEqual(remainingActions({
    like: { required: false, status: 'SKIPPED' }, comment: { required: true, status: 'UNCERTAIN' }
  }), { like: false, comment: true });
});
