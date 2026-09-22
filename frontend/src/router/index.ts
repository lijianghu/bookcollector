import { createRouter, createWebHashHistory, type RouteRecordRaw } from 'vue-router'
import { useUserStore } from '@/store/user'
import {
  beginGuardSessionCheck,
  endGuardSessionCheck,
  SessionInvalidError,
} from '@/api/request'

/**
 * 路由表。
 *
 * <h3>为什么用 hash 模式</h3>
 * 本期是本地单机跑（`vite dev` / 静态文件直接打开），没有 Nginx 做 history fallback。
 * history 模式下刷新 `/books` 会 404。hash 模式（`/#/books`）不需要任何服务端配合。
 *
 * <h3>结构</h3>
 * 业务页面全部是 `BasicLayout` 的子路由 —— 这样切页面时侧边栏和顶栏不会重建
 * （组件实例复用），折叠状态、滚动位置这些都不会跳。
 */

const routes: RouteRecordRaw[] = [
  {
    path: '/login',
    name: 'login',
    component: () => import('@/views/Login.vue'),
    meta: { title: '登录', public: true },
  },
  {
    path: '/',
    component: () => import('@/layouts/BasicLayout.vue'),
    redirect: '/dashboard',
    children: [
      {
        path: 'dashboard',
        name: 'dashboard',
        component: () => import('@/views/dashboard/index.vue'),
        meta: { title: '仪表盘' },
      },
      {
        path: 'books',
        name: 'books',
        component: () => import('@/views/book/index.vue'),
        meta: { title: '图书库' },
      },
      {
        path: 'tasks',
        name: 'tasks',
        component: () => import('@/views/task/index.vue'),
        meta: { title: '任务中心' },
      },
      {
        path: 'taxonomy',
        name: 'taxonomy',
        component: () => import('@/views/taxonomy/index.vue'),
        meta: { title: '分类榜单' },
      },
      {
        path: 'cursors',
        name: 'cursors',
        component: () => import('@/views/cursor/index.vue'),
        meta: { title: '采集游标' },
      },
      {
        path: 'requests',
        name: 'requests',
        component: () => import('@/views/audit/index.vue'),
        meta: { title: '请求审计' },
      },
    ],
  },
  {
    path: '/:pathMatch(.*)*',
    name: 'not-found',
    component: () => import('@/views/error/404.vue'),
    meta: { title: '页面不存在', public: true },
  },
]

const router = createRouter({
  history: createWebHashHistory(),
  routes,
  scrollBehavior: () => ({ top: 0 }),
})

// ======================================================================
// 登录守卫
// ======================================================================

/**
 * ⚠️ 这里的判断依据是 **localStorage 里的 token**（通过 `userStore.isLoggedIn`），
 * 不是「store 里有没有用户信息」。
 *
 * 原因：刷新页面后 Pinia 状态全丢，但 localStorage 里的 token 还在 ——
 * 此时用户**应该**被认为是登录着的（token 还有效），只是缺昵称这些展示信息。
 * 反过来若按「store 里有信息才算登录」，每次刷新都会把用户踢到登录页。
 *
 * 真正的有效性判断只有后端能做（token 不对会返 4100），
 * 那时 `api/request.ts` 的拦截器会统一跳登录页。
 */
router.beforeEach(async (to) => {
  const userStore = useUserStore()

  // --- 公开页面（登录页 / 404）---
  if (to.meta.public) {
    // 已登录还去登录页 → 直接送进后台，别让用户再登一次
    if (to.path === '/login' && userStore.isLoggedIn) {
      return { path: '/dashboard' }
    }
    return true
  }

  // --- 需要登录 ---
  if (!userStore.isLoggedIn) {
    return {
      path: '/login',
      // 记住来路，登录成功后跳回去。用 fullPath 而不是 path，才能带上 query
      query: { redirect: to.fullPath },
    }
  }

  // 有 token 但缺用户信息（刚刷新过）→ 补一次。
  // 顺便当作 token 探活：4100 说明 token 坏了，把用户送回登录页。
  if (!userStore.nickname && userStore.roles.length === 0) {
    // 告诉拦截器「这次请求是守卫在做探活」—— 4100 时它会只清 token、不抢 URL，
    // 由下面 return 的登录路由来跳。否则拦截器和正在进行的这次导航会抢 URL，
    // 结果用户停在原页面而不是登录页（S5 实测到的缺陷）
    beginGuardSessionCheck()
    try {
      await userStore.fetchMe()
    } catch (e) {
      if (e instanceof SessionInvalidError) {
        // ⚠️ 必须连内存里的 token 一起清掉（`resetLocal()`），不能只靠拦截器的
        // `clearToken()`（那只清 localStorage）。
        // 否则接下来导航到 `/login` 时，守卫的公开分支会看到
        // `isLoggedIn === true`（内存里的 token 还在），又把你弹回 `/dashboard` ——
        // 表现成「token 明明坏了，却一直停在后台」。
        // 这是 S5 验收实测出来的第二个缺陷（第一个是拦截器和路由抢 URL）。
        userStore.resetLocal()
        return { path: '/login', query: { redirect: to.fullPath } }
      }
      // 其他错误（后端没起来、网络断）不该把用户踢去登录页 ——
      // 那时候他登也登不上，只会更困惑。放行，让页面自己去显示错误
    } finally {
      endGuardSessionCheck()
    }
  }

  return true
})

router.afterEach((to) => {
  const title = (to.meta?.title as string) || ''
  document.title = title ? `${title} · 微信读书采集后台` : '微信读书采集后台'
})

export default router
