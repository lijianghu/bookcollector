<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { pageRequests } from '@/api/audit'
import ProTable from '@/components/ProTable.vue'
import SearchForm from '@/components/SearchForm.vue'
import { useColumnPrefs, type ColumnPref } from '@/composables/useColumnPrefs'
import { dash, formatDateTime, formatNumber } from '@/utils/format'
import type { ApiRequest } from '@/types'

/**
 * 请求审计（S6.6）。
 *
 * <h3>这一页解决什么问题</h3>
 * 「为什么这批数据不对？」是采集类系统最难查的问题。这一层记录**每一次对
 * 微信读书接口的 HTTP 请求**（`ApiRequestRepository` 在采集过程中写入），
 * 所以能看到每一页的 `statusCode` / `responseTimeMs` / `resultCount` / `hasMore` ——
 * 一眼就能分辨「接口返了 0 条」和「接口返了 20 条但我们解析错了」。
 *
 * <h3>⚠️ 它不能当账本用</h3>
 * 写入侧的约束是「只插不改、失败不抛异常」—— 审计写失败绝不能影响采集。
 * 所以数据可能有缺口（某次请求没记上）。查询侧相反，条件写错就该报错。
 *
 * <h3>⚠️ 只读</h3>
 * 后端不提供删除接口，前端也刻意不做「清空日志」按钮 ——
 * 一个能被随手清空的审计日志等于没有审计。
 *
 * <h3>失败为什么要整行高亮</h3>
 * 排查时用户是「扫一眼找红色」，不是逐行读 `statusCode`。
 * 所以失败行（`statusCode >= 400` 或有 `errorMsg`）整行染红底，
 * 并且把「只看失败」做成筛选栏里的一个开关。
 */

// ---------------------------------------------------------------------------
// 列配置
// ---------------------------------------------------------------------------

const COLUMNS: ColumnPref[] = [
  { key: 'createdAt', label: '请求时间', fixed: true },
  { key: 'target', label: '采集目标', fixed: true },
  { key: 'pageIndex', label: '第几页' },
  { key: 'statusCode', label: '状态码' },
  { key: 'responseTimeMs', label: '耗时(ms)' },
  { key: 'resultCount', label: '本页条数' },
  { key: 'totalCount', label: '目标总数' },
  { key: 'hasMore', label: '还有下一页' },
  // 默认不显示：这两列最长，但只在出问题时才看
  { key: 'errorMsg', label: '错误信息', defaultVisible: false },
  { key: 'url', label: '请求 URL', defaultVisible: false },
]

const cols = useColumnPrefs('requests', COLUMNS)
const columnPanelVisible = ref(false)

// ---------------------------------------------------------------------------
// 查询
// ---------------------------------------------------------------------------

const loading = ref(false)
const rows = ref<ApiRequest[]>([])
const total = ref(0)
const page = ref(1)
const size = ref(20)

/** 4 个查询条件，与后端 `ApiRequestQuery` 一一对应 */
const query = reactive<{
  runId?: string
  targetType: '' | 'CATEGORY' | 'RANKING'
  targetId?: string
  onlyFailed?: boolean
}>({
  targetType: '',
})

const onlyFailed = computed({
  get: () => query.onlyFailed === true,
  set: (value: boolean) => {
    query.onlyFailed = value ? true : undefined
  },
})

let correcting = false

async function load(): Promise<void> {
  loading.value = true
  try {
    const result = await pageRequests({
      runId: query.runId,
      targetType: query.targetType,
      targetId: query.targetId,
      onlyFailed: query.onlyFailed,
      page: page.value,
      size: size.value,
    })
    rows.value = result.list
    total.value = result.total

    // 页码越界纠正（和第 S6.2 同样的处理）：换筛选条件后页码可能超出范围
    const lastPage = Math.max(1, result.totalPages || 1)
    if (!correcting && result.list.length === 0 && page.value > lastPage) {
      correcting = true
      page.value = lastPage
      await load()
      correcting = false
    }
  } catch {
    rows.value = []
    total.value = 0
  } finally {
    loading.value = false
  }
}

let reloadScheduled = false
function scheduleReload(): void {
  if (reloadScheduled) {
    return
  }
  reloadScheduled = true
  void Promise.resolve().then(() => {
    reloadScheduled = false
    void load()
  })
}

function handleSearch(): void {
  page.value = 1
  scheduleReload()
}

function handleReset(): void {
  query.runId = undefined
  query.targetType = ''
  query.targetId = undefined
  query.onlyFailed = undefined
  page.value = 1
  scheduleReload()
}

