package com.wherelee.base.modules.metering.aop;

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
import com.wherelee.base.modules.metering.annotation.UsageMetered;
import org.junit.jupiter.api.Test;
import org.springframework.aop.aspectj.annotation.AspectJProxyFactory;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class UsageMeteredAspectTest {

    private final RecordingMetering metering = new RecordingMetering();

    @Test
    void recordsUsageFromExpressionAfterSuccess() {
        AspectJProxyFactory factory = new AspectJProxyFactory(new Target());
        factory.addAspect(new UsageMeteredAspect(metering));
        Target proxy = factory.getProxy();

        assertThat(proxy.generate(5)).isEqualTo(10);

        assertThat(metering.lastCommand).isNotNull();
        assertThat(metering.lastCommand.meterKey()).isEqualTo("tokens");
        assertThat(metering.lastCommand.quantity()).isEqualByComparingTo(new BigDecimal("10"));
        assertThat(metering.lastCommand.unit()).isEqualTo("token");
    }

    static class Target {
        @UsageMetered(meterKey = "tokens", quantity = "#result", unit = "token")
        public int generate(int n) {
            return n * 2;
        }
    }

    static class RecordingMetering implements MeteringOperations {
        private UsageCommand lastCommand;

        @Override
        public void recordUsage(UsageCommand command) {
            this.lastCommand = command;
        }

        @Override
        public java.math.BigDecimal estimateCost(String meterKey, java.math.BigDecimal quantity, Instant occurredAt) {
            return java.math.BigDecimal.ZERO;
        }

        @Override
        public ReservationView reserve(ReserveCommand command) {
            return null;
        }

        @Override
        public void settle(SettleCommand command) {
        }

        @Override
        public void release(ReleaseCommand command) {
        }

        @Override
        public BalanceView queryBalance(SubjectRef subject) {
            return null;
        }

        @Override
        public List<UsageView> queryUsage(UsageQuery query) {
            return List.of();
        }
    }
}
