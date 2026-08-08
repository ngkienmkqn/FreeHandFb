const fs = require('fs');
const path = require('path');
const { pool } = require('./pool');

function nowMs() {
    return Date.now();
}

async function runMigrations() {
    await pool.query(`
        CREATE TABLE IF NOT EXISTS fh_schema_migrations (
            version TEXT PRIMARY KEY,
            applied_at TIMESTAMPTZ NOT NULL DEFAULT now()
        )
    `);
    const migrationsDir = path.join(__dirname, 'migrations');
    const files = fs.readdirSync(migrationsDir).filter(file => file.endsWith('.sql')).sort();
    for (const file of files) {
        const version = file.replace(/\.sql$/, '');
        const existing = await pool.query('SELECT 1 FROM fh_schema_migrations WHERE version = $1', [version]);
        if (existing.rowCount > 0) continue;
        const sql = fs.readFileSync(path.join(migrationsDir, file), 'utf8');
        const client = await pool.connect();
        try {
            await client.query('BEGIN');
            await client.query(sql);
            await client.query('INSERT INTO fh_schema_migrations(version) VALUES($1)', [version]);
            await client.query('COMMIT');
            console.log(`[DB] Applied migration ${version}`);
        } catch (error) {
            await client.query('ROLLBACK');
            throw error;
        } finally {
            client.release();
        }
    }
}

async function transaction(work) {
    const client = await pool.connect();
    try {
        await client.query('BEGIN');
        const result = await work(client);
        await client.query('COMMIT');
        return result;
    } catch (error) {
        await client.query('ROLLBACK');
        throw error;
    } finally {
        client.release();
    }
}

function userToDb(user) {
    const createdAt = user.createdAt || nowMs();
    return [
        user.id,
        user.username,
        user.password,
        user.group || 'default',
        user.role || 'user',
        Number.isFinite(user.points) ? user.points : 20,
        user.phone || '',
        user.zaloLink || '',
        user.facebookName || '',
        user.deviceId || null,
        user.webDeviceId || null,
        !!user.isLocked,
        !!user.isDebug,
        JSON.stringify(user.settings || {}),
        JSON.stringify(user.history || []),
        Number.isFinite(user.maxGroupPostsPerDay) ? user.maxGroupPostsPerDay : 1,
        createdAt,
        user.updatedAt || createdAt
    ];
}

function dbToUser(row) {
    return {
        id: row.id,
        username: row.username,
        password: row.password_hash,
        group: row.user_group,
        role: row.role,
        points: row.points,
        phone: row.phone || '',
        zaloLink: row.zalo_link || '',
        facebookName: row.facebook_name || '',
        deviceId: row.device_id || null,
        webDeviceId: row.web_device_id || null,
        isLocked: !!row.is_locked,
        isDebug: !!row.is_debug,
        settings: row.settings || {},
        history: row.history || [],
        maxGroupPostsPerDay: row.max_group_posts_per_day
    };
}

async function upsertUsers(fh_users) {
    for (const user of fh_users) {
        await pool.query(
            `INSERT INTO fh_users(
                id, username, password_hash, user_group, role, points, phone, zalo_link, facebook_name,
                device_id, web_device_id, is_locked, is_debug, settings, history, max_group_posts_per_day,
                created_at, updated_at
            )
            VALUES($1,$2,$3,$4,$5,$6,$7,$8,$9,$10,$11,$12,$13,$14::jsonb,$15::jsonb,$16,$17,$18)
            ON CONFLICT(id) DO UPDATE SET
                username = EXCLUDED.username,
                password_hash = EXCLUDED.password_hash,
                user_group = EXCLUDED.user_group,
                role = EXCLUDED.role,
                points = EXCLUDED.points,
                phone = EXCLUDED.phone,
                zalo_link = EXCLUDED.zalo_link,
                facebook_name = EXCLUDED.facebook_name,
                device_id = EXCLUDED.device_id,
                web_device_id = EXCLUDED.web_device_id,
                is_locked = EXCLUDED.is_locked,
                is_debug = EXCLUDED.is_debug,
                settings = EXCLUDED.settings,
                history = EXCLUDED.history,
                max_group_posts_per_day = EXCLUDED.max_group_posts_per_day,
                updated_at = EXCLUDED.updated_at`,
            userToDb(user)
        );
    }
}

