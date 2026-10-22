-- ============================================================
-- M1:登录鉴权
-- ============================================================

-- app_user 增加密码哈希(BCrypt)
ALTER TABLE app_user ADD COLUMN password_hash TEXT;

-- 邮箱大小写不敏感唯一
CREATE UNIQUE INDEX ux_app_user_email_lower ON app_user (lower(email));
