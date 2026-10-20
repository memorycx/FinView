<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, reactive, ref } from 'vue'
import type { Component } from 'vue'
import {
  ArrowDownLeft,
  ArrowUpRight,
  Coins,
  Landmark,
  Loader2,
  Pencil,
  Play,
  Square,
  TrendingUp,
  Wallet,
  X,
} from '@lucide/vue'
import Button from '@/components/ui/Button.vue'
import { createAdjustment } from '@/api'
import type { AdjustmentAction, AdjustmentPayload, InvestFrequency } from '@/api/types'

/**
 * 「添加资产」录入弹窗（投资看板 → 定投计划列表底部按钮）。
 *
 * 表单就是原来「录入数据」整页的那一张，语义不变：既能给新资产录第一条，
 * 也能给已有资产补一笔；现金走保留 code CASH（见后端 CashAsset）。
 * 与原整页录入的两点差别：
 * - 提交成功不再显示回执、也不能连续录入，直接 emit saved —— 父级关弹窗并刷新看板；
 * - 弹窗由父级 v-if 控制挂载，每次打开都是全新实例，表单状态天然干净，不用手动重置。
 */
const emit = defineEmits<{
  /** 提交成功：父级关弹窗，并重拉计划列表与组合走势（后端已重算序列） */
  saved: []
  /** 请求关闭：点 X / 点遮罩 / 按 Esc（提交中不响应，避免请求在飞就卸载） */
  close: []
}>()

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
  { value: 3, label: '买入', hint: '一次性资金流入', icon: ArrowDownLeft },
  { value: 4, label: '卖出', hint: '一次性资金流出', icon: ArrowUpRight },
]

const frequencyOptions: { value: InvestFrequency; label: string }[] = [
  { value: 'daily', label: '每日' },
  { value: 'weekly', label: '每周' },
  { value: 'monthly', label: '每月' },
]

/** 现金资产的保留编码，与后端 CashAsset.CODE 一致 */
const CASH_CODE = 'CASH'

/**
 * 现金的联动起点（与后端 finview.cash.link-from 一致，仅用于文案）。
 * 只有这一天及以后的定投扣款 / 买入 / 卖出才会动现金，之前的历史不追溯。
 */
const CASH_LINK_FROM_LABEL = '2026-10-08'

/** 录入的资产类型：基金细分股基/债基/货币基金（就是 asset.asset_type 的 equity / bond / money），外加现金 */
type AssetKind = 'equity' | 'bond' | 'money' | 'cash'

const assetType = ref<AssetKind>('equity')
/** 现金模式：只填日期 + 金额，动作/频率/编码都不需要 */
const isCash = computed(() => assetType.value === 'cash')

/**
 * 用户动过类型卡片的标记：**只有动过才把 assetType 传给后端**。
 * 不动 = 不改已有分类——给一只债基录常规买入时，不会被默认高亮的「股票基金」翻回股基；
 * 新建的资产行则由后端落默认 equity，不传与传 equity 结果一样。
 */
const assetTypeTouched = ref(false)

const assetTypeOptions: {
  value: AssetKind
  label: string
  hint: string
  icon: Component
}[] = [
  { value: 'equity', label: '股票基金', hint: '股基 · 定投 / 买卖', icon: TrendingUp },
  { value: 'bond', label: '债券基金', hint: '债基 · 计入安全资金', icon: Landmark },
  { value: 'money', label: '货币基金', hint: '货基 · 计入安全资金', icon: Coins },
  { value: 'cash', label: '现金', hint: '只填日期与金额', icon: Wallet },
]

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

/** 是否展示定投频率字段（仅基金 + 定投开始；现金模式整块都不该出现） */
const showFrequency = computed(() => !isCash.value && form.action === 1)

/** 统一输入框样式 */
const inputClass =
  'w-full rounded-lg border border-border bg-background px-3.5 py-2.5 text-sm transition-colors placeholder:text-muted-foreground/60 focus:outline-none focus:ring-2 focus:ring-brand/30 focus:border-brand disabled:opacity-50 disabled:cursor-not-allowed'

