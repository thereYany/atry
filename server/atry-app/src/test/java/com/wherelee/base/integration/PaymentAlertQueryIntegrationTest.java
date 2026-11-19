package com.wherelee.base.integration;

import com.wherelee.base.modules.payment.PaymentAlertQueryService;
import com.wherelee.base.modules.platform.tenant.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;

import java.sql.Timestamp;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * 支付告警查询真库回归测试（P0-1 修复 + P1-2 补测）。
 *
 * <p>覆盖两个真实故障面：</p>
 *
 * <ol>
 *   <li><b>不再抛异常</b>：{@code countSettlementUndeliveredBefore} 修复前的 SQL 带
 *       {@code NOT EXISTS} 子查询且不含 {@code tenant_id}，在 enforce 模式
 *       （{@code app.tenant.enforce=true}，生产默认值）下会被租户重写器判为「无法安全注入」
 *       而抛 {@link IllegalStateException}，导致 {@code PaymentAlertJob} 每轮定时任务必然失败。
 *       修复后系统作用域放行自动注入、SQL 补了同租户关联，查询可正常返回跨租户统计。</li>
 *   <li><b>统计口径</b>：修复后该 SQL 显式带同租户关联 {@code o.tenant_id = p.tenant_id}
 *       （SQL 文本含 tenant_id，租户重写器直接放行），所以请求线程与系统作用域都是跨租户口径；
 *       而 {@code countPayingPaymentBefore} / {@code countRefundRetryExhausted} 的单位 SQL
 *       不带 tenant_id：系统作用域跨租户，请求线程按当前租户隔离。断言按此口径写。</li>
 * </ol>
 *
 * <p>数据隔离：所有单号带本次运行的唯一前缀，{@code @AfterEach} 在系统作用域按前缀精确清理；
 * 基座 {@code @BeforeEach} 另会 TRUNCATE 内核表，两者互为保险。</p>
 */
class PaymentAlertQueryIntegrationTest extends KernelIntegrationTestSupport {

    /** 本次测试运行的唯一标记（时间戳转 36 进制），单号都带它，避免与残留数据/并发运行串号。 */
    private static final String RUN = Long.toString(System.currentTimeMillis() % 100_000_000L, 36).toUpperCase();
    private static final String PAYMENT_PREFIX = "P-IT-ALERTQ-" + RUN;
    private static final String REFUND_PREFIX = "R-IT-ALERTQ-" + RUN;

    @Autowired
    private PaymentAlertQueryService paymentAlertQueryService;

    /** 集成 profile 的退款重试上限；用它造「重试耗尽」数据，避免写死配置值。 */
    @Value("${app.payment.refund-max-attempts:5}")
    private int refundMaxAttempts;

    @AfterEach
    void cleanup() {
        // 先清上下文，再在系统作用域（未回填租户 = 跨租户）按唯一前缀删数据，
        // 避免租户重写器把 DELETE 收窄到默认租户 0 而漏删 1/2 的数据。
        TenantContext.clear();
        TenantContext.runAsSystem(() -> {
            jdbc.update("DELETE FROM t_payment_outbox WHERE aggregate_no LIKE ?", PAYMENT_PREFIX + "%");
            jdbc.update("DELETE FROM t_payment_attempt WHERE payment_no LIKE ?", PAYMENT_PREFIX + "%");
            jdbc.update("DELETE FROM t_payment_event WHERE payment_no LIKE ?", PAYMENT_PREFIX + "%");
            jdbc.update("DELETE FROM t_refund WHERE refund_no LIKE ?", REFUND_PREFIX + "%");
            jdbc.update("DELETE FROM t_payment_order WHERE payment_no LIKE ?", PAYMENT_PREFIX + "%");
        });
        TenantContext.clear();
    }

