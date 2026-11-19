# P5a 验收记录：DeepSeek 真实 Provider（2026-11-19）

## 1. 实现

| 项 | 内容 |
|---|---|
| Provider | `DeepSeekProvider implements LlmProvider`；OpenAI 兼容 `POST {base-url}/chat/completions` |
| 配置 | `atry.llm.deepseek.{enabled,base-url,api-key,default-model,thinking-disabled,connect-timeout-ms,total-timeout-ms}`；key 只从 env/KMS 注入，默认关闭 |
| 非流式 | 解析 `choices[0].message.content/finish_reason`、`model`、`usage` |
| 流式 | `text/event-stream` 逐行解析 `data:`，转发 chunk；`[DONE]` 结束；支持 `stream_options.include_usage`；无 usage 时按累计字符估算并标 ESTIMATED |
| usage 映射 | `prompt_tokens`/`completion_tokens`；缓存命中 `prompt_cache_hit_tokens` 或 `prompt_tokens_details.cached_tokens`；reasoning tokens 兼容；来源标记 PROVIDER |
| 错误映射 | 401/403→AUTH；400/413/422→INVALID_REQUEST；402→QUOTA_EXHAUSTED；429→RATE_LIMITED(retryable)；5xx→UPSTREAM_5XX(retryable)；IO→TIMEOUT_CONNECT；未分类→INTERNAL；不重试（框架单点重试） |
| thinking | 默认 `{"type":"disabled"}`（可配），降低延迟/成本；stream 时透传 |

## 2. 测试

- 本地 `HttpServer` 模拟 OpenAI 兼容服务：
  - 非流式成功 + usage 字段映射；
  - 429 → RATE_LIMITED/retryable/providerCode；
  - 401 → AUTH；缺失 key → MISSING_API_KEY；
  - SSE 流式 chunk 拼接 + usage + finish_reason；
- 自动化全绿；`mvn test` 不依赖真实网络。
- 真实 DeepSeek 小请求（`DEEPSEEK_API_KEY` 存在时运行）：

```bash
set -a; . ~/.config/atry/deepseek.env; set +a
cd server && mvn -B -q -pl atry-llm -Dtest=DeepSeekProviderRealTest test
```

结果：✅ 通过；返回真实 `deepseek-flash` 内容与正数 input/output tokens（key 未打印/未入库）。
模型/usage 字段与官方一致性已在 `deepseek-verification.md` 记录。

## 3. 已知限制 / 后续

- 价格书/成本核算：P5b 按 DeepSeek 官方价格建立版本（输入/输出/缓存分价），并把 `llm-usage` 事件拆成 input/output meter 以精确计价；
- 压测/故障演练：P5c；
- 真实 provider 的重试/熔断由框架负责，provider 内不重试（已遵守）。