const panel = ref<HTMLElement | null>(null)
/** 打开前的 body overflow，关闭时还原（本项目没有别处动它，存一下更稳） */
let prevBodyOverflow = ''

function resetForm() {
  form.code = ''
  form.name = ''
  form.date = today()
  form.action = 1
  form.frequency = 'monthly'
  form.amount = ''
  form.reason = ''
  form.note = ''
  assetType.value = 'equity'
  assetTypeTouched.value = false
}

/**
 * 切换资产类型。点卡片就算「动过」（哪怕点的是已经高亮的那张）：这是用户对分类的显式表态，
 * 而股基 / 债基之间切换不动金额——金额的正负含义只有基金和现金不同，跨过去才必须清掉，
 * 免得 −500 被带进基金模式。
 */
function switchType(type: AssetKind) {
  assetTypeTouched.value = true
  if (type === assetType.value) return
  if ((type === 'cash') !== isCash.value) form.amount = ''
  assetType.value = type
  error.value = ''
}

/** 提交中不响应关闭，避免请求还在飞就卸载 */
function requestClose() {
  if (!loading.value) emit('close')
}

function onKeydown(e: KeyboardEvent) {
  if (e.key === 'Escape') requestClose()
}

onMounted(() => {
  document.addEventListener('keydown', onKeydown)
  // 锁背景滚动：桌面端看板本身锁死一屏，主要防的是移动端 / 未解锁视口下的整页滚动
  prevBodyOverflow = document.body.style.overflow
  document.body.style.overflow = 'hidden'
  panel.value?.focus()
})

onBeforeUnmount(() => {
  document.removeEventListener('keydown', onKeydown)
  document.body.style.overflow = prevBodyOverflow
})

