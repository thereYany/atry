# P4 验收记录：通用计量计费（进行中，2026-11-19）

> P4 已完成：事件/计价/幂等、账户/冻结、预扣/结算/释放、账本、并发防超卖、LLM 预扣联动、outbox 投递、充值、出账、对账 L1–L3、过期预扣回收。

## 1. 数据与种子

- 复用 P0 的 `t_metering_*` 8 张表；
- Flyway `V501__metering_seed`：默认 meter `llm.tokens`（SUM/token）、价格书 `default`(CNY) v1、费率 0.000001/token（CEIL）。

## 2. 计量与计价

`MeteringService`：

- `recordUsage`：按 `eventId` 幂等插入；按 `occurredAt` 查找生效价格（价格书版本 + 费率卡）；金额 = quantity × unitPrice，scale=6 CEIL；写 `t_metering_charge` 并回填 `usage_event.charge_id/status=RATED`；
- `estimateCost(meterKey, quantity, at)`：同样的价格路径，用于预扣/预算检查。

## 3. 预扣/结算/账本

- `reserve`：账户行锁（`SELECT ... FOR UPDATE`），可用额 = balance − frozen；校验后 `frozen += amount` 并写 `PENDING` reservation；
- `settle`：锁 reservation + account；`frozen -= reserved`、`balance -= actual`；写不可变 `DEBIT` 账本（`biz_no=settle:<reservationId>` 唯一），状态 `SETTLED`；
- `release`：`frozen -= amount`，状态 `RELEASED`；重复 settle/release 幂等；
- `queryBalance` / `queryUsage` 可查。

## 4. LLM 联动

- `LlmService`：当请求带 `budgetRef` 时，按"估算输入 token + maxTokens"调用 `estimateCost` 预扣，调用成功后按实际 usage 结算，失败则释放；预扣失败映射 `QUOTA_EXHAUSTED`；
- `LlmOutboxDispatcher`（可配开关/轮询间隔）：读取 `t_llm_outbox`，将 `LLM_CALL_COMPLETED` 转为 `UsageCommand`（eventId=`llm-usage:<requestId>`）投递给 metering，成功标 `SENT`，失败指数退避重试（10 次后 DEAD）；
- 端到端测试：一次 LLM 调用 → 预扣/结算 → 余额下降/frozen=0/账本 1 条 → outbox SENT → usage_event + charge 1 条。

## 5. 验证

| 测试 | 结果 |
|---|---|
| `MeteringCoreIntegrationTest`：幂等计价、reserve/settle、release、余额不足、并发防超卖、用量查询 | ✅ 6/6 |
| `LlmBillingIntegrationTest`：预扣→结算→ledger→outbox→usage/charge | ✅ |
| P1/P2 LLM 集成（不带 budgetRef）仍全绿 | ✅ |

```bash
cd server
IT_DB_URL=...atry_it... mvn -B -Pintegration -pl btry-app \
  -Dtest='MeteringCoreIntegrationTest,LlmBillingIntegrationTest' test
```

## 6. 运营面（P4 收尾）

- 充值：`MeteringAdminOperations.recharge`，`bizNo` 幂等，写 CREDIT 账本；
- 出账：`generateInvoice(subject, from, to, currency)` 聚合期间 Charge，写 invoice + items，状态 `FINALIZED`（同一账期幂等）；无计费结果返回 null；
- 对账 L1–L3：
  - L1 事件/Charge 数量与孤儿（EVENT_WITHOUT_CHARGE / CHARGE_WITHOUT_EVENT）；
  - L2 期间 Charge 总额 vs 结算流水总额（AMOUNT_MISMATCH）；
  - L3 每个账户 balance vs 账本派生值（BALANCE_MISMATCH）；
  - 每次对账落 `t_metering_recon_run/diff`，可追溯；
- 过期预扣：`MeteringAdminOperations.expireReservations` + 定时任务（默认 60s），解冻并置 `EXPIRED`。

## 7. 已知限制 / 后续

- `settle` 失败记 warning 不影响已完成调用返回（避免"模型已生成却报错"），靠对账补偿；
- outbox 单实例轮询；多实例需抢占锁/MQ；
- 价格 seed 仅演示，真实价格在 P5 按 DeepSeek 官方价建版本；
- 多币种/汇率、发票税务、充值退款申请不在本期。
