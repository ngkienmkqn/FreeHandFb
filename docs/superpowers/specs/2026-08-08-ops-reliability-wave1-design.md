# Design: Ops Console + Executor Reliability (Wave 1)

**Date:** 2026-08-08  
**Status:** Approved for implementation (Wave 1)  
**Roadmap:** Approach “Wave theo rủi ro” covering A→B→C→D; this doc locks **Wave 1 only**. Waves 2–4 are sketched for sequencing, not implementation scope yet.

## Goal

Giảm job kẹt và rủi ro cháy nick khi farm chạy executor:

1. **Ops console:** người vận hành xử lý được job `INTERRUPTED` và target `NEEDS_REVIEW` trên dashboard (không cần curl/DB).
2. **Cooldown executor:** thiết bị đang trong `block_timeout` không claim job mới.
3. **`speed` thật:** planner gán `scheduledAt` theo SLOW/NORMAL/FAST; claim đã có `isExecutorJobDue` sẽ tôn trọng.

## Non-goals (Wave 1)

- Không auto-resolve `INTERRUPTED` mà không có người xác nhận.
- Không Socket.IO realtime / metrics pack (Wave 3).
- Không harden auth/seed/secrets (Wave 2).
- Không `onlineOnly`, deadline/khung giờ target, worker groups, DRAFT, campaign (Wave 4).
- Không đổi hành vi LLM preview.
- Không khôi phục full legacy `MainApp` Settings (Wave 3).

## Context (observed)

- `canClaimExecutorJob` đã check `scheduledAt`, group pause, membership `BLOCKED` / `cooldownUntil`, comment dedupe (`server/index.js`).
- `planInteractionTarget` tạo job `QUEUED` **không** set `scheduledAt`.
- `ExecutorForegroundService.claimNext` **không** đọc `block_timeout_epoch` (khác `AutoPublishWorker`).
- Dashboard hiện badge `INTERRUPTED` / label `NEEDS_REVIEW` nhưng không có action resolve; `PATCH` target chỉ nhận `RUNNING|PAUSED`.

---

## Architecture

```
Dashboard ops actions
    │  POST /api/executor/jobs/:id/resolve
    │  PATCH /api/interaction-targets/:id  (NEEDS_REVIEW → RUNNING)
    ▼
Server planner / queue state
    │  planInteractionTarget → scheduledAt theo speed
    ▼
Android ExecutorForegroundService
    │  gate block_timeout_epoch trước claim
    ▼
Existing claim / heartbeat / complete path
```

---

## 1. Ops console

### 1.1 Job resolve API

`POST /api/executor/jobs/:id/resolve`  
**Auth:** user cùng `group` với job, hoặc admin.

Body:

```json
{
  "action": "mark_succeeded" | "requeue" | "fail",
  "note": "optional string ≤ 500"
}
```

| Action | Precondition | Effect |
|--------|--------------|--------|
| `mark_succeeded` | `status === INTERRUPTED` | `SUCCEEDED`; ghi `resolvedAt`, `resolvedBy`, `resolveNote`, `resolveAction`; refresh target progress; **không** tạo replacement job |
| `requeue` | `status === INTERRUPTED` | `QUEUED`; clear `leaseToken`, `claimedBy`, `deviceId`, `claimedAt`, `heartbeatAt`, `irreversibleAt`; giữ `payload` và `attempts`; ghi cùng các field resolve\* (action=`requeue`) |
| `fail` | `status === INTERRUPTED` | `FAILED` + `lastError` = note hoặc `"Ops đánh fail sau INTERRUPTED"`; ghi resolve\*; **không** tạo replacement job; **có** cập nhật group fail streak / target refresh giống nhánh fail không-retryable |

Responses:

- `200` `{ job: publicExecutorJob }`
- `404` job không tồn tại
- `403` sai group
- `409` status không phải `INTERRUPTED`

Idempotency Wave 1: **không** soft-retry. Mọi resolve khi job không còn `INTERRUPTED` → `409`.

### 1.2 Target resume từ NEEDS_REVIEW

Mở rộng `PATCH /api/interaction-targets/:id`:

- Cho phép `status: RUNNING` khi target đang `NEEDS_REVIEW` hoặc `PAUSED`.
- Khi `NEEDS_REVIEW` → `RUNNING`: clear hoặc giữ `reviewReason` (giữ trong history; set `resumedFromReviewAt`).
- Sau khi set `RUNNING`, gọi `planInteractionTarget` như path PAUSED → RUNNING hiện tại.
- `PAUSED` từ `NEEDS_REVIEW` vẫn cho phép (tạm ngưng sau khi xem).
- **Không** cho `PATCH` sang `CLOSED` — vẫn dùng `POST .../close`.

Dashboard target card:

