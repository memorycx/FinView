<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref } from 'vue'
import type { Component } from 'vue'
import { Coins, Landmark, Loader2, Pencil, TrendingUp, X } from '@lucide/vue'
import Button from '@/components/ui/Button.vue'
import { updateFund } from '@/api'
import type { Fund, FundMarket, FundType } from '@/api/types'

/**
 * 编辑基金基础信息弹窗（定投详情页的「编辑」按钮）。
 *
 * 只改 asset 行上的用户元数据：名称 / 类型 / 申购费率 / 标的市场。
 * **编码是关联键**（adjustments、asset_series、nav_trend、plan_asset 都按它关联），
 * 所以只读展示、不开放修改。
 *
 * 费率与标的市场会影响序列：rate 决定历史买入的手续费、market 决定 QDII 的扣款日。
 * 保存后由后端整段重算 asset_series，父级收到 saved 后重拉计划列表与组合走势即可。
 * 弹窗由父级 v-if 控制挂载，每次打开都是全新实例，表单状态天然干净。
 */
const props = defineProps<{
  fund: Fund
}>()

const emit = defineEmits<{
  /** 保存成功：父级关弹窗并重拉数据（费率 / 市场变了的话后端已重算序列） */
  saved: []
  /** 请求关闭：点 X / 点遮罩 / 按 Esc（保存中不响应，避免请求在飞就卸载） */
  close: []
}>()

/** 名称：直接以当前资产名为初始值（asset 行缺名时后端会用 code 兜底，这里同样有值） */
const name = ref(props.fund.name)
/** 类型：null（未标注）按股基显示，与后端 bucketOf / 列表徽标的兜底一致 */
const assetType = ref<FundType>(props.fund.assetType ?? 'equity')
/**
 * 费率输入框：空串 = 不计费（提交 null）。
 * 注意 `<input type="number">` 上的 v-model 会自动套 `.number`，输入后 ref 是 number 而不是 string，
 * 所以这里类型放宽成 string | number，提交时统一 String() 再 trim。
 */
const rateInput = ref<string | number>(props.fund.rate == null ? '' : props.fund.rate)
/** 标的市场：null = A 股 */
const market = ref<FundMarket | null>(props.fund.market ?? null)

const loading = ref(false)
const error = ref('')

const assetTypeOptions: {
  value: FundType
  label: string
  hint: string
  icon: Component
}[] = [
  { value: 'equity', label: '股票基金', hint: '股基', icon: TrendingUp },
  { value: 'bond', label: '债券基金', hint: '计入安全资金', icon: Landmark },
  { value: 'money', label: '货币基金', hint: '计入安全资金', icon: Coins },
]

const marketOptions: { value: FundMarket | null; label: string; hint: string }[] = [
  { value: null, label: 'A 股', hint: '只看 A 股交易日历' },
  { value: 'us', label: '美股', hint: 'QDII · 叠加 NYSE 休市' },
  { value: 'hk', label: '港股', hint: 'QDII · 叠加港交所休市' },
]

/** 统一输入框样式，与 EntryDialog 保持一致 */
const inputClass =
  'w-full rounded-lg border border-border bg-background px-3.5 py-2.5 text-sm transition-colors placeholder:text-muted-foreground/60 focus:outline-none focus:ring-2 focus:ring-brand/30 focus:border-brand disabled:opacity-50 disabled:cursor-not-allowed'

const panel = ref<HTMLElement | null>(null)
const nameInput = ref<HTMLInputElement | null>(null)
/** 打开前的 body overflow，关闭时还原 */
let prevBodyOverflow = ''

/** 保存中不响应关闭，避免请求还在飞就卸载 */
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
  nameInput.value?.focus()
})

onBeforeUnmount(() => {
  document.removeEventListener('keydown', onKeydown)
  document.body.style.overflow = prevBodyOverflow
})

