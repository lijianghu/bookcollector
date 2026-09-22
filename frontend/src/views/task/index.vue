<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  cancelTask,
  deleteTask,
  listTasks,
  pauseTask,
  resumeTask,
  retryTask,
  startTask,
} from '@/api/task'
import ProTable from '@/components/ProTable.vue'
import TaskFormDialog from './TaskFormDialog.vue'
import TaskDetailDrawer from './TaskDetailDrawer.vue'
import { dash, formatDateTime, formatNumber } from '@/utils/format'
import {
  primaryTaskAction,
  taskActions,
  taskStatusLabel,
  taskStatusTagType,
  type TaskActionKey,
} from '@/utils/taskStatus'
import type { CollectTask } from '@/types'

/**
 * 任务中心（S6.3）。
 *
 * <h3>为什么不翻页</h3>
 * 一个采集目标只能有一个任务（唯一索引），目标总数就是分类 + 榜单的量级（几十个），
 * 所以后端 `/api/tasks` 直接返回全量、不分页。硬加一个分页器只会让人多点一次。
 *
 * <h3>操作按钮为什么分两处</h3>
 * 状态机一共 5 个动作（启动 / 暂停 / 恢复 / 取消 / 重试），但**同一状态下最多只有 2 个可用**。
 * 全塞进表格行会撑到换行（实测 8 个按钮一行放不下），所以：
 * - **表格行**只放「详情」+ 当前最该做的那一个 + 「取消」（活跃状态才有）→ 最多 3 个
 * - **详情抽屉**里把 5 个全画出来，不可用的置灰 + tooltip 说明原因
 *
 * 让用户看到「有哪些能力、为什么现在不能用」，比「按钮凭空消失」好理解得多。
 * 可用性规则集中在 `utils/taskStatus.ts`，与后端 `TaskService` 的前置校验逐条对齐。
 *
 * <h3>⚠️ 每个动作之后必须重载列表</h3>
 * 状态变化完全由后端决定（还可能被 CAS 抢先改变），前端**不做乐观更新** ——
 * 乐观更新在这里会骗人：你以为点了「暂停」就停了，实际后端返回
 * 「任务状态已被其他操作改变，暂停未生效」。
 */

const loading = ref(false)
const tasks = ref<CollectTask[]>([])
const acting = ref('')

/** 统计：运行中 / 待执行 各几个，工具栏一行说清 */
const stats = computed(() => {
  const running = tasks.value.filter((t) => t.status === 'RUNNING').length
  const pending = tasks.value.filter((t) => t.status === 'PENDING').length
  const active = tasks.value.filter((t) => t.status === 'RUNNING' || t.status === 'PAUSED').length
  return { running, pending, active }
})

async function load(): Promise<void> {
  loading.value = true
  try {
    tasks.value = await listTasks()
  } catch {
    tasks.value = []
  } finally {
    loading.value = false
  }
  syncDetailTask()
}

/**
 * 把抽屉里那份 task 换成列表里的新对象。
 *
 * 为什么需要：抽屉的 props 来自 `detailTask`，它指向的是**上一次**列表里的对象。
 * 编辑保存后 `load()` 换掉了整个数组，`detailTask` 还指着旧对象 ——
 * 于是抽屉里显示的还是编辑前的值（备注没变），看起来像「保存没生效」。
 *
 * 顺手处理「任务已经不在列表里了」（被删掉）：关掉抽屉，
 * 免得留一个显示已删数据的抽屉在屏幕上。
 */
function syncDetailTask(): void {
  if (!detailTask.value?.id) {
    return
  }
  const fresh = tasks.value.find((t) => t.id === detailTask.value?.id)
  if (fresh) {
    detailTask.value = fresh
  } else {
    detailVisible.value = false
    detailTask.value = null
  }
}

// ---------------------------------------------------------------------------
// 动作
// ---------------------------------------------------------------------------

const ACTION_SUCCESS_TEXT: Record<TaskActionKey, string> = {
  start: '任务已启动。进度不会自动推 —— 打开详情点「刷新」看。',
  pause: '已请求暂停：当前页跑完会停住，游标已保留。',
  resume: '任务已恢复，从游标继续（未重采）。',
  cancel: '任务已取消。想继续请用「重试」，它会从游标接着采。',
  retry: '任务已重试，从游标续传（不是从头）。',
}

