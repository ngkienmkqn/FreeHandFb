'use strict';

function applyJobResolve(job, { action, note, username, now = Date.now() }) {
  if (!job || job.status !== 'INTERRUPTED') {
    return { ok: false, statusCode: 409, error: 'Chỉ resolve được job INTERRUPTED.' };
  }
  const act = String(action || '');
  if (!['mark_succeeded', 'requeue', 'fail'].includes(act)) {
    return { ok: false, statusCode: 400, error: 'action không hợp lệ.' };
  }
  const resolveNote = String(note || '').trim().slice(0, 500);
  job.resolvedAt = now;
  job.resolvedBy = username;
  job.resolveAction = act;
  job.resolveNote = resolveNote;
  job.updatedAt = now;
  delete job.leaseToken;

  if (act === 'mark_succeeded') {
    job.status = 'SUCCEEDED';
    job.claimedBy = undefined;
    job.deviceId = undefined;
    job.claimedAt = undefined;
    job.heartbeatAt = undefined;
    job.irreversibleAt = undefined;
    return { ok: true, job };
  }
  if (act === 'requeue') {
    job.status = 'QUEUED';
    job.claimedBy = undefined;
    job.deviceId = undefined;
    job.claimedAt = undefined;
    job.heartbeatAt = undefined;
    job.irreversibleAt = undefined;
    // Do not set scheduledAt — due immediately for ops verify
    return { ok: true, job };
  }
  // fail
  job.status = 'FAILED';
  job.lastError = resolveNote || 'Ops đánh fail sau INTERRUPTED';
  job.claimedBy = undefined;
  job.deviceId = undefined;
  job.claimedAt = undefined;
  job.heartbeatAt = undefined;
  job.irreversibleAt = undefined;
  return { ok: true, job };
}

module.exports = { applyJobResolve };
