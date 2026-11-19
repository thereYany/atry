# P3 验收记录：可观测与 AOP（2026-11-19）

## 1. 结构化日志

- 组件：`LlmEventLogger`（logger name `LLM_EVENTS`），一条 JSON 一个事件；
- envelope：`event_name/event_version/timestamp/trace_id/request_id`；
- 分组：`subject`（tenant/app/user）、`llm`（provider/model/operation/stream）、`usage`（分项 token+source）、`latency`、`outcome`（status/error_category/provider_code/retryable/billable）、`cost`、`sampling`、`content`（固定 `content_captured:false`）；
- 绝不记录 prompt/response；`provider_code` 截断 64 字符；日志 logger 与业务日志分离，便于采集/脱敏。

样例（真实集成测试输出）：

```json
{"event_name":"llm.call.finished","event_version":1,"timestamp":"2026-11-19T12:09:06.200614580Z","trace_id":null,"subject":{"tenant_id":0,"user_id":7,"app_id":0},"llm":{"provider":"mock","model_requested":"mock-small","model_served":"mock-small","operation":"chat","stream":false},"usage":{"total_tokens":66,"input_tokens":2,"source":"PROVIDER","output_tokens":64,"reasoning_tokens":0,"cached_read_tokens":0,"cached_write_tokens":0},"latency":{"total_ms":138},"outcome":{"status":"SUCCESS"},"cost":{"currency":"CNY","amount_micros":0},"sampling":{"sampled":true},"content":{"content_captured":false},"attempt_count":1,"stream":false,"request_id":"req-p1-success"}
```

事件类型：`llm.call.started` / `llm.attempt.failed` / `llm.call.finished` / `llm.call.failed` / `llm.annotation.call`。

## 2. 指标

`LlmMetrics`（Micrometer；低基数标签 provider/model/status，不放 tenant/user/prompt）：

| 指标 | 类型 | 标签 |
|---|---|---|
| `atry.llm.calls` | counter | provider/model/operation/stream/status |
| `atry.llm.duration` | timer | provider/model/stream |
| `atry.llm.ttft` | timer | provider/model（流式首 chunk） |
| `atry.llm.tokens` | counter | provider/model/token_type（input/output/cached_read/reasoning） |
| `atry.llm.errors` | counter | provider/category/retryable |
| `atry.llm.retries` | counter | provider/outcome |
| `atry.llm.ratelimit.rejected` | counter | scope（预留） |
| `atry.llm.annotated.calls` | counter | name/status/mode |

集成测试断言 `atry.llm.calls{status=SUCCESS}` 与 `atry.llm.tokens{token_type=input}` 已产生。

## 3. OTel GenAI 属性

`LlmSpanTags` 集中映射（内部字段稳定，semconv 升级只改这里）：

- request：`gen_ai.operation.name=chat`、`gen_ai.provider.name`、`gen_ai.request.model`、`gen_ai.request.stream`、`gen_ai.request.max_tokens`；
- response：`gen_ai.response.model`、`gen_ai.response.finish_reasons`、`gen_ai.usage.input_tokens/output_tokens`；
- error：`error.type`、`atry.llm.provider_code`；
- 一次逻辑调用一个 `llm.invoke` span，重试在 span 内（attempt 作为 tags，不新增 inference span）。

## 4. 注解与切面

| 注解 | 模块 | 行为 |
|---|---|---|
| `@LlmCall` | atry-llm | 同步调用耗时/成功失败 + `atry.llm.annotated.calls` + 结构化 annotation 事件 |
| `@LlmStream` | atry-llm | 流式入口生命周期观测，不缓存/拦截 chunk |
| `@UsageMetered` | atry-metering | 方法成功后按 SpEL（`#result`/参数名/`#p0`）提取数量调用 `MeteringOperations.recordUsage`；计量失败不回滚业务 |

切面顺序：`@Order(0)`（LLM 观测）→ `@Order(50)`（计量）；与 platform 的限流/幂等/审计切面按设计顺序共存。
`@BudgetGuard` 依赖 metering 额度接口，留到 P4 与预扣一起实现（避免空壳注解）。

## 5. 测试

- 单元：`LlmEventLoggerTest`（JSON 字段/无内容）、`LlmMetricsTest`（计数器/timer/token）、`LlmCallAspectTest`（成功/失败）、`UsageMeteredAspectTest`（SpEL 数量提取）；
- 集成：P1/P2 全部 9 个场景通过，且成功用例断言 metrics 已产生；
- `mvn test` 全绿（含架构门禁）。

## 6. 已知限制 / 后续

- 采样当前固定 `sampled=true`，采样率/内容审计开关在 P4/P5 接入；
- OTel exporter 在部署层接入（本地 JFR/控制台可验证 span 字段）；
- Prometheus 看板/告警规则在 P5 用压测数据校准；
- `@BudgetGuard`、成本指标（cost）待 P4 计量计费联动。
