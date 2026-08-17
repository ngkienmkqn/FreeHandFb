# Design: Join Group Job + Merged Executor Session

**Date:** 2026-08-17  
**Status:** Approved for implementation  
**Branch context:** `codex/node-scoring` (or follow-on branch)

## Goal

Cho user tạo **job Join group** trên web (keyword hoặc link group), Android executor tự search/mở group qua Accessibility, bấm tham gia, rồi báo hoàn thành.

Đồng thời **gộp phiên client**: một session “đang chờ job” với multi-select loại job (Tương tác / Đăng bài / Join group), không còn mode độc quyền Interaction **hoặc** Publishing.

## Decisions (locked)

| # | Decision | Choice |
|---|----------|--------|
| 1 | Shape sản phẩm | Job type **riêng** `join` (không nhét vào publish/interact payload) |
| 2 | Sau success | Job **DONE** + cập nhật **group intel** `joinedAccounts` / membership `JOINED` (`source: join_job`) |
| 3 | Ai claim | Chỉ **user tạo job** (`createdBy === username`), giống publishing |
| 4 | Questionnaire | Auto form đơn giản (đồng ý quy tắc / submit); free-text → fail `GROUP_QUESTIONNAIRE_REQUIRED` |
| 5 | Client session | **Merged session** + multi-select checkboxes; 1 job Facebook tại một thời điểm |
| 6 | Keyword search | Lấy **group đầu tiên** trong kết quả tìm kiếm |
| 7 | Claim priority | **Join → Interaction → Publishing** |

## Non-goals (v1)

