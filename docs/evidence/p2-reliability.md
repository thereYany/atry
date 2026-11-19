# P2 验收记录：可靠性治理（P2a/P2b，2026-11-19）

> 范围：超时、截止时间、单点重试、重试预算、熔断、多维限流、bulkhead。
> P2c（流式/取消/部分用量）与 Redis 分布式限流留到后续轮次，故本阶段标记"进行中"。

## 1. 已实现

| 能力 | 实现 | 默认值 |
|---|---|---|
| 四层超时配置 | `LlmProperties.Timeouts`（connect/firstToken/chunkIdle/total）；非流式每次尝试取 `min(firstToken,total,deadline剩余)` | 5s/45s/60s/600s |
| deadline 传播 | 入口 `deadlineAt` 与全局 total 取 `min`；每次重试消耗同一预算 | — |
| 单点重试 | `LlmService` 内循环；`LlmRetryPolicy` 只重试 TIMEOUT_*/UPSTREAM_5XX/UPSTREAM_OVERLOADED；4xx/RATE_LIMITED/CONTENT/CONTEXT 不重试 | maxAttempts=3 |
| 退避 | 指数 ×2 + ±20% 抖动，上限 2s；`Retry-After` 透传留 P5 真实 provider | 300ms→2s |
| 重试预算 | `LlmRetryBudget` 按 provider token bucket：容量 10，重试 -1，成功 +0.1；耗尽即停 | — |
| 熔断 | `LlmCircuitBreaker`：provider×model、CLOSED/OPEN/HALF_OPEN；失败率+最小样本；4xx 不计数；OPEN 快速失败（不占线程）；到期半开探测 | 50%/20 calls/30s/5 probes |
| 多维限流 | `LlmRateLimiter`：租户 RPM + provider RPM（令牌桶，容量≈2s 突发）+ provider 在途 Semaphore | 600/6000/100 |
| 失败可见 | 熔断/舱壁/限流拒绝均落 `t_llm_call_record`（FAILED，attempt_count 可能为 0），日志带 category/providerCode | — |
| 不再重试的边界 | circuit 已打开时停止重试；已 committed 语义留给流式（P2c） | — |

## 2. 验证

```bash
cd server
mvn -B -q test          # 单元 + 架构门禁
IT_DB_URL=...atry_it... mvn -B -Pintegration -pl btry-app -Dtest='Llm*IntegrationTest' test
```

结果：

| 测试 | 结果 |
|---|---|
| 单元：Mock provider（成功/失败/flaky）、RetryPolicy、CircuitBreaker、RateLimiter | ✅ |
| 集成：成功落库+outbox、失败落库、重复 requestId | ✅ |
| 集成：flaky 首次失败 → 重试成功，attempt_count=2 | ✅ |
| 集成：mock-slow 超时 → TIMEOUT_TOTAL，attempts=3，FAILED 记录，无 outbox | ✅ |
| 集成：连续失败打开熔断 → 第三次 CIRCUIT_OPEN 快速失败，attempt_count=0 | ✅ |
| 架构门禁（ArchUnit/Modulith/KernelPurity/数据边界） | ✅ |

日志样例：

```text
llm.call.failed requestId=req-p2-timeout provider=mock model=mock-slow category=TIMEOUT_TOTAL retryable=true billable=false attempts=3 latencyMs=842
llm.call.failed requestId=req-p2-c3 provider=mock model=mock-fail category=UPSTREAM_OVERLOADED providerCode=CIRCUIT_OPEN retryable=false billable=false attempts=0 latencyMs=1
```

## 3. 已知限制 / 后续

- **P2c**：流式 mock（首 token/chunk idle/total 两阶段、客户端取消、部分 usage）、慢调用率熔断、`429 Retry-After` 透传。
- **多实例**：熔断/重试预算/限流目前进程内；Redis 共享状态 + 本地兜底在 P4/多实例阶段。
- **路由/fallback**：P2 单 provider；路由链、冷却、降级在 P3/P5。
- **指标**：重试/熔断/限流指标在 P3 接入。
