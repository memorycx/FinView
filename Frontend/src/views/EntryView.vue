<script setup lang="ts">
import { computed, reactive, ref } from 'vue'
import type { Component } from 'vue'
import {
  ArrowDownLeft,
  ArrowUpRight,
  CheckCircle2,
  Loader2,
  Pencil,
  Play,
  Square,
} from '@lucide/vue'
import Card from '@/components/ui/card/Card.vue'
import Button from '@/components/ui/Button.vue'
import { createAdjustment } from '@/api'
import { formatCNY } from '@/lib/finance'
import type {
  Adjustment,
  AdjustmentAction,
  AdjustmentPayload,
  InvestFrequency,
} from '@/api/types'

/** 今天 YYYY-MM-DD */
function today(): string {
  return new Date().toISOString().slice(0, 10)
}

/** 动作选项 */
const actionOptions: {
  value: AdjustmentAction
  label: string
  hint: string
  icon: Component
}[] = [
  { value: 1, label: '定投开始', hint: '开启周期定投', icon: Play },
  { value: 2, label: '结束定投', hint: '停止周期定投', icon: Square },
  { value: 3, label: '一笔收入', hint: '一次性资金流入', icon: ArrowDownLeft },
  { value: 4, label: '一笔支出', hint: '一次性资金流出', icon: ArrowUpRight },
]

const frequencyOptions: { value: InvestFrequency; label: string }[] = [
  { value: 'daily', label: '每日' },
  { value: 'weekly', label: '每周' },
  { value: 'monthly', label: '每月' },
]

const ACTION_LABELS: Record<AdjustmentAction, string> = {
  1: '定投开始',
  2: '结束定投',
  3: '一笔收入',
  4: '一笔支出',
}

const FREQ_LABELS: Record<InvestFrequency, string> = {
  daily: '每日',
  weekly: '每周',
  monthly: '每月',
}

interface EntryForm {
  code: string
  name: string
  date: string
  action: AdjustmentAction
  frequency: InvestFrequency
  amount: string
  reason: string
  note: string
}

const form = reactive<EntryForm>({
  code: '',
  name: '',
  date: today(),
  action: 1,
  frequency: 'monthly',
  amount: '',
  reason: '',
  note: '',
})

const loading = ref(false)
const error = ref('')
const success = ref<Adjustment | null>(null)
/** 提交成功时记下当时填的资产名——表单会被清空，回执里还要显示它 */
const successName = ref('')

/** 是否展示定投频率字段（仅定投开始） */
const showFrequency = computed(() => form.action === 1)

/** 统一输入框样式 */
const inputClass =
  'w-full rounded-lg border border-border bg-background px-3.5 py-2.5 text-sm transition-colors placeholder:text-muted-foreground/60 focus:outline-none focus:ring-2 focus:ring-brand/30 focus:border-brand disabled:opacity-50 disabled:cursor-not-allowed'

function resetForm() {
  form.code = ''
  form.name = ''
  form.date = today()
  form.action = 1
  form.frequency = 'monthly'
  form.amount = ''
  form.reason = ''
  form.note = ''
}

