package com.wherelee.base.integration;

import com.wherelee.base.modules.contracts.llm.ChatMessage;
import com.wherelee.base.modules.contracts.llm.LlmError;
import com.wherelee.base.modules.contracts.llm.LlmErrorCategory;
import com.wherelee.base.modules.contracts.llm.LlmException;
import com.wherelee.base.modules.contracts.llm.LlmOperations;
import com.wherelee.base.modules.contracts.llm.LlmRequest;
import com.wherelee.base.modules.contracts.llm.LlmStreamSink;
import com.wherelee.base.modules.contracts.llm.LlmUsage;
import com.wherelee.base.modules.contracts.metering.BalanceView;
import com.wherelee.base.modules.contracts.metering.MeteringOperations;
import com.wherelee.base.modules.contracts.metering.SubjectRef;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * P7-③ 流式计费证据：流式调用接入预扣/结算——成功按实际 usage 结算；
 * 客户端断开（sink 抛 CANCELLED）时取消上游、释放预扣、不产生扣费。
 */
@Tag("integration")
class LlmStreamBillingIntegrationTest extends KernelIntegrationTestSupport {

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
    @DisplayName("流式成功：chunks 输出、按实际 usage 结算（余额降、冻结归零、账本一条）")
    void streamingCallSettlesActualUsage() {
        LlmRequest request = new LlmRequest("req-stream-billing", 0, 0, 7, "mock-small",
                List.of(new ChatMessage("user", "hello stream")), 32, true,
                Instant.now().plusSeconds(30), "default");
        List<String> chunks = new ArrayList<>();
        AtomicReference<LlmUsage> usage = new AtomicReference<>();

        llm.stream(request, new LlmStreamSink() {
            @Override
            public void onChunk(String delta) {
                chunks.add(delta);
            }

            @Override
            public void onComplete(LlmUsage completedUsage, String finishReason) {
                usage.set(completedUsage);
            }

            @Override
            public void onError(LlmError error) {
                throw new AssertionError("流式不应失败：" + error.message());
            }
        });

        assertThat(chunks).isNotEmpty();
        assertThat(usage.get()).isNotNull();
        assertThat(usage.get().totalTokens()).isPositive();

        BalanceView balance = metering.queryBalance(subject);
        assertThat(balance.frozen()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(balance.balance()).isLessThan(new BigDecimal("10"));
        assertThat(count("t_metering_ledger", "biz_no = ?", "settle:llm:req-stream-billing")).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT status FROM t_llm_call_record WHERE request_id = ?", String.class, "req-stream-billing"))
                .isEqualTo("SUCCESS");
    }

    @Test
    @DisplayName("客户端断开：抛 CANCELLED，释放预扣、不产生扣费/账本")
    void cancelledStreamReleasesReservation() {
        LlmRequest request = new LlmRequest("req-stream-cancel", 0, 0, 7, "mock-small",
                List.of(new ChatMessage("user", "hello stream")), 32, true,
                Instant.now().plusSeconds(30), "default");

        assertThatThrownBy(() -> llm.stream(request, new LlmStreamSink() {
            @Override
            public void onChunk(String delta) {
                throw new LlmException(new LlmError(LlmErrorCategory.CANCELLED, null, false, false,
                        "客户端已断开（测试注入）"));
            }

            @Override
            public void onComplete(LlmUsage usage, String finishReason) {
            }

            @Override
            public void onError(LlmError error) {
            }
        })).isInstanceOfSatisfying(LlmException.class,
                e -> assertThat(e.error().category()).isEqualTo(LlmErrorCategory.CANCELLED));

        BalanceView balance = metering.queryBalance(subject);
        assertThat(balance.balance()).isEqualByComparingTo(new BigDecimal("10"));
        assertThat(balance.frozen()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(count("t_metering_ledger", "biz_no LIKE ?", "settle:llm:req-stream-cancel%")).isZero();
        assertThat(count("t_llm_call_record", "request_id = ?", "req-stream-cancel")).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT status FROM t_metering_reservation WHERE reservation_id = ?",
                String.class, "llm:req-stream-cancel")).isEqualTo("RELEASED");
    }
}
