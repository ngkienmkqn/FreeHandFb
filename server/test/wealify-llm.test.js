'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const {
    parseCommentList,
    parsePostContent,
    generateComments,
    generatePostContent,
    streamChatCompletions
} = require('../lib/wealify-llm');

test('parsePostContent flattens nested object content/variants', () => {
    const parsed = parsePostContent(JSON.stringify({
        content: { text: 'Bài chính nested' },
        variants: [{ text: 'Bản A' }, { content: 'Bản B' }]
    }));
    assert.equal(parsed.content, 'Bài chính nested');
    assert.deepEqual(parsed.variants, ['Bản A', 'Bản B']);
});

test('parseCommentList flattens object items', () => {
    const list = parseCommentList(JSON.stringify([
        { text: 'Comment 1' },
        { content: 'Comment 2' }
    ]));
    assert.deepEqual(list, ['Comment 1', 'Comment 2']);
});

test('parseCommentList reads JSON array even with markdown fences', () => {
    const list = parseCommentList('```json\n["Hay quá","Mình quan tâm"]\n```');
    assert.deepEqual(list, ['Hay quá', 'Mình quan tâm']);
});

test('parseCommentList falls back to lines', () => {
    const list = parseCommentList('1. Xin thông tin thêm\n- Giá sao bạn\n');
    assert.deepEqual(list, ['Xin thông tin thêm', 'Giá sao bạn']);
});

test('parsePostContent reads JSON object', () => {
    const parsed = parsePostContent('{"content":"Bài chính","variants":["Bản ngắn"]}');
    assert.equal(parsed.content, 'Bài chính');
    assert.deepEqual(parsed.variants, ['Bản ngắn']);
});

test('generateComments uses mock fetch and respects count', async () => {
    const prev = process.env.WEALIFY_LLM_API_KEY;
    process.env.WEALIFY_LLM_API_KEY = 'sk-test';
    try {
        let sentBody = null;
        const comments = await generateComments({
            postText: 'Bán nhà quận 7 giá tốt',
            count: 2,
            userPrompt: 'giọng thân thiện, hỏi giá',
            fetchImpl: async (_url, opts) => {
                sentBody = JSON.parse(opts.body);
                return {
                    ok: true,
                    text: async () => JSON.stringify({
                        choices: [{ message: { content: '["Comment A","Comment B","Comment C"]' } }]
                    })
                };
            }
        });
        assert.deepEqual(comments, ['Comment A', 'Comment B']);
        const userMsg = sentBody.messages.find(m => m.role === 'user').content;
        assert.match(userMsg, /giọng thân thiện, hỏi giá/);
        assert.match(userMsg, /Bán nhà quận 7/);
    } finally {
        if (prev === undefined) delete process.env.WEALIFY_LLM_API_KEY;
        else process.env.WEALIFY_LLM_API_KEY = prev;
    }
});

test('generateComments requires postText', async () => {
    await assert.rejects(() => generateComments({ postText: '  ' }), (err) => err.statusCode === 400);
});

test('generatePostContent uses mock fetch', async () => {
    const prev = process.env.WEALIFY_LLM_API_KEY;
    process.env.WEALIFY_LLM_API_KEY = 'sk-test';
    try {
        const result = await generatePostContent({
            draft: 'nhà đẹp giá tốt',
            fetchImpl: async () => ({
                ok: true,
                text: async () => JSON.stringify({
                    choices: [{ message: { content: '{"content":"Nhà đẹp giá tốt, inbox nhé","variants":["Inbox mình"]}' } }]
                })
            })
        });
        assert.equal(result.content, 'Nhà đẹp giá tốt, inbox nhé');
        assert.deepEqual(result.variants, ['Inbox mình']);
    } finally {
        if (prev === undefined) delete process.env.WEALIFY_LLM_API_KEY;
        else process.env.WEALIFY_LLM_API_KEY = prev;
    }
});

test('streamChatCompletions yields SSE deltas', async () => {
    const prev = process.env.WEALIFY_LLM_API_KEY;
    process.env.WEALIFY_LLM_API_KEY = 'sk-test';
    try {
        const sse = [
            'data: {"choices":[{"delta":{"content":"Xin"}}]}\n',
            'data: {"choices":[{"delta":{"content":" chào"}}]}\n',
            'data: [DONE]\n'
        ].join('\n');
        const encoder = new TextEncoder();
        let offset = 0;
        const body = {
            getReader() {
                return {
                    async read() {
                        if (offset >= sse.length) return { done: true, value: undefined };
                        const value = encoder.encode(sse.slice(offset));
                        offset = sse.length;
                        return { done: false, value };
                    }
                };
            }
        };
        const deltas = [];
        for await (const d of streamChatCompletions({
            messages: [{ role: 'user', content: 'hi' }],
            fetchImpl: async (_url, opts) => {
                assert.equal(JSON.parse(opts.body).stream, true);
                return { ok: true, body };
            }
        })) deltas.push(d);
        assert.deepEqual(deltas, ['Xin', ' chào']);
    } finally {
        if (prev === undefined) delete process.env.WEALIFY_LLM_API_KEY;
        else process.env.WEALIFY_LLM_API_KEY = prev;
    }
});
