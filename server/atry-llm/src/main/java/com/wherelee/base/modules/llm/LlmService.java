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
import com.wherelee.base.modules.llm.config.LlmProperties;
import com.wherelee.base.modules.llm.execution.LlmCallExecutor;
import com.wherelee.base.modules.llm.provider.LlmProvider;
import com.wherelee.base.modules.llm.provider.LlmProviderRegistry;
import com.wherelee.base.modules.llm.resilience.LlmCircuitBreaker;
import com.wherelee.base.modules.llm.resilience.LlmRateLimiter;
import com.wherelee.base.modules.llm.resilience.LlmRetryBudget;
import com.wherelee.base.modules.llm.resilience.LlmRetryPolicy;
import com.wherelee.base.modules.platform.common.BizException;
import com.wherelee.base.modules.platform.tracing.TraceSpans;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;

/**
 * LLM 调用编排（P2）：校验 → 限流/舱壁 → 熔断 → 单点重试（带预算/退避）→ 调用记录 + outbox + 日志。
 *
 * <p>provider 只负责协议调用；超时、重试、熔断、限流全部在此层，避免重试叠加。
 * 日志只含元数据与 usage，不含 prompt/response 内容。</p>
 */
@Service
public class LlmService implements LlmOperations {

    private static final Logger log = LoggerFactory.getLogger(LlmService.class);

    private final LlmProviderRegistry providerRegistry;
    private final LlmCallRepository callRepository;
    private final LlmCallWriter callWriter;
    private final TraceSpans traceSpans;
    private final LlmCallExecutor executor;
    private final LlmRetryPolicy retryPolicy;
    private final LlmRetryBudget retryBudget;
    private final LlmCircuitBreaker circuitBreaker;
    private final LlmRateLimiter rateLimiter;
    private final LlmProperties.Timeouts timeouts;

    public LlmService(LlmProviderRegistry providerRegistry, LlmCallRepository callRepository,
                      LlmCallWriter callWriter, TraceSpans traceSpans, LlmCallExecutor executor,
                      LlmRetryPolicy retryPolicy, LlmRetryBudget retryBudget,
                      LlmCircuitBreaker circuitBreaker, LlmRateLimiter rateLimiter,
                      LlmProperties properties) {
        this.providerRegistry = providerRegistry;
        this.callRepository = callRepository;
        this.callWriter = callWriter;
        this.traceSpans = traceSpans;
        this.executor = executor;
        this.retryPolicy = retryPolicy;
        this.retryBudget = retryBudget;
        this.circuitBreaker = circuitBreaker;
        this.rateLimiter = rateLimiter;
        this.timeouts = properties.timeouts();
    }

