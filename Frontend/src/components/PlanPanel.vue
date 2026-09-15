<script setup lang="ts">
import { computed } from 'vue'
import { ArrowRight, ChevronRight } from '@lucide/vue'
import Card from '@/components/ui/card/Card.vue'
import CardHeader from '@/components/ui/card/CardHeader.vue'
import CardContent from '@/components/ui/card/CardContent.vue'
import Badge from '@/components/ui/Badge.vue'
import Button from '@/components/ui/Button.vue'
import PlanDetail from '@/components/PlanDetail.vue'
import { formatCNY, formatPct, frequencyLabel, returnRate } from '@/lib/finance'
import type { AssetCategory, Fund } from '@/api/types'

const props = defineProps<{
  /** 全部定投计划（含进行中与已归档），由父级通过 API 加载 */
  funds: Fund[]
  tab: 'active' | 'archived'
  selectedId: string | null
  view: 'list' | 'detail'
}>()

const emit = defineEmits<{
  tabChange: [tab: 'active' | 'archived']
  select: [id: string]
  viewChange: [view: 'list' | 'detail']
}>()

const categoryLabels: Record<AssetCategory, string> = {
  fund: '基金',
  stock: '股票',
  bond: '债券',
  cash: '现金',
}

const list = computed(() =>
  props.funds.filter((f) => (props.tab === 'active' ? f.active : !f.active)),
)

const selected = computed(
  () => list.value.find((f) => f.id === props.selectedId) ?? null,
)

const tabs = [
  { key: 'active', label: '进行中' },
  { key: 'archived', label: '归档' },
] as const
</script>

<template>
  <PlanDetail
    v-if="view === 'detail' && selected"
    class="lg:min-h-0 lg:flex-1"
    :fund="selected"
    @back="emit('viewChange', 'list')"
  />

  <Card v-else class="lg:min-h-0 lg:flex-1">
    <CardHeader class="shrink-0 gap-4">
      <div class="flex items-center justify-between">
        <div class="space-y-1">
          <p class="text-xs font-medium uppercase tracking-widest text-muted-foreground">
            定投计划
          </p>
          <h2 class="text-xl font-bold">
            {{ tab === 'active' ? '进行中的计划' : '已归档计划' }}
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

    <CardContent class="space-y-2.5">
      <button
        v-for="fund in list"
        :key="fund.id"
        type="button"
        class="flex w-full items-center gap-4 rounded-xl border px-5 py-4 text-left transition-colors"
        :class="
          fund.id === selectedId
            ? 'border-border bg-muted/70'
            : 'border-transparent hover:bg-muted/60'
        "
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
              {{ categoryLabels[fund.category] }}
            </Badge>
          </div>
          <p class="mt-1 font-mono text-sm text-muted-foreground">
            {{ fund.code
            }}{{ fund.active ? ` · ${frequencyLabel(fund.frequency)} ${formatCNY(fund.amount)}` : ' · 已停止' }}
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
        <ChevronRight
          class="size-5 shrink-0 transition-colors"
          :class="fund.id === selectedId ? 'text-foreground/60' : 'text-muted-foreground/40'"
        />
      </button>

      <div v-if="selected" class="pt-2">
        <Button class="w-full" @click="emit('viewChange', 'detail')">
          查看「{{ selected.name }}」定投详情
          <ArrowRight class="size-4" />
        </Button>
      </div>
    </CardContent>
  </Card>
</template>
