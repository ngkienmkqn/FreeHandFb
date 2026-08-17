// Comment Helper Server - Hot-Reload Enabled
require('dotenv').config();
const express = require('express');
const app = express();
const http = require('http').Server(app);
const io = require('socket.io')(http, { cors: { origin: "*" } });
const path = require('path');
const cors = require('cors');
const fs = require('fs');
const crypto = require('crypto');
const dbStore = require('./db/store');
const executorPolicy = require('./lib/executor-policy');
const wealifyLlm = require('./lib/wealify-llm');
const { scheduledAtForJobIndex } = require('./lib/executor-schedule');
const { applyJobResolve } = require('./lib/executor-resolve');
const { isWithinActiveWindow, isPastMaxRuntime } = require('./lib/executor-target-window');
const { preferJoinedGate } = require('./lib/executor-claim-gate');
const { buildJoinJobsFromInputs } = require('./lib/executor-join-create');
const { resolveJoinIntelKey } = require('./lib/executor-join-input');

app.use(cors());
app.use(express.json({ limit: '200mb' }));
app.use(express.urlencoded({ limit: '200mb', extended: true }));

// Serve dashboard at root
app.get('/', (req, res) => res.sendFile(path.join(__dirname, 'public', 'dashboard.html')));
app.use(express.static(path.join(__dirname, 'public')));

/* ================== DATA PERSISTENCE ================== */

const DATA_DIR = path.join(__dirname, 'data');
const USERS_STORE = 'users';
const TOKENS_STORE = 'tokens';
const POSTS_STORE = 'posts';
const TEMPLATES_STORE = 'templates';
const CONFIG_STORE = 'config';
const NOTIFICATIONS_STORE = 'notifications';
const ARTICLES_STORE = 'articles';
const SUGGESTED_GROUPS_STORE = 'suggested_groups';
const SETTINGS_STORE = 'settings';
const INTERACTION_QUEUE_STORE = 'interaction_queue';
const INTERACTION_TARGETS_STORE = 'interaction_targets';
const GROUP_INTELLIGENCE_STORE = 'group_intelligence';
const PUBLISHING_QUEUE_STORE = 'publishing_queue';
const JOIN_QUEUE_STORE = 'join_queue';
const ENGINE_STORE = 'engine';
const APK_LOGS_FILE = path.join(DATA_DIR, 'apk_logs.txt');
const LOGS_DIR = path.join(DATA_DIR, 'logs');

if (!fs.existsSync(DATA_DIR)) fs.mkdirSync(DATA_DIR, { recursive: true });
if (!fs.existsSync(LOGS_DIR)) fs.mkdirSync(LOGS_DIR, { recursive: true });

let postgresStorageReady = false;
const pendingPostgresSaves = new Map();

function dataDocumentName(file) {
    return file;
}

function persistToPostgres(file, data) {
    const key = dataDocumentName(file);
    pendingPostgresSaves.set(key, { file, data });
    if (!postgresStorageReady) return;
    const item = pendingPostgresSaves.get(key);
    pendingPostgresSaves.delete(key);
    const write = async () => {
        if (file === USERS_STORE) return dbStore.replaceUsers(item.data || []);
        if (file === TOKENS_STORE) return dbStore.replaceTokens(item.data || {});
        if (file === INTERACTION_QUEUE_STORE) return dbStore.replaceJobs('interaction', item.data || []);
        if (file === PUBLISHING_QUEUE_STORE) return dbStore.replaceJobs('publishing', item.data || []);
        if (file === JOIN_QUEUE_STORE) return dbStore.replaceJobs('join', item.data || []);
        if (file === INTERACTION_TARGETS_STORE) return dbStore.replaceInteractionTargets(item.data || []);
        if (file === POSTS_STORE) return dbStore.replacePosts(item.data || []);
        if (file === TEMPLATES_STORE) return dbStore.replaceTemplates(item.data || {});
        if (file === NOTIFICATIONS_STORE) return dbStore.replaceNotifications(item.data || []);
        if (file === ARTICLES_STORE) return dbStore.replaceArticles(item.data || []);
        if (file === SUGGESTED_GROUPS_STORE) return dbStore.replaceSuggestedGroups(item.data || []);
        if (file === SETTINGS_STORE) return dbStore.saveSettings(item.data || {});
        if (file === CONFIG_STORE) return dbStore.saveConfig(item.data || {});
        if (file === GROUP_INTELLIGENCE_STORE) return dbStore.replaceGroupIntelligence(item.data || {});
        if (file === ENGINE_STORE) return dbStore.saveEngine(item.data || {});
        throw new Error(`Unknown PostgreSQL store: ${key}`);
    };
    write().catch(error => console.error(`[DB] Failed to persist ${key}:`, error.message));
}

function saveState(file, data) {
    persistToPostgres(file, data);
}
function genId() { return Date.now().toString(36) + Math.random().toString(36).slice(2, 7); }
function hashPw(pw) { return crypto.createHash('sha256').update(pw).digest('hex'); }

function normalizeTargetText(value, maxLength) {
    return String(value || '').replace(/\s+/g, ' ').trim().slice(0, maxLength);
}

function buildTargetPost(value) {
    if (!value || typeof value !== 'object' || Array.isArray(value)) return null;
    const author = normalizeTargetText(value.author, 160);
    const text = normalizeTargetText(value.text, 4000);
    const suppliedAnchors = Array.isArray(value.anchors) ? value.anchors : [];
    const generatedAnchors = text
        .split(/(?:[.!?]\s+|\n+)/)
        .map(part => normalizeTargetText(part, 160))
        .filter(part => part.length >= 12);
    if (text.length >= 12 && generatedAnchors.length === 0) generatedAnchors.push(text.slice(0, 160));
    const anchors = [...new Set([...suppliedAnchors, ...generatedAnchors]
        .map(part => normalizeTargetText(part, 160))
        .filter(part => part.length >= 8))]
        .slice(0, 5);
    if (!author && !text && anchors.length === 0) return null;
    return { author, text, anchors };
}

// --- Data stores ---
let users = [];
let tokens = {};
let posts = [];
let templates = {};
let notifications = [];
let articles = [];
let suggestedGroups = [];
let appSettings = { maxGroupPostsPerDay: 1, llmEnabled: false };
let interactionQueue = [];
let interactionTargets = [];
let groupIntelligence = {};
let publishingQueue = [];
let joinQueue = [];

function runLogsCleanup() {
    try {
        console.log('[LOGS] Running weekly logs garbage collection...');
        const now = Date.now();
        const SEVEN_DAYS_MS = 7 * 24 * 3600 * 1000;
        
        if (fs.existsSync(LOGS_DIR)) {
            const files = fs.readdirSync(LOGS_DIR);
            files.forEach(file => {
                const filePath = path.join(LOGS_DIR, file);
                const stats = fs.statSync(filePath);
                if (now - stats.mtimeMs > SEVEN_DAYS_MS) {
                    fs.unlinkSync(filePath);
                    console.log(`[LOGS] Deleted old user log: ${file}`);
                }
            });
        }
        
        if (fs.existsSync(APK_LOGS_FILE)) {
            const stats = fs.statSync(APK_LOGS_FILE);
            if (now - stats.mtimeMs > SEVEN_DAYS_MS) {
                fs.writeFileSync(APK_LOGS_FILE, '[CLEANED] Monolithic log truncated.\n', 'utf8');
                console.log('[LOGS] Truncated monolithic apk_logs.txt');
            }
        }
        
        appSettings.lastLogsCleanup = now;
        saveState(SETTINGS_STORE, appSettings);
    } catch (e) {
        console.error('[LOGS] Error during weekly garbage collection:', e);
    }
}

function scheduleLogsCleanup() {
    const lastCleanup = appSettings.lastLogsCleanup || 0;
    if (Date.now() - lastCleanup > 7 * 24 * 3600 * 1000) {
        runLogsCleanup();
    }
    setInterval(() => {
        const last = appSettings.lastLogsCleanup || 0;
        if (Date.now() - last > 7 * 24 * 3600 * 1000) {
            runLogsCleanup();
        }
    }, 24 * 3600 * 1000); // Check once a day
}
let config = {
    appVersion: '1.0.0',
    apkUrl: '',
    changelog: '',
    defaultComments: []
};

// Ensure system admin always exists
const SYSTEM_ADMIN = 'admin@xommuaban.com';
// PostgreSQL bootstrap is responsible for seeding/resetting system admin.

/* ================== AUTH ================== */

// Login
app.post('/api/login', (req, res) => {
    const { username, password, deviceId, isWeb } = req.body;
    if (!username || !password || !deviceId) return res.status(400).json({ error: 'Username, password and System ID required' });

    const user = users.find(u => u.username === username && u.password === hashPw(password));
    if (!user) return res.status(401).json({ error: 'Sai tài khoản hoặc mật khẩu' });
    if (user.isLocked) return res.status(403).json({ error: 'Tài khoản đã bị tạm khóa. Vui lòng liên hệ Admin.' });

    if (user.role !== 'admin' && !isWeb) {
        if (!user.deviceId) {
            user.deviceId = deviceId;
            saveState(USERS_STORE, users);
        } else if (user.deviceId !== deviceId) {
            return res.status(403).json({ error: 'Thiết bị máy cày không hợp lệ. Vui lòng liên hệ Admin để đổi thiết bị đăng nhập.' });
        }
    }

    const token = crypto.randomUUID();
    tokens[token] = {
        userId: user.id,
        username: user.username,
        group: user.group,
        role: user.role,
        createdAt: Date.now()
    };
    saveState(TOKENS_STORE, tokens);

    res.json({
        token,
        user: { id: user.id, username: user.username, group: user.group, role: user.role }
    });
});

// Logout
app.post('/api/logout', (req, res) => {
    const token = extractToken(req);
    if (token && tokens[token]) {
        delete tokens[token];
        saveState(TOKENS_STORE, tokens);
    }
    res.json({ ok: true });
});

function extractToken(req) {
    const auth = req.headers.authorization;
    if (auth && auth.startsWith('Bearer ')) return auth.slice(7);
    return null;
}

function authMiddleware(req, res, next) {
    const token = extractToken(req);
    if (!token || !tokens[token]) return res.status(401).json({ error: 'Unauthorized' });
    const u = users.find(x => x.id === tokens[token].userId);
    if (u && u.isLocked) return res.status(401).json({ error: 'Tài khoản đã bị khóa' });
    req.user = tokens[token];
    next();
}

function adminOnly(req, res, next) {
    if (req.user.role !== 'admin') return res.status(403).json({ error: 'Admin only' });
    next();
}

/* ================== EXECUTOR QUEUES ================== */

const EXECUTOR_LEASE_MS = 60 * 1000;
const executorQueues = {
    interaction: () => interactionQueue,
    publishing: () => publishingQueue,
    join: () => joinQueue
};

function saveExecutorQueue(type) {
    if (type === 'interaction') saveState(INTERACTION_QUEUE_STORE, interactionQueue);
    if (type === 'publishing') saveState(PUBLISHING_QUEUE_STORE, publishingQueue);
    if (type === 'join') saveState(JOIN_QUEUE_STORE, joinQueue);
}

function getExecutorQueue(type) {
    return executorQueues[type]?.() || null;
}

function publicExecutorJob(job) {
    const copy = { ...job };
    delete copy.leaseToken;
    return copy;
}

function parseScheduledAt(value) {
    if (value === undefined || value === null || value === '') return 0;
    const ts = typeof value === 'number' ? value : Date.parse(String(value));
    return Number.isFinite(ts) && ts > 0 ? ts : NaN;
}

function isExecutorJobDue(job, now = Date.now()) {
    return !job.scheduledAt || job.scheduledAt <= now;
}

