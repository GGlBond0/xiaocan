# State Management

> 状态管理约定。

---

## Overview

**无 Pinia、无 Vuex、无全局 store 库**。`package.json` dependencies 仅 `axios`/`element-plus`/`vue`/`vue-router`。状态分三类管理：组件本地 ref/reactive + provide/inject 跨层 + localStorage 持久化。

---

## State Categories

### 组件本地状态（`ref`/`reactive`）
- 各 view 内部状态全本地化。例 `MonitorConfigView.vue`：`const configList = ref<any[]>([])`、`const loading = ref(false)`。
- `reactive` 用于对象表单：`HomeView.vue` 的 `reactive({ cityCode: null as number | null, ... })`。

### 跨层认证状态（provide/inject）
- `App.vue` provide `authState`，含 `isAuthenticated`（readonly ref）、`setAuthenticated`、`waitForAuth`（Promise）。
- 各 view `inject('authState')`，`onMounted` 内 `await authState?.waitForAuth()` 后加载数据。
- 这是项目唯一的"全局状态"机制。

### 持久化（localStorage）
- **token**：`src/api/index.ts` `getToken()` 读；`NavBar.vue` 写入（URL query / 手动输入 / 注册返回）；登录后 `window.location.reload()` 刷新。
- **selectedAddressId**：`HomeView.vue` 读写默认地址记忆。
- 无统一 localStorage 封装，直接 `localStorage.setItem/getItem/removeItem`。

### 服务端状态
- 无缓存层（无 SWR/React Query）。每次进入页面 `onMounted` 重新请求。
- 后端 BaseResult 响应：`{ success, code, msg, data }`，前端判 `res.data.success`。

---

## When to Use Global State

- 当前唯一全局状态是认证（provide/inject）。
- 新增需跨页面共享的状态：优先考虑 `App.vue` provide（遵循现有模式），**不引入 Pinia**（保持依赖精简）。仅当多个无父子关系的深层组件都需访问时才考虑。

---

## Server State

- 不缓存、不同步。页面 `onMounted` 拉取，本地 ref 存。操作后重新拉取或本地更新。
- 错误统一在 `src/api/index.ts` 响应拦截器 `ElMessage.error`。

---

## Common Mistakes

- 不要引入 Pinia——现状无，引入需明确约定。
- 不要在路由层做认证守卫——当前**无 `router.beforeEach`**，认证靠组件层 `waitForAuth` + `NavBar` 弹登录框。改此模式需全盘调整。
- localStorage 的 key（`token`、`selectedAddressId`）是约定，勿改名。
- 401 时拦截器只 `ElMessage.error`，**不自动清 token / 不跳转**（见 `api/index.ts:42-44`），需用户重新输入 token。