async function loadUsers() {
    const result = await pool.query('SELECT * FROM fh_users ORDER BY created_at ASC, username ASC');
    return result.rows.map(dbToUser);
}

async function replaceUsers(fh_users) {
    await transaction(async client => {
        await client.query('DELETE FROM fh_users');
        for (const user of fh_users || []) {
            await client.query(
                `INSERT INTO fh_users(id,username,password_hash,user_group,role,points,phone,zalo_link,facebook_name,
                    device_id,web_device_id,is_locked,is_debug,settings,history,max_group_posts_per_day,created_at,updated_at)
                 VALUES($1,$2,$3,$4,$5,$6,$7,$8,$9,$10,$11,$12,$13,$14::jsonb,$15::jsonb,$16,$17,$18)`,
                userToDb(user)
            );
        }
    });
}

async function loadTokens() {
    const result = await pool.query('SELECT * FROM fh_auth_tokens');
    const tokens = {};
    for (const row of result.rows) {
        tokens[row.token] = {
            userId: row.user_id,
            username: row.username,
            group: row.user_group,
            role: row.role,
            createdAt: row.created_at
        };
    }
    return tokens;
}

async function replaceTokens(tokens) {
    await transaction(async client => {
      await client.query('DELETE FROM fh_auth_tokens');
      for (const [token, value] of Object.entries(tokens || {})) {
        await client.query(
            `INSERT INTO fh_auth_tokens(token, user_id, username, user_group, role, created_at)
             VALUES($1,$2,$3,$4,$5,$6)
             ON CONFLICT(token) DO UPDATE SET
                user_id = EXCLUDED.user_id,
                username = EXCLUDED.username,
                user_group = EXCLUDED.user_group,
                role = EXCLUDED.role,
                created_at = EXCLUDED.created_at`,
            [token, value.userId, value.username, value.group || 'default', value.role || 'user', value.createdAt || nowMs()]
        );
      }
    });
}

function dbToJob(row) {
    const job = {
        id: row.id,
        type: row.job_type,
        group: row.user_group,
        targetPostId: row.target_post_id || undefined,
        priority: row.priority || 'NORMAL',
        payload: row.payload || {},
        status: row.status,
        attempts: row.attempts || 0,
        createdBy: row.created_by || undefined,
        claimedBy: row.claimed_by || null,
        deviceId: row.device_id || null,
        leaseToken: row.lease_token || null,
        result: row.result || undefined,
        lastError: row.last_error || undefined,
        createdAt: row.created_at,
        updatedAt: row.updated_at,
        scheduledAt: row.scheduled_at || undefined,
        claimedAt: row.claimed_at || undefined,
        heartbeatAt: row.heartbeat_at || undefined,
        irreversibleAt: row.irreversible_at || undefined,
        finishedAt: row.finished_at || undefined
    };
    Object.keys(job).forEach(key => job[key] === undefined && delete job[key]);
    return job;
}

function jobParams(job, queueType) {
    return [
        job.id,
        queueType || job.type,
        job.type,
        job.group || 'default',
        job.targetPostId || job.payload?.targetPostId || null,
        job.priority || 'NORMAL',
        JSON.stringify(job.payload || {}),
        job.status || 'QUEUED',
        job.attempts || 0,
        job.createdBy || null,
        job.claimedBy || null,
        job.deviceId || null,
        job.leaseToken || null,
        JSON.stringify(job.result || {}),
        job.lastError || null,
        job.createdAt || nowMs(),
        job.updatedAt || nowMs(),
        job.scheduledAt || null,
        job.claimedAt || null,
        job.heartbeatAt || null,
        job.irreversibleAt || null,
        job.finishedAt || null
    ];
}

