'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
const { resolveJoinIntelKey } = require('../lib/executor-join-input');

// Export resolveJoinIntelKey from executor-join-input.js in implementation step

test('prefers result.groupUrl', () => {
  const key = resolveJoinIntelKey(
    { payload: { kind: 'keyword', query: 'xe' } },
    { groupUrl: 'https://www.facebook.com/groups/9/' }
  );
  assert.match(key, /groups\/9/);
});

test('falls back to payload.groupUrl', () => {
  const key = resolveJoinIntelKey(
    { payload: { kind: 'link', groupUrl: 'https://www.facebook.com/groups/1/' } },
    {}
  );
  assert.match(key, /groups\/1/);
});

test('keyword fallback', () => {
  assert.equal(
    resolveJoinIntelKey({ payload: { kind: 'keyword', query: 'Mua Bán' } }, {}),
    'keyword:mua bán'
  );
});
