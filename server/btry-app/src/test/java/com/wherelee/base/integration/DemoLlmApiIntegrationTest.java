package com.wherelee.base.integration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * P6 演示链路验收：登录 → 演示调用 → 用量/成本 → 预扣结算 → 余额扣减。
 *
 * <p>证明 atry-llm 与 atry-metering 在真实 HTTP + 真实 MySQL 下串起来可用，
 * 且错误分类能映射为调用方可决策的 HTTP 状态码。</p>
 */
@AutoConfigureMockMvc
class DemoLlmApiIntegrationTest extends KernelIntegrationTestSupport {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Autowired
    private MockMvc mockMvc;

    @Test
    @DisplayName("演示端点：调用一次 LLM，返回用量/成本，余额按结算口径扣减，账户自动开户")
    void demoChatShowsUsageCostAndBalance() throws Exception {
        seedUser("it_demo", "Integration1Pass", "演示用户", "ADMIN");
        String token = login("it_demo");

        BigDecimal before = balanceOf(token);
        assertThat(before).isEqualByComparingTo("10.000000");

        JsonNode chat = chat(token, """
                {"message":"你好，atry","model":"mock-small","maxTokens":32}
                """);
        assertThat(chat.get("requestId").asText()).startsWith("demo").hasSize(36);
        assertThat(chat.get("content").asText()).startsWith("mock reply");
        assertThat(chat.get("provider").asText()).isEqualTo("mock");
        assertThat(chat.get("usage").get("inputTokens").asLong()).isPositive();
        assertThat(chat.get("usage").get("outputTokens").asLong()).isPositive();
        assertThat(chat.get("usage").get("totalTokens").asLong()).isEqualTo(
                chat.get("usage").get("inputTokens").asLong() + chat.get("usage").get("outputTokens").asLong());
        assertThat(new BigDecimal(chat.get("cost").get("amount").asText())).isPositive();

        BigDecimal after = new BigDecimal(chat.get("balance").get("balance").asText());
        assertThat(after).isLessThan(before);
        assertThat(after).isEqualByComparingTo(balanceOf(token));

        // 用量事件已同步入账（outbox 在集成 profile 关闭，这里断言的是调用记录链路本身）
        assertThat(count("t_llm_call_record", "request_id = ?", chat.get("requestId").asText())).isEqualTo(1);
        assertThat(count("t_metering_ledger", "biz_no LIKE ?", "settle:llm:" + chat.get("requestId").asText()))
                .isEqualTo(1);
    }

    @Test
    @DisplayName("演示端点受 JWT 保护；上游 5xx 经重试后按错误分类映射为 502")
    void demoEndpointSecurityAndErrorMapping() throws Exception {
        seedUser("it_demo2", "Integration1Pass", "演示用户2", "ADMIN");
        String token = login("it_demo2");

        mockMvc.perform(post("/api/demo/llm/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"message":"未登录调用"}
                                """))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(post("/api/demo/llm/chat")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"message":"模拟上游故障","model":"mock-fail"}
                                """))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.category").value("UPSTREAM_5XX"))
                .andExpect(jsonPath("$.retryable").value(true));
    }

    private JsonNode chat(String token, String body) throws Exception {
        String response = mockMvc.perform(post("/api/demo/llm/chat")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return JSON.readTree(response);
    }

    private BigDecimal balanceOf(String token) throws Exception {
        String response = mockMvc.perform(get("/api/demo/metering/balance")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return new BigDecimal(JSON.readTree(response).get("balance").asText());
    }

    private String login(String username) throws Exception {
        String body = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"%s","password":"Integration1Pass"}
                                """.formatted(username)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return JSON.readTree(body).get("token").asText();
    }
}
