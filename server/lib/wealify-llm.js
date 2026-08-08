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

/** Flatten LLM JSON quirks (nested objects/arrays) into readable plain text. */
function coercePlainText(value) {
    if (value == null) return '';
    if (typeof value === 'string') return value.replace(/\s+/g, ' ').trim();
    if (typeof value === 'number' || typeof value === 'boolean') return String(value);
    if (Array.isArray(value)) {
        return value.map(coercePlainText).filter(Boolean).join('\n').trim();
    }
    if (typeof value === 'object') {
        for (const key of ['text', 'content', 'body', 'message', 'value', 'caption', 'title']) {
            if (value[key] != null) {
                const nested = coercePlainText(value[key]);
                if (nested && nested !== '[object Object]') return nested;
            }
        }
        // Prefer joining known text-ish fields; avoid dumping keys.
        const parts = Object.values(value).map(coercePlainText).filter(Boolean);
        return parts.join('\n').trim();
    }
    return '';
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
                return [...new Set(arr.map(coercePlainText).filter(Boolean))].slice(0, 50);
            }
        }
        // Some models wrap as {"comments":[...]}
        const objStart = raw.indexOf('{');
        const objEnd = raw.lastIndexOf('}');
        if (objStart >= 0 && objEnd > objStart) {
            const obj = JSON.parse(raw.slice(objStart, objEnd + 1));
            const list = obj.comments || obj.items || obj.data;
            if (Array.isArray(list)) {
                return [...new Set(list.map(coercePlainText).filter(Boolean))].slice(0, 50);
            }
        }
    } catch (_) { /* fall through */ }
    return [...new Set(
        raw.split(/\r?\n/)
            .map(line => line.replace(/^\s*[-*\d.)]+\s*/, '').replace(/\s+/g, ' ').trim())
            .filter(line => line && line !== '[object Object]')
    )].slice(0, 50);
}

function parsePostContent(text) {
    const raw = String(text || '').trim();
    try {
        const start = raw.indexOf('{');
        const end = raw.lastIndexOf('}');
        if (start >= 0 && end > start) {
            const obj = JSON.parse(raw.slice(start, end + 1));
            const content = coercePlainText(obj.content || obj.text || obj.body || obj.post);
            const variants = Array.isArray(obj.variants)
                ? obj.variants.map(coercePlainText).filter(Boolean)
                : [];
            if (content && content !== '[object Object]') {
                return { content, variants: variants.slice(0, 5) };
            }
        }
    } catch (_) { /* fall through */ }
    const fallback = coercePlainText(raw);
    return { content: fallback === '[object Object]' ? '' : fallback, variants: [] };
}

function ensureLlmReady(fetchImpl) {
    const cfg = getLlmConfig();
    if (!cfg.configured) {
        const err = new Error('WEALIFY_LLM_API_KEY chưa được cấu hình.');
        err.statusCode = 503;
        throw err;
    }
    const fetchFn = fetchImpl || globalThis.fetch;
    if (typeof fetchFn !== 'function') {
        const err = new Error('fetch không khả dụng trên runtime này.');
        err.statusCode = 500;
        throw err;
    }
    return { cfg, fetchFn };
}

async function chatCompletions({ messages, fetchImpl, stream = false, temperature = 0.8 } = {}) {
    const { cfg, fetchFn } = ensureLlmReady(fetchImpl);
    const res = await fetchFn(`${cfg.baseUrl}/chat/completions`, {
        method: 'POST',
        headers: {
            Authorization: `Bearer ${cfg.apiKey}`,
            'Content-Type': 'application/json'
        },
        body: JSON.stringify({
            model: cfg.model,
            messages,
            temperature: Math.max(0, Math.min(1.5, Number(temperature) || 0.8)),
            stream: !!stream
        })
    });
    if (stream) return res;
    const bodyText = await res.text();
    if (!res.ok) {
        const err = new Error(`Wealify LLM lỗi HTTP ${res.status}: ${bodyText.slice(0, 300)}`);
        err.statusCode = 502;
        throw err;
    }
    let data;
    try {
        data = JSON.parse(bodyText);
    } catch {
        const err = new Error('Wealify trả về JSON không hợp lệ.');
        err.statusCode = 502;
        throw err;
    }
    return String(data?.choices?.[0]?.message?.content || '').trim();
}

