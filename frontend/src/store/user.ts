import { defineStore } from 'pinia'
import { ref, computed } from 'vue'
import * as authApi from '@/api/auth'
import {
  clearToken,
  getStoredUsername,
  getToken,
  setStoredUsername,
  setToken,
} from '@/api/request'
import type { LoginRequest } from '@/types'

/**
 * 用户 store。
 *
 * <h3>为什么 token 同时存在 localStorage 和 store 里</h3>
 * localStorage 是**真相来源**：axios 拦截器在模块加载期就要能读到 token，
 * 那时候 Pinia 还没初始化，所以拦截器只认 localStorage（见 `api/request.ts`）。
 * store 里的 `token` 只是给界面用的响应式镜像（顶栏显示用户名、控制菜单渲染）。
 *
 * 代价是「改了一边要记得改另一边」—— 所以所有写操作都收敛到 `login()` / `logout()`
 * 两个 action 里，不要在其他地方直接 `localStorage.setItem`。
 *
 * <h3>为什么没有 refreshToken</h3>
 * 后端用 Sa-Token 签发 token 并把会话存在 Redis 里，有效期 30 天
 * （`application.yml` 的 `sa-token.timeout`），期间不需要刷新 ——
 * 本地单用户工具里，让 token 自己过期比维护一套刷新流程省事得多。
 * 过期 / 被登出后后端返回 `code=4100`，由 axios 拦截器统一送回登录页。
 */
export const useUserStore = defineStore('user', () => {
  // ---------------- state ----------------
  const token = ref<string>(getToken() ?? '')
  const username = ref<string>(getStoredUsername() ?? '')
  const nickname = ref<string>('')
  const roles = ref<string[]>([])

  // ---------------- getters ----------------
  const isLoggedIn = computed(() => token.value !== '')
  /** 顶栏显示名：优先昵称，退回账号 */
  const displayName = computed(() => nickname.value || username.value || '未登录')

  // ---------------- actions ----------------

  /**
   * 登录。
   *
   * 成功后才写 localStorage —— 顺序很重要：先写 token 再调接口的话，
   * 万一接口失败会留下一个「看起来已登录但 token 是坏的」状态。
   */
  async function login(payload: LoginRequest): Promise<void> {
    const data = await authApi.login(payload)
    setToken(data.token)
    setStoredUsername(data.username)
    token.value = data.token
    username.value = data.username
  }

  /**
   * 拉取当前用户信息。
   *
   * 刷新页面后 store 是空的，但 localStorage 里有 token ——
   * 所以路由守卫在「有 token 但 store 没用户信息」时会调这个补上。
   * 顺便也当成一次「token 还有效吗」的探活。
   */
  async function fetchMe(): Promise<void> {
    const data = await authApi.me()
    username.value = data.username
    nickname.value = data.nickname
    roles.value = data.roles ?? []
  }

  /**
   * 登出。
   *
   * 后端**真的**会删掉 Redis 里的会话（改造前是无状态的，清了也没用），
   * 所以这个请求有实际作用，不能省。
   * 但**即使它失败也必须把本地状态清掉** —— 否则用户会卡在
   * 「点了登出但还是登录着」的状态里（那种情况下后端会话还在，
   * 只是本地已经登不回去，重新登录即可）。
   */
  async function logout(): Promise<void> {
    try {
      await authApi.logout()
    } catch {
      // 忽略：本地状态必须清
    } finally {
      token.value = ''
      username.value = ''
      nickname.value = ''
      roles.value = []
      clearToken()
    }
  }

  /** 清空本地登录态（不调接口）。给 4100 之后复用 */
  function resetLocal(): void {
    token.value = ''
    username.value = ''
    nickname.value = ''
    roles.value = []
    clearToken()
  }

  return {
    token,
    username,
    nickname,
    roles,
    isLoggedIn,
    displayName,
    login,
    fetchMe,
    logout,
    resetLocal,
  }
})
