'use strict';

// Host labels only (not substring of not-a-fb.com). Boundary checked in findGroupUrlMatch.
const GROUP_URL_RE = /(?:https?:\/\/)?(?:www\.|m\.)?(?:facebook\.com|fb\.com)\/groups\/[^\s]+/gi;

function normalizeJoinQuery(query) {
  return String(query || '').replace(/\s+/g, ' ').trim().toLowerCase().slice(0, 200);
}

function keywordIntelKey(query) {
  return `keyword:${normalizeJoinQuery(query)}`;
}

function ensureHttpUrl(raw) {
  const s = String(raw || '').trim();
  if (!s) return '';
  if (/^https?:\/\//i.test(s)) return s;
  return `https://${s}`;
}

/** @returns {RegExpMatchArray|null} */
function findGroupUrlMatch(text) {
  GROUP_URL_RE.lastIndex = 0;
  let match;
  while ((match = GROUP_URL_RE.exec(text))) {
    const idx = match.index;
    // Reject mid-label hosts e.g. not-a-fb.com/groups/...
    if (idx > 0 && /[A-Za-z0-9-]/.test(text[idx - 1])) continue;
    return match;
  }
  return null;
}

/**
 * @param {string} raw
 * @returns {{ok:true,kind:'keyword'|'link',query:string|null,groupUrl:string|null}|{ok:false,error:string}}
 */
function detectJoinInput(raw) {
  const text = String(raw || '').trim();
  if (!text) return { ok: false, error: 'Input trống.' };
  const match = findGroupUrlMatch(text);
  if (match) {
    let groupUrl = ensureHttpUrl(match[0].replace(/[),.]+$/, ''));
    // strip trailing slash noise except keep path
    try {
      const u = new URL(groupUrl);
      const host = u.hostname.toLowerCase().replace(/^www\./, '').replace(/^m\./, '');
      if (host !== 'facebook.com' && host !== 'fb.com') {
        return { ok: false, error: 'Link group không hợp lệ.' };
      }
      if (!/\/groups\//i.test(u.pathname)) return { ok: false, error: 'Link group không hợp lệ.' };
      groupUrl = `https://www.facebook.com${u.pathname.replace(/\/$/, '')}/`;
    } catch {
      return { ok: false, error: 'Link group không hợp lệ.' };
    }
    return { ok: true, kind: 'link', query: null, groupUrl };
  }
  if (text.length > 200) return { ok: false, error: 'Từ khóa tối đa 200 ký tự.' };
  return { ok: true, kind: 'keyword', query: text.replace(/\s+/g, ' ').trim(), groupUrl: null };
}

function resolveJoinIntelKey(job, result = {}) {
  const fromResult = String(result.groupUrl || '').trim();
  if (fromResult) return fromResult;
  const fromPayload = String(job?.payload?.groupUrl || '').trim();
  if (fromPayload) return fromPayload;
  const q = String(job?.payload?.query || '').trim();
  if (q) return keywordIntelKey(q);
  return null;
}

module.exports = { detectJoinInput, keywordIntelKey, normalizeJoinQuery, resolveJoinIntelKey };
