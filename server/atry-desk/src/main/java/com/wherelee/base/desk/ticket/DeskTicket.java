package com.wherelee.base.desk.ticket;

import java.time.LocalDateTime;

/**
 * 客服工单（M1：单轮 AI 首答；后续接入人工接管/知识库/满意度）。
 *
 * <p>状态机：AI_SERVING（AI 接待中）→ WAITING_HUMAN / HUMAN_SERVING（人工接管）→ RESOLVED / CLOSED。
 * M1 只落 AI_SERVING，状态字段先占位，避免后续加表。</p>
 */
public record DeskTicket(long id, long tenantId, String ticketNo, long userId, String subject,
                         String status, LocalDateTime createdAt, LocalDateTime updatedAt) {
}
