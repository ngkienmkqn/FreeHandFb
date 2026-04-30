const express = require('express');
const app = express();
const http = require('http').Server(app);
const io = require('socket.io')(http, { cors: { origin: "*" } });
const path = require('path');
const cors = require('cors');
const fs = require('fs');
const crypto = require('crypto');

app.use(cors());
app.use(express.json({ limit: '200mb' }));
app.use(express.urlencoded({ limit: '200mb', extended: true }));

// Serve dashboard at root
app.get('/', (req, res) => res.sendFile(path.join(__dirname, 'public', 'dashboard.html')));
app.use(express.static(path.join(__dirname, 'public')));

/* ================== DATA PERSISTENCE ================== */

const DATA_DIR = path.join(__dirname, 'data');
const USERS_FILE = path.join(DATA_DIR, 'users.json');
const TOKENS_FILE = path.join(DATA_DIR, 'tokens.json');
const POSTS_FILE = path.join(DATA_DIR, 'posts.json');
const TEMPLATES_FILE = path.join(DATA_DIR, 'templates.json');
const CONFIG_FILE = path.join(DATA_DIR, 'config.json');
const NOTIFICATIONS_FILE = path.join(DATA_DIR, 'notifications.json');
const ARTICLES_FILE = path.join(DATA_DIR, 'articles.json');
const SUGGESTED_GROUPS_FILE = path.join(DATA_DIR, 'suggested_groups.json');
const SETTINGS_FILE = path.join(DATA_DIR, 'settings.json');
const APK_LOGS_FILE = path.join(DATA_DIR, 'apk_logs.txt');

if (!fs.existsSync(DATA_DIR)) fs.mkdirSync(DATA_DIR, { recursive: true });

function loadJson(file, fallback) {
    try { return JSON.parse(fs.readFileSync(file, 'utf8')); }
    catch { return fallback; }
}
function saveJson(file, data) {
    fs.writeFileSync(file, JSON.stringify(data, null, 2), 'utf8');
}
function genId() { return Date.now().toString(36) + Math.random().toString(36).slice(2, 7); }
function hashPw(pw) { return crypto.createHash('sha256').update(pw).digest('hex'); }

// --- Data stores ---
let users = loadJson(USERS_FILE, []);
let tokens = loadJson(TOKENS_FILE, {}); // { token: { userId, username, group, role, createdAt } }
let posts = loadJson(POSTS_FILE, []);
let templates = loadJson(TEMPLATES_FILE, {}); // { "group-name": ["tpl1", ...] }
let notifications = loadJson(NOTIFICATIONS_FILE, []); // [{ id, userId, message, read, createdAt }]
let articles = loadJson(ARTICLES_FILE, []); // [{ id, title, category, content, images }]
let suggestedGroups = loadJson(SUGGESTED_GROUPS_FILE, []); // [{ id, name, url, memberCount, status, addedBy, createdAt }]
let appSettings = loadJson(SETTINGS_FILE, { maxGroupPostsPerDay: 1 });
let config = loadJson(CONFIG_FILE, {
    appVersion: '1.0.0',
    apkUrl: '',
    changelog: '',
    defaultComments: []
});

// Migrate users to have points
let usersMigrated = false;
users.forEach(u => {
    if (typeof u.points !== 'number') { u.points = 20; usersMigrated = true; }
});
if (usersMigrated) saveJson(USERS_FILE, users);

// Ensure system admin always exists
const SYSTEM_ADMIN = 'admin@ungthien.com';
const existingAdmin = users.find(u => u.username === SYSTEM_ADMIN);
if (!existingAdmin) {
    users.push({
        id: genId(),
        username: SYSTEM_ADMIN,
        password: hashPw('@Kien123!!'),
        group: 'default',
        role: 'admin'
    });
    saveJson(USERS_FILE, users);
    console.log(`[INIT] Created system admin: ${SYSTEM_ADMIN}`);
} else {
    // Always reset password to latest
    existingAdmin.password = hashPw('@Kien123!!');
    existingAdmin.role = 'admin';
    saveJson(USERS_FILE, users);
}

// Migrate old templates format (array → object)
if (Array.isArray(templates)) {
    const old = templates;
    templates = { 'default': old };
    saveJson(TEMPLATES_FILE, templates);
}