async function submit() {
  error.value = ''
  const trimmedName = name.value.trim()
  if (!trimmedName) {
    error.value = '请填写基金名称'
    return
  }
  if (trimmedName.length > 100) {
    error.value = '基金名称不能超过 100 个字符'
    return
  }

  // 空 = 不计费（null）；其余必须是 0~100 的百分数（与后端校验同一口径）
  let rate: number | null = null
  const rawRate = String(rateInput.value ?? '').trim()
  if (rawRate !== '') {
    rate = Number(rawRate)
    if (!Number.isFinite(rate) || rate < 0 || rate > 100) {
      error.value = '申购费率需在 0~100 之间（0.12 表示 0.12%）'
      return
    }
  }

  loading.value = true
  try {
    await updateFund(props.fund.id, {
      name: trimmedName,
      assetType: assetType.value,
      rate,
      market: market.value,
    })
    // 父级收到就关弹窗、重拉计划列表与组合走势
    emit('saved')
  } catch (e) {
    error.value = e instanceof Error ? e.message : '保存失败，请稍后重试'
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <Teleport to="body">
    <div class="fixed inset-0 z-50 flex items-center justify-center p-4 sm:p-6">
      <!-- 遮罩：点击关闭（保存中不响应） -->
      <div
        class="absolute inset-0 animate-in bg-black/45 backdrop-blur-[2px] duration-150 fade-in dark:bg-black/65"
        aria-hidden="true"
        @click="requestClose"
      />

      <div
        ref="panel"
        role="dialog"
        aria-modal="true"
        aria-labelledby="fund-edit-title"
        tabindex="-1"
        class="relative flex max-h-[88vh] w-full max-w-lg animate-in flex-col overflow-hidden rounded-2xl border border-border bg-card text-card-foreground shadow-2xl outline-none duration-150 fade-in zoom-in-95"
      >
        <div class="flex shrink-0 items-start gap-4 border-b border-border px-5 py-4 sm:px-6">
          <div
            class="flex size-11 shrink-0 items-center justify-center rounded-xl bg-brand/10 text-brand"
          >
            <Pencil class="size-5" />
          </div>
          <div class="min-w-0 flex-1 space-y-0.5">
            <h2 id="fund-edit-title" class="text-lg font-semibold leading-tight">
              编辑基金信息
            </h2>
            <p class="text-xs text-muted-foreground">
              修改名称、类型、申购费率与标的市场
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

        <!-- 表单区独自滚动，底部操作按钮固定：内容再长也不会把「保存」挤出弹窗 -->
        <form class="flex min-h-0 flex-1 flex-col" @submit.prevent="submit">
          <div
            class="min-h-0 flex-1 space-y-5 overflow-y-auto overscroll-contain px-5 pb-5 pt-5 sm:px-6"
          >
          <!-- 名称 + 编码：编码是关联键，只读展示 -->
          <div class="grid gap-4 sm:grid-cols-2 sm:gap-5">
            <div class="space-y-2">
              <label class="text-sm font-medium text-foreground" for="fund-name">基金名称</label>
              <input
                id="fund-name"
                ref="nameInput"
                v-model="name"
                type="text"
                placeholder="如：沪深300指数增强"
                maxlength="100"
                :class="inputClass"
                :disabled="loading"
              />
            </div>
            <div class="space-y-2">
              <label class="text-sm font-medium text-foreground" for="fund-code">资产编码</label>
              <input
                id="fund-code"
                :value="fund.code"
                type="text"
                :class="inputClass"
                disabled
                title="编码是调整记录与序列的关联键，不能修改"
              />
            </div>
          </div>

          <!-- 基金类型：与「我的资产」的股票基金 / 债券基金 / 货币基金三个桶同口径 -->
          <div class="space-y-2">
            <label class="text-sm font-medium text-foreground">基金类型</label>
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
                @click="assetType = opt.value"
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

          <!-- 申购费率：百分数，留空 = 不计费 -->
          <div class="space-y-2">
            <label class="text-sm font-medium text-foreground" for="fund-rate">申购费率</label>
            <div class="relative">
              <input
                id="fund-rate"
                v-model="rateInput"
                type="number"
                inputmode="decimal"
                step="0.0001"
                min="0"
                max="100"
                placeholder="留空或 0 表示不计费"
                :class="inputClass"
                :disabled="loading"
                class="pr-10"
              />
              <span
                class="pointer-events-none absolute right-3 top-1/2 -translate-y-1/2 text-sm text-muted-foreground"
                >%</span
              >
            </div>
            <p class="text-xs text-muted-foreground">
              0.12 表示 0.12%：买入时按「金额 × 费率」扣手续费，扣完的净额才计入本金。
            </p>
          </div>

          <!-- 标的市场：只对 QDII 有意义，A 股基金保持「A 股」 -->
          <div class="space-y-2">
            <label class="text-sm font-medium text-foreground">标的市场</label>
            <div class="grid gap-3 sm:grid-cols-3">
              <button
                v-for="opt in marketOptions"
                :key="opt.value ?? 'a-share'"
                type="button"
                :disabled="loading"
                class="rounded-xl border p-3 text-left transition-all disabled:opacity-50 disabled:cursor-not-allowed"
                :class="
                  market === opt.value
                    ? 'border-brand bg-brand/10 text-brand ring-1 ring-brand/30'
                    : 'border-border hover:bg-muted hover:border-foreground/20'
                "
                @click="market = opt.value"
              >
                <p class="text-sm font-semibold leading-tight">{{ opt.label }}</p>
                <p class="mt-0.5 text-xs leading-tight text-muted-foreground">
                  {{ opt.hint }}
                </p>
              </button>
            </div>
          </div>

          <!-- 口径提示：哪些字段会触发序列重算 -->
          <p
            class="rounded-xl border border-border/60 bg-muted/40 px-3.5 py-3 text-xs leading-relaxed text-muted-foreground"
          >
            名称与类型立即生效；修改申购费率或标的市场会整段重算该基金的每日序列
            （历史手续费与 QDII 扣款日都跟着变），重算完成后走势图与计划金额同步更新。
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
                <span>{{ loading ? '保存中…' : '保存' }}</span>
              </Button>
            </div>
          </div>
        </form>
      </div>
    </div>
  </Teleport>
</template>
