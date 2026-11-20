# atry

[![ci](https://github.com/thereYany/atry/actions/workflows/ci.yml/badge.svg)](https://github.com/thereYany/atry/actions/workflows/ci.yml)

**AI 应用基座**：与业务无关的 Java 21 / Spring Boot 4.1.2 模块化单体，把大模型调用治理、计量计费、身份/权限、支付与运营可靠性做成可复用的内核能力，业务方只写自己的垂直域。

> 仓库有意不保留设计文档（旧文档会误导后来的维护者），**以代码与本 README 为准**；历史文档可在 git 历史中查阅。

## 模块

```
atry-app            宿主应用：演示端点 + 运营接口（只装配内核）
 ├─ atry-llm        LLM 调用治理：多 provider 路由、四层超时、重试/熔断、多维限流、预算守卫、流式调用、调用记录与 outbox
 ├─ atry-metering   计量计费：预扣 → 结算/释放 → 不可变账本；定价（分 token 类型）、充值、对账、账单、过期回收
 ├─ atry-identity   身份：JWT、RBAC、邀请注册、登录节流、改密/会话失效
 ├─ atry-payment    支付：支付单状态机、渠道抽象（支付宝/模拟）、回调 + 主动查单兜底、发件箱、对账、退款数据与告警
 ├─ atry-contracts  契约：业务只依赖这里的接口（LlmOperations / MeteringOperations / PaymentOperations / 身份）
 └─ atry-platform   平台 starter：多租户 SQL 拦截、AOP（幂等/审计/限流/计量）、告警、对象存储、追踪、健康检查
examples/x-try-template  垂直接入模板：支付履约事件监听的最小示例
```

依赖方向由 ArchUnit + Spring Modulith 门禁强制：`platform` 不依赖业务模块，四个业务模块之间只通过 `contracts` 交互，内核不依赖宿主。

## 技术栈

Java 21 · Spring Boot 4.1.2 · MySQL 8（Flyway）· Redis 7 · JdbcTemplate/MyBatis · Resilience4j · Micrometer + OpenTelemetry · Docker Compose

## 质量与证据

| 项 | 数值 |
|---|---|
| 测试 | **457 用例**：单元 + 架构门禁 388（`mvn test`），集成（真实 MySQL + Redis）65（`-Pintegration`），垂直模板 4 |
| 覆盖率 | JaCoCo 行 **70.8%** / 分支 67.4%（`atry-llm` 行覆盖 89.6%） |
| 并发 | 32 线程对余额 10.000000 并发预扣 3.000000：**恰好 3 成功、29 拒绝**，冻结 9.000000，无超卖 |
| 多实例 | Redis 共享令牌桶：A 实例用满额度后 B 实例立即拒绝；回调 / 充值 / 邀请 / 发件箱并发幂等 |
| CI | data-boundary 棘轮（跨模块表访问只降不升）+ backend（单测 / 集成 / JaCoCo） |

## 可观测性与错误契约（0.2.0+）

**日志**（`logback-spring.xml`，默认文本 / `prod` profile JSON）：

- 每行带 `traceId / userId / tenantId`（MDC）；消息统一走 `%maskedMsg` 脱敏
- 三份文件：`logs/<app>.log`（应用）、`-error.log`（仅 ERROR）、`-access.log`（访问日志）
- 异步写盘（queueSize 4096，不静默丢日志）；滚动 20MB/14 天/1GB
- 脱敏规则：密码/令牌/密钥整段 `******`，手机号/身份证/邮箱打码；JSON 输出额外用 MaskingJsonGeneratorDecorator

**访问日志**（`AccessLogFilter`）：

```
access method=POST path=/api/chat/x status=200 latencyMs=67 userId=2 tenantId=0 ip=1.2.3.4 slow=false traceId=... ua="..."
```

只记 path 不记 query；健康检查/静态资源跳过；SSE 等异步请求在 `onComplete` 记录**完整流时长**。
开关：`LOG_ACCESS_ENABLED`（默认 true）、`ACCESS_LOG_SLOW_MS`（慢请求标记阈值，默认 1000ms）。

**统一错误体**（`GlobalExceptionHandler`）：

```json
{"code":"VALIDATION_FAILED","message":"用户名不能为空","traceId":"...","path":"/api/auth/register",
 "timestamp":"2026-11-21T...Z","details":[{"field":"username","message":"用户名不能为空"}]}
```

错误码目录见 `ErrorCodes`（只增不改）；未知异常/数据库异常只返回兜底文案，堆栈与 SQL 只进日志。

**慢 SQL**：`SLOW_SQL_ENABLED=true`（默认关）+ `SLOW_SQL_THRESHOLD_MS=200`；只记录超阈值语句并脱敏，不带参数值。

**OpenAPI**：`/v3/api-docs`、`/swagger-ui.html`（`SPRINGDOC_ENABLED`，生产 profile 默认关）。

## 注册与认证（0.3.0）

- `POST /api/auth/register`：用户名 + 密码 + 昵称 + 邮箱（可选），成功直接返回 token（免二次登录）
- **不接验证码/短信/邮件**（按产品约束）：防刷靠 `@RateLimit(dimension=IP)`（默认每 IP 每小时 10 次）、
  用户名/邮箱租户内唯一键、注册总开关；邮箱只做格式校验与唯一性，不验证真实性
- 开关与默认角色：`app.auth.registration-enabled`、`app.auth.default-role`（默认 USER）
- 登录/注册/登出/失败都写 `t_auth_event`（user_id/username/event_type/success/ip/ua/detail），
  审计写入失败只告警、不影响认证主流程；失败详情只记分类（如 `BAD_CREDENTIALS`），不记密码/令牌
- 登录节流沿用账号+IP 双维度（Redis + 本地降级）；注册限流走统一 `@RateLimit` 切面
- **会话管理（0.4.0）**：登录/注册签发带 `sid` 的 token 并落 `t_user_session`；Redis 活跃标记 + DB 兜底，
  支持 `GET /api/auth/sessions`（当前设备标记）、`DELETE /api/auth/sessions/{sid}`（单会话踢下线）、
  `POST /api/auth/logout`（吊销当前会话）。多实例即时生效；无 sid 的旧 token 仅做用户状态/版本校验。
  每日清理过期/已吊销会话（保留 30 天，可配）。

**数据库迁移约定**：内核迁移固定 `V1xx–V5xx`，垂直应用使用 `V9xx`。已有垂直库升级内核时，
新内核迁移版本号小于已执行的垂直线，需要 `spring.flyway.out-of-order=true`（当前 chatry 如此）；
P4 会补「版本段分配 + 乱序禁止」的校验脚本与 CI 门禁。

## 快速开始

前置：JDK 21、Maven 3.9、MySQL 8、Redis 7。

```bash
cd server
mvn -B -DskipTests install

DB_URL='jdbc:mysql://127.0.0.1:3306/atry_dev?createDatabaseIfNotExist=true&useSSL=false&allowPublicKeyRetrieval=true' \
DB_USER=root DB_PASSWORD=root REDIS_HOST=127.0.0.1 \
java -jar atry-app/target/atry-app-0.4.0-exec.jar
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
- 未做：多实例 outbox 抢占/分片、周期额度（Entitlement）、账单税务/多币种、真实 provider 高并发压测。

## License

个人项目，未声明开源许可证；如需引用请联系仓库作者。
