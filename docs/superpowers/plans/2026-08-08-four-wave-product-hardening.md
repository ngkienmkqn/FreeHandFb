# Four-Wave Product Hardening Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Ship ops/reliability (W1), security hygiene (W2), operator UX (W3), then slim product MVP (W4) in sequence so the Facebook executor farm is safer and operable before adding more features.

**Architecture:** Keep the existing Express monolith + vanilla dashboards + Kotlin executor. Extract small pure helpers under `server/lib/` when logic is testable (schedule already exists; add `executor-resolve.js` for INTERRUPTED transitions). Android gates claims on local `block_timeout_epoch`. Wave 4 adds target time windows and joined-account preference without campaign/worker-pool.

**Tech Stack:** Node.js/Express, `node:test`, PostgreSQL via `server/db/store.js`, vanilla `dashboard.html`/`admin.html`, Kotlin `ExecutorForegroundService` / `ExecutorApp` / `FbAutoService`.

## Global Constraints

- Spec Wave 1: `docs/superpowers/specs/2026-08-08-ops-reliability-wave1-design.md`.
- Wave 4 scope = **MVP mỏng** (user chose A): `maxRuntimeHours`, resume `NEEDS_REVIEW`, prefer joined accounts, target active window/deadline — **no** campaign / worker-pool / DRAFT lifecycle.
- `onlineOnly` = **remove from UI + ignore** (user chose A); do not build device presence.
- Do **not** auto-resolve `INTERRUPTED` without human action.
- Do **not** git commit unless the user explicitly asks in chat (skip commit steps).
- Never commit secrets; remove private keys / API keys from docs when touching those files.
- Prefer extending existing APIs/UI patterns over new frameworks.
- After each Wave: run relevant `node --test` and a short manual smoke before starting the next Wave.

## Decisions locked

| Topic | Decision |
|-------|----------|
| Sequencing | W1 → W2 → W3 → W4 |
| W4 depth | Slim MVP, not full product spec |
| `onlineOnly` | Drop from UI; server may keep DB column default, ignore at claim |
| Speed pacing | **Already implemented** via `server/lib/executor-schedule.js` + `planInteractionTarget` |

## File map (by wave)

| Wave | Create | Modify |
|------|--------|--------|
| W1 | `server/lib/executor-resolve.js`, `server/test/executor-resolve.test.js` | `server/index.js`, `server/public/dashboard.html`, `ExecutorForegroundService.kt` |
| W2 | — | `server/index.js` (seed, logs, OTA auth), `docs/README.md`, `docs/wealify-llm.md` (scrub secrets) |
| W3 | — | `ExecutorApp.kt`, `MainActivity.kt` (reuse settings pieces), `admin.html`, optional light poll on `dashboard.html` |
| W4 | `server/lib/executor-target-window.js`, tests | `server/index.js`, `dashboard.html`, claim/plan paths |

---

# Wave 1 — Ops + Reliability

### Task 1: Confirm speed scheduling (already done)

**Files:**
- Verify: `server/lib/executor-schedule.js`
- Verify: `server/index.js` (`job.scheduledAt = scheduledAtForJobIndex(...)`)
- Test: `server/test/executor-schedule.test.js`

**Interfaces:**
- Consumes: none
- Produces: `spreadMsForSpeed(speed)`, `scheduledAtForJobIndex(now, index, jobCount, speed)` already exported

- [ ] **Step 1: Run existing schedule tests**

Run: `cd server && node --test test/executor-schedule.test.js`  
Expected: PASS

- [ ] **Step 2: Spot-check planner wiring**

Confirm `planInteractionTarget` sets `job.scheduledAt` for each new job. If missing, add:

```js
const { scheduledAtForJobIndex } = require('./lib/executor-schedule');
// inside create loop:
job.scheduledAt = scheduledAtForJobIndex(now, i, jobCount, target.speed);
```

---

### Task 2: Pure resolve helper + unit tests

**Files:**
- Create: `server/lib/executor-resolve.js`
- Create: `server/test/executor-resolve.test.js`

**Interfaces:**
- Produces:
  - `applyJobResolve(job, { action, note, username, now })` → `{ ok: true, job }` or `{ ok: false, statusCode, error }`
  - Actions: `'mark_succeeded' | 'requeue' | 'fail'`
  - Mutates a shallow copy pattern: function mutates the passed `job` object in place (same as queue items)

