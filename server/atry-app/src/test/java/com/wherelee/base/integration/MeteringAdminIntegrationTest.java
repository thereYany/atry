package com.wherelee.base.integration;

import com.wherelee.base.modules.contracts.metering.BalanceView;
import com.wherelee.base.modules.contracts.metering.InvoiceCommand;
import com.wherelee.base.modules.contracts.metering.InvoiceView;
import com.wherelee.base.modules.contracts.metering.MeteringAdminOperations;
import com.wherelee.base.modules.contracts.metering.MeteringOperations;
import com.wherelee.base.modules.contracts.metering.ReconciliationReport;
import com.wherelee.base.modules.contracts.metering.RechargeCommand;
import com.wherelee.base.modules.contracts.metering.ReserveCommand;
import com.wherelee.base.modules.contracts.metering.SettleCommand;
import com.wherelee.base.modules.contracts.metering.SubjectRef;
import com.wherelee.base.modules.contracts.metering.UsageCommand;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("integration")
class MeteringAdminIntegrationTest extends KernelIntegrationTestSupport {

    @Autowired
    private MeteringOperations metering;
    @Autowired
    private MeteringAdminOperations admin;

    private final SubjectRef subject = new SubjectRef(0, 0, 7);

    @Test
    void rechargeIsIdempotentByBizNo() {
        BalanceView first = admin.recharge(new RechargeCommand(subject, new BigDecimal("20"), "CNY", "recharge-1"));
        BalanceView second = admin.recharge(new RechargeCommand(subject, new BigDecimal("20"), "CNY", "recharge-1"));

        assertThat(first.balance()).isEqualByComparingTo(new BigDecimal("20"));
        assertThat(second.balance()).isEqualByComparingTo(new BigDecimal("20"));
        assertThat(count("t_metering_ledger", "biz_no = ?", "recharge-1")).isEqualTo(1);
    }

    @Test
    void generatesInvoiceAndBalancedReconciliation() {
        admin.recharge(new RechargeCommand(subject, new BigDecimal("20"), "CNY", "recharge-2"));
        metering.reserve(new ReserveCommand("res-inv-1", subject, new BigDecimal("0.001"), "CNY",
                Instant.now().plusSeconds(3600), null));
        metering.settle(new SettleCommand("res-inv-1", new BigDecimal("0.001"), "CNY", "inv-1"));
        metering.recordUsage(new UsageCommand("evt-inv-1", "llm.tokens", subject,
                new BigDecimal("1000"), "token", Map.of("model", "mock-small"),
                Instant.now(), "req-inv-1"));

        Instant from = Instant.now().minusSeconds(60);
        Instant to = Instant.now().plusSeconds(60);
        InvoiceView invoice = admin.generateInvoice(new InvoiceCommand(subject, from, to, "CNY"));

        assertThat(invoice).isNotNull();
        assertThat(invoice.totalAmount()).isEqualByComparingTo(new BigDecimal("0.001000"));
        assertThat(invoice.items()).hasSize(1);
        assertThat(invoice.status()).isEqualTo("FINALIZED");
        List<InvoiceView> invoices = admin.queryInvoices(subject, 10);
        assertThat(invoices).hasSize(1);

        ReconciliationReport report = admin.reconcile(from, to);
        assertThat(report.checkedEvents()).isEqualTo(1);
        assertThat(report.checkedCharges()).isEqualTo(1);
        assertThat(report.balanced()).isTrue();
    }

    @Test
    void expiresOverdueReservationAndReleasesFrozen() {
        admin.recharge(new RechargeCommand(subject, new BigDecimal("20"), "CNY", "recharge-3"));
        Long accountId = jdbc.queryForObject(
                "SELECT id FROM t_metering_account WHERE tenant_id = 0 AND app_id = 0 AND user_id = 7",
                Long.class);
        jdbc.update("UPDATE t_metering_account SET frozen = 1 WHERE id = ?", accountId);
        jdbc.update("""
                        INSERT INTO t_metering_reservation
                            (reservation_id, account_id, amount, currency, status, expires_at)
                        VALUES ('res-expired-1', ?, 1, 'CNY', 'PENDING', DATE_SUB(CURRENT_TIMESTAMP(3), INTERVAL 1 MINUTE))
                        """, accountId);

        int expired = admin.expireReservations(Instant.now());

        assertThat(expired).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT status FROM t_metering_reservation WHERE reservation_id = 'res-expired-1'", String.class))
                .isEqualTo("EXPIRED");
        assertThat(jdbc.queryForObject(
                "SELECT frozen FROM t_metering_account WHERE id = ?", BigDecimal.class, accountId))
                .isEqualByComparingTo(BigDecimal.ZERO);
    }
}
