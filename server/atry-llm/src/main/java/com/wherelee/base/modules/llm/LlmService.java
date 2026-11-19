package com.wherelee.base.modules.llm;

import com.wherelee.base.modules.contracts.llm.ChatMessage;
import com.wherelee.base.modules.contracts.llm.LlmCallCompleted;
import com.wherelee.base.modules.contracts.llm.LlmError;
import com.wherelee.base.modules.contracts.llm.LlmErrorCategory;
import com.wherelee.base.modules.contracts.llm.LlmException;
import com.wherelee.base.modules.contracts.llm.LlmOperations;
import com.wherelee.base.modules.contracts.llm.LlmRequest;
import com.wherelee.base.modules.contracts.llm.LlmResponse;
import com.wherelee.base.modules.contracts.llm.LlmStreamSink;
import com.wherelee.base.modules.contracts.llm.LlmUsage;
import com.wherelee.base.modules.llm.call.LlmCallRecord;
import com.wherelee.base.modules.llm.call.LlmCallRepository;
import com.wherelee.base.modules.llm.call.LlmCallWriter;
import com.wherelee.base.modules.llm.provider.LlmProvider;
import com.wherelee.base.modules.llm.provider.LlmProviderRegistry;
import com.wherelee.base.modules.platform.common.BizException;
import com.wherelee.base.modules.platform.tracing.TraceSpans;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;

/**
 * P1：最小调用闭环（mock provider）——校验、路由、调用、记录、outbox、结构化日志。
 *
 * <p>P1 范围外（后续迭代）：额度预扣（P4）、超时/重试/熔断/限流（P2）、流式转发（P2/P3）。
 * 日志只含元数据与 usage，不含 prompt/response 内容。</p>
 */
@Service
public class LlmService implements LlmOperations {

    private static final Logger log = LoggerFactory.getLogger(LlmService.class);

    private final LlmProviderRegistry providerRegistry;
    private final LlmCallRepository callRepository;
    private final LlmCallWriter callWriter;
    private final TraceSpans traceSpans;

    public LlmService(LlmProviderRegistry providerRegistry, LlmCallRepository callRepository,
                      LlmCallWriter callWriter, TraceSpans traceSpans) {
        this.providerRegistry = providerRegistry;
        this.callRepository = callRepository;
        this.callWriter = callWriter;
        this.traceSpans = traceSpans;
    }

    @Override
    public LlmResponse invoke(LlmRequest request) {
        validate(request);
        if (callRepository.existsByRequestId(request.requestId())) {
            throw new LlmException(new LlmError(LlmErrorCategory.INVALID_REQUEST, null, false, false,
                    "重复的 requestId：" + request.requestId()));
        }
        LlmProvider provider = providerRegistry.resolve(request.model());
        long startedAt = System.currentTimeMillis();
        log.info("llm.call.started requestId={} tenantId={} appId={} userId={} provider={} model={} stream=false",
                request.requestId(), request.tenantId(), request.appId(), request.userId(),
                provider.id(), request.model());
        try {
            LlmResponse response = traceSpans.trace("llm.invoke",
                    TraceSpans.tags("llm.request_id", request.requestId(),
                            "llm.provider", provider.id(),
                            "llm.model", request.model()),
                    () -> provider.invoke(request));
            long latencyMs = System.currentTimeMillis() - startedAt;
            LlmUsage usage = response.usage() == null
                    ? new LlmUsage(0, 0, 0, 0, 0, LlmUsage.Source.ESTIMATED)
                    : response.usage();
            callWriter.writeSuccess(
                    successRecord(request, provider.id(), response, usage, latencyMs),
                    new LlmCallCompleted(request.requestId(), request.tenantId(), request.appId(),
                            request.userId(), provider.id(), response.modelServed(), usage, latencyMs, Instant.now()));
            log.info("llm.call.finished requestId={} provider={} model={} inputTokens={} outputTokens={} "
                            + "usageSource={} latencyMs={} status=SUCCESS",
                    request.requestId(), provider.id(), response.modelServed(),
                    usage.inputTokens(), usage.outputTokens(), usage.source(), latencyMs);
            return response;
        } catch (LlmException e) {
            long latencyMs = System.currentTimeMillis() - startedAt;
            callWriter.writeFailure(failureRecord(request, provider.id(), e.error(), latencyMs));
            log.warn("llm.call.failed requestId={} provider={} model={} category={} retryable={} billable={} "
                            + "latencyMs={}",
                    request.requestId(), provider.id(), request.model(), e.error().category(),
                    e.error().retryable(), e.error().billable(), latencyMs);
            throw e;
        } catch (RuntimeException e) {
            long latencyMs = System.currentTimeMillis() - startedAt;
            LlmError error = new LlmError(LlmErrorCategory.INTERNAL, "UNEXPECTED", false, false,
                    "LLM 调用内部错误：" + e.getClass().getSimpleName());
            callWriter.writeFailure(failureRecord(request, provider.id(), error, latencyMs));
            log.error("llm.call.failed requestId={} provider={} model={} category=INTERNAL latencyMs={}",
                    request.requestId(), provider.id(), request.model(), latencyMs, e);
            throw new LlmException(error, e);
        }
    }

    @Override
    public void stream(LlmRequest request, LlmStreamSink sink) {
        throw new BizException(HttpStatus.NOT_IMPLEMENTED, "流式调用将在 P2/P3 实现");
    }

    private void validate(LlmRequest request) {
        if (request == null) {
            throw invalid("请求不能为空");
        }
        if (request.requestId() == null || request.requestId().isBlank()) {
            throw invalid("requestId 不能为空");
        }
        if (request.model() == null || request.model().isBlank()) {
            throw invalid("model 不能为空");
        }
        List<ChatMessage> messages = request.messages();
        if (messages == null || messages.isEmpty()) {
            throw invalid("messages 不能为空");
        }
        if (request.maxTokens() != null && request.maxTokens() <= 0) {
            throw invalid("maxTokens 必须大于 0");
        }
        if (request.deadlineAt() != null && !request.deadlineAt().isAfter(Instant.now())) {
            throw invalid("deadlineAt 必须晚于当前时间");
        }
    }

    private LlmException invalid(String message) {
        return new LlmException(new LlmError(LlmErrorCategory.INVALID_REQUEST, null, false, false, message));
    }

    private LlmCallRecord successRecord(LlmRequest request, String providerId, LlmResponse response,
                                        LlmUsage usage, long latencyMs) {
        return new LlmCallRecord(request.requestId(), request.tenantId(), request.appId(), request.userId(),
                "chat", providerId, null, request.model(), response.modelServed(), false, 1, "SUCCESS",
                null, null, null, null,
                usage.inputTokens(), usage.outputTokens(), usage.cachedReadTokens(),
                usage.cachedWriteTokens(), usage.reasoningTokens(), usage.source().name(), latencyMs, null);
    }

    private LlmCallRecord failureRecord(LlmRequest request, String providerId, LlmError error, long latencyMs) {
        return new LlmCallRecord(request.requestId(), request.tenantId(), request.appId(), request.userId(),
                "chat", providerId, null, request.model(), null, false, 1, "FAILED",
                error.category().name(), error.providerCode(), error.retryable(), error.billable(),
                0, 0, 0, 0, 0, null, latencyMs, null);
    }
}