- [ ] **Step 1: Write failing tests**

```js
'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
const { applyJobResolve } = require('../lib/executor-resolve');

function interruptedJob() {
  return {
    id: 'INT-1', status: 'INTERRUPTED', leaseToken: 'abc', claimedBy: 'u1',
    deviceId: 'd1', claimedAt: 1, heartbeatAt: 1, irreversibleAt: 2,
    attempts: 1, payload: { actions: { like: true } }
  };
}

test('mark_succeeded', () => {
  const job = interruptedJob();
  const r = applyJobResolve(job, { action: 'mark_succeeded', note: 'ok', username: 'admin', now: 100 });
  assert.equal(r.ok, true);
  assert.equal(job.status, 'SUCCEEDED');
  assert.equal(job.resolveAction, 'mark_succeeded');
  assert.equal(job.resolvedBy, 'admin');
  assert.equal(job.leaseToken, undefined);
});

test('requeue clears lease fields', () => {
  const job = interruptedJob();
  const r = applyJobResolve(job, { action: 'requeue', username: 'admin', now: 100 });
  assert.equal(r.ok, true);
  assert.equal(job.status, 'QUEUED');
  assert.equal(job.claimedBy, undefined);
  assert.equal(job.irreversibleAt, undefined);
  assert.equal(job.attempts, 1);
});

test('fail does not invent replacement flag', () => {
  const job = interruptedJob();
  const r = applyJobResolve(job, { action: 'fail', note: 'bad', username: 'admin', now: 100 });
  assert.equal(r.ok, true);
  assert.equal(job.status, 'FAILED');
  assert.equal(job.lastError, 'bad');
  assert.equal(job.createReplacement, undefined);
});

test('reject non-interrupted', () => {
  const job = { status: 'QUEUED' };
  const r = applyJobResolve(job, { action: 'requeue', username: 'a', now: 1 });
  assert.equal(r.ok, false);
  assert.equal(r.statusCode, 409);
});
```

- [ ] **Step 2: Run tests — expect FAIL**

Run: `cd server && node --test test/executor-resolve.test.js`  
Expected: FAIL (module missing)

- [ ] **Step 3: Implement helper**

```js
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
```

- [ ] **Step 4: Run tests — expect PASS**

Run: `cd server && node --test test/executor-resolve.test.js`

---

### Task 3: Wire resolve API + NEEDS_REVIEW resume

**Files:**
- Modify: `server/index.js`
- Test: reuse unit tests; manual API via curl later

**Interfaces:**
- Consumes: `applyJobResolve` from `./lib/executor-resolve`
- Produces:
  - `POST /api/executor/jobs/:id/resolve` body `{ action, note? }`
  - `PATCH /api/interaction-targets/:id` accepts `RUNNING` from `NEEDS_REVIEW`

- [ ] **Step 1: Require helper at top of `index.js`**

```js
const { applyJobResolve } = require('./lib/executor-resolve');
```

- [ ] **Step 2: Add route near other job lifecycle routes (~after `/interrupted`)**

```js
app.post('/api/executor/jobs/:id/resolve', authMiddleware, (req, res) => {
  const found = findExecutorJob(req.params.id);
  if (!found) return res.status(404).json({ error: 'Job không tồn tại.' });
  const { type, job } = found;
  if (req.user.role !== 'admin' && job.group !== req.user.group) {
    return res.status(403).json({ error: 'Forbidden' });
  }
  const now = Date.now();
  const result = applyJobResolve(job, {
    action: req.body.action,
    note: req.body.note,
    username: req.user.username,
    now
  });
  if (!result.ok) return res.status(result.statusCode).json({ error: result.error });

  if (job.status === 'SUCCEEDED') {
    // mirror complete side-effects that refresh targets / group intel without replacement
    recordGroupInteraction(job, 'SUCCEEDED', { user: req.user });
  } else if (job.status === 'FAILED') {
    // ops fail: no replacement job; still record fail for target/group when appropriate
    recordGroupInteraction(job, 'FAILED', { user: req.user });
  }
  refreshAllInteractionTargets();
  saveExecutorQueue(type);
  saveInteractionTargets();
  emitExecutorUpdate(job.group);
  emitInteractionTargetsUpdate(job.group);
  res.json({ job: publicExecutorJob(job) });
});
```

