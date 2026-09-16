---
version: alpha
name: LocalJoy 趣享生活
description: React 本地生活店铺与商品界面，以食物摄影和票券式信息结构呈现抢购与订单流程。
colors:
  primary: "#b83c16"
  accent: "#ff6633"
  background: "#f5f6f8"
  surface: "#ffffff"
  text: "#24262b"
  muted: "#626772"
  border: "#dce0e6"
  success: "#18704a"
  danger: "#b3261e"
  track: "#eef0f3"
  thumb: "#959ca8"
typography:
  body:
    fontFamily: "'Microsoft YaHei', 'PingFang SC', system-ui, sans-serif"
    fontSize: "16px"
    lineHeight: "1.7"
  numeric:
    fontFamily: "'Bahnschrift', 'Arial', sans-serif"
rounded:
  control: "10px"
  card: "18px"
spacing:
  unit: "8px"
  page-max: "1120px"
components:
  button:
    height: "44px"
  card:
    rounded: "18px"
---

# LocalJoy Design System

## Overview

面向中文本地生活学习项目，核心场景为手机浏览店铺、抢购商品、查看订单。2026-09-16 用户明确选择 React，围绕简历业务重构主站，保留橙色识别。主站采用 React + React Router + Vite；原版 Vue 页面仅存档。

视觉参考是原版店铺代金券：价格和操作之间用虚线分隔，形成一张可读的商品票券。真实商品、状态和操作优先，不加虚构成交数据。用深橙承担文字和按钮以改善对比度，原版亮橙作为品牌点缀。

范围：发现首页、店铺、商品详情、订单列表/详情和验证码登录。主导航只有附近好物、我的订单与登录，不加入未在简历主线中的笔记、签到、地图和消息。原版页面位于 `frontend/public/legacy`，由页脚低优先级入口进入。

运行时 token 以 `frontend/src/styles.css` 的 `:root` 为准，本文件镜像并解释。映射：`colors.X → --color-X`，`typography.body/numeric → --font-body/--font-numeric`，`rounded.X → --radius-X`，`spacing.unit → --space-unit`、`spacing.page-max → --page-max`。Node 检查脚本校验色值漂移。无需额外主题框架。

## Colors

白色卡片、浅灰页面；深灰正文和灰色辅助文字。深橙用于主操作及可交互链接，绿色表示已支付，红色表示错误。状态同时有文字，不只靠颜色。仅提供浅色主题；强制高对比模式保留系统边框与滚动条。

## Typography

中文使用系统雅黑/苹方，不下载字体。正文 16px、行高 1.7；标题 26–38px；价格 32px；订单号使用数字字体并允许换行。日期明确为北京时间，金额单位为人民币元，接口金额以分存储。订单号始终作为字符串。

## Layout

页面最大 1120px，24px 水平留白；600px 以下收为单列并用 18px 留白。商品卡片在桌面为双列，商品详情为左右布局，手机为上下布局。手机底部导航固定，页脚预留 100px 内容空间；页面自然滚动，不锁定整页高度。首页主图采用单一大圆角切口作为记忆点，其余卡片维持克制。

## Elevation & Depth

新增卡片以边框划分，不使用浮夸阴影或渐变。顶部和底部导航以白色表面、细边框与内容区分。提示插入固定最小高度区域。

## Shapes

控件 10px、卡片 18px 圆角。票券虚线只用于价格与操作区域，不用于所有内容块。商品图片预留 16:10 比例，缺图统一显示本地 SVG 占位。

## Components

共享 `State`、`Notice`、`Photo`、`ProductCard`、`Pager`、`Icon` 位于 `src/components.jsx`，应用壳与登录状态位于 `src/main.jsx`。按钮最小高度 44px，包含 hover、键盘焦点、按下、禁用和处理中状态。表单使用原生输入与明确 label，校验错误关联字段并聚焦首个错误。

提示为就地 `role=status/alert` 文本；加载为有文本的旋转指示，不把关键失败只放到短暂 toast。新增流程不需要选择器、日期选择器或模态弹窗，也不模拟付款确认弹窗。

主站使用统一线宽的内联 SVG 图标并配文字，不依赖 Element UI。动画仅用于加载；减少动态效果设置下停止动画。图片、请求失败及空状态都有固定内容区域。两张生成商品图仅用于演示，卡片、详情与主图明确标注 AI 配图。

## Do's and Don'ts

- 保留“附近好物 / 我的订单 / 本地模拟支付（不扣款）/ 支付宝沙箱支付”跨页面措辞。
- 下单受理后先显示等待建单，不提前承诺抢购成功。
- 订单只展示下单时的金额快照；旧订单缺少快照时解释并引导重新下单，不拿当前商品价补造历史成交价。
- 不放伪造协议、假支付二维码、假订单或无操作的按钮。
- 原版的静态评论与外链图片是历史素材边界，不代表新商品流程的数据来源。

## Payment extension — 2026-09-16

订单卡内复用票券虚线、按钮和 Notice，新增 PaymentPanel 作为支付操作的唯一入口。主操作为支付宝沙箱，次操作为本地模拟；创建渠道流水后隐藏模拟入口。二维码仅由真实沙箱返回的内容在本机生成，预留 256px 区域，过期或终态时移除。错误、待确认、关单中与人工核账都有文字反馈，不将请求不确定呈现为失败后可随意重试。
