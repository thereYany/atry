package com.wherelee.base.modules.llm.observability;

import com.wherelee.base.modules.contracts.llm.LlmError;
import com.wherelee.base.modules.contracts.llm.LlmErrorCategory;
import com.wherelee.base.modules.contracts.llm.LlmUsage;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class LlmMetricsTest {

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final LlmMetrics metrics = new LlmMetrics(registry);

    @Test
    void recordsSuccessTokensAndDuration() {
        metrics.success("mock", "mock-small", true, 120,
                new LlmUsage(10, 20, 3, 0, 0, LlmUsage.Source.PROVIDER), 45L);

        assertThat(registry.get("atry.llm.calls").tag("status", "SUCCESS").counter().count()).isEqualTo(1);
        assertThat(registry.get("atry.llm.duration").tag("stream", "true").timer().count()).isEqualTo(1);
        assertThat(registry.get("atry.llm.tokens").tag("token_type", "input").counter().count()).isEqualTo(10);
        assertThat(registry.get("atry.llm.ttft").timer().count()).isEqualTo(1);

        metrics.cost(7L, "mock-small", new BigDecimal("0.000068"));
        assertThat(registry.get("atry.llm.cost").tag("tenant", "7").tag("model", "mock-small")
                .counter().count()).isEqualTo(0.000068, org.assertj.core.data.Offset.offset(1e-12));
    }

    @Test
    void recordsErrorsWithoutSensitiveTags() {
        metrics.failure("mock", "mock-small", false,
                new LlmError(LlmErrorCategory.UPSTREAM_5XX, "MOCK_FAILURE", true, false, "x"), 30);

        assertThat(registry.get("atry.llm.calls").tag("status", "FAILED").counter().count()).isEqualTo(1);
        assertThat(registry.get("atry.llm.errors").tag("category", "UPSTREAM_5XX").counter().count())
                .isEqualTo(1);
    }
}
