package com.wherelee.base.integration;

import com.wherelee.base.modules.contracts.metering.BalanceView;
import com.wherelee.base.modules.contracts.metering.MeteringOperations;
import com.wherelee.base.modules.contracts.metering.ReleaseCommand;
import com.wherelee.base.modules.contracts.metering.ReserveCommand;
import com.wherelee.base.modules.contracts.metering.SettleCommand;
import com.wherelee.base.modules.contracts.metering.SubjectRef;
import com.wherelee.base.modules.contracts.metering.UsageCommand;
import com.wherelee.base.modules.contracts.metering.UsageQuery;
import com.wherelee.base.modules.platform.common.BizException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Tag("integration")
class MeteringCoreIntegrationTest extends KernelIntegrationTestSupport {

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
    void usageIsIdempotentAndRated() {
        UsageCommand command = new UsageCommand("evt-p4-1", "llm.tokens", subject,
                new BigDecimal("1000"), "token", Map.of("model", "mock-small"),
                Instant.now(), "req-p4-1");

        metering.recordUsage(command);
        metering.recordUsage(command);

        assertThat(count("t_metering_usage_event", "event_id = ?", "evt-p4-1")).isEqualTo(1);
        assertThat(count("t_metering_charge", "event_id = ?", "evt-p4-1")).isEqualTo(1);
        BigDecimal amount = jdbc.queryForObject(
                "SELECT amount FROM t_metering_charge WHERE event_id = ?", BigDecimal.class, "evt-p4-1");
        assertThat(amount).isEqualByComparingTo(new BigDecimal("0.001000"));
        assertThat(jdbc.queryForObject(
                "SELECT status FROM t_metering_usage_event WHERE event_id = ?", String.class, "evt-p4-1"))
                .isEqualTo("RATED");
    }

    @Test
    void reserveThenSettleDebitsBalanceAndWritesLedger() {
        metering.reserve(new ReserveCommand("res-p4-1", subject, new BigDecimal("3"), "CNY",
                Instant.now().plusSeconds(3600), "req-p4-2"));
        assertFrozen("3");

        metering.settle(new SettleCommand("res-p4-1", new BigDecimal("2"), "CNY", "req-p4-2"));

        BalanceView balance = metering.queryBalance(subject);
        assertThat(balance.balance()).isEqualByComparingTo(new BigDecimal("8"));
        assertThat(balance.frozen()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(count("t_metering_ledger", "biz_no = ?", "settle:res-p4-1")).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT status FROM t_metering_reservation WHERE reservation_id = ?", String.class, "res-p4-1"))
                .isEqualTo("SETTLED");
    }

    @Test
    void releaseReturnsFrozenAmount() {
        metering.reserve(new ReserveCommand("res-p4-2", subject, new BigDecimal("2"), "CNY",
                Instant.now().plusSeconds(3600), "req-p4-3"));
        metering.release(new ReleaseCommand("res-p4-2", "test"));
        assertFrozen("0");
        assertThat(metering.queryBalance(subject).balance()).isEqualByComparingTo(new BigDecimal("10"));
    }

    @Test
    void rejectsReserveBeyondAvailable() {
        assertThatThrownBy(() -> metering.reserve(new ReserveCommand("res-p4-3", subject,
                new BigDecimal("20"), "CNY", Instant.now().plusSeconds(3600), null)))
                .isInstanceOfSatisfying(BizException.class,
                        e -> assertThat(e.status().value()).isEqualTo(402));
    }

    @Test
    void concurrentReserveDoesNotOversell() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Callable<Boolean>> tasks = List.of(
                    () -> reserveQuietly("res-p4-c1", start),
                    () -> reserveQuietly("res-p4-c2", start));
            List<Future<Boolean>> futures = tasks.stream().map(pool::submit).toList();
            start.countDown();
            int success = 0;
            for (Future<Boolean> future : futures) {
                if (future.get()) {
                    success++;
                }
            }
            assertThat(success).isEqualTo(1);
            assertFrozen("8");
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("多实例等价并发预扣：8 个独立事务同时抢余额 10 的账户，每个预扣 3，只能成功 3 次")
    void concurrentReserveAcrossInstancesDoesNotOversell() throws Exception {
        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Callable<Boolean>> tasks = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                final int index = i;
                tasks.add(() -> {
                    start.await();
                    try {
                        metering.reserve(new ReserveCommand("res-multi-" + index, subject,
                                new BigDecimal("3"), "CNY", Instant.now().plusSeconds(3600),
                                "req-multi-" + index));
                        return true;
                    } catch (BizException e) {
                        return false;
                    }
                });
            }
            List<Future<Boolean>> futures = tasks.stream().map(pool::submit).toList();
            start.countDown();
            int success = 0;
            for (Future<Boolean> future : futures) {
                if (future.get()) {
                    success++;
                }
            }
            assertThat(success).as("并发预扣成功次数").isEqualTo(3);
            assertThat(count("t_metering_reservation",
                    "reservation_id LIKE ? AND status = 'PENDING'", "res-multi-%")).isEqualTo(3);
            assertFrozen("9");
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void queriesUsageBySubjectAndTime() {
        metering.recordUsage(new UsageCommand("evt-p4-query", "llm.tokens", subject,
                new BigDecimal("10"), "token", Map.of(), Instant.now(), "req-p4-query"));

        List<com.wherelee.base.modules.contracts.metering.UsageView> usage = metering.queryUsage(
                new UsageQuery(subject, Instant.now().minusSeconds(60), Instant.now().plusSeconds(60), 10));

        assertThat(usage).hasSize(1);
        assertThat(usage.get(0).eventId()).isEqualTo("evt-p4-query");
    }

    private boolean reserveQuietly(String reservationId, CountDownLatch start) {
        try {
            start.await();
            metering.reserve(new ReserveCommand(reservationId, subject, new BigDecimal("8"), "CNY",
                    Instant.now().plusSeconds(3600), null));
            return true;
        } catch (BizException e) {
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private void assertFrozen(String expected) {
        BigDecimal frozen = jdbc.queryForObject(
                "SELECT frozen FROM t_metering_account WHERE tenant_id = 0 AND app_id = 0 AND user_id = 7",
                BigDecimal.class);
        assertThat(frozen).isEqualByComparingTo(new BigDecimal(expected));
    }
}
