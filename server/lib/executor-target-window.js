'use strict';

function parseHmToMinutes(value) {
  const match = /^(\d{1,2}):(\d{2})$/.exec(String(value || '').trim());
  if (!match) return null;
  const hour = Number(match[1]);
  const minute = Number(match[2]);
  if (!Number.isFinite(hour) || !Number.isFinite(minute)) return null;
  if (hour < 0 || hour > 23 || minute < 0 || minute > 59) return null;
  return hour * 60 + minute;
}

function minutesOfDayInTimeZone(now, timeZone) {
  const date = now instanceof Date ? now : new Date(now);
  const parts = new Intl.DateTimeFormat('en-GB', {
    timeZone,
    hour: '2-digit',
    minute: '2-digit',
    hour12: false
  }).formatToParts(date);
  let hour = Number(parts.find(part => part.type === 'hour')?.value);
  const minute = Number(parts.find(part => part.type === 'minute')?.value);
  if (!Number.isFinite(hour) || !Number.isFinite(minute)) return 0;
  if (hour === 24) hour = 0;
  return hour * 60 + minute;
}

function resolveActiveHours(target) {
  const direct = target?.activeHours;
  if (direct?.start && direct?.end) return direct;
  const nested = target?.autoClose?.activeHours;
  if (nested?.start && nested?.end) return nested;
  return null;
}

/**
 * @param {object} target
 * @param {number|Date} [now]
 * @param {string} [timeZone]
 * @returns {boolean}
 */
function isWithinActiveWindow(target, now = Date.now(), timeZone = 'Asia/Ho_Chi_Minh') {
  const hours = resolveActiveHours(target);
  if (!hours) return true;
  const start = parseHmToMinutes(hours.start);
  const end = parseHmToMinutes(hours.end);
  if (start == null || end == null) return true;
  if (start === end) return true;
  const current = minutesOfDayInTimeZone(now, timeZone);
  if (start < end) return current >= start && current < end;
  // Overnight window, e.g. 22:00 → 06:00
  return current >= start || current < end;
}

/**
 * Runtime clock starts at resume-from-review when set, else createdAt.
 * @param {object} target
 * @param {number|Date} [now]
 * @returns {boolean}
 */
function isPastMaxRuntime(target, now = Date.now()) {
  const hours = Number(target?.autoClose?.maxRuntimeHours);
  if (!Number.isFinite(hours) || hours <= 0) return false;
  const resumedAt = Number(target?.resumedFromReviewAt);
  const createdAt = Number(target?.createdAt);
  const startAt = Number.isFinite(resumedAt) && resumedAt > 0 ? resumedAt : createdAt;
  if (!Number.isFinite(startAt)) return false;
  const ts = now instanceof Date ? now.getTime() : Number(now);
  if (!Number.isFinite(ts)) return false;
  return ts > startAt + hours * 3_600_000;
}

module.exports = {
  isWithinActiveWindow,
  isPastMaxRuntime
};
