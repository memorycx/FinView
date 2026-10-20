<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref } from 'vue'
import { Loader2, SlidersHorizontal, X } from '@lucide/vue'
import Button from '@/components/ui/Button.vue'
import { createAdjustment, deleteAdjustment } from '@/api'
import { formatCNY, frequencyLabel } from '@/lib/finance'
import type { AdjustmentPayload, Fund, InvestFrequency } from '@/api/types'

/**
 * 「调整定投」弹窗（定投详情页）：
 * 把当前计划的频率 / 每期金额改成新值，并**从下一个交易日起生效**。
 *
 * 实现上落一条 action=1 的调整记录（deferred=true）：
 * - 调整当天仍按原计划扣款（金额也不变），所以不会和今天已经发生的扣款打架；
 * - 后端 fold 从次日起使用新频率 / 金额；扣款只发生在交易日，实际就是「下一个交易日」；
 * - 历史记录 deferred=false，原有「只改金额当天生效」的口径不受影响。
 *
 * 弹窗由父级 v-if 控制挂载，每次打开都是全新实例。
 */
const props = defineProps<{
  fund: Fund
}>()

const emit = defineEmits<{
  /** 保存成功：父级关弹窗并重拉计划列表与组合走势（后端已重算序列） */
  saved: []
  /** 请求关闭：点 X / 点遮罩 / 按 Esc（保存中不响应） */
  close: []
}>()

/** 今天 YYYY-MM-DD（记录日期；生效日由后端按交易日历从次日开始算） */
function today(): string {
  return new Date().toISOString().slice(0, 10)
}

const frequency = ref<InvestFrequency>(props.fund.frequency ?? 'monthly')
/** 金额用字符串接输入框（type=number 的 v-model 会自动转 number，提交时统一 String） */
const amountInput = ref<string | number>(
  props.fund.amount == null ? '' : props.fund.amount,
)
const reason = ref('')
const note = ref('')

const loading = ref(false)
const error = ref('')

const frequencyOptions: { value: InvestFrequency; label: string; hint: string }[] = [
  { value: 'daily', label: '每日', hint: '每个交易日' },
  { value: 'weekly', label: '每周', hint: '每周一' },
  { value: 'monthly', label: '每月', hint: '每月 1 日' },
]

const inputClass =
  'w-full rounded-lg border border-border bg-background px-3.5 py-2.5 text-sm transition-colors placeholder:text-muted-foreground/60 focus:outline-none focus:ring-2 focus:ring-brand/30 focus:border-brand disabled:opacity-50 disabled:cursor-not-allowed'

const panel = ref<HTMLElement | null>(null)
let prevBodyOverflow = ''

/** 当前计划文案（弹窗顶部对照用） */
const currentPlan = `${frequencyLabel(frequencyOf(props.fund))} · ${formatCNY(props.fund.amount ?? 0)}`

function frequencyOf(fund: Fund): InvestFrequency {
  return fund.frequency ?? 'monthly'
}

function requestClose() {
  if (!loading.value) emit('close')
}

function onKeydown(e: KeyboardEvent) {
  if (e.key === 'Escape') requestClose()
}

