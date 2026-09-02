-- V3__team_user_default_sync.sql — P2-A4 默认团队同步本地消息表（重试补偿）

-- 背景：团队创建本地事务提交后需调用 user-service 更新用户默认团队（updateDefaultTeam）。
--       原实现为 afterCommit 内「fire-and-forget」调用，若 user-service 瞬时不可用则永久丢失，
--       导致新用户无默认团队（数据不一致）。
-- 方案：本地落一张「待同步」消息表，afterCommit 仅插入 PENDING 行（按 user_id 幂等），
--       由 DefaultTeamSyncRetryTask 定时扫描并调用 updateDefaultTeam，失败按指数退避重试，
--       超过上限置 FAILED。本地事务回滚时 afterCommit 不执行，故不会插入脏行。

CREATE TABLE team_user_default_sync (
    id              BIGINT       NOT NULL AUTO_INCREMENT,
    user_id         BIGINT       NOT NULL,
    team_id         BIGINT       NOT NULL,
    status          VARCHAR(16)  NOT NULL COMMENT 'PENDING-待同步，DONE-已同步，FAILED-最终失败',
    next_retry_time DATETIME(3)  NOT NULL COMMENT '下一次重试时间（<= NOW(3) 表示可立即处理）',
    retry_count     INT          NOT NULL DEFAULT 0 COMMENT '已重试次数',
    idempotency_key VARCHAR(64)  NOT NULL COMMENT '幂等键，值为 "DEFAULT_TEAM:" + user_id，保证每用户仅一条在途记录',
    create_time     DATETIME(3)  NOT NULL,
    update_time     DATETIME(3)  NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_idempotency_key (idempotency_key),
    KEY idx_status_next_retry (status, next_retry_time)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '用户默认团队同步本地消息表（P2-A4 重试补偿）';
