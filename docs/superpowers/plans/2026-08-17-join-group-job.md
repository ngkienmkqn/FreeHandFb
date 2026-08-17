# Join Group Job + Merged Executor Session Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Web users create join jobs (keyword or group link); Android runs a merged multi-select executor session that claims join → interaction → publishing and joins groups via Accessibility; success updates group intel.

**Architecture:** Add executor queue type `join` on Node (same lease/claim/complete path as publishing). Pure helpers detect keyword vs link and resolve intel keys. Dashboard gets a Join tab. Android replaces exclusive interaction/publishing modes with multi-select types and sequential claim priority. FbAutoService hardens keyword search (first group) + link join + simple questionnaire handling.

**Tech Stack:** Node.js + Express + PostgreSQL (`fh_executor_jobs`), vanilla HTML dashboard, Kotlin/Jetpack Compose Android, AccessibilityService, `node:test`.

**Spec:** `docs/superpowers/specs/2026-08-17-join-group-job-design.md`

## Global Constraints

- Job type name: `join` (queue_type and job.type both `join`)
- Claim join: `createdBy === username` only (owner-only, like publishing)
- Claim priority on device: `join` → `interaction` → `publishing`
- Keyword: first group in Facebook search results only
- On join success: `markAccountJoinedGroup(username, groupKey, 'join_job')`
- Keyword without URL: intel key `keyword:<normalized query>`
- `ALREADY_JOINED` reported as complete success, not fail
- Free-text questionnaire → fail `GROUP_QUESTIONNAIRE_REQUIRED` (retryable false)
- One Facebook job at a time (no concurrent FB tasks)
- Multi-line create: partial success `{ created: [], errors: [] }`
- No free-text questionnaire answers, no auto-add to user group list, no top-N join (v1)

---

## File map

| File | Responsibility |
|------|----------------|
| Create: `server/lib/executor-join-input.js` | Detect keyword/link, normalize query, keyword intel key |
| Create: `server/test/executor-join-input.test.js` | Unit tests for detect/normalize |
| Create: `server/test/executor-join-claim.test.js` | Claim owner-only for join (exported helper or pure canClaim slice) |
| Modify: `server/index.js` | joinQueue, persist/load, create API, claim rules, complete intel, queues/reset |
| Modify: `server/public/dashboard.html` | Join tab UI + API client |
| Modify: `app/.../ExecutorForegroundService.kt` | Multi-type session, claim priority, dispatch join, complete result fields |
| Modify: `app/.../ExecutorApp.kt` | Multi-select UI + start/stop merged session |
| Modify: `app/.../FbAutoService.kt` | Join steps: first search result, link join, questionnaire, result scrape |

---

### Task 1: Join input pure helpers (TDD)

**Files:**
- Create: `server/lib/executor-join-input.js`
- Create: `server/test/executor-join-input.test.js`

**Interfaces:**
- Produces:
  - `detectJoinInput(raw: string): { ok: true, kind: 'keyword'|'link', query: string|null, groupUrl: string|null } | { ok: false, error: string }`
  - `keywordIntelKey(query: string): string` → `keyword:<normalized>`
  - `normalizeJoinQuery(query: string): string` → lowercase, collapse whitespace, trim, max 200

- [ ] **Step 1: Write failing tests**

