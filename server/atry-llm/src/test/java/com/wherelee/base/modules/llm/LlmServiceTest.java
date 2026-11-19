package com.wherelee.base.modules.llm;

import com.wherelee.base.modules.platform.common.BizException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.http.HttpStatus.NOT_IMPLEMENTED;

class LlmServiceTest {

    private final LlmService service = new LlmService();

    @Test
    void p0SkeletonFailsFastWithClearError() {
        assertThatThrownBy(() -> service.invoke(null))
                .isInstanceOf(BizException.class)
                .satisfies(e -> assertThat(((BizException) e).status()).isEqualTo(NOT_IMPLEMENTED));
    }
}
