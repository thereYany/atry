package com.wherelee.base.integration;

import com.wherelee.base.modules.payment.PaymentConfirmationService;
import com.wherelee.base.modules.payment.PaymentNotification;
import com.wherelee.base.modules.payment.PaymentOrderRepository;
import com.wherelee.base.modules.payment.PaymentQueryService;
import com.wherelee.base.modules.platform.tenant.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.sql.Timestamp;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 多租户归属回归测试（真库）：系统作用域跨租户定位后，写库必须归属支付单自己的租户。
 *
 * <p>覆盖三个此前会落默认租户 0 的场景：确认回调写发件箱、过期关单写发件箱、
 * 以及按业务键/支付单号查询的请求线程租户隔离。</p>
 */
class PaymentTenantScopeIntegrationTest extends KernelIntegrationTestSupport {

    private static final long PAYMENT_TENANT = 7L;

    @Autowired
    private PaymentConfirmationService confirmationService;
    @Autowired
    private PaymentQueryService paymentQueryService;
    @Autowired
    private PaymentOrderRepository paymentOrderRepository;

    @AfterEach
    void clearTenantContext() {
        TenantContext.clear();
    }

    @Test
    @DisplayName("tenant=7 的支付单确认后，发件箱与事件都归属 tenant=7（修复前为 0）")
    void confirmationWritesOutboxUnderPaymentTenant() {
        String paymentNo = "P-IT-TENANT-7";
        seedPayingPayment(paymentNo, "IT-TENANT-7", PAYMENT_TENANT,
                LocalDateTime.now().plusMinutes(15), LocalDateTime.now().minusMinutes(5), "PAID");
        String tradeNo = "MOCK-" + paymentNo;

        var result = confirmationService.accept(new PaymentNotification(
                "MOCK", "IT:TENANT-7:PAID", "IT-TENANT-7", "PAYMENT_SUCCESS",
                100L, tradeNo, null), paymentNo);

        assertThat(result.success()).isTrue();
        assertThat(outboxTenant(paymentNo)).isEqualTo(PAYMENT_TENANT);
        assertThat(jdbc.queryForObject(
                "SELECT tenant_id FROM t_payment_event WHERE event_key = ?", Long.class,
                "IT:TENANT-7:PAID")).isEqualTo(PAYMENT_TENANT);
        assertThat(TenantContext.currentOrNull()).isNull();
    }

