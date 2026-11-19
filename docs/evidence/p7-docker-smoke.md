# P7 补充验收：容器化部署（Docker Compose）在本机真机验证

> 时间：2026-11-20 00:55–01:05。环境：本机 4C/7.3G 云服务器（Docker 29.9.3 / Compose v5.7.1，daemon 在本机运行）。
> 背景：P7-④ 收口时提到「Dockerfile/compose 真机跑一遍」——这里的"真机"指**容器化部署形态**，
> 当前服务器本身就是真实机器（真实 MySQL 8 / Redis 6 / 真实网络）。本轮把它补上并修复了 Dockerfile 的历史缺陷。

## 1. 发现并修复的问题

| 问题 | 影响 | 修复 |
|---|---|---|
| `server/Dockerfile` 只 `COPY` `atry-platform/contracts/app` 三个模块 | **镜像根本构建不出来**：app 依赖 identity/payment/llm/metering，Maven 解析失败 | 补齐 7 个模块的 pom + src（`4b…` 见 commit） |
| 无 `server/.dockerignore` | 构建上下文会把 `target/`、`logs/` 打包上传（本地 126MB） | 新增 `.dockerignore`（target/logs/data/*.log） |
| Dockerfile 未覆盖 `atry-llm/metering` | 即使勉强构建也缺 AI 能力 | 随模块补齐一并修复 |

## 2. 实测结果（真实容器栈）

```bash
cd atry
# .env.docker 本地生成（gitignored，含密码/JWT secret，不提交）
sudo docker compose --env-file .env.docker build backend   # BUILD SUCCESS
sudo docker compose --env-file .env.docker up -d
sudo docker compose --env-file .env.docker ps
```

| 容器 | 状态 | 端口（仅 127.0.0.1） |
|---|---|---|
| `atry-mysql-1`（mysql:8.0） | healthy | 13306→3306 |
| `atry-redis-1`（redis:7-alpine） | healthy | 16379→6379 |
| `atry-backend-1`（atry-backend） | healthy | 8084、9091 |

健康检查：`GET http://127.0.0.1:9091/actuator/health` → `status: UP`（db/redis/readiness 均 UP），
启动日志确认：`com.wherelee.base.AtryApplication : Started AtryApplication in 32.084 seconds`（D2 改名后的启动类）。

### 2.1 一键演示脚本（容器内全链路）

```text
== 2/5 余额：{"balance":10.000000,...}
== 3/5 调用：{"requestId":"demo9e69...","content":"mock reply: 用一句话介绍 atry 基座",
              "usage":{"inputTokens":4,"outputTokens":28,"totalTokens":32},
              "cost":{"amount":0.000032},"balance":{"balance":9.999968}}
== 5/5 用量事件：outbox 异步产出 input/output 两条（llm-usage:<requestId>:in/out）
```

### 2.2 SSE 流式（容器内）

8 个 `event:delta` + 1 个 `event:done`；done 事件带 `usage.totalTokens=18`、`cost=0.000018`、
`balance=9.999932`；容器日志可见 `virtual-*` 线程执行 + `demo.llm.stream ... cost=0.000018`。

## 3. 资源占用

- 三容器 mem_limit：mysql 512m / redis 160m / backend 640m；
- 实测运行期间可用内存从约 3.0G 降到约 2.2G（构建阶段更高），4C 机器可承受；
- 验证结束后 `docker compose down` 已停止容器（保留镜像与命名卷，便于下次 `up`）。

## 4. 限制 / 后续

- CI **不构建镜像**（当前 workflow 只跑单测/集成/数据边界）；如果希望"镜像可构建"成为门禁，可加一个 `docker build` job（构建约 3–5 分钟）；
- 镜像未推送到 registry（本地 `atry-backend:latest`）；
- 容器内默认关闭 tracing（`TRACING_ENABLED=false`），需要 Jaeger 时用 `compose.observability.yml`；
- compose 的 MySQL/Redis 为单实例容器，生产化需按 04/05 的多实例与备份方案演进。
