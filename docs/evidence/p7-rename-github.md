# P7-④ 验收记录：D2 重命名（btry-* → atry-*）+ GitHub 推送 + CI

> 时间：2026-11-19/20。对应设计：[08 §P-1 D2](../design/08-迭代计划与验收门.md)、[10 §残余问题 R1/R2](../design/10-自检与修订记录.md)。
> 背景：atry 从 btry 快照初始化后，模块目录/artifactId/配置/CI 仍叫 `btry-*`；本阶段统一为 `atry-*`
> 并首次推送 GitHub 跑通 CI。

## 1. 变更范围（全仓一致）

| 类别 | 变更 |
|---|---|
| 模块目录 / artifactId | `btry-platform/contracts/identity/payment/app` → `atry-*`；parent `btry-parent` → `atry-parent`（`atry-llm/metering` 本就对齐） |
| Java 入口 | `BtryApplication` → `AtryApplication`；**包名保持 `com.wherelee.base`**（与设计一致，重命名只到 artifact/类名层） |
| 运行配置 | `spring.application.name=atry-base`；默认库 `atry_core`/`atry_it`；JWT `iss=atry`、`aud=atry-web`；`PAYMENT_APP_ID` 默认 `atry`；日志 `atry.log`/`atry-prod.log` |
| 可观测 | Redis 前缀 `atry:`（限流/幂等 key）；trace tag `atry.trace_id`、metric `atry.method`；Grafana dashboard `atry-overview.json` + provisioning 更名 |
| 容器 | `compose.yml` name=`atry`、DB 用户默认 `atry`；`.env.docker.example` 同步 |
| CI | MySQL 库 `atry_it`、用户 `atry/atrypass`、`IT_DB_URL` 同步 |
| 工具/模板 | `tools/*`（ci_watch 默认 repo、inventory docstring）、`examples/x-try-template` 依赖与文案 |
| 数据边界基线 | 按新模块名全量重生成（`--write-baseline`），检查通过（跨模块表 0、多表 SQL 6 未上升） |
| 文档 | README、`docs/design/*`、当前 `docs/evidence/*` 全部 atry；**btry 时期历史文档保留原文**，`docs/README.md` 顶部注明历史来源 |

## 2. 保留项（有意不改）

- Java 包名 `com.wherelee.base`：跨仓/跨服务的稳定坐标，重命名收益低、破坏面大；
- `docs/` 下 btry 时期的拆分/支付/对账等历史文档：作为工程演进记录保留原文；
- 归档仓库 `blrs/_archive/btry-2026-11-19` 不动。
- 已知历史遗留：`server/Dockerfile` 仍只 COPY platform/contracts/app 三个模块（btry 时期就缺 identity/payment/llm/metering），
  不在本阶段修复（CI 不构建镜像）——已记录为后续项。

## 3. 本地验证

```text
mvn -B clean install -DskipTests   → BUILD SUCCESS
mvn -B test                       → BUILD SUCCESS
mvn -B -Pintegration test         → 36 tests, 0 failures
python3 tools/table_access_inventory.py --baseline ... → 数据边界检查通过
```

## 4. GitHub 推送与 CI

- 远端：`https://github.com/thereYany/atry`（public，仓库此前已存在但为空）；
- remote 走 `ghfast.top` 前缀（本机直连 `github.com:443` 本次超时；runbook 已记录该兜底）；
- 首推 `main`：`4bbc149`（D2 重命名提交）；
- CI run：[44564056553](https://github.com/thereYany/atry/actions/runs/44564056553) — **success**
  （`data-boundary` 10s ✅，`backend` 2m56s ✅：`mvn -B test` + `mvn -B -Pintegration test` + JaCoCo）。

> 认证方式：`GIT_ASKPASS=~/.config/btry/git-askpass.sh`（token 文件 600），token 未写入 remote URL/仓库。

## 5. 后续

- D2 之后建议：`server/Dockerfile` 补齐全部模块、compose 真机跑一遍 init；
- 历史文档如果要进一步"去 btry 化"，建议整体归档到 `docs/archive/` 而不是逐字改写；
- 后续迭代直接推到本仓，CI 以 main 为门禁（push/PR 均触发）。
