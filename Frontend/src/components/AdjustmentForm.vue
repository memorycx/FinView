<script setup lang="ts">
import { computed, reactive, ref } from 'vue'
import { Loader2 } from '@lucide/vue'
import Button from '@/components/ui/Button.vue'
import { createAdjustment, updateAdjustment } from '@/api'
import type {
  Adjustment,
  AdjustmentAction,
  AdjustmentPayload,
  FundType,
  InvestFrequency,
} from '@/api/types'

/**
 * 调整记录的内嵌编辑 / 新增表单（定投详情页、计划列表的空态都用它）：
 * 字段与录入页一致，只是收成卡片里的纵向布局，塞进右侧窄列（最窄约 244px）。
 *
 * 两种模式由 code 区分：
 * - 传了 code（详情页）：编码固定为当前计划、不提供修改入口——在「某只基金」的详情里改记录，
 *   本来就不该把它挪到别的资产上；
 * - code 为 null（列表底部的「添加资产」）：显示编码输入框（必填）、名称输入框（选填）
 *   与股基/债基二选一，用于给新用户 / 新资产录入第一条记录。
 */
const props = defineProps<{
  /** 当前计划的资产编码，提交时原样带上；null = 添加新资产（表单里手动填编码） */
  code?: string | null
  /**
   * 编辑时传库里的原始记录（由父级 GET /adjustments/{id} 拉好），新增传 null。
   * 列表里的时间线项不能直接回填：action 是文案、结束定投的金额被置空了。
   */
  record: Adjustment | null
}>()

/** 没带 code = 添加新资产：编码与名称要在表单里自己填 */
const isNewAsset = computed(() => !props.code)

const emit = defineEmits<{
  /** 保存成功：父级关表单并重拉计划列表与组合走势 */
  saved: []
  cancel: []
}>()

const actionOptions: { value: AdjustmentAction; label: string }[] = [
  { value: 1, label: '定投开始' },
  { value: 2, label: '结束定投' },
  { value: 3, label: '一笔收入' },
  { value: 4, label: '一笔支出' },
]

const frequencyOptions: { value: InvestFrequency; label: string }[] = [
  { value: 'daily', label: '每日' },
  { value: 'weekly', label: '每周' },
  { value: 'monthly', label: '每月' },
]

/** 基金细分类型（只在「添加资产」模式显示）：股基 / 债基，就是 asset.asset_type 的两个取值 */
const fundTypeOptions: { value: FundType; label: string }[] = [
  { value: 'equity', label: '股票基金' },
  { value: 'bond', label: '债券基金' },
]

/** 今天 YYYY-MM-DD */
function today(): string {
  return new Date().toISOString().slice(0, 10)
}

const form = reactive({
  // 添加新资产时才有值；详情页里编码来自 props.code，这两个字段不展示
  code: '',
  name: '',
  // 基金细分类型：同样只在「添加资产」模式显示（股基 = asset.asset_type 的 equity）
  assetType: 'equity' as FundType,
  date: props.record?.date ?? today(),
  action: (props.record?.action ?? 1) as AdjustmentAction,
  // 结束定投等记录的 frequency 是 null，兜底成 monthly（只有 action=1 会展示并提交它）
  frequency: ((props.record?.frequency as InvestFrequency | null) ?? 'monthly') as InvestFrequency,
  amount: props.record ? String(props.record.amount) : '',
  reason: props.record?.reason ?? '',
  note: props.record?.note ?? '',
})

const saving = ref(false)
const error = ref('')

/**
 * 用户动过股基/债基控件的标记：动过才把 assetType 提交上去。
 * 不动 = 不改已有分类——表单默认高亮股基，但给一只已有的债基录常规记录时不该把它翻回去；
 * 新建的资产行由后端落默认 equity，不传与传 equity 结果一样。
 */
const assetTypeTouched = ref(false)

/** 选类型：点过就记 touched（提交时才带 assetType，见 payload） */
function pickAssetType(value: FundType) {
  form.assetType = value
  assetTypeTouched.value = true
}

const inputClass =
  'w-full rounded-lg border border-border bg-background px-3 py-2 text-sm transition-colors placeholder:text-muted-foreground/60 focus:outline-none focus:ring-2 focus:ring-brand/30 focus:border-brand disabled:opacity-50 disabled:cursor-not-allowed'

async function submit() {
  if (saving.value) return
  error.value = ''
  if (isNewAsset.value && !form.code.trim()) return (error.value = '请填写资产编码')
  if (!form.date) return (error.value = '请选择记录日期')
  if (form.action === 1 && !form.frequency) {
    return (error.value = '定投开始需选择定投频率')
  }
  const amount = Number(form.amount)
  if (!form.amount || Number.isNaN(amount) || amount <= 0) {
    return (error.value = '金额必须大于 0')
  }

  const payload: AdjustmentPayload = {
    code: props.code ?? form.code.trim(),
    // 名称只在添加新资产时填：详情页里改记录不该顺手把资产名也改掉
    name: isNewAsset.value ? form.name.trim() || undefined : undefined,
    // 同理，类型也只在「添加资产」且用户动过控件时才提交（见 assetTypeTouched）
    assetType: isNewAsset.value && assetTypeTouched.value ? form.assetType : undefined,
    date: form.date,
    action: form.action,
    // 只有定投开始带频率，其余传 null 让后端归一（与录入页同一套约定）
    frequency: form.action === 1 ? form.frequency : null,
    amount,
    // 留空 = 不动：后端对空原因/备注会把它置空，这里与录入页一致用 undefined 表达「没填」
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
      {{ record ? '编辑记录' : isNewAsset ? '添加资产' : '新增记录' }}
    </p>

    <div v-if="isNewAsset" class="space-y-1.5">
      <label class="text-xs text-muted-foreground" for="adj-code">资产编码</label>
      <input
        id="adj-code"
        v-model="form.code"
        type="text"
        placeholder="基金代码，如 005827"
        maxlength="64"
        :class="inputClass"
        :disabled="saving"
      />
    </div>

    <div v-if="isNewAsset" class="space-y-1.5">
      <label class="text-xs text-muted-foreground" for="adj-name">资产名称</label>
      <input
        id="adj-name"
        v-model="form.name"
        type="text"
        placeholder="选填，留空就用编码显示"
        maxlength="100"
        :class="inputClass"
        :disabled="saving"
      />
    </div>

    <!-- 股基 / 债基：进「资产配置结构」的两个桶，债基还计入安全资金 -->
    <div v-if="isNewAsset" class="space-y-1.5">
      <span class="text-xs text-muted-foreground">资产类型</span>
      <div class="grid grid-cols-2 gap-1.5">
        <button
          v-for="opt in fundTypeOptions"
          :key="opt.value"
          type="button"
          :disabled="saving"
          class="rounded-lg border px-2 py-1.5 text-xs font-medium transition-colors disabled:opacity-50 disabled:cursor-not-allowed"
          :class="
            form.assetType === opt.value
              ? 'border-brand bg-brand/15 text-brand'
              : 'border-border hover:bg-muted'
          "
          @click="pickAssetType(opt.value)"
        >
          {{ opt.label }}
        </button>
      </div>
    </div>

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
