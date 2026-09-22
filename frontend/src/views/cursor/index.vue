<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { deleteCursor, listCursors, setCursor } from '@/api/cursor'
import ProTable from '@/components/ProTable.vue'
import { dash, formatDateTime, formatNumber } from '@/utils/format'
import type { CollectCursor } from '@/types'

/**
 * 采集游标（S6.5）。
 *
 * <h3>游标是什么（页面上必须讲清楚，否则用户不敢动它）</h3>
 * `maxIndex` = 「下次从第几页开始」。它是**断点续传的唯一权威依据** ——
 * `TaskRun.currentCursor` 只是给界面看的进度快照，改它没有任何效果。
 *
 * 游标按**目标**存（不是按任务）：一个 target 一个游标。
 * 所以「删了任务」不等于「游标没了」，反过来也一样。
 *
 * <h3>「重置」和「指定起点」是同一个接口</h3>
 * `PUT /api/cursors` 传 `maxIndex = 0` 就是重置（后端顺带把 `totalCollected`
 * 清零、`finished` 置 false）。所以页面上是两个按钮、一个接口。
 *
 * <h3>🔴 重置不会删书 —— 这是它安全的原因</h3>
 * 重置只让下一次采集从第 0 页开始。已经入库的书会被 **upsert 覆盖**
 * （`firstCollectedAt` 不变、`lastCollectedAt` 刷新），不会重复插入 ——
 * 这是幂等写入带来的好处。页面上必须写这句，否则用户会以为「重置 = 清空数据」。
 */

const loading = ref(false)
const rows = ref<CollectCursor[]>([])
const acting = ref('')

const stats = computed(() => {
  const finished = rows.value.filter((c) => c.finished === true).length
  const done = rows.value.filter((c) => (c.maxIndex ?? 0) > 0).length
  return { finished, done, fresh: rows.value.length - done }
})

async function load(): Promise<void> {
  loading.value = true
  try {
    rows.value = await listCursors()
  } catch {
    rows.value = []
  } finally {
    loading.value = false
  }
}

/** 采完了没有：`finished=true` 是权威；否则用 maxIndex 是否 > 0 粗略判断「采过」 */
function progressText(row: CollectCursor): string {
  if (row.finished === true) {
    return '已采完'
  }
  return (row.maxIndex ?? 0) > 0 ? '采集中' : '未开始'
}

function progressTagType(row: CollectCursor): 'success' | 'primary' | 'info' {
  if (row.finished === true) {
    return 'success'
  }
  return (row.maxIndex ?? 0) > 0 ? 'primary' : 'info'
}

// ---------------------------------------------------------------------------
// 指定起点 / 重置
// ---------------------------------------------------------------------------

const dialogVisible = ref(false)
const dialogMode = ref<'set' | 'reset'>('set')
const dialogRow = ref<CollectCursor | null>(null)
const dialogForm = reactive<{ maxIndex?: number }>({ maxIndex: undefined })
const dialogSaving = ref(false)

function openSet(row: CollectCursor): void {
  dialogMode.value = 'set'
  dialogRow.value = row
  dialogForm.maxIndex = row.maxIndex ?? 0
  dialogVisible.value = true
}

async function confirmReset(row: CollectCursor): Promise<void> {
  try {
    await ElMessageBox.confirm(
      `确定把「${row.targetName ?? row.targetId}」的游标重置为 0 吗？` +
        `下一次采集会从第 0 页重新开始。` +
        `注意：不会删掉已经采到的图书 —— 重新采到的书会覆盖更新，不会重复插入。`,
      '重置游标',
      { type: 'warning', confirmButtonText: '重置为 0', cancelButtonText: '取消' },
    )
  } catch {
    return
  }
  await applyCursor(row, 0, '已重置为 0，下次采集会从第 0 页开始')
}

async function submitSet(): Promise<void> {
  if (!dialogRow.value) {
    return
  }
  const maxIndex = dialogForm.maxIndex ?? 0
  dialogSaving.value = true
  try {
    const ok = await applyCursor(
      dialogRow.value,
      maxIndex,
      `游标已设为 ${maxIndex}，下次采集会从第 ${maxIndex} 页开始`,
    )
    if (ok) {
      dialogVisible.value = false
    }
  } finally {
    dialogSaving.value = false
  }
}

/** 真正调接口。返回是否成功 */
async function applyCursor(
  row: CollectCursor,
  maxIndex: number,
  successText: string,
): Promise<boolean> {
  const key = `${row.targetType}:${row.targetId}`
  acting.value = key
  try {
    const result = await setCursor({
      targetType: row.targetType,
      targetId: row.targetId,
      targetName: row.targetName ?? undefined,
      maxIndex,
    })
    // 后端会回一句 note，说明它是按「重置」还是「指定起点」处理的 —— 直接展示
    ElMessage.success(result.note ? `${successText}（${result.note}）` : successText)
    await load()
    return true
  } catch {
    // 拦截器已提示
    return false
  } finally {
    acting.value = ''
  }
}

// ---------------------------------------------------------------------------
// 删除
// ---------------------------------------------------------------------------