function canClaimExecutorJob(item, type, user, now = Date.now(), deviceId = '') {
    // onlineOnly is intentionally unused — no device presence in MVP.
    if (item.group !== user.group || item.status !== 'QUEUED') return false;
    if (!isExecutorJobDue(item, now)) return false;

    // A retry must not immediately return to the account/device that just failed it.
    // Keeping the exclusions in payload makes the rule survive server restarts.
    if (!executorPolicy.canExecutorRetry(item.payload?.retry || {}, user.username, deviceId)) return false;

    if (type === 'publishing' || type === 'join') {
        return item.createdBy === user.username;
    }

    const targetId = item.targetPostId || item.payload?.targetPostId;
    if (!targetId) return true;
    const target = interactionTargets.find(t => t.id === targetId);
    if (target?.status !== 'RUNNING') return false;
    if (!isWithinActiveWindow(target, now)) return false;
    const targetGroup = target.groupId || target.group;
    if (groupPausedReason(targetGroup)) return false;
    const intel = groupIntel(targetGroup);
    const membership = intel.accountMembership?.[user.username];
    if (membership && ['NOT_JOINED', 'PENDING', 'BLOCKED', 'LEFT'].includes(membership.status)) return false;
    if (membership?.cooldownUntil > now) return false;
    if (!preferJoinedGate(intel, user.username)) return false;
    if (item.payload?.actions?.comment !== true) return true;
    if (commentRecentlyUsedInGroup(targetGroup, item.payload?.comment)) return false;
    return !targetJobs(targetId).some(job =>
        job.id !== item.id &&
        job.payload?.actions?.comment === true &&
        job.claimedBy === user.username &&
        ['RUNNING', 'SUCCEEDED', 'INTERRUPTED'].includes(job.status)
    );
}

function createReplacementJob(failedJob, failedBy, failedDeviceId, failure) {
    const targetId = failedJob.targetPostId || failedJob.payload?.targetPostId;
    const target = targetId && interactionTargets.find(item => item.id === targetId);
    if (failedJob.type !== 'interaction' || !target || target.status !== 'RUNNING') return null;
    if (failure.retryable === false || failedJob.irreversibleAt) return null;

    const previousRetry = failedJob.payload?.retry || {};
    const retryNumber = Number(previousRetry.retryNumber || 0) + 1;
    const maxRetries = Math.max(1, Number(target.autoClose?.maxFailedJobs || 5));
    if (retryNumber > maxRetries) return null;

    const excludedAccounts = [...new Set([...(previousRetry.excludedAccounts || []), failedBy].filter(Boolean))];
    const excludedDevices = [...new Set([...(previousRetry.excludedDevices || []), failedDeviceId].filter(Boolean))];
    const now = Date.now();
    const remaining = executorPolicy.remainingActions(failedJob.payload?.actionStates || failedJob.payload?.actions);
    if (!remaining.like && !remaining.comment) return null;
    const replacement = {
        id: `INT-${genId()}`,
        type: failedJob.type,
        group: failedJob.group,
        targetPostId: targetId,
        priority: failedJob.priority,
        payload: {
            ...failedJob.payload,
            actions: remaining,
            actionStates: executorPolicy.normalizeActions({
                like: { required: remaining.like, status: remaining.like ? 'PENDING' : 'SKIPPED' },
                comment: { required: remaining.comment, status: remaining.comment ? 'PENDING' : 'SKIPPED' }
            }),
            retry: {
                retryNumber,
                retryOf: previousRetry.retryOf || failedJob.id,
                previousJobId: failedJob.id,
                excludedAccounts,
                excludedDevices,
                lastFailure: failure
            }
        },
        status: 'QUEUED',
        attempts: 0,
        createdBy: failedJob.createdBy,
        createdAt: now,
        updatedAt: now
    };
    interactionQueue.push(replacement);
    return replacement;
}

function reclaimExpiredExecutorJobs() {
    const now = Date.now();
    ['interaction', 'publishing', 'join'].forEach(type => {
        const queue = getExecutorQueue(type);
        let changed = false;
        queue.forEach(job => {
            if (job.status === 'RUNNING' && now - (job.heartbeatAt || job.claimedAt || 0) > EXECUTOR_LEASE_MS) {
                job.status = job.irreversibleAt ? 'INTERRUPTED' : 'QUEUED';
                job.lastError = job.irreversibleAt
                    ? 'Mất heartbeat sau thao tác Gửi/Đăng; cần kiểm tra thủ công.'
                    : 'Lease hết hạn; job đã được trả lại queue.';
                job.claimedBy = null;
                job.deviceId = null;
                job.leaseToken = null;
                job.updatedAt = now;
                changed = true;
            }
        });
        if (changed) saveExecutorQueue(type);
    });
}

function findExecutorJob(id) {
    for (const type of ['interaction', 'publishing', 'join']) {
        const job = getExecutorQueue(type).find(item => item.id === id);
        if (job) return { type, job };
    }
    return null;
}

function emitExecutorUpdate(group) {
    io.to(`group:${group}`).emit('executor_jobs_updated', { group });
}

function saveInteractionTargets() {
    saveState(INTERACTION_TARGETS_STORE, interactionTargets);
}

function emitInteractionTargetsUpdate(group) {
    io.to(`group:${group}`).emit('interaction_targets_updated', { group });
}

function saveGroupIntelligence() {
    saveState(GROUP_INTELLIGENCE_STORE, groupIntelligence);
}

function intelligenceKey(groupId) {
    return normalizeTargetText(groupId || 'default', 180) || 'default';
}

function groupIntel(groupId) {
    const key = intelligenceKey(groupId);
    if (!groupIntelligence[key]) {
        groupIntelligence[key] = {
            groupId: key,
            joinedAccounts: {},
            accountMembership: {},
            accountActivity: {},
            recentComments: [],
            failStreak: 0,
            pausedUntil: 0,
            pauseReason: '',
            updatedAt: Date.now()
        };
    }
    return groupIntelligence[key];
}

function trimGroupIntel(intel) {
    const now = Date.now();
    intel.recentComments = (intel.recentComments || [])
        .filter(item => now - (item.usedAt || 0) < 7 * 24 * 3600 * 1000)
        .slice(-300);
    Object.keys(intel.accountActivity || {}).forEach(username => {
        const activity = intel.accountActivity[username];
        if (activity.comments) activity.comments = activity.comments.slice(-100);
        if (activity.jobs) activity.jobs = activity.jobs.slice(-150);
    });
}

function commentRecentlyUsedInGroup(groupId, comment, windowMs = 30 * 60 * 1000) {
    const normalized = normalizeTargetText(comment, 4000).toLowerCase();
    if (!normalized) return false;
    const now = Date.now();
    return (groupIntel(groupId).recentComments || []).some(item =>
        normalizeTargetText(item.comment, 4000).toLowerCase() === normalized &&
        now - (item.usedAt || 0) < windowMs
    );
}

function accountJoinedGroup(username, groupId) {
    if (!username) return false;
    const intel = groupIntel(groupId);
    return !!intel.joinedAccounts?.[username];
}

function markAccountJoinedGroup(username, groupId, source) {
    if (!username) return;
    const intel = groupIntel(groupId);
    intel.joinedAccounts[username] = {
        username,
        source: source || 'interaction_success',
        joinedAt: intel.joinedAccounts[username]?.joinedAt || Date.now(),
        lastSeenAt: Date.now()
    };
    intel.accountMembership ||= {};
    intel.accountMembership[username] = {
        username, status: 'JOINED', source: source || 'interaction_success',
        lastVerifiedAt: Date.now(), cooldownUntil: 0
    };
    intel.updatedAt = Date.now();
    trimGroupIntel(intel);
    saveGroupIntelligence();
}

function recordGroupInteraction(job, outcome, req) {
    const targetGroup = job.payload?.groupId || job.group;
    const intel = groupIntel(targetGroup);
    const now = Date.now();
    const username = job.claimedBy || req?.user?.username || '';
    const targetPostId = job.targetPostId || job.payload?.targetPostId || '';
    const actions = job.payload?.actions || {};
    if (username) {
        if (!intel.accountActivity[username]) intel.accountActivity[username] = { jobs: [], comments: [] };
        intel.accountActivity[username].jobs.push({ jobId: job.id, targetPostId, outcome, at: now });
    }
    if (outcome === 'SUCCEEDED') {
        intel.failStreak = 0;
        intel.pauseReason = '';
        intel.pausedUntil = 0;
        if (username) markAccountJoinedGroup(username, targetGroup, 'interaction_success');
        if (actions.comment === true && job.payload?.comment) {
            const item = { comment: job.payload.comment, username, targetPostId, jobId: job.id, usedAt: now };
            intel.recentComments.push(item);
            if (username) intel.accountActivity[username].comments.push(item);
        }
    } else if (['FAILED', 'INTERRUPTED'].includes(outcome)) {
        const failure = job.result?.failure || {};
        intel.accountMembership ||= {};
        if (username && failure.code === 'GROUP_MEMBERSHIP_REQUIRED') {
            delete intel.joinedAccounts[username];
            intel.accountMembership[username] = { username, status: 'NOT_JOINED', lastVerifiedAt: now, lastError: failure.message };
        } else if (username && failure.code === 'FACEBOOK_ACTION_BLOCK') {
            delete intel.joinedAccounts[username];
            intel.accountMembership[username] = {
                username, status: 'BLOCKED', lastVerifiedAt: now, lastError: failure.message,
                cooldownUntil: now + 24 * 60 * 60 * 1000
            };
        }
        // Account/device/infrastructure failures must not poison every target in the Facebook group.
        if (!['GROUP', 'TARGET'].includes(failure.category)) {
            intel.updatedAt = now;
            trimGroupIntel(intel);
            saveGroupIntelligence();
            return;
        }
        intel.failStreak = (intel.failStreak || 0) + 1;
        intel.lastFailureAt = now;
        intel.lastFailure = job.lastError || outcome;
        if (intel.failStreak >= (appSettings.maxGroupInteractionFailStreak || 5)) {
            intel.pausedUntil = now + (appSettings.groupInteractionPauseMinutes || 60) * 60 * 1000;
            intel.pauseReason = `Fail liên tục ${intel.failStreak} job: ${intel.lastFailure}`;
            interactionTargets.forEach(target => {
                if ((target.groupId || target.group) === targetGroup && target.status === 'RUNNING') {
                    target.status = 'NEEDS_REVIEW';
                    target.reviewReason = intel.pauseReason;
                    target.updatedAt = now;
                }
            });
            saveInteractionTargets();
            emitInteractionTargetsUpdate(job.group);
        }
    }
    intel.updatedAt = now;
    trimGroupIntel(intel);
    saveGroupIntelligence();
}

function groupPausedReason(groupId) {
    const intel = groupIntel(groupId);
    if (intel.pausedUntil && intel.pausedUntil > Date.now()) return intel.pauseReason || 'Group đang tạm ngưng do fail liên tục.';
    return '';
}

function normalizeQuantity(value) {
    const n = parseInt(value, 10);
    return Number.isFinite(n) && n > 0 ? Math.min(n, 500) : 0;
}

function normalizeCommentPool(value) {
    const raw = Array.isArray(value) ? value : String(value || '').split(/\r?\n/);
    return [...new Set(raw.map(item => String(item || '').replace(/\s+/g, ' ').trim()).filter(Boolean))].slice(0, 500);
}

function priorityRank(priority) {
    return { HIGH: 3, NORMAL: 2, LOW: 1 }[priority] || 2;
}

function normalizePriority(value) {
    const priority = String(value || 'NORMAL').toUpperCase();
    return ['HIGH', 'NORMAL', 'LOW'].includes(priority) ? priority : 'NORMAL';
}

function normalizeSpeed(value) {
    const speed = String(value || 'NORMAL').toUpperCase();
    return ['SLOW', 'NORMAL', 'FAST'].includes(speed) ? speed : 'NORMAL';
}

function targetJobs(targetId) {
    return interactionQueue.filter(job => job.targetPostId === targetId || job.payload?.targetPostId === targetId);
}

function summarizeInteractionTarget(target) {
    const jobs = targetJobs(target.id);
    const countByStatus = jobs.reduce((acc, job) => {
        acc[job.status] = (acc[job.status] || 0) + 1;
        return acc;
    }, {});
    const succeeded = jobs.filter(job => job.status === 'SUCCEEDED');
    const likesDone = succeeded.filter(job => job.payload?.actions?.like !== false).length;
    const commentsDone = succeeded.filter(job => job.payload?.actions?.comment === true).length;
    const intel = groupIntel(target.groupId || target.group);
    return {
        ...target,
        progress: {
            like: { done: likesDone, total: target.requirements?.like?.quantity || 0 },
            comment: { done: commentsDone, total: target.requirements?.comment?.quantity || 0 }
        },
        jobs: {
            total: jobs.length,
            queued: countByStatus.QUEUED || 0,
            running: countByStatus.RUNNING || 0,
            succeeded: countByStatus.SUCCEEDED || 0,
            failed: countByStatus.FAILED || 0,
            interrupted: countByStatus.INTERRUPTED || 0,
            canceled: countByStatus.CANCELED || 0
        },
        groupIntelligence: {
            joinedAccounts: Object.keys(intel.joinedAccounts || {}).length,
            recentComments: (intel.recentComments || []).length,
            failStreak: intel.failStreak || 0,
            pausedUntil: intel.pausedUntil || 0,
            pauseReason: intel.pauseReason || ''
        }
    };
}

