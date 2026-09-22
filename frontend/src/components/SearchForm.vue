<script setup lang="ts">
/**
 * 查询栏封装。
 *
 * 只做三件事：卡片外壳、`el-form` 的 inline 布局、查询/重置按钮。
 * **字段交给默认插槽** —— 各页的查询条件差异很大（图书 12 个、审计 4 个、
 * 任务 0 个），做成配置数组会立刻变成一堆 if。
 *
 * <h3>为什么用 CSS Grid 而不是 `el-form :inline="true"`</h3>
 * inline 模式下每个 `el-form-item` 宽度由内容决定，于是「分类」和「出版时间区间」
 * 两行的输入框左边界对不齐 —— 看起来像没做完。用 Grid 固定列宽后是整齐的网格。
 *
 * ⚠️ 光有 Grid 还不够：`el-form-item` 的标签是行内右对齐的，标签长短不同，
 * 输入框的左边界还是会错开。所以各页**要传 `labelWidth`**（比如 `"92px"`），
 * 让所有标签占同样的宽度。
 *
 * `<el-form @submit.prevent>` 是为了**回车即查询**：`el-input` 在 form 里按回车会触发
 * 原生 submit，不 prevent 的话浏览器会刷新页面（hash 路由下会丢掉当前路由）。
 */

withDefaults(
  defineProps<{
    /** 表单数据对象。只是为了让 el-form 拿到 model（校验用） */
    model?: Record<string, unknown>
    /** 查询按钮的 loading */
    loading?: boolean
    /** 每行放几个字段 */
    columns?: number
    /** 标签宽度。不传就是行内自适应，输入框左边界会随标签长短错开 */
    labelWidth?: string
  }>(),
  {
    model: () => ({}),
    loading: false,
    columns: 4,
    labelWidth: 'auto',
  },
)

const emit = defineEmits<{
  (e: 'search'): void
  (e: 'reset'): void
}>()
</script>

<template>
  <el-card shadow="never" class="search-form">
    <el-form
      :model="model"
      class="form"
      :label-width="labelWidth"
      :style="{ '--cols': String(columns) }"
      @submit.prevent="emit('search')"
    >
      <slot />

      <div class="actions">
        <el-button type="primary" :loading="loading" @click="emit('search')">
          <el-icon><Search /></el-icon>
          查询
        </el-button>
        <el-button @click="emit('reset')">
          <el-icon><RefreshRight /></el-icon>
          重置
        </el-button>
        <slot name="actions" />
      </div>
    </el-form>
  </el-card>
</template>

<style scoped>
.search-form :deep(.el-card__body) {
  padding: 16px 16px 0;
}

.form {
  display: grid;
  grid-template-columns: repeat(var(--cols, 4), minmax(0, 1fr));
  gap: 0 16px;
  align-items: start;
}

/* 让 el-form-item 撑满自己的网格单元，label 与输入框左右对齐 */
.form :deep(> .el-form-item) {
  margin-bottom: 16px;
  margin-right: 0;
  width: 100%;
}
.form :deep(.el-form-item__content) {
  flex: 1;
  min-width: 0;
}
.form :deep(.el-input),
.form :deep(.el-select),
.form :deep(.el-input-number) {
  width: 100%;
}

/* 按钮区不占网格单元，单独一行靠右 */
.actions {
  grid-column: 1 / -1;
  display: flex;
  justify-content: flex-end;
  align-items: center;
  gap: 8px;
  padding-bottom: 16px;
}

@media (max-width: 1200px) {
  .form {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}
@media (max-width: 720px) {
  .form {
    grid-template-columns: minmax(0, 1fr);
  }
}
</style>