    @Test
    @DisplayName("tenant=7 的过期支付单被查单关单后，PAYMENT_EXPIRED 发件箱归属 tenant=7")
    void expiredQueryWritesOutboxUnderPaymentTenant() {
        String paymentNo = "P-IT-TENANT-EXPIRED";
        // 已过期、且 updated_at 早于查单窗口，扣款中尝试 return NOT_FOUND（mock 网关按 attempt 状态返回）
        seedPayingPayment(paymentNo, "IT-TENANT-EXPIRED", PAYMENT_TENANT,
                LocalDateTime.now().minusMinutes(30), LocalDateTime.now().minusHours(2), "NOT_FOUND");

        int handled = TenantContext.runAsSystem(paymentQueryService::queryPending);

        assertThat(handled).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT status FROM t_payment_order WHERE payment_no = ? AND tenant_id = ?",
                String.class, paymentNo, PAYMENT_TENANT)).isEqualTo("CLOSED");
        assertThat(outboxTenant(paymentNo)).isEqualTo(PAYMENT_TENANT);
        assertThat(jdbc.queryForObject(
                "SELECT event_type FROM t_payment_outbox WHERE aggregate_no = ? AND tenant_id = ?",
                String.class, paymentNo, PAYMENT_TENANT)).isEqualTo("PAYMENT_EXPIRED");
        assertThat(TenantContext.isSystemScope()).isFalse();
        assertThat(TenantContext.currentOrNull()).isNull();
    }

    @Test
    @DisplayName("请求线程按租户隔离：tenant=0 查不到 tenant=1 的支付单，系统作用域可跨租户定位")
    void requestThreadLookupIsTenantIsolated() {
        String paymentNo = "P-IT-TENANT-ISO";
        String bizNo = "IT-TENANT-ISO";
        seedPayingPayment(paymentNo, bizNo, 1L,
                LocalDateTime.now().plusMinutes(15), LocalDateTime.now(), "CREATED");

        TenantContext.set(0L);
        assertThat(paymentOrderRepository.findByPaymentNo(paymentNo)).isEmpty();
        assertThat(paymentOrderRepository.findByBizNo(bizNo)).isEmpty();
        assertThat(paymentOrderRepository.findByBiz("IT_ORDER", bizNo)).isEmpty();
        assertThat(paymentOrderRepository.findByProviderTradeNo("MOCK", "MOCK-" + paymentNo)).isEmpty();

        TenantContext.set(1L);
        assertThat(paymentOrderRepository.findByPaymentNo(paymentNo)).isPresent();
        assertThat(paymentOrderRepository.findByBizNo(bizNo)).isPresent();
        assertThat(paymentOrderRepository.findByBiz("IT_ORDER", bizNo)).isPresent();
        assertThat(paymentOrderRepository.findByProviderTradeNo("MOCK", "MOCK-" + paymentNo)).isPresent();

        TenantContext.clear();
        assertThat(TenantContext.runAsSystem(
                () -> paymentOrderRepository.findByPaymentNo(paymentNo))).isPresent();
    }

    @Test
    @DisplayName("系统作用域 hybrid：回填 tenant=7 后不带租户条件的裸 SQL 自动落到 tenant=7")
    void systemScopeWithBackfilledTenantInjectsOnBareSql() {
        String aggregateNo = "P-HYBRID-BARE";
        try {
            // 裸 INSERT（列里没有 tenant_id，依赖自动注入）：系统作用域 + 回填 7 → 应注入 tenant=7
            TenantContext.runAsSystem(() -> {
                TenantContext.set(PAYMENT_TENANT);
                try {
                    jdbc.update("""
                            INSERT INTO t_payment_outbox
                                (event_type, aggregate_no, payload, status, next_at)
                            VALUES ('PAYMENT_SUCCEEDED', ?, '{}', 'PENDING', CURRENT_TIMESTAMP(3))
                            """, aggregateNo);
                } finally {
                    TenantContext.clear();
                }
            });
            assertThat(jdbc.queryForObject(
                    "SELECT tenant_id FROM t_payment_outbox WHERE aggregate_no = ? AND tenant_id = ?",
                    Long.class, aggregateNo, PAYMENT_TENANT)).isEqualTo(PAYMENT_TENANT);

            // 裸 SELECT（不带 tenant_id 条件）：未回填=跨租户可见；回填 7=可见；回填 8=注入 8 不可见。
            assertThat(countBareOutbox(aggregateNo, null)).isEqualTo(1);
            assertThat(countBareOutbox(aggregateNo, PAYMENT_TENANT)).isEqualTo(1);
            assertThat(countBareOutbox(aggregateNo, 8L)).isZero();
        } finally {
            jdbc.update("DELETE FROM t_payment_outbox WHERE aggregate_no = ? AND tenant_id = ?",
                    aggregateNo, PAYMENT_TENANT);
        }
    }

    /** 执行一条不带租户条件的裸 SQL；tenantId=null 表示系统作用域未回填（跨租户）。 */
    private Integer countBareOutbox(String aggregateNo, Long tenantId) {
        return TenantContext.runAsSystem(() -> {
            if (tenantId == null) {
                return jdbc.queryForObject(
                        "SELECT COUNT(*) FROM t_payment_outbox WHERE aggregate_no = ?",
                        Integer.class, aggregateNo);
            }
            TenantContext.set(tenantId);
            try {
                return jdbc.queryForObject(
                        "SELECT COUNT(*) FROM t_payment_outbox WHERE aggregate_no = ?",
                        Integer.class, aggregateNo);
            } finally {
                TenantContext.clear();
            }
        });
    }

    private Long outboxTenant(String paymentNo) {
        return jdbc.queryForObject(
                "SELECT tenant_id FROM t_payment_outbox WHERE aggregate_no = ?", Long.class, paymentNo);
    }

    /**
     * 直接落库一条支付单+支付尝试，绕开请求线程（发起支付需要登录租户）。
     *
     * @param attemptProviderStatus mock 网关按此状态返回查单结果（NOT_FOUND/CREATED/PAID...）
     */
    private void seedPayingPayment(String paymentNo, String bizNo, long tenantId,
                                   LocalDateTime expireAt, LocalDateTime updatedAt,
                                   String attemptProviderStatus) {
        jdbc.update("""
                INSERT INTO t_payment_order
                    (payment_no, app_id, biz_type, biz_no, tenant_id, subject, amount_cents, currency,
                     status, channel, provider_trade_no, expire_at, source_updated_at, updated_at)
                VALUES (?, 'atry', 'IT_ORDER', ?, ?, '多租户归属测试', 100, 'CNY',
                        'PAYING', 'MOCK', ?, ?, NOW(3), ?)
                """, paymentNo, bizNo, tenantId, "MOCK-" + paymentNo,
                Timestamp.valueOf(expireAt), Timestamp.valueOf(updatedAt));
        jdbc.update("""
                INSERT INTO t_payment_attempt
                    (tenant_id, payment_no, attempt_no, channel, status, provider_trade_no,
                     provider_status, provider_amount_cents, request_digest)
                VALUES (?, ?, 1, 'MOCK', 'PROCESSING', ?, ?, 100, 'digest')
                """, tenantId, paymentNo, "MOCK-" + paymentNo, attemptProviderStatus);
    }
}
