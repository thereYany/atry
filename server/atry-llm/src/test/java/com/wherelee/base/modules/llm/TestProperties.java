package com.wherelee.base.modules.llm;

import com.wherelee.base.modules.llm.config.LlmProperties;

/** 测试用默认配置（与 application.yml 默认值保持一致）。 */
public final class TestProperties {

    private TestProperties() {
    }

    public static LlmProperties defaults() {
        return new LlmProperties(
                new LlmProperties.Mock(0, "mock-fail", "mock-flaky", "mock-slow", 5000, 128, 8, 10, "mock-stream-mid-fail"),
                new LlmProperties.Timeouts(5000, 45000, 60000, 600000),
                new LlmProperties.Retry(3, 300, 2000, 0.2),
                new LlmProperties.Circuit(50, 20, 30000, 5, 0, 60),
                new LlmProperties.RateLimit(600, 6000, 100, "auto"));
    }
}
