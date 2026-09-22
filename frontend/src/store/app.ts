import { defineStore } from 'pinia'
import { ref, watch } from 'vue'

const COLLAPSE_KEY = 'bookcollector_sidebar_collapsed'

/**
 * 应用级 UI 状态。
 *
 * 目前只有一件事：侧边栏折叠。之所以持久化到 localStorage，
 * 是因为「折叠」是用户对工作区的偏好 —— 每次刷新都弹回来会很烦。
 *
 * 刻意**不**把页面级状态（当前选中行、查询条件、分页）放进来：
 * 那些状态应该跟着页面组件走（`ref` + 路由 query），
 * 放进全局 store 只会让「离开页面再回来数据还在」这种 bug 变得难以解释。
 */
export const useAppStore = defineStore('app', () => {
  const sidebarCollapsed = ref<boolean>(localStorage.getItem(COLLAPSE_KEY) === '1')

  function toggleSidebar(): void {
    sidebarCollapsed.value = !sidebarCollapsed.value
  }

  function setSidebarCollapsed(value: boolean): void {
    sidebarCollapsed.value = value
  }

  // 用 watch 而不是在 toggle 里写 localStorage：这样任何来源的改动都会被持久化，
  // 不会漏掉「某处直接 setSidebarCollapsed」的情况
  watch(sidebarCollapsed, (value) => {
    localStorage.setItem(COLLAPSE_KEY, value ? '1' : '0')
  })

  return { sidebarCollapsed, toggleSidebar, setSidebarCollapsed }
})
