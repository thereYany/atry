package com.wherelee.base.app.demo;

import com.wherelee.base.modules.contracts.llm.ChatMessage;
import com.wherelee.base.modules.contracts.llm.LlmOperations;
import com.wherelee.base.modules.contracts.llm.LlmRequest;
import com.wherelee.base.modules.contracts.llm.LlmResponse;
import com.wherelee.base.modules.contracts.llm.LlmUsage;
import com.wherelee.base.modules.contracts.metering.MeteringOperations;
import com.wherelee.base.modules.contracts.metering.SubjectRef;
import com.wherelee.base.modules.contracts.metering.UsageQuery;
import com.wherelee.base.modules.contracts.metering.UsageView;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * 演示宿主：一条 curl 走完「LLM 调用 → 用量 → 计价 → 预扣/结算 → 余额」。
 *
 * <p>定位：只做串联与展示，业务逻辑（调用治理、计量计费）全部在 atry-llm / atry-metering 模块内；
 * 这里只允许依赖 contracts，不允许复制内核逻辑。租户/用户取自 JWT 登录态。</p>
 */
@RestController
@RequestMapping("/api/demo")
@ConditionalOnProperty(prefix = "atry.demo", name = "enabled", havingValue = "true", matchIfMissing = true)
public class DemoLlmController {

    private static final Logger log = LoggerFactory.getLogger(DemoLlmController.class);
    private static final long DEMO_APP_ID = 0L;
    private static final long DEADLINE_SECONDS = 30L;

    private final LlmOperations llm;
    private final MeteringOperations metering;
    private final DemoBillingSupport billing;
    private final AtryDemoProperties properties;

    public DemoLlmController(LlmOperations llm, MeteringOperations metering,
                             DemoBillingSupport billing, AtryDemoProperties properties) {
        this.llm = llm;
        this.metering = metering;
        this.billing = billing;
        this.properties = properties;
    }

    /** 同步调用：返回内容、用量、本次成本与结算后余额。 */
    @PostMapping("/llm/chat")
    public DemoChatResponse chat(@RequestAttribute("tenantId") long tenantId,
                                 @RequestAttribute("userId") long userId,
                                 @Valid @RequestBody DemoChatRequest body) {
        SubjectRef subject = billing.subject(tenantId, userId);
        billing.ensureAccount(subject);

        String model = body.model() == null || body.model().isBlank()
                ? properties.defaultModel() : body.model().trim();
        int maxTokens = body.maxTokens() == null ? properties.defaultMaxTokens() : body.maxTokens();
        // 36 字符：满足 t_metering_reservation.reservation_id VARCHAR(40)（"llm:" + requestId）
        String requestId = "demo" + UUID.randomUUID().toString().replace("-", "");

        long startedAt = System.currentTimeMillis();
        LlmResponse response = llm.invoke(new LlmRequest(requestId, tenantId, DEMO_APP_ID, userId, model,
                List.of(new ChatMessage("user", body.message().trim())), maxTokens, false,
                Instant.now().plusSeconds(DEADLINE_SECONDS), "demo"));
        long latencyMs = System.currentTimeMillis() - startedAt;

        LlmUsage usage = response.usage() == null
                ? new LlmUsage(0, 0, 0, 0, 0, LlmUsage.Source.ESTIMATED)
                : response.usage();
        BigDecimal cost = billing.cost(usage, model, Instant.now());
        DemoChatResponse.Balance balance = billing.toView(metering.queryBalance(subject));

        log.info("demo.llm.chat requestId={} tenantId={} userId={} model={} provider={} latencyMs={} usage={} cost={}",
                requestId, tenantId, userId, model, response.provider(), latencyMs, usage.totalTokens(), cost);

        return new DemoChatResponse(requestId, response.provider(), response.modelServed(), response.content(),
                response.finishReason(), latencyMs, usage(usage), new DemoChatResponse.Money(cost, properties.currency()),
                balance);
    }

    /** 当前演示账户余额（首次访问自动开户并注入演示余额）。 */
    @GetMapping("/metering/balance")
    public DemoChatResponse.Balance balance(@RequestAttribute("tenantId") long tenantId,
                                            @RequestAttribute("userId") long userId) {
        SubjectRef subject = billing.subject(tenantId, userId);
        return billing.toView(billing.ensureAccount(subject));
    }

    /** 最近用量事件（来自 outbox 异步投递的计量事件，用于观察「调用后自动入账」）。 */
    @GetMapping("/metering/usage")
    public List<UsageView> usage(@RequestAttribute("tenantId") long tenantId,
                                 @RequestAttribute("userId") long userId,
                                 @RequestParam(defaultValue = "20") int limit) {
        SubjectRef subject = billing.subject(tenantId, userId);
        return metering.queryUsage(new UsageQuery(subject,
                Instant.now().minusSeconds(3600), Instant.now(), limit));
    }

    private DemoChatResponse.Usage usage(LlmUsage usage) {
        return new DemoChatResponse.Usage(usage.inputTokens(), usage.outputTokens(), usage.cachedReadTokens(),
                usage.cachedWriteTokens(), usage.reasoningTokens(), usage.totalTokens(), usage.source().name());
    }
}
