'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const { buildJoinJobsFromInputs } = require('../lib/executor-join-create');

function fixedGenId() {
  let n = 0;
  return () => `id${++n}`;
}

test('builds keyword and link jobs with partial success', () => {
  const now = 1_700_000_000_000;
  const result = buildJoinJobsFromInputs(
    [
      'mua bán xe HN',
      'https://www.facebook.com/groups/123456/',
      '   ',
      'x'.repeat(201)
    ],
    {
      group: 'g1',
      createdBy: 'worker01',
      scheduledAt: 0,
      now,
      genId: fixedGenId(),
      normalizeGroupUrl: (url) => url.replace('www.facebook.com', 'm.facebook.com')
    }
  );

  assert.equal(result.created.length, 2);
  assert.equal(result.errors.length, 2);

  assert.equal(result.created[0].type, 'join');
  assert.equal(result.created[0].id, 'JOIN-id1');
  assert.equal(result.created[0].group, 'g1');
  assert.equal(result.created[0].createdBy, 'worker01');
  assert.equal(result.created[0].status, 'QUEUED');
  assert.deepEqual(result.created[0].payload, {
    kind: 'keyword',
    query: 'mua bán xe HN',
    groupUrl: null
  });

  assert.equal(result.created[1].payload.kind, 'link');
  assert.equal(result.created[1].payload.query, null);
  assert.match(result.created[1].payload.groupUrl, /m\.facebook\.com\/groups\/123456/i);

  assert.equal(result.errors[0].line, 3);
  assert.ok(result.errors[0].error);
  assert.equal(result.errors[1].line, 4);
});

test('includes scheduledAt only when future', () => {
  const now = 1_000;
  const future = buildJoinJobsFromInputs(['kw'], {
    group: 'g',
    createdBy: 'u',
    scheduledAt: now + 5_000,
    now,
    genId: () => 'a'
  });
  assert.equal(future.created[0].scheduledAt, now + 5_000);

  const past = buildJoinJobsFromInputs(['kw'], {
    group: 'g',
    createdBy: 'u',
    scheduledAt: now - 1,
    now,
    genId: () => 'b'
  });
  assert.equal(past.created[0].scheduledAt, undefined);
});

test('all-invalid returns only errors', () => {
  const result = buildJoinJobsFromInputs(['', '  '], {
    group: 'g',
    createdBy: 'u',
    genId: () => 'x',
    now: 1
  });
  assert.equal(result.created.length, 0);
  assert.equal(result.errors.length, 2);
});
