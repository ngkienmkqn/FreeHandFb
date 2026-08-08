'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
const {
  isWithinActiveWindow,
  isPastMaxRuntime
} = require('../lib/executor-target-window');

/** UTC ms for a wall-clock time in Asia/Ho_Chi_Minh (UTC+7, no DST). */
function vnInstant(year, monthIndex, day, hour, minute = 0) {
  return Date.UTC(year, monthIndex, day, hour - 7, minute, 0);
}

test('within window', () => {
  const target = { activeHours: { start: '09:00', end: '22:00' } };
  const nowInside = vnInstant(2024, 0, 15, 10, 0);
  const nowOutside = vnInstant(2024, 0, 15, 23, 0);
  assert.equal(isWithinActiveWindow(target, nowInside), true);
  assert.equal(isWithinActiveWindow(target, nowOutside), false);
});

test('missing activeHours always open', () => {
  assert.equal(isWithinActiveWindow({}, Date.now()), true);
});

test('max runtime', () => {
  const target = { createdAt: 1000, autoClose: { maxRuntimeHours: 1 } };
  assert.equal(isPastMaxRuntime(target, 1000 + 3_600_000 + 1), true);
  assert.equal(isPastMaxRuntime(target, 1000 + 3_600_000), false);
});

test('max runtime uses resumedFromReviewAt over createdAt', () => {
  const resumedAt = 10_000_000;
  const target = {
    createdAt: 1000,
    resumedFromReviewAt: resumedAt,
    autoClose: { maxRuntimeHours: 1 }
  };
  // Still within 1h of resume even though far past createdAt + 1h
  assert.equal(isPastMaxRuntime(target, resumedAt + 3_600_000), false);
  assert.equal(isPastMaxRuntime(target, resumedAt + 3_600_000 + 1), true);
});

test('boundary inclusive start exclusive end', () => {
  const target = { activeHours: { start: '09:00', end: '22:00' } };
  assert.equal(isWithinActiveWindow(target, vnInstant(2024, 0, 15, 9, 0)), true);
  assert.equal(isWithinActiveWindow(target, vnInstant(2024, 0, 15, 22, 0)), false);
});