```js
'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
const { detectJoinInput, keywordIntelKey, normalizeJoinQuery } = require('../lib/executor-join-input');

test('detects facebook group link', () => {
  const r = detectJoinInput('https://www.facebook.com/groups/123456/');
  assert.equal(r.ok, true);
  assert.equal(r.kind, 'link');
  assert.match(r.groupUrl, /facebook\.com\/groups\/123456/i);
  assert.equal(r.query, null);
});

test('detects bare fb.com/groups link', () => {
  const r = detectJoinInput('fb.com/groups/abc');
  assert.equal(r.ok, true);
  assert.equal(r.kind, 'link');
});

test('detects keyword', () => {
  const r = detectJoinInput('  mua bán xe HN  ');
  assert.equal(r.ok, true);
  assert.equal(r.kind, 'keyword');
  assert.equal(r.query, 'mua bán xe HN');
  assert.equal(r.groupUrl, null);
});

test('rejects empty', () => {
  assert.equal(detectJoinInput('   ').ok, false);
});

test('rejects keyword longer than 200', () => {
  assert.equal(detectJoinInput('x'.repeat(201)).ok, false);
});

test('keywordIntelKey normalizes', () => {
  assert.equal(keywordIntelKey('  Mua   Bán  '), 'keyword:mua bán');
});
```

- [ ] **Step 2: Run tests — expect FAIL**

```bash
cd server && node --test test/executor-join-input.test.js
```

Expected: module not found / fail

- [ ] **Step 3: Implement `server/lib/executor-join-input.js`**

```js
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
```

Note: Plan uses a local URL normalize for group paths. In `index.js` create path, also pass `groupUrl` through existing `normalizeFbUrlForNative` when available for claim/open consistency.

- [ ] **Step 4: Run tests — expect PASS**

```bash
cd server && node --test test/executor-join-input.test.js
```

- [ ] **Step 5: Commit**

```bash
git add server/lib/executor-join-input.js server/test/executor-join-input.test.js
git commit -m "feat(server): detect join keyword vs group link input"
```

---

### Task 2: Server join queue wiring + create API

**Files:**
- Modify: `server/index.js` (stores, queues, bootstrap load, create endpoint, reset, queues GET)
- Create: `server/test/executor-join-create.test.js` (pure builder if extracted; else test detect batch loop only — prefer extract `buildJoinJobsFromBody`)

**Interfaces:**
- Consumes: `detectJoinInput` from `./lib/executor-join-input`
- Produces:
  - In-memory `joinQueue`
  - `POST /api/executor/join` → `{ created: publicJob[], errors: { line, input, error }[] }`
  - Queues API includes `join`

- [ ] **Step 1: Wire store constants and memory queue in `server/index.js`**

Near other store constants (~line 42):

```js
const JOIN_QUEUE_STORE = 'join_queue';
```

Near `publishingQueue`:

```js
let joinQueue = [];
```

In `persistToPostgres`:

```js
if (file === JOIN_QUEUE_STORE) return dbStore.replaceJobs('join', item.data || []);
```

In `executorQueues`:

```js
join: () => joinQueue
```

In `saveExecutorQueue`:

```js
if (type === 'join') saveState(JOIN_QUEUE_STORE, joinQueue);
```

In `reclaimExpiredExecutorJobs` and admin reset and bootstrap load (`loadJobs('join')`), include `'join'` wherever `interaction`/`publishing` are listed together.

Bootstrap (~2207):

```js
joinQueue = await dbStore.loadJobs('join');
```

- [ ] **Step 2: Add create helper + route**

