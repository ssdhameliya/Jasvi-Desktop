-- V11_0_83__clear_stale_login_throttle.sql
-- Clear any stale login throttle entries and unlock test administrator on upgrade

UPDATE users SET locked = 0, failed_attempts = 0, lock_reason = 'NONE' WHERE LOWER(username) = 'shailesh';
DELETE FROM login_throttle;
