<script setup lang="ts">
import { ref } from 'vue'
import { useRouter } from 'vue-router'
import { useAuthStore } from '@/stores/auth'

const router = useRouter()
const auth = useAuthStore()

const username = ref('')
const password = ref('')
const confirmPassword = ref('')
const nickname = ref('')
const email = ref('')
const loading = ref(false)
const error = ref('')

async function handleRegister() {
  error.value = ''
  if (!username.value.trim() || !password.value || !confirmPassword.value) {
    error.value = '请填写完整注册信息'
    return
  }
  if (password.value.length < 6) {
    error.value = '密码长度至少 6 位'
    return
  }
  if (password.value !== confirmPassword.value) {
    error.value = '两次输入的密码不一致'
    return
  }

  loading.value = true
  try {
    await auth.register({
      username: username.value.trim(),
      password: password.value,
      nickname: nickname.value.trim() || undefined,
      email: email.value.trim() || undefined,
    })
    router.push('/')
  } catch (e: any) {
    error.value = e.message || '注册失败'
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
          <svg xmlns="http://www.w3.org/2000/svg" class="w-7 h-7" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M16 21v-2a4 4 0 0 0-4-4H6a4 4 0 0 0-4 4v2"/><circle cx="9" cy="7" r="4"/><path d="M22 11h-6"/><path d="M19 8v6"/></svg>
        </div>
        <h1 class="text-2xl font-semibold tracking-tight">创建账户</h1>
        <p class="text-sm text-muted-foreground mt-1">加入 FinView，追踪你的投资</p>
      </div>

      <!-- Form -->
      <form class="space-y-4" @submit.prevent="handleRegister">
        <div class="space-y-2">
          <label class="text-sm font-medium" for="username">用户名 *</label>
          <input
            id="username"
            v-model="username"
            type="text"
            autocomplete="username"
            placeholder="3-32 个字符"
            class="w-full px-3 py-2 rounded-md border bg-background text-sm focus:outline-none focus:ring-2 focus:ring-primary/40 focus:border-primary"
            :disabled="loading"
          />
        </div>

        <div class="space-y-2">
          <label class="text-sm font-medium" for="password">密码 *</label>
          <input
            id="password"
            v-model="password"
            type="password"
            autocomplete="new-password"
            placeholder="至少 6 位"
            class="w-full px-3 py-2 rounded-md border bg-background text-sm focus:outline-none focus:ring-2 focus:ring-primary/40 focus:border-primary"
            :disabled="loading"
          />
        </div>

        <div class="space-y-2">
          <label class="text-sm font-medium" for="confirmPassword">确认密码 *</label>
          <input
            id="confirmPassword"
            v-model="confirmPassword"
            type="password"
            autocomplete="new-password"
            placeholder="再次输入密码"
            class="w-full px-3 py-2 rounded-md border bg-background text-sm focus:outline-none focus:ring-2 focus:ring-primary/40 focus:border-primary"
            :disabled="loading"
          />
        </div>

        <div class="space-y-2">
          <label class="text-sm font-medium" for="nickname">昵称 <span class="text-muted-foreground font-normal">(可选)</span></label>
          <input
            id="nickname"
            v-model="nickname"
            type="text"
            placeholder="显示的名称"
            class="w-full px-3 py-2 rounded-md border bg-background text-sm focus:outline-none focus:ring-2 focus:ring-primary/40 focus:border-primary"
            :disabled="loading"
          />
        </div>

        <div class="space-y-2">
          <label class="text-sm font-medium" for="email">邮箱 <span class="text-muted-foreground font-normal">(可选)</span></label>
          <input
            id="email"
            v-model="email"
            type="email"
            autocomplete="email"
            placeholder="your@email.com"
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
          <span v-if="loading">注册中...</span>
          <span v-else>注册</span>
        </button>
      </form>

      <!-- Footer -->
      <p class="text-center text-sm text-muted-foreground mt-6">
        已有账户？
        <RouterLink to="/login" class="text-primary hover:underline font-medium">立即登录</RouterLink>
      </p>
    </div>
  </div>
</template>
