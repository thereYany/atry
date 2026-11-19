package com.wherelee.base.modules.platform.tenant;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 系统作用域单测：默认不生效、嵌套可恢复、异常安全，且不干扰租户值本身。
 */
class TenantContextTest {

    @AfterEach
    void clearContext() {
        TenantContext.clear();
    }

    @Test
    void systemScopeIsOffByDefault() {
        assertThat(TenantContext.isSystemScope()).isFalse();
    }

    @Test
    void runAsSystemEnablesScopeOnlyInsideTheAction() {
        Boolean inside = TenantContext.runAsSystem(TenantContext::isSystemScope);

        assertThat(inside).isTrue();
        assertThat(TenantContext.isSystemScope()).isFalse();
    }

    @Test
    void nestedRunAsSystemRestoresOuterScope() {
        boolean nestedCorrect = TenantContext.runAsSystem(() -> {
            boolean outer = TenantContext.isSystemScope();
            boolean inner = TenantContext.runAsSystem(TenantContext::isSystemScope);
            boolean afterInner = TenantContext.isSystemScope();
            return outer && inner && afterInner;
        });

        assertThat(nestedCorrect).isTrue();
        assertThat(TenantContext.isSystemScope()).isFalse();
    }

    @Test
    void supplierExceptionStillRestoresScope() {
        RuntimeException failure = new IllegalStateException("boom");

        assertThatThrownBy(() -> TenantContext.runAsSystem(() -> {
            throw failure;
        })).isSameAs(failure);
        assertThat(TenantContext.isSystemScope()).isFalse();
    }

    @Test
    void runnableExceptionStillRestoresScope() {
        assertThatThrownBy(() ->
                TenantContext.runAsSystem((Runnable) () -> {
                    throw new IllegalStateException("boom");
                })
        ).isInstanceOf(IllegalStateException.class);
        assertThat(TenantContext.isSystemScope()).isFalse();
    }

    @Test
    void runAsSystemDoesNotChangeTenantValue() {
        TenantContext.set(7L);

        Long inside = TenantContext.runAsSystem(TenantContext::currentOrNull);

        assertThat(inside).isEqualTo(7L);
        assertThat(TenantContext.current()).isEqualTo(7L);
        assertThat(TenantContext.currentOrNull()).isEqualTo(7L);
    }

    @Test
    void runnableOverloadRunsInsideScope() {
        AtomicBoolean seen = new AtomicBoolean();

        TenantContext.runAsSystem(() -> seen.set(TenantContext.isSystemScope()));

        assertThat(seen).isTrue();
        assertThat(TenantContext.isSystemScope()).isFalse();
    }

    @Test
    void runAsSystemWorksOnBackgroundThreadWithoutTenant() {
        // 模拟调度线程：无租户值时系统作用域仍然生效
        Long tenant = TenantContext.currentOrNull();
        boolean scoped = TenantContext.runAsSystem(() -> tenant == null && TenantContext.isSystemScope());

        assertThat(tenant).isNull();
        assertThat(scoped).isTrue();
    }
}