Implementer must align `recordGroupInteraction` call signature with the existing function (pass whatever the current helper expects — inspect call sites at complete/fail). If the helper requires `req`, pass `req`.

- [ ] **Step 3: Extend PATCH status allow-list**

Replace the status branch so:

```js
if (req.body.status !== undefined) {
  const status = String(req.body.status).toUpperCase();
  if (!['RUNNING', 'PAUSED'].includes(status)) {
    return res.status(400).json({ error: 'Chỉ hỗ trợ RUNNING hoặc PAUSED ở endpoint này.' });
  }
  if (['CLOSED', 'COMPLETED'].includes(target.status)) {
    return res.status(409).json({ error: 'Target đã đóng/hoàn tất.' });
  }
  // Allow NEEDS_REVIEW → RUNNING|PAUSED and PAUSED ↔ RUNNING
  if (!['RUNNING', 'PAUSED', 'NEEDS_REVIEW'].includes(target.status) && status !== target.status) {
    return res.status(409).json({ error: `Không chuyển từ ${target.status} sang ${status}.` });
  }
  if (target.status === 'NEEDS_REVIEW' && status === 'RUNNING') {
    target.resumedFromReviewAt = Date.now();
  }
  target.status = status;
}
```

Keep existing `planInteractionTarget` call when status becomes `RUNNING`.

- [ ] **Step 4: Smoke with node syntax check**

Run: `cd server && node --check index.js`  
Expected: no output / exit 0

---

### Task 4: Dashboard ops UI

**Files:**
- Modify: `server/public/dashboard.html`

**Interfaces:**
- Consumes: `POST /api/executor/jobs/:id/resolve`, existing PATCH/close
- Produces: filter + buttons in job/target lists

- [ ] **Step 1: Add ops count + filter control in interaction/publishing queue sections**

Add markup near job lists (IDs):

```html
<div class="ops-bar">
  <span>Cần xử lý: <strong id="opsNeedsCount">0</strong></span>
  <select id="jobStatusFilter">
    <option value="ALL">Tất cả</option>
    <option value="INTERRUPTED">Cần kiểm tra</option>
    <option value="QUEUED">Đang chờ</option>
    <option value="RUNNING">Đang chạy</option>
    <option value="SUCCEEDED">Hoàn tất</option>
    <option value="FAILED">Thất bại</option>
  </select>
</div>
```

- [ ] **Step 2: Update `renderJobList` to accept filter and show resolve buttons**

```js
async function resolveExecutorJob(id, action) {
  const labels = { mark_succeeded: 'đánh OK', requeue: 'chạy lại', fail: 'đánh fail' };
  if (!confirm(`Xác nhận ${labels[action]} job ${id}?`)) return;
  const note = action === 'fail' ? (prompt('Lý do fail', 'Ops đánh fail') || 'Ops đánh fail') : '';
  const res = await apiCall(`/api/executor/jobs/${id}/resolve`, {
    method: 'POST', body: JSON.stringify({ action, note })
  });
  const data = await res.json().catch(() => ({}));
  if (!res.ok) return alert(data.error || 'Resolve thất bại');
  await fetchExecutorQueues();
  await fetchInteractionTargets();
}

function renderJobList(jobs, emptyText) {
  const filter = document.getElementById('jobStatusFilter')?.value || 'ALL';
  const filtered = filter === 'ALL' ? jobs : jobs.filter(j => j.status === filter);
  const labels = { QUEUED:'Đang chờ', RUNNING:'Đang chạy', SUCCEEDED:'Hoàn tất', FAILED:'Thất bại', INTERRUPTED:'Cần kiểm tra', CANCELED:'Đã hủy' };
  if (!filtered.length) return `<div style="text-align:center;color:var(--text-muted);padding:20px">${emptyText}</div>`;
  return filtered.map(job => `
    <div class="list-item">
      <div class="list-title">${escapeHtml(job.id)} · ${job.type === 'interaction' ? 'Tương tác' : 'Đăng bài'}</div>
      <div style="font-size:.8rem;color:var(--text-muted);word-break:break-all">${escapeHtml(job.payload?.url || job.payload?.groupUrl || '')}</div>
      <div class="list-meta"><span>${escapeHtml(job.createdBy)}</span><span class="badge ${job.status === 'SUCCEEDED' ? 'done' : 'pending'}" style="${job.status === 'INTERRUPTED' ? 'background:rgba(239,68,68,.2);color:#fca5a5' : ''}">${labels[job.status] || job.status}</span></div>
      ${job.scheduledAt ? `<div style="font-size:.78rem;color:var(--warn);margin-top:6px">Hẹn: ${escapeHtml(formatScheduleTime(job.scheduledAt))}</div>` : ''}
      ${job.lastError ? `<div style="font-size:.78rem;color:var(--error);margin-top:6px">${escapeHtml(job.lastError)}</div>` : ''}
      ${job.status === 'INTERRUPTED' ? `<div class="target-actions">
        <button class="btn-success" onclick="resolveExecutorJob('${job.id}','mark_succeeded')">OK</button>
        <button class="btn-secondary" onclick="resolveExecutorJob('${job.id}','requeue')">Chạy lại</button>
        <button class="btn-danger" onclick="resolveExecutorJob('${job.id}','fail')">Fail</button>
      </div>` : ''}
    </div>`).join('');
}
```