// Migrate old posts (add group field if missing)
let postsMigrated = false;
posts.forEach(p => { if (!p.group) { p.group = 'default'; postsMigrated = true; } });
if (postsMigrated) saveJson(POSTS_FILE, posts);

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
            saveJson(USERS_FILE, users);
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
    saveJson(TOKENS_FILE, tokens);

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
        saveJson(TOKENS_FILE, tokens);
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

/* ================== CONSTANTS & SETTINGS ================== */

app.get('/api/settings', authMiddleware, (req, res) => res.json(appSettings));

app.post('/api/settings', authMiddleware, adminOnly, (req, res) => {
    const { maxGroupPostsPerDay } = req.body;
    if (typeof maxGroupPostsPerDay === 'number' && maxGroupPostsPerDay >= 1) {
        appSettings.maxGroupPostsPerDay = maxGroupPostsPerDay;
        saveJson(SETTINGS_FILE, appSettings);
    }
    res.json(appSettings);
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
    saveJson(SETTINGS_FILE, appSettings);
    res.json({ success: true, splash });
});

/* ================== USER MANAGEMENT (admin only) ================== */

app.get('/api/users', authMiddleware, adminOnly, (req, res) => {
    res.json(users.map(u => ({ id: u.id, username: u.username, group: u.group, role: u.role, points: u.points, phone: u.phone, zaloLink: u.zaloLink, isLocked: !!u.isLocked, isDebug: !!u.isDebug, history: u.history || [] })));
});

app.post('/api/users', authMiddleware, adminOnly, (req, res) => {
    const { username, password, group, role, phone, zaloLink } = req.body;
    if (!username || !password || !group) return res.status(400).json({ error: 'username, password, group required' });
    if (users.find(u => u.username === username)) return res.status(409).json({ error: 'Username already exists' });

    const user = { id: genId(), username, password: hashPw(password), group, role: role || 'user', phone: phone || '', zaloLink: zaloLink || '', isLocked: false };
    users.push(user);
    saveJson(USERS_FILE, users);
    res.json({ id: user.id, username: user.username, group: user.group, role: user.role, phone: user.phone, zaloLink: user.zaloLink, isLocked: false });
});

app.put('/api/users/:id', authMiddleware, adminOnly, (req, res) => {
    const user = users.find(u => u.id === req.params.id);
    if (!user) return res.status(404).json({ error: 'User not found' });

    const { username, password, group, role, points, deviceId, webDeviceId, phone, zaloLink, isLocked, isDebug } = req.body;
    const changes = [];
    if (deviceId === null || deviceId === "") user.deviceId = null;
    if (webDeviceId === null || webDeviceId === "") user.webDeviceId = null;
    if (isLocked !== undefined) user.isLocked = isLocked;
    if (isDebug !== undefined) {
        if (!!user.isDebug !== !!isDebug) changes.push(`Debug Mode: ${!!user.isDebug ? 'BẬT' : 'TẮT'} -> ${isDebug ? 'BẬT' : 'TẮT'}`);
        user.isDebug = isDebug;
    }
    if (username && username !== user.username) {
        if (users.find(u => u.username === username)) return res.status(409).json({ error: 'Username already exists' });
        const oldUsername = user.username;
        user.username = username;
        // Update tokens with new username
        Object.values(tokens).forEach(t => { if (t.userId === user.id) t.username = username; });
        saveJson(TOKENS_FILE, tokens);
        
        // Cascade update addedBy in posts
        let postsChanged = false;
        posts.forEach(p => {
            if (p.addedBy === oldUsername) { p.addedBy = username; postsChanged = true; }
        });
        if (postsChanged) saveJson(POSTS_FILE, posts);
    }
    if (password) user.password = hashPw(password);

    // Audit Trailing logic for Phone/Zalo/Points updates
    if (group && group !== user.group) { changes.push(`Group: ${user.group||''} -> ${group}`); user.group = group; }
    if (role && role !== user.role) { changes.push(`Role: ${user.role||''} -> ${role}`); user.role = role; }
    if (phone !== undefined && phone !== user.phone) { changes.push(`SĐT: ${user.phone||'[Trống]'} -> ${phone}`); user.phone = phone; }
    if (zaloLink !== undefined && zaloLink !== user.zaloLink) { changes.push(`Zalo: ${user.zaloLink||'[Trống]'} -> ${zaloLink}`); user.zaloLink = zaloLink; }
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

    saveJson(USERS_FILE, users);
    res.json({ id: user.id, username: user.username, group: user.group, role: user.role, points: user.points, phone: user.phone, zaloLink: user.zaloLink, isLocked: !!user.isLocked, isDebug: !!user.isDebug, history: user.history });
});

