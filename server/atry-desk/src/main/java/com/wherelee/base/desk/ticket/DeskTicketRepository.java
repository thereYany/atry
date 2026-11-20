package com.wherelee.base.desk.ticket;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.List;
import java.util.Optional;

/**
 * 工单/消息仓储。
 *
 * <p>SQL 全部手写 {@code tenant_id} 条件（内核租户改写器见到 tenant_id 即跳过自动注入），
 * 这样垂直表既能显式表达租户归属，也不依赖内核表清单维护。</p>
 */
@Repository
public class DeskTicketRepository {

    private static final RowMapper<DeskTicket> TICKET_MAPPER = (rs, rowNum) -> new DeskTicket(
            rs.getLong("id"),
            rs.getLong("tenant_id"),
            rs.getString("ticket_no"),
            rs.getLong("user_id"),
            rs.getString("subject"),
            rs.getString("status"),
            rs.getTimestamp("created_at").toLocalDateTime(),
            rs.getTimestamp("updated_at").toLocalDateTime()
    );

    private static final RowMapper<DeskMessage> MESSAGE_MAPPER = (rs, rowNum) -> new DeskMessage(
            rs.getLong("id"),
            rs.getLong("tenant_id"),
            rs.getLong("ticket_id"),
            rs.getString("role"),
            rs.getString("content"),
            rs.getString("llm_request_id"),
            (Integer) rs.getObject("input_tokens"),
            (Integer) rs.getObject("output_tokens"),
            rs.getObject("cost_micros") == null ? null : rs.getLong("cost_micros"),
            rs.getTimestamp("created_at").toLocalDateTime()
    );

    private final JdbcTemplate jdbc;

    public DeskTicketRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public long createTicket(long tenantId, String ticketNo, long userId, String subject) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO t_desk_ticket (tenant_id, ticket_no, user_id, subject, status)
                    VALUES (?, ?, ?, ?, 'AI_SERVING')
                    """, Statement.RETURN_GENERATED_KEYS);
            statement.setLong(1, tenantId);
            statement.setString(2, ticketNo);
            statement.setLong(3, userId);
            statement.setString(4, subject);
            return statement;
        }, keyHolder);
        Number key = keyHolder.getKey();
        if (key == null) {
            throw new IllegalStateException("创建工单后未返回 ID");
        }
        return key.longValue();
    }

    public Optional<DeskTicket> findTicket(long tenantId, String ticketNo) {
        return jdbc.query("""
                SELECT id, tenant_id, ticket_no, user_id, subject, status, created_at, updated_at
                FROM t_desk_ticket
                WHERE tenant_id = ? AND ticket_no = ?
                """, TICKET_MAPPER, tenantId, ticketNo).stream().findFirst();
    }

    public List<DeskTicket> listTickets(long tenantId, long userId, int limit) {
        return jdbc.query("""
                SELECT id, tenant_id, ticket_no, user_id, subject, status, created_at, updated_at
                FROM t_desk_ticket
                WHERE tenant_id = ? AND user_id = ?
                ORDER BY updated_at DESC, id DESC
                LIMIT ?
                """, TICKET_MAPPER, tenantId, userId, limit);
    }

    public void touch(long tenantId, long ticketId) {
        jdbc.update("""
                UPDATE t_desk_ticket
                SET updated_at = CURRENT_TIMESTAMP(3)
                WHERE tenant_id = ? AND id = ?
                """, tenantId, ticketId);
    }

    public long appendMessage(long tenantId, long ticketId, String role, String content) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO t_desk_message (tenant_id, ticket_id, role, content)
                    VALUES (?, ?, ?, ?)
                    """, Statement.RETURN_GENERATED_KEYS);
            statement.setLong(1, tenantId);
            statement.setLong(2, ticketId);
            statement.setString(3, role);
            statement.setString(4, content);
            return statement;
        }, keyHolder);
        Number key = keyHolder.getKey();
        if (key == null) {
            throw new IllegalStateException("写入工单消息后未返回 ID");
        }
        return key.longValue();
    }

    public long appendAssistantMessage(long tenantId, long ticketId, String content, String llmRequestId,
                                       int inputTokens, int outputTokens, long costMicros) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO t_desk_message
                        (tenant_id, ticket_id, role, content, llm_request_id, input_tokens, output_tokens, cost_micros)
                    VALUES (?, ?, 'ASSISTANT', ?, ?, ?, ?, ?)
                    """, Statement.RETURN_GENERATED_KEYS);
            statement.setLong(1, tenantId);
            statement.setLong(2, ticketId);
            statement.setString(3, content);
            statement.setString(4, llmRequestId);
            statement.setInt(5, inputTokens);
            statement.setInt(6, outputTokens);
            statement.setLong(7, costMicros);
            return statement;
        }, keyHolder);
        Number key = keyHolder.getKey();
        if (key == null) {
            throw new IllegalStateException("写入工单回复后未返回 ID");
        }
        return key.longValue();
    }

    public List<DeskMessage> listMessages(long tenantId, long ticketId, int limit) {
        List<DeskMessage> messages = jdbc.query("""
                SELECT id, tenant_id, ticket_id, role, content, llm_request_id,
                       input_tokens, output_tokens, cost_micros, created_at
                FROM t_desk_message
                WHERE tenant_id = ? AND ticket_id = ?
                ORDER BY id DESC
                LIMIT ?
                """, MESSAGE_MAPPER, tenantId, ticketId, limit);
        // 取最近 N 条后再按时间正序返回，便于直接拼装 LLM 上下文
        return messages.reversed();
    }
}
