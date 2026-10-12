<script setup lang="ts">
import { ref } from 'vue'
import { ArrowLeft, Loader2, Pencil, Plus, RefreshCw, Trash2 } from '@lucide/vue'
import Card from '@/components/ui/card/Card.vue'
import CardHeader from '@/components/ui/card/CardHeader.vue'
import CardContent from '@/components/ui/card/CardContent.vue'
import Badge from '@/components/ui/Badge.vue'
import Button from '@/components/ui/Button.vue'
import Separator from '@/components/ui/Separator.vue'
import AdjustmentForm from '@/components/AdjustmentForm.vue'
import { deleteAdjustment, generateFundSeries, getAdjustment } from '@/api'
import {
  formatCNY,
  formatPct,
  frequencyLabel,
  returnRate,
} from '@/lib/finance'
import type { Adjustment, Fund, PlanAdjustment } from '@/api/types'

const props = defineProps<{
  fund: Fund
}>()

const emit = defineEmits<{
  back: []
  /** 序列重新生成完毕，父级要重新拉一次计划列表与组合走势 */
  generated: []
}>()

/** 生成序列：后端整段重算 asset_series（净值异步补，不阻塞），期间锁住按钮防连点 */
const generating = ref(false)
/** 生成结果，就展示在按钮下方；下次点击时清掉 */
const generateMessage = ref<string | null>(null)

async function handleGenerate() {
  if (generating.value) return
  generating.value = true
  generateMessage.value = null
  try {
    const rows = await generateFundSeries(props.fund.id)
    generateMessage.value = rows > 0 ? `已生成 ${rows} 行` : '没有调整记录，未生成'
    // 库里序列变了，父级的计划列表与组合走势都是旧数据，重新拉一遍
    emit('generated')
  } catch (e) {
    generateMessage.value = e instanceof Error ? e.message : String(e)
  } finally {
    generating.value = false
  }
}

/* ==================== 调整记录的编辑 / 新增 / 删除 ==================== */

/** 正在编辑哪条记录（值 = 时间线项的 id，即 adjustmentId 字符串），null = 没开编辑表单 */
const editingId = ref<string | null>(null)
/** 编辑表单的回填数据；点开时才从 /adjustments/{id} 拉真实字段，见 openEdit */
const editRecord = ref<Adjustment | null>(null)
/** 正在拉取哪条记录（防连点 + 卡片上转圈），null = 空闲 */
const loadingId = ref<string | null>(null)
/** 新增表单是否展开；与编辑互斥，同一时刻只开一个表单 */
const adding = ref(false)
/** 待确认删除的记录 id：点垃圾桶先进确认态，确认后才真删 */
const confirmId = ref<string | null>(null)
/** 正在删除哪条记录（请求中，图标变转圈防连点） */
const deletingId = ref<string | null>(null)
/** 拉取 / 删除失败的提示，显示在记录列表下方 */
const actionError = ref<string | null>(null)

/**
 * 点记录卡片进编辑态。必须先拉一次原始记录：列表里的时间线项是**展示视图** ——
 * action 是「上调金额」这种文案而不是 1/2/3/4，结束定投的记录金额还被刻意置空了，
 * 直接拿它回填会把动作猜错、把金额清掉。
 */
async function openEdit(adj: PlanAdjustment) {
  if (loadingId.value || deletingId.value) return
  adding.value = false
  confirmId.value = null
  actionError.value = null
  loadingId.value = adj.id
  try {
    editRecord.value = await getAdjustment(adj.id)
    editingId.value = adj.id
  } catch (e) {
    actionError.value = e instanceof Error ? e.message : '加载记录失败'
  } finally {
    loadingId.value = null
  }
}

function openAdd() {
  editingId.value = null
  editRecord.value = null
  confirmId.value = null
  actionError.value = null
  adding.value = true
}

function closeForm() {
  editingId.value = null
  editRecord.value = null
  adding.value = false
}

/** 保存成功（新增或编辑）：关表单，父级重拉计划列表与组合走势（后端已重算序列） */
function handleSaved() {
  closeForm()
  emit('generated')
}

/** 点垃圾桶：先进确认态（删除会重算序列且不可撤销，不做「一点就删」） */
function askDelete(adj: PlanAdjustment) {
  if (deletingId.value) return
  confirmId.value = confirmId.value === adj.id ? null : adj.id
}

