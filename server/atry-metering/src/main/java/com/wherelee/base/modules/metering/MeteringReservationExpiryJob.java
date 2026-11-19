package com.wherelee.base.modules.metering;

import com.wherelee.base.modules.contracts.metering.MeteringAdminOperations;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;

/** 定时回收过期预扣，避免 frozen 永久占用。 */
@Component
@ConditionalOnProperty(name = "atry.metering.expiry.enabled", havingValue = "true", matchIfMissing = true)
public class MeteringReservationExpiryJob {

    private static final Logger log = LoggerFactory.getLogger(MeteringReservationExpiryJob.class);

    private final MeteringAdminOperations admin;

    public MeteringReservationExpiryJob(MeteringAdminOperations admin) {
        this.admin = admin;
    }

    @Scheduled(fixedDelayString = "${atry.metering.expiry.poll-ms:60000}")
    public void expire() {
        int expired = admin.expireReservations(Instant.now());
        if (expired > 0) {
            log.info("metering.reservation.expired count={}", expired);
        }
    }
}
