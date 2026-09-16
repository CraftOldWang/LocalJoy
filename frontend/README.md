# LocalJoy React 前端

主站围绕简历中的店铺商品、秒杀订单和模拟支付重构，使用 React 19 + React Router 7 + Vite 8。原版 Vue 页面只保留在 `public/legacy`，不再承担主流程。

## 运行

需要 Node.js 22.12+，后端默认运行于 8081。

```powershell
cd frontend
npm ci
npm run dev
```

打开 http://127.0.0.1:8088 。`BACKEND_URL` 覆盖代理目标，`PORT` 覆盖开发端口。预览模式与开发模式不要同时占用 8088。

```powershell
npm test
npm run check
npm run build
npm run preview
```

构建产物在 `dist`。仓库根目录的 `node frontend/server.mjs` 也能提供构建产物、SPA 路由回退与 API 代理；仅监听本机，不作为公网服务器使用。

## 代码入口

| 文件 | 职责 |
| --- | --- |
| `src/main.jsx` | React 根组件、路由、登录态、导航与保护路由 |
| `src/pages/Discover.jsx` | 首页商品、真实店铺搜索与分页、店铺详情 |
| `src/pages/Product.jsx` | 元数据与活动查询、抢购提交 |
| `src/pages/Orders.jsx` | 订单分页、受理结果查询、模拟支付 |
| `src/pages/Login.jsx` | 验证码表单、字段错误、返回原页面 |
| `src/components.jsx` | 图片、空/加载/失败状态、商品卡片、分页、图标 |
| `src/hooks.js` | 时钟与带取消能力的数据加载 |
| `src/domain.mjs` | 请求适配、认证过期、金额/日期/ID、活动状态 |
| `src/styles.css` | 共享 token、页面布局、响应式与无障碍状态 |

首页搜索支持 300ms 防抖、中文输入法组合期间不提交、清除按钮与旧请求取消。React 只显示后端数据，不将受理响应伪装成建单成功。

订单号必须是字符串。金额由分转元；LocalDateTime 按北京时间解释。生产部署需由反向代理提供 SPA fallback 和 `/api` 转发，不要把 token 放进 URL。

## 开发与历史资料

- 全站主流程约定：[UX-CONTRACT](../UX-CONTRACT.md)。
- 样式 token 和迁移范围：[DESIGN](../DESIGN.md)。
- 实际运行结果：[前端验收记录](../docs/frontend-acceptance.md)。
- 原版 Vue 2 / Element UI / Axios 与图片保留原来源声明，旧社交功能未在本轮重新验收。
- `public/imgs/*-generated.png` 是本轮 AI 生成的演示图片；主站对生成图片显示来源标记。
