package com.wherelee.base.desk.web;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.time.LocalDateTime;
import java.util.List;

/** 垂直域 HTTP 契约（只接受业务字段；tenantId/userId 一律取自登录态）。 */
public final class DeskDtos {

    private DeskDtos() {
    }

    /** 新建工单：主题 + 首条问题。 */
    public record CreateTicketRequest(
            @NotBlank(message = "主题不能为空") @Size(max = 120, message = "主题不能超过 120 字符") String subject,
            @NotBlank(message = "问题不能为空") @Size(max = 2000, message = "问题不能超过 2000 字符") String message
    ) {
    }

    /** 追加一条用户消息（AI 回复以 SSE 返回）。 */
    public record SendMessageRequest(
            @NotBlank(message = "消息不能为空") @Size(max = 2000, message = "消息不能超过 2000 字符") String message
    ) {
    }

    public record TicketView(String ticketNo, String subject, String status,
                             LocalDateTime createdAt, LocalDateTime updatedAt) {
    }

    public record MessageView(String role, String content, Integer inputTokens, Integer outputTokens,
                              Long costMicros, LocalDateTime createdAt) {
    }

    public record TicketDetailView(TicketView ticket, List<MessageView> messages) {
    }
}
