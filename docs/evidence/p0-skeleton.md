# P0 验收记录：模块骨架与契约（2026-11-19）

## 1. 仓库形态（D1）

- `atry/` 已从 btry 干净快照初始化为独立 Git 仓库：commit `e6f566e`，无旧业务提交历史。
- 原始 btry 仓库归档在 `blrs/_archive/btry-2026-11-19`（含旧历史，仅参考，不参与构建）。
- blrs 仓库已整目录忽略 `atry/`（commit `ec7a1db`）。
- 设计文档在 `atry/docs/design/`，随 atry 仓提交。

## 2. 交付内容

| 项 | 内容 |
|---|---|
| 新模块 | `server/atry-llm`（调用治理骨架）、`server/atry-metering`（计量计费骨架）；artifact 使用 `atry-*`，存量 `btry-*` 模块暂保留（后续统一清理） |
| 契约 | `contracts.llm.*`（LlmOperations/Request/Response/Usage/Error/StreamSink）、`contracts.metering.*`（MeteringOperations/Usage/Reserve/Settle/Release/Balance/Query） |
| 数据 | Flyway `V400__llm_schema`（t_llm_call_record、t_llm_outbox）；`V500__metering_schema`（meter/usage_event/price_book/rate_card/account/ledger/reservation/charge） |
| 装配 | btry-app 依赖新模块并扫描 `modules.llm/metering`；Flyway locations 增加 llm/metering |
| 门禁 | ArchUnit 增加 llm/metering 边界规则；Spring Modulith 模块图新增 llm/metering；KernelPurity 自动覆盖；数据边界基线登记 10 张新表（owner: llm/metering） |
| 骨架行为 | 未实现的调用返回 501 `BizException`（明确错误而非 NPE），为 P1/P4 留出契约 |

## 3. 验证结果

| 检查 | 结果 |
|---|---|
| `mvn -B -q test`（单元 + 架构门禁） | ✅ 通过 |
| `mvn -B -Pintegration -pl btry-app -Dtest=KernelIdentityIntegrationTest` | ✅ 通过；Flyway V400/V500 applied |
| 数据边界门禁 | ✅ 通过（跨模块表 0；新表已登记） |
| `btry_it` 表与 Flyway 历史 | ✅ V400/V500 success；10 张 t_llm_*/t_metering_* 表存在 |
| Modulith 文档 | ✅ 生成 module-llm/module-metering |

## 4. 下一步

P1：Mock provider + 调用记录/outbox + 最小日志；随后 P2 可靠性、P3 观测/AOP、P4 计量闭环、P5 真实 DeepSeek 接入。
