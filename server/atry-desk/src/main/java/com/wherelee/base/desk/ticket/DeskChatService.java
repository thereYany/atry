package com.wherelee.base.desk.ticket;

import com.wherelee.base.desk.billing.DeskBillingSupport;
import com.wherelee.base.desk.config.DeskProperties;
import com.wherelee.base.modules.contracts.llm.ChatMessage;
import com.wherelee.base.modules.contracts.llm.LlmError;
import com.wherelee.base.modules.contracts.llm.LlmException;
import com.wherelee.base.modules.contracts.llm.LlmOperations;
import com.wherelee.base.modules.contracts.llm.LlmRequest;
import com.wherelee.base.modules.contracts.llm.LlmStreamSink;
import com.wherelee.base.modules.contracts.llm.LlmUsage;
import com.wherelee.base.modules.contracts.metering.BalanceView;
import com.wherelee.base.modules.contracts.metering.SubjectRef;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

/**
 * AI 首答链路：工单消息 → 上下文 → {@link LlmOperations#stream} → 落库 + 计量。
 *
 * <p>治理与计费全部由内核 {@code LlmService} 负责（超时/重试/熔断/限流/预扣/结算），
 * 垂直域只负责拼上下文、落消息、展示成本。客户端断开时由 SSE 写入方抛出
 * {@link LlmException}（CANCELLED），内核会取消上游并释放预扣。</p>
 */
@Service
public class DeskChatService {

    private static final Logger log = LoggerFactory.getLogger(DeskChatService.class);
    private static final String BUDGET_REF = "desk";
    private static final long STREAM_DEADLINE_SECONDS = 300L;
    private static final String SYSTEM_PROMPT = """
            你是 atry 的在线客服助手。请用简洁、准确的中文回答用户问题；
            不确定的信息不要编造，涉及订单/退款等操作时提示用户提供必要信息。
            """;

    private final DeskTicketRepository repository;
    private final DeskBillingSupport billing;
    private final DeskProperties properties;
    private final LlmOperations llm;

    public DeskChatService(DeskTicketRepository repository, DeskBillingSupport billing,
                           DeskProperties properties, LlmOperations llm) {
        this.repository = repository;
        this.billing = billing;
        this.properties = properties;
        this.llm = llm;
    }

    /**
     * 同步执行一次 AI 流式回复（调用方放到专用 executor 上并负责 SSE 输出）。
     *
     * <p>成功：落一条 ASSISTANT 消息（含 usage/成本），回调 {@code onDone}；
     * 失败：回调 {@code onError}，不落库（用户消息已落库，可直接重试）。</p>
     */
    public void streamReply(long tenantId, long userId, String ticketNo, String content,
                            DeskChatListener listener) {
        DeskTicket ticket = repository.findTicket(tenantId, ticketNo)
                .filter(found -> found.userId() == userId)
                .orElseThrow(() -> new com.wherelee.base.modules.platform.common.BizException(
                        org.springframework.http.HttpStatus.NOT_FOUND, "工单不存在"));
        repository.appendMessage(tenantId, ticket.id(), "USER", content.trim());
        repository.touch(tenantId, ticket.id());

        String model = properties.defaultModel();
        String requestId = "desk" + UUID.randomUUID().toString().replace("-", "");
        StringBuilder reply = new StringBuilder();
        AtomicReference<LlmUsage> usageRef = new AtomicReference<>();
        AtomicReference<String> finishReasonRef = new AtomicReference<>();

        try {
            billing.ensureAccount(billing.subject(tenantId, userId));
            LlmRequest request = new LlmRequest(requestId, tenantId, properties.appId(), userId, model,
                    history(tenantId, ticket.id()), properties.maxTokens(), true,
                    Instant.now().plusSeconds(STREAM_DEADLINE_SECONDS), BUDGET_REF);
            llm.stream(request, new LlmStreamSink() {
                @Override
                public void onChunk(String delta) {
                    reply.append(delta);
                    listener.onDelta(delta);
                }

                @Override
                public void onComplete(LlmUsage usage, String finishReason) {
                    usageRef.set(usage);
                    finishReasonRef.set(finishReason);
                }

                @Override
                public void onError(LlmError error) {
                    // 错误由内核统一落库/记录后以异常抛出，这里不重复处理
                }
            });
        } catch (LlmException e) {
            log.warn("desk.chat.failed requestId={} ticketNo={} category={} message={}",
                    requestId, ticketNo, e.error().category(), e.error().message());
            listener.onError(e.error().category().name(), e.error().message());
            return;
        } catch (RuntimeException e) {
            log.error("desk.chat.unexpected requestId={} ticketNo={}", requestId, ticketNo, e);
            listener.onError("INTERNAL", "AI 回复内部错误，请稍后重试");
            return;
        }

        LlmUsage usage = usageRef.get() == null
                ? new LlmUsage(0, 0, 0, 0, 0, LlmUsage.Source.ESTIMATED) : usageRef.get();
        BigDecimal cost = billing.cost(usage, model, Instant.now());
        long costMicros = cost.movePointRight(6).setScale(0, java.math.RoundingMode.HALF_UP).longValue();
        repository.appendAssistantMessage(tenantId, ticket.id(), reply.toString(), requestId,
                Math.toIntExact(usage.inputTokens()), Math.toIntExact(usage.outputTokens()), costMicros);
        repository.touch(tenantId, ticket.id());

        BalanceView balance;
        try {
            balance = billing.balance(tenantId, userId);
        } catch (RuntimeException e) {
            balance = null;
        }
        listener.onDone(new DeskReply(requestId, reply.toString(), usage, cost,
                finishReasonRef.get(), balance,
                repository.findTicket(tenantId, ticketNo).map(DeskTicket::status).orElse(ticket.status())));
    }

    /** 组装上下文：系统提示 + 最近 N 条消息（含刚写入的用户消息）。 */
    private List<ChatMessage> history(long tenantId, long ticketId) {
        List<ChatMessage> messages = new java.util.ArrayList<>();
        messages.add(new ChatMessage("system", SYSTEM_PROMPT));
        repository.listMessages(tenantId, ticketId, properties.historyLimit()).stream()
                .filter(message -> "USER".equals(message.role()) || "ASSISTANT".equals(message.role()))
                .forEach(message -> messages.add(new ChatMessage(
                        "USER".equals(message.role()) ? "user" : "assistant", message.content())));
        return messages;
    }

    /** 流式回调：实现方负责把增量写进 SSE；写失败应抛出 LlmException(CANCELLED) 以取消上游。 */
    public interface DeskChatListener {

        void onDelta(String delta);

        void onDone(DeskReply reply);

        void onError(String category, String message);
    }

    /** 一次回复的汇总（requestId 用于关联内核调用记录）。 */
    public record DeskReply(String requestId, String content, LlmUsage usage, BigDecimal cost,
                            String finishReason, BalanceView balance, String ticketStatus) {
    }
}
