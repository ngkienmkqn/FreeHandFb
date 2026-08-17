-- Allow executor queue_type 'join' (join-group jobs).
-- Postgres auto-names inline column CHECKs as {table}_{column}_check.
ALTER TABLE fh_executor_jobs
    DROP CONSTRAINT IF EXISTS fh_executor_jobs_queue_type_check;

ALTER TABLE fh_executor_jobs
    ADD CONSTRAINT fh_executor_jobs_queue_type_check
    CHECK (queue_type IN ('interaction', 'publishing', 'join'));
