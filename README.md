# atry

[![ci](https://github.com/thereYany/atry/actions/workflows/ci.yml/badge.svg)](https://github.com/thereYany/atry/actions/workflows/ci.yml)

**AI 应用基座**：与业务无关的 Java 21 / Spring Boot 4.1.2 模块化单体，把大模型调用治理、计量计费、身份/权限、支付与运营可靠性做成可复用的内核能力，业务方只写自己的垂直域。

> 仓库有意不保留设计文档（旧文档会误导后来的维护者），**以代码与本 README 为准**；历史文档可在 git 历史中查阅。

## 模块

```
atry-app            内核演示宿主：演示端点 + 运营接口（只装配内核）
atry-desk           垂直宿主示例：AI 客服工单/会话 + AI 流式首答（SSE）与成本落库，复用内核身份、LLM 治理与计量
 ├─ atry-llm         LLM 调用治理：多 provider 路由、四层超时、重试/熔断、多维限流、预算守卫、流式调用、调用记录与 outbox
 ├─ atry-metering    计量计费：预扣 → 结算/释放 → 不可变账本；定价（分 token 类型）、充值、对账、账单、过期回收
 ├─ atry-identity    身份：JWT、RBAC、邀请注册、登录节流、改密/会话失效
 ├─ atry-payment     支付：支付单状态机、渠道抽象（支付宝/模拟）、回调 + 主动查单兜底、发件箱、对账、退款数据与告警
 ├─ atry-contracts   契约：业务只依赖这里的接口（LlmOperations / MeteringOperations / PaymentOperations / 身份）
 └─ atry-platform    平台 starter：多租户 SQL 拦截、AOP（幂等/审计/限流/计量）、告警、对象存储、追踪、健康检查
examples/x-try-template  垂直接入模板：支付履约事件监听的最小示例
```

依赖方向由 ArchUnit + Spring Modulith 门禁强制：`platform` 不依赖业务模块，四个业务模块之间只通过 `contracts` 交互，内核不依赖宿主。

## 技术栈

Java 21 · Spring Boot 4.1.2 · MySQL 8（Flyway）· Redis 7 · JdbcTemplate/MyBatis · Resilience4j · Micrometer + OpenTelemetry · Docker Compose

## 质量与证据

| 项 | 数值 |
|---|---|
| 测试 | **436 用例**：单元 + 架构门禁 367（`mvn test`，含垂直 AI 链路 3），集成（真实 MySQL + Redis）65（`-Pintegration`），垂直模板 4 |
| 覆盖率 | JaCoCo 行 **70.8%** / 分支 67.4%（`atry-llm` 行覆盖 89.6%） |
| 并发 | 32 线程对余额 10.000000 并发预扣 3.000000：**恰好 3 成功、29 拒绝**，冻结 9.000000，无超卖 |
| 多实例 | Redis 共享令牌桶：A 实例用满额度后 B 实例立即拒绝；回调 / 充值 / 邀请 / 发件箱并发幂等 |
| CI | data-boundary 棘轮（跨模块表访问只降不升）+ backend（单测 / 集成 / JaCoCo） |

## 快速开始

前置：JDK 21、Maven 3.9、MySQL 8、Redis 7。

```bash
cd server
mvn -B -DskipTests install

DB_URL='jdbc:mysql://127.0.0.1:3306/atry_dev?createDatabaseIfNotExist=true&useSSL=false&allowPublicKeyRetrieval=true' \
DB_USER=root DB_PASSWORD=root REDIS_HOST=127.0.0.1 \
java -jar atry-app/target/atry-app-0.1.0-exec.jar
```

默认端口 8084（管理端 9091），Flyway 启动时自动建表并创建管理员 `admin`（密码见启动日志或 `SEED_ADMIN_PASSWORD`，首次登录强制改密）。默认使用 **mock LLM provider**，无需任何外部 key 即可跑通全链路。

演示（mock 模型）：

```bash
# 登录拿 token → 同步调用 → 查余额
curl -s -X POST localhost:8084/api/auth/login -H 'Content-Type: application/json' \
  -d '{"username":"admin","password":"<初始密码>"}'
curl -s -X POST localhost:8084/api/demo/llm/chat -H "Authorization: Bearer <token>" \
  -H 'Content-Type: application/json' -d '{"message":"你好"}'
curl -s localhost:8084/api/demo/metering/balance -H "Authorization: Bearer <token>"
# SSE 流式：POST /api/demo/llm/stream（delta / done / error 事件）
```

接入真实 DeepSeek：`DEEPSEEK_ENABLED=true DEEPSEEK_API_KEY=...`（默认 `deepseek-flash`；不配置则用 mock）。

## 垂直示例：atry-desk（AI 客服）

独立宿主 `atry-desk` 演示「内核 + 垂直」的复用方式：工单/消息落库，AI 回复走内核
`LlmOperations.stream`（SSE），预扣/结算/成本记录由内核完成，垂直域只拼上下文与展示。

```bash
cd server
DB_URL='jdbc:mysql://127.0.0.1:3306/atry_desk?createDatabaseIfNotExist=true&useSSL=false&allowPublicKeyRetrieval=true' \
DB_USER=<user> DB_PASSWORD=<pass> REDIS_HOST=127.0.0.1 \
java -jar atry-desk/target/atry-desk-0.1.0.jar     # 默认 mock 模型，端口 8095

# 登录 → 建单 → SSE 追问（delta / done / error）
curl -s -X POST localhost:8095/api/desk/tickets -H "Authorization: Bearer <token>" \
  -H 'Content-Type: application/json' -d '{"subject":"无法登录","message":"改完密码登录不上"}'
curl -sN -X POST localhost:8095/api/desk/tickets/<ticketNo>/messages \
  -H "Authorization: Bearer <token>" -H 'Content-Type: application/json' -d '{"message":"在吗"}'
```

实测（mock provider）：8 个 `delta` 事件 + `done` 事件；`done` 中 usage 23 in / 16 out、
cost 0.000039 CNY，余额从 20.000000 降至 19.999961，`t_desk_message` 落库的
`cost_micros=39` 与计量口径一致。工具调用/知识库 RAG/人工接管为后续阶段。

## 测试

```bash
cd server
mvn -B test                 # 单元 + 架构门禁（不需要外部依赖）
# 集成测试需要真实 MySQL / Redis；本地库用 atry_it，Redis 用 db15
IT_DB_URL='jdbc:mysql://127.0.0.1:3306/atry_it?allowPublicKeyRetrieval=true&useSSL=false&allowMultiQueries=true' \
IT_DB_USER=<user> IT_DB_PASSWORD=<pass> IT_REDIS_DATABASE=15 \
mvn -B -Pintegration test
mvn -B -q jacoco:report     # 覆盖率报告：各模块 target/site/jacoco/index.html
```

CI 的集成测试环境变量约定见 `.github/workflows/ci.yml`。

## 已知限制

- **多租户暂未启用**：租户隔离链路（SQL 改写 / 系统作用域）已实现并有单测，但登录、邀请、支付唯一键等仍按单租户部署假设；3 个多租户用例以 `@Disabled` 标注为已知限制，不宣称生产可用。
- **支付**：默认 mock 渠道用于演示；支付宝渠道已接入协议与验签/对账，但需要商户资质与真实密钥；退款目前只有数据模型、查询与告警，退款执行链路未实现。
- **演示与合规**：项目仅作个人技术演示，不公开运营；真实模型调用仅在本地/临时演示时开启，公开演示使用 mock provider。
- 未做：多实例 outbox 抢占/分片、周期额度（Entitlement）、账单税务/多币种、真实 provider 高并发压测、atry-desk 的工具调用/RAG/人工接管。

## License

个人项目，未声明开源许可证；如需引用请联系仓库作者。
