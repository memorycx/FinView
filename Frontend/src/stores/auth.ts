import { defineStore } from 'pinia'
import { ref, computed } from 'vue'

export interface LoginPayload {
  username: string
  password: string
}

export interface RegisterPayload extends LoginPayload {
  email?: string
  nickname?: string
}

export interface UserInfo {
  token: string | null
  userId: number | null
  username: string | null
  nickname: string | null
  email: string | null
  role: string | null
}

const TOKEN_KEY = 'finview_token'
const USER_KEY = 'finview_user'
const USE_MOCK = import.meta.env.VITE_USE_MOCK !== 'false'

const MOCK_USERS: Record<string, UserInfo & { password: string }> = {
  admin: {
    password: 'admin123',
    token: 'mock-token-admin',
    userId: 1,
    username: 'admin',
    nickname: '管理员',
    email: 'admin@finview.local',
    role: 'admin',
  },
  demo: {
    password: 'demo123',
    token: 'mock-token-demo',
    userId: 2,
    username: 'demo',
    nickname: '普通用户',
    email: 'demo@finview.local',
    role: 'user',
  },
}

function loadStoredUser(): UserInfo {
  try {
    const token = localStorage.getItem(TOKEN_KEY)
    const raw = localStorage.getItem(USER_KEY)
    if (token && raw) {
      const parsed = JSON.parse(raw)
      return { token, ...parsed }
    }
  } catch {}
  return { token: null, userId: null, username: null, nickname: null, email: null, role: null }
}

export const useAuthStore = defineStore('auth', () => {
  const user = ref<UserInfo>(loadStoredUser())

  const isAuthenticated = computed(() => !!user.value.token)

  /** 登录：直接 fetch，不走 mock（认证必须走真实后端） */
  async function login(payload: LoginPayload) {
    const data = await callAuthApi('/auth/login', payload)
    applyUser(data)
    return data
  }

  /** 注册 */
  async function register(payload: RegisterPayload) {
    const data = await callAuthApi('/auth/register', payload)
    applyUser(data)
    return data
  }

  /** 登出：清除内存和本地存储 */
  function logout() {
    user.value = { token: null, userId: null, username: null, nickname: null, email: null, role: null }
    localStorage.removeItem(TOKEN_KEY)
    localStorage.removeItem(USER_KEY)
  }

  /** 获取当前 token（给 http.ts 用） */
  function getToken() {
    return user.value.token
  }

  function applyUser(data: UserInfo) {
    user.value = { ...data }
    if (data.token) localStorage.setItem(TOKEN_KEY, data.token)
    const saveData: any = { ...data }
    delete saveData.token
    localStorage.setItem(USER_KEY, JSON.stringify(saveData))
  }

  /** 直接 fetch 调用认证端点（绕过 mock） */
  async function callAuthApi(url: string, payload: unknown): Promise<UserInfo> {
    if (USE_MOCK) {
      const credentials = payload as LoginPayload
      if (url === '/auth/login') {
        const mockUser = MOCK_USERS[credentials.username]
        if (!mockUser || mockUser.password !== credentials.password) {
          throw new Error('用户名或密码错误')
        }
        const { password: _password, ...user } = mockUser
        return user
      }
      throw new Error('Mock 注册接口未实现')
    }

    const base = import.meta.env.VITE_API_BASE_URL ?? '/api'
    let resp: Response
    try {
      resp = await fetch(`${base}${url}`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(payload),
      })
    } catch {
      throw new Error(`无法连接后端服务：${base}，请确认后端已启动`)
    }
    const body = await resp.json()
    if (body.code !== 0) throw new Error(body.message || '请求失败')
    return body.data as UserInfo
  }

  return { user, isAuthenticated, login, register, logout, getToken }
})