/** Yield plain text deltas from OpenAI-compatible SSE stream. */
async function* streamChatCompletions({ messages, fetchImpl, temperature } = {}) {
    const res = await chatCompletions({ messages, fetchImpl, stream: true, temperature });
    if (!res.ok) {
        const bodyText = await res.text().catch(() => '');
        const err = new Error(`Wealify LLM lỗi HTTP ${res.status}: ${bodyText.slice(0, 300)}`);
        err.statusCode = 502;
        throw err;
    }
    if (!res.body || typeof res.body.getReader !== 'function') {
        const err = new Error('Runtime không hỗ trợ stream body.');
        err.statusCode = 500;
        throw err;
    }
    const reader = res.body.getReader();
    const decoder = new TextDecoder();
    let buffer = '';
    while (true) {
        const { done, value } = await reader.read();
        if (done) break;
        buffer += decoder.decode(value, { stream: true });
        const parts = buffer.split('\n');
        buffer = parts.pop() || '';
        for (const line of parts) {
            const trimmed = line.trim();
            if (!trimmed.startsWith('data:')) continue;
            const payload = trimmed.slice(5).trim();
            if (!payload || payload === '[DONE]') continue;
            try {
                const json = JSON.parse(payload);
                const delta = json?.choices?.[0]?.delta?.content;
                if (typeof delta === 'string' && delta) yield delta;
            } catch (_) { /* ignore partial/invalid SSE frames */ }
        }
    }
}

function normalizeUserPrompt(userPrompt) {
    return truncate(userPrompt, 1500);
}

function hintBlock(userPrompt) {
    const hint = normalizeUserPrompt(userPrompt);
    return hint ? `\n\nYêu cầu thêm từ người dùng (ưu tiên tuân thủ):\n${hint}` : '';
}

function buildCommentMessages({ postText, count, userPrompt, forStream = false }) {
    const n = Math.max(1, Math.min(30, Number(count) || 5));
    const text = truncate(postText);
    if (!text) {
        const err = new Error('postText là bắt buộc.');
        err.statusCode = 400;
        throw err;
    }
    const format = forStream
        ? `Mỗi comment một dòng riêng. Đúng ${n} dòng. Không JSON, không đánh số, không markdown, không ngoặc kép.`
        : 'Chỉ trả JSON array các string, không markdown, không giải thích.';
    return {
        n,
        messages: [
            {
                role: 'system',
                content: [
                    'Bạn đóng vai người Việt bình thường đang lướt Facebook group, viết comment như chat với bạn bè — không phải nhân viên CSKH, không phải sales.',
                    'Giọng đời thường: ngắn, thoải mái, đôi khi dùng từ lóng nhẹ (nha, á, vậy, luôn, thật á). Có thể viết hoa thường lẫn lộn nhẹ như chat thật.',
                    'Mỗi comment khác nhau về ý và cách nói: hỏi thăm, tò mò, góp ý, kể trải nghiệm ngắn, thả cảm xúc — đừng lặp cùng một khuôn.',
                    'Bám đúng chủ đề bài; không bịa giá/địa chỉ/SĐT không có trong bài.',
                    'CẤM giọng khách sáo / marketing / CSKH / bán hộ. Không dùng kiểu: "Anh/Chị vui lòng", "Mình rất quan tâm sản phẩm", "Cho em xin thông tin ạ", "Inbox mình nhé", "liên hệ nhé", "ai cần thì...", "Chất lượng tốt không ạ?", "Cảm ơn bạn đã chia sẻ". Comment là người xem bài, không phải chủ bài.',
                    'Không emoji quá nhiều (tối đa 1 nếu cần), không viết hoa hết, không link/SĐT/Zalo trừ khi user yêu cầu.',
                    'Độ dài: thường 3–12 từ, tối đa 1–2 câu ngắn.',
                    format
                ].join(' ')
            },
            {
                role: 'user',
                content: [
                    `Viết đúng ${n} comment như người thật vừa đọc bài này.`,
                    'Ví dụ GIỌNG ĐÚNG (tham khảo phong cách, đừng copy): "cái này xài ổn ko vậy", "ủa còn hàng ko", "nhìn cũng được á", "chỗ nào vậy bác".',
                    'Ví dụ GIỌNG SAI (tránh): "Cho em xin thông tin chi tiết với ạ", "Mình quan tâm sản phẩm này, inbox giúp mình nhé".',
                    hintBlock(userPrompt),
                    '',
                    '--- NỘI DUNG BÀI ---',
                    text,
                    '--- HẾT ---'
                ].filter(Boolean).join('\n')
            }
        ]
    };
}

