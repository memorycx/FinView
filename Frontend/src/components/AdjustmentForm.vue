<script setup lang="ts">
import { reactive, ref } from 'vue'
import { Loader2 } from '@lucide/vue'
import Button from '@/components/ui/Button.vue'
import { createAdjustment, updateAdjustment } from '@/api'
import type {
  Adjustment,
  AdjustmentAction,
  AdjustmentPayload,
  InvestFrequency,
} from '@/api/types'

/**
 * 调整记录的内嵌编辑 / 新增表单（定投详情页用）：
 * 字段与录入弹窗（EntryDialog）一致，只是收成卡片里的纵向布局，塞进右侧窄列（最窄约 244px）。
 *
 * 只服务已有资产：编码固定为当前计划、不提供修改入口——在「某只基金」的详情里改记录，
 * 本来就不该把它挪到别的资产上；给新资产录第一条走投资看板的「添加资产」弹窗。
 */
const props = defineProps<{
  /** 当前计划的资产编码，提交时原样带上 */
  code: string
  /**
   * 编辑时传库里的原始记录（由父级 GET /adjustments/{id} 拉好），新增传 null。
   * 列表里的时间线项不能直接回填：action 是文案、结束定投的金额被置空了。
   */
  record: Adjustment | null
}>()

const emit = defineEmits<{
  /** 保存成功：父级关表单并重拉计划列表与组合走势 */
  saved: []
  cancel: []
}>()

const actionOptions: { value: AdjustmentAction; label: string }[] = [
  { value: 1, label: '定投开始' },
  { value: 2, label: '结束定投' },
  { value: 3, label: '买入' },
  { value: 4, label: '卖出' },
]

const frequencyOptions: { value: InvestFrequency; label: string }[] = [
  { value: 'daily', label: '每日' },
  { value: 'weekly', label: '每周' },
  { value: 'monthly', label: '每月' },
]

/** 今天 YYYY-MM-DD */
function today(): string {
  return new Date().toISOString().slice(0, 10)
}

const form = reactive({
  date: props.record?.date ?? today(),
  action: (props.record?.action ?? 1) as AdjustmentAction,
  // 结束定投等记录的 frequency 是 null，兜底成 monthly（只有 action=1 会展示并提交它）
  frequency: ((props.record?.frequency as InvestFrequency | null) ?? 'monthly') as InvestFrequency,
  // 只有 action=1 有意义：勾上 = 当天仍按原计划扣，频率与金额从下一交易日起生效。
  // 编辑已有记录时保留库里的值，避免把「调整定投」改回当日生效。
  deferred: props.record?.deferred ?? false,
  amount: props.record ? String(props.record.amount) : '',
  reason: props.record?.reason ?? '',
  note: props.record?.note ?? '',
})

const saving = ref(false)
const error = ref('')

const inputClass =
  'w-full rounded-lg border border-border bg-background px-3 py-2 text-sm transition-colors placeholder:text-muted-foreground/60 focus:outline-none focus:ring-2 focus:ring-brand/30 focus:border-brand disabled:opacity-50 disabled:cursor-not-allowed'

async function submit() {
  if (saving.value) return
  error.value = ''
  if (!form.date) return (error.value = '请选择记录日期')
  if (form.action === 1 && !form.frequency) {
    return (error.value = '定投开始需选择定投频率')
  }
  const amount = Number(form.amount)
  if (!form.amount || Number.isNaN(amount) || amount <= 0) {
    return (error.value = '金额必须大于 0')
  }

  const payload: AdjustmentPayload = {
    code: props.code,
    date: form.date,
    action: form.action,
    // 只有定投开始带频率，其余传 null 让后端归一（与录入弹窗同一套约定）
    frequency: form.action === 1 ? form.frequency : null,
    // 只有定投开始/调整才有「下一交易日起生效」的选项
    deferred: form.action === 1 ? form.deferred : false,
    amount,
    // 留空 = 不动：后端对空原因/备注会把它置空，这里与录入弹窗一致用 undefined 表达「没填」
    reason: form.reason.trim() || undefined,
    note: form.note.trim() || undefined,
  }

  saving.value = true
  try {
    if (props.record) {
      await updateAdjustment(String(props.record.adjustmentId), payload)
    } else {
      await createAdjustment(payload)
    }
    // 后端已经重算过该 code 的序列，父级重新拉数据即可
    emit('saved')
  } catch (e) {
    error.value = e instanceof Error ? e.message : '保存失败，请稍后重试'
  } finally {
    saving.value = false
  }
}
</script>

