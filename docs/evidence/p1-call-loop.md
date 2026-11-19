# P1 验收记录：Mock 调用最小闭环（2026-11-19）

## 1. 范围

| 项 | 内容 |
|---|---|
| Provider SPI | `LlmProvider`（id/supports/invoke）；`LlmProviderRegistry` 按 model 选 provider；mock 只做协议/usage/错误映射，不重试 |
| Mock provider | `MockLlmProvider`：确定性 token、可配置 `delay-ms`、失败模型 `mock-fail`；默认输出 token 由 requestId 哈希决定且不超过 maxTokens |
| 调用编排 | `LlmService`：入参校验 → 重复 requestId 检查 → provider 调用 → 调用记录 + 成功事件 outbox → 结构化日志；异常按 `LlmException` 归一化 |
| 数据 | `LlmCallRepository`（t_llm_call_record）、`LlmOutboxRepository`（t_llm_outbox）；`LlmCallWriter` 用事务保证"记录 + outbox"同成同败 |
| 配置 | `atry.llm.mock.delay-ms / failure-model / default-max-tokens`（application.yml，支持环境变量覆盖） |
| 日志 | `llm.call.started/finished/failed`，字段：requestId/tenantId/appId/userId/provider/model/usage/latency/status/category；**不含 prompt/response** |

## 2. 行为

- 成功：返回统一 `LlmResponse`，落一条 `SUCCESS` 调用记录，事件 `LLM_CALL_COMPLETED` 写 outbox。
- 失败：`mock-fail` → `LlmException(UPSTREAM_5XX, retryable=true, billable=false)`；落 `FAILED` 记录（含 error_category/provider_code），不写成功事件。
- 参数错误：空 requestId/model/messages、非法 maxTokens、过期 deadline → `LlmException(INVALID_REQUEST)`。
- 重复 requestId：拒绝并返回 `INVALID_REQUEST`（并发竞态下的唯一键兜底留到 P2/P4 完善）。
- 流式：P1 返回 501，P2/P3 实现。

## 3. 验证

```bash
# 单元 + 架构门禁
cd server && mvn -B -q test

# P1 集成测试（atry_it，Flyway V400/V500 自动迁移）
IT_DB_URL='jdbc:mysql://127.0.0.1:3306/atry_it?...' ... \
  mvn -B -Pintegration -pl atry-app -Dtest=LlmCallFlowIntegrationTest test
```

结果：

- 单元测试：通过（含 MockLlmProvider 的 token/失败/内容确定性断言）；
- 集成测试：3 个场景通过（成功落库+outbox、失败落库无 outbox、重复 requestId 拒绝）；
- `atry_it`：`t_llm_call_record`/`t_llm_outbox` 有数据；Flyway V400/V500 success；
- 数据边界门禁：通过（新表已登记 llm owner）。

日志样例（`LOGGING_LEVEL_COM_WHERELEE_BASE=INFO`）：

```text
llm.call.started requestId=req-p1-success tenantId=0 appId=0 userId=7 provider=mock model=mock-small stream=false
llm.call.finished requestId=req-p1-success provider=mock model=mock-small inputTokens=2 outputTokens=64 usageSource=PROVIDER latencyMs=101 status=SUCCESS
```

## 4. 已知限制（后续阶段）

- P2：四层超时/重试/熔断/限流/bulkhead、流式、错误分类到 HTTP/业务错误映射。
- P3：JSON 日志 schema、trace 属性、指标/SLO、注解 `@LlmCall`。
- P4：额度预扣/结算、usage 事件消费与计价、账本/对账；outbox 投递器。
- P5：真实 DeepSeek provider（key/模型已核验，见 `deepseek-verification.md`）。
