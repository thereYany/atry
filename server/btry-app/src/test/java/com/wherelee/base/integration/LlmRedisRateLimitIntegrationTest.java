package com.wherelee.base.integration;

import com.wherelee.base.modules.contracts.llm.LlmException;
import com.wherelee.base.modules.llm.config.LlmProperties;
import com.wherelee.base.modules.llm.observability.LlmMetrics;
import com.wherelee.base.modules.llm.resilience.LlmRateLimiter;
import com.wherelee.base.modules.platform.resilience.RedisFallbackMetrics;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 多实例共享限流的集成证据：两个独立的 {@link LlmRateLimiter} 实例（模拟两个应用实例）
 * 连接同一个 Redis，租户额度必须合计生效，而不是每实例各自一份。
 */
@Tag("integration")
class LlmRedisRateLimitIntegrationTest extends KernelIntegrationTestSupport {

    @Autowired
    private StringRedisTemplate redis;
    @Autowired
    private LlmProperties llmProperties;
    @Autowired
    private MeterRegistry meterRegistry;

    @Test
    @DisplayName("两个实例共享同一租户令牌桶：合计只放行 2 次（rpm=60 的 2 秒容量）")
    void twoInstancesShareTenantQuota() {
        long tenantId = 987654L;
        String provider = "it-provider-" + UUID.randomUUID();
        String tenantKey = "atry:llm:rate:tenant:" + tenantId;
        String providerKey = "atry:llm:rate:provider:" + provider;
        redis.delete(List.of(tenantKey, providerKey));
        try {
            LlmRateLimiter instanceA = limiter("redis", 60);
            LlmRateLimiter instanceB = limiter("redis", 60);

            int allowed = attempt(instanceA, provider, tenantId, 3)
                    + attempt(instanceB, provider, tenantId, 3);

            assertThat(allowed).as("共享桶合计放行数").isEqualTo(2);
            assertThat(redis.getExpire(tenantKey)).as("桶 key 必须有过期时间").isPositive();
        } finally {
            redis.delete(List.of(tenantKey, providerKey));
        }
    }

    @Test
    @DisplayName("本地模式对照：每个实例各自 2 次容量（说明共享模式真实生效）")
    void localBackendAllowsPerInstanceQuota() {
        long tenantId = 987655L;
        String provider = "it-provider-" + UUID.randomUUID();
        String tenantKey = "atry:llm:rate:tenant:" + tenantId;
        redis.delete(tenantKey);
        try {
            LlmRateLimiter instanceA = limiter("local", 60);
            LlmRateLimiter instanceB = limiter("local", 60);

            int allowed = attempt(instanceA, provider, tenantId, 3)
                    + attempt(instanceB, provider, tenantId, 3);

            assertThat(allowed).isEqualTo(4);
            assertThat(redis.hasKey(tenantKey)).isFalse();
        } finally {
            redis.delete(List.of(tenantKey, "atry:llm:rate:provider:" + provider));
        }
    }

    private int attempt(LlmRateLimiter limiter, String provider, long tenantId, int times) {
        int allowed = 0;
        for (int i = 0; i < times; i++) {
            try {
                limiter.acquire(provider, tenantId);
                limiter.release(provider);
                allowed++;
            } catch (LlmException e) {
                // RATE_LIMITED：预期内
            }
        }
        return allowed;
    }

    private LlmRateLimiter limiter(String backend, int tenantRpm) {
        LlmProperties props = new LlmProperties(llmProperties.mock(), llmProperties.timeouts(),
                llmProperties.retry(), llmProperties.circuit(),
                new LlmProperties.RateLimit(tenantRpm, 6000, 10, backend));
        return new LlmRateLimiter(props, redis, new RedisFallbackMetrics(meterRegistry),
                new LlmMetrics(meterRegistry));
    }
}