Wire `jobStatusFilter` `onchange` → `fetchExecutorQueues`.

- [ ] **Step 3: Target NEEDS_REVIEW actions**

In `fetchInteractionTargets` map:

```js
const canRun = t.status === 'PAUSED' || t.status === 'NEEDS_REVIEW';
// show reviewReason when present
${t.reviewReason ? `<div style="font-size:.78rem;color:var(--error);margin-top:6px">${escapeHtml(t.reviewReason)}</div>` : ''}
${canRun ? `<button class="btn-success" onclick="updateInteractionTarget('${t.id}','RUNNING')">Chạy tiếp</button>` : ''}
```

- [ ] **Step 4: Update ops count**

In `fetchExecutorQueues` / `fetchInteractionTargets`, set:

```js
const interrupted = (data.interaction?.counts?.INTERRUPTED || 0) + (data.publishing?.counts?.INTERRUPTED || 0);
// plus NEEDS_REVIEW targets count from targets fetch — store in window.__needsReviewCount
document.getElementById('opsNeedsCount').textContent = interrupted + (window.__needsReviewCount || 0);
```

---

### Task 5: Android executor cooldown gate

**Files:**
- Modify: `app/src/main/java/com/example/commenthelper/ExecutorForegroundService.kt`

**Interfaces:**
- Consumes: SharedPreferences keys `block_timeout_epoch` (Long), written by `FbAutoService`
- Produces: skip claim while cooling down; status string for UI

- [ ] **Step 1: Add helper in service**

```kotlin
private fun cooldownRemainingMs(): Long {
    val unlockAt = prefs.getLong("block_timeout_epoch", 0L)
    val now = System.currentTimeMillis()
    return if (unlockAt > now) unlockAt - now else 0L
}
```

- [ ] **Step 2: Gate before claim in `workerLoop` idle branch**

Replace the idle claim block so:

```kotlin
if (active == null) {
    val cool = cooldownRemainingMs()
    if (cool > 0L) {
        val until = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
            .format(java.util.Date(System.currentTimeMillis() + cool))
        executorStatus.value = "Tạm nghỉ chống chặn đến $until"
        updateNotification("${modeLabel(mode)} · Tạm nghỉ đến $until")
        delay(minOf(cool, 60_000L))
    } else {
        executorStatus.value = "Đang chờ yêu cầu"
        updateNotification("${modeLabel(mode)} · Đang chờ yêu cầu")
        claimNext(mode)?.let { /* existing claim handling */ }
    }
}
```

Keep existing claimed-job / heartbeat logic unchanged.

- [ ] **Step 3: Manual check**

Set prefs `block_timeout_epoch` to `now+10min` on device → executor shows cooldown and does not claim → clear/expire → claims resume.

---

### Task 6: Wave 1 verification gate

- [ ] **Step 1: Run server unit tests**

Run: `cd server && node --test test/executor-schedule.test.js test/executor-resolve.test.js test/executor-policy.test.js`  
Expected: all PASS

- [ ] **Step 2: Manual checklist**

1. Create FAST vs SLOW target → new jobs have staggered `scheduledAt`.
2. Force `INTERRUPTED` → OK / requeue / fail from dashboard.
3. Target `NEEDS_REVIEW` → Chạy tiếp works; Đóng still works.
4. Cooldown on device blocks claim.

