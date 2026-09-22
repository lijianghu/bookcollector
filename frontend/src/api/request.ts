import axios, { type AxiosInstance, type AxiosRequestConfig, type AxiosResponse } from 'axios'
import { ElMessage } from 'element-plus'
import { CODE_OK, CODE_SESSION_INVALID, type ResultBean } from '@/types'

const TOKEN_KEY = 'bookcollector_token'
const USERNAME_KEY = 'bookcollector_username'

// ======================================================================
// token 读写
// ======================================================================

export function getToken(): string | null {
  return localStorage.getItem(TOKEN_KEY)
}

export function setToken(token: string): void {
  localStorage.setItem(TOKEN_KEY, token)
}

export function clearToken(): void {
  localStorage.removeItem(TOKEN_KEY)
  localStorage.removeItem(USERNAME_KEY)
}

export function getStoredUsername(): string | null {
  return localStorage.getItem(USERNAME_KEY)
}

export function setStoredUsername(username: string): void {
  localStorage.setItem(USERNAME_KEY, username)
}

// ======================================================================
// 身份失效处理
// ======================================================================

/**
 * 会话失效的专用错误类型。
 *
 * 为什么不用 `new Error(message)`：路由守卫需要**区分**「token 坏了（4100）」
 * 和「后端没起来 / 网络断了」。前者应该把用户送回登录页，
 * 后者应该留在原地让他重试（把人踢去登录页也登不上，只会更困惑）。
 * 光靠 message 字符串判断太脆 —— 后端改一个字就失效。
 */
export class SessionInvalidError extends Error {
  /** 与 ResultBean.code_session_invalid 一致 */
  readonly code = 4100

  constructor(message: string) {
    super(message)
    this.name = 'SessionInvalidError'
  }
}

/**
 * 已经因为 4100 处理过一次了吗？
 *
 * 为什么需要这个：仪表盘一进页面会并发发好几个请求（overview + charts + ping）。
 * token 失效时它们会**同时**返回 4100，于是弹出 4 条一模一样的「身份已失效」提示，
 * 并触发 4 次整页重载。第一次重载就已经在卸载文档了，后面几次纯属噪声。
 */
let sessionInvalidHandled = false

/** 页面刚加载时重置（每次真正的新会话只有一次） */
export function resetSessionInvalidFlag(): void {
  sessionInvalidHandled = false
}

/**
 * 「当前这次 4100 是路由守卫在做 token 探活时发生的」标记。
 *
 * <h3>为什么需要它（这是 S5 验收实测出来的一个真实缺陷）</h3>
 * 原来的实现是「拦截器收到 4100 → 把 hash 改成 `#/login`」。
 * 问题在于：如果这次 4100 正好发生在**路由守卫的 `fetchMe()` 里**，
 * 那么此刻有一次 router 导航正在进行中 —— 守卫拿到 reject 之后返回 `true`，
 * router 会继续完成这次导航，并在完成时把 URL 写回原路径。
 * **结果拦截器设的 `#/login` 被覆盖掉了**，用户停在一个空白的仪表盘上。
 *
 * 实测现象（`tools/s5-acceptance.mjs` 第 5 项）：
 * `token` 已被清空（说明 4100 处理跑了），但 `location.hash` 还是 `#/dashboard`。
 *
 * 所以约定：**守卫探活期间的跳转由守卫自己负责**（它会 `return` 登录路由），
 * 拦截器只清 token、不抢 URL。守卫的返回值是权威的，拦截器不该跟它抢。
 */
let sessionCheckInGuard = false

export function beginGuardSessionCheck(): void {
  sessionCheckInGuard = true
}

export function endGuardSessionCheck(): void {
  sessionCheckInGuard = false
}

/**
 * 跳登录页。
 *
 * <h3>为什么是整页重载，而不是改 hash</h3>
 * 只改 hash（`window.location.href = '/#/login'`）**不会重新加载页面**，
 * 于是会和正在进行的 router 导航抢 URL（见上面 `sessionCheckInGuard` 的说明）。
 * 整页重载没有这个问题：文档从零开始，token 已被清掉，
 * 路由守卫必然把用户送到 `/login?redirect=<当前路径>`。
 *
 * 附带的好处是**回跳地址只由守卫一处计算** —— 不会出现「拦截器算一套、
 * 守卫算一套」两个算法不一致的情况。
 *
 * 代价是丢掉页面内的内存状态（筛选条件、分页）。但会话都已经失效了，
 * 这些状态本来也要重新登录后才能用，而且重载后守卫会把路径记进 `?redirect=`，
 * 用户登录完还能回到原页面。所以这个代价是值得的。
 */