async function submit() {
  error.value = ''

  if (!isCash.value && !form.code.trim()) return (error.value = '请填写资产编码')
  if (!form.date) return (error.value = '请选择记录日期')
  if (!isCash.value && form.action === 1 && !form.frequency) {
    return (error.value = '定投开始需选择定投频率')
  }
  const amount = Number(form.amount)
  if (!form.amount || Number.isNaN(amount) || amount === 0) {
    return (error.value = isCash.value ? '金额不能为 0' : '金额必须大于 0')
  }
  if (!isCash.value && amount < 0) {
    return (error.value = '金额必须大于 0')
  }

  const name = form.name.trim()
  const payload: AdjustmentPayload = isCash.value
    ? {
        // 现金是保留 code（见后端 CashAsset）：只需要日期和金额。
        // 方向用 action 表达 —— 正数进账、负数出账且存绝对值，
        // 全库的约定是「金额恒正、方向看 action」，后端的 @Positive 也不用放开
        code: CASH_CODE,
        name: '现金',
        date: form.date,
        action: amount > 0 ? 3 : 4,
        amount: Math.abs(amount),
        note: form.note.trim() || undefined,
      }
    : {
        code: form.code.trim(),
        // 选填：留空则后端不动资产名（新建的资产继续用编码当名字）
        name: name || undefined,
        // 动过类型卡片才带（这里 kind 已经是 equity / bond / money）：不传 = 后端不改已有分类
        assetType: assetTypeTouched.value ? (assetType.value as 'equity' | 'bond' | 'money') : undefined,
        date: form.date,
        action: form.action,
        frequency: form.action === 1 ? form.frequency : null,
        amount,
        reason: form.reason.trim() || undefined,
        note: form.note.trim() || undefined,
      }

  loading.value = true
  try {
    await createAdjustment(payload)
    // 不显示回执：父级收到就关弹窗、刷新列表与组合走势
    emit('saved')
  } catch (e) {
    error.value = e instanceof Error ? e.message : '录入失败，请稍后重试'
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <Teleport to="body">
    <div class="fixed inset-0 z-50 flex items-center justify-center p-4 sm:p-6">
      <!-- 遮罩：点击关闭（提交中不响应） -->
      <div
        class="absolute inset-0 animate-in bg-black/45 backdrop-blur-[2px] duration-150 fade-in dark:bg-black/65"
        aria-hidden="true"
        @click="requestClose"
      />

      <!-- 面板：头部固定，表单区超高时内部滚动 -->
      <div
        ref="panel"
        role="dialog"
        aria-modal="true"
        aria-labelledby="entry-dialog-title"
        tabindex="-1"
        class="relative flex max-h-[88vh] w-full max-w-2xl animate-in flex-col overflow-hidden rounded-2xl border border-border bg-card text-card-foreground shadow-2xl outline-none duration-150 fade-in zoom-in-95"
      >
        <div class="flex shrink-0 items-start gap-4 border-b border-border px-5 py-4 sm:px-6">
          <div
            class="flex size-11 shrink-0 items-center justify-center rounded-xl bg-brand/10 text-brand"
          >
            <Pencil class="size-5" />
          </div>
          <div class="min-w-0 flex-1 space-y-0.5">
            <h2 id="entry-dialog-title" class="text-lg font-semibold leading-tight">录入数据</h2>
            <p class="text-xs text-muted-foreground">
              新增调整记录：定投开始 / 结束、买入 / 卖出，或记一笔现金。
            </p>
          </div>
          <Button
            variant="ghost"
            size="icon-sm"
            class="shrink-0 text-muted-foreground"
            aria-label="关闭"
            :disabled="loading"
            @click="requestClose"
          >
            <X class="size-4" />
          </Button>
        </div>

        <form
          class="min-h-0 flex-1 space-y-5 overflow-y-auto overscroll-contain px-5 pb-5 pt-5 sm:px-6 sm:pb-6"
          @submit.prevent="submit"
        >
          <!-- 资产类型：基金细分股基/债基（进「资产配置结构」的两个桶），现金只填日期 + 金额 -->
          <div class="space-y-2">
            <label class="text-sm font-medium text-foreground">资产类型</label>
            <div class="grid gap-3 sm:grid-cols-3">
              <button
                v-for="opt in assetTypeOptions"
                :key="opt.value"
                type="button"
                :disabled="loading"
                class="flex items-center gap-3 rounded-xl border p-3.5 text-left transition-all disabled:opacity-50 disabled:cursor-not-allowed"
                :class="
                  assetType === opt.value
                    ? 'border-brand bg-brand/10 text-brand ring-1 ring-brand/30'
                    : 'border-border hover:bg-muted hover:border-foreground/20'
                "
                @click="switchType(opt.value)"
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

          <!-- 动作（2×2 选择卡），仅基金 -->
          <div v-if="!isCash" class="space-y-2">
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

          <!-- 资产名称（写进 asset.name，选填），仅基金 -->
          <div v-if="!isCash" class="space-y-2">
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

          <!-- 资产编码 + 记录日期（现金没有编码，日期独占一行） -->
          <div :class="isCash ? 'grid gap-4' : 'grid gap-4 sm:grid-cols-2 sm:gap-5'">
            <div v-if="!isCash" class="space-y-2">
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

          <!-- 金额 + 频率（频率仅定投开始；现金只需要金额） -->
          <div :class="isCash ? 'grid gap-4' : 'grid gap-4 sm:grid-cols-2 sm:gap-5'">
            <div class="space-y-2">
              <label class="text-sm font-medium text-foreground" for="amount">
                {{ isCash ? '金额（正数进账 / 负数出账）' : '金额' }}
              </label>
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
                  :min="isCash ? undefined : 0"
                  :placeholder="isCash ? '如 5000 或 -1200' : '0.00'"
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

          <!-- 现金口径提示：联动起点之后才自动扣，历史不追溯 -->
          <p
            v-if="isCash"
            class="rounded-xl border border-border/60 bg-muted/40 px-3.5 py-3 text-xs leading-relaxed text-muted-foreground"
          >
            现金从 {{ CASH_LINK_FROM_LABEL }} 起自动跟随定投扣款 / 买入 / 卖出变化（买入额外扣手续费），
            之前的历史不追溯；余额计入「我的资产」，不进入投资看板。
          </p>

          <!-- 变动原因，仅基金 -->
          <div v-if="!isCash" class="space-y-2">
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
      </div>
    </div>
  </Teleport>
</template>
