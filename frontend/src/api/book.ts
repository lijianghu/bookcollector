import { request } from './request'
import type {
  BatchDeleteResult,
  Book,
  BookQuery,
  BookUpdateRequest,
  PageResult,
} from '@/types'

/**
 * 图书接口（5 个）。
 *
 * 分页接口直接收 `BookQuery` 的 12 个查询参数 —— 后端 `BookQuery` 是
 * 一个普通 POJO，Spring 会按字段名绑定，所以前端只要把非空字段拼进 query 即可。
 */

/**
 * 拼查询参数：**跳过 `undefined` / `null` / 空字符串**。
 *
 * 为什么不直接把整个 query 对象丢给 axios：axios 会把 `undefined` 也序列化掉没问题，
 * 但空字符串会被发成 `?author=`，而后端 `resolveAuthor()` 的 `blankToNull`
 * 会把它当「没传」—— 结果对，但 URL 里全是空参数，排查时看着累。
 *
 * 另外注意 `asc: false` 和 `page: 0` 这类**假值必须保留**，
 * 所以判断条件是 `!== undefined && !== null && !== ''`，不能用 `if (value)`。
 */
function toParams(query: Record<string, unknown>): Record<string, unknown> {
  const params: Record<string, unknown> = {}
  Object.keys(query).forEach((key) => {
    const value = query[key]
    if (value !== undefined && value !== null && value !== '') {
      params[key] = value
    }
  })
  return params
}

/** 分页查询图书 */
export function pageBooks(query: BookQuery) {
  return request<PageResult<Book>>({
    url: '/books',
    method: 'get',
    params: toParams(query as Record<string, unknown>),
  })
}

/** 图书详情。不存在 → 后端返 code=404 */
export function getBook(bookId: string) {
  return request<Book>({
    url: `/books/${encodeURIComponent(bookId)}`,
    method: 'get',
  })
}

/**
 * 编辑图书。
 *
 * ⚠️ 后端是**局部更新**：只写你传了的字段。
 * 所以这里只提交用户真正改过的字段，不要图省事把整个 Book 回传 ——
 * 那会把 `newRating` / `readingCount` 这些「接口给的、用户不能改的」字段一起写进去。
 */
export function updateBook(bookId: string, data: BookUpdateRequest) {
  return request<Book>({
    url: `/books/${encodeURIComponent(bookId)}`,
    method: 'put',
    data,
  })
}

/** 删除单本图书 */
export function deleteBook(bookId: string) {
  return request<BatchDeleteResult>({
    url: `/books/${encodeURIComponent(bookId)}`,
    method: 'delete',
  })
}

/** 批量删除。返回**实际**删除数（请求里可能有 bookId 已经不存在了） */
export function batchDeleteBooks(bookIds: string[]) {
  return request<BatchDeleteResult>({
    url: '/books/batch-delete',
    method: 'post',
    data: { bookIds },
  })
}
