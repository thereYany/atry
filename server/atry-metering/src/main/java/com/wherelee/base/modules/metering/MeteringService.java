package com.wherelee.base.modules.metering;

import com.wherelee.base.modules.contracts.metering.BalanceView;
import com.wherelee.base.modules.contracts.metering.MeteringOperations;
import com.wherelee.base.modules.contracts.metering.ReleaseCommand;
import com.wherelee.base.modules.contracts.metering.ReservationView;
import com.wherelee.base.modules.contracts.metering.ReserveCommand;
import com.wherelee.base.modules.contracts.metering.SettleCommand;
import com.wherelee.base.modules.contracts.metering.SubjectRef;
import com.wherelee.base.modules.contracts.metering.UsageCommand;
import com.wherelee.base.modules.contracts.metering.UsageQuery;
import com.wherelee.base.modules.contracts.metering.UsageView;
import com.wherelee.base.modules.platform.common.BizException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.util.List;

/** P0 骨架：契约已就位，计量/计价/预扣/账本按迭代 P4 实现。 */
@Service
public class MeteringService implements MeteringOperations {

    @Override
    public void recordUsage(UsageCommand command) {
        throw notImplemented();
    }

    @Override
    public ReservationView reserve(ReserveCommand command) {
        throw notImplemented();
    }

    @Override
    public void settle(SettleCommand command) {
        throw notImplemented();
    }

    @Override
    public void release(ReleaseCommand command) {
        throw notImplemented();
    }

    @Override
    public BalanceView queryBalance(SubjectRef subject) {
        throw notImplemented();
    }

    @Override
    public List<UsageView> queryUsage(UsageQuery query) {
        throw notImplemented();
    }

    private BizException notImplemented() {
        return new BizException(HttpStatus.NOT_IMPLEMENTED, "计量计费尚未实现（P0 骨架）");
    }
}
