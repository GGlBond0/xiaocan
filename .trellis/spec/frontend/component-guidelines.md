# Component Guidelines

> 组件约定。

---

## Overview

全部使用 `<script setup lang="ts">`，**无 Options API**。组件间不通过 props 通信——`NavBar` 不接收 props，views 之间通过路由跳转 + provide/inject 共享认证状态。

---

## Component Structure

`.vue` 文件内固定顺序：`<script setup lang="ts">` → `<template>` → `<style>`。

```vue
<script setup lang="ts">
import { ref, reactive, onMounted, inject } from 'vue'
import api from '../api'
// ...逻辑
</script>

<template>
  <!-- Element Plus 组件 -->
</template>

<style lang="scss" scoped>
$primary: #...;
/* 样式 */
</style>
```

例外：`HomeView.vue` 末尾额外一个非 scoped 的 `<style lang="scss">` 块（用于 `el-dialog` append-to-body 样式穿透，见 `HomeView.vue:1933`）。

---

## Props Conventions

- **当前不使用 props**：`defineProps`/`withDefaults`/`defineEmits` 在 `src/` 无匹配。组件间通过 provide/inject + 路由通信。
- 若新增可复用组件需要 props，用 `defineProps<{...}>()` + TS 泛型（遵循 `<script setup>` 习惯）。

---

## Styling Patterns

- **SCSS + scoped**：views 用 `<style lang="scss" scoped>`（`HomeView.vue`、`MonitorConfigView.vue`、`LocationView.vue`、`NotifyHistoryView.vue`）。
- `App.vue`/`NavBar.vue` 用 `<style scoped>`（无 lang）。
- SCSS 变量定义在各组件 scoped 块顶部（如 `HomeView.vue` 的 `$primary`），**无全局 SCSS 变量文件**。
- 全局样式：`src/styles/global.scss`。
- Element Plus：`main.ts` 全量注册 `app.use(ElementPlus)`，组件直接用 `el-card`/`el-dialog`/`el-form`/`el-table`；图标按需 `import { ArrowDown } from '@element-plus/icons-vue'`。

---

## Accessibility

- 未建立 a11y 标准（既有现状）。Element Plus 组件自带基础 a11y，本任务不强制新增。

---

## Common Mistakes

- 不要用 Options API——统一 `<script setup lang="ts">`。
- 样式穿透 `el-dialog`/`el-select` 弹层（append-to-body）需用非 scoped style 块或 `:deep()`，见 `HomeView.vue` 末尾非 scoped 块。
- Element Plus 组件已全量注册，无需在各组件局部注册。
