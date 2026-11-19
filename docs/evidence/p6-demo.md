# P6 验收记录：可演示收口（宿主接入 + 成本可观测）

> 时间：2026-11-19。环境：本机 4C/8G、MySQL 8、Redis 6.0、JDK 21、Spring Boot 4.1.2，单实例 `btry-app`。
> 目标：把 atry-llm / atry-metering 接到演示宿主，一条命令看到「调用 → 用量 → 计价 → 预扣/结算 → 余额 → 用量事件」；
> 同时补齐 P3 遗留的成本可观测（结构化日志 cost 分组 + `atry.llm.cost` 指标）。

## 1. 交付物

| 项 | 位置 | 说明 |
|---|---|---|
| 演示端点 | `server/btry-app/src/main/java/com/wherelee/base/app/demo/DemoLlmController.java` | `POST /api/demo/llm/chat`、`GET /api/demo/metering/balance`、`GET /api/demo/metering/usage`，全部走 JWT 保护 |
| 演示账户 | `server/.../demo/DemoBillingSupport.java` | 首访幂等开户并注入演示余额（bizNo `demo-seed:<tenant>:<user>`，重复调用不重复入账） |
| 错误映射 | `server/.../demo/LlmExceptionHandler.java` | `LlmErrorCategory` → HTTP（402 配额 / 429 限流 / 504 超时 / 502 上游 / 503 过载），保留 category/retryable/billable |
| 成本可观测 | `atry-llm/.../LlmEventLogger.java`、`LlmMetrics.java`、`LlmService.java` | `llm.call.finished` 的 cost 分组（amount / amount_micros）；`atry.llm.cost`（tenant×model）；结算/日志/指标使用同一价格路径 |
| 一键脚本 | [docs/bench/p6-demo.sh](../bench/p6-demo.sh) | 登录 → 调用 → 余额 → 用量事件 |
| 配置 | `atry.demo.*` | `enabled` / `currency` / `initial-balance` / `default-model` / `default-max-tokens`，可 `ATRY_DEMO_ENABLED=false` 关闭 |

## 2. 复现步骤

```bash
# 1) 构建内核模块（首次）
cd server && mvn -B -DskipTests install

# 2) 启动演示宿主（空库首次启动会自动执行 8 个 Flyway 迁移）
DB_URL='jdbc:mysql://127.0.0.1:3306/atry_dev?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai' \
DB_USER=btry DB_PASSWORD=btrypass \
REDIS_HOST=127.0.0.1 REDIS_PORT=6379 \
SEED_ADMIN_PASSWORD=Demo1Pass \
mvn -B -pl btry-app spring-boot:run

# 3) 另开终端：一条命令看完整链路
docs/bench/p6-demo.sh
```

## 3. 实测输出（真实运行，2026-11-19）

```text
== 1/5 登录 admin
  token ok (236 chars)

== 2/5 演示账户余额（首次访问自动开户并注入演示余额）
{"balance":9.999858,"frozen":0.000000,"currency":"CNY"}

== 3/5 一次 LLM 调用（mock provider 的 mock-small 模型）
{"requestId":"demo13a06fabf1b2445480ac68c2ef20ca92","provider":"mock","model":"mock-small",
 "content":"mock reply: 用一句话介绍 atry 基座","finishReason":"stop","latencyMs":56,
 "usage":{"inputTokens":4,"outputTokens":64,"cachedReadTokens":0,"cachedWriteTokens":0,
          "reasoningTokens":0,"totalTokens":68,"source":"PROVIDER"},
 "cost":{"amount":0.000068,"currency":"CNY"},
 "balance":{"balance":9.999790,"frozen":0.000000,"currency":"CNY"}}

== 4/5 结算后余额（应小于上一步的初始余额）
{"balance":9.999790,"frozen":0.000000,"currency":"CNY"}

== 5/5 最近用量事件（outbox 异步投递，等 2s 后可见 input/output 两条）
[{"eventId":"llm-usage:demo13a06fabf1b2445480ac68c2ef20ca92:out","meterKey":"llm.output_tokens",
  "quantity":64.000000,"unit":"token","dimensionsJson":"{\"model\": \"mock-small\", \"provider\": \"mock\"}",...},
 {"eventId":"llm-usage:demo13a06fabf1b2445480ac68c2ef20ca92:in","meterKey":"llm.input_tokens",
  "quantity":4.000000,"unit":"token","dimensionsJson":"{\"model\": \"mock-small\", \"provider\": \"mock\"}",...}]
```