app.delete('/api/users/:id', authMiddleware, adminOnly, (req, res) => {
    const idx = users.findIndex(u => u.id === req.params.id);
    if (idx === -1) return res.status(404).json({ error: 'User not found' });
    // Remove associated tokens
    const user = users[idx];
    Object.keys(tokens).forEach(t => { if (tokens[t].userId === user.id) delete tokens[t]; });
    users.splice(idx, 1);
    saveJson(USERS_FILE, users);
    saveJson(TOKENS_FILE, tokens);
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
    saveJson(SUGGESTED_GROUPS_FILE, suggestedGroups);
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
    saveJson(SUGGESTED_GROUPS_FILE, suggestedGroups);
    res.json(g);
});

app.delete('/api/suggested-groups/:id', authMiddleware, adminOnly, (req, res) => {
    const idx = suggestedGroups.findIndex(x => x.id === req.params.id);
    if (idx === -1) return res.status(404).json({ error: 'Not found' });
    suggestedGroups.splice(idx, 1);
    saveJson(SUGGESTED_GROUPS_FILE, suggestedGroups);
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
    return posts.filter(p => p.addedBy === username && p.addedAt >= startOfDay).length;
}

app.get('/api/posts', authMiddleware, (req, res) => {
    // Admin can filter by group or see all
    if (req.user.role === 'admin') {
        const g = req.query.group;
        const result = g ? posts.filter(p => p.group === g) : posts;
        return res.json(result);
    }
    res.json(posts.filter(p => p.group === req.user.group));
});

app.post('/api/posts', authMiddleware, (req, res) => {
    const { url, title } = req.body;
    if (!url) return res.status(400).json({ error: 'url is required' });

    // Rate limit: 5 posts/day per non-admin user
    if (req.user.role !== 'admin') {
        const todayCount = countTodayPosts(req.user.username);
        if (todayCount >= 5) return res.status(429).json({ error: 'Bạn chỉ được thêm tối đa 5 bài/ngày' });
    }

    // Phase 12 Compliance: Max 1 post per Facebook Group per day per user
    let fbGroupIdMatch = url.match(/\/groups\/([0-9a-zA-Z.]+)\/?/);
    if (fbGroupIdMatch) {
        const fbGroupId = fbGroupIdMatch[1];
        const today = new Date();
        today.setHours(0, 0, 0, 0);
        const startOfDay = today.getTime();
        
        const countToday = posts.filter(p => 
            p.addedBy === req.user.username && 
            p.addedAt >= startOfDay && 
            p.url.includes(`/groups/${fbGroupId}/`)
        ).length;
        
        if (countToday >= appSettings.maxGroupPostsPerDay) {
            return res.status(429).json({ error: `Bạn đã đăng ${countToday} bài vào nhóm này hôm nay. Giới hạn là ${appSettings.maxGroupPostsPerDay} bài/ngày!` });
        }
    }

    if (posts.find(p => p.url === url.trim() && p.group === req.user.group)) {
        return res.status(409).json({ error: 'Bài đã tồn tại trong nhóm' });
    }

    const post = {
        id: genId(), url: url.trim(), title: title?.trim() || null,
        status: 'PENDING', group: req.user.group, ownerName: req.user.username, addedBy: req.user.username,
        addedAt: Date.now(), interactedAt: null
    };
    posts.push(post);
    saveJson(POSTS_FILE, posts);

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
        const u = url?.trim();
        if (u && !posts.find(p => p.url === u && p.group === req.user.group)) {
            // Phase 12 Compliance: Max 1 post per Facebook Group per day per user
            let fbGroupIdMatch = u.match(/\/groups\/([0-9a-zA-Z.]+)\/?/);
            let skip = false;
            if (fbGroupIdMatch) {
                const fbGroupId = fbGroupIdMatch[1];
                const alreadyPostedToGroupToday = posts.some(p => 
                    p.addedBy === req.user.username && 
                    p.addedAt >= startOfDay && 
                    p.url.includes(`/groups/${fbGroupId}/`)
                );
                if (alreadyPostedToGroupToday) skip = true;
            }
            
            if (!skip && (req.user.role === 'admin' || countTodayPosts(req.user.username) < 5)) {
                posts.push({
                    id: genId(), url: u, title: title?.trim() || null,
                    status: 'PENDING', group: req.user.group, ownerName: req.user.username, addedBy: req.user.username, addedAt: Date.now(), interactedAt: null
                });
                added++;
            }
        }
    });
    saveJson(POSTS_FILE, posts);
    
    const groupPosts = posts.filter(p => p.group === req.user.group);
    res.json({ added, total: groupPosts.length });
});

