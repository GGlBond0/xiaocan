# Quality Guidelines

> 前端质量约定。

---

## Overview

Vue3 + Vite + Element Plus + axios + vue-router。**无 ESLint 配置、无 Prettier、无测试**（无 vitest/jest，`tsconfig.app.json` 的 `__tests__` exclude 是脚手架占位）。质量靠 `vue-tsc` 类型检查 + 构建通过。

---

## Forbidden Patterns

- **不要用 Options API**——统一 `<script setup lang="ts">`。
- **不要引入 Pinia/Vuex**——现状无状态库。
- **不要用值导入代替 `import type`**——`verbatimModuleSyntax: true` 会报错。
- **不要改 localStorage key**（`token`、`selectedAddressId`）——是约定。
- 不要在路由层加 `beforeEach` 守卫——认证靠组件层 `waitForAuth`，改模式需全盘调整。
- 不要本地注册已全量注册的 Element Plus 组件。

---

## Required Patterns

- 组件用 `<script setup lang="ts">`，顺序 `script → template → style`。
- views 命名 `XxxView.vue`，放 `src/views/`，懒加载路由 `() => import(...)`。
- HTTP 走 `src/api/index.ts` 的 `api` 对象（已封装 token 注入 + 错误提示），不要直接 `axios` 裸调。
- 加载数据前 `await authState?.waitForAuth()`（token 就绪）。
- 样式 `<style lang="scss" scoped>`，SCSS 变量在块顶部。
- 构建前跑 `npm run build`（含 `type-check` + `build-only` 并行），无错误才部署。

---

## Testing Requirements

- **当前无测试**。不强制新增。新功能至少手动浏览器验证 + 控制台无报错。

---

## Code Review Checklist

- 是否 `<script setup lang="ts">`，类型导入用 `import type`。
- HTTP 是否经 `api` 对象（token 注入 + 401 提示）。
- 加载数据前是否 `await waitForAuth()`。
- 新页面是否在 `router/index.ts` 加懒加载路由。
- 样式是否 scoped（除非需穿透弹层）。
- `npm run build` 是否通过（type-check + vite build）。
- 部署：构建 `dist/` → scp `/var/www/xiaocan/dist` → nginx 直接读（无需 reload）。勿在服务器构建。见 memory `deploy-topology`。
