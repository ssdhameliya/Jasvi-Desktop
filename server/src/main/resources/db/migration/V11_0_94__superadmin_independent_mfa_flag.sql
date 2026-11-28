-- Independent Super Admin MFA flag (YES/NO) separated from company-wide Admin & User MFA policy
INSERT INTO application_setting(setting_key, setting_value, updated_at)
VALUES('security.superadmin.mfa.enabled', 'NO', CURRENT_TIMESTAMP::text)
ON CONFLICT(setting_key) DO NOTHING;
