package com.wherelee.base.integration;

import com.wherelee.base.modules.contracts.metering.MeteringOperations;
import com.wherelee.base.modules.contracts.metering.SubjectRef;
import com.wherelee.base.modules.contracts.metering.UsageCommand;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** P5b：DeepSeek 官方价（输入 2 元/M、输出 8 元/M、缓存命中 0.04 元/M）按 token 类型与 model 维度计价。 */
@Tag("integration")
class MeteringPricingIntegrationTest extends KernelIntegrationTestSupport {

    @Autowired
    private MeteringOperations metering;

    private final SubjectRef subject = new SubjectRef(0, 0, 7);

    @Test
    void pricesDeepSeekTokenTypesByModelSelector() {
        String model = "deepseek-flash";
        record("evt-price-in", "llm.input_tokens", "1000000", model);
        record("evt-price-out", "llm.output_tokens", "1000000", model);
        record("evt-price-cache", "llm.cached_read_tokens", "1000000", model);

        assertThat(amount("evt-price-in")).isEqualByComparingTo(new BigDecimal("2.000000"));
        assertThat(amount("evt-price-out")).isEqualByComparingTo(new BigDecimal("8.000000"));
        assertThat(amount("evt-price-cache")).isEqualByComparingTo(new BigDecimal("0.040000"));
    }

    private void record(String eventId, String meterKey, String quantity, String model) {
        metering.recordUsage(new UsageCommand(eventId, meterKey, subject, new BigDecimal(quantity),
                "token", Map.of("model", model, "provider", "deepseek"), Instant.now(), eventId));
    }

    private BigDecimal amount(String eventId) {
        return jdbc.queryForObject("SELECT amount FROM t_metering_charge WHERE event_id = ?",
                BigDecimal.class, eventId);
    }
}
