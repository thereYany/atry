package com.wherelee.base.modules.contracts.metering;

import java.util.List;

/**
 * 通用计量计费契约：业务/框架只通过它上报用量、预扣、结算与查询。
 *
 * <p>实现负责：事件幂等、计量/计价、额度与余额、账本与对账；
 * 不暴露任何垂直业务概念。金额/数量使用 BigDecimal，单位由 meter 定义。</p>
 */
public interface MeteringOperations {

    /** 上报用量（幂等键 eventId；重复上报不重复入账）。 */
    void recordUsage(UsageCommand command);

    /** 预扣（准入）：余额/额度不足时抛出业务异常。 */
    ReservationView reserve(ReserveCommand command);

    /** 按实际金额结算预扣。 */
    void settle(SettleCommand command);

    /** 释放预扣（失败/未使用）。 */
    void release(ReleaseCommand command);

    BalanceView queryBalance(SubjectRef subject);

    List<UsageView> queryUsage(UsageQuery query);
}