async function confirmRemove(row: CollectCursor): Promise<void> {
  try {
    await ElMessageBox.confirm(
      `确定删除「${row.targetName ?? row.targetId}」的游标吗？` +
        `删掉之后下次采集会从第 0 页开始 —— 效果等同于重置，但记录本身也没了。` +
        `不会删除已采到的图书。`,
      '删除游标',
      { type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消' },
    )
  } catch {
    return
  }
  const id = row.id
  if (!id) {
    return
  }
  acting.value = `${row.targetType}:${row.targetId}`
  try {
    await deleteCursor(id)
    ElMessage.success('游标已删除')
    await load()
  } catch {
    // 拦截器已提示
  } finally {
    acting.value = ''
  }
}

onMounted(load)
</script>

<template>
  <div class="page">
    <el-alert type="info" :closable="false" class="intro">
      <template #title>
        游标是「下次从第几页开始」，按目标存（一个分类 / 榜单一个游标）。
        它是断点续传的唯一依据 —— 任务详情里那个「当前游标」只是给界面看的快照，改它没有效果。
        重置游标不会删除已采到的图书：重新采到的书会覆盖更新，不会重复插入。
      </template>
    </el-alert>

    <el-card shadow="never" class="table-card">
      <ProTable
        :data="rows"
        :loading="loading"
        :show-pagination="false"
        row-key="id"
        empty-text="还没有游标。游标是采集过程的副产品 —— 跑一次任务就会自动出现。"
      >
        <template #toolbar>
          <el-button type="primary" :loading="loading" @click="load">
            <el-icon><Refresh /></el-icon>
            刷新
          </el-button>
          <span class="toolbar-info">
            共 {{ rows.length }} 条 · 已采完 {{ stats.finished }} · 采过 {{ stats.done }} ·
            未开始 {{ stats.fresh }}
          </span>
        </template>

        <el-table-column label="采集目标" min-width="200" show-overflow-tooltip>
          <template #default="{ row }">
            <el-tag size="small" effect="plain" class="type-tag">
              {{ row.targetType === 'RANKING' ? '榜单' : '分类' }}
            </el-tag>
            {{ row.targetName ?? '-' }}
            <span class="text-muted">（{{ row.targetId }}）</span>
          </template>
        </el-table-column>

        <el-table-column label="状态" width="96" align="center">
          <template #default="{ row }">
            <el-tag size="small" :type="progressTagType(row)" effect="dark">
              {{ progressText(row) }}
            </el-tag>
          </template>
        </el-table-column>

        <el-table-column label="下次从第几页" width="130" align="right">
          <template #default="{ row }">
            <span class="num highlight">{{ dash(row.maxIndex) }}</span>
          </template>
        </el-table-column>

        <el-table-column label="累计入库" width="100" align="right">
          <template #default="{ row }">{{ formatNumber(row.totalCollected) }}</template>
        </el-table-column>

        <el-table-column label="目标总数" width="100" align="right">
          <template #default="{ row }">{{ formatNumber(row.totalCount) }}</template>
        </el-table-column>

        <el-table-column label="最近更新" width="150">
          <template #default="{ row }">{{ formatDateTime(row.updatedAt) }}</template>
        </el-table-column>

        <el-table-column label="最近运行 ID" min-width="150" show-overflow-tooltip>
          <template #default="{ row }">
            <span class="text-mono">{{ dash(row.lastRunId) }}</span>
          </template>
        </el-table-column>

        <el-table-column label="操作" width="186" fixed="right" align="center">
          <template #default="{ row }">
            <el-button
              link
              type="primary"
              :loading="acting === `${row.targetType}:${row.targetId}`"
              @click="openSet(row)"
            >
              指定起点
            </el-button>
            <el-button
              link
              type="warning"
              :loading="acting === `${row.targetType}:${row.targetId}`"
              @click="confirmReset(row)"
            >
              重置
            </el-button>
            <el-button
              link
              type="danger"
              :loading="acting === `${row.targetType}:${row.targetId}`"
              @click="confirmRemove(row)"
            >
              删除
            </el-button>
          </template>
        </el-table-column>
      </ProTable>
    </el-card>

    <!-- 指定起点 -->
    <el-dialog
      v-model="dialogVisible"
      title="指定游标起点"
      width="480px"
    >
      <el-alert type="info" :closable="false" class="dialog-tip">
        <template #title>
          目标：{{ dialogRow?.targetName ?? dialogRow?.targetId }}
          （{{ dialogRow?.targetType === 'RANKING' ? '榜单' : '分类' }}）
        </template>
      </el-alert>

      <el-form label-width="110px">
        <el-form-item label="下次从第几页">
          <el-input-number v-model="dialogForm.maxIndex" :min="0" :controls="false" />
        </el-form-item>
      </el-form>
      <div class="dialog-hint">
        第 1 页是 0。填 0 = 重置（等同于点「重置」）。
        下一次采集会从这一页开始往后翻，已经采到的图书不会重复插入。
      </div>

      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="dialogSaving" @click="submitSet">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.page {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.intro :deep(.el-alert__title) {
  line-height: 1.7;
}

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

.num {
  font-variant-numeric: tabular-nums;
}
.highlight {
  font-weight: 600;
  color: #409eff;
}

.dialog-tip {
  margin-bottom: 14px;
}
.dialog-hint {
  margin: -4px 0 0 110px;
  font-size: 12px;
  line-height: 1.7;
  color: #909399;
}
:deep(.el-input-number) {
  width: 100%;
}
</style>
