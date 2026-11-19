package com.wherelee.base.modules.llm.resilience;

import com.wherelee.base.modules.contracts.llm.LlmError;
import com.wherelee.base.modules.contracts.llm.LlmErrorCategory;
import com.wherelee.base.modules.contracts.llm.LlmException;
import com.wherelee.base.modules.llm.config.LlmProperties;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;

/**
 * 多维限流（P2 本地版）：租户 RPM + provider RPM + 每 provider 在途上限。
 *
 * <p>令牌桶容量取 RPM 的 2 秒量，允许可控突发；在途用 Semaphore 直接限并发。
 * 多实例共享计数（Redis）在 P4/多实例阶段接入，本地兜底逻辑保持一致。</p>
 */
@Component
public class LlmRateLimiter {

    private final int tenantRpm;
    private final int providerRpm;
    private final ConcurrentHashMap<String, TokenBucket> tenantBuckets = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, TokenBucket> providerBuckets = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Semaphore> inFlight = new ConcurrentHashMap<>();
    private final int maxInFlight;

    public LlmRateLimiter(LlmProperties properties) {
        LlmProperties.RateLimit config = properties.rateLimit();
        this.tenantRpm = Math.max(1, config.tenantRpm());
        this.providerRpm = Math.max(1, config.providerRpm());
        this.maxInFlight = Math.max(1, config.maxInFlight());
    }

    /** 申请许可；失败抛出 RATE_LIMITED / UPSTREAM_OVERLOADED。返回的 providerId 用于 release。 */
    public String acquire(String providerId, long tenantId) {
        Semaphore semaphore = inFlight.computeIfAbsent(providerId, ignored -> new Semaphore(maxInFlight));
        if (!semaphore.tryAcquire()) {
            throw new LlmException(new LlmError(LlmErrorCategory.UPSTREAM_OVERLOADED, "BULKHEAD_FULL",
                    false, false, "provider 在途并发已满：" + providerId));
        }
        try {
            if (!tenantBuckets.computeIfAbsent("t:" + tenantId, ignored -> new TokenBucket(tenantRpm)).tryAcquire()) {
                throw new LlmException(new LlmError(LlmErrorCategory.RATE_LIMITED, "TENANT_RATE_LIMITED",
                        true, false, "租户请求速率超限"));
            }
            if (!providerBuckets.computeIfAbsent(providerId, ignored -> new TokenBucket(providerRpm)).tryAcquire()) {
                throw new LlmException(new LlmError(LlmErrorCategory.RATE_LIMITED, "PROVIDER_RATE_LIMITED",
                        true, false, "provider 请求速率超限"));
            }
            return providerId;
        } catch (RuntimeException e) {
            semaphore.release();
            throw e;
        }
    }

    public void release(String providerId) {
        Semaphore semaphore = inFlight.get(providerId);
        if (semaphore != null) {
            semaphore.release();
        }
    }

    /** 令牌桶：容量 = max(1, rpm/30)（约 2 秒突发），按纳秒补充。 */
    static final class TokenBucket {
        private final double capacity;
        private final double tokensPerNano;
        private double tokens;
        private long lastRefillNanos;

        TokenBucket(int rpm) {
            this.capacity = Math.max(1, rpm / 30.0);
            this.tokens = capacity;
            this.tokensPerNano = rpm / 60.0 / 1_000_000_000.0;
            this.lastRefillNanos = System.nanoTime();
        }

        synchronized boolean tryAcquire() {
            long now = System.nanoTime();
            tokens = Math.min(capacity, tokens + (now - lastRefillNanos) * tokensPerNano);
            lastRefillNanos = now;
            if (tokens < 1.0) {
                return false;
            }
            tokens -= 1.0;
            return true;
        }
    }
}
