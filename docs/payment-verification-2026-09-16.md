# 支付、超时关单与并发冲突验证

验证日期：2026-09-16。业务代码基于 `198a09d`，本轮补充 6 个针对性测试，没有修改支付或订单业务实现。

## 结论

简历中的 **MQ 异步下单与幂等、延迟关单、支付和关单冲突控制** 有代码与本机测试证据。补充后的三个测试类合计 **35 项：34 通过、0 失败、0 错误，1 项缓存性能测量跳过**。这是两次运行的汇总，运行顺序见下文，不是单次执行数量。

支付宝已实现沙箱接入；实际预下单和查单此前已跑通。本轮的付款成功、签名通知、故障和并发由隔离测试验证，**不代表沙箱买家已实际付款或公网通知已验收**。核对浏览器对应的演示订单时，仍为待支付。

原始结果：[用例清单](evidence/2026-09-16-payment-regression-tests.json) · [真实 MQ 重投与关单](evidence/2026-09-16-payment-real-mq.json) · [首次消费](evidence/2026-09-16-payment-first-message.json)。

## 简历 bullet 对应证据

| 业务声明 | 这次怎样验证 | 结果与边界 |
| --- | --- | --- |
| Redis Lua 预扣、一人一单、防超卖 | 200 次并发准入、重复消费；检查 Redis / MySQL 库存和订单条数 | 通过；没有验证 Redis 主从切换与锁服务故障 |
| RocketMQ 异步下单、幂等消费 | 真实 Broker 触发重投，消息经消费者入库；检查重复扣库存、迟到旧消息与新预占隔离 | 通过；独立测试 Topic / 消费组，测试库，非生产负载 |
| RocketMQ 延迟关单 | 测试订单有效期 2 秒，延迟级别 1；观察真实延迟投递，提前到达不能关单，到期后取消及库存回补 | 通过；不是手动调用两次消费者冒充 Broker 重投 |
| 重复支付确认 | 16 次并发重复通知，断言订单版本只增加一次，库存不增加 | 通过；渠道传输模拟 |
| 支付和关单同时执行 | 16 个线程通过屏障同时启动，8 个支付通知、8 个关单请求；渠道状态设为已支付 | 最终已支付，版本为 2（创建渠道流水一次 + 支付一次），两侧库存均保持扣减，购买预占仍归属原订单 |
| 乐观锁独立兜底 | 实际 Mapper 更新前，另一数据库连接提交 `version + 1`，绕过 Redis 锁制造旧版本写入 | 实际支付 UPDATE 影响 0 行；通知返回失败并保留待支付；重试后成功。没有只靠应用锁来推断 CAS 生效 |
| 渠道关单与付款竞争 | 第一次查单待支付、渠道关单失败、再次查单已支付 | 本地确认支付，不返库存；渠道成功关闭路径也验证重复关单只回补一次 |
| 渠道关闭、DB 回补失败 | 删除本用例的秒杀库存行注入故障，使本地事务失败；随后恢复该行并执行恢复任务 | 订单取消回滚，`CLOSED` 渠道流水保留；恢复任务完成取消，不再次调用渠道关单 |
| DB 已取消、Redis 回补失败 | 对 Lua 回补注入一次 Redis 连接异常，再重投关单处理 | DB 库存先恢复、Redis 暂不变；重试只补 Redis，再次重试不多加库存 |
| 通知丢失后的恢复 | 从数据库扫描两笔流水：一笔渠道已支付，一笔过期且渠道已关闭 | 同一个恢复方法分别完成支付、关单；重复运行不重复处理。测试直接调用任务方法，不依赖睡眠等待调度器 |
| RSA2 验签和重复回调 | 用临时生成的 RSA 密钥签名，经 MockMvc 通知入口验证签名、归属、金额，再实际更新 MySQL；篡改金额并重放成功通知 | 篡改返回 `failure`，有效通知及重复通知返回 `success`；订单版本只增加一次。不是支付宝公网实际推送 |
| 结果未知、金额或身份不符 | 预下单超时、查单失败、NOT_FOUND、错误 APPID / 商家 / 金额 / 订单号 | 不误标为支付成功，不盲目重建支付或回补库存 |
| 本地已关闭却收到支付成功 | 注入冲突终态与成功通知 | 保留 `REVIEW_REQUIRED`，不恢复已释放的订单；需要人工核账，未自动退款 |

