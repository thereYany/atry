# P7-③ 验收记录：SSE 流式演示端点 + 流式计费

> 时间：2026-11-19。对应设计：[04 §8 流式容错](../design/04-可靠性设计.md)、[02 §5 时序](../design/02-架构设计.md)。
> 背景：P2 已有流式治理（两阶段超时/取消/部分 usage），但 `LlmService.stream` 一直**没有接预扣/结算**
> （P4 记录里明确写了「流式未计费」）；宿主也没有 SSE 演示端点。本阶段两项一起补。

## 1. 流式计费语义（`LlmService.stream`）

| 场景 | 行为 |
|---|---|
| 正常完成 | 调用前按估算预扣（budgetRef 非空时）；完成后按**实际 usage** 结算（多退少补），写 `settle:llm:<requestId>` 账本 |
| 已输出部分内容后失败/中断 | 按部分 usage 结算（可计费的部分照收，`LlmError.billable` 语义不变），记 `INTERRUPTED` 调用记录 |
| 客户端断开 / 取消（CANCELLED） | 取消上游生成，**释放预扣**（不产生扣费/账本），调用记录 `FAILED`，预留单 `RELEASED` |
| 预扣阶段余额不足 | 与同步路径一致：`QUOTA_EXHAUSTED` → 402 |

实现要点：

- `stream()` 与 `invoke()` 复用同一套 `reserveBudget / settleBudget / releaseBudget`；
- 成本计算与同步路径共用 `actualCost()`（同一价格路径），日志/指标/账本三处数字一致；
- 部分 usage 结算条件：`cost > 0` 且请求带 budgetRef；否则走 release 兜底。

## 2. SSE 演示端点

`POST /api/demo/llm/stream`（JWT 保护，`text/event-stream`）：

| 事件 | data | 说明 |
|---|---|---|
| `delta` | `{"delta":"..."}` | 增量文本 |
| `done` | 完整 `DemoChatResponse`（content/usage/cost/balance/requestId…） | 流结束；此时预扣已结算 |
| `error` | `{"category","message"}` | 分类错误（402/429/504/502…） |

- 在虚拟线程执行器 `llmCallThreadExecutor` 上跑，容器线程立即返回 `SseEmitter`；
- 客户端断开后再写入会抛 `IOException` → 回调内转成 `CANCELLED` 向上传播 → 框架取消上游 + 释放预扣；
- 首访自动开户注入演示余额，与同步端点一致。

```bash
TOKEN=...  # 登录 token
curl -N -X POST http://127.0.0.1:8084/api/demo/llm/stream \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"message":"流式演示","model":"mock-small","maxTokens":32}'
```

## 3. 自动化证据

| 测试 | 断言 |
|---|---|
| `LlmStreamBillingIntegrationTest.streamingCallSettlesActualUsage` | chunks 非空、usage.totalTokens>0；账本 `settle:llm:req-stream-billing` 1 条；余额下降、冻结归零；调用记录 `SUCCESS` |
| `...cancelledStreamReleasesReservation` | sink 首 chunk 抛 CANCELLED → 异常分类 CANCELLED；余额仍 10、冻结 0、无 settle 账本；预扣单 `RELEASED`；调用记录落库 |
| `DemoStreamApiIntegrationTest.streamsDeltasAndSettlesBilling` | 真实 HTTP（RANDOM_PORT）+ 真实 MySQL/Redis：SSE 含 `event:delta` / `event:done`；done 里的 requestId 对应 1 条结算账本 + 1 条 SUCCESS 调用记录；账户余额 < 10 |

结果：3/3 通过（含 context 启动）。相关单测/集成在 `mvn -Pintegration test` 全量中回归。

## 4. 限制 / 后续

- SSE 端点仅演示同步 sink；未做服务端节流/背压策略（SSE 写入阻塞时靠 TCP 自然背压，设计 04 §6）；
- 客户端断开时**不结算已生成 token**（按 CANCELLED 释放预扣）；若产品要求"已生成即计费"，需要在 sink 层累计部分 usage 后再结算（已有 `outcome.usage()` 路径可复用）；
- 流式成本只在结束时可算，无法像同步调用一样在响应里实时回显（demo 的 done 事件在结算后发出，是可行折中）；
- 多实例流式连接的取消传播/连接数上限未做专项验证（设计 04 §6 目标，需要压测）。
