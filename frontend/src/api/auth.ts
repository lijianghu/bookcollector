import { request } from './request'
import type { LoginData, LoginRequest, MeData } from '@/types'

/**
 * 认证接口（3 个）。
 *
 * ⚠️ 服务端**没有会话**：token 是配置文件里写死的固定值，
 * `logout` 只是给前端一个「确认可以清了」的应答，不会让 token 失效。
 * 第一期是本地单用户，这是刻意的。
 */

/** 登录。用户名或密码错误 → 后端返 code=400 且**不区分**是哪个错了 */
export function login(data: LoginRequest) {
  return request<LoginData>({
    url: '/auth/login',
    method: 'post',
    data,
  })
}

/** 登出。服务端无状态，只返回确认 */
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
