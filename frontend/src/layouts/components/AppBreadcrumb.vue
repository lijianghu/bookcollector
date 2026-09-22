<script setup lang="ts">
import { computed } from 'vue'
import { useRoute } from 'vue-router'
import { findMenu } from '@/config/menu'

/**
 * 面包屑。
 *
 * 目前只有两级（首页 / 当前页），因为路由表是平的、没有嵌套菜单。
 * 之所以还是做成独立组件而不是直接写在 Navbar 里：
 * S6 的图书详情、任务详情如果用子路由，面包屑就要变成三级，
 * 那时候只改这一个文件就够了。
 */

const route = useRoute()
const current = computed(() => findMenu(route.path))
</script>

<template>
  <el-breadcrumb separator="/">
    <el-breadcrumb-item :to="{ path: '/dashboard' }">首页</el-breadcrumb-item>
    <el-breadcrumb-item v-if="current && current.path !== '/dashboard'">
      {{ current.title }}
    </el-breadcrumb-item>
  </el-breadcrumb>
</template>