function refreshInteractionTargetStatus(target) {
    if (!target || !['RUNNING', 'PAUSED'].includes(target.status)) return false;
    const summary = summarizeInteractionTarget(target);
    const likeRequirement = target.requirements?.like?.enabled ? target.requirements.like.quantity : 0;
    const commentRequirement = target.requirements?.comment?.enabled ? target.requirements.comment.quantity : 0;
    const enoughLikes = summary.progress.like.done >= likeRequirement;
    const enoughComments = summary.progress.comment.done >= commentRequirement;
    if (enoughLikes && enoughComments) {
        target.status = 'COMPLETED';
        target.completedAt = Date.now();
        target.updatedAt = Date.now();
        return true;
    }
    const maxFailedJobs = target.autoClose?.maxFailedJobs || 0;
    if (maxFailedJobs > 0 && summary.jobs.failed >= maxFailedJobs) {
        target.status = 'NEEDS_REVIEW';
        target.reviewReason = 'Fail quá ngưỡng cấu hình.';
        target.updatedAt = Date.now();
        return true;
    }
    if (
        target.status === 'RUNNING' &&
        target.autoClose?.enabled !== false &&
        isPastMaxRuntime(target)
    ) {
        target.status = 'NEEDS_REVIEW';
        target.reviewReason = 'Quá maxRuntimeHours.';
        target.updatedAt = Date.now();
        return true;
    }
    return false;
}

function refreshAllInteractionTargets() {
    let changed = false;
    interactionTargets.forEach(target => {
        if (refreshInteractionTargetStatus(target)) changed = true;
    });
    if (changed) saveInteractionTargets();
}

function planInteractionTarget(target) {
    if (!target || target.status !== 'RUNNING') return { created: 0, jobs: [] };
    const pausedReason = groupPausedReason(target.groupId || target.group);
    if (pausedReason) {
        const error = new Error(pausedReason);
        error.statusCode = 409;
        throw error;
    }

    const jobs = targetJobs(target.id);
    const countedStatuses = new Set(['QUEUED', 'RUNNING', 'SUCCEEDED']);
    const plannedLikes = jobs.filter(job => countedStatuses.has(job.status) && job.payload?.actions?.like !== false).length;
    const plannedComments = jobs.filter(job => countedStatuses.has(job.status) && job.payload?.actions?.comment === true).length;
    const likeGoal = target.requirements?.like?.enabled ? target.requirements.like.quantity : 0;
    const commentGoal = target.requirements?.comment?.enabled ? target.requirements.comment.quantity : 0;
    const likesToCreate = Math.max(0, likeGoal - plannedLikes);
    const commentsToCreate = Math.max(0, commentGoal - plannedComments);
    const jobCount = Math.max(likesToCreate, commentsToCreate);
    if (jobCount === 0) return { created: 0, jobs: [] };

    const usedComments = new Set(jobs.map(job => job.payload?.comment).filter(Boolean));
    const freshComments = (target.commentPool || []).filter(comment =>
        !usedComments.has(comment) &&
        !commentRecentlyUsedInGroup(target.groupId || target.group, comment)
    );
    const fallbackComments = (target.commentPool || []).filter(comment => !usedComments.has(comment));
    if (commentsToCreate > 0 && !target.allowRepeatComments && freshComments.length < commentsToCreate) {
        const error = new Error('Không đủ comment mẫu mới cho group; một số comment vừa được dùng gần đây.');
        error.statusCode = 400;
        throw error;
    }
    const availableComments = freshComments.length >= commentsToCreate ? freshComments : fallbackComments;
    if (commentsToCreate > 0 && !target.allowRepeatComments && availableComments.length < commentsToCreate) {
        const error = new Error('Không đủ comment mẫu để tạo job không lặp.');
        error.statusCode = 400;
        throw error;
    }

    const now = Date.now();
    const createdJobs = [];
    for (let i = 0; i < jobCount; i++) {
        const shouldLike = i < likesToCreate;
        const shouldComment = i < commentsToCreate;
        const comment = shouldComment
            ? (availableComments[i] || target.commentPool[(plannedComments + i) % target.commentPool.length])
            : '';
        const job = {
            id: `INT-${genId()}`,
            type: 'interaction',
            group: target.group,
            targetPostId: target.id,
            priority: target.priority,
            payload: {
                type: 'interaction',
                targetPostId: target.id,
                groupId: target.groupId || target.group,
                url: normalizeFbUrlForNative(target.postUrl),
                postUrl: normalizeFbUrlForNative(target.postUrl),
                actions: { like: shouldLike, comment: shouldComment },
                actionStates: executorPolicy.normalizeActions({ like: shouldLike, comment: shouldComment }),
                ...(shouldComment ? { comment: comment.slice(0, 4000) } : { comment: '' }),
                ...(target.targetPost ? { targetPost: target.targetPost } : {})
            },
            status: 'QUEUED',
            attempts: 0,
            createdBy: target.createdBy,
            createdAt: now + i,
            updatedAt: now
        };
        job.scheduledAt = scheduledAtForJobIndex(now, i, jobCount, target.speed);
        interactionQueue.push(job);
        createdJobs.push(job);
    }
    target.lastPlannedAt = now;
    target.updatedAt = now;
    saveExecutorQueue('interaction');
    saveInteractionTargets();
    emitExecutorUpdate(target.group);
    emitInteractionTargetsUpdate(target.group);
    return { created: createdJobs.length, jobs: createdJobs };
}

app.get('/api/executor/queues', authMiddleware, (req, res) => {
    reclaimExpiredExecutorJobs();
    refreshAllInteractionTargets();
    const result = {};
    for (const type of ['interaction', 'publishing', 'join']) {
        const jobs = getExecutorQueue(type)
            .filter(job => req.user.role === 'admin' || job.group === req.user.group)
            .sort((a, b) => b.createdAt - a.createdAt);
        result[type] = {
            counts: jobs.reduce((acc, job) => {
                acc[job.status] = (acc[job.status] || 0) + 1;
                return acc;
            }, {}),
            jobs: jobs.slice(0, 50).map(publicExecutorJob)
        };
    }
    res.json(result);
});

app.get('/api/interaction-targets', authMiddleware, (req, res) => {
    reclaimExpiredExecutorJobs();
    refreshAllInteractionTargets();
    const targets = interactionTargets
        .filter(target => req.user.role === 'admin' || target.group === req.user.group)
        .sort((a, b) => (b.createdAt || 0) - (a.createdAt || 0))
        .map(summarizeInteractionTarget);
    res.json(targets);
});

app.get('/api/group-intelligence', authMiddleware, (req, res) => {
    const entries = Object.values(groupIntelligence)
        .filter(item => req.user.role === 'admin' || item.groupId === req.user.group)
        .map(item => {
            const joinedAccounts = Object.values(item.joinedAccounts || {});
            const accountMembership = Object.values(item.accountMembership || {});
            return {
                groupId: item.groupId,
                joinedAccounts,
                accountMembership,
                joinedCount: joinedAccounts.length,
                recentComments: (item.recentComments || []).slice(-30).reverse(),
                failStreak: item.failStreak || 0,
                pausedUntil: item.pausedUntil || 0,
                pauseReason: item.pauseReason || '',
                updatedAt: item.updatedAt || 0
            };
        })
        .sort((a, b) => (b.updatedAt || 0) - (a.updatedAt || 0));
    res.json(entries);
});

app.put('/api/group-intelligence/:groupId/accounts/:username', authMiddleware, adminOnly, (req, res) => {
    const username = normalizeTargetText(req.params.username, 160);
    const groupId = intelligenceKey(req.params.groupId);
    if (!username) return res.status(400).json({ error: 'Username không hợp lệ.' });

    const allowed = ['JOINED', 'NOT_JOINED', 'PENDING', 'BLOCKED', 'LEFT'];
    let status = String(req.body.status || '').toUpperCase();
    if (!status) {
        if (req.body.joined === false) status = 'NOT_JOINED';
        else if (req.body.joined === true) status = 'JOINED';
    }
    if (!allowed.includes(status)) {
        return res.status(400).json({ error: `status phải là một trong: ${allowed.join(', ')}` });
    }

    const intel = groupIntel(groupId);
    intel.accountMembership ||= {};
    if (status === 'JOINED') {
        markAccountJoinedGroup(username, groupId, req.body.source || 'admin');
        return res.json({
            ok: true,
            joined: true,
            status,
            account: intel.joinedAccounts[username],
            membership: intel.accountMembership[username]
        });
    }

    delete intel.joinedAccounts[username];
    intel.accountMembership[username] = {
        username,
        status,
        source: req.body.source || 'admin',
        lastVerifiedAt: Date.now(),
        cooldownUntil: status === 'BLOCKED' ? Date.now() + 24 * 60 * 60 * 1000 : 0,
        lastError: normalizeTargetText(req.body.lastError || '', 500)
    };
    intel.updatedAt = Date.now();
    saveGroupIntelligence();
    res.json({ ok: true, joined: false, status, membership: intel.accountMembership[username] });
});

app.post('/api/group-intelligence/:groupId/resume', authMiddleware, adminOnly, (req, res) => {
    const intel = groupIntel(req.params.groupId);
    intel.failStreak = 0;
    intel.pausedUntil = 0;
    intel.pauseReason = '';
    intel.updatedAt = Date.now();
    saveGroupIntelligence();
    res.json({ ok: true, group: intel });
});

