# LocalJoy React 交互约定

## 权威来源与范围

接口以 ProductController、ProductOrderController、ProductOrderServiceImpl 为准。2026-09-16 用户选择 React，并明确聚焦简历中的商品秒杀、缓存、异步订单、超时关单与限流，不要求继续开发原版社交功能。

## Canonical UI Map

| Capability | Canonical owner | Source of truth | Allowed variants | Verification |
| --- | --- | --- | --- | --- |
| Form | src/pages/Login.jsx + domain.mjs | 验证码登录 API | 原生手机号/验证码、noValidate | 字段错误、键盘提交、失败重试 |
| Scrollbar | src/styles.css 全局规则 | DESIGN.md | 强制颜色模式使用系统值 | 静态检查与浏览器 |
| Toast | src/components.jsx Notice / State | 本文 | 持续的 status / alert，无关键短暂消息 | 网络错误与业务错误可恢复 |
| CRUD | src/pages + domain.mjs + PaymentPanel.jsx | 后端用户归属与状态机 | 商品只读、创建订单、本地模拟或支付宝沙箱，无删除 | 登录→秒杀→查询→支付 |

无表格多选、Select/Listbox、日期输入或模态对话框需求，不新增这些组件。历史 Vue 页面不属于该迁移范围。

## 页面与交互

- 主导航是附近好物、我的订单和登录态；手机保留底部双入口。原版 Vue 仅在页脚存档入口出现。
- 首页推荐读取店铺 1 的真实商品。店铺搜索使用 /shop/of/name，300ms 防抖，组合输入期间不请求，旧请求在新查询时取消，URL 保留 q/page；清除搜索回到第 1 页并聚焦输入。
- 店铺页显示资料与商品。商品详情分别读取元数据和活动列表；活动缺失、未开始、已结束、售罄时禁用抢购。
- 未登录购买先跳转验证码登录；returnTo 限同源相对路径。登录后回原页面，不自动重复下单。
- 写操作用 ref 和 busy 双重防重入，不自动重发；失败提示查看订单，避免请求结果不确定时反复提交。
- 订单列表由服务端按当前用户查询，每页 10 笔，页码在 URL；不能由客户端指定 userId 决定归属。
- 受理后的订单页最多自动查询 30 次：未建单 2 秒、待支付 5 秒；离开页面取消请求和定时器。超限后保留手动刷新与订单列表入口。
- 模拟支付只在待支付且本地倒计时未结束时启用；服务器截止时间和状态条件仍是最终校验。界面明确“不扣款”。
- 查询失败不能变成成功或空订单；已取得订单的数据可保留，同时显示陈旧结果提示。付款失败后重新查询，处理并发状态变化。
- 401 清除 token 并跳转登录；429 提示稍后重试；网络失败不触发二次 JS 异常。useResource 取消卸载与过期请求。
- 全部交互使用 button/link、可见焦点、关联 label、错误文字和实时状态；禁止浏览器原生 alert/confirm/prompt。

## 数据与边界

中文 zh-CN，时间按 Asia/Shanghai。金额由分转元，订单 ID 始终字符串。token 按现有架构保存在 sessionStorage，不写入 URL、仓库或验收报告。

新增订单记录下单金额与标题快照；历史订单保持缺失状态，支付宝入口禁用并说明原因。没有真实资金扣款、退款、核销与管理员后台。原版课程写接口需要另行补权限体系才能面向公网。

生成图片标记为 AI 演示配图；店铺资料为课程样例。无假成交数据、假评论或伪造服务协议。代码入口与浏览器验证见 docs/frontend-acceptance.md。

## 支付宝沙箱交互

- PaymentPanel 是唯一支付操作入口，复用 Notice、request 和订单页面的本地轮询；每次普通页面轮询不直接请求支付宝。
- 同一时刻只允许一个写请求，离开页面取消等待；服务端可能已受理，所以失败后先读取流水，不自动重发 POST。
- 未配置显示原因；历史无金额快照无法创建沙箱支付；支付数据读取失败时禁用写操作并保留刷新入口。
- 有支付宝流水后不可切回本地模拟。二维码内容只来自后端，仅 WAITING 且截止前展示，二维码在浏览器本地生成。
- UNKNOWN / CREATING / CLOSING 状态提供查单；SUCCEEDED / CLOSED / REVIEW_REQUIRED 不再提供付款操作。终态冲突明确要求人工核账。
- 支付状态以服务端查单或验签通知为准，页面不能用扫描、返回页或倒计时推断付款结果。
