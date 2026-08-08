'use strict';

function spreadMsForSpeed(speed) {
  switch (String(speed || 'NORMAL').toUpperCase()) {
    case 'SLOW': return 12 * 60 * 60 * 1000;
    case 'FAST': return 30 * 60 * 1000;
    default: return 4 * 60 * 60 * 1000;
  }
}

function scheduledAtForJobIndex(now, index, jobCount, speed) {
  const n = Math.max(1, Number(jobCount) || 1);
  const i = Math.max(0, Number(index) || 0);
  const windowMs = spreadMsForSpeed(speed);
  return now + Math.floor((i * windowMs) / n);
}

module.exports = { spreadMsForSpeed, scheduledAtForJobIndex };
