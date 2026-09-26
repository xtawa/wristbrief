-- 0015: Per-user follower requests for deduplicated in-flight transcript jobs.
--
-- One artifact_jobs row (the "leader", owned by the first requester) performs the
-- expensive fetch/FFmpeg/STT work for one content+language+version. Every other user
-- who asks for the same content while that work is in flight gets a row here instead
-- of the leader's job id, so:
--   * each user polls an id they are allowed to read (their own status/error/attempts),
--   * the underlying work stays exactly one job (no second enqueue),
--   * the follower's access grant and shared-cache charge are applied only when the
--     artifact becomes ready, so no private transcript content leaks before then.
--
-- Billing: a follower reserves transcript_shared at join time
-- (credit_transactions.reference_id = transcript_job_followers.id, multiplier 0.2).
-- The reservation is committed together with the access grant on completion, and
-- released by failJob when the leader fails.

CREATE TABLE IF NOT EXISTS transcript_job_followers (
  id TEXT PRIMARY KEY,
  job_id TEXT NOT NULL,
  user_id TEXT NOT NULL,
  content_id TEXT NOT NULL,
  language TEXT NOT NULL,
  status TEXT NOT NULL,
  attempt_count INTEGER NOT NULL DEFAULT 1,
  error_code TEXT,
  normal_units REAL NOT NULL DEFAULT 0,
  quota_multiplier REAL NOT NULL DEFAULT 0,
  quota_units REAL NOT NULL DEFAULT 0,
  artifact_id TEXT,
  created_at INTEGER NOT NULL,
  updated_at INTEGER NOT NULL,
  UNIQUE (job_id, user_id)
);

CREATE INDEX IF NOT EXISTS idx_transcript_job_followers_user ON transcript_job_followers(user_id);
CREATE INDEX IF NOT EXISTS idx_transcript_job_followers_job ON transcript_job_followers(job_id);
CREATE INDEX IF NOT EXISTS idx_transcript_job_followers_content ON transcript_job_followers(content_id);

-- Account deletion soft-deletes the users row (authServer.deleteAccount) and purges the
-- tables it knows about. Follower rows are per-user data, so mirror that purge at the
-- database layer to guarantee no follower row survives the account that owns it.
CREATE TRIGGER IF NOT EXISTS trg_users_deleted_purge_transcript_followers
AFTER UPDATE OF status ON users
WHEN NEW.status = 'deleted' AND OLD.status IS NOT 'deleted'
BEGIN
  DELETE FROM transcript_job_followers WHERE user_id = OLD.id;
END;

-- A follower request cannot outlive the generation job it joined (for example when the
-- creator's account is deleted while the job is in flight). Fail the follower instead of
-- leaving a queued row pointing at a job that no longer exists, and release the shared
-- reservation so nobody is billed for work that was removed.
CREATE TRIGGER IF NOT EXISTS trg_artifact_jobs_delete_settle_followers
AFTER DELETE ON artifact_jobs
BEGIN
  UPDATE credit_transactions
  SET status = 'RELEASED'
  WHERE operation_type = 'transcript_shared'
    AND status = 'RESERVED'
    AND reference_id IN (SELECT id FROM transcript_job_followers WHERE job_id = OLD.id);

  UPDATE transcript_job_followers
  SET status = 'failed',
      error_code = 'job_removed',
      updated_at = CAST(strftime('%s', 'now') AS INTEGER) * 1000
  WHERE job_id = OLD.id AND status IN ('queued', 'running');
END;
