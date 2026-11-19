package com.wherelee.base.modules.llm.resilience;

import com.wherelee.base.modules.contracts.llm.LlmErrorCategory;
import com.wherelee.base.modules.contracts.llm.LlmException;
import com.wherelee.base.modules.llm.config.LlmProperties;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LlmRateLimiterTest {

    @Test
    void limitsTenantRequestsPerMinute() {
        LlmRateLimiter limiter = limiter(1, 6000, 10);
        assertThat(limiter.acquire("mock", 1L)).isEqualTo("mock");
        limiter.release("mock");
        assertThatThrownBy(() -> limiter.acquire("mock", 1L))
                .isInstanceOfSatisfying(LlmException.class,
                        e -> assertThat(e.error().category()).isEqualTo(LlmErrorCategory.RATE_LIMITED));
    }

    @Test
    void limitsProviderInFlight() {
        LlmRateLimiter limiter = limiter(600, 6000, 1);
        limiter.acquire("mock", 1L);
        assertThatThrownBy(() -> limiter.acquire("mock", 1L))
                .isInstanceOfSatisfying(LlmException.class,
                        e -> assertThat(e.error().providerCode()).isEqualTo("BULKHEAD_FULL"));
    }

    private LlmRateLimiter limiter(int tenantRpm, int providerRpm, int inFlight) {
        return new LlmRateLimiter(new LlmProperties(
                new LlmProperties.Mock(0, "mock-fail", "mock-flaky", "mock-slow", 5000, 128, 8, 10, "mock-stream-mid-fail"),
                new LlmProperties.Timeouts(5000, 45000, 60000, 600000),
                new LlmProperties.Retry(3, 300, 2000, 0.2),
                new LlmProperties.Circuit(50, 20, 30000, 5, 0, 60),
                new LlmProperties.RateLimit(tenantRpm, providerRpm, inFlight, "local")));
    }
}
