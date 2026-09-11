<script setup lang="ts">
import { computed } from 'vue'
import Card from '@/components/ui/card/Card.vue'
import CardHeader from '@/components/ui/card/CardHeader.vue'
import CardContent from '@/components/ui/card/CardContent.vue'
import Badge from '@/components/ui/Badge.vue'
import { formatCNY, type Fund, type PlanAdjustment } from '@/lib/data'

const props = defineProps<{
  /** 当前选中的基金；为 null 时汇总 funds 范围内全部调整记录 */
  fund: Fund | null
  /** 汇总范围（对应当前 tab：进行中 / 归档） */
  funds: Fund[]
}>()

type TimelineEntry = PlanAdjustment & { fundName: string }

/** 选中基金时展示该基金记录；未选中时合并全部计划，按日期升序排列 */
const entries = computed<TimelineEntry[]>(() => {
  const fund = props.fund
  if (fund) {
    return fund.adjustments.map((a) => ({ ...a, fundName: fund.name }))
  }
  return props.funds
    .flatMap((f) => f.adjustments.map((a) => ({ ...a, fundName: f.name })))
    .sort((a, b) => a.date.localeCompare(b.date))
})
</script>

<template>
  <Card>
    <CardHeader class="gap-1">
      <p class="text-xs font-medium uppercase tracking-widest text-muted-foreground">时间轴</p>
      <h2 class="text-xl font-bold">定投调整历程</h2>
      <p v-if="!fund" class="text-xs text-muted-foreground">
        全部计划 · 按时间顺序排列
      </p>
    </CardHeader>
    <CardContent>
      <!-- 可视区域约两条记录高度，更多记录可滚动查看；桌面端为一屏布局压缩高度 -->
      <ol
        class="relative ml-1 max-h-[292px] overflow-y-auto border-l border-border pl-6 pr-2 lg:max-h-[210px]"
      >
        <li
          v-for="(adj, i) in entries"
          :key="`${adj.fundName}-${adj.id}`"
          :class="i === entries.length - 1 ? '' : 'pb-5'"
        >
          <span
            class="absolute -left-[6.5px] mt-1.5 size-3.5 rounded-full border-2 border-background"
            :style="{
              backgroundColor:
                adj.monthlyAmount === null ? 'var(--loss)' : 'var(--brand)',
            }"
            aria-hidden
          />
          <p class="font-mono text-sm text-muted-foreground">{{ adj.date }}</p>
          <p class="mt-1 flex flex-wrap items-center gap-2 text-base font-semibold">
            {{ adj.action }}
            <Badge
              v-if="!fund"
              variant="secondary"
              class="font-normal text-muted-foreground"
            >
              {{ adj.fundName }}
            </Badge>
          </p>
          <p class="mt-1 text-sm leading-relaxed text-muted-foreground">{{ adj.reason }}</p>
          <div class="mt-2">
            <Badge v-if="adj.monthlyAmount === null" variant="secondary">
              停止定投
            </Badge>
            <Badge v-else variant="outline" class="font-mono font-normal">
              月投 {{ formatCNY(adj.monthlyAmount) }}
            </Badge>
          </div>
        </li>
      </ol>
    </CardContent>
  </Card>
</template>
