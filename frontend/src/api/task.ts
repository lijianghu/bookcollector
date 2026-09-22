import { request } from './request'
import type {
  CollectTask,
  TaskCreateRequest,
  TaskDeleteResult,
  TaskRun,
  TaskUpdateRequest,
} from '@/types'

/**
 * 采集任务接口（10 个）。
 *
 * <h3>状态机的动作语义（前端按钮要按这个来）</h3>
 *
 * | 当前状态 | start | pause | resume | cancel | retry |
 * |---|---|---|---|---|---|
 * | PENDING | ✅ | — | — | — | — |
 * | RUNNING | — | ✅ | — | ✅ | — |
 * | PAUSED | — | — | ✅ | ✅ | — |
 * | INTERRUPTED | ✅（走 resume） | — | ✅ | — | — |
 * | SUCCESS / FAILED / CANCELED | — | — | — | — | ✅（FAILED 时） |
 *
 * ⚠️ **终态不可再推进**：SUCCESS / FAILED / CANCELED 之后只能 `retry`
 * （后端会新建一次 run，游标保留 → 从断点继续）。
 *
 * ⚠️ **运行中不能编辑 / 不能删除**：后端会返 code=400，
 * 提示「请先停止它」。前端应该把按钮置灰，而不是等用户点了再报错。
 */

/** 任务列表（后端按创建时间倒序，不分页 —— 任务数量天然是几十个量级） */
export function listTasks() {
  return request<CollectTask[]>({
    url: '/tasks',
    method: 'get',
  })
}

/** 任务详情 */
export function getTask(id: string) {
  return request<CollectTask>({
    url: `/tasks/${encodeURIComponent(id)}`,
    method: 'get',
  })
}

/**
 * 新建任务。
 *
 * ⚠️ 一个目标只能有一个任务（`collect_tasks` 上 `(targetType, targetId)` 唯一索引）。
 * 重复新建 → 后端返 code=405。想换参数就改这个任务，不要建第二个。
 */
export function createTask(data: TaskCreateRequest) {
  return request<CollectTask>({
    url: '/tasks',
    method: 'post',
    data,
  })
}

/**
 * 编辑任务。
 *
 * ⚠️ 只有 `name` / `maxPages` / `remark` 可改。
 * `targetType` / `targetId` 不在请求体里 —— 它们决定游标归属，改了会脱钩。
 */
export function updateTask(id: string, data: TaskUpdateRequest) {
  return request<CollectTask>({
    url: `/tasks/${encodeURIComponent(id)}`,
    method: 'put',
    data,
  })
}

/**
 * 删除任务。
 *
 * 后端**级联删** `collect_task_runs` + `collect_task_logs`，
 * 但**不删游标**（游标是「目标」的属性，任务删了不代表要重新采集）。
 * 返回 `runsDeleted` / `logsDeleted` 让用户知道连带删了多少。
 */
export function deleteTask(id: string) {
  return request<TaskDeleteResult>({
    url: `/tasks/${encodeURIComponent(id)}`,
    method: 'delete',
  })
}

/** 启动。返回新建的 TaskRun（可以拿它的 id 去拉进度） */
export function startTask(id: string) {
  return request<TaskRun>({
    url: `/tasks/${encodeURIComponent(id)}/start`,
    method: 'post',
  })
}

/** 暂停。**阻塞式**暂停，恢复时一页都不重采 */
export function pauseTask(id: string) {
  return request<{ taskId: string; status: string }>({
    url: `/tasks/${encodeURIComponent(id)}/pause`,
    method: 'post',
  })
}

/** 恢复（原地继续，不是重新开始） */
export function resumeTask(id: string) {
  return request<TaskRun>({
    url: `/tasks/${encodeURIComponent(id)}/resume`,
    method: 'post',
  })
}

/** 取消。会同时解除暂停状态，否则暂停中的任务永远等不到唤醒 */
export function cancelTask(id: string) {
  return request<{ taskId: string; status: string }>({
    url: `/tasks/${encodeURIComponent(id)}/cancel`,
    method: 'post',
  })
}

/** 重试。只对 FAILED 有意义；游标保留，所以是从断点继续而不是从头 */
export function retryTask(id: string) {
  return request<TaskRun>({
    url: `/tasks/${encodeURIComponent(id)}/retry`,
    method: 'post',
  })
}