function handlePageChange(next: number): void {
  page.value = next
  scheduleReload()
}

function handleSizeChange(next: number): void {
  size.value = next
  page.value = 1
  scheduleReload()
}

// ---------------------------------------------------------------------------
// 展示
// ---------------------------------------------------------------------------

/** 失败行：HTTP 状态码 >= 400，或者有 errorMsg（超时、连接失败这类没有状态码） */
function isFailed(row: ApiRequest): boolean {
  if (typeof row.statusCode === 'number' && row.statusCode >= 400) {
    return true
  }
  return Boolean(row.errorMsg)
}

/** 失败行整行染色 —— 排查时是「扫一眼找红色」，不是逐行读状态码 */
function rowClassName({ row }: { row: ApiRequest }): string {
  return isFailed(row) ? 'row-failed' : ''
}

function statusTagType(row: ApiRequest): 'success' | 'danger' | 'info' {
  if (isFailed(row)) {
    return 'danger'
  }
  return typeof row.statusCode === 'number' ? 'success' : 'info'
}

function statusText(row: ApiRequest): string {
  if (typeof row.statusCode === 'number') {
    return String(row.statusCode)
  }
  return row.errorMsg ? '无响应' : '-'
}

/** 耗时染色：超过 3 秒标橙，超过 10 秒标红 */
function timeClass(ms?: number | null): string {
  if (typeof ms !== 'number') {
    return ''
  }
  if (ms >= 10000) {
    return 'time-slow'
  }
  if (ms >= 3000) {
    return 'time-warn'
  }
  return ''
}

/** 目标展示：`CATEGORY / 300000` */
function targetText(row: ApiRequest): string {
  const type = row.targetType === 'RANKING' ? '榜单' : '分类'
  return `${type} · ${row.targetId ?? '-'}`
}

const failedCount = computed(() => rows.value.filter(isFailed).length)

onMounted(load)
</script>

