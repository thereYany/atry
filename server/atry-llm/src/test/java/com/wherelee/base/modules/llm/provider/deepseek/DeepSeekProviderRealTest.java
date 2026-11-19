package com.wherelee.base.modules.llm.provider.deepseek;

import com.wherelee.base.modules.contracts.llm.ChatMessage;
import com.wherelee.base.modules.contracts.llm.LlmRequest;
import com.wherelee.base.modules.contracts.llm.LlmResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import tools.jackson.databind.ObjectMapper;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** 真实 DeepSeek 小请求（仅当 DEEPSEEK_API_KEY 存在时运行；不打印 key）。 */
@EnabledIfEnvironmentVariable(named = "DEEPSEEK_API_KEY", matches = ".+")
class DeepSeekProviderRealTest {

    @Test
    void invokesRealDeepSeekFlash() {
        DeepSeekProvider provider = new DeepSeekProvider(new DeepSeekProperties(true,
                System.getenv().getOrDefault("DEEPSEEK_BASE_URL", "https://api.deepseek.com"),
                System.getenv("DEEPSEEK_API_KEY"), "deepseek-flash", true, 5000, 60000),
                new ObjectMapper());

        LlmResponse response = provider.invoke(new LlmRequest("req-real-ds-1", 0, 0, 1,
                "deepseek-flash", List.of(new ChatMessage("user", "Reply with exactly: OK")),
                16, false, null, null));

        assertThat(response.content()).isNotBlank();
        assertThat(response.modelServed()).startsWith("deepseek");
        assertThat(response.usage().inputTokens()).isPositive();
        assertThat(response.usage().outputTokens()).isPositive();
    }
}