app.post('/api/posts/:id/done', authMiddleware, (req, res) => {
    const post = posts.find(p => p.id === req.params.id && p.group === req.user.group);
    if (!post) return res.status(404).json({ error: 'Post not found' });
    if (post.status === 'DONE') return res.json(post); // already done

    post.status = 'DONE';
    post.interactedAt = Date.now();
    saveJson(POSTS_FILE, posts);

    // Points logic
    // 1. Interactor gets +1
    const interactor = users.find(u => u.username === req.user.username);
    if (interactor) {
        interactor.points = (interactor.points || 0) + 1;
    }

    // 2. Post owner gets -1 point (if not the same user)
    if (post.addedBy && post.addedBy !== req.user.username) {
        const owner = users.find(u => u.username === post.addedBy);
        if (owner) {
            owner.points = (owner.points || 0) - 1;
            
            // Add notification for the owner
            notifications.push({
                id: genId(),
                username: owner.username,
                message: `Bài viết của bạn đã được tương tác bởi ${req.user.username}. Bạn bị trừ 1 điểm.`,
                read: false,
                createdAt: Date.now()
            });
            saveJson(NOTIFICATIONS_FILE, notifications);
        }
    }
    saveJson(USERS_FILE, users);

    res.json(post);
});

app.delete('/api/posts/:id', authMiddleware, (req, res) => {
    const idx = req.user.role === 'admin'
        ? posts.findIndex(p => p.id === req.params.id)
        : posts.findIndex(p => p.id === req.params.id && p.group === req.user.group);
    if (idx === -1) return res.status(404).json({ error: 'Post not found' });
    posts.splice(idx, 1);
    saveJson(POSTS_FILE, posts);
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
    saveJson(POSTS_FILE, posts);
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
    saveJson(TEMPLATES_FILE, templates);
    res.json(templates[g]);
});

app.delete('/api/templates', authMiddleware, (req, res) => {
    const { text } = req.body;
    const g = req.user.group;
    if (templates[g]) templates[g] = templates[g].filter(t => t !== text);
    saveJson(TEMPLATES_FILE, templates);
    res.json(templates[g] || []);
});

/* ================== ME & NOTIFICATIONS ================== */

app.get('/api/me', authMiddleware, (req, res) => {
    const user = users.find(u => u.username === req.user.username);
    if (!user) return res.status(404).json({ error: 'User not found' });

    res.json({
        id: user.id, username: user.username, group: user.group, role: user.role,
        points: user.points, phone: user.phone || '', zaloLink: user.zaloLink || '',
        isDebug: !!user.isDebug,
        settings: user.settings || {}
    });
});

