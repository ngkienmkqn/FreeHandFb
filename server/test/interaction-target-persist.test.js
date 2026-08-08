'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
const { autoCloseForPersist, dbToTarget } = require('../db/store');
const { isPastMaxRuntime } = require('../lib/executor-target-window');

test('autoCloseForPersist mirrors resumedFromReviewAt into JSONB payload', () => {
  const resumedFromReviewAt = 12_345_678;
  const packed = autoCloseForPersist({
    resumedFromReviewAt,
    autoClose: { enabled: true, maxRuntimeHours: 24 }
  });
  assert.equal(packed.resumedFromReviewAt, resumedFromReviewAt);
  assert.equal(packed.maxRuntimeHours, 24);
});

test('dbToTarget hydrates resumedFromReviewAt from auto_close JSONB', () => {
  const resumedFromReviewAt = 9_876_543;
  const target = dbToTarget({
    id: 't1',
    user_group: 'g',
    group_id: 'gid',
    post_url: 'https://example.com',
    status: 'RUNNING',
    requirements: {},
    comment_pool: [],
    allow_repeat_comments: false,
    target_post: {},
    speed: 'NORMAL',
    priority: 'NORMAL',
    online_only: true,
    auto_close: { maxRuntimeHours: 1, resumedFromReviewAt },
    created_at: 1000,
    updated_at: 2000
  });
  assert.equal(target.resumedFromReviewAt, resumedFromReviewAt);
  assert.equal(isPastMaxRuntime(target, resumedFromReviewAt + 3_600_000), false);
  assert.equal(isPastMaxRuntime(target, resumedFromReviewAt + 3_600_000 + 1), true);
});

test('persist → hydrate round-trip keeps resume clock (not createdAt)', () => {
  const createdAt = 1000;
  const resumedFromReviewAt = 10_000_000;
  const packed = autoCloseForPersist({
    createdAt,
    resumedFromReviewAt,
    autoClose: { maxRuntimeHours: 1 }
  });
  const target = dbToTarget({
    id: 't2',
    user_group: 'g',
    group_id: 'gid',
    post_url: 'https://example.com',
    status: 'RUNNING',
    requirements: {},
    comment_pool: [],
    allow_repeat_comments: false,
    target_post: {},
    speed: 'NORMAL',
    priority: 'NORMAL',
    online_only: true,
    auto_close: packed,
    created_at: createdAt,
    updated_at: resumedFromReviewAt
  });
  assert.equal(target.resumedFromReviewAt, resumedFromReviewAt);
  // Without hydration, isPastMaxRuntime would use createdAt and trip immediately.
  assert.equal(isPastMaxRuntime(target, resumedFromReviewAt + 60_000), false);
});

test('activeHours still hydrates from auto_close (Wave 4 already OK)', () => {
  const target = dbToTarget({
    id: 't3',
    user_group: 'g',
    group_id: 'gid',
    post_url: 'https://example.com',
    status: 'RUNNING',
    requirements: {},
    comment_pool: [],
    allow_repeat_comments: false,
    target_post: {},
    speed: 'NORMAL',
    priority: 'NORMAL',
    online_only: true,
    auto_close: { activeHours: { start: '09:00', end: '22:00' } },
    created_at: 1,
    updated_at: 1
  });
  assert.deepEqual(target.activeHours, { start: '09:00', end: '22:00' });
});
