<script setup lang="ts">
import { ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useAuthStore } from '@/stores/auth'

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()

const username = ref('')
const password = ref('')
const loading = ref(false)
const error = ref('')

async function handleLogin() {
  error.value = ''
  if (!username.value.trim() || !password.value) {
    error.value = '请输入用户名和密码'
    return
  }
  loading.value = true
  try {
    await auth.login({ username: username.value.trim(), password: password.value })
    // 登录成功后跳转到原始请求页，fallback 到首页
    const redirect = (route.query.redirect as string) || '/'
    router.push(redirect)
  } catch (e: any) {
    error.value = e.message || '登录失败'
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <div class="min-h-screen flex items-center justify-center bg-background px-4">
    <div class="w-full max-w-sm">
      <!-- Logo & Title -->
      <div class="text-center mb-8">
        <div class="inline-flex items-center justify-center w-14 h-14 rounded-2xl bg-primary/10 text-primary mb-4">
          <svg xmlns="http://www.w3.org/2000/svg" class="w-7 h-7" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M3 3v18h18"/><path d="m19 9-5 5-4-4-3 3"/></svg>
        </div>
        <h1 class="text-2xl font-semibold tracking-tight">FinView</h1>
        <p class="text-sm text-muted-foreground mt-1">登录你的账户</p>
      </div>

      <!-- Form -->
      <form
        class="space-y-4"
        @submit.prevent="handleLogin"
      >
        <div class="space-y-2">
          <label class="text-sm font-medium" for="username">用户名</label>
          <input
            id="username"
            v-model="username"
            type="text"
            autocomplete="username"
            placeholder="请输入用户名"
            class="w-full px-3 py-2 rounded-md border bg-background text-sm focus:outline-none focus:ring-2 focus:ring-primary/40 focus:border-primary"
            :disabled="loading"
          />
        </div>

        <div class="space-y-2">
          <div class="flex items-center justify-between">
            <label class="text-sm font-medium" for="password">密码</label>
          </div>
          <input
            id="password"
            v-model="password"
            type="password"
            autocomplete="current-password"
            placeholder="请输入密码"
            class="w-full px-3 py-2 rounded-md border bg-background text-sm focus:outline-none focus:ring-2 focus:ring-primary/40 focus:border-primary"
            :disabled="loading"
          />
        </div>

        <p v-if="error" class="text-sm text-destructive">{{ error }}</p>

        <button
          type="submit"
          :disabled="loading"
          class="w-full py-2 px-4 rounded-md bg-primary text-primary-foreground text-sm font-medium hover:bg-primary/90 disabled:opacity-50 disabled:cursor-not-allowed transition-colors"
        >
          <span v-if="loading">登录中...</span>
          <span v-else>登录</span>
        </button>
      </form>

      <!-- Footer -->
      <p class="text-center text-sm text-muted-foreground mt-6">
        还没有账户？
        <RouterLink to="/register" class="text-primary hover:underline font-medium">注册新账户</RouterLink>
      </p>

      <!-- 提示信息 -->
      <div class="mt-6 p-3 rounded-lg bg-muted/50 border text-xs text-muted-foreground">
        <p class="font-medium text-foreground/80 mb-1">测试账号</p>
        <p>管理员：admin / admin123</p>
        <p>普通用户：demo / demo123</p>
      </div>
    </div>
  </div>
</template>
