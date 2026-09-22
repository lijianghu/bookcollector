import { request } from './request'
import type { CollectCursor, CursorSetRequest, CursorSetResult } from '@/types'

/**
 * 采集游标接口（3 个）。
 *
 * <h3>游标是什么</h3>
 * `maxIndex` = 「下次从哪开始」。它是**断点续传的唯一权威依据** ——
 * `TaskRun.currentCursor` 只是给界面看的进度快照，改它没有任何效果。
 *
 * 游标按**目标**存（不是按任务）：一个 target 一个游标。
 * 所以「删了任务」不等于「游标没了」，反过来也一样。
 */

/** 游标列表（全部，不分页） */
export function listCursors() {
  return request<CollectCursor[]>({
    url: '/cursors',
    method: 'get',
  })
}

/**
 * 设置游标。
 *
 * ⚠️ **`maxIndex = 0` 就是「重置」** —— 后端把它当成 reset 处理，
 * 顺带把 `totalCollected` 清零、`finished` 置 false。
 * 所以「重置」和「指定起点」是同一个接口，不是两个。
 *
 * ⚠️ **重置不会删书**。它只让下一次采集从第 0 页开始 ——
 * 已经入库的书会被 upsert 覆盖（`firstCollectedAt` 不变，`lastCollectedAt` 刷新），
 * 不会重复插入。这是幂等写入带来的好处，也是为什么重置是安全的。
 */
export function setCursor(data: CursorSetRequest) {
  return request<CursorSetResult>({
    url: '/cursors',
    method: 'put',
    data,
  })
}

/** 删除游标。删了之后下次采集会从第 0 页开始（等于隐式重置） */
export function deleteCursor(id: string) {
  return request<{ id: string }>({
    url: `/cursors/${encodeURIComponent(id)}`,
    method: 'delete',
  })
}
