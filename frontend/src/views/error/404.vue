<script setup lang="ts">
import { useRouter } from 'vue-router'

/** 404。路由表里挂在 `/:pathMatch(.*)*` 上，所以任何拼错的地址都会落到这里 */
const router = useRouter()
</script>

<template>
  <div class="not-found">
    <el-result icon="warning" title="404" sub-title="没有这个页面">
      <template #extra>
        <el-button type="primary" @click="router.replace('/dashboard')">回到仪表盘</el-button>
      </template>
    </el-result>
  </div>
</template>

<style scoped>
.not-found {
  display: flex;
  align-items: center;
  justify-content: center;
  min-height: 100vh;
  background: #f5f7fa;
}
</style>