app.post('/api/interaction-targets', authMiddleware, (req, res) => {
    const postUrl = String(req.body.postUrl || req.body.url || '').trim();
    const targetPost = buildTargetPost(req.body.targetPost);
    const likeQuantity = normalizeQuantity(req.body.likeQuantity ?? req.body.requirements?.like?.quantity);
    const commentQuantity = normalizeQuantity(req.body.commentQuantity ?? req.body.requirements?.comment?.quantity);
    const likeEnabled = req.body.likeEnabled !== false && likeQuantity > 0;
    const commentEnabled = req.body.commentEnabled !== false && commentQuantity > 0;
    const commentPool = normalizeCommentPool(req.body.commentPool);
    const allowRepeatComments = req.body.allowRepeatComments === true;
    if (!/^https?:\/\//i.test(postUrl)) return res.status(400).json({ error: 'Link bài Facebook không hợp lệ.' });
    if (!targetPost || targetPost.anchors.length === 0) return res.status(400).json({ error: 'Nội dung bài mục tiêu là bắt buộc để app khóa đúng bài.' });
    if (!likeEnabled && !commentEnabled) return res.status(400).json({ error: 'Cần ít nhất một mục tiêu like hoặc comment.' });
    if (commentEnabled && commentPool.length === 0) return res.status(400).json({ error: 'Bật comment thì phải nhập comment pool.' });
    if (commentEnabled && !allowRepeatComments && commentPool.length < commentQuantity) {
        return res.status(400).json({ error: `Cần ${commentQuantity} comment nhưng pool chỉ có ${commentPool.length}. Bật cho phép lặp nếu muốn tiếp tục.` });
    }

    const now = Date.now();
    const group = req.user.role === 'admin' && req.body.group ? String(req.body.group).trim() : req.user.group;
    const pausedReason = groupPausedReason(req.body.groupId || group);
    if (pausedReason) return res.status(409).json({ error: pausedReason });
    const bodyActive = req.body.activeHours && typeof req.body.activeHours === 'object'
        ? req.body.activeHours
        : null;
    const activeStart = String(bodyActive?.start || '').trim();
    const activeEnd = String(bodyActive?.end || '').trim();
    const activeHours = (activeStart && activeEnd) ? { start: activeStart, end: activeEnd } : undefined;
    const maxRuntimeHours = normalizeQuantity(req.body.autoClose?.maxRuntimeHours) || 24;
    const maxFailedJobs = normalizeQuantity(req.body.autoClose?.maxFailedJobs) || 5;
    const target = {
        id: `TGT-${genId()}`,
        postUrl: normalizeFbUrlForNative(postUrl),
        group,
        groupId: String(req.body.groupId || group),
        status: 'RUNNING',
        requirements: {
            like: { enabled: likeEnabled, quantity: likeEnabled ? likeQuantity : 0 },
            comment: { enabled: commentEnabled, quantity: commentEnabled ? commentQuantity : 0 }
        },
        commentPool,
        allowRepeatComments,
        targetPost,
        speed: normalizeSpeed(req.body.speed),
        priority: normalizePriority(req.body.priority),
        onlineOnly: false, // ignored — no device presence in MVP
        ...(activeHours ? { activeHours } : {}),
        autoClose: {
            enabled: req.body.autoClose?.enabled !== false,
            whenRequirementsMet: req.body.autoClose?.whenRequirementsMet !== false,
            maxRuntimeHours,
            maxFailedJobs,
            // Persist activeHours inside autoClose JSONB so it survives reload without a migration.
            ...(activeHours ? { activeHours } : {})
        },
        createdBy: req.user.username,
        createdAt: now,
        updatedAt: now
    };
    interactionTargets.push(target);
    try {
        const planned = planInteractionTarget(target);
        res.status(201).json({ target: summarizeInteractionTarget(target), planned: planned.created });
    } catch (e) {
        interactionTargets = interactionTargets.filter(item => item.id !== target.id);
        saveInteractionTargets();
        res.status(e.statusCode || 500).json({ error: e.message || 'Không lập kế hoạch được target.' });
    }
});

app.patch('/api/interaction-targets/:id', authMiddleware, (req, res) => {
    const target = interactionTargets.find(item => item.id === req.params.id);
    if (!target) return res.status(404).json({ error: 'Target không tồn tại.' });
    if (req.user.role !== 'admin' && target.group !== req.user.group) return res.status(403).json({ error: 'Forbidden' });
    if (req.body.priority !== undefined) target.priority = normalizePriority(req.body.priority);
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
            const resumedFromReviewAt = Date.now();
            target.resumedFromReviewAt = resumedFromReviewAt;
            // Mirror into autoClose JSONB so Postgres persist (auto_close) survives reload.
            target.autoClose = { ...(target.autoClose || {}), resumedFromReviewAt };
        }
        target.status = status;
    }
    target.updatedAt = Date.now();
    saveInteractionTargets();
    if (target.status === 'RUNNING') {
        try { planInteractionTarget(target); } catch (_) {}
    }
    emitInteractionTargetsUpdate(target.group);
    res.json(summarizeInteractionTarget(target));
});

app.post('/api/interaction-targets/:id/plan', authMiddleware, (req, res) => {
    const target = interactionTargets.find(item => item.id === req.params.id);
    if (!target) return res.status(404).json({ error: 'Target không tồn tại.' });
    if (req.user.role !== 'admin' && target.group !== req.user.group) return res.status(403).json({ error: 'Forbidden' });
    try {
        const planned = planInteractionTarget(target);
        res.json({ target: summarizeInteractionTarget(target), planned: planned.created });
    } catch (e) {
        res.status(e.statusCode || 500).json({ error: e.message || 'Không lập kế hoạch được target.' });
    }
});

app.post('/api/interaction-targets/:id/close', authMiddleware, (req, res) => {
    const target = interactionTargets.find(item => item.id === req.params.id);
    if (!target) return res.status(404).json({ error: 'Target không tồn tại.' });
    if (req.user.role !== 'admin' && target.group !== req.user.group) return res.status(403).json({ error: 'Forbidden' });
    const now = Date.now();
    target.status = 'CLOSED';
    target.closedAt = now;
    target.closedBy = req.user.username;
    target.closeReason = normalizeTargetText(req.body.reason || 'Đóng thủ công', 240);
    target.updatedAt = now;
    let canceled = 0;
    interactionQueue.forEach(job => {
        if ((job.targetPostId === target.id || job.payload?.targetPostId === target.id) && job.status === 'QUEUED') {
            job.status = 'CANCELED';
            job.lastError = 'Target post đã đóng.';
            job.updatedAt = now;
            canceled++;
        }
    });
    saveExecutorQueue('interaction');
    saveInteractionTargets();
    emitExecutorUpdate(target.group);
    emitInteractionTargetsUpdate(target.group);
    res.json({ target: summarizeInteractionTarget(target), canceled });
});

app.delete('/api/executor/queues/reset', authMiddleware, adminOnly, (req, res) => {
    const deleted = {
        interaction: interactionQueue.length,
        publishing: publishingQueue.length,
        join: joinQueue.length
    };
    const affectedGroups = [...new Set([
        ...interactionQueue.map(job => job.group),
        ...publishingQueue.map(job => job.group),
        ...joinQueue.map(job => job.group)
    ].filter(Boolean))];

    interactionQueue = [];
    publishingQueue = [];
    joinQueue = [];
    saveExecutorQueue('interaction');
    saveExecutorQueue('publishing');
    saveExecutorQueue('join');
    affectedGroups.forEach(emitExecutorUpdate);

    res.json({
        ok: true,
        deleted,
        totalDeleted: deleted.interaction + deleted.publishing + deleted.join
    });
});

app.post('/api/executor/interaction', authMiddleware, (req, res) => {
    const url = String(req.body.url || '').trim();
    const comment = String(req.body.comment || '').trim();
    const targetPost = buildTargetPost(req.body.targetPost);
    if (!/^https?:\/\//i.test(url) || !comment || !targetPost || targetPost.anchors.length === 0) {
        return res.status(400).json({ error: 'URL, nội dung bài mục tiêu và comment hoàn chỉnh là bắt buộc.' });
    }
    const now = Date.now();
    const job = {
        id: `INT-${genId()}`, type: 'interaction', group: req.user.group,
        payload: {
            url: normalizeFbUrlForNative(url),
            comment: comment.slice(0, 4000),
            ...(targetPost ? { targetPost } : {})
        },
        status: 'QUEUED', attempts: 0, createdBy: req.user.username,
        createdAt: now, updatedAt: now
    };
    interactionQueue.push(job);
    saveExecutorQueue('interaction');
    emitExecutorUpdate(job.group);
    res.status(201).json(publicExecutorJob(job));
});

app.post('/api/executor/publishing', authMiddleware, (req, res) => {
    const groupUrl = String(req.body.groupUrl || '').trim();
    const content = String(req.body.content || '').trim();
    const scheduledAt = parseScheduledAt(req.body.scheduledAt);
    const images = Array.isArray(req.body.images)
        ? req.body.images.map(value => String(value).trim()).filter(Boolean)
        : [];
    if (!/^https?:\/\//i.test(groupUrl) || !content || images.some(url => !/^https?:\/\//i.test(url) && !url.startsWith('data:image'))) {
        return res.status(400).json({ error: 'Link group, nội dung hoàn chỉnh và link ảnh hợp lệ là bắt buộc.' });
    }
    if (Number.isNaN(scheduledAt)) {
        return res.status(400).json({ error: 'Thời gian hẹn đăng không hợp lệ.' });
    }
    const now = Date.now();
    const job = {
        id: `PUB-${genId()}`, type: 'publishing', group: req.user.group,
        payload: { groupUrl: normalizeFbUrlForNative(groupUrl), content, images },
        status: 'QUEUED', attempts: 0, createdBy: req.user.username,
        createdAt: now, updatedAt: now,
        ...(scheduledAt > now ? { scheduledAt } : {})
    };
    publishingQueue.push(job);
    saveExecutorQueue('publishing');
    emitExecutorUpdate(job.group);
    res.status(201).json(publicExecutorJob(job));
});

