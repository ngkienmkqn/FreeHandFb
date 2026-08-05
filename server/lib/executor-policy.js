'use strict';

const FAILURE_RULES = Object.freeze({
  FACEBOOK_ACTION_BLOCK: { category: 'ACCOUNT', retryScope: 'OTHER_ACCOUNT' },
  GROUP_MEMBERSHIP_REQUIRED: { category: 'ACCOUNT', retryScope: 'OTHER_ACCOUNT' },
  OPEN_FACEBOOK_FAILED: { category: 'DEVICE', retryScope: 'OTHER_DEVICE' },
  STEP_TIMEOUT: { category: 'DEVICE', retryScope: 'OTHER_DEVICE' },
  CHECKPOINT_REJECTED: { category: 'INFRASTRUCTURE', retryScope: 'SAME_OR_OTHER' },
  TARGET_POST_NOT_FOUND: { category: 'TARGET', retryScope: 'OTHER_ACCOUNT' },
  TARGET_POST_UNAVAILABLE: { category: 'TARGET', retryScope: 'NONE', retryable: false },
  FACEBOOK_GROUP_UNAVAILABLE: { category: 'GROUP', retryScope: 'NONE', retryable: false }
});

function classifyFailure(input = {}) {
  const code = String(input.code || 'ACCESSIBILITY_FAILED').trim().toUpperCase();
  const rule = FAILURE_RULES[code] || { category: 'UNKNOWN', retryScope: 'OTHER_DEVICE' };
  return { ...input, code, ...rule, retryable: input.retryable !== false && rule.retryable !== false };
}

function normalizeActions(actions = {}) {
  const normalize = value => typeof value === 'object'
    ? { required: value.required !== false, status: value.status || 'PENDING' }
    : { required: value === true, status: value === true ? 'PENDING' : 'SKIPPED' };
  return { like: normalize(actions.like), comment: normalize(actions.comment) };
}

function remainingActions(actions = {}) {
  const normalized = normalizeActions(actions);
  const done = new Set(['CONFIRMED', 'ALREADY_DONE', 'SKIPPED']);
  return {
    like: normalized.like.required && !done.has(normalized.like.status),
    comment: normalized.comment.required && !done.has(normalized.comment.status)
  };
}

function canExecutorRetry(retry = {}, username, deviceId) {
  return !(retry.excludedAccounts || []).includes(username) &&
    !(retry.excludedDevices || []).includes(deviceId);
}

module.exports = { FAILURE_RULES, classifyFailure, normalizeActions, remainingActions, canExecutorRetry };
