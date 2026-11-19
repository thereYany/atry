package com.wherelee.base.modules.llm.observability;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.wherelee.base.modules.contracts.llm.ChatMessage;
import com.wherelee.base.modules.contracts.llm.LlmRequest;
import com.wherelee.base.modules.contracts.llm.LlmResponse;
import com.wherelee.base.modules.contracts.llm.LlmUsage;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class LlmEventLoggerTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void emitsStructuredJsonWithoutPromptOrResponseContent() {
        Logger logger = (Logger) LoggerFactory.getLogger(LlmEventLogger.LOGGER_NAME);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            LlmEventLogger events = new LlmEventLogger(objectMapper);
            LlmRequest request = new LlmRequest("req-log-1", 1, 2, 3, "mock-small",
                    List.of(new ChatMessage("user", "secret prompt")), 64, false, null, null);
            LlmResponse response = new LlmResponse("req-log-1", "mock", "mock-small", "secret response",
                    "stop", new LlmUsage(2, 3, 0, 0, 0, LlmUsage.Source.PROVIDER), false);

            events.finished(request, "mock", response,
                    new LlmUsage(2, 3, 0, 0, 0, LlmUsage.Source.PROVIDER), 120, 1, false);

            assertThat(appender.list).hasSize(1);
            String json = appender.list.get(0).getFormattedMessage();
            JsonNode node = objectMapper.readTree(json);
            assertThat(node.get("event_name").asString()).isEqualTo("llm.call.finished");
            assertThat(node.get("request_id").asString()).isEqualTo("req-log-1");
            assertThat(node.get("subject").get("tenant_id").asLong()).isEqualTo(1);
            assertThat(node.get("llm").get("provider").asString()).isEqualTo("mock");
            assertThat(node.get("usage").get("input_tokens").asLong()).isEqualTo(2);
            assertThat(node.get("latency").get("total_ms").asLong()).isEqualTo(120);
            assertThat(node.get("outcome").get("status").asString()).isEqualTo("SUCCESS");
            assertThat(node.get("content").get("content_captured").asBoolean()).isFalse();
            assertThat(json).doesNotContain("secret prompt").doesNotContain("secret response");
        } finally {
            logger.detachAppender(appender);
        }
    }
}