    @Override
    public LlmResponse invoke(LlmRequest request) {
        validate(request);
        if (callRepository.existsByRequestId(request.requestId())) {
            throw new LlmException(new LlmError(LlmErrorCategory.INVALID_REQUEST, null, false, false,
                    "重复的 requestId：" + request.requestId()));
        }
        LlmProvider provider = providerRegistry.resolve(request.model());
        String circuitKey = provider.id() + ":" + request.model();
        long startedAt = System.currentTimeMillis();
        long deadlineAt = deadlineMs(request);
        int attempts = 0;
        String permit = null;
        log.info("llm.call.started requestId={} tenantId={} appId={} userId={} provider={} model={} stream=false",
                request.requestId(), request.tenantId(), request.appId(), request.userId(),
                provider.id(), request.model());
        try {
            permit = rateLimiter.acquire(provider.id(), request.tenantId());
            circuitBreaker.acquire(circuitKey);
            LlmException last = null;
            int maxAttempts = retryPolicy.maxAttempts();
            for (int attempt = 1; attempt <= maxAttempts; attempt++) {
                attempts = attempt;
                long remaining = deadlineAt - System.currentTimeMillis();
                if (remaining <= 0) {
                    last = timeoutError("deadline 已耗尽");
                    break;
                }
                long attemptTimeout = Math.min(Math.min(timeouts.firstTokenMs(), timeouts.totalMs()), remaining);
                try {
                    LlmResponse response = traceSpans.trace("llm.invoke",
                            TraceSpans.tags("llm.request_id", request.requestId(),
                                    "llm.provider", provider.id(),
                                    "llm.model", request.model(),
                                    "llm.attempt", Integer.toString(attempt)),
                            () -> executor.invoke(provider, request, attemptTimeout));
                    long latencyMs = System.currentTimeMillis() - startedAt;
                    LlmUsage usage = response.usage() == null
                            ? new LlmUsage(0, 0, 0, 0, 0, LlmUsage.Source.ESTIMATED)
                            : response.usage();
                    circuitBreaker.recordSuccess(circuitKey);
                    retryBudget.onSuccess(provider.id());
                    callWriter.writeSuccess(
                            successRecord(request, provider.id(), response, usage, latencyMs, attempts),
                            new LlmCallCompleted(request.requestId(), request.tenantId(), request.appId(),
                                    request.userId(), provider.id(), response.modelServed(), usage,
                                    latencyMs, Instant.now()));
                    log.info("llm.call.finished requestId={} provider={} model={} attempt={} inputTokens={} "
                                    + "outputTokens={} usageSource={} latencyMs={} status=SUCCESS",
                            request.requestId(), provider.id(), response.modelServed(), attempt,
                            usage.inputTokens(), usage.outputTokens(), usage.source(), latencyMs);
                    return response;
                } catch (LlmException e) {
                    last = e;
                    circuitBreaker.recordFailure(circuitKey, e.error());
                    long backoff = retryPolicy.backoffMs(attempt);
                    boolean canRetry = attempt < maxAttempts
                            && retryPolicy.retryable(e.error())
                            && !circuitBreaker.isOpen(circuitKey)
                            && retryBudget.tryRetry(provider.id())
                            && System.currentTimeMillis() + backoff < deadlineAt;
                    log.warn("llm.attempt.failed requestId={} provider={} model={} attempt={} category={} "
                                    + "retryable={} willRetry={} backoffMs={}",
                            request.requestId(), provider.id(), request.model(), attempt,
                            e.error().category(), e.error().retryable(), canRetry, canRetry ? backoff : 0);
                    if (!canRetry) {
                        break;
                    }
                    sleepBackoff(backoff);
                }
            }
            throw last == null ? new LlmException(new LlmError(LlmErrorCategory.INTERNAL, "NO_ATTEMPT",
                    false, false, "没有可执行的尝试")) : last;
        } catch (LlmException e) {
            long latencyMs = System.currentTimeMillis() - startedAt;
            callWriter.writeFailure(failureRecord(request, provider.id(), e.error(), latencyMs, attempts));
            log.error("llm.call.failed requestId={} provider={} model={} category={} providerCode={} "
                            + "retryable={} billable={} attempts={} latencyMs={}",
                    request.requestId(), provider.id(), request.model(), e.error().category(),
                    e.error().providerCode(), e.error().retryable(), e.error().billable(), attempts, latencyMs);
            throw e;
        } catch (RuntimeException e) {
            long latencyMs = System.currentTimeMillis() - startedAt;
            LlmError error = new LlmError(LlmErrorCategory.INTERNAL, "UNEXPECTED", false, false,
                    "LLM 调用内部错误：" + e.getClass().getSimpleName());
            callWriter.writeFailure(failureRecord(request, provider.id(), error, latencyMs, attempts));
            log.error("llm.call.failed requestId={} provider={} model={} category=INTERNAL attempts={} latencyMs={}",
                    request.requestId(), provider.id(), request.model(), attempts, latencyMs, e);
            throw new LlmException(error, e);
        } finally {
            if (permit != null) {
                rateLimiter.release(permit);
            }
        }
    }

    @Override
    public void stream(LlmRequest request, LlmStreamSink sink) {
        throw new BizException(HttpStatus.NOT_IMPLEMENTED, "流式调用将在 P2c 实现");
    }

    private long deadlineMs(LlmRequest request) {
        long byTotal = System.currentTimeMillis() + timeouts.totalMs();
        if (request.deadlineAt() != null) {
            return Math.min(byTotal, request.deadlineAt().toEpochMilli());
        }
        return byTotal;
    }

    private LlmException timeoutError(String message) {
        return new LlmException(new LlmError(LlmErrorCategory.TIMEOUT_TOTAL, null, true, false, message));
    }

    private void sleepBackoff(long backoffMs) {
        try {
            Thread.sleep(backoffMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new LlmException(new LlmError(LlmErrorCategory.CANCELLED, null, false, false,
                    "重试等待被中断"), e);
        }
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
                                        LlmUsage usage, long latencyMs, int attempts) {
        return new LlmCallRecord(request.requestId(), request.tenantId(), request.appId(), request.userId(),
                "chat", providerId, null, request.model(), response.modelServed(), false, attempts, "SUCCESS",
                null, null, null, null,
                usage.inputTokens(), usage.outputTokens(), usage.cachedReadTokens(),
                usage.cachedWriteTokens(), usage.reasoningTokens(), usage.source().name(), latencyMs, null);
    }

    private LlmCallRecord failureRecord(LlmRequest request, String providerId, LlmError error,
                                        long latencyMs, int attempts) {
        return new LlmCallRecord(request.requestId(), request.tenantId(), request.appId(), request.userId(),
                "chat", providerId, null, request.model(), null, false, attempts, "FAILED",
                error.category().name(), error.providerCode(), error.retryable(), error.billable(),
                0, 0, 0, 0, 0, null, latencyMs, null);
    }
}
