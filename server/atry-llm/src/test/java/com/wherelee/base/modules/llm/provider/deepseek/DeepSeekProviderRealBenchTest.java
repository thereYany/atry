package com.wherelee.base.modules.llm.provider.deepseek;

import com.wherelee.base.modules.contracts.llm.ChatMessage;
import com.wherelee.base.modules.contracts.llm.LlmRequest;
import com.wherelee.base.modules.contracts.llm.LlmResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** 真实 DeepSeek 小流量延迟基线（仅当 key 存在；默认 3 次，成本极低）。 */
@EnabledIfEnvironmentVariable(named = "DEEPSEEK_API_KEY", matches = ".+")
class DeepSeekProviderRealBenchTest {

    @Test
    void measuresRealDeepSeekLatency() {
        DeepSeekProvider provider = new DeepSeekProvider(new DeepSeekProperties(true,
                System.getenv().getOrDefault("DEEPSEEK_BASE_URL", "https://api.deepseek.com"),
                System.getenv("DEEPSEEK_API_KEY"), "deepseek-flash", true, 5000, 60000),
                new ObjectMapper());
        List<Long> latencies = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            long begin = System.currentTimeMillis();
            LlmResponse response = provider.invoke(new LlmRequest("req-real-bench-" + i, 0, 0, 1,
                    "deepseek-flash", List.of(new ChatMessage("user", "Reply with exactly: OK")),
                    16, false, null, null));
            latencies.add(System.currentTimeMillis() - begin);
            assertThat(response.usage().totalTokens()).isPositive();
        }
        System.out.printf("BENCH real-deepseek calls=%d latencyMs=%s%n", latencies.size(), latencies);
    }
}
