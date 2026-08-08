'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const { preferJoinedGate } = require('../lib/executor-claim-gate');

test('empty joinedAccounts allows any user (bootstrap)', () => {
  assert.equal(preferJoinedGate({}, 'alice'), true);
  assert.equal(preferJoinedGate({ joinedAccounts: {} }, 'alice'), true);
  assert.equal(preferJoinedGate({ joinedAccounts: {}, accountMembership: {} }, 'bob'), true);
});

test('missing intel allows (cold start)', () => {
  assert.equal(preferJoinedGate(null, 'alice'), true);
  assert.equal(preferJoinedGate(undefined, 'alice'), true);
});

test('user in joinedAccounts may claim', () => {
  const intel = {
    joinedAccounts: { alice: { username: 'alice' } },
    accountMembership: {}
  };
  assert.equal(preferJoinedGate(intel, 'alice'), true);
});

test('user with membership JOINED may claim even if not in joinedAccounts', () => {
  const intel = {
    joinedAccounts: { alice: { username: 'alice' } },
    accountMembership: { bob: { username: 'bob', status: 'JOINED' } }
  };
  assert.equal(preferJoinedGate(intel, 'bob'), true);
});

test('non-joined user denied when joinedAccounts non-empty', () => {
  const intel = {
    joinedAccounts: { alice: { username: 'alice' } },
    accountMembership: { bob: { username: 'bob', status: 'NOT_JOINED' } }
  };
  assert.equal(preferJoinedGate(intel, 'bob'), false);
  assert.equal(preferJoinedGate(intel, 'carol'), false);
});

test('PENDING/BLOCKED/LEFT do not satisfy preferJoinedGate', () => {
  const intel = {
    joinedAccounts: { alice: { username: 'alice' } },
    accountMembership: {
      p: { status: 'PENDING' },
      b: { status: 'BLOCKED' },
      l: { status: 'LEFT' }
    }
  };
  assert.equal(preferJoinedGate(intel, 'p'), false);
  assert.equal(preferJoinedGate(intel, 'b'), false);
  assert.equal(preferJoinedGate(intel, 'l'), false);
});
