import { request } from './request'
import type { LoginData, LoginRequest, MeData } from '@/types'

/**
 * 认证接口（3 个）。
 *
 * 服务端用 **Sa-Token** 维护真实会话（会话数据存 Redis，账号来自 `sys_user` 表、
 * 密码以 MD5 摘要存储）：
 * - `login` 返回的 token 是**真实会话凭证**，之后所有请求带 `Authorization: Bearer <token>`；
 * - `logout` 会**真的销毁服务端会话**，旧 token 立即失效（再用 → `code=4100`）；
 * - 鉴权失败统一是 **HTTP 200 + `code=4100`**，前端按既有逻辑跳登录页即可，无需特殊处理。
 */

/** 登录。用户名或密码错误 → 后端返 code=400 且**不区分**是哪个错了 */
export function login(data: LoginRequest) {
  return request<LoginData>({
    url: '/auth/login',
    method: 'post',
    data,
  })
}

/** 登出。**服务端会销毁会话**，此后旧 token 一律 `code=4100` */
export function logout() {
  return request<{ message: string }>({
    url: '/auth/logout',
    method: 'post',
  })
}

/** 当前用户。用于刷新页面后恢复用户信息 */
export function me() {
  return request<MeData>({
    url: '/auth/me',
    method: 'get',
  })
}
