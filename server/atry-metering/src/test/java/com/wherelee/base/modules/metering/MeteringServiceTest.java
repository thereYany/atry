package com.wherelee.base.modules.metering;

import com.wherelee.base.modules.contracts.metering.SubjectRef;
import com.wherelee.base.modules.platform.common.BizException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.http.HttpStatus.NOT_IMPLEMENTED;

class MeteringServiceTest {

    private final MeteringService service = new MeteringService();

    @Test
    void p0SkeletonFailsFastWithClearError() {
        assertThatThrownBy(() -> service.queryBalance(new SubjectRef(0, 0, 1)))
                .isInstanceOf(BizException.class)
                .satisfies(e -> assertThat(((BizException) e).status()).isEqualTo(NOT_IMPLEMENTED));
    }
}
