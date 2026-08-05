CREATE TABLE IF NOT EXISTS fh_posts (
    id TEXT PRIMARY KEY,
    url TEXT NOT NULL,
    title TEXT,
    status TEXT NOT NULL DEFAULT 'PENDING',
    user_group TEXT NOT NULL DEFAULT 'default',
    owner_name TEXT,
    added_by TEXT,
    added_at BIGINT NOT NULL,
    interacted_at BIGINT,
    is_publishing_group BOOLEAN NOT NULL DEFAULT false,
    interacted_by JSONB NOT NULL DEFAULT '[]'::jsonb,
    verifications JSONB NOT NULL DEFAULT '[]'::jsonb,
    extra JSONB NOT NULL DEFAULT '{}'::jsonb
);
CREATE INDEX IF NOT EXISTS idx_posts_group_status ON fh_posts(user_group, status, added_at);

CREATE TABLE IF NOT EXISTS fh_templates (
    user_group TEXT NOT NULL,
    text TEXT NOT NULL,
    position INTEGER NOT NULL DEFAULT 0,
    PRIMARY KEY(user_group, text)
);

CREATE TABLE IF NOT EXISTS fh_notifications (
    id TEXT PRIMARY KEY,
    user_id TEXT,
    message TEXT NOT NULL DEFAULT '',
    is_read BOOLEAN NOT NULL DEFAULT false,
    created_at BIGINT NOT NULL,
    data JSONB NOT NULL DEFAULT '{}'::jsonb
);
CREATE INDEX IF NOT EXISTS idx_notifications_user ON fh_notifications(user_id, created_at DESC);

CREATE TABLE IF NOT EXISTS fh_articles (
    id TEXT PRIMARY KEY,
    category TEXT,
    title TEXT NOT NULL DEFAULT '',
    content TEXT NOT NULL DEFAULT '',
    images JSONB NOT NULL DEFAULT '[]'::jsonb,
    status TEXT,
    created_at BIGINT,
    updated_at BIGINT,
    data JSONB NOT NULL DEFAULT '{}'::jsonb
);

CREATE TABLE IF NOT EXISTS fh_suggested_groups (
    id TEXT PRIMARY KEY,
    name TEXT NOT NULL,
    url TEXT NOT NULL,
    member_count TEXT,
    status TEXT NOT NULL DEFAULT 'pending',
    added_by TEXT,
    created_at BIGINT NOT NULL,
    data JSONB NOT NULL DEFAULT '{}'::jsonb
);

CREATE TABLE IF NOT EXISTS fh_app_settings (
    singleton BOOLEAN PRIMARY KEY DEFAULT true CHECK(singleton),
    max_group_posts_per_day INTEGER NOT NULL DEFAULT 1,
    last_logs_cleanup BIGINT NOT NULL DEFAULT 0,
    max_group_interaction_fail_streak INTEGER NOT NULL DEFAULT 5,
    group_interaction_pause_minutes INTEGER NOT NULL DEFAULT 60,
    extra JSONB NOT NULL DEFAULT '{}'::jsonb
);

CREATE TABLE IF NOT EXISTS fh_app_config (
    singleton BOOLEAN PRIMARY KEY DEFAULT true CHECK(singleton),
    app_version TEXT NOT NULL DEFAULT '1.0.0',
    apk_url TEXT NOT NULL DEFAULT '',
    changelog TEXT NOT NULL DEFAULT '',
    default_comments JSONB NOT NULL DEFAULT '[]'::jsonb,
    extra JSONB NOT NULL DEFAULT '{}'::jsonb
);

CREATE TABLE IF NOT EXISTS fh_group_intelligence (
    group_id TEXT PRIMARY KEY,
    joined_accounts JSONB NOT NULL DEFAULT '{}'::jsonb,
    account_activity JSONB NOT NULL DEFAULT '{}'::jsonb,
    recent_comments JSONB NOT NULL DEFAULT '[]'::jsonb,
    fail_streak INTEGER NOT NULL DEFAULT 0,
    paused_until BIGINT NOT NULL DEFAULT 0,
    pause_reason TEXT NOT NULL DEFAULT '',
    last_failure_at BIGINT,
    last_failure TEXT,
    updated_at BIGINT NOT NULL,
    extra JSONB NOT NULL DEFAULT '{}'::jsonb
);

CREATE TABLE IF NOT EXISTS fh_engine_state (
    singleton BOOLEAN PRIMARY KEY DEFAULT true CHECK(singleton),
    latest_version TEXT NOT NULL DEFAULT 'v1.0.0',
    js_code TEXT NOT NULL DEFAULT '',
    updated_at BIGINT NOT NULL
);

CREATE TABLE IF NOT EXISTS fh_engine_versions (
    version TEXT PRIMARY KEY,
    anchors JSONB NOT NULL DEFAULT '{}'::jsonb,
    updated_at BIGINT NOT NULL
);

INSERT INTO fh_app_settings(singleton) VALUES(true) ON CONFLICT(singleton) DO NOTHING;
INSERT INTO fh_app_config(singleton) VALUES(true) ON CONFLICT(singleton) DO NOTHING;
INSERT INTO fh_engine_state(singleton, updated_at) VALUES(true, 0) ON CONFLICT(singleton) DO NOTHING;

