package com.wherelee.base.modules.llm.resilience;

import com.wherelee.base.modules.contracts.llm.LlmErrorCategory;
import com.wherelee.base.modules.contracts.llm.LlmException;
import com.wherelee.base.modules.llm.config.LlmProperties;
import com.wherelee.base.modules.llm.observability.LlmMetrics;
import com.wherelee.base.modules.platform.resilience.RedisFallbackMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 多实例共享限流（Redis Lua）的降级契约：
 * Redis 故障 → 本地令牌桶兜底（不 fail-closed）+ 指标；Redis 可用 → 以共享桶结果为准。
 */
class LlmRateLimiterRedisFallbackTest {

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final RedisFallbackMetrics fallbackMetrics = new RedisFallbackMetrics(registry);
    private final LlmMetrics llmMetrics = new LlmMetrics(registry);

    @Test
    void fallsBackToLocalBucketWhenRedisFails() {
        LlmRateLimiter limiter = new LlmRateLimiter(properties(1, 6000, "redis"),
                failingRedis(), fallbackMetrics, llmMetrics);

        assertThat(limiter.acquire("mock", 1L)).isEqualTo("mock");
        limiter.release("mock");
        // 本地兜底桶容量 = max(1, rpm/30) = 1：同一租户第二次被本地限流
        assertThatThrownBy(() -> limiter.acquire("mock", 1L))
                .isInstanceOfSatisfying(LlmException.class,
                        e -> assertThat(e.error().category()).isEqualTo(LlmErrorCategory.RATE_LIMITED));

        assertThat(registry.get("infra.redis.fallback")
                .tag("component", "llm_rate_limit").tag("operation", "redis_error")
                .counter().count()).isGreaterThanOrEqualTo(2d);
    }

    @Test
    void sharedRedisDecisionIsAuthoritativeWhenAvailable() {
        LlmRateLimiter limiter = new LlmRateLimiter(properties(60, 6000, "redis"),
                denyingRedis(), fallbackMetrics, llmMetrics);

        assertThatThrownBy(() -> limiter.acquire("mock", 1L))
                .isInstanceOfSatisfying(LlmException.class, e -> {
                    assertThat(e.error().category()).isEqualTo(LlmErrorCategory.RATE_LIMITED);
                    assertThat(e.error().providerCode()).isEqualTo("TENANT_RATE_LIMITED");
                });
        // Redis 决策生效时不走本地兜底
        assertThat(registry.find("infra.redis.fallback").counter()).isNull();
        assertThat(registry.get("atry.llm.ratelimit.rejected").tag("scope", "tenant").counter().count())
                .isEqualTo(1d);
    }

    @Test
    void localBackendIgnoresRedis() {
        LlmRateLimiter limiter = new LlmRateLimiter(properties(60, 6000, "local"),
                denyingRedis(), fallbackMetrics, llmMetrics);

        assertThat(limiter.acquire("mock", 1L)).isEqualTo("mock");
        limiter.release("mock");
        assertThat(registry.find("infra.redis.fallback").counter()).isNull();
    }

    private StringRedisTemplate failingRedis() {
        return new StringRedisTemplate() {
            @Override
            public <T> T execute(RedisScript<T> script, List<String> keys, Object... args) {
                throw new RedisConnectionFailureException("redis down（测试注入）");
            }
        };
    }

    private StringRedisTemplate denyingRedis() {
        return new StringRedisTemplate() {
            @Override
            @SuppressWarnings("unchecked")
            public <T> T execute(RedisScript<T> script, List<String> keys, Object... args) {
                return (T) Long.valueOf(0L);
            }
        };
    }

    private LlmProperties properties(int tenantRpm, int providerRpm, String backend) {
        return new LlmProperties(
                new LlmProperties.Mock(0, "mock-fail", "mock-flaky", "mock-slow", 5000, 128, 8, 10,
                        "mock-stream-mid-fail"),
                new LlmProperties.Timeouts(5000, 45000, 60000, 600000),
                new LlmProperties.Retry(3, 300, 2000, 0.2),
                new LlmProperties.Circuit(50, 20, 30000, 5, 0, 60),
                new LlmProperties.RateLimit(tenantRpm, providerRpm, 10, backend));
    }
}
