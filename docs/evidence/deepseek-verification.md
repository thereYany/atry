# DeepSeek 真实接入核验（2026-11-19）

> 目的：在设计/实现早期确认真实 provider 的模型名、调用方式与 usage 字段，避免契约测试建立在假设上。
> 密钥只保存在本机 `~/.config/atry/deepseek.env`（权限 600），不进入仓库/不打印。

## 1. 官方模型与端点

- Base URL（OpenAI 兼容）：`https://api.deepseek.com`
- 当前模型（官方定价页）：`deepseek-flash`（DeepSeek-V4.1-Flash-1017）、`deepseek-v4-pro`
- 上下文 1M；最大输出 384K；thinking 默认开启（可关闭）；Flash 并发限制 2500
- 官方定价页：<https://api-docs.deepseek.com/zh-cn/quick_start/pricing/>

## 2. 账号可用性核验

```text
GET https://api.deepseek.com/models  -> HTTP 200
models: ["deepseek-flash", "deepseek-v4-pro"]
```

最小 chat 请求（`thinking.type=disabled`，`max_tokens=16`）：

```json
{
  "model": "deepseek-flash",
  "messages": [{"role":"user","content":"Reply with exactly: OK"}],
  "max_tokens": 16,
  "temperature": 0,
  "thinking": {"type": "disabled"},
  "stream": false
}
```

响应关键字段：

```json
{
  "model": "deepseek-flash",
  "usage": {
    "prompt_tokens": 9,
    "completion_tokens": 1,
    "total_tokens": 10,
    "prompt_tokens_details": {"cached_tokens": 0},
    "prompt_cache_hit_tokens": 0,
    "prompt_cache_miss_tokens": 9
  },
  "choices": [{"finish_reason": "stop"}]
}
```

## 3. 对实现的约束（P1/P5 使用）

1. usage 解析：优先读 `prompt_tokens/completion_tokens`；缓存字段映射 `prompt_tokens_details.cached_tokens`（同时保留 `prompt_cache_hit_tokens/miss_tokens` 供对账）；缺失时估算并标记 `source=ESTIMATED`。
2. thinking 默认开启：契约需允许 `thinking.type=disabled` 透传（providerOptions/raw 字段），避免不必要的 reasoning token 成本。
3. 流式：使用 `stream=true`；usage 需在流末尾收集（DeepSeek OpenAI 兼容接口支持 `stream_options.include_usage` 时优先）。
4. 价格：先按官方人民币价格建价格书；峰谷/缓存命中分价作为价格维度，P5 用真实账单核对。
5. 并发/限流：Flash 并发 2500 是 provider 侧配额；框架仍按租户/部署独立限流。

## 4. 安全

- key 存放：`~/.config/atry/deepseek.env`（chmod 600）；代码/仓库/日志中不出现明文。
- 生产应改为 KMS/Secrets Manager 引用（设计 02/09 已要求 `credential-ref`）。
