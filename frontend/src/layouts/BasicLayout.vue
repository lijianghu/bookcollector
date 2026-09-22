<script setup lang="ts">
import { computed } from 'vue'
import { useAppStore } from '@/store/app'
import AppSidebar from './components/AppSidebar.vue'
import AppNavbar from './components/AppNavbar.vue'

/**
 * 后台主布局：左固定侧边栏 + 右「顶栏 / 内容区」。
 *
 * 用 `el-container` 而不是 flex 手写，是因为 `el-aside` 支持 `width` 属性
 * 直接接一个响应式值，折叠动画不用自己算。
 *
 * 侧边栏宽度不写死在 CSS 里，而是由 `appStore.sidebarCollapsed` 算出来 ——
 * 这样折叠状态是**单一来源**，不需要 CSS 类名和 JS 状态两处同步。
 */

const appStore = useAppStore()

/** 折叠宽度 64px 是 Element Plus 的 el-menu 折叠态标准宽度，别改 */
const asideWidth = computed(() => (appStore.sidebarCollapsed ? '64px' : '210px'))
</script>

<template>
  <el-container class="layout">
    <el-aside :width="asideWidth" class="layout-aside">
      <AppSidebar />
    </el-aside>

    <el-container class="layout-right">
      <el-header height="56px" class="layout-header">
        <AppNavbar />
      </el-header>

      <el-main class="layout-main">
        <!--
          用 transition + v-slot 而不是直接 <router-view />：
          切页面时内容区做一个很短的淡入，避免「内容瞬间跳变」的闪烁感。
          mode="out-in" 保证旧页面先消失、新页面再出现，不会两个页面重叠。
        -->
        <router-view v-slot="{ Component }">
          <transition name="fade-slide" mode="out-in">
            <component :is="Component" />
          </transition>
        </router-view>
      </el-main>
    </el-container>
  </el-container>
</template>

<style scoped>
.layout {
  height: 100vh;
  overflow: hidden;
}

.layout-aside {
  transition: width 0.2s;
  overflow: hidden;
}

.layout-right {
  /* el-container 默认 flex-direction: column，但 el-container 里有 el-header
     时会自动变成 column；这里显式写出来免得依赖它的隐式规则 */
  display: flex;
  flex-direction: column;
  min-width: 0;
}

.layout-header {
  padding: 0;
  background: #ffffff;
}

.layout-main {
  /* 内容区自己滚动，侧边栏和顶栏不动 —— 这样长表格滚动时不会把顶栏顶走 */
  overflow-y: auto;
  padding: 16px;
  background: #f5f7fa;
}

.fade-slide-enter-active,
.fade-slide-leave-active {
  transition: opacity 0.15s ease, transform 0.15s ease;
}
.fade-slide-enter-from {
  opacity: 0;
  transform: translateY(6px);
}
.fade-slide-leave-to {
  opacity: 0;
  transform: translateY(-6px);
}
</style>
