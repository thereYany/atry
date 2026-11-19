package com.wherelee.base.modules.llm;

import com.wherelee.base.modules.contracts.llm.ChatMessage;
import com.wherelee.base.modules.contracts.llm.LlmErrorCategory;
import com.wherelee.base.modules.contracts.llm.LlmException;
import com.wherelee.base.modules.contracts.llm.LlmRequest;
import com.wherelee.base.modules.contracts.llm.LlmResponse;
import com.wherelee.base.modules.llm.config.LlmProperties;
import com.wherelee.base.modules.llm.provider.MockLlmProvider;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MockLlmProviderTest {

    private final MockLlmProvider provider = new MockLlmProvider(
            new LlmProperties(new LlmProperties.Mock(0, "mock-fail", 128)));

    @Test
    void returnsDeterministicUsageAndContent() {
        LlmRequest request = new LlmRequest("req-unit-1", 0, 0, 1, "mock-small",
                List.of(new ChatMessage("user", "hello llm")), 64, false, null, null);

        LlmResponse response = provider.invoke(request);

        assertThat(response.provider()).isEqualTo("mock");
        assertThat(response.usage().inputTokens()).isEqualTo(3); // 9 chars -> ceil(9/4)=3
        assertThat(response.usage().outputTokens()).isBetween(1L, 64L);
        assertThat(response.usage().source()).isEqualTo(com.wherelee.base.modules.contracts.llm.LlmUsage.Source.PROVIDER);
        assertThat(response.content()).startsWith("mock reply: hello llm");
    }

    @Test
    void failureModelRaisesUpstreamError() {
        LlmRequest request = new LlmRequest("req-unit-2", 0, 0, 1, "mock-fail",
                List.of(new ChatMessage("user", "hi")), 64, false, null, null);

        assertThatThrownBy(() -> provider.invoke(request))
                .isInstanceOfSatisfying(LlmException.class, e -> {
                    assertThat(e.error().category()).isEqualTo(LlmErrorCategory.UPSTREAM_5XX);
                    assertThat(e.error().retryable()).isTrue();
                    assertThat(e.error().billable()).isFalse();
                });
    }
}
