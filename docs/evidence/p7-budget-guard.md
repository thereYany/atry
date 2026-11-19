# P7-② 验收记录：@BudgetGuard 预算守卫

> 时间：2026-11-19。对应设计：[03 §1.2 注解目录 / §2 切面顺序](../design/03-横切设计-可观测与AOP.md)。
> 背景：P3 设计里 `@BudgetGuard` 依赖 P4 计量计费，P4/P5 完成后一直未落地（P3 遗留项）。

## 1. 语义

`@BudgetGuard(budgetRef, minAvailable, onExceed, scope)`，切面顺序 **-50**（鉴权/限流/幂等之后、业务方法之前）：

| 配置 | 说明 |
|---|---|
| `budgetRef` | 预算用途标识，只进日志/指标标签，不参与计价 |
| `minAvailable` | 可用额下限（金额字符串）；可用额 = `balance - frozen`（来自计量账户） |
| `onExceed` | `REJECT`（默认，抛 402）或 `DEGRADE`（放行 + 标记降级上下文） |
| `scope` | `TENANT`（默认）或 `USER`，决定查哪个主体的账户 |

行为约定：

- **快速失败**：可用额不足时在进入业务方法前就返回 402，不消耗上游调用/线程；
- **不是权威准入**：真正的额度权威仍是 `MeteringOperations.reserve`（预扣），两者失败语义一致（402）；
- **fail-open**：无登录上下文（无请求属性）、未装配计量模块、或计量查询本身异常时，守卫放行（守卫不得成为新的故障点）；
- **DEGRADE**：置位 `BudgetContext`（线程内、支持嵌套），业务方法内可 `BudgetContext.isDegraded()` 判断并走轻量路径；调用结束自动清理；
- 指标：`atry.llm.budget.exceeded{budget,action}`，WARN 日志 `llm.budget.rejected` / `llm.budget.degraded`。

使用示例：

```java
@BudgetGuard(budgetRef = "chat", minAvailable = "1.00", onExceed = BudgetGuard.Action.REJECT,
        scope = BudgetGuard.SubjectScope.USER)
public ChatResult chat(ChatCommand command) {
    if (BudgetContext.isDegraded()) {
        // DEGRADE 模式下走小模型/缓存
    }
    ...
}
```

## 2. 交付

| 项 | 位置 |
|---|---|
| 注解 | `atry-llm/.../annotation/BudgetGuard.java` |
| 切面 | `atry-llm/.../budget/BudgetGuardAspect.java`（`@Order(-50)`，`ObjectProvider<MeteringOperations>` 可选依赖） |
| 上下文 | `atry-llm/.../budget/BudgetContext.java`（ThreadLocal，支持嵌套） |
| 指标 | `LlmMetrics#budgetExceeded` → `atry.llm.budget.exceeded` |

## 3. 自动化证据

单元测试（`BudgetGuardAspectTest`，7/7 通过）：

| 用例 | 断言 |
|---|---|
| 可用额低于下限 | 抛 `BizException` 402、业务方法未被调用、`budget.exceeded{action=REJECT}` +1、主体解析为 `(tenant,0,user)` |
| 可用额足够 | 放行并返回业务结果 |
| DEGRADE | 方法内 `BudgetContext.isDegraded()=true` 且 `degradedBudgetRef` 正确，返回后自动清理；`action=DEGRADE` 指标 +1 |
| 无登录上下文 | fail-open（不查余额、直接放行） |
| 计量查询异常 | fail-open（不因守卫打挂业务） |
| 未装配计量模块 | fail-open |
| 切面顺序 | `@Order(-50)` |

集成测试（真实 MySQL，`BudgetGuardIntegrationTest`，1/1 通过）：

1. 充值 2.00 CNY 到 `(0,0,user)` 账户 → 注解方法放行；
2. 把余额 UPDATE 为 0 → 同一方法抛 402（切面在 Spring 容器内真实生效，不只是单测 mock）。

## 4. 限制 / 后续

- 仅覆盖 HTTP 请求上下文（`RequestContextHolder`）；异步/定时任务需显式传主体（后续可扩展 `RequestContext` 抽象）；
- 判断的是「账户可用额」而不是周期额度（Entitlement/Grant 语义未实现，见 05 §5.2）；
- DEGRADE 只提供标记，不自动切换模型/降级策略（由业务按 `BudgetContext` 自行决定）；
- `minAvailable` 是静态值，未按模型/请求估算调用成本（成本估算在 `LlmService` 预扣时已做）。