```js
const { detectJoinInput } = require('./lib/executor-join-input');

function createJoinJobsFromInputs(inputs, user, scheduledAt) {
  const created = [];
  const errors = [];
  const now = Date.now();
  inputs.forEach((raw, index) => {
    const line = index + 1;
    const detected = detectJoinInput(raw);
    if (!detected.ok) {
      errors.push({ line, input: String(raw || ''), error: detected.error });
      return;
    }
    const payload = detected.kind === 'link'
      ? { kind: 'link', query: null, groupUrl: normalizeFbUrlForNative(detected.groupUrl) }
      : { kind: 'keyword', query: detected.query, groupUrl: null };
    const job = {
      id: `JOIN-${genId()}`,
      type: 'join',
      group: user.group,
      payload,
      status: 'QUEUED',
      attempts: 0,
      createdBy: user.username,
      createdAt: now,
      updatedAt: now,
      ...(scheduledAt > now ? { scheduledAt } : {})
    };
    joinQueue.push(job);
    created.push(job);
  });
  if (created.length) {
    saveExecutorQueue('join');
    emitExecutorUpdate(user.group);
  }
  return { created: created.map(publicExecutorJob), errors };
}

app.post('/api/executor/join', authMiddleware, (req, res) => {
  const scheduledAt = parseScheduledAt(req.body.scheduledAt);
  if (Number.isNaN(scheduledAt)) {
    return res.status(400).json({ error: 'Thời gian hẹn không hợp lệ.' });
  }
  let inputs = [];
  if (Array.isArray(req.body.inputs)) {
    inputs = req.body.inputs.map(v => String(v));
  } else if (req.body.input != null) {
    inputs = String(req.body.input).split(/\r?\n/);
  } else if (req.body.kind === 'link' && req.body.groupUrl) {
    inputs = [String(req.body.groupUrl)];
  } else if (req.body.kind === 'keyword' && req.body.query) {
    inputs = [String(req.body.query)];
  } else {
    return res.status(400).json({ error: 'Cần input, inputs, hoặc kind+query/groupUrl.' });
  }
  inputs = inputs.map(s => s.trim()).filter(Boolean);
  if (!inputs.length) return res.status(400).json({ error: 'Không có dòng input hợp lệ.' });
  const result = createJoinJobsFromInputs(inputs, req.user, scheduledAt);
  if (!result.created.length && result.errors.length) {
    return res.status(400).json(result);
  }
  res.status(201).json(result);
});
```

- [ ] **Step 3: Update GET queues + reset**

```js
for (const type of ['interaction', 'publishing', 'join']) {
```

Admin reset: clear `joinQueue`, save, include counts.

- [ ] **Step 4: Manual smoke (no DB if unit only)**

```bash
cd server && node --test test/executor-join-input.test.js
```

If server can start with env DB:

```bash
# create job with curl after login — optional in this task
```

- [ ] **Step 5: Commit**

```bash
git add server/index.js
git commit -m "feat(server): add join executor queue and create API"
```

---

### Task 3: Claim rules + complete intel for join

**Files:**
- Modify: `server/index.js` (`canClaimExecutorJob`, claim active scan, complete handler)
- Create: `server/test/executor-join-intel.test.js` testing pure `resolveJoinIntelKey(job, result)`

**Interfaces:**
- Produces:
  - `resolveJoinIntelKey(job, result): string | null`
  - join claim owner-only
  - complete marks intel with source `join_job`

- [ ] **Step 1: Write tests for intel key resolution**

```js
'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
const { resolveJoinIntelKey } = require('../lib/executor-join-input');

// Export resolveJoinIntelKey from executor-join-input.js in implementation step

test('prefers result.groupUrl', () => {
  const key = resolveJoinIntelKey(
    { payload: { kind: 'keyword', query: 'xe' } },
    { groupUrl: 'https://www.facebook.com/groups/9/' }
  );
  assert.match(key, /groups\/9/);
});

test('falls back to payload.groupUrl', () => {
  const key = resolveJoinIntelKey(
    { payload: { kind: 'link', groupUrl: 'https://www.facebook.com/groups/1/' } },
    {}
  );
  assert.match(key, /groups\/1/);
});

test('keyword fallback', () => {
  assert.equal(
    resolveJoinIntelKey({ payload: { kind: 'keyword', query: 'Mua Bán' } }, {}),
    'keyword:mua bán'
  );
});
```

- [ ] **Step 2: Add `resolveJoinIntelKey` to `executor-join-input.js` and export**

```js
function resolveJoinIntelKey(job, result = {}) {
  const fromResult = String(result.groupUrl || '').trim();
  if (fromResult) return fromResult;
  const fromPayload = String(job?.payload?.groupUrl || '').trim();
  if (fromPayload) return fromPayload;
  const q = String(job?.payload?.query || '').trim();
  if (q) return keywordIntelKey(q);
  return null;
}
```

