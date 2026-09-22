<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { getRun, getRunLogs, pageRuns } from '@/api/run'
import { dash, formatDateTime, formatNumber, formatRelative } from '@/utils/format'
import {
  taskActions,
  taskStatusLabel,
  taskStatusTagType,
  type TaskActionKey,
  type TaskActionState,
} from '@/utils/taskStatus'
import type { CollectTask, TaskLog, TaskRun } from '@/types'

/**
 * 任务详情抽屉。
 *
 * <h3>为什么分成三个 Tab</h3>
 * 「这个任务现在怎么样了」「它以前跑成什么样」「刚才到底发生了什么」是三个不同的问题，
 * 竖着堆在一个长滚动页里，用户要滚三屏才能找到日志。分成 Tab 之后每屏只回答一个问题。
 *
 * <h3>为什么进度要手动刷新</h3>
 * 用户明确说了**不做 SSE 推送，手动刷新即可**（第一期功能收敛）。
 * 所以这里给一个显式的「刷新」按钮，并且把「最后更新」用**相对时间**显示 ——
 * 「2 分钟前」能让人一眼看出任务是不是卡住了，而「2026-09-22 15:31:04」看不出来。
 * 另外运行中超过 2 分钟没更新会主动标红提示。
 *
 * <h3>5 个动作按钮为什么全画出来（不可用的置灰）</h3>
 * 按钮凭空消失会让人以为「这个功能没有」；置灰 + tooltip 说明原因，
 * 用户能看到「有哪些能力、为什么现在不能用」。规则集中在
 * `utils/taskStatus.ts`，与后端 `TaskService` 的前置校验逐条对齐。
 *
 * <h3>⚠️ 运行历史用分页接口，不是 /tasks/{id}/runs</h3>
 * 后端只有 `/api/runs?taskId=x` 一个分页接口，没有按任务分列的运行历史接口。
 */

const props = defineProps<{
  modelValue: boolean
  task: CollectTask | null
}>()

const emit = defineEmits<{
  (e: 'update:modelValue', value: boolean): void
  /** 用户点了某个动作。由页面执行（页面负责改数据 + 重载列表） */
  (e: 'action', key: TaskActionKey): void
  (e: 'edit'): void
  (e: 'remove'): void
}>()

const activeTab = ref('overview')

// ---------------------------------------------------------------------------
// 概览：当前运行
// ---------------------------------------------------------------------------

const currentRun = ref<TaskRun | null>(null)
const overviewLoading = ref(false)

const actions = computed<TaskActionState[]>(() => taskActions(props.task?.status))

/** 运行中但超过 2 分钟没有进度更新 → 可能卡住了 */
const STALE_MS = 2 * 60 * 1000
const staleSeconds = computed(() => {
  if (props.task?.status !== 'RUNNING' || !currentRun.value?.lastProgressAt) {
    return 0
  }
  const t = new Date(currentRun.value.lastProgressAt).getTime()
  if (Number.isNaN(t)) {
    return 0
  }
  return Math.floor((Date.now() - t) / 1000)
})
const isStale = computed(() => staleSeconds.value * 1000 > STALE_MS)

/** 有 maxPages 时才画进度条 —— 不限制页数时「进度」没有分母，画了是假的 */
const progressPercent = computed(() => {
  const max = props.task?.maxPages
  const done = currentRun.value?.pagesDone
  if (!max || max <= 0 || done === null || done === undefined) {
    return null
  }
  return Math.min(100, Math.round((done / max) * 100))
})

/**
 * 不画进度条时的说明文案。
 *
 * ⚠️ 要分三种情况，不能一句话打发了 —— 第一版只有「没有限制页数」一句，
 * 结果一个限了 1 页、但还没跑过的任务也显示「任务没有限制页数」，纯属胡说。
 */
const noProgressReason = computed(() => {
  if (!props.task?.maxPages || props.task.maxPages <= 0) {
    return '任务没有限制页数（一直翻到接口说没有下一页），所以没有百分比可算。'
  }
  if (!currentRun.value) {
    return `任务限了 ${props.task.maxPages} 页，但还没有运行过 —— 点上面的「启动」开始。`
  }
  return `任务限了 ${props.task.maxPages} 页，但这次运行还没记录到页数。`
})