async function replaceJobs(queueType, jobs) {
    await transaction(async client => {
        await client.query('DELETE FROM fh_executor_jobs WHERE queue_type = $1', [queueType]);
        for (const job of jobs || []) await upsertJob(queueType, job, client);
    });
}

async function upsertJob(queueType, job, db = pool) {
    await db.query(
        `INSERT INTO fh_executor_jobs(
            id, queue_type, job_type, user_group, target_post_id, priority, payload, status, attempts,
            created_by, claimed_by, device_id, lease_token, result, last_error,
            created_at, updated_at, scheduled_at, claimed_at, heartbeat_at, irreversible_at, finished_at
        )
        VALUES($1,$2,$3,$4,$5,$6,$7::jsonb,$8,$9,$10,$11,$12,$13,$14::jsonb,$15,$16,$17,$18,$19,$20,$21,$22)
        ON CONFLICT(id) DO UPDATE SET
            queue_type = EXCLUDED.queue_type,
            job_type = EXCLUDED.job_type,
            user_group = EXCLUDED.user_group,
            target_post_id = EXCLUDED.target_post_id,
            priority = EXCLUDED.priority,
            payload = EXCLUDED.payload,
            status = EXCLUDED.status,
            attempts = EXCLUDED.attempts,
            created_by = EXCLUDED.created_by,
            claimed_by = EXCLUDED.claimed_by,
            device_id = EXCLUDED.device_id,
            lease_token = EXCLUDED.lease_token,
            result = EXCLUDED.result,
            last_error = EXCLUDED.last_error,
            created_at = EXCLUDED.created_at,
            updated_at = EXCLUDED.updated_at,
            scheduled_at = EXCLUDED.scheduled_at,
            claimed_at = EXCLUDED.claimed_at,
            heartbeat_at = EXCLUDED.heartbeat_at,
            irreversible_at = EXCLUDED.irreversible_at,
            finished_at = EXCLUDED.finished_at`,
        jobParams(job, queueType)
    );
}

async function loadJobs(queueType) {
    const result = await pool.query('SELECT * FROM fh_executor_jobs WHERE queue_type = $1 ORDER BY created_at ASC', [queueType]);
    return result.rows.map(dbToJob);
}

/** Merge top-level Wave-4 fields into auto_close JSONB for persist (no migration). */
function autoCloseForPersist(target) {
    const autoClose = { ...(target?.autoClose || {}) };
    const resumed = Number(target?.resumedFromReviewAt ?? autoClose.resumedFromReviewAt);
    if (Number.isFinite(resumed) && resumed > 0) {
        autoClose.resumedFromReviewAt = resumed;
    }
    const hours = target?.activeHours;
    if (hours?.start && hours?.end && !(autoClose.activeHours?.start && autoClose.activeHours?.end)) {
        autoClose.activeHours = { start: hours.start, end: hours.end };
    }
    return autoClose;
}

function dbToTarget(row) {
    const autoClose = { ...(row.auto_close || {}) };
    const activeHours = autoClose.activeHours && typeof autoClose.activeHours === 'object'
        ? autoClose.activeHours
        : undefined;
    const resumedRaw = row.resumed_from_review_at ?? autoClose.resumedFromReviewAt;
    const resumedFromReviewAt = Number(resumedRaw);
    const target = {
        id: row.id, group: row.user_group, groupId: row.group_id, postUrl: row.post_url, status: row.status,
        requirements: row.requirements || {}, commentPool: row.comment_pool || [],
        allowRepeatComments: !!row.allow_repeat_comments, targetPost: row.target_post || {}, speed: row.speed,
        priority: row.priority, onlineOnly: !!row.online_only, autoClose,
        ...(activeHours?.start && activeHours?.end ? { activeHours: { start: activeHours.start, end: activeHours.end } } : {}),
        ...(Number.isFinite(resumedFromReviewAt) && resumedFromReviewAt > 0 ? { resumedFromReviewAt } : {}),
        createdBy: row.created_by || undefined, closedBy: row.closed_by || undefined,
        closeReason: row.close_reason || undefined, reviewReason: row.review_reason || undefined,
        createdAt: row.created_at, updatedAt: row.updated_at, lastPlannedAt: row.last_planned_at || undefined,
        completedAt: row.completed_at || undefined, closedAt: row.closed_at || undefined
    };
    Object.keys(target).forEach(key => target[key] === undefined && delete target[key]);
    return target;
}

