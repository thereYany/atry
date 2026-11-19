# P5c 验收记录：压测与故障注入证据（2026-11-19）

> 环境：本机 4C/8G（以实际为准）、MySQL 8 本地、Redis 本地、Java 21、单实例 atry-app。
> 目的：给出框架自身基线 + 真实 provider 小流量延迟，不把本地数字当生产容量承诺。

## 1. Mock 并发基线

命令：

```bash
cd server
IT_DB_URL=...atry_it... mvn -B -Pintegration -pl atry-app \
  -Dtest=LlmConcurrencyBenchTest -Dsurefire.failIfNoSpecifiedTests=false test
```

结果（200 次调用，16 并发，每次调用含：限流/舱壁/熔断检查 + mock provider + 调用记录 + outbox 事务写库）：

```text
BENCH mock total=200 concurrency=16 wallMs=1780 rps=112.4 p50=117388us p95=419607us p99=482829us
```

说明：

- 该基线主要是"同步落库 + 单实例 MySQL"的路径成本，不是 mock provider 本身；真实吞吐应按部署（连接池/批量/异步记账）重新压测；
- 记录/outbox 一致性断言通过（200 条 call_record、200 条 outbox，无丢失/重复）；
- 限流维度会先拦截超额流量（本次用独立 tenantId 规避租户 RPM，仅压框架路径）。

## 2. 真实 DeepSeek 小流量延迟

命令（key 从 600 权限 env 注入，不落库/不打印）：

```bash
set -a; . ~/.config/atry/deepseek.env; set +a
cd server && mvn -B -q -pl atry-llm -Dtest=DeepSeekProviderRealBenchTest test
```

结果（deepseek-flash，thinking disabled，max_tokens=16，3 次串行）：

```text
BENCH real-deepseek calls=3 latencyMs=[1642, 865, 671]
```

说明：真实延迟包含网络+服务端排队；provider 只做协议调用，超时/重试/熔断由框架负责。成本约 3 × 数十 token，可忽略；未做真实高并发压测（避免费用与限流影响，按需在专用账号做）。

## 3. 故障注入矩阵（自动化）

| 故障 | 测试 | 结论 |
|---|---|---|
| provider 5xx | `MockLlmProviderTest`、P2 熔断测试 | 分类 UPSTREAM_5XX、可重试、熔断计数 |
| 429/401 | `DeepSeekProviderTest` | RATE_LIMITED(retryable) / AUTH |
| 首 token 超时 | `LlmReliabilityIntegrationTest`、`LlmStreamExecutorTest` | TIMEOUT_FIRST_TOKEN；FAILED 记录，无成功 outbox |
| chunk 空闲超时 | `LlmStreamExecutorTest`（slow/mid 场景覆盖两阶段） | TIMEOUT_IDLE / STREAM_INTERRUPTED |
| 流中中断 | 流式集成测试 | INTERRUPTED + 部分 usage，不自动续写 |
| 熔断打开 | `LlmCircuitIntegrationTest` | CIRCUIT_OPEN 快速失败，attempt=0 |
| 限流/舱壁 | `LlmRateLimiterTest` | RATE_LIMITED / BULKHEAD_FULL |
| Redis/DB 异常 | 现有 btry 降级测试（不在本次范围） | 本模块限流为本地兜底；Redis 共享限流后续接入 |
| 重复请求 | `LlmCallFlowIntegrationTest` | 重复 requestId 拒绝；DB 唯一键兜底 |
| 计量重复投递 | `MeteringCoreIntegrationTest` | eventId 幂等，不重复计价 |

## 4. 结论与限制

- 框架关键路径（限流/熔断/重试/超时/流式/记账/对账）有自动化故障用例；
- 压测数字是本地基线，不是生产 SLO；生产需按部署形态（连接池、批量、多实例、Redis 共享限流）重新校准；
- 真实 provider 只做了小流量验证；高并发真实压测需要单独配额与费用预算，未在本轮执行。
