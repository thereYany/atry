package com.wherelee.base.modules.llm.resilience;

import com.wherelee.base.modules.contracts.llm.LlmError;
import com.wherelee.base.modules.contracts.llm.LlmErrorCategory;
import com.wherelee.base.modules.contracts.llm.LlmException;
import com.wherelee.base.modules.llm.config.LlmProperties;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LlmCircuitBreakerTest {

    private final LlmCircuitBreaker breaker = new LlmCircuitBreaker(new LlmProperties(
            new LlmProperties.Mock(0, "mock-fail", "mock-flaky", "mock-slow", 5000, 128, 8, 10, "mock-stream-mid-fail"),
            new LlmProperties.Timeouts(5000, 45000, 60000, 600000),
            new LlmProperties.Retry(3, 300, 2000, 0.2),
            new LlmProperties.Circuit(50, 2, 100, 2, 0, 60),
            new LlmProperties.RateLimit(600, 6000, 100, "local")));

    @Test
    void opensAfterThresholdAndRecoversAfterOpenWindow() throws InterruptedException {
        String key = "mock:mock-fail";
        breaker.acquire(key);
        breaker.recordFailure(key, error(LlmErrorCategory.UPSTREAM_5XX));
        breaker.acquire(key);
        breaker.recordFailure(key, error(LlmErrorCategory.UPSTREAM_5XX));

        assertThat(breaker.isOpen(key)).isTrue();
        assertThatThrownBy(() -> breaker.acquire(key))
                .isInstanceOfSatisfying(LlmException.class, e ->
                        assertThat(e.error().providerCode()).isEqualTo("CIRCUIT_OPEN"));

        Thread.sleep(150);
        breaker.acquire(key); // HALF_OPEN probe
        breaker.recordSuccess(key);
        assertThat(breaker.isOpen(key)).isFalse();
    }

    @Test
    void clientErrorsDoNotCountAsFailures() {
        String key = "mock:mock-bad";
        breaker.acquire(key);
        breaker.recordFailure(key, error(LlmErrorCategory.INVALID_REQUEST));
        breaker.acquire(key);
        breaker.recordFailure(key, error(LlmErrorCategory.AUTH));
        assertThat(breaker.isOpen(key)).isFalse();
    }

    private LlmError error(LlmErrorCategory category) {
        return new LlmError(category, null, true, false, "test");
    }

    @Test
    void slowCallsOpenCircuitBeforeFailures() {
        LlmCircuitBreaker slowBreaker = new LlmCircuitBreaker(new LlmProperties(
                new LlmProperties.Mock(0, "mock-fail", "mock-flaky", "mock-slow", 5000, 128, 8, 10,
                        "mock-stream-mid-fail"),
                new LlmProperties.Timeouts(5000, 45000, 60000, 600000),
                new LlmProperties.Retry(3, 300, 2000, 0.2),
                new LlmProperties.Circuit(50, 2, 1000, 2, 10, 50),
                new LlmProperties.RateLimit(600, 6000, 100, "local")));

        slowBreaker.recordSuccess("mock:mock-slow", 50);
        slowBreaker.recordSuccess("mock:mock-slow", 50);

        assertThat(slowBreaker.isOpen("mock:mock-slow")).isTrue();
    }
}