function redirectToLogin(): void {
  clearToken()

  // 守卫探活期间的跳转交给守卫（它 return 登录路由），这里不抢
  if (sessionCheckInGuard) {
    return
  }

  if (sessionInvalidHandled) {
    return
  }
  sessionInvalidHandled = true

  const currentPath = window.location.hash.replace(/^#/, '') || '/'
  if (currentPath.startsWith('/login')) {
    // 已经在登录页了，再重载就是无意义的抖动
    return
  }

  window.location.reload()
}

// ======================================================================
// axios 实例
// ======================================================================

const service: AxiosInstance = axios.create({
  // 走 vite 的 /api 代理 → http://127.0.0.1:8080
  baseURL: '/api',
  timeout: 30000,
  headers: { 'Content-Type': 'application/json;charset=UTF-8' },
})

// ---------------- 请求拦截：带上 token ----------------
service.interceptors.request.use(
  (config) => {
    const token = getToken()
    if (token) {
      config.headers.Authorization = `Bearer ${token}`
    }
    return config
  },
  (error) => Promise.reject(error),
)

// ---------------- 响应拦截：按 body.code 判定，**不按 HTTP 状态码** ----------------
service.interceptors.response.use(
  (response: AxiosResponse) => {
    const body = response.data as ResultBean

    // 非 ResultBean 结构（比如直接取静态资源）原样放行
    if (body == null || typeof body.code !== 'number') {
      return response.data
    }

    if (body.code === CODE_OK) {
      // 成功：只把 data 交给调用方，调用方不用再 .data 一次
      return body.data
    }

    if (body.code === CODE_SESSION_INVALID) {
      // 后端约定：鉴权失败返回 HTTP 200 + code=4100（不是 401），
      // 所以这个分支才是「登录过期」的唯一入口
      ElMessage.error(body.message || '身份已失效，请重新登录')
      redirectToLogin()
      return Promise.reject(new SessionInvalidError(body.message || '身份已失效'))
    }

    // 其他业务错误（400 / 404 / 405 / 500）：弹后端给的 message 并 reject
    ElMessage.error(body.message || `请求失败（code=${body.code}）`)
    return Promise.reject(new Error(body.message || `code=${body.code}`))
  },
  (error) => {
    // 走到这里说明是 HTTP 层错误（后端没起、网络断、超时、跨域等）
    const status = error?.response?.status
    if (status === 401 || status === 403) {
      // 理论上不该出现（后端一律返 200），留着兜底
      ElMessage.error('没有权限，请重新登录')
      redirectToLogin()
    } else if (error?.code === 'ECONNABORTED') {
      ElMessage.error('请求超时，后端可能正在处理长任务')
    } else if (!error?.response) {
      ElMessage.error('无法连接后端服务，请确认 8080 端口已启动')
    } else {
      ElMessage.error(`请求异常：HTTP ${status}`)
    }
    return Promise.reject(error)
  },
)

// ======================================================================
// 对外统一入口
// ======================================================================

/**
 * 统一请求方法：返回值已被响应拦截器解包为业务 `data`。
 *
 * <h3>为什么这里要 `as unknown as`</h3>
 * axios 的类型签名是「返回 `Promise<AxiosResponse<T>>`」，它**不知道**我们的
 * 响应拦截器已经把 `response.data` 里的 `ResultBean` 拆成了 `body.data`。
 * 所以运行时拿到的是 `T`，而类型系统认为拿到的是 `AxiosResponse<T>` ——
 * 两者之间没有可推导的关系，只能显式断言。
 *
 * 试过 `service.request<unknown, T>(config)`（axios 的第二个泛型参数是「返回类型」），
 * 但 axios 1.20 的签名是 `request<T, R = AxiosResponseDefault, D, P>`，
 * `R` 传 `T` 之后返回的仍是 `AxiosResponseResult<...>` 这种包装类型，仍然对不上。
 *
 * 这个断言是安全的：拦截器的成功分支**只**返回 `body.data`，
 * 业务错误分支一律 `reject`。所以「resolve 出来的东西就是 data」这个前提，
 * 是由拦截器的代码结构保证的，不是靠猜。
 */
export function request<T = unknown>(config: AxiosRequestConfig): Promise<T> {
  return service.request(config) as unknown as Promise<T>
}

export default service