要点：**一次调用 = 一条调用记录 + 两条用量事件（input/output，异步 outbox）+ 一条账本流水（settle）+ 可复算的成本**；
余额 `9.999858 → 9.999790`，差值 `0.000068` 与响应中的 cost 完全一致（4 in + 64 out，默认价 1e-6 CNY/token）。

## 4. 成本可观测证据

同一请求的结构化日志（`LLM_EVENTS`，一条 JSON 还原一次调用）：

```json
{"event_name":"llm.call.finished","event_version":1,
 "subject":{"tenant_id":0,"app_id":0,"user_id":1},
 "llm":{"provider":"mock","model_requested":"mock-small","model_served":"mock-small",
        "operation":"chat","stream":false},
 "usage":{"input_tokens":4,"output_tokens":64,"total_tokens":68,"source":"PROVIDER"},
 "latency":{"total_ms":51},"outcome":{"status":"SUCCESS"},
 "cost":{"currency":"CNY","amount":0.000068,"amount_micros":68},
 "attempt_count":1,"stream":false,"request_id":"demo13a06fabf1b2445480ac68c2ef20ca92"}
```

- 修复前：`cost.amount_micros` 恒为 0（P3 占位，未与 P4 联动）；
- 修复后：日志 / 指标 / 结算统一走 `LlmService.actualCost()` 的价格路径，`amount_micros=68` 与账本扣减一致；
- 指标：`atry.llm.cost{tenant,model}` 同步累加（tenant 只允许出现在成本类指标，其余指标保持低基数）；
- 价格不可用时的行为：**不按 0 结算**（跳过 settle，交给过期回收/对账兜底），日志与指标成本记 0，并打 `llm.billing.price_unavailable` 告警日志。

## 5. 自动化测试

| 套件 | 结果 |
|---|---|
| `mvn -B test`（单测 + 架构/Modulith/KernelPurity/数据边界门禁） | 129 tests，0 failures，0 errors，2 skipped |
| `mvn -B -Pintegration test`（真实 MySQL + Redis） | 29 tests，0 failures（含本阶段新增 2 个用例） |
| 新增 `DemoLlmApiIntegrationTest` | 2/2 通过：①调用→余额扣减→账本 settle 一条；②未登录 401、mock-fail 重试后 502 + category=UPSTREAM_5XX |

`DemoLlmApiIntegrationTest` 覆盖的核心断言：

- 演示端点登录后返回内容/用量/成本，`usage.totalTokens = input + output`；
- 响应余额 = `GET /api/demo/metering/balance` 余额，且小于调用前余额；
- `t_llm_call_record` 1 条、`t_metering_ledger` 中 `settle:llm:<requestId>` 1 条（不重不漏）；
- 未带 token → 401；`mock-fail` → 502 且响应保留 `category/retryable`。

## 6. 关键取舍

| 取舍 | 决策 | 理由 |
|---|---|---|
| 演示端点放哪 | `btry-app` 宿主层，只依赖 contracts | 内核保持业务无关；换宿主不影响模块 |
| 租户/用户来源 | 只取 JWT 登录态，请求体不允许指定 | 演示端点不能成为越权入口 |
| 错误映射位置 | 宿主层 `@RestControllerAdvice`（最高优先级） | 内核只保证稳定分类，HTTP 语义属于接入方 |
| 账户种子 | 幂等充值（`demo-seed:<tenant>:<user>`）+ 首访开户 | 重启/清库后自动恢复，不需要额外 bootstrap 顺序协调 |
| 成本计算 | 与结算共用 `actualCost()` 价格路径 | 日志、指标、余额三处数字必须一致 |

## 7. 限制 / 后续

- 演示只有同步调用；**SSE 流式演示端点未做**（流式治理 P2 已有测试覆盖，但宿主未暴露给前端）；
- 流式调用仍未接预扣/结算（只有成本日志/指标），与 P4 记录的「流式未计费」一致；
- 自动开户 + 注入演示余额是 demo 语义，不是产品功能；生产宿主应关闭 `atry.demo.enabled`；
- 成本按调用时刻现算价格（未缓存），高频下需要价格缓存/预聚合；
- cost 日志分组暂只有 amount / amount_micros，设计中的 `price_version` / `unit_price` / `billing_event_id` 后续补；
- `@BudgetGuard`、Redis 多实例共享限流/预扣仍未做（P3/R5 遗留）。
