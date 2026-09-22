<script setup lang="ts">
/**
 * 分页表格封装。
 *
 * <h3>为什么用「插槽传列」而不是「配置数组传列」</h3>
 * 试过配置数组（`columns: [{ prop, label, width, formatter }]`），但本项目有大量
 * 「单元格里要放自定义内容」的场景：封面缩略图、状态标签、操作按钮组、可展开的 URL。
 * 配置数组最后必然演变成「加一个 `type: 'slot'` 字段 + 一堆 if」，比直接写
 * `<el-table-column>` 更难读。
 *
 * 所以这里只封装**真正重复的部分**：loading、空状态、分页、多选。
 * 列交给默认插槽，页面想怎么写就怎么写。
 *
 * <h3>它处理掉的一个坑</h3>
 * **改每页条数时必须把页码重置到 1**。否则「在第 5 页把 size 从 20 改成 100」
 * 会请求 `page=5&size=100` —— 而总数可能只有 3 页，结果是**一个空表格**，
 * 用户以为数据没了。这个重置逻辑写在这里，页面不用各写一遍。
 *
 * <h3>⚠️ 它会造成「一次操作、两次请求」</h3>
 * 换每页条数时它**先 emit `update:size` 再 emit `update:page(1)`**，
 * 两个事件都会让页面去请求数据。页面侧要么合并（微任务里攒一次再请求），
 * 要么就会发出两个完全相同的请求 —— 而且它们**返回顺序不保证**，
 * 后发的先回来就会把先发的结果覆盖掉。
 * 用法见 `views/book/index.vue` 的 `scheduleReload()`。
 */

withDefaults(
  defineProps<{
    /** 表格数据 */
    data: unknown[]
    /** 加载中 */
    loading?: boolean
    /** 总条数 */
    total?: number
    /** 当前页（从 1 开始） */
    page?: number
    /** 每页条数 */
    size?: number
    /** 每页条数可选项 */
    pageSizes?: number[]
    /** 行主键。不传时 el-table 会用索引，翻页后选中态会错乱 */
    rowKey?: string
    /** 是否显示多选列 */
    selectable?: boolean
    /** 是否显示分页器 */
    showPagination?: boolean
    /** 空数据文案 */
    emptyText?: string
    /** 表格最大高度。传了就固定表头 */
    maxHeight?: string
    /**
     * 初始排序。只影响**表头上的箭头显示** ——
     * 真正的排序是服务端做的（`sortable="custom"` + `sort-change`）。
     * 不传的话，后端按推荐值降序返回，但表头上看不到箭头，
     * 用户会以为「没排序」。
     */
    defaultSort?: { prop: string; order: 'ascending' | 'descending' }
    /**
     * 行样式回调。请求审计页用它把「失败行」整行染红 ——
     * 排查时用户是「扫一眼找红色」，不是逐行读 statusCode。
     */
    rowClassName?: (payload: { row: never; rowIndex: number }) => string
  }>(),
  {
    loading: false,
    total: 0,
    page: 1,
    size: 20,
    pageSizes: () => [20, 50, 100],
    rowKey: 'id',
    selectable: false,
    showPagination: true,
    emptyText: '没有数据',
    defaultSort: undefined,
    rowClassName: undefined,
  },
)

const emit = defineEmits<{
  (e: 'update:page', value: number): void
  (e: 'update:size', value: number): void
  (e: 'selection-change', rows: unknown[]): void
  /** el-table 的排序回调。`order` 为 `null` 表示用户取消了排序 */
  (e: 'sort-change', payload: { prop: string | null; order: string | null }): void
}>()

function handlePageChange(next: number): void {
  emit('update:page', next)
}

function handleSizeChange(next: number): void {
  // ★ 换每页条数一定要回到第 1 页，否则会请求一个超出范围的页码 → 空表格
  emit('update:size', next)
  emit('update:page', 1)
}

function handleSelectionChange(rows: unknown[]): void {
  emit('selection-change', rows)
}

/**
 * 转发 el-table 的 sort-change。
 *
 * 不转发的话页面绑了 `@sort-change` 也收不到 —— `el-table` 在 ProTable 内部，
 * 事件不会自动冒泡出去。而 `sortable="custom"`（服务端排序）**必须**靠这个回调，
 * 否则表头点了没反应。
 */
function handleSortChange(payload: { prop: string | null; order: string | null }): void {
  emit('sort-change', payload)
}
</script>

<template>
  <div class="pro-table">
    <div v-if="$slots.toolbar" class="toolbar">
      <slot name="toolbar" />
    </div>

    <el-table
      v-loading="loading"
      :data="data"
      :row-key="rowKey"
      :max-height="maxHeight"
      :default-sort="defaultSort"
      :row-class-name="rowClassName"
      border
      stripe
      size="small"
      class="table"
      @selection-change="handleSelectionChange"
      @sort-change="handleSortChange"
    >
      <!--
        刻意**不加** reserve-selection（跨页保留选中）。
        理由：el-table 的「已选 N 条」只统计**当前页可见**的选中行，
        跨页保留时用户会看到「已选 3 条」却删掉 8 条 —— 数据损坏级别的不一致。
        翻页清空选中虽然少了个便利功能，但行为可预测。
      -->
      <el-table-column v-if="selectable" type="selection" width="44" />
      <slot />

      <template #empty>
        <div class="empty">
          <el-icon class="empty-icon"><Files /></el-icon>
          <span>{{ loading ? '加载中…' : emptyText }}</span>
        </div>
      </template>
    </el-table>

    <div v-if="showPagination" class="footer">
      <el-pagination
        :current-page="page"
        :page-size="size"
        :page-sizes="pageSizes"
        :total="total"
        layout="total, sizes, prev, pager, next, jumper"
        background
        small
        @current-change="handlePageChange"
        @size-change="handleSizeChange"
      />
    </div>
  </div>
</template>

<style scoped>
.pro-table {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.toolbar {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}

.table {
  width: 100%;
}

.footer {
  display: flex;
  justify-content: flex-end;
}

.empty {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 8px;
  padding: 24px 0;
  color: #a8abb2;
  font-size: 13px;
}
.empty-icon {
  font-size: 26px;
  color: #dcdfe6;
}
</style>
