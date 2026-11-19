package com.wherelee.base.modules.platform.tenant;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

/**
 * 租户 SQL 注入决策单测：普通作用域仍注入/跨表仍拒绝，系统作用域直接放行。
 *
 * <p>只测 {@code prepare}（重写决策），不连接数据库；真正的 SQL 执行由集成测试覆盖。</p>
 */
class TenantAwareJdbcTemplateTest {

    private static final String SINGLE_TABLE_SQL =
            "SELECT payment_no FROM t_payment_order WHERE status = ?";
    private static final String CROSS_TABLE_SQL = """
            SELECT COUNT(*) FROM t_payment_order p
            WHERE p.status = 'PAID'
              AND NOT EXISTS (SELECT 1 FROM t_payment_outbox o
                              WHERE o.aggregate_no = p.payment_no)
            """;

    private final TenantSqlRewriter rewriter = new TenantSqlRewriter(Set.of());
    private final DataSource dataSource = mock(DataSource.class);

    @AfterEach
    void clearContext() {
        TenantContext.clear();
    }

    @Test
    void injectsTenantPredicateWhenNotInSystemScope() {
        TenantAwareJdbcTemplate template = new TenantAwareJdbcTemplate(dataSource, true, rewriter);
        TenantContext.set(7L);

        TenantSqlRewriter.RewriteResult result = template.prepare(SINGLE_TABLE_SQL);

        assertThat(result.changed()).isTrue();
        assertThat(result.sql()).contains("tenant_id = ?");
        assertThat(result.argIndex()).isEqualTo(1);
    }

    @Test
    void systemScopeDoesNotInjectTenantPredicate() {
        TenantAwareJdbcTemplate template = new TenantAwareJdbcTemplate(dataSource, true, rewriter);

        TenantSqlRewriter.RewriteResult result =
                TenantContext.runAsSystem(() -> template.prepare(SINGLE_TABLE_SQL));

        assertThat(result.changed()).isFalse();
        assertThat(result.sql()).isEqualTo(SINGLE_TABLE_SQL);
    }

    @Test
    void systemScopeWithBackfilledTenantInjectsPredicate() {
        TenantAwareJdbcTemplate template = new TenantAwareJdbcTemplate(dataSource, true, rewriter);

        TenantSqlRewriter.RewriteResult result = runAsSystemWithTenant(7L,
                () -> template.prepare(SINGLE_TABLE_SQL));

        assertThat(result.changed()).isTrue();
        assertThat(result.sql()).contains("tenant_id = ?");
        assertThat(result.argIndex()).isEqualTo(1);
    }

    @Test
    void systemScopeWithBackfilledTenantIsBackToNormalEnforcement() {
        TenantAwareJdbcTemplate template = new TenantAwareJdbcTemplate(dataSource, true, rewriter);

        assertThatThrownBy(() -> runAsSystemWithTenant(7L, () -> template.prepare(CROSS_TABLE_SQL)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("tenant_id");
    }

    @Test
    void systemScopeBackfillIsClearedAfterAction() {
        TenantAwareJdbcTemplate template = new TenantAwareJdbcTemplate(dataSource, true, rewriter);

        runAsSystemWithTenant(7L, () -> template.prepare(SINGLE_TABLE_SQL));

        assertThat(TenantContext.isSystemScope()).isFalse();
        assertThat(TenantContext.currentOrNull()).isNull();
        // 回到“系统作用域未回填”时仍是放行语义
        TenantSqlRewriter.RewriteResult result = TenantContext.runAsSystem(
                () -> template.prepare(SINGLE_TABLE_SQL));
        assertThat(result.changed()).isFalse();
    }

    @Test
    void notInSystemScopeWithoutTenantStillInjectsPredicate() {
        TenantAwareJdbcTemplate template = new TenantAwareJdbcTemplate(dataSource, true, rewriter);

        TenantSqlRewriter.RewriteResult result = template.prepare(SINGLE_TABLE_SQL);

        assertThat(result.changed()).isTrue();
        assertThat(result.sql()).contains("tenant_id = ?");
    }

    @Test
    void crossTableSqlStillRejectedOutsideSystemScope() {
        TenantAwareJdbcTemplate template = new TenantAwareJdbcTemplate(dataSource, true, rewriter);

        assertThatThrownBy(() -> template.prepare(CROSS_TABLE_SQL))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("tenant_id");
    }

    @Test
    void systemScopeLetsCrossTableSqlThroughWithoutRewriting() {
        TenantAwareJdbcTemplate template = new TenantAwareJdbcTemplate(dataSource, true, rewriter);

        TenantSqlRewriter.RewriteResult result =
                TenantContext.runAsSystem(() -> template.prepare(CROSS_TABLE_SQL));

        assertThat(result.changed()).isFalse();
        assertThat(result.sql()).isEqualTo(CROSS_TABLE_SQL);
    }

    @Test
    void injectionResumesAfterSystemScopeExits() {
        TenantAwareJdbcTemplate template = new TenantAwareJdbcTemplate(dataSource, true, rewriter);

        TenantContext.runAsSystem(() -> template.prepare(SINGLE_TABLE_SQL));

        assertThat(template.prepare(SINGLE_TABLE_SQL).changed()).isTrue();
    }

    @Test
    void enforceFalseNeverRewrites() {
        TenantAwareJdbcTemplate template = new TenantAwareJdbcTemplate(dataSource, false, rewriter);
        TenantContext.set(7L);

        TenantSqlRewriter.RewriteResult result = template.prepare(SINGLE_TABLE_SQL);

        assertThat(result.changed()).isFalse();
        assertThat(result.sql()).isEqualTo(SINGLE_TABLE_SQL);
    }

    /** 模拟回调/投递器：系统作用域内回填支付单租户，执行完清理。 */
    private <T> T runAsSystemWithTenant(long tenantId, java.util.function.Supplier<T> action) {
        return TenantContext.runAsSystem(() -> {
            TenantContext.set(tenantId);
            try {
                return action.get();
            } finally {
                TenantContext.clear();
            }
        });
    }
}
