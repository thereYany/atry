package com.wherelee.base.desk.billing;

import com.wherelee.base.desk.config.DeskProperties;
import com.wherelee.base.modules.contracts.llm.LlmUsage;
import com.wherelee.base.modules.contracts.metering.BalanceView;
import com.wherelee.base.modules.contracts.metering.MeteringAdminOperations;
import com.wherelee.base.modules.contracts.metering.MeteringOperations;
import com.wherelee.base.modules.contracts.metering.RechargeCommand;
import com.wherelee.base.modules.contracts.metering.SubjectRef;
import com.wherelee.base.modules.platform.common.BizException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;

/**
 * 垂直域计费辅助：账户种子、成本展示与余额查询。
 *
 * <p>真正的预扣/结算由内核 {@code LlmService} 依据 {@code LlmRequest.budgetRef} 完成；
 * 这里只负责「首次使用自动开户」和「按与内核一致的计价口径展示本次成本」，
 * 不复制计量实现。</p>
 */
@Service
public class DeskBillingSupport {

    private static final String INPUT_METER = "llm.input_tokens";
    private static final String OUTPUT_METER = "llm.output_tokens";
    private static final String CACHED_READ_METER = "llm.cached_read_tokens";
    private static final String BILLING_METER = "llm.tokens";

    private final MeteringOperations metering;
    private final MeteringAdminOperations meteringAdmin;
    private final DeskProperties properties;

    public DeskBillingSupport(MeteringOperations metering, MeteringAdminOperations meteringAdmin,
                              DeskProperties properties) {
        this.metering = metering;
        this.meteringAdmin = meteringAdmin;
        this.properties = properties;
    }

    /** 计量主体：租户 → 本垂直 appId → 用户。 */
    public SubjectRef subject(long tenantId, long userId) {
        return new SubjectRef(tenantId, properties.appId(), userId);
    }

    /** 首次使用幂等开户（bizNo 唯一键兜底）；已存在时直接返回当前余额。 */
    public BalanceView ensureAccount(SubjectRef subject) {
        String bizNo = "desk-seed:" + subject.tenantId() + ":" + subject.userId();
        try {
            return meteringAdmin.recharge(new RechargeCommand(subject, properties.initialBalance(),
                    properties.currency(), bizNo));
        } catch (DuplicateKeyException e) {
            return metering.queryBalance(subject);
        }
    }

    /** 与内核结算口径一致的实际成本：非缓存输入 + 输出 + 缓存命中。 */
    public BigDecimal cost(LlmUsage usage, String model, Instant at) {
        if (usage == null) {
            return BigDecimal.ZERO;
        }
        long nonCachedInput = Math.max(0, usage.inputTokens() - usage.cachedReadTokens());
        Map<String, String> dimensions = Map.of("model", model);
        try {
            return price(INPUT_METER, nonCachedInput, at, dimensions)
                    .add(price(OUTPUT_METER, usage.outputTokens(), at, dimensions))
                    .add(price(CACHED_READ_METER, usage.cachedReadTokens(), at, dimensions));
        } catch (BizException e) {
            // 未配置分项 token 价格时退回总量口径，与 LlmService 保持一致
            return metering.estimateCost(BILLING_METER,
                    BigDecimal.valueOf(Math.max(1, usage.totalTokens())), at);
        }
    }

    public BalanceView balance(long tenantId, long userId) {
        return metering.queryBalance(subject(tenantId, userId));
    }

    private BigDecimal price(String meterKey, long quantity, Instant at, Map<String, String> dimensions) {
        if (quantity <= 0) {
            return BigDecimal.ZERO.setScale(6);
        }
        return metering.estimateCost(meterKey, BigDecimal.valueOf(quantity), at, dimensions);
    }
}