const progressStatus = computed<'success' | 'exception' | 'warning' | undefined>(() => {
  const status = props.task?.status
  if (status === 'FAILED') {
    return 'exception'
  }
  if (status === 'PAUSED' || status === 'INTERRUPTED') {
    return 'warning'
  }
  if (status === 'SUCCESS') {
    return 'success'
  }
  return undefined
})

async function loadOverview(): Promise<void> {
  const runId = props.task?.lastRunId
  if (!runId) {
    currentRun.value = null
    return
  }
  try {
    currentRun.value = await getRun(runId)
  } catch {
    // run 可能被删了（任务删除时级联删），拿不到就不显示进度
    currentRun.value = null
  }
}

// ---------------------------------------------------------------------------
// 运行历史
// ---------------------------------------------------------------------------

const runs = ref<TaskRun[]>([])
const runTotal = ref(0)
const runPage = ref(1)
const runSize = ref(5)
const runsLoading = ref(false)

async function loadRuns(): Promise<void> {
  if (!props.task?.id) {
    runs.value = []
    runTotal.value = 0
    return
  }
  runsLoading.value = true
  try {
    const result = await pageRuns({
      taskId: props.task.id,
      page: runPage.value,
      size: runSize.value,
    })
    runs.value = result.list
    runTotal.value = result.total
  } catch {
    runs.value = []
    runTotal.value = 0
  } finally {
    runsLoading.value = false
  }
}

function handleRunPageChange(next: number): void {
  runPage.value = next
  void loadRuns()
}

// ---------------------------------------------------------------------------
// 日志
// ---------------------------------------------------------------------------

const logs = ref<TaskLog[]>([])
const logsRunId = ref<string>('')
const logsLoading = ref(false)

/**
 * 加载某次运行的日志。
 *
 * ⚠️ 后端会**先校验 run 存在**再查日志：runId 写错返 404 而不是空数组 ——
 * 否则前端分不清「这次运行确实没日志」和「你查的 run 根本不存在」。
 */
async function loadLogs(runId: string): Promise<void> {
  logsRunId.value = runId
  if (!runId) {
    logs.value = []
    return
  }
  logsLoading.value = true
  try {
    logs.value = await getRunLogs(runId, 500)
  } catch {
    logs.value = []
  } finally {
    logsLoading.value = false
  }
}

/** 从运行历史跳到日志 Tab */
function viewLogsOf(run: TaskRun): void {
  activeTab.value = 'logs'
  void loadLogs(run.id ?? '')
}

/** 日志级别 → el-tag 的 type */
function levelTagType(level?: string | null): 'info' | 'warning' | 'danger' {
  const upper = (level ?? '').toUpperCase()
  if (upper === 'ERROR') {
    return 'danger'
  }
  if (upper === 'WARN' || upper === 'WARNING') {
    return 'warning'
  }
  return 'info'
}

// ---------------------------------------------------------------------------

async function load(): Promise<void> {
  if (!props.task) {
    return
  }
  overviewLoading.value = true
  runPage.value = 1
  try {
    await Promise.all([loadOverview(), loadRuns()])
  } finally {
    overviewLoading.value = false
  }
  // 默认把最近一次运行的日志也拉好，省得用户切过去还要点一下
  const lastRunId = props.task.lastRunId
  if (lastRunId) {
    void loadLogs(lastRunId)
  } else if (runs.value.length > 0 && runs.value[0].id) {
    void loadLogs(runs.value[0].id)
  } else {
    logs.value = []
    logsRunId.value = ''
  }
}

/**
 * 打开时、或任务本身变了（id / 状态 / lastRunId）就重载。
 *
 * 为什么盯 `status` 和 `lastRunId`：页面执行完动作后会重载任务列表，
 * 于是传进来的 `task` 对象是新的 —— 状态一变这里就自动跟着刷新，
 * 不用页面再去调抽屉的内部方法（少一层耦合）。
 */
watch(
  () => [props.modelValue, props.task?.id, props.task?.status, props.task?.lastRunId] as const,
  ([visible]) => {
    if (!visible) {
      return
    }
    activeTab.value = 'overview'
    void load()
  },
  { immediate: true },
)

function handleClose(): void {
  emit('update:modelValue', false)
}

function trigger(action: TaskActionState): void {
  if (!action.enabled) {
    return
  }
  emit('action', action.key)
}
</script>

