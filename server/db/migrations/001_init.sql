CREATE TABLE IF NOT EXISTS fh_schema_migrations (
    version TEXT PRIMARY KEY,
    applied_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS fh_users (
    id TEXT PRIMARY KEY,
    username TEXT NOT NULL UNIQUE,
    password_hash TEXT NOT NULL,
    user_group TEXT NOT NULL DEFAULT 'default',
    role TEXT NOT NULL DEFAULT 'user',
    points INTEGER NOT NULL DEFAULT 20,
    phone TEXT NOT NULL DEFAULT '',
    zalo_link TEXT NOT NULL DEFAULT '',
    facebook_name TEXT NOT NULL DEFAULT '',
    device_id TEXT,
    web_device_id TEXT,
    is_locked BOOLEAN NOT NULL DEFAULT false,
    is_debug BOOLEAN NOT NULL DEFAULT false,
    settings JSONB NOT NULL DEFAULT '{}'::jsonb,
    history JSONB NOT NULL DEFAULT '[]'::jsonb,
    max_group_posts_per_day INTEGER NOT NULL DEFAULT 1,
    created_at BIGINT NOT NULL,
    updated_at BIGINT NOT NULL
);

CREATE TABLE IF NOT EXISTS fh_auth_tokens (
    token TEXT PRIMARY KEY,
    user_id TEXT NOT NULL REFERENCES fh_users(id) ON DELETE CASCADE,
    username TEXT NOT NULL,
    user_group TEXT NOT NULL DEFAULT 'default',
    role TEXT NOT NULL DEFAULT 'user',
    created_at BIGINT NOT NULL
);

CREATE TABLE IF NOT EXISTS fh_executor_jobs (
    id TEXT PRIMARY KEY,
    queue_type TEXT NOT NULL CHECK (queue_type IN ('interaction', 'publishing')),
    job_type TEXT NOT NULL,
    user_group TEXT NOT NULL DEFAULT 'default',
    target_post_id TEXT,
    priority TEXT NOT NULL DEFAULT 'NORMAL',
    payload JSONB NOT NULL DEFAULT '{}'::jsonb,
    status TEXT NOT NULL DEFAULT 'QUEUED',
    attempts INTEGER NOT NULL DEFAULT 0,
    created_by TEXT,
    claimed_by TEXT,
    device_id TEXT,
    lease_token TEXT,
    result JSONB NOT NULL DEFAULT '{}'::jsonb,
    last_error TEXT,
    created_at BIGINT NOT NULL,
    updated_at BIGINT NOT NULL,
    scheduled_at BIGINT,
    claimed_at BIGINT,
    heartbeat_at BIGINT,
    irreversible_at BIGINT,
    finished_at BIGINT
);

CREATE INDEX IF NOT EXISTS idx_executor_jobs_claim
    ON fh_executor_jobs (queue_type, status, priority, created_at);

CREATE INDEX IF NOT EXISTS idx_executor_jobs_group
    ON fh_executor_jobs (user_group);

CREATE INDEX IF NOT EXISTS idx_executor_jobs_target
    ON fh_executor_jobs (target_post_id);

CREATE TABLE IF NOT EXISTS fh_interaction_targets (
    id TEXT PRIMARY KEY,
    user_group TEXT NOT NULL DEFAULT 'default',
    group_id TEXT NOT NULL DEFAULT 'default',
    post_url TEXT NOT NULL,
    status TEXT NOT NULL DEFAULT 'RUNNING',
    requirements JSONB NOT NULL DEFAULT '{}'::jsonb,
    comment_pool JSONB NOT NULL DEFAULT '[]'::jsonb,
    allow_repeat_comments BOOLEAN NOT NULL DEFAULT false,
    target_post JSONB NOT NULL DEFAULT '{}'::jsonb,
    speed TEXT NOT NULL DEFAULT 'NORMAL',
    priority TEXT NOT NULL DEFAULT 'NORMAL',
    online_only BOOLEAN NOT NULL DEFAULT true,
    auto_close JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_by TEXT,
    closed_by TEXT,
    close_reason TEXT,
    review_reason TEXT,
    created_at BIGINT NOT NULL,
    updated_at BIGINT NOT NULL,
    last_planned_at BIGINT,
    completed_at BIGINT,
    closed_at BIGINT
);

CREATE INDEX IF NOT EXISTS idx_interaction_targets_group_status
    ON fh_interaction_targets (user_group, status, created_at);

CREATE TABLE IF NOT EXISTS fh_job_events (
    id BIGSERIAL PRIMARY KEY,
    job_id TEXT NOT NULL,
    event_type TEXT NOT NULL,
    message TEXT,
    payload JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at BIGINT NOT NULL
);
