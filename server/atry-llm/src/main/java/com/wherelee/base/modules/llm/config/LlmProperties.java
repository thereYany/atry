package com.wherelee.base.modules.llm.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** LLM 框架配置（P1 仅 mock provider；真实 provider 在 P5 扩展）。 */
@ConfigurationProperties(prefix = "atry.llm")
public record LlmProperties(Mock mock) {

    public record Mock(long delayMs, String failureModel, int defaultMaxTokens) {
    }
}
