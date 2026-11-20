-- 垂直域：客服工单与会话消息（M1）
-- 表前缀 t_desk_*；tenant_id 显式保留，M1 按单租户部署（tenant 0）。
CREATE TABLE t_desk_ticket
(
    id         BIGINT       NOT NULL AUTO_INCREMENT,
    tenant_id  BIGINT       NOT NULL DEFAULT 0,
    ticket_no  VARCHAR(32)  NOT NULL COMMENT '工单号（D+时间戳+随机）',
    user_id    BIGINT       NOT NULL COMMENT '提问用户（来自登录态）',
    subject    VARCHAR(120) NOT NULL,
    status     VARCHAR(20)  NOT NULL DEFAULT 'AI_SERVING' COMMENT 'AI_SERVING / WAITING_HUMAN / HUMAN_SERVING / RESOLVED / CLOSED',
    created_at DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_desk_ticket_no (tenant_id, ticket_no),
    KEY idx_desk_ticket_user (tenant_id, user_id, updated_at)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='客服工单';

CREATE TABLE t_desk_message
(
    id             BIGINT      NOT NULL AUTO_INCREMENT,
    tenant_id      BIGINT      NOT NULL DEFAULT 0,
    ticket_id      BIGINT      NOT NULL,
    role           VARCHAR(16) NOT NULL COMMENT 'USER / ASSISTANT / AGENT / SYSTEM',
    content        TEXT        NOT NULL,
    llm_request_id VARCHAR(40) NULL COMMENT '关联内核 t_llm_call_record.request_id',
    input_tokens   INT         NULL,
    output_tokens  INT         NULL,
    cost_micros    BIGINT      NULL COMMENT '该条回复的估算成本（微元）',
    created_at     DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_desk_message_req (tenant_id, llm_request_id),
    KEY idx_desk_message_ticket (tenant_id, ticket_id, id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='工单消息';
