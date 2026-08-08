# Design: LLM Comment Pool + Publish Content + Executor Reliability

**Date:** 2026-08-07  
**Status:** Approved for implementation

## Goal

Cho phép sinh nội dung bằng Wealify LLM khi tạo yêu cầu Executor:

1. **Interaction:** sau khi nhập nội dung bài → LLM sinh comment pool (preview) → chỉ lưu DB khi user post yêu cầu, gắn với target/bài đó.
2. **Publishing:** sau khi nhập draft → LLM sinh nội dung đăng (preview) → lưu vào job khi post yêu cầu.
3. **Config:** admin bật/tắt LLM trên server (`llmEnabled`).
4. **Reliability:** giảm lệch state khi mất mạng lúc báo `complete`/`fail`.

## Non-goals (MVP)

- Không gọi LLM trên Android lúc claim/runtime.
- Không tạo system-wide reusable comment library tách entity mới.
- Không auto-resolve job `INTERRUPTED` sau checkpoint (vẫn cần kiểm tra thủ công).
- Không bắt buộc LLM: tắt hoặc lỗi Wealify → user nhập tay.

## Architecture

```
Dashboard (preview)
    │  POST /api/llm/generate-comments | generate-post
    ▼
Server ──(if llmEnabled + API key)──► Wealify OpenAI-compatible /v1
    │
    │  User submits request
    ▼
interaction_targets.commentPool  OR  publishing_queue.payload.content
    │
    ▼
Existing planInteractionTarget / claim / FbAutoService
```

- Model mặc định: `gemma3:12b-it-qat` (tiếng Việt).
- Key: env `WEALIFY_LLM_API_KEY` (optional `WEALIFY_LLM_BASE_URL`, default `https://llm.wealify.app/v1`).
- Toggle: `appSettings.llmEnabled` (boolean, default `false`).

## Interaction flow

1. User nhập `postUrl` + nội dung bài (`targetPost` text) — bắt buộc như hiện tại.
2. Nếu LLM on: chọn N (mặc định = comment quantity) → `POST /api/llm/generate-comments` `{ postText, count }` → list string preview vào textarea pool.
3. User chỉnh tay.
4. `POST /api/interaction-targets` với `commentPool` → persist gắn target → `planInteractionTarget` phân job.

## Publishing flow

1. User nhập `groupUrl` + draft nội dung.
2. Nếu LLM on: `POST /api/llm/generate-post` `{ draft }` → 1 nội dung chính (+ optional `variants[]`) → điền textarea content.
3. User chỉnh → `POST /api/executor/publishing` lưu `payload.content` như hiện tại.

## API

| Method | Path | Auth | Behavior |
|--------|------|------|----------|
| GET | `/api/settings` | user | Thêm `llmEnabled`, `llmConfigured` (boolean, không lộ key) |
| POST | `/api/settings` | admin | Cho phép set `llmEnabled` |
| POST | `/api/llm/generate-comments` | auth | 403 nếu `!llmEnabled`; 503 nếu thiếu key; body `{ postText, count }` → `{ comments: string[] }` |
| POST | `/api/llm/generate-post` | auth | 403/503 tương tự; body `{ draft }` → `{ content, variants? }` |

Generate endpoints **không** ghi DB. Persist chỉ khi tạo target/publishing job.

## Reliability (MVP)

### Existing (keep)

- Lease 60s; heartbeat ~15s.
- Hết lease: không checkpoint → `QUEUED`; đã checkpoint → `INTERRUPTED`.
- Interaction fail retryable → replacement job + exclude account/device.
- Recover claim với `irreversibleAt` → client `interrupted`, không chạy lại.

### Gaps to fix

1. **Lifecycle outbox (Android):** nếu `complete`/`fail`/`interrupted` HTTP không 2xx (hoặc network -1), không `clearActiveJob` ngay — retry backoff (vd. 2s, 5s, 10s, tối đa ~6 lần). Persist pending report vào SharedPreferences để flush sau khi process chết.
2. **Idempotent complete (server):** nếu job đã `SUCCEEDED` và request có `leaseToken` khớp token đã dùng **hoặc** `claimedBy` + `deviceId` khớp lần claim gần nhất trong `result`, trả `{ ok: true, idempotent: true }`. Nếu job `RUNNING` + lease hợp lệ → complete như cũ.
3. **Dashboard:** hiển thị rõ job `INTERRUPTED` trong queue list (nếu UI đã list status thì đảm bảo filter/badge).

## UI

- **admin.html:** checkbox “Bật LLM (Wealify)” trong Cài đặt hệ thống; hiện trạng thái key đã cấu hình (env).
- **dashboard.html:** nút “Sinh comment AI” (interaction) và “Sinh nội dung AI” (publishing); ẩn/disable khi `!llmEnabled`.

## Testing

- Unit: parse LLM response → comment list; reject khi disabled/missing key (mock fetch).
- Manual: bật LLM → sinh pool → tạo target → claim; tắt LLM → nút ẩn, tạo tay vẫn OK; airplane mode sau POST_DONE → outbox retry khi mạng về.

## Security

- Không commit API key vào repo.
- Không trả raw key qua `/api/settings`.
- Prompt chỉ dùng nội dung bài user cung cấp; truncate postText/draft (vd. 4000 chars).
