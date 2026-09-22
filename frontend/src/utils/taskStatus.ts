/**
 * 任务状态机的**前端镜像**。
 *
 * <h3>为什么要单独抽一个文件</h3>
 * 五个动作按钮的可用性、状态标签的颜色和文案，在「任务中心」「运行记录」
 * 两个页面都要用。散在模板里写 `v-if` 会立刻出现两份不一致的规则 ——
 * 而后端 `TaskService` 是**有前置校验**的：按钮没置灰，用户点下去就会收到
 * 「任务正在运行中（运行中），请先停止它再编辑」这种本来可以避免的报错。
 *
 * <h3>规则来自后端实现，不是猜的</h3>
 * 逐条对照 `TaskService` 的前置判断：
 *
 * <table border="1">
 *   <tr><th>动作</th><th>允许的状态</th><th>后端依据</th></tr>
 *   <tr><td>启动 start</td><td>PENDING</td>
 *       <td>后端也接受 INTERRUPTED（两者都走 {@code launch} 续传），
 *           但 UI 只给「恢复」—— 两个按钮做同一件事只会让人犹豫点哪个</td></tr>
 *   <tr><td>暂停 pause</td><td>RUNNING</td><td>{@code if (status != RUNNING) 拒绝}</td></tr>
 *   <tr><td>恢复 resume</td><td>PAUSED / INTERRUPTED</td>
 *       <td>PAUSED 走原地唤醒；其余走 {@code launch}</td></tr>
 *   <tr><td>取消 cancel</td><td>非终态（PENDING / RUNNING / PAUSED / INTERRUPTED）</td>
 *       <td>{@code if (status.isTerminal()) 拒绝}</td></tr>
 *   <tr><td>重试 retry</td><td>终态（SUCCESS / FAILED / CANCELED）</td>
 *       <td>{@code if (status.isActive()) 拒绝} + {@code if (!status.isTerminal()) 拒绝}</td></tr>
 * </table>
 *
 * <p><b>编辑 / 删除</b>：只允许非活跃状态（PENDING / INTERRUPTED / 三个终态）。
 * 后端对活跃状态直接拒绝 —— 尤其删除，如果放过去，采集线程会**继续跑**，
 * 用户以为「删了就不采了」，这是最危险的一类误解。
 *
 * <p><b>INTERRUPTED 同时满足「启动」和「恢复」</b>：两者在后端都走 `launch`
 * （从游标续传）。UI 上只给一个按钮（叫「继续」），给两个只会让人犹豫点哪个。
 */

import type { TaskStatus } from '@/types'
import { isTerminalStatus } from '@/types'

export type TaskActionKey = 'start' | 'pause' | 'resume' | 'cancel' | 'retry'

export interface TaskActionState {
  key: TaskActionKey
  label: string
  /** el-button 的 type */
  buttonType: 'primary' | 'warning' | 'danger' | 'success'
  /** 是否可用 */
  enabled: boolean
  /** 不可用的原因。渲染成 tooltip —— 置灰但不解释是最招人烦的交互 */
  reason: string
  /** 是否需要二次确认（取消会丢掉当前 run 的进度，值得确认一下） */
  needConfirm: boolean
}

/** 状态 → 中文标签。与后端 `TaskStatus` 的 `label` 保持一致 */
export const TASK_STATUS_LABELS: Record<TaskStatus, string> = {
  PENDING: '待执行',
  RUNNING: '运行中',
  PAUSED: '已暂停',
  SUCCESS: '已完成',
  FAILED: '失败',
  CANCELED: '已取消',
  INTERRUPTED: '已中断',
}

export function taskStatusLabel(status?: TaskStatus | null): string {
  if (!status) {
    return '-'
  }
  return TASK_STATUS_LABELS[status] ?? status
}

/** 状态 → el-tag 的 type。运行中用 primary（蓝），终态用 success/danger/info */
export function taskStatusTagType(
  status?: TaskStatus | null,
): 'success' | 'info' | 'warning' | 'danger' | 'primary' {
  switch (status) {
    case 'RUNNING':
      return 'primary'
    case 'PAUSED':
      return 'warning'
    case 'SUCCESS':
      return 'success'
    case 'FAILED':
      return 'danger'
    case 'CANCELED':
      return 'info'
    case 'INTERRUPTED':
      return 'danger'
    default:
      return 'info'
  }
}

