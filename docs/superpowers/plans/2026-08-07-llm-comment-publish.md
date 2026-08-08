# LLM Comment/Publish + Executor Outbox Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add Wealify LLM preview generation for interaction comment pools and publishing content (persist only on submit), admin `llmEnabled` toggle, and Android lifecycle outbox retries for complete/fail.

**Architecture:** Server module `lib/wealify-llm.js` calls OpenAI-compatible Wealify API; Express routes gate on `appSettings.llmEnabled` + env key; dashboard preview buttons; ExecutorForegroundService retries lifecycle POSTs and persists outbox in SharedPreferences.

**Tech Stack:** Node.js/Express, node:test, vanilla dashboard/admin HTML, Kotlin ExecutorForegroundService, Wealify `gemma3:12b-it-qat`.

## Global Constraints

- Default `llmEnabled = false`.
- Env key name: `WEALIFY_LLM_API_KEY`; base URL default `https://llm.wealify.app/v1`.
- Never commit API keys; never return key material from APIs.
- LLM generate endpoints must not write DB.
- Truncate user text inputs to 4000 characters before prompting.

---

### Task 1: Wealify LLM client + unit tests

**Files:**
- Create: `server/lib/wealify-llm.js`
- Create: `server/test/wealify-llm.test.js`

**Interfaces:**
- Produces:
  - `getLlmConfig()` → `{ apiKey, baseUrl, model, configured: boolean }`
  - `parseCommentList(text: string): string[]`
  - `parsePostContent(text: string): { content: string, variants: string[] }`
  - `generateComments({ postText, count, fetchImpl? }): Promise<string[]>`
  - `generatePostContent({ draft, fetchImpl? }): Promise<{ content: string, variants: string[] }>`

- [ ] **Step 1: Implement `server/lib/wealify-llm.js`**

