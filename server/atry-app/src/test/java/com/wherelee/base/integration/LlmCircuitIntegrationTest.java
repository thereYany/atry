package com.wherelee.base.integration;

import com.wherelee.base.modules.contracts.llm.ChatMessage;
import com.wherelee.base.modules.contracts.llm.LlmException;
import com.wherelee.base.modules.contracts.llm.LlmOperations;
import com.wherelee.base.modules.contracts.llm.LlmRequest;
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
        "atry.llm.circuit.minimum-calls=2",
        "atry.llm.circuit.failure-rate-threshold=50",
        "atry.llm.circuit.open-ms=2000",
        "atry.llm.retry.max-attempts=1"
})
class LlmCircuitIntegrationTest extends KernelIntegrationTestSupport {

    @Autowired
    private LlmOperations llm;

    @BeforeEach
    void cleanLlmTables() {
        jdbc.execute("TRUNCATE TABLE t_llm_outbox");
        jdbc.execute("TRUNCATE TABLE t_llm_call_record");
    }

    @Test
    void opensCircuitAndFailsFastForSameModel() {
        assertThatThrownBy(() -> llm.invoke(request("req-p2-c1", "mock-fail")))
                .isInstanceOf(LlmException.class);
        assertThatThrownBy(() -> llm.invoke(request("req-p2-c2", "mock-fail")))
                .isInstanceOf(LlmException.class);

        assertThatThrownBy(() -> llm.invoke(request("req-p2-c3", "mock-fail")))
                .isInstanceOfSatisfying(LlmException.class,
                        e -> assertThat(e.error().providerCode()).isEqualTo("CIRCUIT_OPEN"));

        Map<String, Object> row = jdbc.queryForMap(
                "SELECT * FROM t_llm_call_record WHERE request_id = ?", "req-p2-c3");
        assertThat(row.get("status")).isEqualTo("FAILED");
        assertThat(row.get("provider_code")).isEqualTo("CIRCUIT_OPEN");
        assertThat(((Number) row.get("attempt_count")).intValue()).isZero();
    }

    private LlmRequest request(String requestId, String model) {
        return new LlmRequest(requestId, 0, 0, 7, model,
                List.of(new ChatMessage("user", "hello")), 64, false, null, null);
    }
}