async function replaceInteractionTargets(targets) {
    await transaction(async client => {
        await client.query('DELETE FROM fh_interaction_targets');
        for (const target of targets || []) {
            await client.query(
                `INSERT INTO fh_interaction_targets(id,user_group,group_id,post_url,status,requirements,comment_pool,
                    allow_repeat_comments,target_post,speed,priority,online_only,auto_close,created_by,closed_by,
                    close_reason,review_reason,created_at,updated_at,last_planned_at,completed_at,closed_at)
                 VALUES($1,$2,$3,$4,$5,$6::jsonb,$7::jsonb,$8,$9::jsonb,$10,$11,$12,$13::jsonb,$14,$15,$16,$17,$18,$19,$20,$21,$22)`,
                [target.id,target.group || 'default',target.groupId || 'default',target.postUrl,target.status || 'RUNNING',
                 JSON.stringify(target.requirements || {}),JSON.stringify(target.commentPool || []),!!target.allowRepeatComments,
                 JSON.stringify(target.targetPost || {}),target.speed || 'NORMAL',target.priority || 'NORMAL',target.onlineOnly !== false,
                 JSON.stringify(autoCloseForPersist(target)),target.createdBy || null,target.closedBy || null,target.closeReason || null,
                 target.reviewReason || null,target.createdAt || nowMs(),target.updatedAt || nowMs(),target.lastPlannedAt || null,
                 target.completedAt || null,target.closedAt || null]
            );
        }
    });
}

async function loadInteractionTargets() {
    const result = await pool.query('SELECT * FROM fh_interaction_targets ORDER BY created_at');
    return result.rows.map(dbToTarget);
}

async function replacePosts(posts) {
    await transaction(async client => {
        await client.query('DELETE FROM fh_posts');
        for (const post of posts || []) await client.query(
            `INSERT INTO fh_posts(id,url,title,status,user_group,owner_name,added_by,added_at,interacted_at,is_publishing_group,interacted_by,verifications,extra)
             VALUES($1,$2,$3,$4,$5,$6,$7,$8,$9,$10,$11::jsonb,$12::jsonb,$13::jsonb)`,
            [post.id,post.url,post.title || null,post.status || 'PENDING',post.group || 'default',post.ownerName || null,
             post.addedBy || null,post.addedAt || nowMs(),post.interactedAt || null,!!post.isPublishingGroup,
             JSON.stringify(post.interactedBy || []),JSON.stringify(post.verifications || []),JSON.stringify(post)]
        );
    });
}
async function loadPosts() {
    const r = await pool.query('SELECT * FROM fh_posts ORDER BY added_at');
    return r.rows.map(x => ({...(x.extra || {}),id:x.id,url:x.url,title:x.title,status:x.status,group:x.user_group,
        ownerName:x.owner_name,addedBy:x.added_by,addedAt:x.added_at,interactedAt:x.interacted_at,
        isPublishingGroup:x.is_publishing_group,interactedBy:x.interacted_by || [],verifications:x.verifications || []}));
}

async function replaceTemplates(templates) {
    await transaction(async client => {
        await client.query('DELETE FROM fh_templates');
        for (const [group, items] of Object.entries(templates || {})) for (let i = 0; i < items.length; i++)
            await client.query('INSERT INTO fh_templates(user_group,text,position) VALUES($1,$2,$3)', [group, items[i], i]);
    });
}
async function loadTemplates() {
    const r = await pool.query('SELECT * FROM fh_templates ORDER BY user_group,position');
    return r.rows.reduce((out,x) => { (out[x.user_group] ||= []).push(x.text); return out; }, {});
}