- [ ] **Step 3: Extend `canClaimExecutorJob`**

```js
if (type === 'publishing' || type === 'join') {
  return item.createdBy === user.username;
}
```

- [ ] **Step 4: Claim active job scan includes join**

```js
const active = ['interaction', 'publishing', 'join']
  .flatMap(queueType => getExecutorQueue(queueType) || [])
  .find(job => job.status === 'RUNNING' && job.claimedBy === req.user.username);
```

Ensure `getExecutorQueue('join')` works so `POST /api/executor/join/claim` works via existing `/:type/claim`.

- [ ] **Step 5: On complete, mark joined for join jobs**

Inside `POST /api/executor/jobs/:id/complete` after setting SUCCEEDED:

```js
if (type === 'join') {
  const { resolveJoinIntelKey } = require('./lib/executor-join-input'); // prefer top-level require once
  const groupKey = resolveJoinIntelKey(job, req.body.result || {});
  if (groupKey && req.user.username) {
    markAccountJoinedGroup(req.user.username, groupKey, 'join_job');
    job.payload = {
      ...job.payload,
      resolvedGroupUrl: (req.body.result || {}).groupUrl || job.payload.groupUrl || null,
      resolvedGroupName: (req.body.result || {}).groupName || null
    };
  }
}
```

- [ ] **Step 6: Run tests**

```bash
cd server && node --test test/executor-join-input.test.js test/executor-join-intel.test.js
```

Expected: PASS

Also run full suite:

```bash
cd server && npm test
```

- [ ] **Step 7: Commit**

```bash
git add server/lib/executor-join-input.js server/test/executor-join-intel.test.js server/index.js
git commit -m "feat(server): join claim owner-only and intel on complete"
```

---

### Task 4: Dashboard Join tab

**Files:**
- Modify: `server/public/dashboard.html`

**Interfaces:**
- Consumes: `POST /api/executor/join`, `GET /api/executor/queues` (with `join`)
- Produces: UI tab Join group

- [ ] **Step 1: Add nav item + tab content**

Bottom nav: insert between publish and articles:

```html
<button class="nav-item" onclick="switchTab('join', this); fetchExecutorQueues()">
  <i class="ph-fill ph-users-three"></i>
  <span>Join</span>
</button>
```

Tab panel (mirror publish structure):

```html
<div class="tab-content" id="tab-join">
  <div class="queue-strip">
    <div class="queue-chip"><div class="val" id="joinQueued">0</div><div class="lbl">Join chờ</div></div>
    <div class="queue-chip"><div class="val" id="joinRunning" style="color:var(--text-muted)">0</div><div class="lbl">Đang join</div></div>
  </div>
  <div class="glass card">
    <div class="card-header"><i class="ph-fill ph-user-plus"></i> Tìm & tham gia group</div>
    <p class="section-hint">Mỗi dòng: từ khóa (lấy group đầu trong search) hoặc link group Facebook.</p>
    <label class="field-label" for="joinInputs">Từ khóa hoặc link group</label>
    <textarea id="joinInputs" rows="5" placeholder="mua bán xe HN&#10;https://facebook.com/groups/..."></textarea>
    <label class="field-label" for="joinScheduledAt">Lên lịch (tuỳ chọn)</label>
    <input id="joinScheduledAt" type="datetime-local" />
    <button class="btn-success" style="width:100%;margin-top:14px" onclick="createJoinJobs()">
      <i class="ph-bold ph-user-plus"></i> Đưa vào queue join
    </button>
  </div>
  <div class="glass card">
    <div class="card-header" style="justify-content:space-between">
      <div><i class="ph-fill ph-list-dashes"></i> Job join</div>
      <button class="btn-secondary" style="font-size:0.8rem;padding:4px 10px" onclick="fetchExecutorQueues()">Tải lại</button>
    </div>
    <div id="executorJobListJoin"></div>
  </div>
</div>
```