## 新增测试位置

`src/test/java/com/hmdp/AlipayPaymentTest.java` 新增：

1. `callbackAndTimeoutRunConcurrentlyWithoutCancelingPaidOrder`
2. `staleDatabaseVersionRejectsCallbackUntilRetryEvenWithoutRedisContender`
3. `channelClosedButDatabaseRollbackRecoversFromPersistedLedger`
4. `redisFailureAfterCancelCommitRetriesOnlyReservationCompensation`
5. `scheduledRecoveryPaysWithoutCallbackAndClosesConfirmedExpiredOrders`
6. `realSignedHttpCallbackRejectsTamperingAndAcceptsDuplicateSuccess`

测试使用 MyBatis 测试专用拦截器在真实 Mapper 写入前制造版本竞争；生产代码没有加测试开关。临时 RSA 私钥在测试临时目录生成，不读取用户沙箱密钥。

## 如何复现

环境：Windows、JDK 8、MySQL 5.7 测试库 `hmdp_resume_test`、Redis DB14，已有本机 RocketMQ Broker。测试只清理自己创建的商品、订单与支付流水，不清空演示库。

一条命令包含支付测试和真实 MQ：

```powershell
.\scripts\test-resume.ps1 -JavaHome '你的 JDK 8 目录' -RealMq
```

本轮实际执行顺序：

```powershell
# 当时已有 15 个支付测试；完整运行 34 项，33 通过、1 跳过。
mvn "-Dtest=AlipayPaymentTest,AlipaySignatureTest,ResumeEvidenceTest" "-Dresume.realMq=true" test
# 再补充实际签名经通知入口处理的用例，只重跑受影响的支付测试类：16 项全通过。
mvn "-Dtest=AlipayPaymentTest" test
```

合并最新报告：AlipayPaymentTest 16/16，AlipaySignatureTest 1/1，ResumeEvidenceTest 17/18，缓存基准用例未启用。测试中预期的故障日志不是测试失败；以断言和 Surefire 结果为准。

真实 MQ 用例还附带一批 200 笔、16 并发的服务层请求：200 笔受理并落库，受理约 0.322 秒，全部落库约 1.615 秒。该短批次排除了 HTTP、登录和 AOP 限流，也不是稳定负载压测，**不能支持“TPS 2000”**。本轮没有重测缓存延迟降低 30%。

## 面试怎么解释

这条链路由三层保护协作：

- **Redis 分布式锁**让同一用户、商品的支付与关单操作尽量串行，减少应用线程竞争。
- **数据库状态和版本条件更新**拒绝陈旧写入，即使有不经过同一把锁的写入，也不能直接覆盖订单状态；订单取消和 DB 库存回补在同一事务。
- **渠道查单与关单确认**处理支付宝这个外部系统。乐观锁只能保护本地数据库，不能证明支付宝没有付款，所以不能仅凭本地超时返还库存。

可以将原 bullet 调整为：

> 基于 RocketMQ 延迟消息实现超时关单，结合 Redis 分布式锁与订单状态 / 版本条件更新协调支付确认和关单，支持重复消息幂等及库存补偿重试；通过真实 MQ 投递与并发故障注入验证。

支付宝作为补充说明：

> 接入支付宝沙箱，使用 RSA2 验签、唯一支付流水与服务端查单处理重复通知和支付结果不确定。

不要描述为“仅靠乐观锁解决整个支付一致性”或“生产支付闭环已验收”。长期 NOT_FOUND 会保留库存，冲突终态仍需人工核账；MQ 重试耗尽后也需要人工恢复。扫码实付、公网回调、自动退款、基础设施故障切换不在此次通过范围。
