# Hook Guidelines

> 组合式函数（composable）约定。

---

## Overview

**本项目无自定义 composable**（无 `useXxx` 文件、无 `composables/`/`hooks/` 目录）。`useRoute()`/`useRouter()` 是 vue-router 自带，不算自定义。逻辑直接写在组件 `<script setup>` 内。

---

## Custom Hook Patterns

- 当前不提取 composable。每个页面组件自包含其逻辑（数据获取、表单、状态）。
- 若未来逻辑跨页面复用（如多处用相同 API + 加载态），可提取 `src/composables/useXxx.ts`——但**目前未采用**，勿擅自引入破坏一致性。

---

## Data Fetching

- 用 **axios**，封装在 `src/api/index.ts`。该文件导出默认 `api` 对象（方法 `get/post/put/delete/postForm`），内部 `axios.create` 实例 + 请求拦截器（注入 `token` header）+ 响应拦截器（统一 `ElMessage.error` 错误提示、401 处理）。
- 各 view 直接 `import api from '../api'` 调用：
  ```ts
  const res = await api.post('/api/xiaochan/query', requestData)
  if (res.data.success) { /* res.data.data */ }
  ```
- **响应未类型化**：`api.post` 返回 `Promise<AxiosResponse>`（默认 any），各 view 直接访问 `res.data.success`/`res.data.data`/`res.data.msg`，无泛型。
- 无 React Query/SWR 等数据缓存层。

---

## Naming Conventions

- 若新增 composable：`useXxx` 命名，放 `src/composables/`（目前无此目录，引入需约定）。

---

## Common Mistakes

- 不要在 view 外建数据获取逻辑分散——目前统一走 `src/api/index.ts` 的 `api` 对象。
- 认证门控：加载数据前必须 `await authState?.waitForAuth()`（见 `HomeView.vue:626`、`MonitorConfigView.vue:479`），否则 token 未就绪请求会 401。
