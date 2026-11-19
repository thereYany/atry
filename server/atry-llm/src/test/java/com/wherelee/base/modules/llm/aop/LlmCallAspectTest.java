package com.wherelee.base.modules.llm.aop;

import com.wherelee.base.modules.llm.annotation.LlmCall;
import com.wherelee.base.modules.llm.observability.LlmEventLogger;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.aop.aspectj.annotation.AspectJProxyFactory;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LlmCallAspectTest {

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final LlmCallAspect aspect = new LlmCallAspect(registry, new LlmEventLogger(new ObjectMapper()));

    @Test
    void recordsAnnotatedCallSuccessAndFailure() {
        AspectJProxyFactory factory = new AspectJProxyFactory(new Target());
        factory.addAspect(aspect);
        Target proxy = factory.getProxy();

        assertThat(proxy.call()).isEqualTo("ok");
        assertThatThrownBy(proxy::boom).isInstanceOf(IllegalStateException.class);

        assertThat(registry.get("atry.llm.annotated.calls").tag("status", "SUCCESS").counter().count())
                .isEqualTo(1);
        assertThat(registry.get("atry.llm.annotated.calls").tag("status", "FAILED").counter().count())
                .isEqualTo(1);
    }

    static class Target {
        @LlmCall("demo")
        public String call() {
            return "ok";
        }

        @LlmCall("demo")
        public String boom() {
            throw new IllegalStateException("boom");
        }
    }
}