/** 活跃（有采集线程或占着目标）：RUNNING / PAUSED */
export function isTaskActive(status?: TaskStatus | null): boolean {
  return status === 'RUNNING' || status === 'PAUSED'
}

/** 任务是否可编辑。后端：活跃状态一律拒绝 */
export function canEditTask(status?: TaskStatus | null): boolean {
  return !isTaskActive(status)
}

/** 任务是否可删除。后端：活跃状态一律拒绝（否则采集会继续跑） */
export function canDeleteTask(status?: TaskStatus | null): boolean {
  return !isTaskActive(status)
}

/** 不可编辑/删除时的原因说明 */
export function taskLockReason(status?: TaskStatus | null): string {
  if (!isTaskActive(status)) {
    return ''
  }
  return `任务${taskStatusLabel(status)}，请先停止它（取消）再操作`
}

/**
 * 五个动作的完整可用性列表。
 *
 * 返回**全部 5 个**（而不是只返回可用的）—— 详情抽屉里要把它们都画出来、
 * 不可用的置灰 + tooltip 说明原因。让用户看到「有哪些能力、为什么现在不能用」，
 * 比「按钮凭空消失」好理解得多。
 */
export function taskActions(status?: TaskStatus | null): TaskActionState[] {
  const terminal = isTerminalStatus(status)
  const active = isTaskActive(status)

  const startEnabled = status === 'PENDING'
  const startReason = active
    ? `任务${taskStatusLabel(status)}，无需重复启动`
    : status === 'INTERRUPTED'
      ? '任务已中断 —— 请用「恢复」从断点继续'
      : terminal
        ? `任务${taskStatusLabel(status)}，已结束 —— 请用「重试」`
        : ''

  const resumeEnabled = status === 'PAUSED' || status === 'INTERRUPTED'
  const resumeReason = resumeEnabled
    ? ''
    : status === 'PENDING'
      ? '任务还没开始跑过，请用「启动」'
      : `只有「已暂停」「已中断」的任务才能恢复，当前是「${taskStatusLabel(status)}」`

  return [
    {
      key: 'start',
      label: '启动',
      buttonType: 'primary',
      enabled: startEnabled,
      reason: startReason,
      needConfirm: false,
    },
    {
      key: 'pause',
      label: '暂停',
      buttonType: 'warning',
      enabled: status === 'RUNNING',
      reason:
        status === 'RUNNING'
          ? ''
          : `只有「运行中」的任务才能暂停，当前是「${taskStatusLabel(status)}」`,
      needConfirm: false,
    },
    {
      key: 'resume',
      label: '恢复',
      buttonType: 'success',
      enabled: resumeEnabled,
      reason: resumeReason,
      needConfirm: false,
    },
    {
      key: 'cancel',
      label: '取消',
      buttonType: 'danger',
      enabled: !terminal,
      reason: terminal ? `任务${taskStatusLabel(status)}，无需取消` : '',
      needConfirm: true,
    },
    {
      key: 'retry',
      label: '重试',
      buttonType: 'primary',
      enabled: terminal,
      reason: active
        ? `任务${taskStatusLabel(status)}，请先停止它再重试`
        : terminal
          ? ''
          : `任务还没结束（${taskStatusLabel(status)}），请用「启动」`,
      needConfirm: false,
    },
  ]
}

/**
 * 列表行里该显示哪一个「主行动」按钮。
 *
 * 表格一行只有 ~200px 放操作列，塞 5 个按钮会换行。所以行里只放
 * **当前状态下最该做的那一个**（外加取消），完整的一组放详情抽屉里。
 * 返回 `null` 表示这个状态没有主行动（比如 SUCCESS 只剩「重试」，
 * 那就让「重试」当主行动 —— 所以这里不会真的返回 null）。
 */
export function primaryTaskAction(status?: TaskStatus | null): TaskActionState | null {
  if (status === 'PENDING') {
    return taskActions(status).find((a) => a.key === 'start') ?? null
  }
  if (status === 'RUNNING') {
    return taskActions(status).find((a) => a.key === 'pause') ?? null
  }
  if (status === 'PAUSED' || status === 'INTERRUPTED') {
    // PAUSED → 恢复；INTERRUPTED → 恢复（后端也是走 launch 续传）
    return taskActions(status).find((a) => a.key === 'resume') ?? null
  }
  return taskActions(status).find((a) => a.key === 'retry') ?? null
}
