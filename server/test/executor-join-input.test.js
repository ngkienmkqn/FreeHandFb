'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
const { detectJoinInput, keywordIntelKey, normalizeJoinQuery } = require('../lib/executor-join-input');

test('detects facebook group link', () => {
  const r = detectJoinInput('https://www.facebook.com/groups/123456/');
  assert.equal(r.ok, true);
  assert.equal(r.kind, 'link');
  assert.match(r.groupUrl, /facebook\.com\/groups\/123456/i);
  assert.equal(r.query, null);
});

test('detects bare fb.com/groups link', () => {
  const r = detectJoinInput('fb.com/groups/abc');
  assert.equal(r.ok, true);
  assert.equal(r.kind, 'link');
});

test('does not treat not-a-fb.com as facebook host', () => {
  const r = detectJoinInput('https://not-a-fb.com/groups/1');
  assert.equal(r.ok, true);
  assert.equal(r.kind, 'keyword');
  assert.equal(r.query, 'https://not-a-fb.com/groups/1');
  assert.equal(r.groupUrl, null);
});

test('detects keyword', () => {
  const r = detectJoinInput('  mua bán xe HN  ');
  assert.equal(r.ok, true);
  assert.equal(r.kind, 'keyword');
  assert.equal(r.query, 'mua bán xe HN');
  assert.equal(r.groupUrl, null);
});

test('rejects empty', () => {
  assert.equal(detectJoinInput('   ').ok, false);
});

test('rejects keyword longer than 200', () => {
  assert.equal(detectJoinInput('x'.repeat(201)).ok, false);
});

test('keywordIntelKey normalizes', () => {
  assert.equal(keywordIntelKey('  Mua   Bán  '), 'keyword:mua bán');
});