- Nếu `NEEDS_REVIEW`: nút **Tiếp tục** (`PATCH` → `RUNNING`) và **Đóng** (prompt reason → close API hiện có).
- Resume `PAUSED` giữ như hiện tại.

### 1.3 Dashboard job list

- Filter chips hoặc select: `Tất cả | Cần kiểm tra (INTERRUPTED) | Đang chờ | Đang chạy | …`
- Mỗi job `INTERRUPTED`: 3 nút — **OK**, **Chạy lại**, **Fail** (confirm ngắn trước Fail / OK).
- Header đếm: `Cần xử lý: N` = số job `INTERRUPTED` (trong group user) + số target `NEEDS_REVIEW`.

---

## 2. Cooldown trên executor (Android)

### 2.1 Claim gate

Trong `ExecutorForegroundService` (trước `claimNext` HTTP, hoặc đầu `claimNext`):

```text
if (prefs.block_timeout_epoch > now) {
  show status "Tạm nghỉ đến <time>"
  delay until min(epoch - now, 60s) then retry loop
  do not POST claim
}
```

- Dùng cùng key `block_timeout_epoch` mà `FbAutoService` đã ghi khi phát hiện block.
- Không thêm server field mới trong Wave 1.
- Server membership `BLOCKED` / `cooldownUntil` **giữ nguyên** (đã có).

### 2.2 UI Executor

- Khi đang cooldown: `executorStatus` hiển thị thời điểm mở lại (format local).
- Wave 1: **không** nút “bỏ qua cooldown” (tránh phá an toàn). Có thể thêm debug flag Wave 3 nếu cần.

---

## 3. Speed → scheduledAt

### 3.1 Mapping

Hàm helper (server), ví dụ `spreadMsForSpeed(speed)`:

| Speed | Window |
|-------|--------|
| `SLOW` | 12 hours |
| `NORMAL` | 4 hours |
| `FAST` | 30 minutes |

Khi tạo batch `jobCount` jobs trong `planInteractionTarget`:

```text
scheduledAt(i) = now + floor(i * windowMs / max(jobCount, 1))
```

- Job đầu (`i=0`) có thể `scheduledAt = now` (claim ngay) hoặc `now` + 0.
- Job đã tồn tại trước Wave 1 (không có `scheduledAt`) vẫn claim được ngay (`isExecutorJobDue` đã đúng).
- Replan tạo job **mới** mới áp dụng spread; không backfill rewrite toàn bộ queue cũ trừ khi ops requeue (requeue **không** bắt buộc gán `scheduledAt` mới — giữ due ngay để ops kiểm tra nhanh).

### 3.2 UI

- Không đổi dropdown speed trên form tạo target.
- Optional (nice): tooltip “SLOW trải ~12h …” — không bắt buộc Wave 1.

---

## 4. Testing

### Automated (nhẹ)

- Unit/helper: `spreadMsForSpeed` + assignment `scheduledAt` monotonic trong window.
- Resolve: `INTERRUPTED` → từng action; reject khi `QUEUED`.

### Manual

1. Tạo target FAST vs SLOW → inspect `scheduledAt` trên `/api/executor/queues`.
2. Set `block_timeout_epoch` tương lai trên device → không thấy claim mới; hết giờ → claim lại.
3. Ép job `INTERRUPTED` → OK / Chạy lại / Fail từ dashboard.
4. Target `NEEDS_REVIEW` → Tiếp tục plan thêm job; Đóng hủy `QUEUED`.

---

## 5. Security / auth notes (Wave 1 boundary)

- Resolve/resume tôn trọng group isolation như các executor API hiện có.
- Không mở public endpoint mới.
- Harden seed/OTA/logs → **Wave 2**, không làm trong PR Wave 1 trừ khi đụng file trùng bắt buộc.

---

## Roadmap sketch (out of Wave 1 scope)

| Wave | Focus |
|------|--------|
| **W2** | Ngừng rewrite seed passwords mỗi boot; auth log ingest + OTA GET; gỡ secret khỏi docs |
| **W3** | Settings trên Executor UI; admin group-intelligence + OTA editor; poll/realtime nhẹ |
| **W4** | MVP từ product requirements: `maxRuntimeHours`, resume UX đầy đủ, prefer joined, quyết định `onlineOnly` (enforce hoặc bỏ), deadline/khung giờ target — chưa full campaign/worker-pool |

---

## Implementation order (Wave 1)

1. Server: `scheduledAt` trong `planInteractionTarget` + tests helper.
2. Server: resolve API + PATCH `NEEDS_REVIEW` → `RUNNING`.
3. Dashboard: filters + buttons + counts.
4. Android: cooldown gate + status text.
5. Manual smoke trên device/farm nhỏ.