- [ ] **Step 2: JS create + render**

```js
async function createJoinJobs() {
  const raw = document.getElementById('joinInputs').value || '';
  const scheduledAt = document.getElementById('joinScheduledAt').value || null;
  const body = { input: raw };
  if (scheduledAt) body.scheduledAt = scheduledAt;
  const res = await api('POST', '/api/executor/join', body);
  if (!res.ok) {
    toast(res.data?.error || (res.data?.errors && res.data.errors[0]?.error) || 'Tạo join job thất bại');
    return;
  }
  const n = (res.data.created || []).length;
  const e = (res.data.errors || []).length;
  toast(`Đã tạo ${n} job join` + (e ? `, ${e} dòng lỗi` : ''));
  if (e) console.warn(res.data.errors);
  document.getElementById('joinInputs').value = '';
  await fetchExecutorQueues();
}
```

In `fetchExecutorQueues`, read `data.join`:

```js
const joinCounts = data.join?.counts || {};
const elJ = document.getElementById('joinQueued');
if (elJ) elJ.textContent = joinCounts.QUEUED || 0;
const elR = document.getElementById('joinRunning');
if (elR) elR.textContent = joinCounts.RUNNING || 0;
// render join jobs into #executorJobListJoin similar to publishing list
// show kind + query/groupUrl + status + lastError
```

Update queue strips on interact/publish tabs optionally with a muted join count (optional, not required).

- [ ] **Step 3: Manual browser check**

Start server, open dashboard, login as non-admin user, create keyword + link lines, confirm list shows QUEUED and counts update.

- [ ] **Step 4: Commit**

```bash
git add server/public/dashboard.html
git commit -m "feat(web): dashboard tab to create join group jobs"
```

---

### Task 5: Android merged multi-select executor session

**Files:**
- Modify: `app/src/main/java/com/example/commenthelper/ExecutorForegroundService.kt`
- Modify: `app/src/main/java/com/example/commenthelper/ExecutorApp.kt`

**Interfaces:**
- Produces:
  - `EXTRA_TYPES` = `StringArray` of selected types
  - `activeTypes: StateFlow<Set<String>>` (or keep `activeMode` as `"multi"` / joined string `"join,interaction"`)
  - Claim order: join, interaction, publishing
  - Dispatch branch for `join`

- [ ] **Step 1: Service constants and start API**

```kotlin
companion object {
  const val ACTION_START = "..."
  const val ACTION_STOP = "..."
  const val EXTRA_TYPES = "types" // StringArrayList
  const val TYPE_INTERACTION = "interaction"
  const val TYPE_PUBLISHING = "publishing"
  const val TYPE_JOIN = "join"
  val CLAIM_PRIORITY = listOf(TYPE_JOIN, TYPE_INTERACTION, TYPE_PUBLISHING)

  val activeTypes = MutableStateFlow<Set<String>>(emptySet())
  // Keep activeMode for backward UI: non-null when session running
  val activeMode = MutableStateFlow<String?>(null) // "session" when running
}
```

- [ ] **Step 2: `startExecutor(types: Set<String>)`**

```kotlin
private fun startExecutor(types: Set<String>) {
  val cleaned = types.filter { it in setOf(TYPE_JOIN, TYPE_INTERACTION, TYPE_PUBLISHING) }.toSet()
  if (cleaned.isEmpty()) return
  if (workerJob?.isActive == true) {
    // update selected types live
    activeTypes.value = cleaned
    prefs.edit().putStringSet("executor_running_types", cleaned).apply()
    return
  }
  activeTypes.value = cleaned
  activeMode.value = "session"
  prefs.edit()
    .putStringSet("executor_running_types", cleaned)
    .putString("executor_running_mode", "session")
    .apply()
  startForeground(...)
  workerJob = scope.launch { workerLoop() }
}
```

