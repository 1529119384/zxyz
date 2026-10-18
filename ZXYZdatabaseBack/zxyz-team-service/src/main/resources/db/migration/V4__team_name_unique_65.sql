-- 65 号 §2.8 A-1（🔴）：分布式锁租约击穿的最小兜底。
-- LOCK_LEASE=30s 而临界区内含跨服务 HTTP（3×10s 重试）⇒ 并发建同名团队可重复落库。
-- 团队名全局唯一（产品语义：团队名即标识），以唯一索引兜住锁击穿窗口。
-- 上线前先跑：SELECT name, COUNT(*) c FROM team GROUP BY name HAVING c > 1;
ALTER TABLE team ADD CONSTRAINT uk_team_name UNIQUE (name);
