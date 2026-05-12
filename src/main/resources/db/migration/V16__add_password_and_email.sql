-- =============================================
-- V16: Add Two-Tier Authentication (Password + PIN) and Email
-- =============================================

ALTER TABLE system.users ADD COLUMN password_hash VARCHAR(255);
ALTER TABLE system.users ADD COLUMN email VARCHAR(150);

-- Migrate existing users to have a default password of "owner123"
-- BCrypt hash generated with cost 12
UPDATE system.users SET password_hash = '$2a$12$qhBuLCJFkG.PJyHvwtqFYeFwN.JOnb1FfFqBJMXDR/DcBSHuozPhi';

-- Enforce NOT NULL for password_hash
ALTER TABLE system.users ALTER COLUMN password_hash SET NOT NULL;
