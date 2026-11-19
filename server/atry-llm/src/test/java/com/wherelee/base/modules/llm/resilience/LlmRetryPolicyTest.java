package com.wherelee.base.modules.llm.resilience;

import com.wherelee.base.modules.contracts.llm.LlmError;
import com.wherelee.base.modules.contracts.llm.LlmErrorCategory;
import com.wherelee.base.modules.llm.TestProperties;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class LlmRetryPolicyTest {

    private final LlmRetryPolicy policy = new LlmRetryPolicy(TestProperties.defaults());

    @Test
    void onlyRetriesTransientCategories() {
        assertThat(policy.retryable(error(LlmErrorCategory.UPSTREAM_5XX))).isTrue();
        assertThat(policy.retryable(error(LlmErrorCategory.TIMEOUT_TOTAL))).isTrue();
        assertThat(policy.retryable(error(LlmErrorCategory.RATE_LIMITED))).isFalse();
        assertThat(policy.retryable(error(LlmErrorCategory.INVALID_REQUEST))).isFalse();
        assertThat(policy.retryable(error(LlmErrorCategory.CONTENT_FILTERED))).isFalse();
    }

    @Test
    void backoffGrowsAndRespectsJitterBounds() {
        long first = policy.backoffMs(1);
        long third = policy.backoffMs(3);
        assertThat(first).isBetween(240L, 360L);
        assertThat(third).isBetween(960L, 1440L);
        assertThat(policy.maxAttempts()).isEqualTo(3);
    }

    private LlmError error(LlmErrorCategory category) {
        return new LlmError(category, null, true, false, "test");
    }
}
