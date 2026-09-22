<script setup lang="ts">
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { useAppStore } from '@/store/app'
import { useUserStore } from '@/store/user'
import AppBreadcrumb from './AppBreadcrumb.vue'

/**
 * 顶栏：折叠按钮 + 面包屑 + 刷新 + 用户下拉。
 *
 * 折叠按钮放在这里而不是侧边栏里，是因为侧边栏折叠后宽度只剩 64px，
 * 按钮放在里面会跟着一起挤 —— 而它恰恰是「展开」的唯一入口。
 */

const router = useRouter()
const appStore = useAppStore()
const userStore = useUserStore()

function handleToggle(): void {
  appStore.toggleSidebar()
}

async function handleLogout(): Promise<void> {
  try {
    await ElMessageBox.confirm('确定要退出登录吗？', '退出登录', {
      confirmButtonText: '退出',
      cancelButtonText: '取消',
      type: 'warning',
    })
  } catch {
    // 用户点了取消 —— ElMessageBox 用 reject 表示取消，不是错误
    return
  }

  await userStore.logout()
  ElMessage.success('已退出登录')
  router.push('/login')
}
</script>

<template>
  <div class="navbar">
    <el-button text class="toggle" @click="handleToggle">
      <el-icon :size="18">
        <component :is="appStore.sidebarCollapsed ? 'Expand' : 'Fold'" />
      </el-icon>
    </el-button>

    <AppBreadcrumb />

    <div class="spacer" />

    <el-tooltip content="重新加载当前页数据" placement="bottom">
      <el-button text class="icon-btn" @click="router.go(0)">
        <el-icon :size="16"><Refresh /></el-icon>
      </el-button>
    </el-tooltip>

    <el-dropdown trigger="click">
      <span class="user">
        <el-icon><User /></el-icon>
        <span class="user-name">{{ userStore.displayName }}</span>
        <el-icon class="arrow"><ArrowDown /></el-icon>
      </span>
      <template #dropdown>
        <el-dropdown-menu>
          <el-dropdown-item disabled>
            <span class="dd-label">账号</span>
            <span class="dd-value">{{ userStore.username || '-' }}</span>
          </el-dropdown-item>
          <el-dropdown-item disabled>
            <span class="dd-label">角色</span>
            <span class="dd-value">{{ userStore.roles.join(', ') || '-' }}</span>
          </el-dropdown-item>
          <el-dropdown-item divided @click="handleLogout">
            <el-icon><SwitchButton /></el-icon>
            退出登录
          </el-dropdown-item>
        </el-dropdown-menu>
      </template>
    </el-dropdown>
  </div>
</template>

<style scoped>
.navbar {
  display: flex;
  align-items: center;
  gap: 12px;
  height: 100%;
  padding: 0 16px;
  background: #ffffff;
  border-bottom: 1px solid #e4e7ed;
}

.toggle {
  padding: 6px;
  color: #606266;
}

.spacer {
  flex: 1;
}

.icon-btn {
  padding: 6px;
  color: #606266;
}

.user {
  display: flex;
  align-items: center;
  gap: 5px;
  padding: 4px 8px;
  border-radius: 4px;
  cursor: pointer;
  color: #303133;
  font-size: 14px;
  outline: none;
}
.user:hover {
  background: #f5f7fa;
}
.user-name {
  max-width: 120px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.arrow {
  font-size: 12px;
  color: #909399;
}

.dd-label {
  color: #909399;
  margin-right: 10px;
}
.dd-value {
  color: #303133;
}
</style>