**Wave 1 done → start Wave 2 only after this gate.**

---

# Wave 2 — Security hygiene

### Task 7: Stop password reseeding on boot

**Files:**
- Modify: `server/index.js` (`ensurePostgresSeedUsers`)

**Interfaces:**
- Produces: create admin/`worker01` **only if missing**; never overwrite existing passwords

- [ ] **Step 1: Rewrite seed function**

```js
async function ensurePostgresSeedUsers() {
  let changed = false;
  if (!users.find(u => u.username === SYSTEM_ADMIN)) {
    users.push({
      id: genId(), username: SYSTEM_ADMIN, password: hashPw('16691'),
      group: 'default', role: 'admin', points: 20,
      createdAt: Date.now(), updatedAt: Date.now()
    });
    changed = true;
    console.warn('[DB] Seeded default admin — CHANGE PASSWORD immediately.');
  }
  if (!users.find(u => u.username === 'worker01')) {
    users.push({
      id: genId(), username: 'worker01', password: hashPw('123456'),
      group: 'default', role: 'user', points: 20,
      createdAt: Date.now(), updatedAt: Date.now()
    });
    changed = true;
  }
  if (changed) {
    await dbStore.replaceUsers(users);
    users = await dbStore.loadUsers();
  }
}
```

- [ ] **Step 2: Verify mentally / log on restart**

Restart server twice → password of existing admin must not reset. (If you previously relied on reset, change password via admin UI once.)

---

### Task 8: Auth-protect log ingest + OTA GET

**Files:**
- Modify: `server/index.js`
- Verify Android already sends `Authorization` on OTA (FbAutoService / ExecutorApp / MainActivity) and on log upload

**Interfaces:**
- `POST /api/logs/apk` → require `authMiddleware` (or accept Bearer; bind username from token over body spoofing)
- `GET /api/engine/scripts` and `GET /api/engine/script` → `authMiddleware`

- [ ] **Step 1: Add authMiddleware to the three routes**

```js
app.post('/api/logs/apk', authMiddleware, (req, res) => {
  try {
    const log = req.body.log;
    const username = req.user.username; // ignore body username for pathing
    // ... append using username
  } catch (e) {}
  res.json({ ok: true });
});

app.get('/api/engine/scripts', authMiddleware, (req, res) => { /* existing */ });
app.get('/api/engine/script', authMiddleware, (req, res) => { /* existing */ });
```

- [ ] **Step 2: Patch Android log upload if it omits Authorization**

In `FbAutoService` / `MainActivity` log POST, ensure:

```kotlin
conn.setRequestProperty("Authorization", "Bearer $token")
```

- [ ] **Step 3: Quick negative test**

`curl -s -o /dev/null -w "%{http_code}" http://localhost:3030/api/engine/scripts`  
Expected: `401` (or whatever unauth status `authMiddleware` uses)

---

### Task 9: Scrub secrets from docs

**Files:**
- Modify: `docs/README.md` — remove embedded OpenSSH private key block; leave path reference only
- Modify: `docs/wealify-llm.md` — replace literal API keys with env placeholders

- [ ] **Step 1: Replace key material with placeholders**

```markdown
# README deploy section
SSH key path (local only, never commit): `~/.ssh/id_ed25519_dtvps`
Do not paste private key contents into the repository.
```

```markdown
# wealify-llm.md
Auth: `Authorization: Bearer $WEALIFY_LLM_API_KEY`
```

- [ ] **Step 2: Grep repo for leaked patterns**

Run: `rg -n "BEGIN OPENSSH PRIVATE KEY|sk-the-|sk-local-" docs server --glob '!.env*'`  
Expected: no private key blocks; no live sk- keys in docs

**Wave 2 gate:** unauth OTA/logs fail; seed does not reset passwords; docs clean.

---

# Wave 3 — Operator UX

### Task 10: Executor settings sheet (subset)

**Files:**
- Modify: `app/src/main/java/com/example/commenthelper/ExecutorApp.kt`
- Optionally reuse fields from `SettingsScreen` in `MainActivity.kt` (do not resurrect full MainApp navigation)