/**
 * 执行一个动作。
 *
 * 取消要先二次确认：它会把当前 run 置为终态，用户如果只是想「歇一下」
 * 应该用暂停（暂停可以原地唤醒、一页都不重采；取消之后再跑就是新的一次 run）。
 */
async function runAction(task: CollectTask, key: TaskActionKey): Promise<void> {
  const action = taskActions(task.status).find((a) => a.key === key)
  if (!action || !action.enabled) {
    return
  }
  if (action.needConfirm) {
    try {
      await ElMessageBox.confirm(
        `确定取消任务「${task.name}」吗？` +
          `取消后当前这次运行会结束（游标保留）。` +
          `如果只是想歇一下，用「暂停」更好 —— 暂停能原地唤醒，一页都不重采。`,
        '取消任务',
        { type: 'warning', confirmButtonText: '取消任务', cancelButtonText: '再想想' },
      )
    } catch {
      return
    }
  }

  const taskId = task.id
  if (!taskId) {
    return
  }
  acting.value = taskId
  try {
    if (key === 'start') {
      await startTask(taskId)
    } else if (key === 'pause') {
      await pauseTask(taskId)
    } else if (key === 'resume') {
      await resumeTask(taskId)
    } else if (key === 'cancel') {
      await cancelTask(taskId)
    } else {
      await retryTask(taskId)
    }
    ElMessage.success(ACTION_SUCCESS_TEXT[key])
    await load()
  } catch {
    // 拦截器已经弹过后端的 message（并发闸门、CAS 失败、假死记录都走这里）
  } finally {
    acting.value = ''
  }
}

// ---------------------------------------------------------------------------
// 新建 / 编辑
// ---------------------------------------------------------------------------

const formVisible = ref(false)
const formMode = ref<'create' | 'edit'>('create')
const editingTask = ref<CollectTask | null>(null)

function openCreate(): void {
  formMode.value = 'create'
  editingTask.value = null
  formVisible.value = true
}

function openEdit(task: CollectTask): void {
  formMode.value = 'edit'
  editingTask.value = task
  formVisible.value = true
}

async function handleSaved(): Promise<void> {
  await load()
}

// ---------------------------------------------------------------------------
// 详情抽屉
// ---------------------------------------------------------------------------

const detailVisible = ref(false)
const detailTask = ref<CollectTask | null>(null)

function openDetail(task: CollectTask): void {
  detailTask.value = task
  detailVisible.value = true
}

/** 抽屉里的动作：执行完重载列表，抽屉靠 `task.status` 变化自动刷新 */
async function handleDrawerAction(key: TaskActionKey): Promise<void> {
  if (!detailTask.value) {
    return
  }
  await runAction(detailTask.value, key)
}

// ---------------------------------------------------------------------------
// 删除
// ---------------------------------------------------------------------------

