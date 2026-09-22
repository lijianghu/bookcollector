<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { deleteTaxonomy, listTaxonomy, updateTaxonomy } from '@/api/taxonomy'
import { listTasks } from '@/api/task'
import ProTable from '@/components/ProTable.vue'
import TaxonomyFormDialog from './TaxonomyFormDialog.vue'
import { dash, formatDateTime } from '@/utils/format'
import type { CollectTask, TargetType, TaxonomyItem } from '@/types'

/**
 * 分类榜单（S6.4）。
 *
 * <h3>为什么两个 Tab 共用一个表格</h3>
 * 「分类」和「榜单」的字段、操作、业务规则**完全一样**，只有 `type` 不同
 * （后端的 `TaxonomyController` 也确实是两组几乎相同的方法）。
 * 做成两个表格组件就是把同一份代码抄两遍，以后加一列要改两处。
 * 所以这里只有一份表格，靠 `activeType` 切换数据源。
 *
 * <h3>三条业务规则（都在后端实现，前端要能解释给用户看）</h3>
 * 1. **编辑不允许改 `code`** —— 它是采集目标的身份，任务和游标都指向它。
 *    后端不是静默忽略而是**直接报错**（静默忽略会让人以为改成功了）。
 * 2. **有任务引用则拒绝删除** —— 提示还剩几个任务在引用。
 * 3. **只有游标、没有任务 → 允许删，并顺带清掉游标** —— 游标是采集过程的
 *    副产品，不是用户资产，留一条孤儿游标只会在「采集游标」页多一行噪音。
 *
 * <h3>「有几个任务引用它」怎么算</h3>
 * 直接拉一次 `/api/tasks` 在前端数，而不是为每一行发一个请求。
 * 任务量级是几十个，一次拉全量比 N 次查询便宜得多。
 */

const activeType = ref<TargetType>('CATEGORY')

const categories = ref<TaxonomyItem[]>([])
const rankings = ref<TaxonomyItem[]>([])
const tasks = ref<CollectTask[]>([])
const loading = ref(false)

const rows = computed(() =>
  activeType.value === 'CATEGORY' ? categories.value : rankings.value,
)

const typeLabel = computed(() => (activeType.value === 'RANKING' ? '榜单' : '分类'))

/** 某个目标被几个任务引用 */
function refCount(item: TaxonomyItem): number {
  return tasks.value.filter(
    (t) => t.targetType === item.type && t.targetId === item.code,
  ).length
}

async function load(): Promise<void> {
  loading.value = true
  try {
    const [cats, ranks, taskList] = await Promise.all([
      listTaxonomy('CATEGORY'),
      listTaxonomy('RANKING'),
      listTasks(),
    ])
    categories.value = cats
    rankings.value = ranks
    tasks.value = taskList
  } catch {
    categories.value = []
    rankings.value = []
    tasks.value = []
  } finally {
    loading.value = false
  }
}

// ---------------------------------------------------------------------------
// 新建 / 编辑
// ---------------------------------------------------------------------------

const formVisible = ref(false)
const formMode = ref<'create' | 'edit'>('create')
const editingItem = ref<TaxonomyItem | null>(null)

function openCreate(): void {
  formMode.value = 'create'
  editingItem.value = null
  formVisible.value = true
}

function openEdit(item: TaxonomyItem): void {
  formMode.value = 'edit'
  editingItem.value = item
  formVisible.value = true
}

// ---------------------------------------------------------------------------
// 删除
// ---------------------------------------------------------------------------