**Interfaces:**
- UI fields: `block_timeout_hours`, `facebookName` / display name used for self-comment skip, OTA version pin (`ota_version` pref, default `latest`)
- Persist to SharedPreferences; sync via existing `PUT /api/me` if those keys already sync — mirror MainActivity sync keys

- [ ] **Step 1: Add a Settings tab or dialog on ExecutorApp**

Minimal Compose: Tab “Cài đặt” with:

- OutlinedTextField hours → `prefs.edit().putInt("block_timeout_hours", v)`
- OutlinedTextField facebook name → `facebookName`
- OTA version text field default `latest`

- [ ] **Step 2: Use OTA pin in ExecutorApp engine fetch**

```kotlin
val ver = prefs.getString("ota_version", "latest") ?: "latest"
val engine = executorRequest("/api/engine/script?version=$ver", authToken)
```

- [ ] **Step 3: Manual** — change hours, kill app, reopen → value persists; cooldown duration after block uses new hours (set by FbAutoService on next block).

---

### Task 11: Admin group-intelligence console

**Files:**
- Modify: `server/public/admin.html`

**Interfaces:**
- Consumes existing:
  - `GET /api/group-intelligence`
  - `PUT /api/group-intelligence/:groupId/accounts/:username`
  - `POST /api/group-intelligence/:groupId/resume`

- [ ] **Step 1: Add Admin nav section “Group Intel”**

Table: groupId, failStreak, pausedUntil, pauseReason, joined count. Button **Resume** → POST resume.

- [ ] **Step 2: Account membership editor**

Select group → list accounts → set status `JOINED|NOT_JOINED|PENDING|BLOCKED|LEFT` via PUT.

- [ ] **Step 3: Manual** — pause a group via fail streak or DB → Resume from admin → targets can run.

---

### Task 12: Admin OTA editor

**Files:**
- Modify: `server/public/admin.html`
- Uses: `GET /api/engine/script?version=…`, `POST /api/engine/script` (admin)

- [ ] **Step 1: Form fields**

- version (text)
- anchors (textarea JSON)
- jsCode (textarea)
- Load current + Save (POST)

- [ ] **Step 2: Validate JSON anchors client-side before POST**

```js
JSON.parse(anchorsText); // alert on throw
```

- [ ] **Step 3: Manual** — bump version string → device hot-reload picks up on next post (existing path).

---

### Task 13: Dashboard light refresh for ops

**Files:**
- Modify: `server/public/dashboard.html`

- [ ] **Step 1: Poll every 15s when logged in**

```js
setInterval(() => {
  if (!authToken) return;
  fetchInteractionTargets();
  fetchExecutorQueues();
}, 15000);
```

(Socket.IO optional later — not required.)

**Wave 3 gate:** settings reachable on Executor; admin can resume groups + edit OTA; dashboard auto-refreshes ops counts.

---

# Wave 4 — Slim product MVP

### Task 14: Drop `onlineOnly` from UI; ignore at product layer

**Files:**
- Modify: `server/public/dashboard.html` (remove any onlineOnly checkbox if present; ensure create payload does not require it)
- Modify: `server/index.js` create-target: keep storing default but document ignored — or force `onlineOnly: false` in summarize notes

- [ ] **Step 1: Ensure create body does not advertise onlineOnly in UI**

Server may still default `onlineOnly: false` for schema stability:

```js
onlineOnly: false, // ignored — no device presence in MVP
```

- [ ] **Step 2: Add one-line comment in `canClaimExecutorJob` that onlineOnly is intentionally unused

---

### Task 15: Target active window + maxRuntimeHours

**Files:**
- Create: `server/lib/executor-target-window.js`
- Create: `server/test/executor-target-window.test.js`
- Modify: `server/index.js`, `dashboard.html`

**Interfaces:**
- Produces:
  - `isWithinActiveWindow(target, now, timeZone = 'Asia/Ho_Chi_Minh')` using `target.activeHours: { start: '09:00', end: '22:00' }` (optional)
  - `isPastMaxRuntime(target, now)` using `createdAt + autoClose.maxRuntimeHours`
- Claim: `canClaimExecutorJob` returns false if outside window
- Refresh: `refreshInteractionTargetStatus` sets `NEEDS_REVIEW` with reason when past max runtime while RUNNING

- [ ] **Step 1: Tests for window helper**

