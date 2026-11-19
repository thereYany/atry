package com.wherelee.base.modules.llm.provider.deepseek;

import com.wherelee.base.modules.contracts.llm.ChatMessage;
import com.wherelee.base.modules.contracts.llm.LlmErrorCategory;
import com.wherelee.base.modules.contracts.llm.LlmException;
import com.wherelee.base.modules.contracts.llm.LlmRequest;
import com.wherelee.base.modules.contracts.llm.LlmResponse;
import com.wherelee.base.modules.contracts.llm.LlmStreamSink;
import com.wherelee.base.modules.contracts.llm.LlmUsage;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DeepSeekProviderTest {

    private HttpServer server;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final AtomicReference<String> capturedBody = new AtomicReference<>();
    private volatile String mode = "success";

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/chat/completions", this::handle);
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    @Test
    void mapsOpenAiCompatibleResponseAndUsage() {
        DeepSeekProvider provider = provider("test-key");

        LlmResponse response = provider.invoke(request("req-ds-1", 64));

        assertThat(response.provider()).isEqualTo("deepseek");
        assertThat(response.modelServed()).isEqualTo("deepseek-flash");
        assertThat(response.content()).isEqualTo("OK");
        assertThat(response.usage().inputTokens()).isEqualTo(9);
        assertThat(response.usage().outputTokens()).isEqualTo(1);
        assertThat(response.usage().cachedReadTokens()).isEqualTo(0);
        assertThat(response.usage().source()).isEqualTo(LlmUsage.Source.PROVIDER);
        assertThat(capturedBody.get()).contains("\"model\":\"deepseek-flash\"");
    }

    @Test
    void mapsRateLimitError() {
        mode = "429";
        DeepSeekProvider provider = provider("test-key");

        assertThatThrownBy(() -> provider.invoke(request("req-ds-2", 64)))
                .isInstanceOfSatisfying(LlmException.class, e -> {
                    assertThat(e.error().category()).isEqualTo(LlmErrorCategory.RATE_LIMITED);
                    assertThat(e.error().retryable()).isTrue();
                    assertThat(e.error().providerCode()).isEqualTo("rate_limit");
                });
    }

    @Test
    void mapsAuthErrorAndMissingKey() {
        mode = "401";
        assertThatThrownBy(() -> provider("test-key").invoke(request("req-ds-3", 64)))
                .isInstanceOfSatisfying(LlmException.class,
                        e -> assertThat(e.error().category()).isEqualTo(LlmErrorCategory.AUTH));

        assertThatThrownBy(() -> provider("").invoke(request("req-ds-4", 64)))
                .isInstanceOfSatisfying(LlmException.class,
                        e -> assertThat(e.error().providerCode()).isEqualTo("MISSING_API_KEY"));
    }

    @Test
    void streamsSseChunksAndUsage() {
        mode = "stream";
        DeepSeekProvider provider = provider("test-key");
        CollectingSink sink = new CollectingSink();

        provider.stream(request("req-ds-stream", 64), sink);

        assertThat(sink.content.toString()).isEqualTo("mock-chunk-0 mock-chunk-1 ");
        assertThat(sink.usage).isNotNull();
        assertThat(sink.usage.inputTokens()).isEqualTo(9);
        assertThat(sink.usage.outputTokens()).isEqualTo(2);
        assertThat(sink.finishReason).isEqualTo("stop");
    }

    private DeepSeekProvider provider(String apiKey) {
        return new DeepSeekProvider(new DeepSeekProperties(true,
                "http://127.0.0.1:" + server.getAddress().getPort(),
                apiKey, "deepseek-flash", true, 2000, 5000), objectMapper);
    }

    private LlmRequest request(String requestId, int maxTokens) {
        return new LlmRequest(requestId, 0, 0, 1, "deepseek-flash",
                List.of(new ChatMessage("user", "test")), maxTokens, false, null, null);
    }

    private void handle(HttpExchange exchange) throws IOException {
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        capturedBody.set(body);
        if ("stream".equals(mode)) {
            exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, 0);
            try (OutputStream out = exchange.getResponseBody()) {
                writeEvent(out, "{\"choices\":[{\"delta\":{\"content\":\"mock-chunk-0 \"}}]}");
                writeEvent(out, "{\"choices\":[{\"delta\":{\"content\":\"mock-chunk-1 \"}}]}");
                writeEvent(out, "{\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\"}],"
                        + "\"usage\":{\"prompt_tokens\":9,\"completion_tokens\":2}}");
                out.write("data: [DONE]\n\n".getBytes(StandardCharsets.UTF_8));
                out.flush();
            }
            return;
        }
        if ("429".equals(mode)) {
            respond(exchange, 429, "{\"error\":{\"code\":\"rate_limit\",\"message\":\"too many\"}}");
            return;
        }
        if ("401".equals(mode)) {
            respond(exchange, 401, "{\"error\":{\"code\":\"auth\",\"message\":\"bad key\"}}");
            return;
        }
        respond(exchange, 200, "{\"model\":\"deepseek-flash\",\"choices\":[{\"message\":{\"content\":\"OK\"},"
                + "\"finish_reason\":\"stop\"}],\"usage\":{\"prompt_tokens\":9,\"completion_tokens\":1}}");
    }

    private void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    private void writeEvent(OutputStream out, String data) throws IOException {
        out.write(("data: " + data + "\n\n").getBytes(StandardCharsets.UTF_8));
        out.flush();
    }

    private static final class CollectingSink implements LlmStreamSink {
        private final StringBuilder content = new StringBuilder();
        private LlmUsage usage;
        private String finishReason;

        @Override
        public void onChunk(String delta) {
            content.append(delta);
        }

        @Override
        public void onComplete(LlmUsage usage, String finishReason) {
            this.usage = usage;
            this.finishReason = finishReason;
        }

        @Override
        public void onError(com.wherelee.base.modules.contracts.llm.LlmError error) {
            throw new AssertionError("unexpected stream error: " + error);
        }
    }
}