/** 确认删除：删完重拉数据；删掉的是该 code 最后一条时，基金会从计划列表里整体消失 */
async function doDelete(adj: PlanAdjustment) {
  if (deletingId.value) return
  deletingId.value = adj.id
  actionError.value = null
  try {
    await deleteAdjustment(adj.id)
    // 库里记录与序列都变了，父级重拉计划列表与组合走势（页脚的「最后更新」也跟着变）
    emit('generated')
  } catch (e) {
    actionError.value = e instanceof Error ? e.message : '删除失败，请稍后重试'
  } finally {
    confirmId.value = null
    deletingId.value = null
  }
}
</script>

<template>
  <Card class="h-full">
    <CardHeader class="gap-4">
      <button
        type="button"
        class="inline-flex w-fit items-center gap-1.5 text-xs font-medium text-muted-foreground transition-colors hover:text-foreground"
        @click="emit('back')"
      >
        <ArrowLeft class="size-3.5" />
        返回计划列表
      </button>
      <!-- 右列面板很窄（约 260px），按钮挤不下时让它整块换行，别把标题压成两行 -->
      <div class="flex flex-wrap items-start justify-between gap-3">
        <div class="min-w-0 space-y-1">
          <p class="text-xs font-medium uppercase tracking-widest text-muted-foreground">
            定投详情
          </p>
          <h2 class="text-pretty text-xl font-bold">{{ fund.name }}</h2>
        </div>
        <div class="flex shrink-0 flex-col items-end gap-1">
          <Button
            variant="outline"
            size="sm"
            :disabled="generating"
            :title="`按调整记录重算 ${fund.code} 的每日序列（asset_series）`"
            @click="handleGenerate"
          >
            <RefreshCw class="size-3.5" :class="generating && 'animate-spin'" />
            {{ generating ? '生成中…' : '生成序列' }}
          </Button>
          <p v-if="generateMessage" class="text-xs text-muted-foreground">
            {{ generateMessage }}
          </p>
        </div>
      </div>
      <!--
        三格统计：这个面板的宽度由左列（图表卡）的 min-content 决定，窄屏下只有 ~190px，
        写死 grid-cols-3 会把 ¥10,000.00 这种数（约 105px）压成溢出重叠。
        改成最小 8rem 的 auto-fit：放得下就三列，放不下自动降到两列 / 一列，不截断数字。
        8rem 是这么来的：数值最宽约 5.25rem（¥10,000.00 @ text-sm 等宽），
        两边各 0.75rem（p-3）内边距，再留一点余量。注意根字号是 20px，1rem = 20px。
      -->
      <div class="grid grid-cols-[repeat(auto-fit,minmax(8rem,1fr))] gap-3">
        <div class="rounded-lg bg-muted/50 p-3">
          <p class="text-xs text-muted-foreground">投入本金</p>
          <p class="mt-1 font-mono text-sm font-semibold tabular-nums">
            {{ formatCNY(fund.principal) }}
          </p>
        </div>
        <div class="rounded-lg bg-muted/50 p-3">
          <p class="text-xs text-muted-foreground">当前市值</p>
          <p class="mt-1 font-mono text-sm font-semibold tabular-nums">
            {{ formatCNY(fund.current) }}
          </p>
        </div>
        <div class="rounded-lg bg-muted/50 p-3">
          <p class="text-xs text-muted-foreground">收益率</p>
          <p
            class="mt-1 font-mono text-sm font-semibold tabular-nums"
            :style="{
              color: returnRate(fund) >= 0 ? 'var(--gain)' : 'var(--loss)',
            }"
          >
            {{ formatPct(returnRate(fund)) }}
          </p>
        </div>
      </div>
    </CardHeader>
    <CardContent class="space-y-4 lg:min-h-0 lg:flex-1 lg:overflow-y-auto lg:pr-3">
      <Separator />
      <div>
        <p class="mb-3 text-sm font-medium">定投调整记录</p>
        <div class="space-y-2.5">
          <template v-for="adj in [...fund.adjustments].reverse()" :key="adj.id">
            <!-- 编辑态：整条换成表单（回填值由 openEdit 拉好） -->
            <AdjustmentForm
              v-if="editingId === adj.id && editRecord"
              :code="fund.code"
              :record="editRecord"
              @saved="handleSaved"
              @cancel="closeForm"
            />
            <!--
              展示态：整卡可点进编辑。外层的 div 不能省 —— 编辑/删除是真正的按钮，
              按钮里套按钮是非法 HTML；点图标时用 .stop 阻止冒泡，不走「整卡编辑」那条路。
              底部一行左边是备注、右边是编辑 + 删除图标，两者同一行高（图标按钮带 aria-label，
              键盘用户走它们，不依赖外层 div 的点击）。
            -->
            <div
              v-else
              class="group cursor-pointer rounded-xl border border-border/70 transition-colors hover:border-border hover:bg-muted/50"
              :class="{ 'pointer-events-none opacity-60': loadingId === adj.id || deletingId === adj.id }"
              :title="`编辑这条记录（${adj.date}）`"
              @click="openEdit(adj)"
            >
              <div class="p-3.5 pb-1">
                <div class="flex items-center justify-between gap-3">
                  <span class="text-sm font-medium">{{ adj.action }}</span>
                  <span class="font-mono text-xs text-muted-foreground">{{ adj.date }}</span>
                </div>
                <p class="mt-1.5 text-sm text-muted-foreground">{{ adj.reason }}</p>
                <div class="mt-2.5 flex items-center gap-2 text-xs">
                  <span class="text-muted-foreground">调整后：</span>
                  <Badge v-if="adj.amount === null" variant="secondary">
                    停止定投
                  </Badge>
                  <!-- 只有定投记录才有频率：买进卖出没有频率，硬套会显示「undefined ¥70」 -->
                  <span v-else class="font-mono font-medium">
                    <template v-if="adj.frequency">{{ frequencyLabel(adj.frequency) }} </template>
                    {{ formatCNY(adj.amount) }}
                  </span>
                </div>
              </div>

              <!-- 备注行：图标与备注文字同一行高；没有备注时这一行只剩右侧图标 -->
              <div class="flex items-center justify-between gap-2 px-3.5 pb-3.5 pt-1.5">
                <p class="min-w-0 flex-1 break-words text-xs text-muted-foreground/80">
                  <template v-if="adj.note">备注：{{ adj.note }}</template>
                </p>
                <span class="flex shrink-0 items-center gap-1.5">
                  <Loader2
                    v-if="loadingId === adj.id || deletingId === adj.id"
                    class="size-3.5 animate-spin text-muted-foreground"
                  />
                  <!-- 确认态：删除会整段重算序列、不可撤销，不做「一点就删」 -->
                  <template v-else-if="confirmId === adj.id">
                    <span class="text-xs text-muted-foreground">删除？</span>
                    <button
                      type="button"
                      class="text-xs font-medium text-destructive transition-colors hover:underline"
                      @click.stop="doDelete(adj)"
                    >
                      删除
                    </button>
                    <button
                      type="button"
                      class="text-xs text-muted-foreground transition-colors hover:text-foreground"
                      @click.stop="confirmId = null"
                    >
                      取消
                    </button>
                  </template>
                  <template v-else>
                    <button
                      type="button"
                      class="rounded p-0.5 text-muted-foreground/50 transition-colors hover:text-foreground/80"
                      :title="`编辑这条记录（${adj.date}）`"
                      aria-label="编辑这条记录"
                      @click.stop="openEdit(adj)"
                    >
                      <Pencil class="size-3.5" />
                    </button>
                    <button
                      type="button"
                      class="rounded p-0.5 text-muted-foreground/50 transition-colors hover:text-destructive"
                      title="删除这条记录"
                      aria-label="删除这条记录"
                      @click.stop="askDelete(adj)"
                    >
                      <Trash2 class="size-3.5" />
                    </button>
                  </template>
                </span>
              </div>
            </div>
          </template>

          <!-- 新增态 / 加号卡片：从这里录入一条调整记录（编码固定为当前计划） -->
          <AdjustmentForm
            v-if="adding"
            :code="fund.code"
            :record="null"
            @saved="handleSaved"
            @cancel="closeForm"
          />
          <button
            v-else
            type="button"
            class="flex w-full items-center justify-center gap-1.5 rounded-xl border border-dashed border-border px-3.5 py-3 text-sm text-muted-foreground transition-colors hover:border-brand/50 hover:bg-brand/5 hover:text-brand"
            @click="openAdd"
          >
            <Plus class="size-4" />
            添加记录
          </button>

          <p v-if="actionError" class="text-xs text-destructive">{{ actionError }}</p>
        </div>
      </div>
    </CardContent>
  </Card>
</template>