async function confirmRemove(task: CollectTask): Promise<void> {
  try {
    await ElMessageBox.confirm(
      `确定删除任务「${task.name}」吗？` +
        `它会连带删除这个任务的全部运行记录和日志。` +
        `注意：采集游标不会被删 —— 游标是「目标」的属性。` +
        `所以删掉再重建一个同名任务，会从原来的位置接着采。`,
      '删除任务',
      { type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消' },
    )
  } catch {
    return
  }
  const taskId = task.id
  if (!taskId) {
    return
  }
  acting.value = taskId
  try {
    const result = await deleteTask(taskId)
    ElMessage.success(
      `已删除任务，连带删除 ${result.runsDeleted} 条运行记录、${result.logsDeleted} 条日志`,
    )
    if (detailTask.value?.id === taskId) {
      detailVisible.value = false
      detailTask.value = null
    }
    await load()
  } catch {
    // 拦截器已提示（活跃状态会被后端拒绝）
  } finally {
    acting.value = ''
  }
}

onMounted(load)
</script>

<template>
  <div class="page">
    <el-card shadow="never" class="table-card">
      <ProTable
        :data="tasks"
        :loading="loading"
        :show-pagination="false"
        row-key="id"
        empty-text="还没有采集任务。点「新建任务」挑一个分类或榜单开始。"
      >
        <template #toolbar>
          <el-button type="primary" :loading="loading" @click="load">
            <el-icon><Refresh /></el-icon>
            刷新
          </el-button>
          <el-button type="success" @click="openCreate">
            <el-icon><Plus /></el-icon>
            新建任务
          </el-button>
          <span class="toolbar-info">
            共 {{ tasks.length }} 个任务 · 运行中 {{ stats.running }} · 待执行 {{ stats.pending }} ·
            占着目标 {{ stats.active }} 个
          </span>
        </template>

        <el-table-column prop="name" label="任务名" min-width="180" show-overflow-tooltip>
          <template #default="{ row }">
            <el-link type="primary" :underline="false" @click="openDetail(row)">
              {{ row.name }}
            </el-link>
          </template>
        </el-table-column>

        <el-table-column label="采集目标" min-width="200" show-overflow-tooltip>
          <template #default="{ row }">
            <el-tag size="small" effect="plain" class="type-tag">
              {{ row.targetType === 'RANKING' ? '榜单' : '分类' }}
            </el-tag>
            {{ row.targetName ?? '-' }}
            <span class="text-muted target-id">（{{ row.targetId }}）</span>
          </template>
        </el-table-column>

        <el-table-column label="状态" width="96" align="center">
          <template #default="{ row }">
            <el-tag size="small" :type="taskStatusTagType(row.status)" effect="dark">
              {{ taskStatusLabel(row.status) }}
            </el-tag>
          </template>
        </el-table-column>

        <el-table-column label="已运行" width="84" align="right">
          <template #default="{ row }">{{ formatNumber(row.runCount) }}</template>
        </el-table-column>

        <el-table-column label="最大页数" width="96" align="right">
          <template #default="{ row }">
            {{ row.maxPages && row.maxPages > 0 ? row.maxPages : '不限' }}
          </template>
        </el-table-column>

        <el-table-column prop="remark" label="备注" min-width="140" show-overflow-tooltip>
          <template #default="{ row }">{{ dash(row.remark) }}</template>
        </el-table-column>

        <el-table-column label="创建时间" width="150">
          <template #default="{ row }">{{ formatDateTime(row.createdAt) }}</template>
        </el-table-column>

        <el-table-column label="操作" width="168" fixed="right" align="center">
          <template #default="{ row }">
            <el-button link type="primary" @click="openDetail(row)">详情</el-button>

            <!-- 当前状态下最该做的那一个 -->
            <el-tooltip
              v-if="primaryTaskAction(row.status)"
              :content="primaryTaskAction(row.status)?.label ?? ''"
              placement="top"
            >
              <el-button
                link
                type="primary"
                :loading="acting === row.id"
                @click="runAction(row, primaryTaskAction(row.status)!.key)"
              >
                {{ primaryTaskAction(row.status)!.label }}
              </el-button>
            </el-tooltip>

            <!-- 取消只在活跃状态出现：终态取消没有意义 -->
            <el-button
              v-if="row.status === 'RUNNING' || row.status === 'PAUSED'"
              link
              type="danger"
              :loading="acting === row.id"
              @click="runAction(row, 'cancel')"
            >
              取消
            </el-button>
          </template>
        </el-table-column>
      </ProTable>
    </el-card>

    <TaskFormDialog
      v-model="formVisible"
      :mode="formMode"
      :task="editingTask"
      :existing-tasks="tasks"
      @saved="handleSaved"
    />

    <TaskDetailDrawer
      v-model="detailVisible"
      :task="detailTask"
      @action="handleDrawerAction"
      @edit="detailTask && openEdit(detailTask)"
      @remove="detailTask && confirmRemove(detailTask)"
    />
  </div>
</template>

<style scoped>
.table-card :deep(.el-card__body) {
  padding: 16px;
}

.toolbar-info {
  margin-left: auto;
  font-size: 12px;
  color: #909399;
}

.type-tag {
  margin-right: 4px;
}
.target-id {
  font-size: 12px;
}
</style>
