'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
const { spreadMsForSpeed, scheduledAtForJobIndex } = require('../lib/executor-schedule');

test('spread windows', () => {
  assert.equal(spreadMsForSpeed('SLOW'), 12 * 60 * 60 * 1000);
  assert.equal(spreadMsForSpeed('NORMAL'), 4 * 60 * 60 * 1000);
  assert.equal(spreadMsForSpeed('FAST'), 30 * 60 * 1000);
  assert.equal(spreadMsForSpeed('NOPE'), 4 * 60 * 60 * 1000);
});

test('first job is due now; last near window end', () => {
  const now = 1_700_000_000_000;
  assert.equal(scheduledAtForJobIndex(now, 0, 4, 'FAST'), now);
  const last = scheduledAtForJobIndex(now, 3, 4, 'FAST');
  assert.ok(last > now);
  assert.ok(last <= now + spreadMsForSpeed('FAST'));
});