    @Test
    @DisplayName("系统作用域执行结算未投递统计不抛 IllegalStateException（修复前必抛）")
    void settlementUndeliveredQueryDoesNotThrowInSystemScope() {
        LocalDateTime now = LocalDateTime.now();

        assertThatCode(() -> TenantContext.runAsSystem(
                () -> paymentAlertQueryService.countSettlementUndeliveredBefore(now)))
                .as("NOT EXISTS 子查询 SQL 在系统作用域必须放行自动注入，不能抛 IllegalStateException")
                .doesNotThrowAnyException();

        long counted = TenantContext.runAsSystem(
                () -> paymentAlertQueryService.countSettlementUndeliveredBefore(now));
        assertThat(counted).isZero();
        assertThat(TenantContext.isSystemScope()).isFalse();
        assertThat(TenantContext.currentOrNull()).isNull();
    }

    @Test
    @DisplayName("PAID 无 SENT 发件箱计入；同租户 SENT 后不再计入，异租户 SENT 不得抵消")
    void settlementUndeliveredCountsUnsentOutboxOfSameTenantOnly() {
        LocalDateTime now = LocalDateTime.now();
        long baseline = systemScopeSettlementUndelivered(now);
        String no = paymentNo("SETTLE");

        // 1) tenant=0 的 PAID 单、无发件箱 → 计数 +1
        seedPaidPayment(0L, no, now.minusMinutes(5));
        assertThat(systemScopeSettlementUndelivered(now)).isEqualTo(baseline + 1);
        // 该 SQL 显式含 tenant_id，不会被自动注入：请求线程（默认租户 0）与系统作用域同口径
        assertThat(requestThreadSettlementUndelivered(now)).isEqualTo(baseline + 1);

        // 2) 异租户（tenant=1）的同单号 SENT 发件箱不得把 tenant=0 的未投递单抵消掉
        //    （修复前 SQL 的 NOT EXISTS 少了 o.tenant_id = p.tenant_id，会被跨租户"串单"）
        insertOutbox(1L, no, "PAYMENT_SUCCEEDED", "SENT");
        assertThat(systemScopeSettlementUndelivered(now))
                .as("异租户 SENT 发件箱不得抵消本租户未投递单")
                .isEqualTo(baseline + 1);

        // 3) 补一条同租户（tenant=0）SUCCEEDED 且 SENT 的发件箱 → 不再计入
        insertOutbox(0L, no, "PAYMENT_SUCCEEDED", "SENT");
        assertThat(systemScopeSettlementUndelivered(now)).isEqualTo(baseline);
        assertThat(requestThreadSettlementUndelivered(now)).isEqualTo(baseline);
    }

    @Test
    @DisplayName("多租户统计口径：系统作用域跨租户计入 0/1/2，请求线程 tenant=0 只计本租户")
    void payingAndRefundCountsDifferBetweenSystemScopeAndRequestThread() {
        LocalDateTime cutoff = LocalDateTime.now().minusMinutes(15);
        long payingBaseline = systemScopePaying(cutoff);
        long refundBaseline = systemScopeRefundExhausted();

        // PAYING 超过 cutoff：tenant=0/1/2 各一条（单位 SQL，自动注入按调用方作用域生效）
        for (long tenantId = 0; tenantId <= 2; tenantId++) {
            seedPayingPayment(tenantId, paymentNo("PAYING-" + tenantId), cutoff.minusMinutes(10));
        }
        // 退款重试耗尽：tenant=0/1/2 各一条（attempt_count 达到集成 profile 的退款重试上限）
        for (long tenantId = 0; tenantId <= 2; tenantId++) {
            seedFailedRefund(tenantId, refundNo("REFUND-" + tenantId), refundMaxAttempts);
        }

        // 系统作用域（未回填租户）：跨租户统计，三条都计入
        assertThat(systemScopePaying(cutoff)).isEqualTo(payingBaseline + 3);
        assertThat(systemScopeRefundExhausted()).isEqualTo(refundBaseline + 3);

        // 请求线程（tenant=0）：自动注入 tenant_id 条件，只计本租户
        TenantContext.set(0L);
        try {
            assertThat(paymentAlertQueryService.countPayingPaymentBefore(cutoff))
                    .as("请求线程只能看到 tenant=0 的 PAYING 单")
                    .isEqualTo(payingBaseline + 1);
            assertThat(paymentAlertQueryService.countRefundRetryExhausted())
                    .as("请求线程只能看到 tenant=0 的重试耗尽退款")
                    .isEqualTo(refundBaseline + 1);
        } finally {
            TenantContext.clear();
        }
    }

