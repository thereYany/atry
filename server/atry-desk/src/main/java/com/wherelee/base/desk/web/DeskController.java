package com.wherelee.base.desk.web;

import com.wherelee.base.desk.ticket.DeskChatService;
import com.wherelee.base.desk.ticket.DeskMessage;
import com.wherelee.base.desk.ticket.DeskTicket;
import com.wherelee.base.desk.ticket.DeskTicketService;
import com.wherelee.base.modules.contracts.llm.LlmError;
import com.wherelee.base.modules.contracts.llm.LlmErrorCategory;
import com.wherelee.base.modules.contracts.llm.LlmException;
import com.wherelee.base.modules.platform.common.BizException;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;

/**
 * 客服工单入口。
 *
 * <p>tenantId / userId 只从 JWT 登录态读取（JwtAuthFilter 写入 request attribute），
 * 不接受请求体指定——垂直域同样不允许把越权入口交给客户端。
 * AI 回复走 SSE：delta（增量）/ done（用量/成本/余额）/ error。</p>
 */
@RestController
@RequestMapping("/api/desk")
public class DeskController {

    private static final Logger log = LoggerFactory.getLogger(DeskController.class);
    private static final long STREAM_TIMEOUT_MS = 300_000L;

    private final DeskTicketService ticketService;
    private final DeskChatService chatService;
    private final ExecutorService llmCallExecutor;
    private final ObjectMapper objectMapper;

    public DeskController(DeskTicketService ticketService, DeskChatService chatService,
                          @Qualifier("llmCallThreadExecutor") ExecutorService llmCallExecutor,
                          ObjectMapper objectMapper) {
        this.ticketService = ticketService;
        this.chatService = chatService;
        this.llmCallExecutor = llmCallExecutor;
        this.objectMapper = objectMapper;
    }

    /** 建单 + 首条问题。 */
    @PostMapping("/tickets")
    public DeskDtos.TicketDetailView create(@RequestAttribute("tenantId") long tenantId,
                                            @RequestAttribute("userId") long userId,
                                            @Valid @RequestBody DeskDtos.CreateTicketRequest request) {
        DeskTicket ticket = ticketService.create(tenantId, userId, request.subject(), request.message());
        DeskTicketService.DeskTicketDetail detail = ticketService.detail(tenantId, userId, ticket.ticketNo());
        return toView(detail);
    }

    /** 我的工单列表。 */
    @GetMapping("/tickets")
    public List<DeskDtos.TicketView> list(@RequestAttribute("tenantId") long tenantId,
                                          @RequestAttribute("userId") long userId,
                                          @RequestParam(defaultValue = "20") int limit) {
        return ticketService.list(tenantId, userId, limit).stream().map(DeskController::toView).toList();
    }

    /** 工单详情（含消息）。 */
    @GetMapping("/tickets/{ticketNo}")
    public DeskDtos.TicketDetailView detail(@RequestAttribute("tenantId") long tenantId,
                                            @RequestAttribute("userId") long userId,
                                            @PathVariable String ticketNo) {
        return toView(ticketService.detail(tenantId, userId, ticketNo));
    }

    /** 发送消息并流式接收 AI 回复：SSE delta / done / error。 */
    @PostMapping(value = "/tickets/{ticketNo}/messages", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter sendMessage(@RequestAttribute("tenantId") long tenantId,
                                  @RequestAttribute("userId") long userId,
                                  @PathVariable String ticketNo,
                                  @Valid @RequestBody DeskDtos.SendMessageRequest request) {
        SseEmitter emitter = new SseEmitter(STREAM_TIMEOUT_MS);
        llmCallExecutor.execute(() -> runStream(emitter, tenantId, userId, ticketNo, request.message()));
        return emitter;
    }

    private void runStream(SseEmitter emitter, long tenantId, long userId, String ticketNo, String content) {
        try {
            chatService.streamReply(tenantId, userId, ticketNo, content, new DeskChatService.DeskChatListener() {
                @Override
                public void onDelta(String delta) {
                    send(emitter, "delta", Map.of("delta", delta));
                }

                @Override
                public void onDone(DeskChatService.DeskReply reply) {
                    Map<String, Object> payload = new LinkedHashMap<>();
                    payload.put("requestId", reply.requestId());
                    payload.put("content", reply.content());
                    payload.put("finishReason", reply.finishReason());
                    payload.put("usage", Map.of(
                            "inputTokens", reply.usage().inputTokens(),
                            "outputTokens", reply.usage().outputTokens(),
                            "totalTokens", reply.usage().totalTokens(),
                            "source", reply.usage().source().name()));
                    payload.put("cost", reply.cost());
                    if (reply.balance() != null) {
                        payload.put("balance", reply.balance().balance());
                        payload.put("frozen", reply.balance().frozen());
                        payload.put("currency", reply.balance().currency());
                    }
                    trySend(emitter, "done", payload);
                    emitter.complete();
                }

                @Override
                public void onError(String category, String message) {
                    trySend(emitter, "error", Map.of("category", category, "message", message));
                    emitter.complete();
                }
            });
        } catch (BizException e) {
            trySend(emitter, "error", Map.of("category", "BIZ_ERROR", "message", e.getMessage()));
            emitter.complete();
        } catch (RuntimeException e) {
            log.error("desk.chat.unexpected ticketNo={}", ticketNo, e);
            trySend(emitter, "error", Map.of("category", "INTERNAL", "message", "AI 回复内部错误"));
            emitter.complete();
        }
    }

    /** 发送 SSE 事件；客户端断开时转 CANCELLED 向上传播，让内核取消上游并释放预扣。 */
    private void send(SseEmitter emitter, String event, Object data) {
        try {
            emitter.send(SseEmitter.event().name(event)
                    .data(objectMapper.writeValueAsString(data), MediaType.APPLICATION_JSON));
        } catch (IOException | IllegalStateException e) {
            throw new LlmException(new LlmError(LlmErrorCategory.CANCELLED, null, false, false,
                    "客户端已断开：" + e.getClass().getSimpleName()));
        }
    }

    private void trySend(SseEmitter emitter, String event, Object data) {
        try {
            send(emitter, event, data);
        } catch (RuntimeException ignored) {
            // 客户端已断开 / emitter 已结束：不再抛出
        }
    }

    private DeskDtos.TicketDetailView toView(DeskTicketService.DeskTicketDetail detail) {
        return new DeskDtos.TicketDetailView(toView(detail.ticket()),
                detail.messages().stream().map(DeskController::toView).toList());
    }

    private static DeskDtos.TicketView toView(DeskTicket ticket) {
        return new DeskDtos.TicketView(ticket.ticketNo(), ticket.subject(), ticket.status(),
                ticket.createdAt(), ticket.updatedAt());
    }

    private static DeskDtos.MessageView toView(DeskMessage message) {
        return new DeskDtos.MessageView(message.role(), message.content(), message.inputTokens(),
                message.outputTokens(), message.costMicros(), message.createdAt());
    }
}