onMounted(() => {
  document.addEventListener('keydown', onKeydown)
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
  const rawAmount = String(amountInput.value ?? '').trim()
  if (rawAmount === '') {
    error.value = '请填写每期定投金额'
    return
  }
  const amount = Number(rawAmount)
  if (!Number.isFinite(amount) || amount <= 0) {
    error.value = '定投金额必须大于 0'
    return
  }

  const payload: AdjustmentPayload = {
    code: props.fund.code,
    date: today(),
    action: 1,
    frequency: frequency.value,
    // 核心标记：当天仍按原计划扣，频率与金额从下一个交易日起生效
    deferred: true,
    amount,
    reason: reason.value.trim() || undefined,
    note: note.value.trim() || undefined,
  }

  loading.value = true
  try {
    const created = await createAdjustment(payload)
    // 旧后端不认识 deferred（会忽略该字段、按「当日生效」落库）。
    // 这时立刻撤销刚建的记录，避免把序列按错误口径改掉。
    if (created.deferred !== true) {
      try {
        await deleteAdjustment(String(created.adjustmentId))
      } catch {
        // 回滚失败也继续报错：至少让用户知道后端没更新
      }
      throw new Error('后端尚未更新（deferred 未生效），请重启后端后重试')
    }
    // 后端已重算该 code 的序列，父级重新拉计划列表与组合走势即可
    emit('saved')
  } catch (e) {
    error.value = e instanceof Error ? e.message : '调整失败，请稍后重试'
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <Teleport to="body">
    <div class="fixed inset-0 z-50 flex items-center justify-center p-4 sm:p-6">
      <div
        class="absolute inset-0 animate-in bg-black/45 backdrop-blur-[2px] duration-150 fade-in dark:bg-black/65"
        aria-hidden="true"
        @click="requestClose"
      />

      <div
        ref="panel"
        role="dialog"
        aria-modal="true"
        aria-labelledby="plan-adjust-title"
        tabindex="-1"
        class="relative flex max-h-[88vh] w-full max-w-lg animate-in flex-col overflow-hidden rounded-2xl border border-border bg-card text-card-foreground shadow-2xl outline-none duration-150 fade-in zoom-in-95"
      >
        <div class="flex shrink-0 items-start gap-4 border-b border-border px-5 py-4 sm:px-6">
          <div
            class="flex size-11 shrink-0 items-center justify-center rounded-xl bg-brand/10 text-brand"
          >
            <SlidersHorizontal class="size-5" />
          </div>
          <div class="min-w-0 flex-1 space-y-0.5">
            <h2 id="plan-adjust-title" class="text-lg font-semibold leading-tight">
              调整定投计划
            </h2>
            <p class="text-xs text-muted-foreground">
              {{ fund.name }} · 当前 {{ currentPlan }}
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

        <form class="flex min-h-0 flex-1 flex-col" @submit.prevent="submit">
          <div
            class="min-h-0 flex-1 space-y-5 overflow-y-auto overscroll-contain px-5 pb-5 pt-5 sm:px-6"
          >
            <!-- 新频率 -->
            <div class="space-y-2">
              <label class="text-sm font-medium text-foreground">定投频率</label>
              <div class="grid gap-3 sm:grid-cols-3">
                <button
                  v-for="opt in frequencyOptions"
                  :key="opt.value"
                  type="button"
                  :disabled="loading"
                  class="rounded-xl border p-3 text-left transition-all disabled:opacity-50 disabled:cursor-not-allowed"
                  :class="
                    frequency === opt.value
                      ? 'border-brand bg-brand/10 text-brand ring-1 ring-brand/30'
                      : 'border-border hover:bg-muted hover:border-foreground/20'
                  "
                  @click="frequency = opt.value"
                >
                  <p class="text-sm font-semibold leading-tight">{{ opt.label }}</p>
                  <p class="mt-0.5 text-xs leading-tight text-muted-foreground">
                    {{ opt.hint }}
                  </p>
                </button>
              </div>
            </div>

            <!-- 新金额 -->
            <div class="space-y-2">
              <label class="text-sm font-medium text-foreground" for="adjust-amount">
                每期金额
              </label>
              <div class="relative">
                <span
                  class="pointer-events-none absolute left-3 top-1/2 -translate-y-1/2 text-sm text-muted-foreground"
                  >¥</span
                >
                <input
                  id="adjust-amount"
                  v-model="amountInput"
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
              <p class="text-xs text-muted-foreground">
                买入时按该金额扣申购手续费（费率在「编辑」里配置），扣完的净额才计入本金。
              </p>
            </div>

            <div class="space-y-2">
              <label class="text-sm font-medium text-foreground" for="adjust-reason">
                变动原因
              </label>
              <input
                id="adjust-reason"
                v-model="reason"
                type="text"
                placeholder="选填，如：改为每周定投并调整金额"
                maxlength="255"
                :class="inputClass"
                :disabled="loading"
              />
            </div>

            <div class="space-y-2">
              <label class="text-sm font-medium text-foreground" for="adjust-note">备注</label>
              <textarea
                id="adjust-note"
                v-model="note"
                rows="2"
                placeholder="选填，补充说明"
                maxlength="512"
                :class="inputClass"
                :disabled="loading"
                class="resize-none"
              />
            </div>

            <!-- 生效口径：这是本功能的语义核心，放在保存按钮上方，避免用户误以为当天就变 -->
            <p
              class="rounded-xl border border-border/60 bg-muted/40 px-3.5 py-3 text-xs leading-relaxed text-muted-foreground"
            >
              调整当天仍按原计划（{{ currentPlan }}）扣款；新频率与金额从<span
                class="font-medium text-foreground"
                >下一个交易日</span
              >起生效。遇非交易日自动顺延；QDII 还需标的市场开市（在「编辑」里设置）。
            </p>
          </div>

          <div class="shrink-0 space-y-2 border-t border-border px-5 py-4 sm:px-6">
            <p v-if="error" class="text-sm text-destructive">{{ error }}</p>
            <div class="flex items-center justify-end gap-3">
              <Button type="button" variant="outline" :disabled="loading" @click="requestClose">
                取消
              </Button>
              <Button type="submit" :disabled="loading">
                <Loader2 v-if="loading" class="size-4 animate-spin" />
                <span>{{ loading ? '保存中…' : '保存调整' }}</span>
              </Button>
            </div>
          </div>
        </form>
      </div>
    </div>
  </Teleport>
</template>
