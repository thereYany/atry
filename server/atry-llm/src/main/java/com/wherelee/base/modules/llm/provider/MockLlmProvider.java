package com.wherelee.base.modules.llm.provider;

import com.wherelee.base.modules.contracts.llm.LlmError;
import com.wherelee.base.modules.contracts.llm.LlmErrorCategory;
import com.wherelee.base.modules.contracts.llm.LlmException;
import com.wherelee.base.modules.contracts.llm.LlmRequest;
import com.wherelee.base.modules.contracts.llm.LlmResponse;
import com.wherelee.base.modules.contracts.llm.LlmUsage;
import com.wherelee.base.modules.llm.config.LlmProperties;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * mock provider：确定性 token 数 + 可配置延迟/失败模型，供测试与压测使用，不依赖外网。
 *
 * <p>输入 token 估算 = 消息字符数 / 4 向上取整；输出 token 由 requestId 哈希决定且不超过 maxTokens。</p>
 */
@Component
public class MockLlmProvider implements LlmProvider {

    private final LlmProperties.Mock properties;

    public MockLlmProvider(LlmProperties properties) {
        this.properties = properties.mock();
    }

    @Override
    public String id() {
        return "mock";
    }

    @Override
    public boolean supports(String model) {
        return model != null && model.startsWith("mock");
    }

    @Override
    public LlmResponse invoke(LlmRequest request) {
        if (properties.delayMs() > 0) {
            try {
                Thread.sleep(properties.delayMs());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new LlmException(new LlmError(LlmErrorCategory.CANCELLED, null, false, false,
                        "mock provider 被中断"), e);
            }
        }
        if (request.model().equals(properties.failureModel())) {
            throw new LlmException(new LlmError(LlmErrorCategory.UPSTREAM_5XX, "MOCK_FAILURE",
                    true, false, "mock provider 模拟失败：" + request.model()));
        }
        List<String> contents = request.messages().stream().map(m -> m.content() == null ? "" : m.content()).toList();
        int inputTokens = estimateInputTokens(contents);
        int cap = request.maxTokens() == null ? properties.defaultMaxTokens() : request.maxTokens();
        int outputTokens = Math.max(1, Math.min(cap, 20 + Math.floorMod(request.requestId().hashCode(), 100)));
        String first = contents.isEmpty() ? "" : contents.get(0);
        String content = "mock reply: " + (first.length() > 80 ? first.substring(0, 80) : first);
        return new LlmResponse(request.requestId(), id(), request.model(), content, "stop",
                new LlmUsage(inputTokens, outputTokens, 0, 0, 0, LlmUsage.Source.PROVIDER), false);
    }

    static int estimateInputTokens(List<String> contents) {
        int chars = contents.stream().mapToInt(s -> s == null ? 0 : s.length()).sum();
        return Math.max(1, (chars + 3) / 4);
    }
}