function createJoinJobsFromInputs(inputs, user, scheduledAt) {
    const now = Date.now();
    const { created, errors } = buildJoinJobsFromInputs(inputs, {
        group: user.group,
        createdBy: user.username,
        scheduledAt,
        now,
        genId,
        normalizeGroupUrl: normalizeFbUrlForNative
    });
    created.forEach(job => joinQueue.push(job));
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

app.post('/api/executor/:type/claim', authMiddleware, (req, res) => {
    const type = req.params.type;
    const queue = getExecutorQueue(type);
    const deviceId = String(req.body.deviceId || '').trim();
    if (!queue || !deviceId) return res.status(400).json({ error: 'Queue type hoặc deviceId không hợp lệ.' });
    reclaimExpiredExecutorJobs();

    const active = ['interaction', 'publishing', 'join']
        .flatMap(queueType => getExecutorQueue(queueType))
        .find(job => job.status === 'RUNNING' && job.claimedBy === req.user.username);
    if (active) {
        if (active.type !== type) return res.status(409).json({ error: `Luồng ${active.type} đang chạy.`, activeJob: publicExecutorJob(active) });
        if (active.deviceId !== deviceId) return res.status(409).json({ error: 'Tài khoản đang chạy trên thiết bị khác.' });
        return res.json({ job: publicExecutorJob(active), leaseToken: active.leaseToken, recovered: true });
    }

    const now = Date.now();
    const job = queue
        .filter(item => canClaimExecutorJob(item, type, req.user, now, deviceId))
        .sort((a, b) => priorityRank(b.priority) - priorityRank(a.priority) || (a.createdAt || 0) - (b.createdAt || 0))[0];
    if (!job) return res.status(204).end();
    job.status = 'RUNNING';
    job.claimedBy = req.user.username;
    job.deviceId = deviceId;
    job.claimedAt = now;
    job.heartbeatAt = now;
    job.updatedAt = now;
    job.attempts = (job.attempts || 0) + 1;
    job.leaseToken = crypto.randomUUID();
    saveExecutorQueue(type);
    emitExecutorUpdate(job.group);
    res.json({ job: publicExecutorJob(job), leaseToken: job.leaseToken });
});

function ownedExecutorJob(req, res) {
    const found = findExecutorJob(req.params.id);
    const leaseToken = String(req.body.leaseToken || '');
    if (!found) { res.status(404).json({ error: 'Job không tồn tại.' }); return null; }
    const { type, job } = found;
    if (job.status !== 'RUNNING' || job.claimedBy !== req.user.username || !leaseToken || leaseToken !== job.leaseToken) {
        res.status(409).json({ error: 'Lease của job không còn hợp lệ.' });
        return null;
    }
    return { type, job };
}

app.post('/api/executor/jobs/:id/heartbeat', authMiddleware, (req, res) => {
    const found = ownedExecutorJob(req, res); if (!found) return;
    found.job.heartbeatAt = Date.now();
    found.job.updatedAt = Date.now();
    saveExecutorQueue(found.type);
    res.json({ ok: true });
});

app.post('/api/executor/jobs/:id/checkpoint', authMiddleware, (req, res) => {
    const found = ownedExecutorJob(req, res); if (!found) return;
    found.job.irreversibleAt = Date.now();
    found.job.heartbeatAt = Date.now();
    found.job.updatedAt = Date.now();
    saveExecutorQueue(found.type);
    res.json({ ok: true });
});

app.post('/api/executor/jobs/:id/actions/:action', authMiddleware, (req, res) => {
    const found = ownedExecutorJob(req, res); if (!found) return;
    const action = String(req.params.action || '').toLowerCase();
    const status = String(req.body.status || '').toUpperCase();
    if (!['like', 'comment'].includes(action)) return res.status(400).json({ error: 'Action không hợp lệ.' });
    if (!['IN_PROGRESS', 'CONFIRMED', 'ALREADY_DONE', 'FAILED', 'UNCERTAIN', 'SKIPPED'].includes(status)) {
        return res.status(400).json({ error: 'Action status không hợp lệ.' });
    }
    const states = executorPolicy.normalizeActions(found.job.payload.actionStates || found.job.payload.actions);
    states[action] = { ...states[action], status, updatedAt: Date.now() };
    found.job.payload.actionStates = states;
    found.job.updatedAt = Date.now();
    saveExecutorQueue(found.type);
    res.json({ ok: true, action, status });
});

app.post('/api/executor/jobs/:id/complete', authMiddleware, (req, res) => {
    const found = findExecutorJob(req.params.id);
    const leaseToken = String(req.body.leaseToken || '');
    if (!found) return res.status(404).json({ error: 'Job không tồn tại.' });
    const { type, job } = found;

    // Idempotent: client retries after success when network dropped on the first ACK.
    if (job.status === 'SUCCEEDED') {
        const completedLease = job.result?.completedLeaseToken || '';
        if (leaseToken && completedLease && leaseToken === completedLease) {
            return res.json({ ok: true, idempotent: true });
        }
        if (job.claimedBy === req.user.username && job.deviceId && String(req.body.deviceId || '') === String(job.deviceId)) {
            return res.json({ ok: true, idempotent: true });
        }
        return res.status(409).json({ error: 'Job đã hoàn thành với lease khác.' });
    }

    if (job.status !== 'RUNNING' || job.claimedBy !== req.user.username || !leaseToken || leaseToken !== job.leaseToken) {
        return res.status(409).json({ error: 'Lease của job không còn hợp lệ.' });
    }

    job.status = 'SUCCEEDED';
    job.result = {
        ...(req.body.result || {}),
        completedLeaseToken: leaseToken,
        completedBy: req.user.username,
        completedDeviceId: job.deviceId || null
    };
    job.finishedAt = Date.now();
    job.updatedAt = Date.now();
    job.leaseToken = null;
    if (type === 'join') {
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
    saveExecutorQueue(type);
    const target = interactionTargets.find(item => item.id === (job.targetPostId || job.payload?.targetPostId));
    if (target) recordGroupInteraction(job, 'SUCCEEDED', req);
    if (target && refreshInteractionTargetStatus(target)) {
        saveInteractionTargets();
        emitInteractionTargetsUpdate(target.group);
    }
    emitExecutorUpdate(job.group);
    res.json({ ok: true });
});

app.post('/api/executor/jobs/:id/fail', authMiddleware, (req, res) => {
    const found = ownedExecutorJob(req, res); if (!found) return;
    const failedBy = found.job.claimedBy || req.user.username;
    const failedDeviceId = found.job.deviceId || '';
    const failure = executorPolicy.classifyFailure({
        code: normalizeTargetText(req.body.reasonCode || 'ACCESSIBILITY_FAILED', 80).toUpperCase(),
        message: normalizeTargetText(req.body.error || 'Executor báo thất bại.', 500),
        step: normalizeTargetText(req.body.step || '', 100),
        retryable: req.body.retryable !== false,
        failedBy,
        failedDeviceId,
        failedAt: Date.now()
    });
    found.job.status = 'FAILED';
    found.job.lastError = `${failure.code}: ${failure.message}`;
    found.job.result = { ...(found.job.result || {}), failure };
    found.job.finishedAt = Date.now();
    found.job.updatedAt = Date.now();
    found.job.leaseToken = null;
    const replacement = createReplacementJob(found.job, failedBy, failedDeviceId, failure);
    saveExecutorQueue(found.type);
    const target = interactionTargets.find(item => item.id === (found.job.targetPostId || found.job.payload?.targetPostId));
    if (target) recordGroupInteraction(found.job, 'FAILED', req);
    if (target && refreshInteractionTargetStatus(target)) {
        saveInteractionTargets();
        emitInteractionTargetsUpdate(target.group);
    }
    emitExecutorUpdate(found.job.group);
    res.json({ ok: true, replacementJobId: replacement?.id || null, reassigned: !!replacement });
});

app.post('/api/executor/jobs/:id/interrupted', authMiddleware, (req, res) => {
    const found = ownedExecutorJob(req, res); if (!found) return;
    const safeToRetry = req.body.safeToRetry === true && !found.job.irreversibleAt;
    found.job.status = safeToRetry ? 'QUEUED' : 'INTERRUPTED';
    found.job.lastError = String(req.body.error || 'Người dùng đã dừng executor.');
    found.job.updatedAt = Date.now();
    found.job.claimedBy = null;
    found.job.deviceId = null;
    found.job.leaseToken = null;
    saveExecutorQueue(found.type);
    const target = interactionTargets.find(item => item.id === (found.job.targetPostId || found.job.payload?.targetPostId));
    if (target && found.job.status === 'INTERRUPTED') recordGroupInteraction(found.job, 'INTERRUPTED', req);
    if (target && refreshInteractionTargetStatus(target)) {
        saveInteractionTargets();
        emitInteractionTargetsUpdate(target.group);
    }
    emitExecutorUpdate(found.job.group);
    res.json({ ok: true, status: found.job.status });
});

app.post('/api/executor/jobs/:id/resolve', authMiddleware, (req, res) => {
    const found = findExecutorJob(req.params.id);
    if (!found) return res.status(404).json({ error: 'Job không tồn tại.' });
    const { type, job } = found;
    if (req.user.role !== 'admin' && job.group !== req.user.group) {
        return res.status(403).json({ error: 'Forbidden' });
    }
    const priorClaimedBy = job.claimedBy || null;
    const now = Date.now();
    const result = applyJobResolve(job, {
        action: req.body.action,
        note: req.body.note,
        username: req.user.username,
        now
    });
    if (!result.ok) return res.status(result.statusCode).json({ error: result.error });

    const target = interactionTargets.find(item => item.id === (job.targetPostId || job.payload?.targetPostId));
    if (target && job.status === 'SUCCEEDED' && priorClaimedBy) {
        // Temporarily restore executor account so join/activity is not attributed to ops user
        job.claimedBy = priorClaimedBy;
        recordGroupInteraction(job, 'SUCCEEDED', req);
        delete job.claimedBy;
    } else if (target && job.status === 'FAILED') {
        // ops fail: no replacement; TARGET category so fail streak / NEEDS_REVIEW can apply
        job.result = {
            ...(job.result || {}),
            failure: {
                category: 'TARGET',
                code: 'OPS_RESOLVE_FAIL',
                message: job.lastError || 'Ops đánh fail sau INTERRUPTED'
            }
        };
        recordGroupInteraction(job, 'FAILED', req);
    }
    refreshAllInteractionTargets();
    saveExecutorQueue(type);
    saveInteractionTargets();
    emitExecutorUpdate(job.group);
    emitInteractionTargetsUpdate(job.group);
    res.json({ job: publicExecutorJob(job) });
});

setInterval(reclaimExpiredExecutorJobs, 30 * 1000);

/* ================== CONSTANTS & SETTINGS ================== */

app.get('/api/settings', authMiddleware, (req, res) => {
    let customSettings = { ...appSettings };
    if (req.user && req.user.maxGroupPostsPerDay !== undefined) {
        customSettings.maxGroupPostsPerDay = req.user.maxGroupPostsPerDay;
    }
    customSettings.llmEnabled = !!appSettings.llmEnabled;
    customSettings.llmConfigured = wealifyLlm.getLlmConfig().configured;
    res.json(customSettings);
});

app.post('/api/settings', authMiddleware, adminOnly, (req, res) => {
    const { maxGroupPostsPerDay, llmEnabled } = req.body;
    let changed = false;
    if (typeof maxGroupPostsPerDay === 'number' && maxGroupPostsPerDay >= 1) {
        appSettings.maxGroupPostsPerDay = maxGroupPostsPerDay;
        changed = true;
    }
    if (typeof llmEnabled === 'boolean') {
        appSettings.llmEnabled = llmEnabled;
        changed = true;
    }
    if (changed) saveState(SETTINGS_STORE, appSettings);
    res.json({
        ...appSettings,
        llmEnabled: !!appSettings.llmEnabled,
        llmConfigured: wealifyLlm.getLlmConfig().configured
    });
});

function requireLlmEnabled(req, res) {
    if (!appSettings.llmEnabled) {
        res.status(403).json({ error: 'LLM đang tắt trên server.' });
        return false;
    }
    return true;
}

function beginLlmSse(res) {
    res.status(200);
    res.setHeader('Content-Type', 'text/event-stream; charset=utf-8');
    res.setHeader('Cache-Control', 'no-cache, no-transform');
    res.setHeader('Connection', 'keep-alive');
    res.setHeader('X-Accel-Buffering', 'no');
    if (typeof res.flushHeaders === 'function') res.flushHeaders();
}

function writeLlmSse(res, payload) {
    res.write(`data: ${JSON.stringify(payload)}\n\n`);
    if (typeof res.flush === 'function') res.flush();
}

async function pipeLlmStream(res, iterator) {
    beginLlmSse(res);
    try {
        for await (const event of iterator) {
            writeLlmSse(res, event);
        }
    } catch (e) {
        writeLlmSse(res, { type: 'error', error: e.message || 'LLM stream thất bại.' });
    } finally {
        writeLlmSse(res, { type: 'close' });
        res.end();
    }
}

app.post('/api/llm/generate-comments', authMiddleware, async (req, res) => {
    if (!requireLlmEnabled(req, res)) return;
    try {
        const comments = await wealifyLlm.generateComments({
            postText: req.body.postText,
            count: req.body.count,
            userPrompt: req.body.userPrompt || req.body.prompt || ''
        });
        res.json({ comments });
    } catch (e) {
        res.status(e.statusCode || 500).json({ error: e.message || 'Sinh comment thất bại.' });
    }
});

app.post('/api/llm/generate-comments/stream', authMiddleware, async (req, res) => {
    if (!requireLlmEnabled(req, res)) return;
    await pipeLlmStream(res, wealifyLlm.streamGenerateComments({
        postText: req.body.postText,
        count: req.body.count,
        userPrompt: req.body.userPrompt || req.body.prompt || ''
    }));
});

app.post('/api/llm/generate-post', authMiddleware, async (req, res) => {
    if (!requireLlmEnabled(req, res)) return;
    try {
        const result = await wealifyLlm.generatePostContent({
            draft: req.body.draft,
            userPrompt: req.body.userPrompt || req.body.prompt || ''
        });
        res.json(result);
    } catch (e) {
        res.status(e.statusCode || 500).json({ error: e.message || 'Sinh bài thất bại.' });
    }
});

app.post('/api/llm/generate-post/stream', authMiddleware, async (req, res) => {
    if (!requireLlmEnabled(req, res)) return;
    await pipeLlmStream(res, wealifyLlm.streamGeneratePostContent({
        draft: req.body.draft,
        userPrompt: req.body.userPrompt || req.body.prompt || ''
    }));
});

// --- Splash Screen ---
app.get('/api/splash', (req, res) => {
    const splash = appSettings.splash || { imageUrl: '', text: 'Chào mừng bạn đến với FreeHand Fb', durationMs: 3000 };
    res.json(splash);
});

app.post('/api/splash', authMiddleware, adminOnly, (req, res) => {
    const { imageBase64, text, durationMs } = req.body;
    
    let splash = appSettings.splash || { imageUrl: '', text: 'Chào mừng bạn đến với FreeHand Fb', durationMs: 3000 };
    
    if (text !== undefined) splash.text = text;
    if (durationMs !== undefined) splash.durationMs = parseInt(durationMs) || 3000;
    
    if (imageBase64 && imageBase64.startsWith('data:image/')) {
        const matches = imageBase64.match(/^data:image\/([a-zA-Z0-9]+);base64,(.+)$/);
        if (matches && matches.length === 3) {
            const ext = matches[1] === 'jpeg' ? 'jpg' : matches[1];
            const buffer = Buffer.from(matches[2], 'base64');
            const filename = `splash_${Date.now()}.${ext}`;
            const filepath = path.join(__dirname, 'public', 'uploads', filename);
            fs.writeFileSync(filepath, buffer);
            
            splash.imageUrl = `${req.protocol}://${req.get('host')}/uploads/${filename}`;
        }
    }
    
    appSettings.splash = splash;
    saveState(SETTINGS_STORE, appSettings);
    res.json({ success: true, splash });
});

/* ================== USER MANAGEMENT (admin only) ================== */

app.get('/api/users', authMiddleware, adminOnly, (req, res) => {
    res.json(users.map(u => ({ id: u.id, username: u.username, group: u.group, role: u.role, points: u.points, phone: u.phone, zaloLink: u.zaloLink, facebookName: u.facebookName || '', isLocked: !!u.isLocked, isDebug: !!u.isDebug, settings: u.settings || {}, history: u.history || [], maxGroupPostsPerDay: u.maxGroupPostsPerDay !== undefined ? u.maxGroupPostsPerDay : 1 })));
});

app.post('/api/users', authMiddleware, adminOnly, (req, res) => {
    const { username, password, group, role, phone, zaloLink, facebookName } = req.body;
    if (!username || !password || !group) return res.status(400).json({ error: 'username, password, group required' });
    if (users.find(u => u.username === username)) return res.status(409).json({ error: 'Username already exists' });

    const user = { id: genId(), username, password: hashPw(password), group, role: role || 'user', phone: phone || '', zaloLink: zaloLink || '', facebookName: facebookName || '', isLocked: false, maxGroupPostsPerDay: req.body.maxGroupPostsPerDay !== undefined ? req.body.maxGroupPostsPerDay : 1 };
    users.push(user);
    saveState(USERS_STORE, users);
    res.json({ id: user.id, username: user.username, group: user.group, role: user.role, phone: user.phone, zaloLink: user.zaloLink, facebookName: user.facebookName, isLocked: false, maxGroupPostsPerDay: user.maxGroupPostsPerDay });
});

app.put('/api/users/:id', authMiddleware, adminOnly, (req, res) => {
    const user = users.find(u => u.id === req.params.id);
    if (!user) return res.status(404).json({ error: 'User not found' });

    const { username, password, group, role, points, deviceId, webDeviceId, phone, zaloLink, facebookName, isLocked, isDebug, settings } = req.body;
    const changes = [];
    if (deviceId === null || deviceId === "") user.deviceId = null;
    if (webDeviceId === null || webDeviceId === "") user.webDeviceId = null;
    if (isLocked !== undefined) user.isLocked = isLocked;
    if (req.body.maxGroupPostsPerDay !== undefined) {
        if (user.maxGroupPostsPerDay !== req.body.maxGroupPostsPerDay) changes.push(`Max Posts/Day: ${user.maxGroupPostsPerDay || 1} -> ${req.body.maxGroupPostsPerDay}`);
        user.maxGroupPostsPerDay = req.body.maxGroupPostsPerDay;
    }
    if (isDebug !== undefined) {
        if (!!user.isDebug !== !!isDebug) changes.push(`Debug Mode: ${!!user.isDebug ? 'BẬT' : 'TẮT'} -> ${isDebug ? 'BẬT' : 'TẮT'}`);
        user.isDebug = isDebug;
    }
    if (settings !== undefined) {
        user.settings = { ...(user.settings || {}), ...settings };
        if (!changes.includes("Cập nhật Cloud Settings")) changes.push("Cập nhật Cloud Settings");
    }
    if (username && username !== user.username) {
        if (users.find(u => u.username === username)) return res.status(409).json({ error: 'Username already exists' });
        const oldUsername = user.username;
        user.username = username;
        // Update tokens with new username
        Object.values(tokens).forEach(t => { if (t.userId === user.id) t.username = username; });
        saveState(TOKENS_STORE, tokens);
        
        // Cascade update addedBy in posts
        let postsChanged = false;
        posts.forEach(p => {
            if (p.addedBy === oldUsername) { p.addedBy = username; postsChanged = true; }
        });
        if (postsChanged) saveState(POSTS_STORE, posts);
    }
    if (password) user.password = hashPw(password);

    // Audit Trailing logic for Phone/Zalo/Points updates
    if (group && group !== user.group) { changes.push(`Group: ${user.group||''} -> ${group}`); user.group = group; }
    if (role && role !== user.role) { changes.push(`Role: ${user.role||''} -> ${role}`); user.role = role; }
    if (phone !== undefined && phone !== user.phone) { changes.push(`SĐT: ${user.phone||'[Trống]'} -> ${phone}`); user.phone = phone; }
    if (zaloLink !== undefined && zaloLink !== user.zaloLink) { changes.push(`Zalo: ${user.zaloLink||'[Trống]'} -> ${zaloLink}`); user.zaloLink = zaloLink; }
    if (facebookName !== undefined && facebookName !== user.facebookName) { changes.push(`Tên FB: ${user.facebookName||'[Trống]'} -> ${facebookName}`); user.facebookName = facebookName; }
    if (points !== undefined) {
        const parsedPoints = parseInt(points, 10) || 0;
        if (parsedPoints !== user.points) { changes.push(`Điểm: ${user.points||0} -> ${parsedPoints}`); user.points = parsedPoints; }
    }
    
    if (changes.length > 0) {
        if (!user.history) user.history = [];
        user.history.unshift({ timestamp: Date.now(), by: req.user.username, desc: changes.join(' | ') });
        // Keep only last 10 logs
        if (user.history.length > 10) user.history.pop();
    }

    saveState(USERS_STORE, users);
    res.json({ id: user.id, username: user.username, group: user.group, role: user.role, points: user.points, phone: user.phone, zaloLink: user.zaloLink, facebookName: user.facebookName || '', isLocked: !!user.isLocked, isDebug: !!user.isDebug, settings: user.settings || {}, history: user.history });
});

app.delete('/api/users/:id', authMiddleware, adminOnly, (req, res) => {
    const idx = users.findIndex(u => u.id === req.params.id);
    if (idx === -1) return res.status(404).json({ error: 'User not found' });
    // Remove associated tokens
    const user = users[idx];
    Object.keys(tokens).forEach(t => { if (tokens[t].userId === user.id) delete tokens[t]; });
    users.splice(idx, 1);
    saveState(USERS_STORE, users);
    saveState(TOKENS_STORE, tokens);
    res.json({ ok: true });
});

// Get group list (admin)
app.get('/api/groups', authMiddleware, adminOnly, (req, res) => {
    const groups = [...new Set(users.map(u => u.group))];
    res.json(groups);
});

/* ================== SUGGESTED GROUPS API ================== */

app.get('/api/suggested-groups', authMiddleware, (req, res) => {
    if (req.user.role === 'admin') {
        const approved = suggestedGroups.filter(g => g.status === 'approved');
        const pending = suggestedGroups.filter(g => g.status === 'pending');
        return res.json({ approved, pending });
    }
    // Users only see approved
    res.json({ approved: suggestedGroups.filter(g => g.status === 'approved') });
});

app.post('/api/suggested-groups', authMiddleware, (req, res) => {
    const { name, url, memberCount } = req.body;
    if (!name || !url) return res.status(400).json({ error: 'Name and URL are required' });
    const status = req.user.role === 'admin' ? 'approved' : 'pending';
    const g = { id: genId(), name, url, memberCount: memberCount || '', status, addedBy: req.user.username, createdAt: Date.now() };
    suggestedGroups.push(g);
    saveState(SUGGESTED_GROUPS_STORE, suggestedGroups);
    res.json(g);
});

app.put('/api/suggested-groups/:id', authMiddleware, adminOnly, (req, res) => {
    const g = suggestedGroups.find(x => x.id === req.params.id);
    if (!g) return res.status(404).json({ error: 'Not found' });
    const { name, url, memberCount, status } = req.body;
    if (name) g.name = name;
    if (url) g.url = url;
    if (memberCount !== undefined) g.memberCount = memberCount;
    if (status && ['approved', 'pending'].includes(status)) g.status = status;
    saveState(SUGGESTED_GROUPS_STORE, suggestedGroups);
    res.json(g);
});

app.delete('/api/suggested-groups/:id', authMiddleware, adminOnly, (req, res) => {
    const idx = suggestedGroups.findIndex(x => x.id === req.params.id);
    if (idx === -1) return res.status(404).json({ error: 'Not found' });
    suggestedGroups.splice(idx, 1);
    saveState(SUGGESTED_GROUPS_STORE, suggestedGroups);
    res.json({ ok: true });
});

// Get group leaderboard
app.get('/api/group/members', authMiddleware, (req, res) => {
    const memberScores = users.filter(u => u.group === req.user.group)
        .map(u => ({ username: u.username, points: u.points || 0 }))
        .sort((a, b) => b.points - a.points);
    res.json(memberScores);
});

/* ================== POSTS API (group-scoped) ================== */

// Helper: count posts added today by user
function countTodayPosts(username) {
    const today = new Date();
    today.setHours(0, 0, 0, 0);
    const startOfDay = today.getTime();
    return posts.filter(p => p.addedBy === username && p.addedAt >= startOfDay && !p.isPublishingGroup).length;
}

function isCommentablePost(url) {
    if (!url) return false;
    if (url.includes("/groups/")) {
        return url.includes("/posts/") || 
               url.includes("/permalink/") || 
               url.includes("/permalink.php") || 
               url.includes("multi_permalinks") || 
               url.includes("story_fbid");
    }
    return true;
}

function normalizeFbUrlForNative(url) {
    return url.trim()
        .replace(/www\.facebook\.com/gi, 'm.facebook.com')
        .replace(/mbasic\.facebook\.com/gi, 'm.facebook.com')
        .replace(/web\.facebook\.com/gi, 'm.facebook.com');
}

app.get('/api/posts', authMiddleware, (req, res) => {
    // Admin can filter by group or see all
    if (req.user.role === 'admin') {
        const g = req.query.group;
        const result = g ? posts.filter(p => p.group === g) : posts;
        return res.json(result);
    }
    const filtered = posts.filter(p => p.group === req.user.group && !p.isPublishingGroup && isCommentablePost(p.url));
    res.json(filtered);
});

app.post('/api/posts', authMiddleware, (req, res) => {
    const { url, title } = req.body;
    if (!url) return res.status(400).json({ error: 'url is required' });
    const normalizedUrl = normalizeFbUrlForNative(url);

    // Rate limit: 5 posts/day per non-admin user
    if (req.user.role !== 'admin') {
        const todayCount = countTodayPosts(req.user.username);
        if (todayCount >= 5) return res.status(429).json({ error: 'Bạn chỉ được thêm tối đa 5 bài/ngày' });
    }

    // Phase 12 Compliance: Max 1 post per Facebook Group per day per user
    let fbGroupIdMatch = normalizedUrl.match(/\/groups\/([0-9a-zA-Z._-]+)/);
    if (fbGroupIdMatch) {
        const fbGroupId = fbGroupIdMatch[1];
        const today = new Date();
        today.setHours(0, 0, 0, 0);
        const startOfDay = today.getTime();
        
        const countToday = posts.filter(p => 
            p.addedBy === req.user.username && 
            p.addedAt >= startOfDay && 
            p.url.includes(`/groups/${fbGroupId}`) &&
            !p.isPublishingGroup
        ).length;
        
        const limit = req.user.maxGroupPostsPerDay !== undefined ? req.user.maxGroupPostsPerDay : (appSettings.maxGroupPostsPerDay || 1);
        if (countToday >= limit) {
            return res.status(429).json({ error: `Bạn đã đăng ${countToday} bài vào nhóm này hôm nay. Giới hạn là ${limit} bài/ngày!` });
        }
    }

    if (posts.find(p => p.url === normalizedUrl && p.group === req.user.group)) {
        return res.status(409).json({ error: 'Bài đã tồn tại trong nhóm' });
    }

    const post = {
        id: genId(), url: normalizedUrl, title: title?.trim() || null,
        status: 'PENDING', interactedBy: [], verifications: [], group: req.user.group, ownerName: req.user.username, addedBy: req.user.username,
        addedAt: Date.now(), interactedAt: null,
        isPublishingGroup: !!req.body.isPublishingGroup
    };
    posts.push(post);
    saveState(POSTS_STORE, posts);

    // Realtime broadcast to all group members
    io.to(`group:${req.user.group}`).emit('posts_updated', { posts: posts.filter(p => p.group === req.user.group) });

    res.json(post);
});

app.post('/api/posts/bulk', authMiddleware, (req, res) => {
    const { items } = req.body;
    if (!Array.isArray(items)) return res.status(400).json({ error: 'items array required' });

    const today = new Date();
    today.setHours(0, 0, 0, 0);
    const startOfDay = today.getTime();

    let added = 0;
    items.forEach(({ url, title }) => {
        const u = url?.trim() ? normalizeFbUrlForNative(url.trim()) : '';
        if (u && !posts.find(p => p.url === u && p.group === req.user.group)) {
            // Phase 12 Compliance: Max 1 post per Facebook Group per day per user
            let fbGroupIdMatch = u.match(/\/groups\/([0-9a-zA-Z._-]+)/);
            let skip = false;
            if (fbGroupIdMatch) {
                const fbGroupId = fbGroupIdMatch[1];
                const alreadyPostedToGroupToday = posts.some(p => 
                    p.addedBy === req.user.username && 
                    p.addedAt >= startOfDay && 
                    p.url.includes(`/groups/${fbGroupId}`) &&
                    !p.isPublishingGroup
                );
                if (alreadyPostedToGroupToday) skip = true;
            }
            
            if (!skip && (req.user.role === 'admin' || countTodayPosts(req.user.username) < 5)) {
                posts.push({
                    id: genId(), url: u, title: title?.trim() || null,
                    status: 'PENDING', interactedBy: [], verifications: [], group: req.user.group, ownerName: req.user.username, addedBy: req.user.username, addedAt: Date.now(), interactedAt: null
                });
                added++;
            }
        }
    });
    saveState(POSTS_STORE, posts);
    
    const groupPosts = posts.filter(p => p.group === req.user.group);
    // Realtime broadcast to all group members
    io.to(`group:${req.user.group}`).emit('posts_updated', { posts: groupPosts });

    res.json({ added, total: groupPosts.length });
});

app.post('/api/posts/:id/verify_request', authMiddleware, (req, res) => {
    const post = posts.find(p => p.id === req.params.id && p.group === req.user.group);
    if (!post) return res.status(404).json({ error: 'Post not found' });
    
    if (!post.verifications) post.verifications = [];
    if (post.verifications.some(v => v.username === req.user.username)) return res.json(post); // already requested

    post.verifications.push({
        username: req.user.username,
        status: 'PENDING',
        requestedAt: Date.now()
    });
    
    saveState(POSTS_STORE, posts);

    // Realtime broadcast: post was interacted
    io.to(`group:${req.user.group}`).emit('posts_updated', { posts: posts.filter(p => p.group === req.user.group) });

    res.json(post);
});

app.post('/api/posts/:id/done', authMiddleware, (req, res) => {
    const post = posts.find(p => p.id === req.params.id && p.group === req.user.group);
    if (!post) return res.status(404).json({ error: 'Post not found' });
    
    post.status = 'DONE';
    post.interactedAt = Date.now();
    
    saveState(POSTS_STORE, posts);
    
    // Realtime broadcast: post marked done
    io.to(`group:${post.group}`).emit('posts_updated', { posts: posts.filter(p => p.group === post.group) });
    
    res.json(post);
});

// API for Bot to get queue of posts that need verification
app.get('/api/verify/queue', authMiddleware, adminOnly, (req, res) => {
    // A post needs verification if it has any PENDING verifications
    const queue = posts.filter(p => p.verifications && p.verifications.some(v => v.status === 'PENDING'));
    res.json(queue);
});

// API for Bot to submit verification results for a post
app.post('/api/verify/submit', authMiddleware, adminOnly, (req, res) => {
    const { postId, fbNames } = req.body; // fbNames: array of string
    if (!postId || !Array.isArray(fbNames)) return res.status(400).json({ error: 'postId and fbNames required' });

    const post = posts.find(p => p.id === postId);
    if (!post) return res.status(404).json({ error: 'Post not found' });

    if (!post.verifications) post.verifications = [];
    
    const pendingVerifications = post.verifications.filter(v => v.status === 'PENDING');
    
    pendingVerifications.forEach(v => {
        const user = users.find(u => u.username === v.username);
        if (!user) return;

        // Check if the user's declared facebookName is in the list scraped by the bot
        const isVerified = user.facebookName && fbNames.some(name => name.toLowerCase() === user.facebookName.toLowerCase());
        
        if (isVerified) {
            v.status = 'VERIFIED';
            // Reward points
            user.points = (user.points || 0) + 1;
            
            // Deduct point from post owner
            if (post.addedBy && post.addedBy !== user.username) {
                const owner = users.find(u => u.username === post.addedBy);
                if (owner) {
                    owner.points = (owner.points || 0) - 1;
                    notifications.push({
                        id: genId(),
                        username: owner.username,
                        message: `Bài viết của bạn đã được tương tác thật bởi ${user.facebookName || user.username}. Bạn bị trừ 1 điểm.`,
                        read: false,
                        createdAt: Date.now()
                    });
                }
            }
            
            // Notify interactor
            notifications.push({
                id: genId(),
                username: user.username,
                message: `Hệ thống đã xác nhận bạn tương tác thật. Bạn được +1 điểm.`,
                read: false,
                createdAt: Date.now()
            });

        } else {
            v.status = 'REJECTED';
            // Penalize for cheating
            user.points = (user.points || 0) - 2; 
            
            notifications.push({
                id: genId(),
                username: user.username,
                message: `Phát hiện gian lận! Hệ thống kiểm duyệt không tìm thấy Tên Facebook (${user.facebookName}) của bạn trong bình luận. Bạn bị TRỪ 2 điểm.`,
                read: false,
                createdAt: Date.now()
            });
        }
    });

    saveState(USERS_STORE, users);
    saveState(NOTIFICATIONS_STORE, notifications);
    saveState(POSTS_STORE, posts);
    
    io.to(`group:${post.group}`).emit('posts_updated', { posts: posts.filter(p => p.group === post.group) });
    
    res.json({ ok: true, verifiedCount: pendingVerifications.length });
});

app.delete('/api/posts/:id', authMiddleware, (req, res) => {
    const idx = req.user.role === 'admin'
        ? posts.findIndex(p => p.id === req.params.id)
        : posts.findIndex(p => p.id === req.params.id && p.group === req.user.group);
    if (idx === -1) return res.status(404).json({ error: 'Post not found' });
    const deletedPost = posts[idx];
    posts.splice(idx, 1);
    saveState(POSTS_STORE, posts);

    // Realtime broadcast: post deleted
    io.to(`group:${deletedPost.group}`).emit('posts_updated', { posts: posts.filter(p => p.group === deletedPost.group) });

    res.json({ ok: true });
});

app.delete('/api/posts/done/clear', authMiddleware, (req, res) => {
    if (req.user.role === 'admin') {
        const g = req.query.group;
        posts = g
            ? posts.filter(p => !(p.group === g && p.status === 'DONE'))
            : posts.filter(p => p.status !== 'DONE');
    } else {
        posts = posts.filter(p => !(p.group === req.user.group && p.status === 'DONE'));
    }
    saveState(POSTS_STORE, posts);

    // Realtime broadcast: done posts cleared
    if (req.user.role === 'admin') {
        // Broadcast to all groups
        const allGroups = [...new Set(posts.map(p => p.group))];
        allGroups.forEach(g => io.to(`group:${g}`).emit('posts_updated', { posts: posts.filter(p => p.group === g) }));
    } else {
        io.to(`group:${req.user.group}`).emit('posts_updated', { posts: posts.filter(p => p.group === req.user.group) });
    }

    res.json({ remaining: posts.length });
});

/* ================== TEMPLATES API (group-scoped + global) ================== */

app.get('/api/templates', authMiddleware, (req, res) => {
    const groupTemplates = templates[req.user.group] || [];
    const globalTemplates = config.defaultComments || [];
    // Merge so global templates always show up for everyone
    const merged = [...new Set([...globalTemplates, ...groupTemplates])];
    res.json(merged);
});

app.post('/api/templates', authMiddleware, (req, res) => {
    const { text } = req.body;
    if (!text?.trim()) return res.status(400).json({ error: 'text required' });
    const g = req.user.group;
    if (!templates[g]) templates[g] = [];
    const t = text.trim();
    if (!templates[g].includes(t)) templates[g].push(t);
    saveState(TEMPLATES_STORE, templates);
    res.json(templates[g]);
});

app.delete('/api/templates', authMiddleware, (req, res) => {
    const { text } = req.body;
    const g = req.user.group;
    if (templates[g]) templates[g] = templates[g].filter(t => t !== text);
    saveState(TEMPLATES_STORE, templates);
    res.json(templates[g] || []);
});

/* ================== ME & NOTIFICATIONS ================== */

app.get('/api/me', authMiddleware, (req, res) => {
    const user = users.find(u => u.username === req.user.username);
    if (!user) return res.status(404).json({ error: 'User not found' });

    res.json({
        id: user.id, username: user.username, group: user.group, role: user.role,
        points: user.points, phone: user.phone || '', zaloLink: user.zaloLink || '', facebookName: user.facebookName || '',
        isDebug: !!user.isDebug,
        settings: user.settings || {}
    });
});

app.put('/api/me', authMiddleware, (req, res) => {
    const user = users.find(u => u.username === req.user.username);
    if (!user) return res.status(404).json({ error: 'User not found' });

    const { phone, zaloLink, facebookName, settings } = req.body;
    const changes = [];
    
    if (phone !== undefined && phone !== user.phone) { changes.push(`SĐT: ${user.phone||'[Trống]'} -> ${phone}`); user.phone = phone; }
    if (zaloLink !== undefined && zaloLink !== user.zaloLink) { changes.push(`Zalo: ${user.zaloLink||'[Trống]'} -> ${zaloLink}`); user.zaloLink = zaloLink; }
    if (facebookName !== undefined && facebookName !== user.facebookName) { changes.push(`Tên FB: ${user.facebookName||'[Trống]'} -> ${facebookName}`); user.facebookName = facebookName; }
    if (settings !== undefined) {
        user.settings = { ...(user.settings || {}), ...settings };
        if (!changes.includes("Cập nhật Cloud Settings")) changes.push("Cập nhật Cloud Settings");
    }
    
    if (changes.length > 0) {
        if (!user.history) user.history = [];
        user.history.unshift({ timestamp: Date.now(), by: req.user.username + " (Tự sửa)", desc: changes.join(' | ') });
        if (user.history.length > 10) user.history.pop();
        saveState(USERS_STORE, users);
    }
    
    res.json({
        id: user.id, username: user.username, group: user.group, role: user.role,
        points: user.points, phone: user.phone || '', zaloLink: user.zaloLink || '', facebookName: user.facebookName || '',
        settings: user.settings || {}
    });
});

app.get('/api/notifications', authMiddleware, (req, res) => {
    const userNotifs = notifications.filter(n => n.username === req.user.username && !n.read);
    res.json(userNotifs);
});

app.post('/api/notifications/read', authMiddleware, (req, res) => {
    notifications.forEach(n => {
        if (n.username === req.user.username) n.read = true;
    });
    saveState(NOTIFICATIONS_STORE, notifications);
    res.json({ ok: true });
});

/* ================== APP CONFIG & ARTICLES (admin/auto-update) ================== */

// Global comments management
app.get('/api/config/comments', authMiddleware, adminOnly, (req, res) => {
    res.json(config.defaultComments || []);
});

app.put('/api/config/comments', authMiddleware, adminOnly, (req, res) => {
    config.defaultComments = req.body || [];
    saveState(CONFIG_STORE, config);
    res.json(config.defaultComments);
});

// Articles (Bài viết mẫu) API
app.get('/api/articles', authMiddleware, (req, res) => {
    if (req.user.role === 'admin') return res.json(articles);
    const visible = articles.filter(a => 
        (a.scope === 'personal' && a.addedBy === req.user.username) || 
        (a.scope !== 'personal' && a.status === 'approved') || 
        // Migrate legacy ones without scope/status transparently
        (!a.scope && !a.status)
    );
    res.json(visible);
});

app.post('/api/articles', authMiddleware, (req, res) => {
    const { category, title, content, images, base64Images, scope } = req.body;
    let finalImages = images || [];
    if (base64Images && Array.isArray(base64Images)) {
        base64Images.forEach(b64 => { const u = saveBase64Image(b64); if (u) finalImages.push("https://free.xommuaban.com" + u); });
    }
    const finalScope = scope === 'personal' ? 'personal' : 'global';
    const status = (finalScope === 'personal' || req.user.role === 'admin') ? 'approved' : 'pending';
    const article = { 
        id: genId(), category, title, content, images: finalImages, 
        createdAt: Date.now(), addedBy: req.user.username, scope: finalScope, status 
    };
    articles.push(article);
    saveState(ARTICLES_STORE, articles);
    res.json(article);
});

app.put('/api/articles/:id', authMiddleware, adminOnly, (req, res) => {
    const idx = articles.findIndex(a => a.id === req.params.id);
    if (idx === -1) return res.status(404).json({ error: 'Not found' });
    const { category, title, content, images, base64Images, status, scope } = req.body;
    let finalImages = images || articles[idx].images || [];
    if (base64Images && Array.isArray(base64Images)) {
        base64Images.forEach(b64 => { const u = saveBase64Image(b64); if (u) finalImages.push("https://free.xommuaban.com" + u); });
    }
    articles[idx] = { 
        ...articles[idx], 
        category: category || articles[idx].category, 
        title: title || articles[idx].title, 
        content: content || articles[idx].content, 
        images: finalImages, 
        status: status || articles[idx].status,
        scope: scope || articles[idx].scope,
        id: req.params.id 
    };
    saveState(ARTICLES_STORE, articles);
    res.json(articles[idx]);
});

app.delete('/api/articles/:id', authMiddleware, (req, res) => {
    const idx = articles.findIndex(a => a.id === req.params.id);
    if (idx === -1) return res.status(404).json({ error: 'Not found' });
    if (req.user.role !== 'admin' && !(articles[idx].addedBy === req.user.username && articles[idx].scope === 'personal')) {
        return res.status(403).json({ error: 'Admin only' });
    }
    articles.splice(idx, 1);
    saveState(ARTICLES_STORE, articles);
    res.json({ ok: true });
});

// Auto Sync endpoint
app.get('/api/sync', authMiddleware, (req, res) => {
    const after = parseInt(req.query.after || '0', 10);
    const myGroupPosts = posts.filter(p => p.group === req.user.group);
    
    // Check if there are any new or updated posts
    const hasChanges = myGroupPosts.some(p => (typeof p.interactedAt === 'number' ? p.interactedAt > after : false) || p.addedAt > after);
    if (!hasChanges) {
        return res.json({ changed: false, serverTime: Date.now() });
    }
    res.json({ changed: true, posts: myGroupPosts, serverTime: Date.now() });
});

// Public: app checks this on launch (no auth needed)
app.get('/api/app-version', (req, res) => {
    res.json(config);
});

// Admin: set latest version + APK URL
app.put('/api/app-version', authMiddleware, adminOnly, (req, res) => {
    const { appVersion, apkUrl, changelog } = req.body;
    if (appVersion) config.appVersion = appVersion;
    if (apkUrl !== undefined) config.apkUrl = apkUrl;
    if (changelog !== undefined) config.changelog = changelog;
    saveState(CONFIG_STORE, config);
    res.json(config);
});

// Logs API
app.post('/api/logs/apk', authMiddleware, (req, res) => {
    try {
        const log = req.body.log;
        const username = req.user.username; // ignore body username for pathing
        if (log) {
            const time = new Date().toLocaleString('vi-VN', { timeZone: 'Asia/Ho_Chi_Minh' });
            const logMsg = `[${time}] ${log}\n`;
            if (username) {
                // Sanitize username to prevent directory traversal
                const safeUsername = String(username).replace(/[^a-zA-Z0-9@._-]/g, '_');
                const userLogFile = path.join(LOGS_DIR, `${safeUsername}_logs.txt`);
                fs.appendFileSync(userLogFile, logMsg);
            } else {
                fs.appendFileSync(APK_LOGS_FILE, `\n\n[=== ${time} ===]\n${log}`);
            }
        }
    } catch(e){}
    res.json({ok: true});
});

app.get('/api/logs/:type', authMiddleware, (req, res) => {
    const type = req.params.type;
    let file = '';
    
    if (type === 'server-err') {
        file = '/root/.pm2/logs/C2-Dashboard-error.log';
    } else if (type === 'server-out') {
        file = '/root/.pm2/logs/C2-Dashboard-out.log';
    } else if (type === 'apk') {
        file = APK_LOGS_FILE;
    } else if (type.startsWith('user-')) {
        const username = type.slice(5).replace(/[^a-zA-Z0-9@._-]/g, '_');
        file = path.join(LOGS_DIR, `${username}_logs.txt`);
    } else {
        return res.json({ log: 'Invalid type' });
    }

    try {
        if (!fs.existsSync(file)) return res.json({ log: 'No logs yet.' });
        const content = fs.readFileSync(file, 'utf-8');
        // Get last 500 lines roughly
        const lines = content.split('\n').filter(Boolean);
        const lastLines = lines.slice(-500).join('\n');
        res.json({ log: lastLines });
    } catch(e) {
        res.json({ log: 'Error reading log: ' + e.message });
    }
});

/* ================== START ================== */

function genId() { return crypto.randomBytes(8).toString('hex'); }

const UPLOADS_DIR = path.join(__dirname, 'public', 'uploads');
if (!fs.existsSync(UPLOADS_DIR)) fs.mkdirSync(UPLOADS_DIR, { recursive: true });

function saveBase64Image(dataStr) {
    try {
        const parts = dataStr.split(',');
        if (parts.length !== 2) return null;
        let ext = 'png';
        if (parts[0].includes('jpeg') || parts[0].includes('jpg')) ext = 'jpg';
        const buffer = Buffer.from(parts[1], 'base64');
        const fileName = `img_${Date.now()}_${crypto.randomBytes(4).toString('hex')}.${ext}`;
        fs.writeFileSync(path.join(UPLOADS_DIR, fileName), buffer);
        return `/uploads/${fileName}`; // Changed to relative server path
    } catch(e) { return null; }
}

/* ================== OTA SCRIPT ENGINE ================== */

const DEFAULT_ENGINE_DATA = {
        latest: "v1.3.0_OTA_VPS",
        jsCode: "// Rhino JS Engine Hot-Reload Script\n// Define overrides or helper logic here.\n",
        versions: {
            "v1.3.0_OTA_VPS": {
                wrong_screen: ["gửi bằng messenger", "gửi trong messenger", "chia sẻ lên tin", "share to story", "gửi cho", "tìm kiếm người", "search people"],
                block_dialog: ["bạn đang tạm thời bị chặn", "tài khoản của bạn bị hạn chế", "you can't post right now", "temporarily blocked", "restricted"],
                group_join: ["tham gia nhóm", "join group", "gia nhập nhóm"],
                questionnaire_submit: ["gửi", "đồng ý", "submit", "i agree"],
                dead_link: ["không khả dụng", "không tồn tại", "đã bị gỡ", "content isn't available", "content not found"],
                compose_button: ["bài viết mới...", "viết gì đó...", "bạn viết gì đi", "bạn đang nghĩ gì", "tạo bài viết", "thảo luận", "write something", "write a public", "what's on your mind", "create post", "share something"],
                post_button: ["đăng", "post"],
                comment_button: ["bình luận", "comment", "viết bình luận", "write a comment"],
                send_comment: ["gửi", "send", "đăng", "post", "tiếp", "next"],
                photo_button: ["ảnh/video", "photo/video", "thêm vào bài viết", "add to your post", "ảnh", "photo"],
                gallery_exclude: ["take", "camera", "thu gọn", "chọn nhiều", "collapse", "select multiple", "thư viện", "library", "pictures", "album", "video", "quay lại", "back", "navigate", "bài viết mới", "new post", "đóng", "close", "thoát", "hủy", "cancel", "khung", "chọn thư mục"],
                multi_select_button: ["chọn nhiều file", "chọn nhiều", "select multiple", "select multiple files"],
                gallery_next_button: ["next", "tiếp", "done", "xong", "tiếp tục", "hoàn tất"],
                gallery_click_delay: 3000,
                notification_ignore: ["đăng nhập", "thiết bị", "yêu cầu tham gia", "tham gia nhóm"],
                notification_approve: ["phê duyệt ảnh", "phê duyệt bài", "approved your photo", "approved your post"]
            }
        }
    };
let engineDataCache = DEFAULT_ENGINE_DATA;

function getEngineData() {
    return engineDataCache;
}

app.get('/api/engine/scripts', authMiddleware, (req, res) => {
    const data = getEngineData();
    res.json({
        latest: data.latest,
        available: Object.keys(data.versions)
    });
});

app.get('/api/engine/script', authMiddleware, (req, res) => {
    const data = getEngineData();
    let v = req.query.version || "latest";
    if (v === "latest") v = data.latest;
    
    const anchors = data.versions[v] || data.versions[data.latest] || {};
    res.json({ version: v, anchors, jsCode: data.jsCode || '' });
});

// Admin API to update the script
app.post('/api/engine/script', authMiddleware, adminOnly, (req, res) => {
    const { version, jsCode, anchors } = req.body;
    if (!version) return res.status(400).json({ error: "version is required" });
    
    const data = getEngineData();
    data.latest = version;
    if (anchors) data.versions[version] = anchors;
    else if (!data.versions[version]) data.versions[version] = data.versions[data.latest] || {};
    if (jsCode !== undefined) data.jsCode = String(jsCode);
    engineDataCache = data;
    saveState(ENGINE_STORE, data);
    
    res.json({ ok: true, version });
});

/* ================== SOCKET.IO REALTIME ================== */

io.on('connection', (socket) => {
    console.log(`[WS] Client connected: ${socket.id}`);

    // Client joins their group room for scoped broadcasts
    socket.on('join_group', (data) => {
        const { group, token: clientToken } = data;
        if (!clientToken || !tokens[clientToken]) {
            socket.emit('error', { message: 'Unauthorized' });
            return;
        }
        const userInfo = tokens[clientToken];
        socket.join(`group:${userInfo.group}`);
        socket.userData = userInfo;
        console.log(`[WS] ${userInfo.username} joined room group:${userInfo.group}`);
    });

    socket.on('disconnect', () => {
        console.log(`[WS] Client disconnected: ${socket.id}`);
    });
});

/* ================== START ================== */

const PORT = process.env.PORT || 3030;

async function loadRuntimeStateFromPostgres() {
    users = await dbStore.loadUsers();
    tokens = await dbStore.loadTokens();
    posts = await dbStore.loadPosts();
    templates = await dbStore.loadTemplates();
    notifications = await dbStore.loadNotifications();
    articles = await dbStore.loadArticles();
    suggestedGroups = await dbStore.loadSuggestedGroups();
    appSettings = await dbStore.loadSettings();
    config = await dbStore.loadConfig();
    groupIntelligence = await dbStore.loadGroupIntelligence();
    engineDataCache = await dbStore.loadEngine();
    if (Object.keys(engineDataCache.versions || {}).length === 0) {
        engineDataCache = DEFAULT_ENGINE_DATA;
        await dbStore.saveEngine(engineDataCache);
    }
    interactionQueue = await dbStore.loadJobs('interaction');
    publishingQueue = await dbStore.loadJobs('publishing');
    joinQueue = await dbStore.loadJobs('join');
    interactionTargets = await dbStore.loadInteractionTargets();
}

async function ensurePostgresSeedUsers() {
    let changed = false;
    if (!users.find(u => u.username === SYSTEM_ADMIN)) {
        users.push({
            id: genId(),
            username: SYSTEM_ADMIN,
            password: hashPw('16691'),
            group: 'default',
            role: 'admin',
            points: 20,
            createdAt: Date.now(),
            updatedAt: Date.now()
        });
        changed = true;
        console.warn('[DB] Seeded default admin — CHANGE PASSWORD immediately.');
    }
    if (!users.find(u => u.username === 'worker01')) {
        users.push({
            id: genId(),
            username: 'worker01',
            password: hashPw('123456'),
            group: 'default',
            role: 'user',
            points: 20,
            createdAt: Date.now(),
            updatedAt: Date.now()
        });
        changed = true;
    }
    if (changed) {
        await dbStore.replaceUsers(users);
        users = await dbStore.loadUsers();
    }
}

async function bootstrapPostgresStorage() {
    await dbStore.runMigrations();
    await loadRuntimeStateFromPostgres();
    await ensurePostgresSeedUsers();
    tokens = {};
    await dbStore.replaceTokens(tokens);
    postgresStorageReady = true;
    pendingPostgresSaves.clear();
    scheduleLogsCleanup();
    console.log('[DB] PostgreSQL runtime storage is active.');
}

bootstrapPostgresStorage()
    .then(() => {
        http.listen(PORT, '0.0.0.0', () => {
            console.log(`Comment Helper Server listening on port ${PORT}`);
            console.log(`Users: ${users.length}, Posts: ${posts.length}`);
        });
    })
    .catch(error => {
        console.error('[DB] Cannot start server because PostgreSQL bootstrap failed:', error);
        process.exit(1);
    });
