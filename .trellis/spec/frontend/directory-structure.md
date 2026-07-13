# Directory Structure

> 前端代码组织（xiaocan-front，Vue3 + TS + Vite）。

---

## Overview

Vue3 SPA，`src/` 目录精简，**无** `store/`、`utils/`、`types/`、`composables/` 子目录。`@` 别名指向 `./src`（见 `vite.config.ts`）。

---

## Directory Layout

```
src/
├── api/            # HTTP 封装（src/api/index.ts 唯一）
├── assets/         # 脚手架默认资源（base.css/main.css/logo.svg，未被 main.ts 引用，可忽略）
├── components/      # 通用组件（NavBar.vue）；components/icons/ 为脚手架残留未用
├── router/          # 路由（src/router/index.ts）
├── styles/          # 全局样式（src/styles/global.scss）
├── views/           # 页面组件，全部 XxxView.vue
├── App.vue          # 根组件，provide 认证状态
└── main.ts          # 入口：createApp + 全量注册 Element Plus + 挂载 router
```

`src/**/*.ts` 仅 3 个文件：`api/index.ts`、`main.ts`、`router/index.ts`。

---

## Module Organization

- 新页面 → `src/views/XxxView.vue`，并在 `src/router/index.ts` 加懒加载路由 `() => import('../views/XxxView.vue')`。
- 跨页复用组件 → `src/components/`（PascalCase，无后缀）。
- 不要新建 `store/`、`hooks/`、`utils/`、`types/` 目录——本项目用 provide/inject + localStorage 管状态，逻辑直接写在 `<script setup>` 内。

---

## Naming Conventions

- 页面组件：`XxxView.vue`（PascalCase + `View` 后缀），放 `views/`。例 `HomeView.vue`、`MonitorConfigView.vue`、`LocationView.vue`、`NotifyHistoryView.vue`。
- 通用组件：PascalCase 无后缀。例 `NavBar.vue`。
- 入口：`App.vue`、`main.ts`。

---

## Examples

- 页面 + 路由：`HomeView.vue` ↔ `router/index.ts` 路由 `/`。
- 认证 provide：`App.vue` provide `authState`，各 view `inject('authState')`。
- API 调用：view 内 `import api from '../api'`，`api.post('/api/...', data)`。
