<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, type FormInstance, type FormRules } from 'element-plus'
import { useUserStore } from '@/store/user'
import { resetSessionInvalidFlag } from '@/api/request'

/**
 * 登录页。
 *
 * <h3>为什么预填了账号密码</h3>
 * 第一期是本地单机、写死凭据（`bookcollector.auth.*`）。预填省掉每次手输，
 * 但**不隐藏**这是写死的 —— 页面上直接写出来，免得以后误以为有真实鉴权。
 * 真要改凭据就改 `backend/src/main/resources/application.yml`。
 *
 * <h3>登录成功后的去向</h3>
 * 优先回 `?redirect=` 指的地方（路由守卫在踢人时写进去的），
 * 没有就进仪表盘。这样「点开图书库 → 被踢去登录 → 登录后」能回到图书库，
 * 而不是每次都掉到首页。
 */

const route = useRoute()
const router = useRouter()
const userStore = useUserStore()

const formRef = ref<FormInstance>()
const loading = ref(false)

const form = reactive({
  username: 'admin',
  password: 'admin123',
})

const rules: FormRules = {
  username: [{ required: true, message: '请输入账号', trigger: 'blur' }],
  password: [{ required: true, message: '请输入密码', trigger: 'blur' }],
}

/** 从 ?redirect= 里取目标路径。取不到（或被改成了外部地址）就回仪表盘 */
function resolveRedirect(): string {
  const raw = route.query.redirect
  const value = Array.isArray(raw) ? raw[0] : raw
  if (typeof value !== 'string' || value === '') {
    return '/dashboard'
  }
  // 只接受站内绝对路径，避免开放重定向
  if (!value.startsWith('/') || value.startsWith('//')) {
    return '/dashboard'
  }
  return value
}

async function submit(): Promise<void> {
  if (!formRef.value) {
    return
  }
  const valid = await formRef.value.validate().catch(() => false)
  if (!valid) {
    return
  }

  loading.value = true
  try {
    await userStore.login({ username: form.username, password: form.password })
    // 登录成功 → 新的会话，允许下一次 4100 再触发跳转
    resetSessionInvalidFlag()
    ElMessage.success('登录成功')
    router.replace(resolveRedirect())
  } catch {
    // 拦截器已经弹过后端给的 message（「用户名或密码错误」），这里不重复提示
  } finally {
    loading.value = false
  }
}

onMounted(() => {
  // 进登录页说明上一次会话已经结束，把「已处理过 4100」的标记清掉，
  // 否则登录后再遇到 token 失效不会跳转
  resetSessionInvalidFlag()
})
</script>

<template>
  <div class="login-page">
    <el-card class="login-card" shadow="always">
      <div class="brand">
        <el-icon class="brand-icon"><Notebook /></el-icon>
        <h1 class="brand-title">微信读书采集后台</h1>
        <p class="brand-sub">本地单机版 · 图书采集与浏览</p>
      </div>

      <el-form
        ref="formRef"
        :model="form"
        :rules="rules"
        label-position="top"
        @submit.prevent="submit"
      >
        <el-form-item label="账号" prop="username">
          <el-input v-model="form.username" size="large" placeholder="admin" clearable>
            <template #prefix>
              <el-icon><User /></el-icon>
            </template>
          </el-input>
        </el-form-item>

        <el-form-item label="密码" prop="password">
          <el-input
            v-model="form.password"
            type="password"
            size="large"
            placeholder="admin123"
            show-password
            @keyup.enter="submit"
          >
            <template #prefix>
              <el-icon><Lock /></el-icon>
            </template>
          </el-input>
        </el-form-item>

        <el-button
          type="primary"
          size="large"
          class="submit-btn"
          :loading="loading"
          @click="submit"
        >
          {{ loading ? '登录中…' : '登 录' }}
        </el-button>
      </el-form>

      <el-alert type="info" :closable="false" class="tip">
        <template #title>
          第一期不做权限体系：账号密码写死在
          <code>application.yml</code> 的 <code>bookcollector.auth</code> 下，已预填。
        </template>
      </el-alert>
    </el-card>
  </div>
</template>

<style scoped>
.login-page {
  display: flex;
  align-items: center;
  justify-content: center;
  min-height: 100vh;
  background: linear-gradient(160deg, #eef3fb 0%, #f5f7fa 55%, #eaf0f9 100%);
}

.login-card {
  width: 400px;
  border-radius: 8px;
}

.brand {
  text-align: center;
  margin-bottom: 20px;
}
.brand-icon {
  font-size: 34px;
  color: #409eff;
}
.brand-title {
  margin: 8px 0 4px;
  font-size: 19px;
  font-weight: 600;
  color: #303133;
}
.brand-sub {
  margin: 0;
  font-size: 12px;
  color: #909399;
}

.submit-btn {
  width: 100%;
  margin-top: 4px;
  letter-spacing: 2px;
}

.tip {
  margin-top: 18px;
}
.tip code {
  background: #f5f7fa;
  padding: 1px 5px;
  border-radius: 3px;
  font-family: Consolas, Monaco, 'Courier New', monospace;
  font-size: 12px;
}
</style>
