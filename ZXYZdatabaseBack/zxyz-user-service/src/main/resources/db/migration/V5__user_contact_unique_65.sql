-- 65 号 §2.8 A-2（🔴）：邮箱/手机号确定性缺陷 —— user 表 email/phone 仅普通索引，
-- bindEmail 无查重 ⇒ 同邮箱双账号合法存在；verify_code 按 (email,scene) upsert 会在
-- 两账号间互相踩踏，未来「按邮箱找回」功能在歧义上炸。
-- NULL 可重复（MySQL UNIQUE 语义），未绑定邮箱/手机的账号不受影响。
-- 上线前先跑：SELECT email, COUNT(*) c FROM user WHERE email IS NOT NULL GROUP BY email HAVING c > 1;
ALTER TABLE user ADD CONSTRAINT uk_user_email UNIQUE (email);
ALTER TABLE user ADD CONSTRAINT uk_user_phone UNIQUE (phone);