```js
test('within window', () => {
  const target = { activeHours: { start: '09:00', end: '22:00' } };
  // construct `now` as a Date in VN that is 10:00 — implement helper with injectable clock parts
  assert.equal(isWithinActiveWindow(target, nowInside), true);
  assert.equal(isWithinActiveWindow(target, nowOutside), false);
});

test('missing activeHours always open', () => {
  assert.equal(isWithinActiveWindow({}, Date.now()), true);
});

test('max runtime', () => {
  const target = { createdAt: 1000, autoClose: { maxRuntimeHours: 1 } };
  assert.equal(isPastMaxRuntime(target, 1000 + 3_600_000 + 1), true);
});
```

- [ ] **Step 2: Implement helper + wire claim/refresh**

```js
// canClaimExecutorJob:
if (!isWithinActiveWindow(target, now)) return false;

// refreshInteractionTargetStatus for RUNNING:
if (target.autoClose?.enabled !== false && isPastMaxRuntime(target)) {
  target.status = 'NEEDS_REVIEW';
  target.reviewReason = 'Quá maxRuntimeHours.';
  return true;
}
```

- [ ] **Step 3: Dashboard create form fields**

- optional time inputs `activeStart` / `activeEnd`
- number `maxRuntimeHours` (default 24) → `autoClose.maxRuntimeHours`

```js
activeHours: (start && end) ? { start, end } : undefined,
autoClose: { enabled: true, whenRequirementsMet: true, maxRuntimeHours, maxFailedJobs: 5 }
```

---

### Task 16: Prefer joined accounts at claim time

**Files:**
- Create or extend: `server/lib/executor-claim-gate.js` (+ test)
- Modify: `server/index.js` (`canClaimExecutorJob`)

**Interfaces:**
- Produces: `preferJoinedGate(intel, username) → boolean`
- Rule (pull-model MVP):
  - If group has **no** known joined accounts → allow any eligible user (bootstrap).
  - If group has ≥1 joined account → only users in `joinedAccounts` or `accountMembership.status === 'JOINED'` may claim.
  - Existing denies for `NOT_JOINED|PENDING|BLOCKED|LEFT` remain.
  - Cold start / empty intel unchanged.

```js
function preferJoinedGate(intel, username) {
  const joinedNames = Object.keys(intel.joinedAccounts || {});
  if (joinedNames.length === 0) return true;
  if (joinedNames.includes(username)) return true;
  return intel.accountMembership?.[username]?.status === 'JOINED';
}
```

- [ ] **Step 1: Write unit tests for `preferJoinedGate`**
- [ ] **Step 2: Implement module and call from `canClaimExecutorJob` after existing membership checks**

```js
if (!preferJoinedGate(intel, user.username)) return false;
```

- [ ] **Step 3: Run** `node --test test/executor-claim-gate.test.js` — PASS

---

### Task 17: Wave 4 verification + docs touch

**Files:**
- Modify: `docs/product-group-interaction-requirements.md` status note at top — mark which MVP items shipped vs deferred
- Modify: `docs/README.md` briefly list executor ops + waves (no secrets)

- [ ] **Step 1: Manual**

1. Target with activeHours outside now → no claim; inside → claim.
2. Short `maxRuntimeHours` → moves to `NEEDS_REVIEW`.
3. Group with joined account A → account B without join cannot claim; A can.
4. `onlineOnly` absent from UI.

- [ ] **Step 2: Run full server tests**

Run: `cd server && node --test`  
Expected: PASS

---

## Self-review (plan vs specs)

| Requirement | Task |
|-------------|------|
| W1 ops resolve INTERRUPTED | Task 2–4 |
| W1 NEEDS_REVIEW resume | Task 3–4 |
| W1 cooldown executor | Task 5 |
| W1 speed scheduledAt | Task 1 (done) |
| W2 seed / auth logs+OTA / scrub docs | Tasks 7–9 |
| W3 settings / group intel UI / OTA editor / poll | Tasks 10–13 |
| W4 maxRuntime + active window | Task 15 |
| W4 prefer joined | Task 16 |
| W4 onlineOnly remove | Task 14 |
| No campaign/worker-pool | Explicit non-goal |

## Execution note

Implement **strictly Wave-by-Wave**. Do not start Wave N+1 until Wave N verification gate passes. Prefer subagent-driven execution per task with review between tasks.
