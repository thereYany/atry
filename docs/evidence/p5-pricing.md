# P5b 验收记录：DeepSeek 价格与成本核算（2026-11-19）

## 1. 计量拆分为 token 类型

- 新增 meter：`llm.input_tokens`、`llm.output_tokens`、`llm.cached_read_tokens`（SUM/token）；
- outbox 投递器把一次调用拆为最多 3 个用量事件（eventId `llm-usage:<requestId>:in|out|cache`），维度含 provider/model；
- 输入 token 中缓存命中部分单独计入 `cached_read`，非缓存输入计入 `input`，避免重复计费。

## 2. 模型维度价格

- `MeteringStore.findPrice` 支持 `subject_selector`：精确模型价优先，NULL 为默认价；按 `effective_from/version` 取版本；
- `estimateCost` 增加 dimensions 重载，rating/预扣/结算使用同一价格路径；
- Flyway `V503__deepseek_pricing.sql`（人民币/1M tokens，2026-11 官方标准时段价）：
  - `deepseek-flash`：input 2 元、output 8 元、cache hit 0.04 元；
  - 默认价 1 元/M（mock/未配置模型，仅本地演示）。

## 3. 验证

- `MeteringPricingIntegrationTest`：
  - input 1,000,000 tokens → charge 2.000000 CNY；
  - output 1,000,000 → 8.000000 CNY；
  - cached_read 1,000,000 → 0.040000 CNY；
  - 证明按 model selector 精确命中 DeepSeek 价而非默认价。
- `LlmBillingIntegrationTest` 更新为 3 类事件（示例输入 2/输出 64 → in/out 两条），预扣/结算/outbox/usage/charge 全链路通过；
- 真实 DeepSeek 调用 usage 字段已核验（见 `deepseek-verification.md`），成本可由 usage × 上述单价复算。

## 4. 限制

- 峰谷时段价、缓存写入价、汇率、税额未建模；后续通过价格维度/价格书版本扩展；
- 预扣按估算 input+maxTokens，结算按实际 usage 多退少补；缓存命中在预扣时未知（按非缓存价预估，结算修正）。
