# P7-① 验收记录：Redis 多实例共享限流 + 预扣一致性

> 时间：2026-11-19。环境：本机 MySQL 8 / Redis 6.0 / JDK 21。
> 对应设计：[04 §5 限流](../design/04-可靠性设计.md)、[05 §8 一致性](../design/05-通用计量计费设计.md)。
> 背景：P2 的限流是单实例本地令牌桶，多实例部署时每台各有一份额度（N 台实例 = N 倍租户/上游额度），
> 这是 R5 残余风险；本阶段补齐多实例共享计数，并给出预扣并发一致性证据。

## 1. 交付

| 项 | 内容 |
|---|---|
| 共享令牌桶 | `atry-llm/.../resilience/LlmRateLimiter.java`：租户/provider 维度改为 **Redis Lua 原子扣减**，`TIME` 取 Redis 服务端时间（避免实例时钟漂移）；容量 = max(1, rpm/30)（约 2 秒突发），key TTL = 容量对应回补时间 + 1s |
| 降级策略 | Redis 异常 → 每实例本地令牌桶兜底（**fail-open，不 fail-closed**），记 `infra.redis.fallback{component=llm_rate_limit,operation=redis_error}` 并打 `llm.rate_limit.redis_fallback` WARN |
| 配置 | `atry.llm.rate-limit.backend=auto|redis|local`（默认 auto：有 Redis 就共享） |
| 舱壁 | 在途并发仍是每实例 Semaphore（语义就是「每实例」），不共享 |
| 拒绝指标 | `atry.llm.ratelimit.rejected{scope=tenant|provider}` 在限流点自增 |
| 预扣一致性证据 | `MeteringCoreIntegrationTest#concurrentReserveAcrossInstancesDoesNotOversell`：8 个独立事务并发预扣同一账户（每个 3，余额 10）→ 恰好 3 成功、冻结 9、3 条 PENDING reservation |

## 2. 为什么预扣仍落 DB（而不是 Redis 余额镜像）

设计 05 §8 的首期决策是「准入预扣 + 持久化 reservation，多实例时把预扣迁到 Redis」。
本阶段**没有**做 Redis 余额镜像，理由：

1. 现有 `MeteringService.reserve` 已经是「单行 `SELECT ... FOR UPDATE` + 条件 UPDATE」的事务语义，
   多实例并发下**不会超卖**（本阶段用 8 并发事务测试固定住这条不变量）；
2. Redis 余额镜像要额外承担充值/结算/退款/过期回收的同步与漂移修复，收益是延迟而非正确性；
   当前账户写入路径（每调用一次预扣 + 一次结算）在本机 bench 下并非瓶颈（见 p5-bench）；
3. 迁移触发条件写成可验证的门槛：**热点账户预扣 QPS > ~500 或行锁等待 p99 > 50ms 时**，
   再按 05 §8.1 方案 D 做 Redis 预扣 + 异步落账，并以对账兜底。

## 3. 自动化证据

单测（`mvn -B -pl atry-llm test`，26 tests 全绿）：

| 用例 | 断言 |
|---|---|
| `LlmRateLimiterRedisFallbackTest.fallsBackToLocalBucketWhenRedisFails` | Redis 抛连接异常 → 调用仍被放行（本地桶），且 `infra.redis.fallback` 计数增加 |
| `...sharedRedisDecisionIsAuthoritativeWhenAvailable` | Redis 返回 0 → 直接 RATE_LIMITED（不降级），`atry.llm.ratelimit.rejected{scope=tenant}=1` |
| `...localBackendIgnoresRedis` | backend=local 时不触碰 Redis、不产生降级指标 |
| `LlmRateLimiterTest.limitsTenantRequestsPerMinute / limitsProviderInFlight` | 原有本地语义不回归 |

集成（真实 Redis + MySQL，`mvn -Pintegration -Dtest=...`，9 tests 全绿）：

| 用例 | 结果 |
|---|---|
| `LlmRedisRateLimitIntegrationTest.twoInstancesShareTenantQuota` | 两个独立 `LlmRateLimiter`（模拟两台实例）连同一 Redis：6 次请求合计只放行 **2** 次（rpm=60 → 2 秒容量），key TTL > 0 |
| `...localBackendAllowsPerInstanceQuota` | 对照组 backend=local：两实例各 2 次、合计 **4** 次，且 Redis 无 key |
| `MeteringCoreIntegrationTest.concurrentReserveAcrossInstancesDoesNotOversell` | 8 并发事务 × 预扣 3 / 余额 10 → 成功 3、冻结 9、PENDING 3 条，无超卖 |

## 4. 限制 / 后续

- Redis 全局不可用时，每实例按满额 rpm 兜底，理论上限 = 实例数 × rpm（宁可放宽，不阻断业务）；
  需要精确值时由运维把 rpm 按实例数均分再下发；
- 限流 key 前缀 `atry:llm:rate:`；平台通用注解限流仍是 `atry:rate:`，D2 重命名阶段统一；
- 未做「多实例 outbox 抢占/分片」（P4 遗留的另一条多实例问题，当前 outbox 是单实例轮询）；
- 未做 Redis 余额预扣（触发条件见 §2）。
