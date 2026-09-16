# 支付宝沙箱接入与支付一致性

LocalJoy 支持两种开发支付方式：本地模拟直接确认订单；支付宝沙箱通过官方 SDK 预下单，展示真实沙箱二维码，再通过验签通知或服务端查单确认结果。两种方式都不扣真实资金。支付宝网关固定为沙箱，不能通过配置切到生产。

## 1. 开通与密钥配置

1. 登录[支付宝开放平台沙箱](https://open.alipay.com/develop/sandbox/app)，找到沙箱应用、APPID 和沙箱商家 PID。沙箱买家与商家是不同账号。
2. 当前控制台支持“系统默认密钥”。如果使用它，直接查看已有密钥即可，不需要另外生成或重置。
3. 将 `config/alipay-sandbox.example.properties` 复制到 `.runtime/alipay/application.properties`，填写 APPID、商家 PID。
4. 在同一目录创建两个 UTF-8 文本文件：`app-private-key.txt` 放 **JAVA 语言应用私钥**；`alipay-public-key.txt` 放底部的 **支付宝公钥**。可粘贴单行正文，代码也支持 PEM 头尾和换行。

三种密钥的作用不要混淆：

| 字段 | 放在哪里 | 用来做什么 |
| --- | --- | --- |
| 应用私钥 | 本机后端的私钥文件 | 给发往支付宝的请求签名 |
| 应用公钥 | 支付宝开放平台 | 支付宝验证我们的请求；系统默认模式已经配好，无须再填写本机 |
| 支付宝公钥 | 本机后端的公钥文件 | 验证支付宝响应和异步通知 |

如果改成自己生成密钥，需将自己生成的**应用公钥**上传平台，本机保留配对的应用私钥；“支付宝公钥”仍然来自平台。不要把应用公钥误填到支付宝公钥文件。

`.runtime/` 已被 Git 忽略。不要把密钥粘贴到聊天、README、截图或提交记录。密钥变更后重启后端。配置检查只能确认本地格式，真正匹配与权限仍需要一次沙箱 API 请求验证。

## 2. 启动与体验

先按 README 启动 MySQL、Redis、RocketMQ，再从仓库根目录执行：

```powershell
mvn -DskipTests package
java -Duser.timezone=Asia/Shanghai -jar target/hm-dianping-0.0.1-SNAPSHOT.jar --spring.config.additional-location=file:.runtime/alipay/application.properties
```

前端仍在 `frontend` 运行 `npm start`。登录后**重新下单**，在订单页选择“支付宝沙箱支付”，用开放平台提供的沙箱钱包与沙箱买家账户扫码。普通支付宝不能用于该测试。若设备无法使用沙箱钱包，可以先验证预下单和查单；不要将显示二维码当作付款成功。

订单金额从下单时的服务端商品数据固化，以“分”存储，前端不能指定金额。V5 迁移新增订单金额和标题快照；历史订单仍为 NULL，不拿今天的商品价冒充历史成交价，因此旧订单需重新下单才能使用沙箱。

只要创建了支付宝支付记录，这笔订单就不能切回本地模拟，避免渠道稍后支付成功导致两条流程互相覆盖。没有渠道记录的订单仍可使用本地模拟。配置 `localjoy.payment.mock-enabled=false` 可关闭模拟入口与接口。

## 3. 本地怎么收到支付结果

### 不配置公网回调

`notify-url` 留空即可。后端每轮最多处理 20 笔未完成流水，启动 30 秒后开始，每轮结束等待 15 秒；调用支付宝查单或关单，进程重启后可从数据库继续。订单页每 5 秒查询的是**本地订单**，不会每次都调用支付宝。也可以点“查询支付结果”，服务端对同一订单普通查单间隔至少 5 秒。

### 配置真实异步回调

设置公网 HTTPS 地址，例如 `https://你的域名/payment/alipay/notify`。支付宝服务器无法访问你电脑的 `127.0.0.1`。如需内网穿透，应只转发这个 POST 路径，不要把含课程遗留写接口的整个后端公开。

通知接口无需用户登录，但必须通过 RSA2 验签，并校验 APPID、商家 PID、商户订单号、金额及支付宝交易号。重复参数直接拒绝。处理成功返回纯文本 `success`，失败返回 `failure` 让渠道重试。浏览器跳转、前端提示与二维码扫描本身均不能作为已支付依据。

服务端查单也通过 SDK 验证渠道响应签名，并校验订单号、金额；查单补偿和异步通知共享同一套入账逻辑。

## 4. 支付与关单如何竞争

所有支付宝操作、本地模拟支付和超时关单共用 `product:order:{productId}:{userId}` 锁；数据库仍用 `status + version` 条件更新兜底。网络调用在数据库事务外，避免长时间占用数据库事务。

### 重复点击、回调丢失与重复通知

先在数据库记录 `CREATING` 流水和唯一商户订单号，再调用预下单。重复点击返回已有流水，不再调用第二次预下单。返回二维码后为 `WAITING`；超时或结果不确定为 `UNKNOWN`，可查单补偿。唯一订单流水、商户订单号、支付宝交易号约束，加上锁和条件更新，使重复成功通知不会二次扣库存或二次确认。

如果预下单成功后应用在保存二维码前崩溃，流水保留，但二维码可能无法恢复；不会为了补二维码盲目重发创建。这是当前保守实现的体验边界。

### 超时关单与付款同时发生

1. 本地截止时间已到，先查询渠道。
2. 若已支付，按真实渠道成功结果将本地订单改为已支付，即使通知晚于本地截止时间到达。
3. 若未支付，向支付宝请求关单；只有收到明确关闭结果，才进行本地取消和库存回补。
4. 关单失败时再查一次，检查是否付款抢先成功。网络异常或状态不确定则保留库存，等待重试。
5. 渠道关闭已记录但本地事务失败，下次任务根据 `CLOSED` 流水继续本地关单，不重复扣减或回补库存。

**查不到交易不等于交易肯定不会出现。** 预下单与实际交易存在阶段差异，也可能有迟到请求，因此目前 `NOT_FOUND` 不触发库存回补。一直未被扫描的二维码、失败但结果不明的创建可能长期保留库存，需要人工核对。预下单同时传绝对支付截止时间；当前没有用未经实测的“等几分钟就算安全”代替渠道关闭证据。

若渠道报告已支付、而本地已是冲突终态，则保留 `REVIEW_REQUIRED` 流水，记录交易号并输出仅含订单号的核账告警，不擅自恢复已释放的库存。当前没有自动退款与管理核账页面，需要人工处理；这不是完整的生产支付账务系统。

## 5. 接口与实现位置

| 接口 | 权限与用途 |
| --- | --- |
| `GET /payment/options` | 公开，仅返回支付配置是否可用，不返回密钥或账号配置 |
| `POST /payment/alipay/{orderId}` | 当前订单所有者，创建一次沙箱支付 |
| `GET /payment/alipay/{orderId}` | 当前订单所有者，读取本地支付流水和有效二维码 |
| `POST /payment/alipay/{orderId}/sync` | 当前订单所有者，服务端查单 |
| `POST /payment/alipay/notify` | form-urlencoded 通知，RSA2 验签 |

- `payment/AlipaySandboxGateway.java`：官方 SDK、密钥读取、固定沙箱网关、金额转换、验签。
- `payment/PaymentService.java`：归属校验、支付流水、幂等确认和渠道关单协调。
- `payment/PaymentReconciler.java`：持久化流水的定时恢复。
- `service/impl/ProductOrderServiceImpl.java`：价格快照、原有库存与订单状态机衔接。
- `frontend/src/PaymentPanel.jsx`：本地生成二维码、支付状态和手动查单。

## 6. 验证方法与证据边界

使用隔离数据库 `hmdp_resume_test`、Redis DB14，按 `docs/resume-evidence.md` 准备测试依赖后执行：

```powershell
mvn "-Dtest=AlipayPaymentTest,AlipaySignatureTest,ResumeEvidenceTest" test
cd frontend
npm test
npm run check
npm run build
```

`AlipayPaymentTest` 使用真实 MySQL/Redis，模拟支付宝和 MQ 传输，覆盖金额快照、用户归属、重复创建、不确定预下单、错误通知、并发重复通知、迟到通知、关单与付款竞争、查单恢复、终态冲突和通知鉴权边界。`AlipaySignatureTest` 生成临时 RSA 密钥，实际运行 RSA2 签名/验签与篡改检测，不使用开发者私钥。

自动化测试不等于真实沙箱扫码付款或公网回调验证。实际联调结果需单独记录；TPS 和缓存性能不能沿用此前版本而不重新测量，因为下单路径新增了商品快照读取。

### 2026-09-16 本次证据

[测试清单与证据边界](evidence/2026-09-16-alipay-tests.json)。SDK 原始请求/响应日志已关闭，应用仅记录订单号和必要的错误码，避免保存买家信息与完整签名报文。

- 上述 Java 测试合计 29 项：26 通过，3 项旧有的可选 MQ/压测用例跳过。MySQL/Redis 为真实隔离实例；支付宝/MQ 传输模拟。
- 前端 5 项测试通过，`check`、生产构建通过；UI strict audit 0 errors / warnings，DESIGN lint 0 errors、8 个已有 token 引用提示。
- 本机实际加载沙箱密钥，新订单经真实 RocketMQ 消费建单后，成功调用支付宝沙箱预下单、生成 ¥19.90 二维码；主动查单完成，尚未确认付款。
- 内置浏览器验证登录、抢购、金额快照、两种支付选择、创建后隐藏模拟入口、二维码和主动查单；390px 窄屏下支付卡片可正常操作。
- **仍待验证：沙箱买家扫码付款后的成功入账，以及公网异步通知。** 当前没有配置公网回调，依靠服务端查单。关单竞态、验签与补偿测试为自动化故障注入证据，不能冒充全部实网场景。

参考：[官方 Java SDK](https://github.com/alipay/alipay-sdk-java-all/tree/master/v2)、[扫码支付流程](https://developer.alibaba.com/docs/doc.htm?articleId=105170&docType=1&treeId=193)、[通知处理校验](https://developer.alibaba.com/docs/doc.htm?articleId=105902&docType=1&treeId=193)。