On sticky restart: read `executor_running_types` from prefs.

Stop clears types + mode.

- [ ] **Step 3: Claim loop priority**

```kotlin
private suspend fun claimNext(): ClaimedJob? {
  val types = activeTypes.value
  for (type in CLAIM_PRIORITY) {
    if (type !in types) continue
    val job = claimType(type) ?: continue
    return job
  }
  return null
}

private suspend fun claimType(type: String): ClaimedJob? {
  // same HTTP as old claimNext(mode) but path /api/executor/$type/claim
}
```

In worker loop idle branch call `claimNext()` instead of `claimNext(mode)`.

- [ ] **Step 4: Dispatch join**

In `dispatchToAccessibility`:

```kotlin
when (job.type) {
  TYPE_PUBLISHING -> { /* existing */ }
  TYPE_JOIN -> {
    val kind = job.payload.optString("kind")
    val url = if (kind == "link") job.payload.getString("groupUrl")
              else "fb_join_keyword:${job.payload.getString("query")}"
    withContext(Dispatchers.Main) {
      accessibility.startProcessing(listOf(
        FbAutoService.TaskItem(
          postId = job.id,
          url = url,
          comment = "",
          isJoinGroup = true,
          executorJobId = job.id,
          reportLegacyCompletion = false
        )
      ), appendNotificationScan = false)
    }
  }
  else -> { /* interaction existing */ }
}
```

(Requires Task 6 `isJoinGroup` flag — implement flag stub in Task 5 if needed: add field default false so compile works; full join UX in Task 6.)

- [ ] **Step 5: Enrich complete body for join results**

`FbAutoService` will put extras on POST_DONE broadcast: `groupUrl`, `groupName`, `alreadyJoined`. Until then optional:

```kotlin
// finishClaimedJob success branch:
val result = JSONObject().put("completedAt", System.currentTimeMillis())
// if intent extras available from resultReceiver, put groupUrl/groupName/alreadyJoined
```

Update `resultReceiver` to read extras and pass into `finishClaimedJob`.

- [ ] **Step 6: UI multi-select in `ExecutorApp`**

Replace dual-tab exclusive panels with one **Chạy job** panel (tabs can become: Chạy | Cài đặt only, or keep info tabs but single start):

```kotlin
var selJoin by remember { mutableStateOf(prefs.getBoolean("sel_join", true)) }
var selInteract by remember { mutableStateOf(prefs.getBoolean("sel_interaction", true)) }
var selPublish by remember { mutableStateOf(prefs.getBoolean("sel_publishing", false)) }

// Checkboxes Row
// Start:
val types = buildList {
  if (selJoin) add(TYPE_JOIN)
  if (selInteract) add(TYPE_INTERACTION)
  if (selPublish) add(TYPE_PUBLISHING)
}
// validate types.isNotEmpty()
Intent(...).setAction(ACTION_START).putStringArrayListExtra(EXTRA_TYPES, ArrayList(types))

// Queue counts: show three numbers — extend queueCounts StateFlow to Triple or Map
```

Update queue poll:

```kotlin
val interaction = json.getJSONObject("interaction").getJSONObject("counts").optInt("QUEUED", 0)
val publishing = json.getJSONObject("publishing").getJSONObject("counts").optInt("QUEUED", 0)
val join = json.optJSONObject("join")?.getJSONObject("counts")?.optInt("QUEUED", 0) ?: 0
// store Triple or data class
```

Remove “other mode running blocks start” exclusive logic.

- [ ] **Step 7: Compile check**

```bash
./gradlew :app:compileDebugKotlin
```

Expected: SUCCESS (or only missing `isJoinGroup` if not added — add field).

- [ ] **Step 8: Commit**

```bash
git add app/src/main/java/com/example/commenthelper/ExecutorForegroundService.kt \
  app/src/main/java/com/example/commenthelper/ExecutorApp.kt
git commit -m "feat(android): merged multi-select executor session with join claim"
```