<template>
  <form
    class="space-y-3 rounded-xl border border-brand/40 bg-brand/5 p-3.5"
    @submit.prevent="submit"
  >
    <p class="text-sm font-medium">
      {{ record ? '编辑记录' : '新增记录' }}
    </p>

    <div class="space-y-1.5">
      <label class="text-xs text-muted-foreground" for="adj-date">记录日期</label>
      <input
        id="adj-date"
        v-model="form.date"
        type="date"
        :class="inputClass"
        :disabled="saving"
      />
    </div>

    <div class="space-y-1.5">
      <span class="text-xs text-muted-foreground">动作</span>
      <div class="grid grid-cols-2 gap-1.5">
        <button
          v-for="opt in actionOptions"
          :key="opt.value"
          type="button"
          :disabled="saving"
          class="rounded-lg border px-2 py-1.5 text-xs font-medium transition-colors disabled:opacity-50 disabled:cursor-not-allowed"
          :class="
            form.action === opt.value
              ? 'border-brand bg-brand/15 text-brand'
              : 'border-border hover:bg-muted'
          "
          @click="form.action = opt.value"
        >
          {{ opt.label }}
        </button>
      </div>
    </div>

    <div class="space-y-1.5">
      <label class="text-xs text-muted-foreground" for="adj-amount">金额</label>
      <div class="relative">
        <span
          class="pointer-events-none absolute left-3 top-1/2 -translate-y-1/2 text-sm text-muted-foreground"
          >¥</span
        >
        <input
          id="adj-amount"
          v-model="form.amount"
          type="number"
          inputmode="decimal"
          step="0.01"
          min="0"
          placeholder="0.00"
          :class="inputClass"
          :disabled="saving"
          class="pl-7"
        />
      </div>
    </div>

    <div v-if="form.action === 1" class="space-y-1.5">
      <label class="text-xs text-muted-foreground" for="adj-frequency">定投频率</label>
      <select
        id="adj-frequency"
        v-model="form.frequency"
        :class="inputClass"
        :disabled="saving"
      >
        <option v-for="opt in frequencyOptions" :key="opt.value" :value="opt.value">
          {{ opt.label }}
        </option>
      </select>
    </div>

    <!-- 调整的生效时机：只在 action=1 时出现；默认不勾 = 保持原有口径 -->
    <label
      v-if="form.action === 1"
      class="flex cursor-pointer items-start gap-2.5 rounded-lg border border-border/70 px-2.5 py-2.5 transition-colors hover:bg-muted/50"
    >
      <input
        v-model="form.deferred"
        type="checkbox"
        class="mt-0.5 size-4 shrink-0 accent-[var(--brand)]"
        :disabled="saving"
      />
      <span class="min-w-0">
        <span class="block text-xs font-medium text-foreground">从下一交易日起生效</span>
        <span class="mt-0.5 block text-xs leading-relaxed text-muted-foreground">
          当天仍按原计划扣款；频率与金额从下一个交易日起生效
        </span>
      </span>
    </label>

    <div class="space-y-1.5">
      <label class="text-xs text-muted-foreground" for="adj-reason">变动原因</label>
      <input
        id="adj-reason"
        v-model="form.reason"
        type="text"
        placeholder="选填，如：估值偏低加仓"
        maxlength="255"
        :class="inputClass"
        :disabled="saving"
      />
    </div>

    <div class="space-y-1.5">
      <label class="text-xs text-muted-foreground" for="adj-note">备注</label>
      <textarea
        id="adj-note"
        v-model="form.note"
        rows="2"
        placeholder="选填，补充说明"
        maxlength="512"
        :class="inputClass"
        :disabled="saving"
        class="resize-none"
      />
    </div>

    <p v-if="error" class="text-xs text-destructive">{{ error }}</p>

    <div class="flex items-center justify-end gap-2 pt-0.5">
      <Button type="button" variant="outline" size="sm" :disabled="saving" @click="emit('cancel')">
        取消
      </Button>
      <Button type="submit" size="sm" :disabled="saving">
        <Loader2 v-if="saving" class="size-3.5 animate-spin" />
        {{ saving ? '保存中…' : '保存' }}
      </Button>
    </div>
  </form>
</template>
