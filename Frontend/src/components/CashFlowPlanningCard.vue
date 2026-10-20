<script setup lang="ts">
import { computed, nextTick, ref } from 'vue'
import { Check, Pencil, X } from '@lucide/vue'
import Card from '@/components/ui/card/Card.vue'
import Button from '@/components/ui/Button.vue'
import { updateMonthlyInflow } from '@/api'
import { formatCNY } from '@/lib/finance'
import type { CashFlowForecast } from '@/api/types'

/**
 * 「现金流规划」卡片（定投分析页的第一张）。
 *
 * 四个数全部由后端算好（口径见 CashFlowService）：
 * 当前可用现金 / 每月固定新增 / 每月定投总额 / 12 个月后预计现金，
 * 四者满足 `现金 + 12×新增 − 12×定投总额 = 12个月后`。
 *
 * 「每月固定新增」是用户手工设的业务假设：点击数值就地编辑、保存后由父级把
 * 重算好的整份预测塞回来（两张卡片一起刷新）。
 */
const props = defineProps<{ forecast?: CashFlowForecast }>()

const emit = defineEmits<{
  /** 保存成功：参数是后端重算后的整份预测 */
  saved: [forecast: CashFlowForecast]
}>()

const editing = ref(false)
/** type=number 的 v-model 会自动转成 number（空值时是空串），所以这里两种类型都要接得住 */
const draft = ref<string | number>('')
const saving = ref(false)
const error = ref<string | null>(null)
const inputEl = ref<HTMLInputElement | null>(null)

const hasForecast = computed(() => !!props.forecast)
const hasInflow = computed(() => props.forecast?.monthlyInflow != null)

/** 被单月覆盖的月份数（在柱状图上点柱子单独改过）；> 0 时在「每月固定新增」下面提示一句 */
const overriddenCount = computed(() => props.forecast?.months.filter((m) => m.overridden).length ?? 0)

/** 12 个月后 = 末月余额；窗口末端超出交易日历覆盖范围时脚注里如实说明 */
const calendarCaveat = computed(() => {
  const end = props.forecast?.calendarCoverageEnd
  const to = props.forecast?.toMonth
  return !!end && !!to && to > end.slice(0, 7)
})

/**
 * 金额；数据还没回来时显示「—」。
 * 负数写成 −¥17,700.00（formatCNY 会渲染成 ¥-17,700.00，符号位置不对），与「每月定投总额」同款减号。
 */
function cny(n: number | undefined) {
  if (n == null) return '—'
  return n < 0 ? '−' + formatCNY(Math.abs(n)) : formatCNY(n)
}

/** 带方向的金额：+¥3,000 / −¥1,560（减号用 U+2212，贴近设计稿） */
function signed(n: number | undefined, sign: '+' | '−') {
  return n == null ? '—' : sign + formatCNY(Math.abs(n))
}

function startEdit() {
  draft.value = props.forecast?.monthlyInflow == null ? '' : String(props.forecast.monthlyInflow)
  error.value = null
  editing.value = true
  nextTick(() => {
    inputEl.value?.focus()
    inputEl.value?.select()
  })
}

function cancel() {
  editing.value = false
  error.value = null
}

/** 空串 = 清空（未设置，预测按 0 算）；后端还会挡「负数 / 超过两位小数」，错误原地展示 */
async function save() {
  if (saving.value) return
  const raw = String(draft.value ?? '').trim()
  const parsed = raw === '' ? null : Number(raw)
  if (parsed !== null && (!Number.isFinite(parsed) || parsed < 0)) {
    error.value = '请输入不小于 0 的金额'
    return
  }
  saving.value = true
  error.value = null
  try {
    emit('saved', await updateMonthlyInflow(parsed))
    editing.value = false
  } catch (e) {
    error.value = e instanceof Error ? e.message : String(e)
  } finally {
    saving.value = false
  }
}
</script>