---

### Task 6: Accessibility join flow (keyword first result + link + questionnaire)

**Files:**
- Modify: `app/src/main/java/com/example/commenthelper/FbAutoService.kt`

**Interfaces:**
- Consumes: `TaskItem.isJoinGroup`, url `fb_join_keyword:` or group URL
- Produces: success/fail with reason codes; optional groupUrl/groupName on broadcast

- [ ] **Step 1: Extend TaskItem + Steps**

```kotlin
data class TaskItem(
  ...
  val isJoinGroup: Boolean = false
)

private enum class Step {
  ...
  JOIN_SEARCH_RESULTS,   // keyword: pick first group
  JOIN_OPEN_GROUP,       // on group page: join / already joined
  JOIN_QUESTIONNAIRE,    // simple form
  ...
}
```

- [ ] **Step 2: `executePostTask` for join**

```kotlin
if (task.isJoinGroup || task.url.startsWith("fb_join_keyword:")) {
  currentStep = if (task.url.startsWith("fb_join_keyword:")) Step.WAITING_FOR_FB_LOAD else Step.WAITING_FOR_FB_LOAD
  // open keyword deep link OR group url (existing)
  // after load for keyword → JOIN_SEARCH_RESULTS
  // after load for link → JOIN_OPEN_GROUP
}
```

Set a field `joinFlowKind = keyword|link` when starting.

- [ ] **Step 3: Replace blind success-after-30-retries for keyword**

In `handleWaitingForLoad`, when join keyword:

```kotlin
if (isJoinKeywordTask(task)) {
  // once search UI has results (list items / "nhóm" labels), go JOIN_SEARCH_RESULTS
  // do NOT markCurrentDone(true) on timer alone
}
```

- [ ] **Step 4: `handleJoinSearchResults`**

```kotlin
private fun handleJoinSearchResults() {
  val root = rootInActiveWindow ?: return
  val nodes = findAllNodes(root)
  // Prefer first clickable row that looks like a group result.
  // Heuristic v1:
  // 1) Find nodes with contentDescription/text containing "nhóm" / "group" / "thành viên" / "members"
  // 2) Else first clickable item below search box in results list
  val candidate = findFirstGroupSearchResult(nodes)
  if (candidate == null) {
    if (retryCount > 20) {
      markCurrentDone(false, "GROUP_NOT_FOUND", "Không thấy group trong kết quả tìm kiếm.", retryable = true)
    }
    recycle...
    return
  }
  performClick(candidate)
  currentStep = Step.JOIN_OPEN_GROUP
  retryCount = 0
  setNextStepDelay(STEP_DELAY)
}
```

Implement `findFirstGroupSearchResult` conservatively; dump X-RAY on miss.

- [ ] **Step 5: `handleJoinOpenGroup`**

```kotlin
private fun handleJoinOpenGroup() {
  val root = rootInActiveWindow ?: return
  val nodes = findAllNodes(root)
  // already joined?
  if (nodes.any { text/desc contains "đã tham gia" || "đã gia nhập" || "joined" || "leave group" || "rời nhóm" }) {
    scrapeJoinMeta(nodes)
    markCurrentDone(success = true, reasonCode = "ALREADY_JOINED", ...)
    // ensure POST_DONE success=true
    return
  }
  // free-text questionnaire?
  if (isJoinQuestionnaireWithFreeText(nodes)) {
    markCurrentDone(false, "GROUP_QUESTIONNAIRE_REQUIRED", "Group yêu cầu trả lời câu hỏi.", retryable = false)
    return
  }
  // simple checks + submit
  if (trySimpleJoinQuestionnaire(nodes)) {
    setNextStepDelay(800)
    return
  }
  // click join button via Engine.groupJoin (contains / equals anchors)
  val joinBtn = findJoinButton(nodes)
  if (joinBtn != null) {
    // checkpoint irreversible? join is semi-irreversible — optional checkpoint before click
    performClick(joinBtn)
    retryCount = 0
    setNextStepDelay(1200)
    return
  }
  if (retryCount > 25) {
    markCurrentDone(false, "JOIN_BUTTON_NOT_FOUND", "Không tìm thấy nút tham gia nhóm.", retryable = true)
  }
}
```

