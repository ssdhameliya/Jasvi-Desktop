-- ============================================================================
-- DSE ERP 11.0.65: Client User Session Log (Per-Login / Logoff Intervals)
-- ============================================================================

CREATE TABLE IF NOT EXISTS client_user_session_log (
    id BIGSERIAL PRIMARY KEY,
    machine_id VARCHAR(64) NOT NULL,
    username VARCHAR(120) NOT NULL,
    role_code VARCHAR(64) DEFAULT 'USER',
    work_date DATE NOT NULL DEFAULT CURRENT_DATE,
    session_start TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    session_end TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    duration_seconds BIGINT NOT NULL DEFAULT 0,
    is_active BOOLEAN NOT NULL DEFAULT TRUE
);

CREATE INDEX IF NOT EXISTS idx_client_user_session_lookup
    ON client_user_session_log(machine_id, username, work_date, session_start);

-- Backfill initial session intervals for existing worked days in client_user_telemetry
INSERT INTO client_user_session_log (
    machine_id, username, role_code, work_date,
    session_start, session_end, duration_seconds, is_active
)
SELECT
    t.machine_id,
    t.username,
    COALESCE(NULLIF(t.role_code, ''), 'USER'),
    t.work_date,
    COALESCE(t.first_login_today, t.last_heartbeat, CURRENT_TIMESTAMP),
    COALESCE(t.last_logout_today, t.last_heartbeat, t.first_login_today, CURRENT_TIMESTAMP),
    COALESCE(t.total_seconds_today, 0),
    (COALESCE(t.is_active_now, FALSE) AND t.last_heartbeat >= CURRENT_TIMESTAMP - INTERVAL '3 minutes')
FROM client_user_telemetry t
WHERE (COALESCE(t.total_seconds_today, 0) > 0 OR t.first_login_today IS NOT NULL)
  AND NOT EXISTS (
      SELECT 1 FROM client_user_session_log s
      WHERE s.machine_id = t.machine_id
        AND LOWER(s.username) = LOWER(t.username)
        AND s.work_date = t.work_date
  );