<template>
  <div class="page">
    <SearchForm
      :model="query"
      :loading="loading"
      :columns="4"
      label-width="92px"
      @search="handleSearch"
      @reset="handleReset"
    >
      <el-form-item label="运行 ID">
        <el-input v-model="query.runId" clearable placeholder="某次运行的 runId" />
      </el-form-item>

      <el-form-item label="目标类型">
        <el-select v-model="query.targetType" clearable placeholder="全部">
          <el-option label="分类" value="CATEGORY" />
          <el-option label="榜单" value="RANKING" />
        </el-select>
      </el-form-item>

      <el-form-item label="目标 ID">
        <el-input v-model="query.targetId" clearable placeholder="如 300000 / rising" />
      </el-form-item>

      <el-form-item label="只看失败">
        <el-switch v-model="onlyFailed" />
      </el-form-item>

      <div class="query-hint">
        这一层记录每一次对微信读书接口的 HTTP 请求，是排查「为什么这批数据不对」的唯一抓手。
        注意：审计写入是「只插不改、失败不抛异常」，所以数据可能有缺口，不能当账本用。
        这一页只读，不提供清空 —— 能被随手清空的审计日志等于没有审计。
      </div>
    </SearchForm>

    <el-card shadow="never" class="table-card">
      <ProTable
        :data="rows"
        :loading="loading"
        :total="total"
        :page="page"
        :size="size"
        :page-sizes="[20, 50, 100]"
        row-key="id"
        :row-class-name="rowClassName"
        max-height="calc(100vh - 470px)"
        empty-text="没有符合条件的请求记录"
        @update:page="handlePageChange"
        @update:size="handleSizeChange"
      >
        <template #toolbar>
          <el-button type="primary" :loading="loading" @click="load">
            <el-icon><Refresh /></el-icon>
            刷新
          </el-button>

          <el-popover v-model:visible="columnPanelVisible" placement="bottom-start" :width="230" trigger="click">
            <template #reference>
              <el-button>
                <el-icon><Setting /></el-icon>
                列配置
              </el-button>
            </template>
            <div class="col-panel">
              <el-checkbox-group v-model="cols.visible.value">
                <el-checkbox
                  v-for="col in cols.columns"
                  :key="col.key"
                  :value="col.key"
                  :disabled="col.fixed"
                  class="col-item"
                >
                  {{ col.label }}
                  <span v-if="col.fixed" class="col-fixed">必显</span>
                </el-checkbox>
              </el-checkbox-group>
              <el-button link type="primary" class="col-reset" @click="cols.reset()">
                恢复默认
              </el-button>
            </div>
          </el-popover>

          <span class="toolbar-info">
            共 {{ formatNumber(total) }} 条 · 本页失败
            <span :class="{ 'has-failed': failedCount > 0 }">{{ failedCount }}</span> 条
          </span>
        </template>

        <el-table-column
          v-if="cols.isVisible('createdAt')"
          label="请求时间"
          width="150"
        >
          <template #default="{ row }">{{ formatDateTime(row.createdAt) }}</template>
        </el-table-column>

        <el-table-column v-if="cols.isVisible('target')" label="采集目标" min-width="150">
          <template #default="{ row }">{{ targetText(row) }}</template>
        </el-table-column>

        <el-table-column
          v-if="cols.isVisible('pageIndex')"
          label="第几页"
          width="80"
          align="right"
        >
          <template #default="{ row }">
            <span class="num">{{ dash(row.pageIndex) }}</span>
          </template>
        </el-table-column>

        <el-table-column
          v-if="cols.isVisible('statusCode')"
          label="状态码"
          width="86"
          align="center"
        >
          <template #default="{ row }">
            <el-tag size="small" :type="statusTagType(row)" effect="plain">
              {{ statusText(row) }}
            </el-tag>
          </template>
        </el-table-column>

        <el-table-column
          v-if="cols.isVisible('responseTimeMs')"
          label="耗时(ms)"
          width="94"
          align="right"
        >
          <template #default="{ row }">
            <span class="num" :class="timeClass(row.responseTimeMs)">
              {{ formatNumber(row.responseTimeMs) }}
            </span>
          </template>
        </el-table-column>

        <el-table-column
          v-if="cols.isVisible('resultCount')"
          label="本页条数"
          width="90"
          align="right"
        >
          <template #default="{ row }">
            <span class="num">{{ dash(row.resultCount) }}</span>
          </template>
        </el-table-column>

        <el-table-column
          v-if="cols.isVisible('totalCount')"
          label="目标总数"
          width="90"
          align="right"
        >
          <template #default="{ row }">
            <span class="num">{{ formatNumber(row.totalCount) }}</span>
          </template>
        </el-table-column>

        <el-table-column
          v-if="cols.isVisible('hasMore')"
          label="还有下一页"
          width="106"
          align="center"
        >
          <template #default="{ row }">
            <span v-if="row.hasMore === null || row.hasMore === undefined" class="text-muted">-</span>
            <el-tag v-else size="small" :type="row.hasMore ? 'primary' : 'info'" effect="plain">
              {{ row.hasMore ? '有' : '没有' }}
            </el-tag>
          </template>
        </el-table-column>

        <el-table-column
          v-if="cols.isVisible('errorMsg')"
          label="错误信息"
          min-width="180"
          show-overflow-tooltip
        >
          <template #default="{ row }">
            <span v-if="row.errorMsg" class="error-text">{{ row.errorMsg }}</span>
            <span v-else class="text-muted">-</span>
          </template>
        </el-table-column>

        <el-table-column
          v-if="cols.isVisible('url')"
          label="请求 URL"
          min-width="220"
          show-overflow-tooltip
        >
          <template #default="{ row }">
            <span class="text-mono">{{ dash(row.url) }}</span>
          </template>
        </el-table-column>
      </ProTable>
    </el-card>
  </div>
</template>

<style scoped>
.page {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.table-card :deep(.el-card__body) {
  padding: 16px;
}

.query-hint {
  grid-column: 1 / -1;
  margin: -8px 0 8px;
  font-size: 12px;
  line-height: 1.6;
  color: #909399;
}

.toolbar-info {
  margin-left: auto;
  font-size: 12px;
  color: #909399;
}
.has-failed {
  font-weight: 600;
  color: #f56c6c;
}

.num {
  font-variant-numeric: tabular-nums;
}
.time-warn {
  color: #e6a23c;
}
.time-slow {
  font-weight: 600;
  color: #f56c6c;
}
.error-text {
  color: #f56c6c;
}

.col-panel {
  display: flex;
  flex-direction: column;
  gap: 4px;
}
.col-item {
  margin-right: 0;
  height: 26px;
}
.col-fixed {
  margin-left: 6px;
  font-size: 11px;
  color: #c0c4cc;
}
.col-reset {
  align-self: flex-start;
  margin-top: 4px;
}
</style>

<style>
/* 失败行整行染色。⚠️ 必须是非 scoped 的：el-table 的行元素不在组件作用域内，
   scoped 的 data-v 属性加不到它们身上（用 :deep 也要能找到选择器宿主） */
.el-table .row-failed td.el-table__cell {
  background-color: #fef0f0;
}
.el-table .row-failed:hover > td.el-table__cell {
  background-color: #fde2e2 !important;
}
</style>