<template>
  <Card class="gap-0 p-5 sm:p-7">
    <div class="flex items-start justify-between gap-4">
      <div>
        <h2 class="text-2xl font-bold tracking-tight">现金流规划</h2>
        <p class="mt-1 text-sm text-muted-foreground">Cash Flow &amp; DCA Planning</p>
      </div>
      <span class="shrink-0 rounded-full bg-muted/60 px-3 py-1 text-xs text-muted-foreground">未来预测</span>
    </div>

    <div class="mt-6 grid grid-cols-1 gap-x-6 gap-y-7 sm:grid-cols-2">
      <!-- 当前可用现金 -->
      <div>
        <p class="text-sm text-muted-foreground">当前可用现金</p>
        <p class="mt-1.5 font-mono text-2xl font-bold tracking-tight tabular-nums sm:text-3xl">
          {{ cny(forecast?.currentCash) }}
        </p>
      </div>

      <!-- 每月固定新增：点击就地编辑，空 = 未设置 -->
      <div>
        <p class="text-sm text-muted-foreground">每月固定新增</p>

        <div v-if="!editing" class="mt-1.5">
          <button
            type="button"
            class="group inline-flex items-center gap-2 rounded-lg outline-none focus-visible:ring-3 focus-visible:ring-ring/50"
            aria-label="编辑每月固定新增"
            @click="startEdit"
          >
            <span
              v-if="hasInflow"
              class="font-mono text-2xl font-bold tracking-tight tabular-nums sm:text-3xl"
              :style="{ color: 'var(--flow-in)' }"
            >
              {{ signed(forecast?.monthlyInflow ?? undefined, '+') }}
            </span>
            <span v-else class="text-sm text-muted-foreground underline decoration-dotted underline-offset-4">
              未设置（点击填写）
            </span>
            <Pencil
              class="size-4 shrink-0 text-muted-foreground opacity-0 transition-opacity group-hover:opacity-60"
            />
          </button>
        </div>

        <div v-else class="mt-1.5 flex items-center gap-1.5">
          <input
            ref="inputEl"
            v-model="draft"
            type="number"
            inputmode="decimal"
            step="0.01"
            min="0"
            placeholder="如 3000"
            class="h-9 w-36 rounded-lg border border-border bg-background px-2 font-mono text-lg tabular-nums outline-none focus-visible:ring-3 focus-visible:ring-ring/50"
            @keyup.enter="save"
            @keyup.esc="cancel"
          />
          <Button variant="ghost" size="icon-sm" :disabled="saving" aria-label="保存" @click="save">
            <Check />
          </Button>
          <Button variant="ghost" size="icon-sm" :disabled="saving" aria-label="取消" @click="cancel">
            <X />
          </Button>
        </div>

        <p v-if="error" class="mt-1 text-xs text-destructive">{{ error }}</p>
        <p v-else-if="overriddenCount > 0" class="mt-1 text-xs text-muted-foreground">
          有 {{ overriddenCount }} 个月在柱状图上单独调整过
        </p>
      </div>

      <!-- 平均每月定投总额（含在扣款金额里的申购费，不额外增加流出） -->
      <div>
        <p class="text-sm text-muted-foreground">平均每月定投总额</p>
        <p
          class="mt-1.5 font-mono text-2xl font-bold tracking-tight tabular-nums sm:text-3xl"
          :style="{ color: 'var(--flow-out)' }"
        >
          {{ signed(forecast?.monthlyOutlay, '−') }}
        </p>
      </div>

      <!-- 12 个月后预计现金 -->
      <div>
        <p class="text-sm text-muted-foreground">12 个月后预计现金</p>
        <p class="mt-1.5 font-mono text-2xl font-bold tracking-tight tabular-nums sm:text-3xl">
          {{ cny(forecast?.projectedCash) }}
        </p>
        <p v-if="hasForecast && !hasInflow" class="mt-1 text-xs text-muted-foreground">
          未设置每月固定新增，暂按 ¥0 计算
        </p>
      </div>
    </div>

    <p class="mt-6 border-t border-border pt-4 text-xs leading-relaxed text-muted-foreground">
      每月固定新增是默认值（点柱状图的柱子可单独调整某个月）；定投支出按当前定投计划的频率与金额、真实交易日历推算（每周一 / 每月 1 日遇非交易日顺延到下一个交易日）。
      平均每月定投总额 = 未来 12 个月扣款合计 ÷ 12；12 个月后预计现金 = 当前可用现金 + 未来 12 个月新增合计 − 12 × 平均每月定投总额
      （没单独调整过任何月份时 = 当前可用现金 + 12 × 每月固定新增 − 12 × 平均每月定投总额）。
      不含投资收益、不计其他收支；本页窗口为 {{ forecast?.fromMonth ?? '—' }} ~ {{ forecast?.toMonth ?? '—' }}，当月剩余扣款不计入。
      <template v-if="calendarCaveat">
        交易日历覆盖至 {{ forecast?.calendarCoverageEnd }}，之后的节假日暂按「周一至周五」估算。
      </template>
    </p>
  </Card>
</template>