- [ ] **Step 6: Wire `markCurrentDone` extras**

Extend broadcast intent:

```kotlin
putExtra("groupUrl", lastJoinedGroupUrl ?: "")
putExtra("groupName", lastJoinedGroupName ?: "")
putExtra("alreadyJoined", alreadyJoined)
```

`ExecutorForegroundService.resultReceiver` packs these into complete `result`.

- [ ] **Step 7: `interceptGroupJoin` free-text fail for join tasks only**

When `currentTask?.isJoinGroup == true` and questionnaire has EditText that are not empty-required simple — if any EditText visible and required free text, fail instead of looping forever.

Current intercept fills checkboxes and taps submit — keep for simple forms; if EditTexts remain focused/required after submit attempts > 3 → `GROUP_QUESTIONNAIRE_REQUIRED`.

- [ ] **Step 8: Compile**

```bash
./gradlew :app:compileDebugKotlin
```

- [ ] **Step 9: Commit**

```bash
git add app/src/main/java/com/example/commenthelper/FbAutoService.kt \
  app/src/main/java/com/example/commenthelper/ExecutorForegroundService.kt
git commit -m "feat(android): accessibility join first search result and link join"
```

---

### Task 7: End-to-end verification checklist

**Files:** none (QA)

- [ ] **Step 1: Server tests green**

```bash
cd server && npm test
```

Expected: all PASS including join helpers.

- [ ] **Step 2: API flow (curl)**

```bash
# login → token
# POST /api/executor/join { "input": "test group\nhttps://facebook.com/groups/1" }
# GET /api/executor/queues → join.counts.QUEUED >= 1
# claim as owner deviceId=test → 200
# complete with result.groupUrl → intel joined
# claim as other user → 204
```

- [ ] **Step 3: Dashboard**

Create multi-line jobs; list shows kind/status; errors for bad lines.

- [ ] **Step 4: Device QA**

1. Tick only Join → claim keyword job → first result → join or already joined  
2. Tick Join+Interact → join job first when both queued  
3. Link join job opens group and joins  
4. Free-text questionnaire fails with correct code  
5. Stop mid-job → interrupted  

- [ ] **Step 5: Final commit if fixes needed**

```bash
git add -A
git commit -m "fix: join group job QA follow-ups"
```

---

## Self-review (plan vs spec)

| Spec requirement | Task |
|------------------|------|
| Job type `join` separate | Task 2 |
| Keyword/link auto-detect | Task 1–2 |
| Multi-line partial success | Task 2 create helper |
| Owner-only claim | Task 3 |
| Intel on success `join_job` | Task 3 |
| Keyword intel key fallback | Task 1, 3 |
| ALREADY_JOINED → complete | Task 6 + 5 extras |
| Questionnaire simple auto / free-text fail | Task 6 |
| Merged multi-select session | Task 5 |
| Priority join→interact→publish | Task 5 |
| First search result only | Task 6 |
| Dashboard form | Task 4 |
| No free-text answers / no group list auto-add | Out of scope (not implemented) |

**Placeholder scan:** none intentional.  
**Type consistency:** `join` string used for queue type, job.type, Android TYPE_JOIN, claim path.

---

## Execution handoff

Plan saved to `docs/superpowers/plans/2026-08-17-join-group-job.md`.

**Two execution options:**

1. **Subagent-Driven (recommended)** — fresh subagent per task, review between tasks  
2. **Inline Execution** — execute tasks in this session with checkpoints  

Which approach?