async function replaceSimpleRows(table, rows, mapper) {
    await transaction(async client => {
        await client.query(`DELETE FROM ${table}`);
        for (const row of rows || []) await mapper(client, row);
    });
}
async function replaceNotifications(rows) { return replaceSimpleRows('fh_notifications', rows, (c,x) => c.query(
    'INSERT INTO fh_notifications(id,user_id,message,is_read,created_at,data) VALUES($1,$2,$3,$4,$5,$6::jsonb)',
    [x.id,x.userId || null,x.message || '',!!x.read,x.createdAt || nowMs(),JSON.stringify(x)])); }
async function loadNotifications() { const r=await pool.query('SELECT data,id,user_id,message,is_read,created_at FROM fh_notifications ORDER BY created_at'); return r.rows.map(x=>({...x.data,id:x.id,userId:x.user_id,message:x.message,read:x.is_read,createdAt:x.created_at})); }

async function replaceArticles(rows) { return replaceSimpleRows('fh_articles', rows, (c,x) => c.query(
    'INSERT INTO fh_articles(id,category,title,content,images,status,created_at,updated_at,data) VALUES($1,$2,$3,$4,$5::jsonb,$6,$7,$8,$9::jsonb)',
    [x.id,x.category || null,x.title || '',x.content || '',JSON.stringify(x.images || []),x.status || null,x.createdAt || null,x.updatedAt || null,JSON.stringify(x)])); }
async function loadArticles() { const r=await pool.query('SELECT * FROM fh_articles ORDER BY COALESCE(created_at,0)'); return r.rows.map(x=>({...x.data,id:x.id,category:x.category,title:x.title,content:x.content,images:x.images || [],status:x.status,createdAt:x.created_at,updatedAt:x.updated_at})); }

async function replaceSuggestedGroups(rows) { return replaceSimpleRows('fh_suggested_groups', rows, (c,x) => c.query(
    'INSERT INTO fh_suggested_groups(id,name,url,member_count,status,added_by,created_at,data) VALUES($1,$2,$3,$4,$5,$6,$7,$8::jsonb)',
    [x.id,x.name,x.url,String(x.memberCount || ''),x.status || 'pending',x.addedBy || null,x.createdAt || nowMs(),JSON.stringify(x)])); }
async function loadSuggestedGroups() { const r=await pool.query('SELECT * FROM fh_suggested_groups ORDER BY created_at'); return r.rows.map(x=>({...x.data,id:x.id,name:x.name,url:x.url,memberCount:x.member_count,status:x.status,addedBy:x.added_by,createdAt:x.created_at})); }

async function saveSettings(x) { await pool.query(`INSERT INTO fh_app_settings(singleton,max_group_posts_per_day,last_logs_cleanup,max_group_interaction_fail_streak,group_interaction_pause_minutes,extra)
 VALUES(true,$1,$2,$3,$4,$5::jsonb) ON CONFLICT(singleton) DO UPDATE SET max_group_posts_per_day=$1,last_logs_cleanup=$2,max_group_interaction_fail_streak=$3,group_interaction_pause_minutes=$4,extra=$5::jsonb`,
 [x.maxGroupPostsPerDay || 1,x.lastLogsCleanup || 0,x.maxGroupInteractionFailStreak || 5,x.groupInteractionPauseMinutes || 60,JSON.stringify(x)]); }
async function loadSettings() { const r=await pool.query('SELECT * FROM fh_app_settings WHERE singleton=true'); if(!r.rowCount)return {maxGroupPostsPerDay:1}; const x=r.rows[0]; return {...x.extra,maxGroupPostsPerDay:x.max_group_posts_per_day,lastLogsCleanup:x.last_logs_cleanup,maxGroupInteractionFailStreak:x.max_group_interaction_fail_streak,groupInteractionPauseMinutes:x.group_interaction_pause_minutes}; }

