# 本地启动与演示指南

## 环境与端口

JDK 8、Maven、Node.js 22.12+、Docker Desktop。Java 使用 `-Duser.timezone=Asia/Shanghai`，React 将无时区的 LocalDateTime 按北京时间解释。

| 服务 | 宿主机地址 |
| --- | --- |
| 后端 | 127.0.0.1:8081 |
| React 开发 / 预览 | 127.0.0.1:8088，二选一 |
| MySQL | 127.0.0.1:12306，hmdp |
| Redis | 127.0.0.1:12379 |
| RocketMQ NameServer | 127.0.0.1:9876 |
| RocketMQ Broker | 10909 / 10911 / 10912；通告地址必须对 Java、Canal 都可达 |
| Canal | 12111 / 12112；应用当前使用 MQ 模式 |

这些配置用于本机开发。演示账号与弱口令不能直接用于公网环境，课程写接口也还没有完整管理权限控制。

## 两种 MQ 启动方式

### 新机器：独立覆盖配置

```powershell
Copy-Item .env.example .env
docker compose -f docker-compose.yml -f docker-compose.local.yml up -d
docker compose -f docker-compose.yml -f docker-compose.local.yml ps
docker compose -f docker-compose.yml -f docker-compose.local.yml logs --tail 80 broker canal
```

基础配置保持旧环境兼容，覆盖文件新增 NameServer / Broker，并把原先的 external 网络改为本项目维护的 hmdp-mq-net。通过网络别名让 Canal 无需修改旧配置即可找到新的 NameServer。

Broker 的 `ROCKETMQ_BROKER_IP` 默认 `host.docker.internal`，适用于能够从宿主机与容器同时解析该地址的 Docker Desktop。若 Java 无法解析，设为可达的宿主机 IP / DNS 名；不能只填容器 IP 或 Java 侧的 localhost，因为 Canal 也要连接 Broker。

本轮只执行了合并配置校验，没有为了测试而停止其他项目的 MQ 并清空数据库重装。新机器的完整首启仍应按上述日志确认。

### 当前已有环境：复用 StudyAgent MQ

```powershell
docker start study-agent-rocketmq-namesrv study-agent-rocketmq-broker
docker compose up -d mysql redis canal
```

需要已有 `study-agent_study-agent-net` 与上述 MQ 容器。此路径是 2026-09-16 实际联调环境。不要同时启用独立配置占用相同端口，不要用删除数据卷来处理启动错误。

## 后端与 React

仓库根目录：

```powershell
mvn -DskipTests package
java -Duser.timezone=Asia/Shanghai -jar target/hm-dianping-0.0.1-SNAPSHOT.jar
```

新终端：

```powershell
cd frontend
npm ci
npm run dev
```

看到 Java `Started HmDianPingApplication` 后再访问 http://127.0.0.1:8088 。源码构建不等于服务已经启动。Windows 重新打包时需要先停止占用同一 JAR 的旧进程，否则 Spring Boot repackage 可能因文件锁失败。

## 一次完整演示

1. 执行 `scripts/seed-demo.ps1`，给店铺 1 准备两款限时商品。已有未结束的同名商品直接复用，不重置库存。
2. 从首页进入商品，点击立即抢购；未登录时转到验证码登录。
3. 填写本地测试手机号并申请验证码。在终端读取 `docker exec hmdp-redis redis-cli GET login:code:<测试手机号>`，不要使用真实用户验证码做演示。
4. 登录后回到商品页，再次点击立即抢购。页面先显示受理状态，订单落库后显示待支付及倒计时。
5. 点击“模拟支付（不扣款）”，订单变为已支付。重复进入已支付订单不会再次改变状态。
6. 查看“我的订单”，列表只能看到当前用户的数据；退出后需重新登录。

演示商品图片是 AI 生成素材，页面和 README 已标注；订单没有价格快照，不展示伪造的历史成交金额。默认超时 30 分钟，等待超时可观察关闭状态；真实 Broker 的短延迟和重试用例另见证据测试脚本。

## 常见问题

| 现象 | 检查点 |
| --- | --- |
| 首页暂时无法加载 | Java 是否启动成功，Vite BACKEND_URL 是否指向正确后端 |
| 店铺有资料，但没有商品 | 课程初始 SQL 没有新增商品；运行 seed-demo，或进入已上架商品的店铺 |
| 获取验证码没有短信 | 本地项目没有短信渠道，从自己的 Redis 测试 Key 获取 |
| 返回订单号却仍在等待 | 查看真实 MQ Topic 路由、消费者连接/位点、错误日志和数据库；不要反复重新下单 |
| 第一次消息被跳过 | 新消费者组已改为 FIRST_OFFSET；旧持久化位点不会自动回退，历史遗漏需精确核对后再恢复 |
| 页面显示请求太频繁 | 接口真实触发 429，稍后再试；不能通过伪造 IP 头绕过 |
| 重买提示已有订单 | 同一用户同一商品已有有效订单；已支付订单也占购买资格 |
| 原版附近店铺为空 | 原版 GEO 查询需初始化商铺坐标；React 名称搜索不依赖 GEO |
| 老图片加载失败 | 部分课程店铺图片来自外链，React 有缺图回退；新增生成商品图在仓库内 |
| Java 21 运行异常 | 本机曾出现 Netty loopback 初始化错误，本项目运行验收使用 JDK 8 |

不把“清空 Redis”“重置库存”“重置所有消费位点”作为通用排障步骤，这些操作会破坏现存订单的一致性。

## 原理与官方参考

- [React 从零开始构建应用](https://react.dev/learn/build-a-react-app-from-scratch)
- [Vite 启动要求](https://vite.dev/guide/)
- [RocketMQ Spring 消费位置配置](https://github.com/apache/rocketmq-spring/wiki/FAQ)
- 本地证据优先见 [resume-evidence.md](resume-evidence.md) 与 [frontend-acceptance.md](frontend-acceptance.md)。
