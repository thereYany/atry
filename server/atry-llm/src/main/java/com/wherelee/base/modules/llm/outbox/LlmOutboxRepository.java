package com.wherelee.base.modules.llm.outbox;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.ObjectMapper;

@Repository
public class LlmOutboxRepository {

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public LlmOutboxRepository(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    public void append(String aggregateId, String eventType, Object payload) {
        jdbc.update("""
                INSERT INTO t_llm_outbox (aggregate_id, event_type, payload, status, next_at)
                VALUES (?, ?, ?, 'PENDING', CURRENT_TIMESTAMP(3))
                """, aggregateId, eventType, objectMapper.writeValueAsString(payload));
    }
}
