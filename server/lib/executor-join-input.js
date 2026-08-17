'use strict';

const GROUP_URL_RE = /(?:https?:\/\/)?(?:www\.|m\.)?(?:facebook|fb)\.com\/groups\/[^\s]+/i;

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

/**
 * @param {string} raw
 * @returns {{ok:true,kind:'keyword'|'link',query:string|null,groupUrl:string|null}|{ok:false,error:string}}
 */
function detectJoinInput(raw) {
  const text = String(raw || '').trim();
  if (!text) return { ok: false, error: 'Input trống.' };
  const match = text.match(GROUP_URL_RE);
  if (match) {
    let groupUrl = ensureHttpUrl(match[0].replace(/[),.]+$/, ''));
    // strip trailing slash noise except keep path
    try {
      const u = new URL(groupUrl);
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

module.exports = { detectJoinInput, keywordIntelKey, normalizeJoinQuery };