```js
'use strict';

const DEFAULT_BASE = 'https://llm.wealify.app/v1';
const DEFAULT_MODEL = 'gemma3:12b-it-qat';

function getLlmConfig() {
  const apiKey = String(process.env.WEALIFY_LLM_API_KEY || '').trim();
  const baseUrl = String(process.env.WEALIFY_LLM_BASE_URL || DEFAULT_BASE).replace(/\/$/, '');
  return { apiKey, baseUrl, model: DEFAULT_MODEL, configured: !!apiKey };
}

function truncate(text, max = 4000) {
  return String(text || '').trim().slice(0, max);
}

function parseCommentList(text) {
  const raw = String(text || '').trim();
  if (!raw) return [];
  try {
    const start = raw.indexOf('[');
    const end = raw.lastIndexOf(']');
    if (start >= 0 && end > start) {
      const arr = JSON.parse(raw.slice(start, end + 1));
      if (Array.isArray(arr)) {
        return [...new Set(arr.map(x => String(x || '').replace(/\s+/g, ' ').trim()).filter(Boolean))].slice(0, 50);
      }
    }
  } catch (_) {}
  return [...new Set(raw.split(/\r?\n/).map(l => l.replace(/^\s*[-*\d.]+)\s*/, '').replace(/\s+/g, ' ').trim()).filter(Boolean))].slice(0, 50);
}

function parsePostContent(text) {
  const raw = String(text || '').trim();
  try {
    const start = raw.indexOf('{');
    const end = raw.lastIndexOf('}');
    if (start >= 0 && end > start) {
      const obj = JSON.parse(raw.slice(start, end + 1));
      const content = String(obj.content || obj.text || '').trim();
      const variants = Array.isArray(obj.variants)
        ? obj.variants.map(v => String(v || '').trim()).filter(Boolean)
        : [];
      if (content) return { content, variants: variants.slice(0, 5) };
    }
  } catch (_) {}
  return { content: raw, variants: [] };
}

async function chatCompletions({ messages, fetchImpl }) {
  const cfg = getLlmConfig();
  if (!cfg.configured) {
    const err = new Error('WEALIFY_LLM_API_KEY chưa được cấu hình.');
    err.statusCode = 503;
    throw err;
  }
  const fetchFn = fetchImpl || globalThis.fetch;
  if (!fetchFn) {
    const err = new Error('fetch không khả dụng trên runtime này.');
    err.statusCode = 500;
    throw err;
  }
  const res = await fetchFn(`${cfg.baseUrl}/chat/completions`, {
    method: 'POST',
    headers: {
      Authorization: `Bearer ${cfg.apiKey}`,
      'Content-Type': 'application/json'
    },
    body: JSON.stringify({ model: cfg.model, messages, temperature: 0.8 })
  });
  const bodyText = await res.text();
  if (!res.ok) {
    const err = new Error(`Wealify LLM lỗi HTTP ${res.status}: ${bodyText.slice(0, 300)}`);
    err.statusCode = 502;
    throw err;
  }
  let data;
  try { data = JSON.parse(bodyText); } catch {
    const err = new Error('Wealify trả về JSON không hợp lệ.');
    err.statusCode = 502;
    throw err;
  }
  return String(data?.choices?.[0]?.message?.content || '').trim();
}

async function generateComments({ postText, count = 5, fetchImpl } = {}) {
  const n = Math.max(1, Math.min(30, Number(count) || 5));
  const text = truncate(postText);
  if (!text) {
    const err = new Error('postText là bắt buộc.');
    err.statusCode = 400;
    throw err;
  }
  const content = await chatCompletions({
    fetchImpl,
    messages: [
      {
        role: 'system',
        content: 'Bạn tạo comment Facebook tiếng Việt ngắn, tự nhiên, không spam, không link. Chỉ trả JSON array string, không markdown.'
      },
      {
        role: 'user',
        content: `Dựa trên nội dung bài sau, tạo đúng ${n} comment khác nhau:\n\n${text}`
      }
    ]
  });
  const comments = parseCommentList(content);
  if (!comments.length) {
    const err = new Error('LLM không trả về comment hợp lệ.');
    err.statusCode = 502;
    throw err;
  }
  return comments.slice(0, n);
}

async function generatePostContent({ draft, fetchImpl } = {}) {
  const text = truncate(draft);
  if (!text) {
    const err = new Error('draft là bắt buộc.');
    err.statusCode = 400;
    throw err;
  }
  const contentRaw = await chatCompletions({
    fetchImpl,
    messages: [
      {
        role: 'system',
        content: 'Bạn viết bài đăng Facebook group tiếng Việt tự nhiên. Trả JSON {"content":"...","variants":["..."]} không markdown. Không chèn link Zalo.'
      },
      {
        role: 'user',
        content: `Viết lại thành bài đăng hoàn chỉnh từ draft sau, thêm 2 variants ngắn hơn nếu phù hợp:\n\n${text}`
      }
    ]
  });
  const parsed = parsePostContent(contentRaw);
  if (!parsed.content) {
    const err = new Error('LLM không trả về nội dung bài hợp lệ.');
    err.statusCode = 502;
    throw err;
  }
  return parsed;
}

module.exports = {
  getLlmConfig, truncate, parseCommentList, parsePostContent,
  chatCompletions, generateComments, generatePostContent
};
```

- [ ] **Step 2: Add tests in `server/test/wealify-llm.test.js`** covering JSON array parse, line parse, post JSON parse, generateComments with mock fetch.

- [ ] **Step 3: Run** `cd server && npm test` — expect pass.

---

### Task 2: Settings + LLM routes in `server/index.js`

**Files:**
- Modify: `server/index.js`
- Consumes: `./lib/wealify-llm`

- [ ] **Step 1:** `const wealifyLlm = require('./lib/wealify-llm');` and default `appSettings = { maxGroupPostsPerDay: 1, llmEnabled: false }`.

