# P2 验收记录：可靠性治理（P2a/P2b，2026-11-19）

> 范围：超时、截止时间、单点重试、重试预算、熔断（失败率+慢调用率）、多维限流、bulkhead、流式两阶段超时/取消/部分用量。
> P2 已完成；Redis 分布式限流已在 P7-① 补齐（见 [p7-redis-ratelimit](p7-redis-ratelimit.md)）；路由/fallback 仍留到后续阶段。

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
| 慢调用率熔断 | `recordSuccess(key, latencyMs)` 累计慢调用；慢调用率 ≥ 阈值（默认 60%，`slow-call-ms=0` 时禁用）提前打开熔断 | 需按真实延迟校准 |
| 流式两阶段超时 | `LlmStreamExecutor`：首事件 firstToken、之后 chunkIdle、total 兜底；有界队列（32）形成上游背压 | 见 04 §8 |
| 流式中断/取消 | 首 token 前失败不计费；已输出后中断 → `STREAM_INTERRUPTED` + 部分 usage（ESTIMATED）；线程中断 → `CANCELLED`；已 committed 不自动续写 | — |

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
| 单元：流式成功/中途失败/首 token 超时（LlmStreamExecutorTest） | ✅ |
| 集成：流式成功（chunks 拼接、USAGE 入账、outbox、stream=true） | ✅ |
| 集成：流式中断（INTERRUPTED + 部分 tokens、无 outbox） | ✅ |
| 集成：流式首 token 超时（TIMEOUT_FIRST_TOKEN，FAILED） | ✅ |
| 单元：慢调用率打开熔断 | ✅ |
| 架构门禁（ArchUnit/Modulith/KernelPurity/数据边界） | ✅ |

日志样例：

```text
llm.call.failed requestId=req-p2-timeout provider=mock model=mock-slow category=TIMEOUT_TOTAL retryable=true billable=false attempts=3 latencyMs=842
llm.call.failed requestId=req-p2-c3 provider=mock model=mock-fail category=UPSTREAM_OVERLOADED providerCode=CIRCUIT_OPEN retryable=false billable=false attempts=0 latencyMs=1
```

## 3. 已知限制 / 后续

- 流式**首 token 前暂未做重试/换 provider**（当前直接失败；P3/P5 补）；已 committed 后不重试。
- 多实例：熔断/重试预算目前进程内；限流已在 P7-① 改为 Redis 共享令牌桶 + 本地兜底。
- 路由/fallback：P2 单 provider；路由链、冷却、降级在 P3/P5。
- 指标：重试/熔断/限流指标在 P3 接入。
- `Retry-After`/慢调用阈值需真实 provider 数据校准（P5）。
