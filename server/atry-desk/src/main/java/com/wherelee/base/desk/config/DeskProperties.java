package com.wherelee.base.desk.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.math.BigDecimal;

/**
 * 垂直域配置。
 *
 * @param appId          本垂直在计量体系里的应用标识（与内核演示宿主 0 区分，便于多应用共用账户）
 * @param defaultModel   AI 首答默认模型（默认 mock-small，切真实模型只改环境变量）
 * @param maxTokens      单次回复的 max_tokens 上限
 * @param historyLimit   带入上下文的历史消息条数上限（后续可换成摘要压缩）
 * @param initialBalance 演示账户首次开户时的种子余额
 * @param currency       计量币种
 */
@ConfigurationProperties(prefix = "app.desk")
public record DeskProperties(long appId, String defaultModel, int maxTokens, int historyLimit,
                             BigDecimal initialBalance, String currency) {
}
