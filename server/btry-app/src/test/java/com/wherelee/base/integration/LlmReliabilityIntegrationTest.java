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
}