function buildPostMessages({ draft, userPrompt, forStream = false }) {
    const text = truncate(draft);
    if (!text) {
        const err = new Error('draft là bắt buộc.');
        err.statusCode = 400;
        throw err;
    }
    if (forStream) {
        return {
            messages: [
                {
                    role: 'system',
                    content: [
                        'Bạn viết bài đăng Facebook group tiếng Việt.',
                        'Giữ đúng ý, số liệu, tên sản phẩm/địa điểm trong draft; chỉ diễn đạt lại cho tự nhiên, rõ ràng.',
                        'Không bịa giá/khuyến mãi/thông tin không có trong draft trừ khi user yêu cầu.',
                        'Không chèn link Zalo/SĐT trừ khi draft hoặc user prompt có.',
                        'Chỉ trả nội dung bài thuần túy (plain text). Không JSON, không markdown, không tiêu đề phụ.'
                    ].join(' ')
                },
                {
                    role: 'user',
                    content: `Viết 1 bài đăng hoàn chỉnh từ draft.${hintBlock(userPrompt)}\n\n--- DRAFT ---\n${text}\n--- HẾT ---`
                }
            ]
        };
    }
    return {
        messages: [
            {
                role: 'system',
                content: [
                    'Bạn viết bài đăng Facebook group tiếng Việt.',
                    'Giữ đúng ý, số liệu, tên sản phẩm/địa điểm trong draft; chỉ diễn đạt lại cho tự nhiên, rõ ràng.',
                    'Không bịa giá/khuyến mãi/thông tin không có trong draft trừ khi user yêu cầu.',
                    'Không chèn link Zalo/SĐT trừ khi draft hoặc user prompt có.',
                    'Trả đúng JSON {"content":"chuỗi","variants":["chuỗi","chuỗi"]} — content và variants phải là string, không phải object. Không markdown.'
                ].join(' ')
            },
            {
                role: 'user',
                content: `Viết 1 bài đăng hoàn chỉnh từ draft, và 2 variants ngắn hơn cùng ý.${hintBlock(userPrompt)}\n\n--- DRAFT ---\n${text}\n--- HẾT ---`
            }
        ]
    };
}

async function generateComments({ postText, count = 5, userPrompt = '', fetchImpl } = {}) {
    const { n, messages } = buildCommentMessages({ postText, count, userPrompt, forStream: false });
    const content = await chatCompletions({ fetchImpl, messages, temperature: 1.05 });
    const comments = parseCommentList(content);
    if (!comments.length) {
        const err = new Error('LLM không trả về comment hợp lệ.');
        err.statusCode = 502;
        throw err;
    }
    return comments.slice(0, n);
}

async function generatePostContent({ draft, userPrompt = '', fetchImpl } = {}) {
    const { messages } = buildPostMessages({ draft, userPrompt, forStream: false });
    const contentRaw = await chatCompletions({ fetchImpl, messages });
    const parsed = parsePostContent(contentRaw);
    if (!parsed.content) {
        const err = new Error('LLM không trả về nội dung bài hợp lệ.');
        err.statusCode = 502;
        throw err;
    }
    return parsed;
}

/** Async generator: yields { type:'delta', text } then { type:'done', comments } */
async function* streamGenerateComments({ postText, count = 5, userPrompt = '', fetchImpl } = {}) {
    const { n, messages } = buildCommentMessages({ postText, count, userPrompt, forStream: true });
    let full = '';
    for await (const delta of streamChatCompletions({ messages, fetchImpl, temperature: 1.05 })) {
        full += delta;
        yield { type: 'delta', text: delta };
    }
    const comments = parseCommentList(full).slice(0, n);
    if (!comments.length) {
        const err = new Error('LLM không trả về comment hợp lệ.');
        err.statusCode = 502;
        throw err;
    }
    yield { type: 'done', comments, raw: full };
}

/** Async generator: yields { type:'delta', text } then { type:'done', content, variants } */
async function* streamGeneratePostContent({ draft, userPrompt = '', fetchImpl } = {}) {
    const { messages } = buildPostMessages({ draft, userPrompt, forStream: true });
    let full = '';
    for await (const delta of streamChatCompletions({ messages, fetchImpl })) {
        full += delta;
        yield { type: 'delta', text: delta };
    }
    const parsed = parsePostContent(full);
    const content = parsed.content || coercePlainText(full);
    if (!content) {
        const err = new Error('LLM không trả về nội dung bài hợp lệ.');
        err.statusCode = 502;
        throw err;
    }
    yield { type: 'done', content, variants: parsed.variants || [], raw: full };
}

module.exports = {
    getLlmConfig,
    truncate,
    coercePlainText,
    parseCommentList,
    parsePostContent,
    chatCompletions,
    streamChatCompletions,
    generateComments,
    generatePostContent,
    streamGenerateComments,
    streamGeneratePostContent
};