async function confirmRemove(item: TaxonomyItem): Promise<void> {
  const refs = refCount(item)
  if (refs > 0) {
    // 前端先拦一道，省得用户点了确认才被后端拒绝。
    // 后端那道校验仍然是权威的（并发下前端数出来的可能过期）。
    ElMessage.warning(
      `${typeLabel.value}「${item.name}」还有 ${refs} 个采集任务在引用它，请先删除这些任务`,
    )
    return
  }

  const cursorHint =
    '如果它只剩游标没有任务，游标会被一起清掉（游标是采集过程的副产品，不是数据资产）。'

  try {
    await ElMessageBox.confirm(
      `确定删除${typeLabel.value}「${item.name}」（code=${item.code}）吗？` +
        cursorHint +
        '注意：已经采到的图书不会被删除。',
      `删除${typeLabel.value}`,
      { type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消' },
    )
  } catch {
    return
  }

  const id = item.id
  if (!id) {
    return
  }
  try {
    const result = await deleteTaxonomy(activeType.value, id)
    ElMessage.success(
      result.cursorsRemoved > 0
        ? `已删除，顺带清理了 ${result.cursorsRemoved} 条游标`
        : '已删除',
    )
    await load()
  } catch {
    // 拦截器已提示（有任务引用时后端返 code=warn）
  }
}

/** 切换启用状态。这是最高频的操作，做成表格里的开关 */
async function toggleEnabled(item: TaxonomyItem, next: boolean): Promise<void> {
  const id = item.id
  if (!id) {
    return
  }
  try {
    await updateTaxonomy(item.type, id, { enabled: next })
    ElMessage.success(next ? `已启用「${item.name}」` : `已停用「${item.name}」`)
    await load()
  } catch {
    // 失败就重载，把开关拨回真实值 —— 否则开关停在用户点的位置，看起来像成功了
    await load()
  }
}

function handleTabChange(): void {
  // 切 Tab 只是换数据源，不用重新请求（两个字典是一起拉的）
}

onMounted(load)
</script>

<template>
  <div class="page">
    <el-card shadow="never" class="table-card">
      <el-tabs v-model="activeType" @tab-change="handleTabChange">
        <el-tab-pane name="CATEGORY">
          <template #label>
            分类
            <el-badge :value="categories.length" type="info" class="tab-badge" />
          </template>
        </el-tab-pane>
        <el-tab-pane name="RANKING">
          <template #label>
            榜单
            <el-badge :value="rankings.length" type="info" class="tab-badge" />
          </template>
        </el-tab-pane>
      </el-tabs>

      <ProTable
        :data="rows"
        :loading="loading"
        :show-pagination="false"
        row-key="id"
        :empty-text="`还没有${typeLabel}。点「新增${typeLabel}」加一条。`"
      >
        <template #toolbar>
          <el-button type="primary" :loading="loading" @click="load">
            <el-icon><Refresh /></el-icon>
            刷新
          </el-button>
          <el-button type="success" @click="openCreate">
            <el-icon><Plus /></el-icon>
            新增{{ typeLabel }}
          </el-button>
          <span class="toolbar-info">
            共 {{ rows.length }} 条 · 启用 {{ rows.filter((r) => r.enabled !== false).length }} 条 ·
            停用的不会出现在新建任务的下拉框里
          </span>
        </template>

        <el-table-column label="code" width="120">
          <template #default="{ row }">
            <span class="text-mono">{{ row.code }}</span>
          </template>
        </el-table-column>

        <el-table-column prop="name" label="名称" min-width="140" show-overflow-tooltip />

        <el-table-column label="启用" width="82" align="center">
          <template #default="{ row }">
            <!-- el-switch 的 update:model-value 类型是 boolean | string | number（它支持
                 active-value / inactive-value 自定义），所以这里必须显式标注参数类型 -->
            <el-switch
              :model-value="row.enabled !== false"
              size="small"
              @update:model-value="(v: string | number | boolean) => toggleEnabled(row, Boolean(v))"
            />
          </template>
        </el-table-column>

        <el-table-column label="排序" width="76" align="right">
          <template #default="{ row }">{{ dash(row.sort) }}</template>
        </el-table-column>

        <el-table-column label="被任务引用" width="106" align="center">
          <template #default="{ row }">
            <el-tag v-if="refCount(row) > 0" size="small" type="warning" effect="plain">
              {{ refCount(row) }} 个任务
            </el-tag>
            <span v-else class="text-muted">无</span>
          </template>
        </el-table-column>

        <el-table-column prop="remark" label="备注" min-width="140" show-overflow-tooltip>
          <template #default="{ row }">{{ dash(row.remark) }}</template>
        </el-table-column>

        <el-table-column label="更新时间" width="150">
          <template #default="{ row }">{{ formatDateTime(row.updatedAt) }}</template>
        </el-table-column>

        <el-table-column label="操作" width="120" fixed="right" align="center">
          <template #default="{ row }">
            <el-button link type="primary" @click="openEdit(row)">编辑</el-button>
            <el-button link type="danger" @click="confirmRemove(row)">删除</el-button>
          </template>
        </el-table-column>
      </ProTable>
    </el-card>

    <TaxonomyFormDialog
      v-model="formVisible"
      :mode="formMode"
      :type="activeType"
      :item="editingItem"
      @saved="load"
    />
  </div>
</template>

<style scoped>
.table-card :deep(.el-card__body) {
  padding: 8px 16px 16px;
}
.table-card :deep(.el-tabs__header) {
  margin-bottom: 12px;
}

.tab-badge {
  margin-left: 6px;
  transform: translateY(-1px);
}

.toolbar-info {
  margin-left: auto;
  font-size: 12px;
  color: #909399;
}
</style>
