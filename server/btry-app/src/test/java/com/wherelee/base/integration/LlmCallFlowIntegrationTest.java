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

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Tag("integration")
class LlmCallFlowIntegrationTest extends KernelIntegrationTestSupport {

    @Autowired
    private LlmOperations llm;

    @BeforeEach
    void cleanLlmTables() {
        jdbc.execute("TRUNCATE TABLE t_llm_outbox");
        jdbc.execute("TRUNCATE TABLE t_llm_call_record");
    }

    @Test
    void mockCallPersistsCallRecordAndCompletionOutbox() {
        LlmRequest request = new LlmRequest("req-p1-success", 0, 0, 7, "mock-small",
                List.of(new ChatMessage("user", "hello")), 64, false,
                Instant.now().plusSeconds(30), "budget-test");

        LlmResponse response = llm.invoke(request);

        assertThat(response.requestId()).isEqualTo("req-p1-success");
        assertThat(response.provider()).isEqualTo("mock");
        assertThat(response.usage().inputTokens()).isEqualTo(2);

        Map<String, Object> row = jdbc.queryForMap(
                "SELECT * FROM t_llm_call_record WHERE request_id = ?", "req-p1-success");
        assertThat(row.get("status")).isEqualTo("SUCCESS");
        assertThat(((Number) row.get("input_tokens")).longValue()).isEqualTo(2);
        assertThat(((Number) row.get("output_tokens")).longValue())
                .isEqualTo(response.usage().outputTokens());
        assertThat(row.get("usage_source")).isEqualTo("PROVIDER");
        assertThat(row.get("latency_ms")).isNotNull();

        assertThat(count("t_llm_outbox", "aggregate_id = ?", "req-p1-success")).isEqualTo(1);
        String payload = jdbc.queryForObject(
                "SELECT CAST(payload AS CHAR) FROM t_llm_outbox WHERE aggregate_id = ?",
                String.class, "req-p1-success");
        assertThat(jdbc.queryForObject(
                "SELECT event_type FROM t_llm_outbox WHERE aggregate_id = ?",
                String.class, "req-p1-success")).isEqualTo("LLM_CALL_COMPLETED");
        assertThat(payload).contains("req-p1-success").doesNotContain("hello");
    }

    @Test
    void mockFailurePersistsFailedRecordWithoutOutbox() {
        LlmRequest request = new LlmRequest("req-p1-failure", 0, 0, 7, "mock-fail",
                List.of(new ChatMessage("user", "hello")), 64, false, null, null);

        assertThatThrownBy(() -> llm.invoke(request))
                .isInstanceOfSatisfying(LlmException.class, e -> {
                    assertThat(e.error().category()).isEqualTo(LlmErrorCategory.UPSTREAM_5XX);
                    assertThat(e.error().retryable()).isTrue();
                });

        Map<String, Object> row = jdbc.queryForMap(
                "SELECT * FROM t_llm_call_record WHERE request_id = ?", "req-p1-failure");
        assertThat(row.get("status")).isEqualTo("FAILED");
        assertThat(row.get("error_category")).isEqualTo("UPSTREAM_5XX");
        assertThat(row.get("provider_code")).isEqualTo("MOCK_FAILURE");
        assertThat(count("t_llm_outbox", "aggregate_id = ?", "req-p1-failure")).isZero();
    }

    @Test
    void duplicateRequestIdIsRejected() {
        LlmRequest request = new LlmRequest("req-p1-dup", 0, 0, 7, "mock-small",
                List.of(new ChatMessage("user", "hello")), 64, false, null, null);

        llm.invoke(request);
        assertThatThrownBy(() -> llm.invoke(request))
                .isInstanceOfSatisfying(LlmException.class,
                        e -> assertThat(e.error().category()).isEqualTo(LlmErrorCategory.INVALID_REQUEST));

        assertThat(count("t_llm_call_record", "request_id = ?", "req-p1-dup")).isEqualTo(1);
        assertThat(count("t_llm_outbox", "aggregate_id = ?", "req-p1-dup")).isEqualTo(1);
    }
}
