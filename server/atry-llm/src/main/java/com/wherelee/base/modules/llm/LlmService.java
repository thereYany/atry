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
import com.wherelee.base.modules.llm.execution.LlmStreamExecutor;
import com.wherelee.base.modules.llm.execution.StreamOutcome;
import com.wherelee.base.modules.llm.provider.LlmProvider;
import com.wherelee.base.modules.llm.provider.LlmProviderRegistry;
import com.wherelee.base.modules.llm.resilience.LlmCircuitBreaker;
import com.wherelee.base.modules.llm.resilience.LlmRateLimiter;
import com.wherelee.base.modules.llm.resilience.LlmRetryBudget;
import com.wherelee.base.modules.llm.resilience.LlmRetryPolicy;
import com.wherelee.base.modules.llm.observability.LlmEventLogger;
import com.wherelee.base.modules.llm.observability.LlmMetrics;
import com.wherelee.base.modules.llm.observability.LlmSpanTags;
import com.wherelee.base.modules.platform.tracing.TraceSpans;
import io.micrometer.tracing.Span;
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

    private final LlmProviderRegistry providerRegistry;
    private final LlmCallRepository callRepository;
    private final LlmCallWriter callWriter;
    private final TraceSpans traceSpans;
    private final LlmCallExecutor executor;
    private final LlmStreamExecutor streamExecutor;
    private final LlmRetryPolicy retryPolicy;
    private final LlmRetryBudget retryBudget;
    private final LlmCircuitBreaker circuitBreaker;
    private final LlmRateLimiter rateLimiter;
    private final LlmProperties.Timeouts timeouts;
    private final LlmEventLogger events;
    private final LlmMetrics metrics;

    public LlmService(LlmProviderRegistry providerRegistry, LlmCallRepository callRepository,
                      LlmCallWriter callWriter, TraceSpans traceSpans, LlmCallExecutor executor,
                      LlmStreamExecutor streamExecutor,
                      LlmRetryPolicy retryPolicy, LlmRetryBudget retryBudget,
                      LlmCircuitBreaker circuitBreaker, LlmRateLimiter rateLimiter,
                      LlmProperties properties, LlmEventLogger events, LlmMetrics metrics) {
        this.providerRegistry = providerRegistry;
        this.callRepository = callRepository;
        this.callWriter = callWriter;
        this.traceSpans = traceSpans;
        this.executor = executor;
        this.streamExecutor = streamExecutor;
        this.retryPolicy = retryPolicy;
        this.retryBudget = retryBudget;
        this.circuitBreaker = circuitBreaker;
        this.rateLimiter = rateLimiter;
        this.timeouts = properties.timeouts();
        this.events = events;
        this.metrics = metrics;
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
        events.started(request, provider.id());
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
                            span -> invokeWithSpan(span, provider, request, attemptTimeout));
                    long latencyMs = System.currentTimeMillis() - startedAt;
                    LlmUsage usage = response.usage() == null
                            ? new LlmUsage(0, 0, 0, 0, 0, LlmUsage.Source.ESTIMATED)
                            : response.usage();
                    circuitBreaker.recordSuccess(circuitKey, latencyMs);
                    retryBudget.onSuccess(provider.id());
                    callWriter.writeSuccess(
                            successRecord(request, provider.id(), response, usage, latencyMs, attempts, false),
                            new LlmCallCompleted(request.requestId(), request.tenantId(), request.appId(),
                                    request.userId(), provider.id(), response.modelServed(), usage,
                                    latencyMs, Instant.now()));
                    events.finished(request, provider.id(), response, usage, latencyMs, attempts, false);
                    metrics.success(provider.id(), request.model(), false, latencyMs, usage, null);
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
                    events.attemptFailed(request, provider.id(), e.error(), attempt, canRetry,
                            canRetry ? backoff : 0);
                    if (canRetry) {
                        metrics.retry(provider.id(), true);
                    }
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
            events.failed(request, provider.id(), e.error(), latencyMs, attempts, false);
            metrics.failure(provider.id(), request.model(), false, e.error(), latencyMs);
            throw e;
        } catch (RuntimeException e) {
            long latencyMs = System.currentTimeMillis() - startedAt;
            LlmError error = new LlmError(LlmErrorCategory.INTERNAL, "UNEXPECTED", false, false,
                    "LLM 调用内部错误：" + e.getClass().getSimpleName());
            callWriter.writeFailure(failureRecord(request, provider.id(), error, latencyMs, attempts));
            events.failed(request, provider.id(), error, latencyMs, attempts, false);
            metrics.failure(provider.id(), request.model(), false, error, latencyMs);
            throw new LlmException(error, e);
        } finally {
            if (permit != null) {
                rateLimiter.release(permit);
            }
        }
    }

    @Override
    public void stream(LlmRequest request, LlmStreamSink sink) {
        validate(request);
        if (callRepository.existsByRequestId(request.requestId())) {
            throw new LlmException(new LlmError(LlmErrorCategory.INVALID_REQUEST, null, false, false,
                    "重复的 requestId：" + request.requestId()));
        }
        LlmProvider provider = providerRegistry.resolve(request.model());
        String circuitKey = provider.id() + ":" + request.model();
        long startedAt = System.currentTimeMillis();
        long deadlineAt = deadlineMs(request);
        String permit = null;
        boolean persisted = false;
        events.started(request, provider.id());
        try {
            permit = rateLimiter.acquire(provider.id(), request.tenantId());
            circuitBreaker.acquire(circuitKey);
            StreamOutcome outcome = traceSpans.trace("llm.invoke",
                    TraceSpans.tags("llm.request_id", request.requestId(),
                            "llm.provider", provider.id(),
                            "llm.model", request.model(),
                            "llm.stream", "true"),
                    span -> streamWithSpan(span, provider, request, sink, deadlineAt));
            long latencyMs = System.currentTimeMillis() - startedAt;
            if (outcome.completed()) {
                LlmUsage usage = outcome.usage() == null
                        ? new LlmUsage(0, 0, 0, 0, 0, LlmUsage.Source.ESTIMATED)
                        : outcome.usage();
                circuitBreaker.recordSuccess(circuitKey, latencyMs);
                retryBudget.onSuccess(provider.id());
                callWriter.writeSuccess(
                        successRecord(request, provider.id(), responseOf(outcome, request.model()), usage,
                                latencyMs, 1, true),
                        new LlmCallCompleted(request.requestId(), request.tenantId(), request.appId(),
                                request.userId(), provider.id(), request.model(), usage, latencyMs, Instant.now()));
                persisted = true;
                events.finished(request, provider.id(), responseOf(outcome, request.model()), usage,
                        latencyMs, 1, true);
                metrics.success(provider.id(), request.model(), true, latencyMs, usage, outcome.ttftMs());
                return;
            }
            LlmError error = outcome.error();
            circuitBreaker.recordFailure(circuitKey, error);
            String status = error.category() == LlmErrorCategory.CANCELLED ? "CANCELLED"
                    : error.category() == LlmErrorCategory.STREAM_INTERRUPTED ? "INTERRUPTED" : "FAILED";
            callWriter.writeFailure(failureRecord(request, provider.id(), error, latencyMs, 1,
                    outcome.usage(), status));
            persisted = true;
            events.failed(request, provider.id(), error, latencyMs, 1, true);
            metrics.failure(provider.id(), request.model(), true, error, latencyMs);
            throw new LlmException(error);
        } catch (LlmException e) {
            if (!persisted) {
                long latencyMs = System.currentTimeMillis() - startedAt;
                callWriter.writeFailure(failureRecord(request, provider.id(), e.error(), latencyMs, 0));
                events.failed(request, provider.id(), e.error(), latencyMs, 0, true);
                metrics.failure(provider.id(), request.model(), true, e.error(), latencyMs);
            }
            throw e;
        } catch (RuntimeException e) {
            long latencyMs = System.currentTimeMillis() - startedAt;
            LlmError error = new LlmError(LlmErrorCategory.INTERNAL, "UNEXPECTED", false, false,
                    "流式调用内部错误：" + e.getClass().getSimpleName());
            if (!persisted) {
                callWriter.writeFailure(failureRecord(request, provider.id(), error, latencyMs, 0));
            }
            events.failed(request, provider.id(), error, latencyMs, 0, true);
            metrics.failure(provider.id(), request.model(), true, error, latencyMs);
            throw new LlmException(error, e);
        } finally {
            if (permit != null) {
                rateLimiter.release(permit);
            }
        }
    }

    private LlmResponse invokeWithSpan(Span span, LlmProvider provider, LlmRequest request, long timeoutMs) {
        LlmSpanTags.request(span, request, provider.id());
        try {
            LlmResponse response = executor.invoke(provider, request, timeoutMs);
            LlmSpanTags.response(span, response, response.usage());
            return response;
        } catch (LlmException e) {
            LlmSpanTags.error(span, e.error());
            throw e;
        }
    }

    private StreamOutcome streamWithSpan(Span span, LlmProvider provider, LlmRequest request,
                                         LlmStreamSink sink, long deadlineAt) {
        LlmSpanTags.request(span, request, provider.id());
        StreamOutcome outcome = streamExecutor.stream(provider, request, sink,
                timeouts.firstTokenMs(), timeouts.chunkIdleMs(), timeouts.totalMs(), deadlineAt);
        if (outcome.completed()) {
            LlmSpanTags.response(span, null, outcome.usage());
        } else {
            LlmSpanTags.error(span, outcome.error());
        }
        return outcome;
    }

    private LlmResponse responseOf(StreamOutcome outcome, String modelServed) {
        return new LlmResponse(null, null, modelServed, null, outcome.finishReason(), outcome.usage(), false);
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
                                        LlmUsage usage, long latencyMs, int attempts, boolean stream) {
        return new LlmCallRecord(request.requestId(), request.tenantId(), request.appId(), request.userId(),
                "chat", providerId, null, request.model(), response.modelServed(), stream, attempts, "SUCCESS",
                null, null, null, null,
                usage.inputTokens(), usage.outputTokens(), usage.cachedReadTokens(),
                usage.cachedWriteTokens(), usage.reasoningTokens(), usage.source().name(), latencyMs, null);
    }

    private LlmCallRecord failureRecord(LlmRequest request, String providerId, LlmError error,
                                        long latencyMs, int attempts) {
        return failureRecord(request, providerId, error, latencyMs, attempts, null, "FAILED");
    }

    private LlmCallRecord failureRecord(LlmRequest request, String providerId, LlmError error,
                                        long latencyMs, int attempts, LlmUsage usage, String status) {
        return new LlmCallRecord(request.requestId(), request.tenantId(), request.appId(), request.userId(),
                "chat", providerId, null, request.model(), null, "INTERRUPTED".equals(status) || "CANCELLED".equals(status),
                attempts, status,
                error.category().name(), error.providerCode(), error.retryable(), error.billable(),
                usage == null ? 0 : usage.inputTokens(), usage == null ? 0 : usage.outputTokens(),
                usage == null ? 0 : usage.cachedReadTokens(), usage == null ? 0 : usage.cachedWriteTokens(),
                usage == null ? 0 : usage.reasoningTokens(),
                usage == null ? null : usage.source().name(), latencyMs, null);
    }
}
