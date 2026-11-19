package com.wherelee.base.modules.llm.budget;

import com.wherelee.base.modules.contracts.metering.BalanceView;
import com.wherelee.base.modules.contracts.metering.MeteringOperations;
import com.wherelee.base.modules.contracts.metering.SubjectRef;
import com.wherelee.base.modules.llm.annotation.BudgetGuard;
import com.wherelee.base.modules.llm.observability.LlmMetrics;
import com.wherelee.base.modules.platform.common.BizException;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.aspectj.lang.ProceedingJoinPoint;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.core.annotation.Order;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 预算守卫切面契约：低于下限 REJECT/DEGRADE、无上下文与计量异常 fail-open、切面顺序 -50。
 */
class BudgetGuardAspectTest {

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final LlmMetrics metrics = new LlmMetrics(registry);
    private final MeteringOperations metering = mock(MeteringOperations.class);
    private final BudgetGuardAspect aspect = new BudgetGuardAspect(provider(metering), metrics);

    @BeforeEach
    void bindLoginContext() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute("tenantId", 1L);
        request.setAttribute("userId", 7L);
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
    }

    @AfterEach
    void clearContext() {
        RequestContextHolder.resetRequestAttributes();
    }

    @Test
    @DisplayName("可用额低于下限：REJECT 抛 402、不进入业务方法、指标计数")
    void rejectsWhenAvailableBelowMinimum() throws Throwable {
        when(metering.queryBalance(any())).thenReturn(
                new BalanceView(new SubjectRef(1, 0, 7), new BigDecimal("1.50"), new BigDecimal("1.00"), "CNY"));
        ProceedingJoinPoint joinPoint = joinPoint("ok");

        assertThatThrownBy(() -> aspect.around(joinPoint, rejectingGuard()))
                .isInstanceOfSatisfying(BizException.class,
                        e -> assertThat(e.status().value()).isEqualTo(402));

        verify(joinPoint, never()).proceed();
        assertThat(registry.get("atry.llm.budget.exceeded")
                .tag("budget", "tenant-budget").tag("action", "REJECT").counter().count()).isEqualTo(1d);

        ArgumentCaptor<SubjectRef> captor = ArgumentCaptor.forClass(SubjectRef.class);
        verify(metering).queryBalance(captor.capture());
        assertThat(captor.getValue()).isEqualTo(new SubjectRef(1, 0, 7));
    }

    @Test
    @DisplayName("可用额足够：放行并返回业务结果")
    void proceedsWhenAvailableEnough() throws Throwable {
        when(metering.queryBalance(any())).thenReturn(
                new BalanceView(new SubjectRef(1, 0, 7), new BigDecimal("5.00"), BigDecimal.ZERO, "CNY"));
        ProceedingJoinPoint joinPoint = joinPoint("result");

        assertThat(aspect.around(joinPoint, rejectingGuard())).isEqualTo("result");
        verify(joinPoint).proceed();
    }

    @Test
    @DisplayName("DEGRADE：放行但本次调用内可见降级标记，结束后清理")
    void degradesWhenConfigured() throws Throwable {
        when(metering.queryBalance(any())).thenReturn(
                new BalanceView(new SubjectRef(1, 0, 7), new BigDecimal("1.50"), new BigDecimal("1.00"), "CNY"));
        ProceedingJoinPoint joinPoint = mock(ProceedingJoinPoint.class);
        when(joinPoint.proceed()).thenAnswer(invocation -> {
            assertThat(BudgetContext.isDegraded()).isTrue();
            assertThat(BudgetContext.degradedBudgetRef()).isEqualTo("tenant-budget");
            return "degraded";
        });

        assertThat(aspect.around(joinPoint, degradingGuard())).isEqualTo("degraded");

        assertThat(BudgetContext.isDegraded()).isFalse();
        assertThat(registry.get("atry.llm.budget.exceeded")
                .tag("action", "DEGRADE").counter().count()).isEqualTo(1d);
    }

    @Test
    @DisplayName("无登录上下文：fail-open（不查余额、直接放行）")
    void failsOpenWithoutRequestContext() throws Throwable {
        RequestContextHolder.resetRequestAttributes();
        ProceedingJoinPoint joinPoint = joinPoint("ok");

        assertThat(aspect.around(joinPoint, rejectingGuard())).isEqualTo("ok");
        verify(metering, never()).queryBalance(any());
    }

    @Test
    @DisplayName("计量查询异常：fail-open（不因守卫本身打挂业务）")
    void failsOpenWhenMeteringFails() throws Throwable {
        when(metering.queryBalance(any())).thenThrow(new IllegalStateException("db down"));
        ProceedingJoinPoint joinPoint = joinPoint("ok");

        assertThat(aspect.around(joinPoint, rejectingGuard())).isEqualTo("ok");
        verify(joinPoint).proceed();
    }

    @Test
    @DisplayName("未装配计量模块：守卫不可用，fail-open")
    void failsOpenWithoutMeteringModule() throws Throwable {
        BudgetGuardAspect withoutMetering = new BudgetGuardAspect(
                new DefaultListableBeanFactory().getBeanProvider(MeteringOperations.class), metrics);
        ProceedingJoinPoint joinPoint = joinPoint("ok");

        assertThat(withoutMetering.around(joinPoint, rejectingGuard())).isEqualTo("ok");
        verify(joinPoint).proceed();
    }

    @Test
    @DisplayName("切面顺序：-50（鉴权/限流/幂等之后、业务方法之前）")
    void hasDesignOrder() throws Throwable {
        assertThat(BudgetGuardAspect.class.getAnnotation(Order.class).value()).isEqualTo(-50);
        verify(metering, times(0)).queryBalance(any());
    }

    @BudgetGuard(budgetRef = "tenant-budget", minAvailable = "1.00",
            onExceed = BudgetGuard.Action.REJECT, scope = BudgetGuard.SubjectScope.USER)
    private void sampleRejecting() {
    }

    @BudgetGuard(budgetRef = "tenant-budget", minAvailable = "1.00",
            onExceed = BudgetGuard.Action.DEGRADE, scope = BudgetGuard.SubjectScope.USER)
    private void sampleDegrading() {
    }

    private BudgetGuard rejectingGuard() throws NoSuchMethodException {
        return getClass().getDeclaredMethod("sampleRejecting").getAnnotation(BudgetGuard.class);
    }

    private BudgetGuard degradingGuard() throws NoSuchMethodException {
        return getClass().getDeclaredMethod("sampleDegrading").getAnnotation(BudgetGuard.class);
    }

    private ObjectProvider<MeteringOperations> provider(MeteringOperations bean) {
        DefaultListableBeanFactory factory = new DefaultListableBeanFactory();
        factory.registerSingleton("metering", bean);
        return factory.getBeanProvider(MeteringOperations.class);
    }

    private ProceedingJoinPoint joinPoint(String result) throws Throwable {
        ProceedingJoinPoint joinPoint = mock(ProceedingJoinPoint.class);
        when(joinPoint.proceed()).thenReturn(result);
        return joinPoint;
    }
}
