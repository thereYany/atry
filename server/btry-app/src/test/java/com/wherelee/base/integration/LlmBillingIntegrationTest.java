package com.wherelee.base.integration;

import com.wherelee.base.modules.contracts.llm.ChatMessage;
import com.wherelee.base.modules.contracts.llm.LlmOperations;
import com.wherelee.base.modules.contracts.llm.LlmRequest;
import com.wherelee.base.modules.contracts.llm.LlmResponse;
import com.wherelee.base.modules.contracts.metering.BalanceView;
import com.wherelee.base.modules.contracts.metering.MeteringOperations;
import com.wherelee.base.modules.contracts.metering.SubjectRef;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("integration")
@TestPropertySource(properties = {
        "atry.llm.outbox.enabled=true",
        "atry.llm.outbox.poll-ms=100"
})
class LlmBillingIntegrationTest extends KernelIntegrationTestSupport {

    @Autowired
    private LlmOperations llm;
    @Autowired
    private MeteringOperations metering;

    private final SubjectRef subject = new SubjectRef(0, 0, 7);

    @BeforeEach
    void seedAccount() {
        jdbc.update("""
                INSERT INTO t_metering_account (tenant_id, app_id, user_id, currency, balance, frozen, status)
                VALUES (0, 0, 7, 'CNY', 10.000000, 0, 'ACTIVE')
                """);
    }

    @Test
    void prepaidReserveSettleAndOutboxIngestionWorkTogether() throws Exception {
        LlmRequest request = new LlmRequest("req-p4-billing", 0, 0, 7, "mock-small",
                List.of(new ChatMessage("user", "hello")), 64, false,
                Instant.now().plusSeconds(30), "default");

        LlmResponse response = llm.invoke(request);

        // 预扣已按实际用量结算：余额下降、冻结归零、账本一条
        BalanceView balance = metering.queryBalance(subject);
        assertThat(balance.frozen()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(balance.balance()).isLessThan(new BigDecimal("10"));
        assertThat(count("t_metering_ledger", "biz_no = ?", "settle:llm:req-p4-billing")).isEqualTo(1);

        // outbox 异步投递到 metering：用量事件 + 计价结果
        waitUntil(() -> count("t_metering_usage_event", "event_id = ?", "llm-usage:req-p4-billing") == 1);
        long tokens = jdbc.queryForObject(
                "SELECT quantity FROM t_metering_usage_event WHERE event_id = ?",
                Long.class, "llm-usage:req-p4-billing");
        assertThat(tokens).isEqualTo(response.usage().totalTokens());
        assertThat(count("t_metering_charge", "event_id = ?", "llm-usage:req-p4-billing")).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT status FROM t_llm_outbox WHERE aggregate_id = ?", String.class, "req-p4-billing"))
                .isEqualTo("SENT");
    }

    private void waitUntil(java.util.function.BooleanSupplier condition) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 5000;
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            Thread.sleep(50);
        }
        throw new AssertionError("等待条件超时");
    }
}
