# 03 横切设计：注解、AOP、日志与可观测

> 目标：让"一次 LLM 调用/一次计量入账"从入口到结算都能被一条 trace + 一条结构化日志还原；人类与 agent 都能据此定位问题；横切逻辑零侵入业务代码、可组合、可关闭、可测试。

## 1. 注解目录（Declarative Surface）

### 1.1 已有注解（btry-platform，复用）

| 注解 | 切面 | 作用 | 顺序建议 |
|---|---|---|---|
| `@Measured(name)` | `MeasuredAspect` | 方法级计时（Micrometer） | 最外层（包含所有重试/等待） |
| `@RateLimit(key, limit, windowSeconds)` | `RateLimitAspect` | Redis+本地限流（429） | 入口前、调用前 |
| `@Idempotent(key, windowSeconds)` | `IdempotentAspect` | 防重复提交（Redis SETNX + 本地兜底） | 限流之后、执行前 |
| `@RequirePermission(code)` | 权限切面 | 方法级 RBAC | 入口最前（鉴权） |
| `@AdminAudit(action, targetType)` | `AdminAuditAspect` | 管理端操作审计 | 外层、执行后记录结果 |

### 1.2 新增注解（按模块归属）

| 注解 | 所属模块 | 目标 | 语义 | 关键属性 |
|---|---|---|---|---|
| `@LlmCall(name, meter?, budgetRef?, sample?)` | `btry-llm` | 方法（同步非流式） | 统一采集：路由结果、attempt、usage、TTFT、耗时、错误、成本；产出 `llm.call.*` 日志/指标/span | name、meterKey(可选)、budgetRef、logContent(默认 false) |
| `@LlmStream(name, meter?, budgetRef?)` | `btry-llm` | 方法（SSE/流式入口） | 流式调用观测与取消语义；不包裹内容输出 | 同上 |
| `@UsageMetered(meterKey, quantity, unit)` | `btry-metering` | 方法（任意 API 计量） | 成功后按 SpEL/参数提取数量并调用 `MeteringOperations.recordUsage` | meterKey、quantity 表达式、dimensions、when |
| `@BudgetGuard(budgetRef, onExceed)` | `btry-llm` | 方法 | 调用前检查租户/项目预算，超限快速拒绝/降级 | budgetRef、action(REJECT/DEGRADE) |
| `@Sensitive` | `btry-platform` | 字段/参数/返回值 | 标记日志脱敏（配合统一序列化器），避免 prompt/密钥落盘 | policy(MASK/HASH/DROP) |
| `@BackgroundJob(name, idempotent?)` | `btry-platform` | 方法 | 定时/异步任务的统一日志、锁、耗时、失败告警 | name、lockKey |

> 设计约束：
> - 注解只做"声明"，不承载业务逻辑；切面失败不得改变业务语义（限流/鉴权/幂等除外，它们是保护语义）。
> - **流式用量无法用 Around 注解可靠获取**：`@LlmStream` 只负责入口观测、取消与错误归一化；用量由框架 `StreamBridge`/`UsageExtractor` 显式上报，不走注解反射。
> - AOP 只适用于 Spring 代理调用（外部调用）；内部方法自调用不生效，文档与测试需覆盖这一限制。

## 2. 切面顺序（Order 约定）

数字越小越靠外：

```text
RequirePermission/Auth      order = -300   （只读/本地，最快失败）
Trace/Span 入口             order = -250
@Measured                   order = -200
@RateLimit                  order = -150
@Idempotent                 order = -100
@BudgetGuard                order = -50
业务方法 / @UsageMetered    order = 0
@AdminAudit                 order = 100    （执行后无论成败都记录）
```

- **Trace 必须最外层**：否则限流拒绝/幂等冲突没有 trace 上下文，排障断链。
- `@Measured` 在最外层之一，但应包含重试总耗时；框架内部另打 `attempt` 维度指标（不依赖注解）。
- 切面之间通过 `RequestContext`（MDC/ScopedValue）共享 `requestId/traceId/tenantId`，禁止静态可变全局字段。
- 每个切面单独测试"命中/未命中/异常/顺序"，并验证不改变返回值和异常类型。

## 3. 结构化日志 schema

### 3.1 事件命名（稳定，只增不改）

