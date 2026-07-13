# Type Safety

> TypeScript 约定。

---

## Overview

TypeScript + `vue-tsc --build` 类型检查（`package.json` `type-check` 脚本）。tsconfig 严格：`@vue/tsconfig` 基线 `strict: true`、`verbatimModuleSyntax: true`、`noImplicitThis: true`，`tsconfig.app.json` 额外 `noUncheckedIndexedAccess: true`。**无独立 `types/` 目录**，无共享 interface/type 文件。

---

## Type Organization

- **无集中类型目录**。类型内联在组件内。
- `import type` 仅用于第三方类型（如 `import type { FormInstance } from 'element-plus'`）——`verbatimModuleSyntax: true` 强制类型导入必须 `import type`。
- `env.d.ts` 仅 `/// <reference types="vite/client" />`，无自定义全局类型。

---

## Validation

- **无运行时校验库**（无 Zod/Yup/io-ts）。表单校验靠 Element Plus `el-form` rules + 手动。
- 后端响应不校验，直接按结构访问。

---

## Common Patterns

- **大量 `any`**（既有现状，非理想）：`ref<any[]>([])`、`const requestData: any`。见 `MonitorConfigView.vue`、`HomeView.vue`、`LocationView.vue`。
- 少量内联类型：`reactive<Record<string, boolean>>({})`、`reactive({ cityCode: null as number | null })`。
- axios 响应未类型化：`api.post` 返回 `Promise<AxiosResponse>`（any），各 view 直接 `res.data.success`/`res.data.data`。
- 模块：`module: ESNext`、`moduleResolution: bundler`、`target: ESNext`、`jsx: preserve`。

---

## Forbidden Patterns

- **不要用值导入代替类型导入**——`verbatimModuleSyntax: true` 下类型必须 `import type`，否则 type-check 报错。
- 新代码尽量减少 `any`（虽现状大量使用），优先给 ref/API 响应加具体类型。但**不要为追求严格而大规模重构既有 `any`**（记录现状，改进是单独讨论）。
- 不要建 `types/` 全局类型文件——现状无，引入需约定。
