package com.wherelee.base.integration;

import com.wherelee.base.modules.contracts.metering.MeteringAdminOperations;
import com.wherelee.base.modules.contracts.metering.RechargeCommand;
import com.wherelee.base.modules.contracts.metering.SubjectRef;
import com.wherelee.base.modules.llm.annotation.BudgetGuard;
import com.wherelee.base.modules.platform.common.BizException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 预算守卫端到端证据：真实 MySQL 账户可用额 + Spring AOP 切面 + 402 语义。
 */
@Import(BudgetGuardIntegrationTest.GuardConfig.class)
class BudgetGuardIntegrationTest extends KernelIntegrationTestSupport {

    @Autowired
    private GuardedTarget guardedTarget;
    @Autowired
    private MeteringAdminOperations meteringAdmin;

    @BeforeEach
    void reset() {
        RequestContextHolder.resetRequestAttributes();
    }

    @AfterEach
    void clear() {
        RequestContextHolder.resetRequestAttributes();
    }

    @Test
    @DisplayName("可用额充足时放行；余额清零后同一切面返回 402 且不进入方法体")
    void guardChecksRealAccountBalance() {
        Long userId = seedUser("it_budget", "Integration1Pass", "预算用户", "ADMIN");
        bindLoginContext(0L, userId);
        SubjectRef subject = new SubjectRef(0L, 0L, userId);
        meteringAdmin.recharge(new RechargeCommand(subject, new BigDecimal("2.000000"), "CNY", "it-budget-seed"));

        assertThat(guardedTarget.expensiveCall()).isEqualTo("called");

        jdbc.update("""
                UPDATE t_metering_account SET balance = 0, frozen = 0
                WHERE tenant_id = 0 AND app_id = 0 AND user_id = ?
                """, userId);

        assertThatThrownBy(() -> guardedTarget.expensiveCall())
                .isInstanceOfSatisfying(BizException.class,
                        e -> assertThat(e.status().value()).isEqualTo(402));
    }

    private void bindLoginContext(long tenantId, long userId) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute("tenantId", tenantId);
        request.setAttribute("userId", userId);
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
    }

    /** 测试用「业务方法」：由真实 Spring AOP 代理，验证 @BudgetGuard 切面在容器内生效。 */
    public static class GuardedTarget {

        @BudgetGuard(budgetRef = "it-budget", minAvailable = "1.00",
                onExceed = BudgetGuard.Action.REJECT, scope = BudgetGuard.SubjectScope.USER)
        public String expensiveCall() {
            return "called";
        }
    }

    @TestConfiguration
    static class GuardConfig {

        @Bean
        GuardedTarget guardedTarget() {
            return new GuardedTarget();
        }
    }
}