| event_name | 级别 | 时机 | 是否采样 |
|---|---|---|---|
| `llm.call.started` | INFO | 进入调用 | 头采样 |
| `llm.call.finished` | INFO | 成功（含部分成功） | 头采样 + 慢/大 token 尾采样 |
| `llm.call.failed` | WARN/ERROR | 最终失败 | **不采样**（错误必须留） |
| `llm.stream.interrupted` | WARN | 流中断/客户端断开 | 不采样 |
| `metering.usage.recorded` | INFO/DEBUG | 用量入账 | 高频可采样，但入账事实不依赖日志 |
| `metering.reservation.placed/settled/expired` | INFO | 预扣生命周期 | 异常/超期不采样 |
| `metering.recon.mismatch` | ERROR | 对账差异 | 不采样 |
| `billing.invoice.finalized` | INFO | 出账 | 不采样 |
| `llm.policy.hit` | DEBUG | 命中的路由/策略版本 | 可采样，用于审计 |

### 3.2 Envelope 与字段分组（核心）

一条 JSON 一行（JSON Lines），顶层固定 envelope + 分组对象；字段名一经发布只增不改：

```json
{
  "event_name": "llm.call.finished",
  "event_version": 1,
  "ts": "2026-11-19T18:40:00.123+08:00",
  "severity": "INFO",
  "service": "atry",
  "trace": {"trace_id": "...", "span_id": "...", "parent_span_id": "...",
            "request_id": "req_...", "parent_request_id": null},
  "subject": {"tenant_id": "t_1", "app_id": "app_1", "user_id": "u_1",
              "api_key_id": "ak_1"},
  "llm": {"operation": "chat", "provider": "deepseek", "model_requested": "deepseek-chat",
          "model_served": "deepseek-chat-2026-09", "stream": false,
          "prompt_name": "support", "prompt_version": "v3", "route": "default",
          "deployment": "deepseek-primary",
          "attempt": 2, "max_attempts": 3, "fallback_chain": ["deepseek->mock"]},
  "usage": {"input_tokens": 820, "output_tokens": 1500, "cached_read_tokens": 0,
            "cached_write_tokens": 0, "reasoning_tokens": 0, "total_tokens": 2320,
            "source": "PROVIDER", "estimated": false},
  "latency": {"queue_ms": 12, "ttft_ms": 480, "generation_ms": 3200, "total_ms": 3692,
              "tpot_ms": 21.3},
  "outcome": {"status": "ok", "http_status": 200, "provider_code": null,
              "error_type": null, "error_category": null, "retryable": false,
              "billable": true, "finish_reason": "stop", "partial_stream": false},
  "cost": {"price_version": "2026-11-01", "currency": "CNY",
           "unit_price": "0.0000012", "amount": "0.002664", "billing_event_id": "ue_..."},
  "content": {"captured": false, "ref": null, "redaction_policy": "default-no-content"},
  "sampling": {"kept": true, "reason": "slow"},
  "error_fingerprint": null
}
```

字段规则：

- `llm.deployment`：命中的具体接入（provider deployment）；与 `model_requested/model_served` 一起用于 fallback/成本归因。


- **金额/单价用十进制字符串 + currency**，禁止浮点；token 用整数。
- 时间戳 ISO-8601 带时区；耗时毫秒整数。
- `error_fingerprint`：`category + provider_code + stable_hash(stage)`，用于聚合与告警去重（不含用户数据）。
- `body` 可放一句人类可读摘要（如 `"deepseek chat ok, 2320 tokens, 3.7s"`），机器字段不放 body。
- 内容（prompt/response）默认 `captured=false`；需要审计时写对象存储，日志只留 `content_ref` 与 hash。

### 3.3 MDC / 上下文传播

固定键：`trace_id`、`span_id`、`request_id`、`tenant_id`、`app_id`、`user_id`、`api_key_id`、`provider`、`model`、`attempt`。

- 由入口 Filter/Interceptor 建立；虚拟线程场景用 `ScopedValue`/显式传递，禁止依赖 `InheritableThreadLocal`（虚拟线程不继承）。
- 异步结算/outbox 消费从消息头恢复上下文，确保"同一 request_id 的落账日志"可关联。

### 3.4 采样、脱敏与保留

| 策略 | 规则 |
|---|---|
| 头采样 | 默认 10%（可配）；线上可调 |
| 尾采样（必留） | 错误、超时、429、慢调用（>p99）、大 token（>阈值）、采样标记请求 |
| 计费事实 | 不依赖日志：用量事件落 DB（不采样），日志仅观测 |
| 脱敏 | 密钥/Authorization/cookie 永不打印；prompt/response 默认不落；需内容审计时走引用+访问控制；统一序列化器对 `@Sensitive` 做 MASK/HASH/DROP |
| 保留 | 应用日志 7–30 天；调用记录（DB）按合规/成本设保留期与分区归档 |