async function submit() {
  error.value = ''
  success.value = null

  if (!form.code.trim()) return (error.value = '请填写资产编码')
  if (!form.date) return (error.value = '请选择记录日期')
  if (form.action === 1 && !form.frequency) {
    return (error.value = '定投开始需选择定投频率')
  }
  const amount = Number(form.amount)
  if (!form.amount || Number.isNaN(amount) || amount <= 0) {
    return (error.value = '金额必须大于 0')
  }

  const name = form.name.trim()
  const payload: AdjustmentPayload = {
    code: form.code.trim(),
    // 选填：留空则后端不动资产名（新建的资产继续用编码当名字）
    name: name || undefined,
    date: form.date,
    action: form.action,
    frequency: form.action === 1 ? form.frequency : null,
    amount,
    reason: form.reason.trim() || undefined,
    note: form.note.trim() || undefined,
  }

  loading.value = true
  try {
    success.value = await createAdjustment(payload)
    successName.value = name
    // 保留日期，清空其余字段便于继续录入
    form.code = ''
    form.name = ''
    form.amount = ''
    form.reason = ''
    form.note = ''
  } catch (e: any) {
    error.value = e?.message || '录入失败，请稍后重试'
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <div class="mx-auto w-full max-w-[1400px] px-4 pt-6 pb-[18px] sm:px-10 lg:pt-4">
    <!-- 与看板返回按钮对齐的占位 -->
    <div class="mb-4 shrink-0 lg:mb-3" aria-hidden="true">
      <div class="h-7 invisible">&nbsp;</div>
    </div>

    <!-- 标题区 -->
    <div class="mb-6 space-y-1.5">
      <h1 class="text-3xl font-bold tracking-tight">录入数据</h1>
      <p class="text-sm text-muted-foreground">
        记录每一笔定投调整与收支流水，让你的投资轨迹更完整。
      </p>
    </div>

    <!-- 表单卡片 -->
    <Card class="mx-auto w-full max-w-2xl">
      <div class="flex items-start gap-4 px-5 pt-5 sm:px-6 sm:pt-6">
        <div
          class="flex size-11 shrink-0 items-center justify-center rounded-xl bg-brand/10 text-brand"
        >
          <Pencil class="size-5" />
        </div>
        <div class="space-y-1">
          <h2 class="text-lg font-semibold leading-tight">新增调整记录</h2>
          <p class="text-xs text-muted-foreground">
            其余字段对应后端 <code class="font-mono">adjustments</code> 表；
            <code class="font-mono">资产名称</code> 写进 <code class="font-mono">asset</code> 表的 name 列。
          </p>
        </div>
      </div>

      <form class="space-y-5 px-5 pb-5 pt-5 sm:px-6 sm:pb-6" @submit.prevent="submit">
        <!-- 动作（2×2 选择卡） -->
        <div class="space-y-2">
          <label class="text-sm font-medium text-foreground">动作</label>
          <div class="grid grid-cols-2 gap-3">
            <button
              v-for="opt in actionOptions"
              :key="opt.value"
              type="button"
              :disabled="loading"
              class="flex items-center gap-3 rounded-xl border p-3.5 text-left transition-all disabled:opacity-50 disabled:cursor-not-allowed"
              :class="
                form.action === opt.value
                  ? 'border-brand bg-brand/10 text-brand ring-1 ring-brand/30'
                  : 'border-border hover:bg-muted hover:border-foreground/20'
              "
              @click="form.action = opt.value"
            >
              <component :is="opt.icon" class="size-5 shrink-0" />
              <div class="min-w-0">
                <p class="text-sm font-semibold leading-tight">{{ opt.label }}</p>
                <p class="mt-0.5 text-xs leading-tight text-muted-foreground">
                  {{ opt.hint }}
                </p>
              </div>
            </button>
          </div>
        </div>

        <!-- 资产名称（写进 asset.name，选填） -->
        <div class="space-y-2">
          <label class="text-sm font-medium text-foreground" for="name">资产名称</label>
          <input
            id="name"
            v-model="form.name"
            type="text"
            placeholder="选填，如：沪深300指数增强。留空则资产继续用编码显示"
            maxlength="100"
            :class="inputClass"
            :disabled="loading"
          />
        </div>

        <!-- 资产编码 + 记录日期 -->
        <div class="grid gap-4 sm:grid-cols-2 sm:gap-5">
          <div class="space-y-2">
            <label class="text-sm font-medium text-foreground" for="code">资产编码</label>
            <input
              id="code"
              v-model="form.code"
              type="text"
              placeholder="如 005827"
              maxlength="64"
              :class="inputClass"
              :disabled="loading"
            />
          </div>
          <div class="space-y-2">
            <label class="text-sm font-medium text-foreground" for="date">记录日期</label>
            <input
              id="date"
              v-model="form.date"
              type="date"
              :class="inputClass"
              :disabled="loading"
            />
          </div>
        </div>

        <!-- 金额 + 频率（仅定投开始） -->
        <div class="grid gap-4 sm:grid-cols-2 sm:gap-5">
          <div class="space-y-2">
            <label class="text-sm font-medium text-foreground" for="amount">金额</label>
            <div class="relative">
              <span
                class="pointer-events-none absolute left-3 top-1/2 -translate-y-1/2 text-sm text-muted-foreground"
                >¥</span
              >
              <input
                id="amount"
                v-model="form.amount"
                type="number"
                inputmode="decimal"
                step="0.01"
                min="0"
                placeholder="0.00"
                :class="inputClass"
                :disabled="loading"
                class="pl-7"
              />
            </div>
          </div>
          <div v-if="showFrequency" class="space-y-2">
            <label class="text-sm font-medium text-foreground" for="frequency">定投频率</label>
            <select
              id="frequency"
              v-model="form.frequency"
              :class="inputClass"
              :disabled="loading"
            >
              <option v-for="opt in frequencyOptions" :key="opt.value" :value="opt.value">
                {{ opt.label }}
              </option>
            </select>
          </div>
        </div>

        <!-- 变动原因 -->
        <div class="space-y-2">
          <label class="text-sm font-medium text-foreground" for="reason">变动原因</label>
          <input
            id="reason"
            v-model="form.reason"
            type="text"
            placeholder="选填，如：估值偏低加仓"
            maxlength="255"
            :class="inputClass"
            :disabled="loading"
          />
        </div>

        <!-- 备注 -->
        <div class="space-y-2">
          <label class="text-sm font-medium text-foreground" for="note">备注</label>
          <textarea
            id="note"
            v-model="form.note"
            rows="3"
            placeholder="选填，补充说明"
            maxlength="512"
            :class="inputClass"
            :disabled="loading"
            class="resize-none"
          />
        </div>

        <!-- 成功提示 -->
        <div
          v-if="success"
          class="flex items-start gap-3 rounded-xl border border-brand/30 bg-brand/10 p-4"
        >
          <CheckCircle2 class="mt-0.5 size-5 shrink-0 text-brand" />
          <div class="min-w-0 flex-1 space-y-1.5">
            <p class="text-sm font-semibold text-brand">录入成功</p>
            <p class="text-xs text-muted-foreground">
              <template v-if="successName">{{ successName }} · </template>
              <span class="font-mono">{{ success.code }}</span>
              · {{ ACTION_LABELS[success.action] }}
              <template v-if="success.frequency">
                · {{ FREQ_LABELS[success.frequency as InvestFrequency] }}定投
              </template>
              · {{ formatCNY(success.amount) }}
              · {{ success.date }}
            </p>
            <p class="text-xs text-muted-foreground">可继续录入下一条记录。</p>
          </div>
          <Button type="button" variant="ghost" size="sm" :disabled="loading" @click="resetForm">
            清空
          </Button>
        </div>

        <!-- 错误提示 -->
        <p v-if="error" class="text-sm text-destructive">{{ error }}</p>

        <!-- 操作按钮 -->
        <div class="flex items-center justify-end gap-3 pt-1">
          <Button type="button" variant="outline" :disabled="loading" @click="resetForm">
            重置
          </Button>
          <Button type="submit" :disabled="loading">
            <Loader2 v-if="loading" class="size-4 animate-spin" />
            <span>{{ loading ? '提交中…' : '提交记录' }}</span>
          </Button>
        </div>
      </form>
    </Card>
  </div>
</template>