async function saveConfig(x) { await pool.query(`INSERT INTO fh_app_config(singleton,app_version,apk_url,changelog,default_comments,extra) VALUES(true,$1,$2,$3,$4::jsonb,$5::jsonb)
 ON CONFLICT(singleton) DO UPDATE SET app_version=$1,apk_url=$2,changelog=$3,default_comments=$4::jsonb,extra=$5::jsonb`,[x.appVersion || '1.0.0',x.apkUrl || '',x.changelog || '',JSON.stringify(x.defaultComments || []),JSON.stringify(x)]); }
async function loadConfig() { const r=await pool.query('SELECT * FROM fh_app_config WHERE singleton=true'); if(!r.rowCount)return {appVersion:'1.0.0',apkUrl:'',changelog:'',defaultComments:[]}; const x=r.rows[0]; return {...x.extra,appVersion:x.app_version,apkUrl:x.apk_url,changelog:x.changelog,defaultComments:x.default_comments || []}; }

async function replaceGroupIntelligence(map) { await transaction(async client => { await client.query('DELETE FROM fh_group_intelligence'); for(const [key,x] of Object.entries(map || {})) await client.query(
    `INSERT INTO fh_group_intelligence(group_id,joined_accounts,account_activity,recent_comments,fail_streak,paused_until,pause_reason,last_failure_at,last_failure,updated_at,extra)
     VALUES($1,$2::jsonb,$3::jsonb,$4::jsonb,$5,$6,$7,$8,$9,$10,$11::jsonb)`,[key,JSON.stringify(x.joinedAccounts || {}),JSON.stringify(x.accountActivity || {}),JSON.stringify(x.recentComments || []),x.failStreak || 0,x.pausedUntil || 0,x.pauseReason || '',x.lastFailureAt || null,x.lastFailure || null,x.updatedAt || nowMs(),JSON.stringify(x)]); }); }
async function loadGroupIntelligence() { const r=await pool.query('SELECT * FROM fh_group_intelligence'); return r.rows.reduce((o,x)=>{o[x.group_id]={...x.extra,groupId:x.group_id,joinedAccounts:x.joined_accounts || {},accountActivity:x.account_activity || {},recentComments:x.recent_comments || [],failStreak:x.fail_streak,pausedUntil:x.paused_until,pauseReason:x.pause_reason,lastFailureAt:x.last_failure_at,lastFailure:x.last_failure,updatedAt:x.updated_at};return o;},{}); }

async function saveEngine(engine) { await transaction(async client => { await client.query('DELETE FROM fh_engine_versions'); for(const [version,anchors] of Object.entries(engine.versions || {})) await client.query('INSERT INTO fh_engine_versions(version,anchors,updated_at) VALUES($1,$2::jsonb,$3)',[version,JSON.stringify(anchors),nowMs()]); await client.query(`INSERT INTO fh_engine_state(singleton,latest_version,js_code,updated_at) VALUES(true,$1,$2,$3) ON CONFLICT(singleton) DO UPDATE SET latest_version=$1,js_code=$2,updated_at=$3`,[engine.latest || 'v1.0.0',engine.jsCode || '',nowMs()]); }); }
async function loadEngine() { const [s,v]=await Promise.all([pool.query('SELECT * FROM fh_engine_state WHERE singleton=true'),pool.query('SELECT version,anchors FROM fh_engine_versions')]); const state=s.rows[0] || {}; return {latest:state.latest_version || 'v1.0.0',versions:v.rows.reduce((o,x)=>(o[x.version]=x.anchors,o),{}),jsCode:state.js_code || ''}; }

module.exports = {
    pool,
    runMigrations,
    loadUsers,
    replaceUsers,
    upsertUsers,
    loadTokens,
    replaceTokens,
    loadJobs,
    replaceJobs,
    upsertJob,
    loadInteractionTargets,
    replaceInteractionTargets,
    autoCloseForPersist,
    dbToTarget,
    loadPosts, replacePosts, loadTemplates, replaceTemplates, loadNotifications, replaceNotifications,
    loadArticles, replaceArticles, loadSuggestedGroups, replaceSuggestedGroups, loadSettings, saveSettings,
    loadConfig, saveConfig, loadGroupIntelligence, replaceGroupIntelligence, loadEngine, saveEngine
};
