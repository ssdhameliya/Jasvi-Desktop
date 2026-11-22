-- V11_0_84__reset_shailesh_account.sql
-- Ensure test administrator is unlocked and failure counters reset

UPDATE users SET locked = 0, failed_attempts = 0, lock_reason = 'NONE' WHERE LOWER(username) = 'shailesh';
DELETE FROM login_throttle;