### 3.5 面向 agent 的排障友好性

- **一条日志还原一次调用**：request_id + attempt + error_fingerprint + route + usage + cost + latency 分解齐全。
- 稳定枚举 + 固定字段名，便于 agent 用 grep/jq 过滤、按字段聚合；避免把关键信息塞进 message。
- 慢/失败日志自动附带"下一步排查建议"（例如 `retryable=true, next=retry_with_backoff`；`error_type=quota_exhausted, next=top_up_or_degrade`）。
- 提供查询片段（文档中维护）：
  - 按 request_id 还原：`jq 'select(.trace.request_id=="req_x")'`
  - 按错误指纹聚合：`jq -r '.error_fingerprint' | sort | uniq -c | sort -nr`
  - 429 分布：`jq 'select(.outcome.error_type=="rate_limited") | .llm.provider'`
- 禁止日志中出现可变对象/异常堆栈全文的高频打印；错误只打印一次完整堆栈（WARN/ERROR），其余用 fingerprint。

## 4. Tracing

### 4.1 Span 结构

```text
HTTP request span（已有 MVC/OTel）
└─ gen_ai.client.inference（一次逻辑调用；name = "{operation} {model}"；重试合并进同一 span）
   ├─ atry.llm.attempt（内部子 span，可选，仅用于延迟分解；不按 gen_ai.* 导出）
   └─ metering.settle（异步 worker 或同链路；tags: amount/usage_event）
```

> OTel GenAI 约定：**一次逻辑推理调用只导出一个 inference span**，自动重试不新增 inference span、attempt 不进入 span 名。内部的 attempt 明细以 span event/属性或非 `gen_ai.*` 命名的子 span 记录，避免破坏 GenAI 语义与后端聚合。

### 4.2 OTel GenAI 映射（集中在适配层）

| 内部字段 | OTel GenAI 属性（semconv 实验版） | 说明 |
|---|---|---|
| operation | `gen_ai.operation.name` | chat/embeddings/... |
| provider | `gen_ai.provider.name` | openai/anthropic/deepseek/… |
| model_requested | `gen_ai.request.model` | |
| model_served | `gen_ai.response.model` | |
| usage.* | `gen_ai.usage.input_tokens/output_tokens/cache_read.input_tokens/...` | 按版本映射，缺失不报错 |
| finish_reason | `gen_ai.response.finish_reasons` | |
| ttft | `gen_ai.response.time_to_first_chunk` | 流式 |
| stream | `gen_ai.request.stream` | |
| error | `error.type` + 内部 error_category | 内部枚举不直接暴露为高基数 label |
| 内容 | `gen_ai.input.messages`/`output.messages`（Opt-In） | 默认关闭 |

- semconv 仍为 Development，**锁定版本**；升级只改映射层，内部 schema 不变。
- 一次逻辑调用一个 `gen_ai.client.inference` span；重试合并进同一 span（attempt 以 span event/内部属性记录）。
- OTel 指标：inference 使用 `gen_ai.client.inference.*`（duration/token 等）；`gen_ai.client.operation.duration` 明确**不适用于 inference**（官方文档注明）。
- 异步结算链路通过 outbox 消息头传播 trace 上下文（或新建 link span）。

## 5. Metrics 与 SLO

### 5.1 指标（低基数标签）

| 指标 | 类型 | 标签 | 用途 |
|---|---|---|---|
| `atry.llm.calls` | counter | provider, model, operation, status | 调用量/成功率（新模块统一 `atry.*` 前缀；既有 btry 指标保留 `btry.*`，不强行改名，看板做命名空间映射） |
| `atry.llm.duration` | histogram | provider, model, stream, status | 总延迟 |
| `atry.llm.ttft` | histogram | provider, model | 首 token 延迟 |
| `atry.llm.tpot` | histogram | provider, model | 每输出 token 时间 |
| `atry.llm.tokens` | counter | provider, model, token_type | token 用量 |
| `atry.llm.errors` | counter | error_type, provider, retryable | 错误分类 |
| `atry.llm.retries` | counter | provider, outcome | 重试率 |
| `atry.llm.fallbacks` | counter | from/to provider | 降级率 |
| `atry.llm.ratelimit.rejected` | counter | scope(tenant/provider/global) | 限流拒绝 |
| `atry.llm.cost` | counter(sum) | tenant, model | 成本归因 |
| `atry.metering.usage.events` | counter | meter, source(provider/estimated) | 计量事件 |
| `atry.metering.settlement.queue.depth` | gauge | — | 结算积压 |
| `atry.metering.settlement.latency` | histogram | — | 结算延迟 |
| `atry.metering.recon.diff` | gauge | period, type | 对账差异 |
| `atry.metering.balance.negative` | gauge | tenant | 负余额告警 |