- [ ] **Step 2:** Extend `GET /api/settings` to include `llmEnabled: !!appSettings.llmEnabled` and `llmConfigured: wealifyLlm.getLlmConfig().configured`.

- [ ] **Step 3:** Extend `POST /api/settings` (admin) to accept `llmEnabled` boolean alongside `maxGroupPostsPerDay`.

- [ ] **Step 4:** Add routes:

```js
function requireLlmEnabled(req, res) {
  if (!appSettings.llmEnabled) {
    res.status(403).json({ error: 'LLM đang tắt trên server.' });
    return false;
  }
  return true;
}

app.post('/api/llm/generate-comments', authMiddleware, async (req, res) => {
  if (!requireLlmEnabled(req, res)) return;
  try {
    const comments = await wealifyLlm.generateComments({
      postText: req.body.postText,
      count: req.body.count
    });
    res.json({ comments });
  } catch (e) {
    res.status(e.statusCode || 500).json({ error: e.message || 'Sinh comment thất bại.' });
  }
});

app.post('/api/llm/generate-post', authMiddleware, async (req, res) => {
  if (!requireLlmEnabled(req, res)) return;
  try {
    const result = await wealifyLlm.generatePostContent({ draft: req.body.draft });
    res.json(result);
  } catch (e) {
    res.status(e.statusCode || 500).json({ error: e.message || 'Sinh bài thất bại.' });
  }
});
```

- [ ] **Step 5:** Make `complete` idempotent when `job.status === 'SUCCEEDED'` and (`leaseToken` matches `job.result?.lastLeaseToken` OR body `leaseToken` equals stored). On successful complete, store `result.lastLeaseToken` before clearing live lease — actually lease is nulled on complete; store `completedLeaseToken` on job before nulling.

---

### Task 3: Admin + Dashboard UI

**Files:**
- Modify: `server/public/admin.html`
- Modify: `server/public/dashboard.html`

- [ ] **Step 1:** Admin settings: checkbox `#cfgLlmEnabled`, label key status `#cfgLlmKeyStatus`; load/save via existing settings helpers.

- [ ] **Step 2:** Dashboard interaction card: input N (default from comment qty), button “Sinh comment AI” → fill `#interactionCommentPool` (one per line). Disable when `!llmEnabled`.

- [ ] **Step 3:** Dashboard publishing card: button “Sinh nội dung AI” → fill `#publishingContent`; if variants, show select or append note. Disable when `!llmEnabled`.

- [ ] **Step 4:** On dashboard load after auth, `GET /api/settings` and toggle button visibility.

---

### Task 4: Android lifecycle outbox

**Files:**
- Modify: `app/src/main/java/com/example/commenthelper/ExecutorForegroundService.kt`

- [ ] **Step 1:** Add prefs key `executor_lifecycle_outbox` (JSONArray of `{jobId, action, body, leaseToken, attempts, nextAt}`).

- [ ] **Step 2:** Change `finishClaimedJob` / stop path: on non-2xx from complete/fail/interrupted, enqueue outbox and keep retrying; only `clearActiveJob` after success or after max attempts (then still clear but leave outbox for later flush).

- [ ] **Step 3:** At start of `workerLoop`, call `flushLifecycleOutbox()`.

- [ ] **Step 4:** Build APK manually smoke: not required in CI; logic review sufficient if no device.

---

### Task 5: Docs touch-up

**Files:**
- Modify: `docs/README.md` section 5b / changelog — note feature + env vars.
- Modify: `docs/wealify-llm.md` — point to server integration (`WEALIFY_LLM_API_KEY`, `llmEnabled`).

---

## Spec coverage check

| Spec item | Task |
|-----------|------|
| Generate comments preview | 1, 2, 3 |
| Generate post preview | 1, 2, 3 |
| Persist only on submit | existing APIs unchanged |
| llmEnabled toggle | 2, 3 |
| Env key | 1, 5 |
| Outbox retry | 4 |
| Idempotent complete | 2 |
| No runtime LLM on device | implied |
