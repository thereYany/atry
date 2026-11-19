package com.wherelee.base.modules.llm.outbox;

import com.wherelee.base.modules.contracts.metering.MeteringOperations;
import com.wherelee.base.modules.contracts.metering.SubjectRef;
import com.wherelee.base.modules.contracts.metering.UsageCommand;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;

/**
 * LLM 调用完成事件投递器：轮询 t_llm_outbox，把用量转换为通用 UsageCommand 交给 metering。
 *
 * <p>事件幂等键 = {@code llm-usage:<requestId>}，与 metering 的事件唯一键配合保证不重复计费。
 * P4 单实例轮询；多实例/更高吞吐后续换 MQ 或加抢占锁。</p>
 */
@Component
@ConditionalOnProperty(name = "atry.llm.outbox.enabled", havingValue = "true", matchIfMissing = true)
public class LlmOutboxDispatcher {

    private static final Logger log = LoggerFactory.getLogger(LlmOutboxDispatcher.class);
    private static final String METER_KEY = "llm.tokens";

    private final LlmOutboxRepository repository;
    private final MeteringOperations metering;
    private final ObjectMapper objectMapper;

    public LlmOutboxDispatcher(LlmOutboxRepository repository, MeteringOperations metering,
                               ObjectMapper objectMapper) {
        this.repository = repository;
        this.metering = metering;
        this.objectMapper = objectMapper;
    }

    @Scheduled(fixedDelayString = "${atry.llm.outbox.poll-ms:1000}")
    public void dispatch() {
        for (LlmOutboxRepository.OutboxRow row : repository.findDue(50)) {
            try {
                dispatchOne(row);
                repository.markSent(row.id());
            } catch (RuntimeException e) {
                log.warn("llm.outbox.dispatch_failed id={} aggregateId={} error={}",
                        row.id(), row.aggregateId(), e.getClass().getSimpleName());
                repository.scheduleRetry(row.id(), row.attempts(), e.getMessage());
            }
        }
    }

    private void dispatchOne(LlmOutboxRepository.OutboxRow row) {
        JsonNode event = objectMapper.readTree(row.payload());
        String requestId = event.get("requestId").asString();
        JsonNode usage = event.get("usage");
        long totalTokens = usage == null ? 0 : usage.path("totalTokens").asLong(0);
        if (usage != null && totalTokens == 0) {
            totalTokens = usage.path("inputTokens").asLong(0) + usage.path("outputTokens").asLong(0)
                    + usage.path("cachedReadTokens").asLong(0) + usage.path("cachedWriteTokens").asLong(0)
                    + usage.path("reasoningTokens").asLong(0);
        }
        SubjectRef subject = new SubjectRef(event.path("tenantId").asLong(0),
                event.path("appId").asLong(0), event.path("userId").asLong(0));
        Instant occurredAt = event.has("occurredAt")
                ? Instant.parse(event.get("occurredAt").asString()) : Instant.now();
        String provider = event.path("provider").asString("unknown");
        String model = event.path("modelServed").asString("unknown");

        metering.recordUsage(new UsageCommand(
                "llm-usage:" + requestId,
                METER_KEY,
                subject,
                BigDecimal.valueOf(Math.max(0, totalTokens)),
                "token",
                Map.of("provider", provider, "model", model),
                occurredAt,
                requestId));
    }
}