标签纪律：request_id/trace_id/user_id/prompt 不进 metric label（高基数/敏感）；tenant 仅在成本类指标（可接受基数），必要时用 exemplar 关联 trace。

### 5.2 SLO（首期建议值，压测后校准）

| SLO | 目标 | 告警 |
|---|---|---|
| 网关可用性（非 5xx+超时成功率） | ≥ 99.9% | 多窗口燃烧率（5m/1h 14.4×、30m/6h 6×、6h/3d 1×） |
| 网关自身开销 p99 | < 15ms（不含上游） | p99 超阈持续 5m |
| 首 token p95（按 provider 基线） | 相对基线 ≤ +20% | 多窗口 |
| 429/限流拒绝比例 | < 1%（非过载时） | 突增告警 |
| 结算延迟 p99 | < 2s | 队列深度 + 延迟双阈值 |
| 账实差异 | = 0（容差内） | 对账任务直接告警 |
| 成本偏差（预估 vs 实际，按日） | ≤ 5% | 日报 + 告警 |

## 6. 错误分类（Error Taxonomy）

三轴：**来源 × 可重试性 × 是否计费**。内部枚举稳定，provider 原始错误另存 `provider_code`。

| error_type | 来源 | 可重试 | 计费 | 典型 HTTP/场景 | 处理 |
|---|---|---|---|---|---|
| `auth_failed` | upstream | 否 | 否 | 401/403 | 终止、告警（凭证问题） |
| `invalid_request` | upstream/client | 否 | 否 | 400/422 | 终止、返回 400 |
| `rate_limited` | upstream | **是**（按 Retry-After） | 否 | 429 | 退避/排队/告警 |
| `quota_exhausted` | upstream/account | 否 | 否 | 429(insufficient_quota) | 终止、降级/充值提示 |
| `timeout_connect` | gateway | 是 | 否 | 建连超时 | 重试/fallback |
| `timeout_first_token` | gateway | 是（未 committed） | 否 | TTFT 超时 | 重试/fallback |
| `timeout_idle` | gateway | 已 committed 则否 | 按已产生 token | chunk 空闲超时 | 中断、部分结算 |
| `upstream_5xx` | upstream | 是 | 否 | 500/502/503/504 | 退避重试/fallback |
| `upstream_overloaded` | upstream | 是 | 否 | 529/overloaded | 退避/降级 |
| `content_filtered` | upstream | 否 | 视策略 | 内容拦截 | 终止、返回业务码 |
| `context_too_long` | upstream | 否 | 否 | 400 context_length | 终止、提示裁剪 |
| `stream_interrupted` | gateway/upstream | 部分 | 按已产生 token | 流断 | 记录、结算、告警 |
| `cancelled` | client | 否 | 按已产生 token | 客户端断开 | 取消上游、结算 |
| `internal_error` | gateway | 视情况 | 视情况 | 500 | 告警、降级 |

- 错误分类由 adapter 的 `mapError` 统一产出；框架不再根据字符串猜。
- `retryable`/`billable` 是策略计算结果，必须同时出现在日志与 metric 标签。

## 7. 测试与验收（本设计点的）

1. 注解/切面：顺序、命中、异常透传、自调用失效场景有单测；日志字段 schema 用 JSON Schema 校验（成功/失败/流中断样例）。
2. 日志脱敏：构造含密钥/PII 的请求，断言日志无明文；prompt/response 默认不出现。
3. Trace：一次调用断言 span 树（invoke + attempts + settle）与关键属性；OTel 映射单测（semconv 版本锁定）。
4. 指标：每个指标有对应测试（counter/histogram 值、标签基数受控）。
5. 性能：开启全部切面 vs 关闭，网关自身 p99 开销 < 15ms；日志异步不阻塞请求线程。
6. agent 可用性：提供一个"按 request_id 还原调用"的脚本/查询文档，并用样例日志验证。
