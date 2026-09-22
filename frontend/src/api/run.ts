import { request } from './request'
import type { PageResult, RunQuery, TaskLog, TaskRun } from '@/types'

/**
 * 运行记录接口（3 个）。
 *
 * `/api/runs?taskId=x` 同时覆盖两个入口：
 * - 不传 `taskId` → 全局运行历史（「运行记录」页）
 * - 传 `taskId` → 某个任务的运行历史（任务详情里）
 *
 * 所以后端只有这一个分页接口，没有单独的 `/tasks/{id}/runs`。
 */

/** 分页查询运行记录 */
export function pageRuns(query: RunQuery) {
  const params: Record<string, unknown> = {}
  if (query.taskId) {
    params.taskId = query.taskId
  }
  if (query.status) {
    params.status = query.status
  }
  if (query.page !== undefined) {
    params.page = query.page
  }
  if (query.size !== undefined) {
    params.size = query.size
  }
  return request<PageResult<TaskRun>>({
    url: '/runs',
    method: 'get',
    params,
  })
}

/** 运行详情 */
export function getRun(id: string) {
  return request<TaskRun>({
    url: `/runs/${encodeURIComponent(id)}`,
    method: 'get',
  })
}

/**
 * 运行日志。
 *
 * ⚠️ 后端会**先校验 run 存在**再查日志：runId 写错时返 404，
 * 而不是返回一个空数组。否则前端无法区分「这次运行确实没日志」
 * 和「你查的 run 根本不存在」。
 *
 * `limit` 不传时用配置的 `bookcollector.task.log-list-limit`（500）。
 */
export function getRunLogs(id: string, limit?: number) {
  return request<TaskLog[]>({
    url: `/runs/${encodeURIComponent(id)}/logs`,
    method: 'get',
    params: limit === undefined ? {} : { limit },
  })
}
