package com.wherelee.base.modules.llm.execution;

import com.wherelee.base.modules.contracts.llm.ChatMessage;
import com.wherelee.base.modules.contracts.llm.LlmError;
import com.wherelee.base.modules.contracts.llm.LlmErrorCategory;
import com.wherelee.base.modules.contracts.llm.LlmRequest;
import com.wherelee.base.modules.contracts.llm.LlmStreamSink;
import com.wherelee.base.modules.contracts.llm.LlmUsage;
import com.wherelee.base.modules.llm.TestProperties;
import com.wherelee.base.modules.llm.config.LlmProperties;
import com.wherelee.base.modules.llm.provider.MockLlmProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;

class LlmStreamExecutorTest {

    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    @AfterEach
    void shutdown() {
        executor.shutdownNow();
    }

    @Test
    void streamsChunksAndCompletes() {
        MockLlmProvider provider = new MockLlmProvider(TestProperties.defaults());
        LlmStreamExecutor streamExecutor = new LlmStreamExecutor(executor);
        CollectingSink sink = new CollectingSink();

        StreamOutcome outcome = streamExecutor.stream(provider, request("req-stream-1", "mock-small"), sink,
                1000, 1000, 5000, System.currentTimeMillis() + 5000);

        assertThat(outcome.completed()).isTrue();
        assertThat(outcome.chunks()).isEqualTo(8);
        assertThat(sink.content.toString()).contains("mock-chunk-0", "mock-chunk-7");
        assertThat(sink.usage.outputTokens()).isEqualTo(16);
        assertThat(sink.error).isNull();
    }

    @Test
    void midStreamFailureReturnsInterruptedWithPartialUsage() {
        MockLlmProvider provider = new MockLlmProvider(TestProperties.defaults());
        LlmStreamExecutor streamExecutor = new LlmStreamExecutor(executor);
        CollectingSink sink = new CollectingSink();

        StreamOutcome outcome = streamExecutor.stream(provider,
                request("req-stream-mid", "mock-stream-mid-fail"), sink,
                1000, 1000, 5000, System.currentTimeMillis() + 5000);

        assertThat(outcome.completed()).isFalse();
        assertThat(outcome.error().category()).isEqualTo(LlmErrorCategory.STREAM_INTERRUPTED);
        assertThat(outcome.chunks()).isGreaterThan(0);
        assertThat(outcome.usage().totalTokens()).isGreaterThan(0);
        assertThat(sink.error).isNotNull();
    }

    @Test
    void firstTokenTimeout() {
        LlmProperties defaults = TestProperties.defaults();
        LlmProperties properties = new LlmProperties(
                new LlmProperties.Mock(0, "mock-fail", "mock-flaky", "mock-slow", 5000, 128, 8, 10,
                        "mock-stream-mid-fail"),
                defaults.timeouts(), defaults.retry(), defaults.circuit(), defaults.rateLimit());
        MockLlmProvider provider = new MockLlmProvider(properties);
        LlmStreamExecutor streamExecutor = new LlmStreamExecutor(executor);
        CollectingSink sink = new CollectingSink();

        StreamOutcome outcome = streamExecutor.stream(provider, request("req-stream-slow", "mock-slow"), sink,
                100, 1000, 5000, System.currentTimeMillis() + 5000);

        assertThat(outcome.completed()).isFalse();
        assertThat(outcome.error().category()).isEqualTo(LlmErrorCategory.TIMEOUT_FIRST_TOKEN);
        assertThat(sink.error).isNotNull();
    }

    private LlmRequest request(String requestId, String model) {
        return new LlmRequest(requestId, 0, 0, 7, model,
                List.of(new ChatMessage("user", "hello")), 64, true, null, null);
    }

    private static final class CollectingSink implements LlmStreamSink {
        private final StringBuilder content = new StringBuilder();
        private LlmUsage usage;
        private LlmError error;

        @Override
        public void onChunk(String delta) {
            content.append(delta);
        }

        @Override
        public void onComplete(LlmUsage usage, String finishReason) {
            this.usage = usage;
        }

        @Override
        public void onError(LlmError error) {
            this.error = error;
        }
    }
}