    // ------------------------------------------------------------------
    // 查询封装：统一收口 TenantContext，避免断言之间上下文残留
    // ------------------------------------------------------------------

    private long systemScopeSettlementUndelivered(LocalDateTime before) {
        return TenantContext.runAsSystem(
                () -> paymentAlertQueryService.countSettlementUndeliveredBefore(before));
    }

    private long requestThreadSettlementUndelivered(LocalDateTime before) {
        TenantContext.set(0L);
        try {
            return paymentAlertQueryService.countSettlementUndeliveredBefore(before);
        } finally {
            TenantContext.clear();
        }
    }

    private long systemScopePaying(LocalDateTime cutoff) {
        return TenantContext.runAsSystem(
                () -> paymentAlertQueryService.countPayingPaymentBefore(cutoff));
    }

    private long systemScopeRefundExhausted() {
        return TenantContext.runAsSystem(paymentAlertQueryService::countRefundRetryExhausted);
    }

    // ------------------------------------------------------------------
    // 造数（直接落库，绕开请求线程发起支付需要登录租户的限制）
    // ------------------------------------------------------------------

    private String paymentNo(String suffix) {
        return PAYMENT_PREFIX + "-" + suffix;
    }

    private String refundNo(String suffix) {
        return REFUND_PREFIX + "-" + suffix;
    }

    /** PAID 支付单：paid_at 早于 cutoff，但尚无 SENT 发件箱。 */
    private void seedPaidPayment(long tenantId, String paymentNo, LocalDateTime paidAt) {
        LocalDateTime now = LocalDateTime.now();
        jdbc.update("""
                INSERT INTO t_payment_order
                    (payment_no, app_id, biz_type, biz_no, tenant_id, subject, amount_cents, currency,
                     status, channel, provider_trade_no, expire_at, paid_at, source_updated_at, updated_at)
                VALUES (?, 'atry', 'IT_ALERTQ', ?, ?, '支付告警查询集成测试', 100, 'CNY',
                        'PAID', 'MOCK', ?, ?, ?, ?, ?)
                """,
                paymentNo, paymentNo, tenantId, "MOCK-" + paymentNo,
                Timestamp.valueOf(now.plusMinutes(15)), Timestamp.valueOf(paidAt),
                Timestamp.valueOf(now), Timestamp.valueOf(now));
    }

    /** PAYING 支付单：updated_at 早于 cutoff，模拟渠道长时间未确认。 */
    private void seedPayingPayment(long tenantId, String paymentNo, LocalDateTime updatedAt) {
        LocalDateTime now = LocalDateTime.now();
        jdbc.update("""
                INSERT INTO t_payment_order
                    (payment_no, app_id, biz_type, biz_no, tenant_id, subject, amount_cents, currency,
                     status, channel, provider_trade_no, expire_at, source_updated_at, updated_at)
                VALUES (?, 'atry', 'IT_ALERTQ', ?, ?, '支付告警查询集成测试', 100, 'CNY',
                        'PAYING', 'MOCK', ?, ?, ?, ?)
                """,
                paymentNo, paymentNo, tenantId, "MOCK-" + paymentNo,
                Timestamp.valueOf(now.plusMinutes(15)), Timestamp.valueOf(now),
                Timestamp.valueOf(updatedAt));
    }

    /** 退款重试耗尽：status=FAILED 且 attempt_count 达到上限。 */
    private void seedFailedRefund(long tenantId, String refundNo, int attemptCount) {
        jdbc.update("""
                INSERT INTO t_refund
                    (tenant_id, refund_no, amount_cents, status, attempt_count, reason, requested_by)
                VALUES (?, ?, 100, 'FAILED', ?, '支付告警查询集成测试', 1)
                """, tenantId, refundNo, attemptCount);
    }

    private void insertOutbox(long tenantId, String aggregateNo, String eventType, String status) {
        jdbc.update("""
                INSERT INTO t_payment_outbox
                    (tenant_id, event_type, aggregate_no, payload, status, sent_at)
                VALUES (?, ?, ?, '{}', ?, CURRENT_TIMESTAMP(3))
                """, tenantId, eventType, aggregateNo, status);
    }
}