<template>
  <el-drawer
    :model-value="modelValue"
    :size="840"
    direction="rtl"
    destroy-on-close
    @update:model-value="handleClose"
  >
    <template #header>
      <div class="drawer-header">
        <span class="drawer-title">{{ task?.name ?? '任务详情' }}</span>
        <span class="drawer-subtitle">
          <el-tag size="small" :type="taskStatusTagType(task?.status)" effect="dark">
            {{ taskStatusLabel(task?.status) }}
          </el-tag>
          <span class="target">
            {{ task?.targetType === 'RANKING' ? '榜单' : '分类' }} ·
            {{ task?.targetName ?? task?.targetId }}（{{ task?.targetId }}）
          </span>
        </span>
      </div>
    </template>

    <el-tabs v-model="activeTab" class="tabs">
      <!-- ==================== 概览 ==================== -->
      <el-tab-pane label="概览" name="overview">
        <div class="tab-toolbar">
          <el-button :loading="overviewLoading" @click="load">
            <el-icon><Refresh /></el-icon>
            刷新
          </el-button>
          <span class="hint">本期不做实时推送，进度靠手动刷新</span>
        </div>

        <el-alert v-if="isStale" type="error" :closable="false" class="stale">
          <template #title>
            运行中，但已经 {{ Math.floor(staleSeconds / 60) }} 分钟没有进度更新了 ——
            可能卡住了，或者上次进程留下的假死状态。建议点「取消」清理后重试。
          </template>
        </el-alert>

        <!-- 进度 -->
        <el-card shadow="never" class="block">
          <template #header>
            <span class="block-title">当前进度</span>
            <span class="block-sub">
              最后一次更新：{{ formatRelative(currentRun?.lastProgressAt) }}
            </span>
          </template>

          <el-progress
            v-if="progressPercent !== null"
            :percentage="progressPercent"
            :status="progressStatus"
            :stroke-width="14"
            class="progress"
          />
          <div v-else class="no-limit">{{ noProgressReason }}</div>

          <el-descriptions :column="3" border size="small" class="desc">
            <el-descriptions-item label="已采页数">
              {{ dash(currentRun?.pagesDone) }}
            </el-descriptions-item>
            <el-descriptions-item label="入库图书">
              {{ formatNumber(currentRun?.booksSaved) }}
            </el-descriptions-item>
            <el-descriptions-item label="当前游标">
              {{ dash(currentRun?.currentCursor) }}
            </el-descriptions-item>
            <el-descriptions-item label="目标总数">
              {{ formatNumber(currentRun?.totalCount) }}
            </el-descriptions-item>
            <el-descriptions-item label="最大页数">
              {{ task?.maxPages && task.maxPages > 0 ? task.maxPages : '不限' }}
            </el-descriptions-item>
            <el-descriptions-item label="历史运行次数">
              {{ dash(task?.runCount) }}
            </el-descriptions-item>
            <el-descriptions-item label="本次开始">
              {{ formatDateTime(currentRun?.startedAt) }}
            </el-descriptions-item>
            <el-descriptions-item label="本次结束">
              {{ formatDateTime(currentRun?.finishedAt) }}
            </el-descriptions-item>
            <el-descriptions-item label="本次状态">
              {{ taskStatusLabel(currentRun?.status) }}
            </el-descriptions-item>
          </el-descriptions>

          <el-alert
            v-if="currentRun?.errorMsg"
            type="error"
            :closable="false"
            class="error-msg"
          >
            <template #title>{{ currentRun.errorMsg }}</template>
          </el-alert>
        </el-card>

        <!-- 动作 -->
        <el-card shadow="never" class="block">
          <template #header>
            <span class="block-title">可用操作</span>
            <span class="block-sub">置灰 = 当前状态不支持（鼠标移上去看原因）</span>
          </template>
          <div class="actions">
            <el-tooltip
              v-for="a in actions"
              :key="a.key"
              :content="a.reason"
              :disabled="a.enabled || !a.reason"
              placement="top"
            >
              <span class="action-wrap">
                <el-button
                  :type="a.buttonType"
                  :disabled="!a.enabled"
                  size="small"
                  @click="trigger(a)"
                >
                  {{ a.label }}
                </el-button>
              </span>
            </el-tooltip>
          </div>
          <div class="actions-note">
            状态机：待执行 → 运行中 ⇄ 已暂停 → 已完成 / 失败 / 已取消。
            「启动」「恢复」「重试」都是从游标续传，不是从头重采 ——
            想从头采要去「采集游标」页重置游标。
          </div>
        </el-card>

        <!-- 任务信息 -->
        <el-card shadow="never" class="block">
          <template #header>
            <span class="block-title">任务信息</span>
          </template>
          <el-descriptions :column="2" border size="small">
            <el-descriptions-item label="任务 ID">
              <span class="text-mono">{{ dash(task?.id) }}</span>
            </el-descriptions-item>
            <el-descriptions-item label="目标类型">
              {{ task?.targetType === 'RANKING' ? '榜单' : '分类' }}
            </el-descriptions-item>
            <el-descriptions-item label="目标 ID">
              <span class="text-mono">{{ dash(task?.targetId) }}</span>
            </el-descriptions-item>
            <el-descriptions-item label="目标名称">
              {{ dash(task?.targetName) }}
            </el-descriptions-item>
            <el-descriptions-item label="创建时间">
              {{ formatDateTime(task?.createdAt) }}
            </el-descriptions-item>
            <el-descriptions-item label="更新时间">
              {{ formatDateTime(task?.updatedAt) }}
            </el-descriptions-item>
            <el-descriptions-item label="最近运行 ID" :span="2">
              <span class="text-mono">{{ dash(task?.lastRunId) }}</span>
            </el-descriptions-item>
            <el-descriptions-item label="备注" :span="2">
              {{ dash(task?.remark) }}
            </el-descriptions-item>
          </el-descriptions>
        </el-card>
      </el-tab-pane>

      <!-- ==================== 运行历史 ==================== -->
      <el-tab-pane name="runs">
        <template #label>
          运行历史
          <el-badge v-if="runTotal > 0" :value="runTotal" type="info" class="tab-badge" />
        </template>

        <div class="tab-toolbar">
          <el-button :loading="runsLoading" @click="loadRuns">
            <el-icon><Refresh /></el-icon>
            刷新
          </el-button>
          <span class="hint">共 {{ runTotal }} 次运行</span>
        </div>

        <el-table v-loading="runsLoading" :data="runs" border stripe size="small">
          <el-table-column label="开始时间" width="150">
            <template #default="{ row }">{{ formatDateTime(row.startedAt) }}</template>
          </el-table-column>
          <el-table-column label="状态" width="86" align="center">
            <template #default="{ row }">
              <el-tag size="small" :type="taskStatusTagType(row.status)" effect="plain">
                {{ taskStatusLabel(row.status) }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column label="页数" width="66" align="right">
            <template #default="{ row }">{{ dash(row.pagesDone) }}</template>
          </el-table-column>
          <el-table-column label="入库" width="72" align="right">
            <template #default="{ row }">{{ formatNumber(row.booksSaved) }}</template>
          </el-table-column>
          <el-table-column label="游标" width="72" align="right">
            <template #default="{ row }">{{ dash(row.currentCursor) }}</template>
          </el-table-column>
          <el-table-column label="耗时" width="120">
            <template #default="{ row }">
              {{ row.finishedAt ? formatRelative(row.finishedAt) : '未结束' }}
            </template>
          </el-table-column>
          <el-table-column label="操作" width="86" align="center">
            <template #default="{ row }">
              <el-button link type="primary" @click="viewLogsOf(row)">看日志</el-button>
            </template>
          </el-table-column>
          <template #empty>
            <span class="text-muted">这个任务还没跑过</span>
          </template>
        </el-table>

        <div class="runs-footer">
          <el-pagination
            :current-page="runPage"
            :page-size="runSize"
            :total="runTotal"
            layout="total, prev, pager, next"
            background
            small
            @current-change="handleRunPageChange"
          />
        </div>
      </el-tab-pane>

      <!-- ==================== 日志 ==================== -->
      <el-tab-pane name="logs">
        <template #label>
          日志
          <el-badge v-if="logs.length > 0" :value="logs.length" type="info" class="tab-badge" />
        </template>

        <div class="tab-toolbar">
          <el-button :loading="logsLoading" :disabled="!logsRunId" @click="loadLogs(logsRunId)">
            <el-icon><Refresh /></el-icon>
            刷新
          </el-button>
          <el-select
            :model-value="logsRunId"
            placeholder="选择一次运行"
            class="run-picker"
            @update:model-value="loadLogs"
          >
            <el-option
              v-for="run in runs"
              :key="run.id ?? ''"
              :label="`${formatDateTime(run.startedAt)} · ${taskStatusLabel(run.status)}`"
              :value="run.id ?? ''"
            />
          </el-select>
        </div>

        <el-skeleton v-if="logsLoading" :rows="6" animated />

        <div v-else-if="logs.length === 0" class="empty-logs">
          <el-icon class="empty-icon"><Document /></el-icon>
          <span>{{ logsRunId ? '这次运行没有日志' : '还没有选中的运行' }}</span>
        </div>

        <div v-else class="log-list">
          <div v-for="log in logs" :key="log.id ?? log.createdAt + (log.message ?? '')" class="log-item">
            <el-tag size="small" :type="levelTagType(log.level)" effect="plain" class="log-level">
              {{ (log.level ?? 'INFO').toUpperCase() }}
            </el-tag>
            <span class="log-time">{{ formatDateTime(log.createdAt) }}</span>
            <span class="log-message">{{ log.message }}</span>
          </div>
        </div>
      </el-tab-pane>
    </el-tabs>

    <template #footer>
      <el-button :disabled="task?.status === 'RUNNING' || task?.status === 'PAUSED'" @click="emit('edit')">
        <el-icon><Edit /></el-icon>
        编辑
      </el-button>
      <el-button
        type="danger"
        :disabled="task?.status === 'RUNNING' || task?.status === 'PAUSED'"
        @click="emit('remove')"
      >
        <el-icon><Delete /></el-icon>
        删除
      </el-button>
    </template>
  </el-drawer>
</template>

<style scoped>
.drawer-header {
  display: flex;
  flex-direction: column;
  gap: 4px;
  min-width: 0;
}
.drawer-title {
  font-size: 15px;
  font-weight: 600;
  color: #303133;
}
.drawer-subtitle {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 12px;
  color: #909399;
}
.target {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.tabs :deep(.el-tabs__header) {
  margin-bottom: 12px;
}
.tab-badge {
  margin-left: 6px;
  transform: translateY(-1px);
}

.tab-toolbar {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 12px;
}
.hint {
  font-size: 12px;
  color: #909399;
}
.run-picker {
  width: 260px;
  margin-left: auto;
}

.stale {
  margin-bottom: 12px;
}

.block {
  margin-bottom: 12px;
}
.block :deep(.el-card__header) {
  padding: 10px 16px;
}
.block-title {
  font-size: 13px;
  font-weight: 600;
  color: #303133;
}
.block-sub {
  margin-left: 10px;
  font-size: 12px;
  color: #909399;
}

.progress {
  margin-bottom: 14px;
}
.no-limit {
  margin-bottom: 14px;
  font-size: 12px;
  color: #909399;
}
.desc :deep(.el-descriptions__label) {
  width: 96px;
  color: #606266;
}
.error-msg {
  margin-top: 12px;
}

.actions {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}
/* tooltip 要能包住 disabled 的按钮 —— disabled 的 button 不派发鼠标事件，
   所以外面套一层 span 作为 tooltip 的引用元素 */
.action-wrap {
  display: inline-flex;
}
.actions-note {
  margin-top: 10px;
  font-size: 12px;
  line-height: 1.7;
  color: #909399;
}

.runs-footer {
  display: flex;
  justify-content: flex-end;
  margin-top: 12px;
}

.empty-logs {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 8px;
  padding: 40px 0;
  color: #a8abb2;
  font-size: 13px;
}
.empty-icon {
  font-size: 26px;
  color: #dcdfe6;
}

.log-list {
  max-height: calc(100vh - 280px);
  overflow-y: auto;
  border: 1px solid #ebeef5;
  border-radius: 4px;
}
.log-item {
  display: flex;
  align-items: flex-start;
  gap: 8px;
  padding: 6px 10px;
  font-size: 12px;
  line-height: 1.7;
  border-bottom: 1px solid #f5f7fa;
}
.log-item:last-child {
  border-bottom: none;
}
.log-level {
  flex-shrink: 0;
}
.log-time {
  flex-shrink: 0;
  color: #a8abb2;
  font-family: Consolas, Monaco, 'Courier New', monospace;
}
.log-message {
  color: #303133;
  word-break: break-all;
}
</style>
