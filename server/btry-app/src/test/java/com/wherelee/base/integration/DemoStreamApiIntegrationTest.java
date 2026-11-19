package com.wherelee.base.integration;

import com.wherelee.base.BtryApplication;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * P7-③ SSE 端点端到端证据：真实 HTTP + 真实 MySQL/Redis。
 * 校验 SSE 事件（delta/done）、结算落账、余额扣减。
 */
@Tag("integration")
@SpringBootTest(classes = BtryApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("integration")
@EnabledIfEnvironmentVariable(named = "IT_DB_URL", matches = ".+")
@TestPropertySource(properties = {
        "atry.llm.rate-limit.tenant-rpm=60000",
        "atry.demo.initial-balance=10.000000"
})
class DemoStreamApiIntegrationTest {

    private static final String PASSWORD = "Integration1Pass";
    private static final Pattern REQUEST_ID = Pattern.compile("\"requestId\":\"(demo[0-9a-f]+)\"");

    @LocalServerPort
    private int port;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private PasswordEncoder passwordEncoder;

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    @BeforeEach
    void seedUser() {
        jdbc.execute("SET FOREIGN_KEY_CHECKS = 0");
        for (String table : List.of("t_user", "t_user_role", "t_llm_call_record", "t_llm_outbox",
                "t_metering_usage_event", "t_metering_charge", "t_metering_account", "t_metering_ledger",
                "t_metering_reservation", "t_metering_invoice_item", "t_metering_invoice")) {
            jdbc.execute("TRUNCATE TABLE " + table);
        }
        jdbc.execute("SET FOREIGN_KEY_CHECKS = 1");
        jdbc.update("""
                INSERT INTO t_user (tenant_id, username, password_hash, nickname, role, status,
                                    must_change_password, token_version)
                VALUES (0, 'it_stream', ?, '流式用户', 'ADMIN', 'ACTIVE', FALSE, 0)
                """, passwordEncoder.encode(PASSWORD));
        jdbc.update("""
                INSERT IGNORE INTO t_user_role (tenant_id, user_id, role_id)
                SELECT 0, u.id, r.id FROM t_user u
                JOIN t_role r ON r.tenant_id = 0 AND r.code = 'ADMIN'
                WHERE u.tenant_id = 0 AND u.username = 'it_stream'
                """);
    }

    @Test
    @DisplayName("SSE：delta 增量 + done 汇总（用量/成本/余额），并产生结算流水")
    void streamsDeltasAndSettlesBilling() throws Exception {
        String token = login();

        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl() + "/api/demo/llm/stream"))
                .timeout(Duration.ofSeconds(30))
                .header("Authorization", "Bearer " + token)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(
                        "{\"message\":\"流式演示\",\"model\":\"mock-small\",\"maxTokens\":32}"))
                .build();
        HttpResponse<Stream<String>> response = http.send(request, HttpResponse.BodyHandlers.ofLines());

        assertThat(response.statusCode()).isEqualTo(200);
        String body;
        try (Stream<String> lines = response.body()) {
            body = String.join("\n", lines.toList());
        }
        assertThat(body).contains("event:delta").contains("event:done");
        assertThat(body).contains("\"totalTokens\"").contains("\"cost\"");

        Matcher matcher = REQUEST_ID.matcher(body);
        assertThat(matcher.find()).as("done 事件应包含 requestId").isTrue();
        String requestId = matcher.group(1);

        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM t_metering_ledger WHERE biz_no = ?", Integer.class,
                "settle:llm:" + requestId)).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM t_llm_call_record WHERE request_id = ? AND status = 'SUCCESS'",
                Integer.class, requestId)).isEqualTo(1);
        assertThat(jdbc.queryForObject("""
                SELECT balance FROM t_metering_account WHERE tenant_id = 0 AND app_id = 0
                AND user_id = (SELECT id FROM t_user WHERE username = 'it_stream')
                """, java.math.BigDecimal.class)).isLessThan(new java.math.BigDecimal("10"));
    }

    private String login() throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl() + "/api/auth/login"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(
                        "{\"username\":\"it_stream\",\"password\":\"" + PASSWORD + "\"}"))
                .build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);
        Matcher matcher = Pattern.compile("\"token\":\"([^\"]+)\"").matcher(response.body());
        assertThat(matcher.find()).isTrue();
        return matcher.group(1);
    }

    private String baseUrl() {
        return "http://127.0.0.1:" + port;
    }
}