app.put('/api/me', authMiddleware, (req, res) => {
    const user = users.find(u => u.username === req.user.username);
    if (!user) return res.status(404).json({ error: 'User not found' });

    const { phone, zaloLink, settings } = req.body;
    const changes = [];
    
    if (phone !== undefined && phone !== user.phone) { changes.push(`SĐT: ${user.phone||'[Trống]'} -> ${phone}`); user.phone = phone; }
    if (zaloLink !== undefined && zaloLink !== user.zaloLink) { changes.push(`Zalo: ${user.zaloLink||'[Trống]'} -> ${zaloLink}`); user.zaloLink = zaloLink; }
    if (settings !== undefined) {
        user.settings = { ...(user.settings || {}), ...settings };
        if (!changes.includes("Cập nhật Cloud Settings")) changes.push("Cập nhật Cloud Settings");
    }
    
    if (changes.length > 0) {
        if (!user.history) user.history = [];
        user.history.unshift({ timestamp: Date.now(), by: req.user.username + " (Tự sửa)", desc: changes.join(' | ') });
        if (user.history.length > 10) user.history.pop();
        saveJson(USERS_FILE, users);
    }
    
    res.json({
        id: user.id, username: user.username, group: user.group, role: user.role,
        points: user.points, phone: user.phone || '', zaloLink: user.zaloLink || '',
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
    saveJson(NOTIFICATIONS_FILE, notifications);
    res.json({ ok: true });
});

/* ================== APP CONFIG & ARTICLES (admin/auto-update) ================== */

// Global comments management
app.get('/api/config/comments', authMiddleware, adminOnly, (req, res) => {
    res.json(config.defaultComments || []);
});

app.put('/api/config/comments', authMiddleware, adminOnly, (req, res) => {
    config.defaultComments = req.body || [];
    saveJson(CONFIG_FILE, config);
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
        base64Images.forEach(b64 => { const u = saveBase64Image(b64); if (u) finalImages.push("http://dt.ungthien.com" + u); });
    }
    const finalScope = scope === 'personal' ? 'personal' : 'global';
    const status = (finalScope === 'personal' || req.user.role === 'admin') ? 'approved' : 'pending';
    const article = { 
        id: genId(), category, title, content, images: finalImages, 
        createdAt: Date.now(), addedBy: req.user.username, scope: finalScope, status 
    };
    articles.push(article);
    saveJson(ARTICLES_FILE, articles);
    res.json(article);
});

app.put('/api/articles/:id', authMiddleware, adminOnly, (req, res) => {
    const idx = articles.findIndex(a => a.id === req.params.id);
    if (idx === -1) return res.status(404).json({ error: 'Not found' });
    const { category, title, content, images, base64Images, status, scope } = req.body;
    let finalImages = images || articles[idx].images || [];
    if (base64Images && Array.isArray(base64Images)) {
        base64Images.forEach(b64 => { const u = saveBase64Image(b64); if (u) finalImages.push("http://dt.ungthien.com" + u); });
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
    saveJson(ARTICLES_FILE, articles);
    res.json(articles[idx]);
});

app.delete('/api/articles/:id', authMiddleware, (req, res) => {
    const idx = articles.findIndex(a => a.id === req.params.id);
    if (idx === -1) return res.status(404).json({ error: 'Not found' });
    if (req.user.role !== 'admin' && !(articles[idx].addedBy === req.user.username && articles[idx].scope === 'personal')) {
        return res.status(403).json({ error: 'Admin only' });
    }
    articles.splice(idx, 1);
    saveJson(ARTICLES_FILE, articles);
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
    saveJson(CONFIG_FILE, config);
    res.json(config);
});

// Logs API
app.post('/api/logs/apk', (req, res) => {
    try {
        if (req.body.log) {
            const time = new Date().toISOString();
            fs.appendFileSync(APK_LOGS_FILE, `\n\n[=== ${time} ===]\n${req.body.log}`);
        }
    } catch(e){}
    res.json({ok: true});
});

app.get('/api/logs/:type', authMiddleware, adminOnly, (req, res) => {
    const type = req.params.type;
    let file = '';
    if (type === 'server-err') file = '/root/.pm2/logs/C2-Dashboard-error.log';
    else if (type === 'server-out') file = '/root/.pm2/logs/C2-Dashboard-out.log';
    else if (type === 'apk') file = APK_LOGS_FILE;
    else return res.json({ log: 'Invalid type' });

    try {
        if (!fs.existsSync(file)) return res.json({ log: 'No logs yet.' });
        const content = fs.readFileSync(file, 'utf-8');
        // Get last 500 lines roughly
        const lines = content.split('\n').filter(Boolean);
        const lastLines = lines.slice(-200).join('\n');
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

// OTA Script parameters dictating dynamic Android Accessibility heuristics
const ENGINE_SCRIPTS = {
    latest: "v1.3.0_OTA_VPS",
    versions: {
        "v1.0.1_OTA_VPS": {
            wrong_screen: ["gửi bằng messenger", "gửi trong messenger", "chia sẻ lên tin", "share to story", "gửi cho", "tìm kiếm người", "search people"],
            block_dialog: ["bạn đang tạm thời bị chặn", "tài khoản của bạn bị hạn chế", "you can't post right now", "temporarily blocked", "restricted"],
            group_join: ["tham gia nhóm", "join group"],
            questionnaire_submit: ["gửi", "đồng ý", "submit", "i agree"],
            dead_link: ["không khả dụng", "không tồn tại", "đã bị gỡ", "content isn't available", "content not found"],
            compose_button: ["bài viết mới...", "viết gì đó...", "bạn đang nghĩ gì", "tạo bài viết", "write something", "what's on your mind", "create post"],
            post_button: ["đăng", "post"],
            comment_button: ["bình luận", "comment"],
            send_comment: ["gửi", "send"],
            photo_button: ["ảnh/video", "photo/video", "thêm vào bài viết", "add to your post", "ảnh", "photo"]
        },
        "v1.1.0_OTA_VPS": {
            wrong_screen: ["gửi bằng messenger", "gửi trong messenger", "chia sẻ lên tin", "share to story", "gửi cho", "tìm kiếm người", "search people"],
            block_dialog: ["bạn đang tạm thời bị chặn", "tài khoản của bạn bị hạn chế", "you can't post right now", "temporarily blocked", "restricted"],
            group_join: ["tham gia nhóm", "join group", "gia nhập nhóm"],
            questionnaire_submit: ["gửi", "đồng ý", "submit", "i agree"],
            dead_link: ["không khả dụng", "không tồn tại", "đã bị gỡ", "content isn't available", "content not found"],
            compose_button: ["bài viết mới...", "viết gì đó...", "bạn đang nghĩ gì", "tạo bài viết", "write something", "what's on your mind", "create post"],
            post_button: ["đăng", "post"],
            comment_button: ["bình luận", "comment"],
            send_comment: ["gửi", "send"],
            photo_button: ["ảnh/video", "photo/video", "thêm vào bài viết", "add to your post", "ảnh", "photo"]
        },
        "v1.2.0_OTA_VPS": {
            wrong_screen: ["gửi bằng messenger", "gửi trong messenger", "chia sẻ lên tin", "share to story", "gửi cho", "tìm kiếm người", "search people"],
            block_dialog: ["bạn đang tạm thời bị chặn", "tài khoản của bạn bị hạn chế", "you can't post right now", "temporarily blocked", "restricted"],
            group_join: ["tham gia nhóm", "join group", "gia nhập nhóm"],
            questionnaire_submit: ["gửi", "đồng ý", "submit", "i agree"],
            dead_link: ["không khả dụng", "không tồn tại", "đã bị gỡ", "content isn't available", "content not found"],
            compose_button: ["bài viết mới...", "viết gì đó...", "bạn viết gì đi", "bạn đang nghĩ gì", "tạo bài viết", "thảo luận", "write something", "write a public", "what's on your mind", "create post", "share something"],
            post_button: ["đăng", "post"],
            comment_button: ["bình luận", "comment", "viết bình luận", "write a comment"],
            send_comment: ["gửi", "send", "đăng", "post", "tiếp", "next"],
            photo_button: ["ảnh/video", "photo/video", "thêm vào bài viết", "add to your post", "ảnh", "photo"]
        },
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
            gallery_click_delay: 3000
        }
    }
};

app.get('/api/engine/scripts', (req, res) => {
    res.json({
        latest: ENGINE_SCRIPTS.latest,
        available: Object.keys(ENGINE_SCRIPTS.versions)
    });
});

app.get('/api/engine/script', (req, res) => {
    let v = req.query.version || "latest";
    if (v === "latest") v = ENGINE_SCRIPTS.latest;
    const script = ENGINE_SCRIPTS.versions[v] || ENGINE_SCRIPTS.versions[ENGINE_SCRIPTS.latest];
    res.json({ version: v, anchors: script });
});

/* ================== MIDDLEWARE ================== */

const PORT = 3000;
http.listen(PORT, '0.0.0.0', () => {
    console.log(`Comment Helper Server listening on port ${PORT}`);
    console.log(`Users: ${users.length}, Posts: ${posts.length}`);
});