- Trả lời free-text questionnaire / screening questions.
- Tự thêm group vào danh sách group đăng bài của user trên web.
- Join top-N kết quả search (chỉ **#1**).
- Team claim / assign join cho account khác.
- Chạy song song nhiều task Facebook trên cùng device.
- Đổi planner interaction target hay LLM publish (ngoài chỗ chạm queue/claim).

## Context (existing code)

- Local keyword join đã có trên app: URL scheme `fb_join_keyword:<kw>` → `fb://search/groups?query=...` (`FbAutoService.executePostTask`), fallback từ `join_keywords` / `AutoPublishWorker` — **chưa** là web job.
- Join button intercept + anchors `group_join` đã có trong OTA engine.
- Executor hiện 2 queue: `interaction`, `publishing`; claim exclusive per username across both types (`/api/executor/:type/claim`).
- `canClaimExecutorJob`: publishing = owner-only; interaction = target window + membership gates + prefer-joined.
- `markAccountJoinedGroup(username, groupId, source)` ghi intel JOINED.
- Android: `ExecutorForegroundService` single `mode` string (`interaction` | `publishing`), exclusive start.

---

## Architecture

```text
Web Dashboard
  ├─ Tab Tương tác     → POST interaction targets / jobs (existing)
  ├─ Tab Đăng bài      → POST /api/executor/publishing (existing)
  └─ Tab Join group    → POST /api/executor/join (new)
            │
            ▼
Node server
  interactionQueue | publishingQueue | joinQueue
  claim / heartbeat / checkpoint / complete|fail
  on join success → markAccountJoinedGroup(...)
            │
            ▼
Android ExecutorForegroundService (merged session)
  selectedTypes: { interaction, publishing, join }
  claim loop priority: join → interaction → publishing
            │
            ▼
FbAutoService (Accessibility)
  keyword → search groups → first result → join
  link    → open group URL → join
  simple questionnaire auto-submit
```

---

## 1. Web Dashboard

### UI

- Thêm section/tab **Join group** cạnh Tương tác / Đăng bài (cùng visual language dashboard hiện tại).
- Form:
  - Textarea **“Từ khóa hoặc link group”** (mỗi dòng một job nếu multi-line).
  - Optional: datetime **lên lịch** (reuse pattern publishing `scheduledAt`; để trống = claim ngay).
  - Nút **Đưa vào queue join**.
- List job join: id, kind (keyword|link), query/url, status, reason, createdAt; filter status giống tab khác.
- Queue chip header: thêm count **Join chờ** (hoặc mở rộng strip 3 cột).

### Input detection (client + server mirror)

Với mỗi dòng non-empty sau `trim`:

| Condition | `kind` | Fields |
|-----------|--------|--------|
| Match group URL | `link` | `groupUrl` = normalized Facebook group URL |
| Else | `keyword` | `query` = raw text |

**Group URL detect (v1):**

- Regex case-insensitive: contains `facebook.com/groups/` **or** `fb.com/groups/` **or** `m.facebook.com/groups/`
- Optional leading `https://` / `http://` / bare domain
- Server rejects empty input; rejects link that fails normalize; keyword min length 1 (after trim), max 200 chars

**Multi-line:** mỗi dòng hợp lệ → 1 job `JOIN-...`; dòng lỗi → báo partial error, các dòng ok vẫn tạo (hoặc fail-all — **chọn: partial success** với `created[]` + `errors[]`).

---

## 2. Server

### Queue

- Thêm `joinQueue` + store key `join_queue` (cùng pattern `replaceJobs('join', ...)` / load).
- `executorQueues.join = () => joinQueue`
- `reclaimExpiredExecutorJobs`, queue summary, reset admin: include `join`.
- Active-job exclusive check on claim: scan `['interaction','publishing','join']`.

### Create API

`POST /api/executor/join` (auth)

Body (single):

```json
{
  "input": "mua bán xe Hà Nội",
  "scheduledAt": null
}
```

hoặc explicit:

```json
{
  "kind": "link",
  "groupUrl": "https://facebook.com/groups/123",
  "scheduledAt": null
}
```

Body (batch):

```json
{
  "inputs": ["keyword one", "https://facebook.com/groups/123"]
}
```

Response 201:

```json
{
  "created": [ { "id": "JOIN-...", "type": "join", ... } ],
  "errors": [ { "line": 2, "input": "...", "error": "..." } ]
}
```

Job shape:

```json
{
  "id": "JOIN-<id>",
  "type": "join",
  "group": "<user.group workspace>",
  "createdBy": "<username>",
  "status": "QUEUED",
  "attempts": 0,
  "createdAt": 0,
  "updatedAt": 0,
  "scheduledAt": 0,
  "payload": {
    "kind": "keyword",
    "query": "mua bán xe",
    "groupUrl": null
  }
}
```

Link job payload:

```json
{
  "kind": "link",
  "query": null,
  "groupUrl": "https://www.facebook.com/groups/123/"
}
```

### Claim rules

Extend `canClaimExecutorJob` for `type === 'join'`:

- `item.group === user.group`
- `status === 'QUEUED'`
- due (`scheduledAt`)
- retry exclusion via `payload.retry` (reuse policy)
- **`item.createdBy === user.username`** (owner-only)

### Lifecycle complete/fail

Reuse existing:

- `POST /api/executor/jobs/:id/heartbeat|checkpoint|complete|fail|interrupted|actions/...`

**On complete (`join` only):**

1. Read optional result from body:
   ```json
   {
     "result": {
       "completedAt": 0,
       "groupUrl": "https://...",
       "groupName": "...",
       "alreadyJoined": false
     }
   }
   ```
2. Resolve intel key:
   - Prefer `result.groupUrl` normalized
   - Else job `payload.groupUrl`
   - Else if keyword only: `keyword:<normalized query>` (lowercase, collapse spaces) — still mark joined under that key so prefer-joined has a signal for same keyword later; if app later reports real URL on retry complete, also mark real URL
3. `markAccountJoinedGroup(username, groupKey, 'join_job')`
4. Optionally set `payload.resolvedGroupUrl` / `payload.resolvedGroupName` on job for ops UI

**On fail:** store `reasonCode`, `error`, `step` như job khác. Không mark joined trừ khi reason là `ALREADY_JOINED` (treat as success path — see below).

### Reason codes (join)

| Code | Meaning | retryable (default) |
|------|---------|---------------------|
| `GROUP_NOT_FOUND` | Search empty / không mở được group | true |
| `JOIN_BUTTON_NOT_FOUND` | Không thấy nút tham gia / đã join indicators ambiguous | true |
| `GROUP_QUESTIONNAIRE_REQUIRED` | Form cần free-text | false |
| `ALREADY_JOINED` | UI “Đã tham gia” — **map to complete success** + intel | n/a |
| `STEP_TIMEOUT` | Timeout state machine | true |
| `ACCESSIBILITY_FAILED` | Generic | true |
| `DEAD_LINK` / `TARGET_POST_UNAVAILABLE` | Link group chết | false |

App should report `ALREADY_JOINED` as **success** (`complete`) with `alreadyJoined: true`, not fail.

### Claim API change (minimal)

Keep `POST /api/executor/:type/claim` per type. Client calls in priority order. No bulk multi-type claim API in v1 (YAGNI).

Ensure `type` path accepts `join`.

### Queues summary

`GET /api/executor/queues` includes `join: { queued, running, ... }` counts for the user’s visibility rules (same as other queues: workspace group + user-owned join/publish).

---

## 3. Android — Merged Executor Session

### UI (`ExecutorApp`)

Replace exclusive mode buttons with:

1. **Multi-select checkboxes**
   - ☐ Tương tác  
   - ☐ Đăng bài  
   - ☐ Join group  
2. **Bắt đầu** enabled khi ≥1 checkbox  
3. **Dừng** stops session  
4. Status: “Đang chờ job (join, tương tác)” / “Đang join JOIN-xxx” / errors  
5. Persist selected types in prefs (`executor_selected_types`)

### Service model

- `ExecutorForegroundService.ACTION_START` carries `EXTRA_TYPES` string list or bitflags, not single exclusive mode.
- Internal: `selectedTypes: Set<String>` (`join`, `interaction`, `publishing`)
- Remove “running mode blocks other mode” for start; still **one claimed job** max.
- Persist `executor_running_types` for sticky restart.

### Claim loop

While session active and no `claimedJob`:

```
for type in [join, interaction, publishing]:
  if type in selectedTypes:
    job = claim(type)
    if job: break
if no job: wait ~2–5s, refresh summary
```

When job finishes → loop again.

If user unchecks a type while idle: next claim skips it.  
If unchecks type of **running** job: let current job finish (do not cancel mid-FB unless user hits Stop).

**Stop:** same as today — interrupt active job if any, clear lease safely.

### Dispatch join

`dispatchToAccessibility` branch `type == join`:

- Keyword: `TaskItem(url = "fb_join_keyword:" + query, ...)`
- Link: `TaskItem(url = groupUrl, isJoinGroup = true)` (or dedicated flag)

Extend `TaskItem` with `isJoinGroup: Boolean = false` if needed to distinguish link-join from interaction URL.

### Accessibility (`FbAutoService`)

**Keyword path (harden existing):**

1. Open `fb://search/groups?query=<encoded>`
2. Wait search results
3. Click **first group result** (not people/page; prefer row that looks like group — members label / “Nhóm” if available; v1: first tappable result in groups search tab)
4. On group page: if “Đã tham gia” / “Joined” → success `ALREADY_JOINED`
5. Else click join anchors (`Engine.groupJoin`)
6. Simple questionnaire: accept rules checkbox + submit buttons matching OTA anchors (`questionnaire_submit`, etc.)
7. Free-text fields detected → fail `GROUP_QUESTIONNAIRE_REQUIRED`
8. Best-effort scrape `groupName` / share link → pass to complete `result`

**Link path:**

1. Open normalized group URL (native intent, existing `openFacebookLink`)
2. Same join / already-joined / questionnaire handling as after search open

**Do not** treat join-only jobs as dead interaction posts; skip interaction like/comment steps.

Timeouts: reuse step timeout; keyword flow must not “succeed after 30 retries blind wait” without verifying join/already-joined (fix current weak success-after-wait if still present).

### Complete payload from app

```json
{
  "result": {
    "completedAt": 123,
    "groupUrl": "https://facebook.com/groups/...",
    "groupName": "Mua bán xe HN",
    "alreadyJoined": false
  }
}
```

---

## 4. Data / persistence

- Postgres job rows: `queue_type = 'join'` via existing `fh_executor_jobs` (no migration if `queue_type` is free-form string — verify store insert accepts `join`).
- Group intel: existing `fh_group_intelligence` / in-memory + save path used by `markAccountJoinedGroup`.
- No new tables required for v1.

---

## 5. Error handling & ops

- Dashboard shows fail reason for join jobs.
- Admin reset queues includes join.
- Logs: Android high-value log for join steps; server complete source `join_job`.

---

## 6. Testing

### Server unit

- Detect keyword vs link helper
- `canClaimExecutorJob` join owner-only / other user denied
- Complete join marks intel with URL key and keyword key
- Claim exclusive across three queues
- Queue summary includes join

### Server integration (optional light)

- Create batch partial errors
- Claim → complete → intel present

### Android (manual / device)

- Keyword → first group → join
- Link → join
- Already joined → complete success
- Questionnaire free-text → fail code
- Multi-select: only selected types claimed
- Priority: join claimed before interaction when both queued
- Stop mid-job → interrupted safe

---

## 7. Implementation order

1. Server: detect helper, join queue, create/claim/complete intel, queues API, tests  
2. Web dashboard: form + list + counts  
3. Android: merged multi-select session + claim priority  
4. Android: harden join accessibility (keyword first result + link + questionnaire)  
5. Wire complete result → intel  
6. Manual QA checklist on device  

---

## 8. Open points closed for v1

| Topic | Resolution |
|-------|------------|
| Multi-line create | Partial success `created` + `errors` |
| Keyword without resolvable URL | Intel key `keyword:<query>` |
| ALREADY_JOINED | complete + intel, not fail |
| Claim multi-type API | Client sequential claim only |
| Priority | join → interaction → publishing |
| Concurrent FB tasks | Forbidden |

---

## Success criteria

1. User tạo join job từ web (keyword hoặc link).  
2. App với checkbox Join (có/không kèm loại khác) claim và join đúng.  
3. Keyword luôn chọn kết quả group **đầu tiên**.  
4. Success cập nhật intel JOINED.  
5. Multi-select session chạy lần lượt các loại đã chọn theo priority, không dual-claim.  
6. Questionnaire free-text fail rõ ràng; rules-only auto pass.  
