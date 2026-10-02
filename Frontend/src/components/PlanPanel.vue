<script setup lang="ts">
import { computed, ref } from 'vue'
import { ChevronRight, Plus } from '@lucide/vue'
import Card from '@/components/ui/card/Card.vue'
import CardHeader from '@/components/ui/card/CardHeader.vue'
import CardContent from '@/components/ui/card/CardContent.vue'
import Badge from '@/components/ui/Badge.vue'
import Button from '@/components/ui/Button.vue'
import PlanDetail from '@/components/PlanDetail.vue'
import AdjustmentForm from '@/components/AdjustmentForm.vue'
import { formatCNY, formatPct, frequencyLabel, returnRate } from '@/lib/finance'
import type { AssetCategory, Fund } from '@/api/types'

const props = defineProps<{
  /** 全部定投计划（含持有中与已归档），由父级通过 API 加载 */
  funds: Fund[]
  tab: 'active' | 'archived'
  selectedId: string | null
  view: 'list' | 'detail'
}>()

const emit = defineEmits<{
  tabChange: [tab: 'active' | 'archived']
  select: [id: string]
  viewChange: [view: 'list' | 'detail']
  /** 生成序列完成，透传给父级重新拉数据 */
  generated: []
}>()

const categoryLabels: Record<AssetCategory, string> = {
  fund: '基金',
  stock: '股票',
  bond: '债券',
  cash: '现金',
}

/**
 * 徽标文案：基金不再显示笼统的「基金」，按 asset_type 细分（与「我的资产」的股票基金/债券基金两个桶同口径）；
 * 未标注（null，存量没标过的）按股票基金显示；其余大类仍走 categoryLabels。
 */
function badgeLabel(fund: Fund): string {
  if (fund.category !== 'fund') return categoryLabels[fund.category]
  return fund.assetType === 'bond' ? '债券基金' : '股票基金'
}

/** tab 与归档与否一一对应：持有中 = 未归档（含定投已结束的，行内标「已停止」） */
const list = computed(() =>
  [...props.funds]
    .filter((f) => (props.tab === 'active' ? !f.archived : f.archived))
    .sort((a, b) => Number(b.active) - Number(a.active)),
)

const selected = computed(
  () => list.value.find((f) => f.id === props.selectedId) ?? null,
)

/** 列表底部的「添加资产」表单是否展开（新用户没有任何计划时也能从这里录入第一条） */
const adding = ref(false)

/** 新资产保存成功：关表单，父级重拉计划列表与组合走势（后端已重算序列） */
function handleAdded() {
  adding.value = false
  emit('generated')
}

const tabs = [
  { key: 'active', label: '持有中' },
  { key: 'archived', label: '归档' },
] as const
</script>

<template>
  <PlanDetail
    v-if="view === 'detail' && selected"
    class="lg:min-h-0 lg:flex-1"
    :fund="selected"
    @back="emit('viewChange', 'list')"
    @generated="emit('generated')"
  />

  <Card v-else class="lg:min-h-0 lg:flex-1 lg:overflow-hidden">
    <CardHeader class="shrink-0 gap-4">
      <div class="flex items-center justify-between">
        <div class="space-y-1">
          <p class="text-xs font-medium uppercase tracking-widest text-muted-foreground">
            定投计划
          </p>
          <h2 class="text-xl font-bold">
            {{ tab === 'active' ? '持有中的计划' : '已归档计划' }}
          </h2>
        </div>
        <div class="inline-flex rounded-full border border-border p-0.5 text-sm">
          <button
            v-for="t in tabs"
            :key="t.key"
            type="button"
            class="rounded-full px-3.5 py-1.5 font-medium transition-colors"
            :class="
              tab === t.key
                ? 'bg-foreground text-background'
                : 'text-muted-foreground hover:text-foreground'
            "
            @click="emit('tabChange', t.key)"
          >
            {{ t.label }}
          </button>
        </div>
      </div>
    </CardHeader>

    <CardContent class="space-y-2.5 lg:max-h-full lg:overflow-y-auto lg:pr-2">
      <!--
        行外壳是 div：主体按钮负责选中/取消，选中后右侧箭头变成「查看详情」按钮
        （按钮不能嵌套，所以拆开），取代原来列表底部的整块按钮。
      -->
      <div
        v-for="fund in list"
        :key="fund.id"
        class="flex w-full items-center gap-4 rounded-xl border px-5 py-4 transition-colors"
        :class="
          fund.id === selectedId
            ? 'border-border bg-muted/70'
            : 'border-transparent hover:bg-muted/60'
        "
      >
        <button
          type="button"
          class="flex min-w-0 flex-1 items-center gap-4 text-left"
          @click="emit('select', fund.id)"
        >
          <span
            class="h-10 w-1 shrink-0 rounded-full"
            :class="fund.id === selectedId ? 'bg-foreground/60' : 'bg-border'"
            aria-hidden
          />
          <div class="min-w-0 flex-1">
            <div class="flex items-center gap-2">
              <p class="truncate text-base font-semibold">{{ fund.name }}</p>
              <Badge
                variant="secondary"
                class="shrink-0 font-normal text-muted-foreground"
              >
                {{ badgeLabel(fund) }}
              </Badge>
            </div>
            <p class="mt-1 font-mono text-sm text-muted-foreground">
              {{ fund.code
              }}{{ fund.active ? ` · ${frequencyLabel(fund.frequency)} ${formatCNY(fund.amount)}` : ' · 持有中' }}
            </p>
          </div>
          <div class="text-right">
            <span
              class="font-mono text-lg font-bold tabular-nums"
              :style="{
                color: returnRate(fund) >= 0 ? 'var(--gain)' : 'var(--loss)',
              }"
            >
              {{ formatPct(returnRate(fund)) }}
            </span>
            <p class="mt-0.5 text-xs text-muted-foreground">收益率</p>
          </div>
        </button>
        <!-- 未选中：纯装饰箭头；选中：变成进详情的入口按钮 -->
        <ChevronRight
          v-if="fund.id !== selectedId"
          class="size-5 shrink-0 text-muted-foreground/40 transition-colors"
        />
        <Button
          v-else
          variant="ghost"
          size="icon-sm"
          class="shrink-0 text-foreground/70 hover:text-foreground"
          :title="`查看「${fund.name}」定投详情`"
          :aria-label="`查看「${fund.name}」定投详情`"
          @click="emit('viewChange', 'detail')"
        >
          <ChevronRight class="size-5" />
        </Button>
      </div>

      <!-- 空态：一个计划都没有时给一句话，别只剩一张空卡片 -->
      <p v-if="list.length === 0" class="py-6 text-center text-sm text-muted-foreground">
        还没有{{ tab === 'active' ? '持有中的计划' : '已归档的计划' }}
      </p>

      <!--
        添加资产：挂在持有中列表的最下面（归档 tab 不显示 —— 新增的资产一定未归档，
        在归档页添加会「点完什么也没出现」）。新用户没有任何数据时，这里是唯一的录入入口。
      -->
      <template v-if="tab === 'active'">
        <AdjustmentForm
          v-if="adding"
          :code="null"
          :record="null"
          @saved="handleAdded"
          @cancel="adding = false"
        />
        <button
          v-else
          type="button"
          class="flex w-full items-center justify-center gap-1.5 rounded-xl border border-dashed border-border px-3.5 py-3 text-sm text-muted-foreground transition-colors hover:border-brand/50 hover:bg-brand/5 hover:text-brand"
          @click="adding = true"
        >
          <Plus class="size-4" />
          添加资产
        </button>
      </template>
    </CardContent>
  </Card>
</template>
