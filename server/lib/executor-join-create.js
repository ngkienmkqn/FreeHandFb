'use strict';

const { detectJoinInput } = require('./executor-join-input');

/**
 * Pure multi-line join job builder (no queue mutation).
 * @param {string[]} inputs
 * @param {{
 *   group: string,
 *   createdBy: string,
 *   scheduledAt?: number,
 *   now?: number,
 *   genId: () => string,
 *   normalizeGroupUrl?: (url: string) => string
 * }} opts
 * @returns {{ created: object[], errors: { line: number, input: string, error: string }[] }}
 */
function buildJoinJobsFromInputs(inputs, opts) {
  const {
    group,
    createdBy,
    scheduledAt = 0,
    now = Date.now(),
    genId,
    normalizeGroupUrl = (url) => url
  } = opts;
  const created = [];
  const errors = [];
  inputs.forEach((raw, index) => {
    const line = index + 1;
    const detected = detectJoinInput(raw);
    if (!detected.ok) {
      errors.push({ line, input: String(raw || ''), error: detected.error });
      return;
    }
    const payload = detected.kind === 'link'
      ? { kind: 'link', query: null, groupUrl: normalizeGroupUrl(detected.groupUrl) }
      : { kind: 'keyword', query: detected.query, groupUrl: null };
    const job = {
      id: `JOIN-${genId()}`,
      type: 'join',
      group,
      payload,
      status: 'QUEUED',
      attempts: 0,
      createdBy,
      createdAt: now,
      updatedAt: now,
      ...(scheduledAt > now ? { scheduledAt } : {})
    };
    created.push(job);
  });
  return { created, errors };
}

module.exports = { buildJoinJobsFromInputs };
