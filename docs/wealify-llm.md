# Wealify LLM Gateway (OpenAI-compatible)

Gateway local/team qua OpenAI SDK / curl. Base URL OpenAI-compatible (`/v1`).

> **Kiểm tra nhanh (2026-08-05):** cả `gemma3:12b-it-qat` và `qwen3:14b` trả `HTTP 200`. Fingerprint: `fp_ollama`.

## Endpoint & key

| Mục | Giá trị |
|-----|---------|
| Base URL | `https://llm.wealify.app/v1` |
| Chat | `POST /v1/chat/completions` |
| Models | `GET /v1/models` |
| Auth | `Authorization: Bearer $WEALIFY_LLM_API_KEY` |

Đặt key trong env local (không commit vào repo):

```bash
export WEALIFY_LLM_API_KEY="your-wealify-api-key"
```

## Model routing (đã chốt dùng)

| Việc | Model ID | Ghi chú |
|------|----------|---------|
| Chat / tiếng Việt | `gemma3:12b-it-qat` | Nhanh, trả lời ngắn gọn |
| Code / toán / logic khó | `qwen3:14b` | Có thể kèm field `reasoning` (thinking) trong message |

`GET /v1/models` hiện chỉ thấy 2 model trên.

## Python (OpenAI SDK)

```bash
pip install openai
```

```python
import os
from openai import OpenAI

client = OpenAI(
    base_url="https://llm.wealify.app/v1",
    api_key=os.environ["WEALIFY_LLM_API_KEY"],
)

# Chat / tiếng Việt → Gemma
r = client.chat.completions.create(
    model="gemma3:12b-it-qat",
    messages=[{"role": "user", "content": "xin chào"}],
)
print(r.choices[0].message.content)

# Code / toán / logic khó → Qwen
r = client.chat.completions.create(
    model="qwen3:14b",
    messages=[{"role": "user", "content": "giải bài toán..."}],
)
print(r.choices[0].message.content)
# Qwen đôi khi có thêm reasoning: getattr(r.choices[0].message, "reasoning", None)
```

## curl

```bash
curl https://llm.wealify.app/v1/chat/completions \
  -H "Authorization: Bearer $WEALIFY_LLM_API_KEY" \
  -H "Content-Type: application/json" \
  -d '{"model":"gemma3:12b-it-qat","messages":[{"role":"user","content":"chào bạn, tự giới thiệu 1 câu"}]}'
```

List models:

```bash
curl https://llm.wealify.app/v1/models \
  -H "Authorization: Bearer $WEALIFY_LLM_API_KEY"
```

## Kết quả smoke test

| Call | Status | Sample |
|------|--------|--------|
| Gemma — “tự giới thiệu 1 câu” | 200 | `Chào bạn, tôi là Gemma, một trợ lý AI được phát triển bởi Google.` |
| Qwen — “1+1 bằng mấy?” | 200 | `1+1 bằng 2.` (+ `reasoning` dài) |
| `GET /models` | 200 | `qwen3:14b`, `gemma3:12b-it-qat` |

## Bảo mật

- Không dán API key thật vào docs/repo; chỉ dùng `$WEALIFY_LLM_API_KEY` / env.
- Ưu tiên đưa key vào `.env` local (`WEALIFY_LLM_API_KEY`) khi tích hợp code; tránh commit thêm vào file khác.
- Nếu key từng lộ → rotate trên Wealify.

## Tích hợp FreeHand server

| Mục | Giá trị |
|-----|---------|
| Env key | `WEALIFY_LLM_API_KEY` (bắt buộc để gọi Wealify) |
| Env base (optional) | `WEALIFY_LLM_BASE_URL` — mặc định `https://llm.wealify.app/v1` |
| Toggle | Admin → Cài đặt → **Bật LLM (Wealify)** (`appSettings.llmEnabled`, mặc định tắt) |
| Sinh comment | `POST /api/llm/generate-comments` `{ postText, count, userPrompt? }` |
| Sinh comment (stream) | `POST /api/llm/generate-comments/stream` → SSE `delta` / `done` |
| Sinh bài đăng | `POST /api/llm/generate-post` `{ draft, userPrompt? }` |
| Sinh bài (stream) | `POST /api/llm/generate-post/stream` → SSE `delta` / `done` |
| Module | `server/lib/wealify-llm.js` |

Wealify hỗ trợ `stream: true` (`text/event-stream`). Dashboard dùng stream để hiện text dần như ChatGPT.

Tắt `llmEnabled` hoặc thiếu key → dashboard ẩn nút AI; tạo yêu cầu thủ công vẫn hoạt động.
