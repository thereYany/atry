package com.wherelee.base.integration;

import com.wherelee.base.modules.contracts.llm.ChatMessage;
import com.wherelee.base.modules.contracts.llm.LlmErrorCategory;
import com.wherelee.base.modules.contracts.llm.LlmException;
import com.wherelee.base.modules.contracts.llm.LlmOperations;
import com.wherelee.base.modules.contracts.llm.LlmRequest;
import com.wherelee.base.modules.contracts.llm.LlmResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Tag("integration")
@TestPropertySource(properties = {
        "atry.llm.timeouts.first-token-ms=200",
        "atry.llm.timeouts.total-ms=2500",
        "atry.llm.retry.max-attempts=3",
        "atry.llm.retry.initial-backoff-ms=50",
        "atry.llm.retry.max-backoff-ms=100"
})
class LlmReliabilityIntegrationTest extends KernelIntegrationTestSupport {

    @Autowired
    private LlmOperations llm;

    @BeforeEach
    void cleanLlmTables() {
        jdbc.execute("TRUNCATE TABLE t_llm_outbox");
        jdbc.execute("TRUNCATE TABLE t_llm_call_record");
    }

    @Test
    void retriesTransientFailureAndSucceeds() {
        LlmRequest request = new LlmRequest("req-p2-retry", 0, 0, 7, "mock-flaky",
                List.of(new ChatMessage("user", "hello")), 64, false, null, null);

        LlmResponse response = llm.invoke(request);

        assertThat(response.requestId()).isEqualTo("req-p2-retry");
        Map<String, Object> row = jdbc.queryForMap(
                "SELECT * FROM t_llm_call_record WHERE request_id = ?", "req-p2-retry");
        assertThat(row.get("status")).isEqualTo("SUCCESS");
        assertThat(((Number) row.get("attempt_count")).intValue()).isEqualTo(2);
        assertThat(count("t_llm_outbox", "aggregate_id = ?", "req-p2-retry")).isEqualTo(1);
    }

    @Test
    void slowProviderTimesOutAndPersistsFailedRecord() {
        LlmRequest request = new LlmRequest("req-p2-timeout", 0, 0, 7, "mock-slow",
                List.of(new ChatMessage("user", "hello")), 64, false, null, null);

        assertThatThrownBy(() -> llm.invoke(request))
                .isInstanceOfSatisfying(LlmException.class,
                        e -> assertThat(e.error().category()).isEqualTo(LlmErrorCategory.TIMEOUT_TOTAL));

        Map<String, Object> row = jdbc.queryForMap(
                "SELECT * FROM t_llm_call_record WHERE request_id = ?", "req-p2-timeout");
        assertThat(row.get("status")).isEqualTo("FAILED");
        assertThat(row.get("error_category")).isEqualTo("TIMEOUT_TOTAL");
        assertThat(((Number) row.get("attempt_count")).intValue()).isEqualTo(3);
        assertThat(count("t_llm_outbox", "aggregate_id = ?", "req-p2-timeout")).isZero();
    }

    @Test
    void streamsChunksAndPersistsSuccess() {
        StringBuilder content = new StringBuilder();
        TestSink sink = new TestSink(content);
        LlmRequest request = new LlmRequest("req-p2-stream", 0, 0, 7, "mock-small",
                List.of(new ChatMessage("user", "hello")), 64, true, null, null);

        llm.stream(request, sink);

        assertThat(content.toString()).contains("mock-chunk-0", "mock-chunk-7");
        assertThat(sink.usage).isNotNull();
        Map<String, Object> row = jdbc.queryForMap(
                "SELECT * FROM t_llm_call_record WHERE request_id = ?", "req-p2-stream");
        assertThat(row.get("status")).isEqualTo("SUCCESS");
        assertThat(row.get("stream")).isEqualTo(true);
        assertThat(((Number) row.get("output_tokens")).longValue()).isEqualTo(16);
        assertThat(count("t_llm_outbox", "aggregate_id = ?", "req-p2-stream")).isEqualTo(1);
    }

    @Test
    void midStreamFailureMarksInterruptedWithPartialUsage() {
        TestSink sink = new TestSink(new StringBuilder());
        LlmRequest request = new LlmRequest("req-p2-stream-mid", 0, 0, 7, "mock-stream-mid-fail",
                List.of(new ChatMessage("user", "hello")), 64, true, null, null);

        assertThatThrownBy(() -> llm.stream(request, sink))
                .isInstanceOfSatisfying(LlmException.class,
                        e -> assertThat(e.error().category()).isEqualTo(LlmErrorCategory.STREAM_INTERRUPTED));

        Map<String, Object> row = jdbc.queryForMap(
                "SELECT * FROM t_llm_call_record WHERE request_id = ?", "req-p2-stream-mid");
        assertThat(row.get("status")).isEqualTo("INTERRUPTED");
        assertThat(((Number) row.get("output_tokens")).longValue()).isGreaterThan(0);
        assertThat(count("t_llm_outbox", "aggregate_id = ?", "req-p2-stream-mid")).isZero();
    }

    @Test
    void firstTokenTimeoutMarksFailed() {
        TestSink sink = new TestSink(new StringBuilder());
        LlmRequest request = new LlmRequest("req-p2-stream-slow", 0, 0, 7, "mock-slow",
                List.of(new ChatMessage("user", "hello")), 64, true, null, null);

        assertThatThrownBy(() -> llm.stream(request, sink))
                .isInstanceOfSatisfying(LlmException.class,
                        e -> assertThat(e.error().category()).isEqualTo(LlmErrorCategory.TIMEOUT_FIRST_TOKEN));

        Map<String, Object> row = jdbc.queryForMap(
                "SELECT * FROM t_llm_call_record WHERE request_id = ?", "req-p2-stream-slow");
        assertThat(row.get("status")).isEqualTo("FAILED");
        assertThat(row.get("error_category")).isEqualTo("TIMEOUT_FIRST_TOKEN");
    }

    private static final class TestSink implements com.wherelee.base.modules.contracts.llm.LlmStreamSink {
        private final StringBuilder content;
        private com.wherelee.base.modules.contracts.llm.LlmUsage usage;

        private TestSink(StringBuilder content) {
            this.content = content;
        }

        @Override
        public void onChunk(String delta) {
            content.append(delta);
        }

        @Override
        public void onComplete(com.wherelee.base.modules.contracts.llm.LlmUsage usage, String finishReason) {
            this.usage = usage;
        }

        @Override
        public void onError(com.wherelee.base.modules.contracts.llm.LlmError error) {
            // 服务层会在失败时抛异常，sink 的 onError 已由执行器调用
        }
    }
}
