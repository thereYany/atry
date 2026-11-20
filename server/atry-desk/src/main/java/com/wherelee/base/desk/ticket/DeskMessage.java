package com.wherelee.base.desk.ticket;

import java.time.LocalDateTime;

/**
 * 工单消息。
 *
 * @param role         USER / ASSISTANT / AGENT / SYSTEM
 * @param llmRequestId 该条回复对应的 LLM 调用 requestId（关联内核 t_llm_call_record，便于对账与排障）
 * @param inputTokens  回复消耗的输入 token（null 表示非 LLM 消息）
 * @param outputTokens 回复消耗的输出 token
 * @param costMicros   该条回复的估算成本（微元，与计量账本口径一致）
 */
public record DeskMessage(long id, long tenantId, long ticketId, String role, String content,
                          String llmRequestId, Integer inputTokens, Integer outputTokens, Long costMicros,
                          LocalDateTime createdAt) {
}
