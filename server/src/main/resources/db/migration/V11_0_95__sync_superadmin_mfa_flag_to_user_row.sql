-- Synchronize pre-existing SUPER_ADMIN / superadmin row MFA state with the independent security.superadmin.mfa.enabled setting
UPDATE users
   SET mfa_enabled = CASE
           WHEN EXISTS (
               SELECT 1 FROM application_setting
               WHERE setting_key = 'security.superadmin.mfa.enabled'
                 AND UPPER(TRIM(COALESCE(setting_value, ''))) IN ('YES', 'TRUE', '1', 'ON')
           ) THEN 1
           ELSE 0
       END,
       mfa_failed_attempts = 0,
       totp_secret_enc = CASE
           WHEN EXISTS (
               SELECT 1 FROM application_setting
               WHERE setting_key = 'security.superadmin.mfa.enabled'
                 AND UPPER(TRIM(COALESCE(setting_value, ''))) IN ('YES', 'TRUE', '1', 'ON')
           ) THEN totp_secret_enc
           ELSE NULL
       END
 WHERE UPPER(TRIM(COALESCE(role, ''))) = 'SUPER_ADMIN'
    OR LOWER(TRIM(COALESCE(username, ''))) = 'superadmin';

DELETE FROM auth_totp_enrollment_pending
 WHERE user_id IN (
     SELECT id FROM users
     WHERE (UPPER(TRIM(COALESCE(role, ''))) = 'SUPER_ADMIN'
         OR LOWER(TRIM(COALESCE(username, ''))) = 'superadmin')
       AND COALESCE(mfa_enabled, 0) = 0
 );

DELETE FROM auth_totp_login_challenge
 WHERE user_id IN (
     SELECT id FROM users
     WHERE (UPPER(TRIM(COALESCE(role, ''))) = 'SUPER_ADMIN'
         OR LOWER(TRIM(COALESCE(username, ''))) = 'superadmin')
       AND COALESCE(mfa_enabled, 0) = 0
 );
