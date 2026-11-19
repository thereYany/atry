package com.wherelee.base.modules.payment.outbox;

import com.wherelee.base.modules.payment.api.PaymentSettledEvent;
import com.wherelee.base.modules.payment.api.PaymentSettledListener;
import com.wherelee.base.modules.platform.tenant.TenantContext;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 发件箱投递器租户上下文单测：listener 执行期间必须是 outbox 行自己的租户，结束后恢复。
 */
@ExtendWith(MockitoExtension.class)
class PaymentOutboxDispatcherTest {

    @Mock private PaymentOutboxRepository repository;
    @Mock private PaymentSettledListener listener;

    private SimpleMeterRegistry meterRegistry;
    private PaymentOutboxDispatcher dispatcher;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        dispatcher = new PaymentOutboxDispatcher(repository, List.of(listener), meterRegistry,
                50, 5, 5, 120);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
        meterRegistry.close();
    }

    @Test
    void listenerRunsWithOutboxRowTenantAndRestoresPreviousContext() {
        PaymentOutboxRepository.OutboxRow row = row(1L, 7L, 0);
        stubCommon(List.of(row));
        AtomicLong tenantSeenByListener = new AtomicLong(-1L);
        doAnswer(invocation -> {
            tenantSeenByListener.set(TenantContext.current());
            return null;
        }).when(listener).onSettled(any());
        TenantContext.set(5L); // 例如测试/手动补偿调用方已有上下文

        int delivered = dispatcher.dispatchOnce();

        assertThat(delivered).isEqualTo(1);
        assertThat(tenantSeenByListener.get()).isEqualTo(7L);
        assertThat(TenantContext.currentOrNull()).isEqualTo(5L);
        verify(repository).markSent(1L);
    }

    @Test
    void listenerSeesRowTenantEvenWhenDispatcherWasCalledWithoutTenant() {
        PaymentOutboxRepository.OutboxRow row = row(2L, 9L, 0);
        stubCommon(List.of(row));
        AtomicLong tenantSeenByListener = new AtomicLong(-1L);
        doAnswer(invocation -> {
            tenantSeenByListener.set(TenantContext.currentOrNull());
            return null;
        }).when(listener).onSettled(any());

        dispatcher.dispatchOnce();

        assertThat(tenantSeenByListener.get()).isEqualTo(9L);
        assertThat(TenantContext.currentOrNull()).isNull();
    }

    @Test
    void failedDeliveryUsesRowTenantThenRestoresForRetryBookkeeping() {
        PaymentOutboxRepository.OutboxRow row = row(3L, 11L, 0);
        stubCommon(List.of(row));
        doThrow(new IllegalStateException("listener boom")).when(listener).onSettled(any());
        TenantContext.set(5L);

        int delivered = dispatcher.dispatchOnce();

        assertThat(delivered).isZero();
        // 失败重试的 forward-only 记录仍在行租户上下文中执行
        verify(repository).reschedule(3L, 1, 5, "java.lang.IllegalStateException: listener boom");
        verify(repository, never()).markSent(any());
        assertThat(TenantContext.currentOrNull()).isEqualTo(5L);
    }

    private PaymentOutboxRepository.OutboxRow row(long id, long tenantId, int attempts) {
        PaymentOutboxRepository.OutboxRow row = new PaymentOutboxRepository.OutboxRow(
                id, "PAYMENT_SUCCEEDED", "P-" + id, "{}", attempts, tenantId);
        when(repository.payload(row)).thenReturn(new PaymentSettledEvent(
                "PAYMENT_SUCCEEDED", "P-" + id, "SAMPLE", "ORD-" + id,
                100L, "CNY", "MOCK", "T-" + id, LocalDateTime.now()));
        return row;
    }

    private void stubCommon(List<PaymentOutboxRepository.OutboxRow> rows) {
        when(repository.claimDue(50)).thenReturn(rows);
        when(repository.countInFlight()).thenReturn(0);
        when(repository.countDead()).thenReturn(0);
    }
}
