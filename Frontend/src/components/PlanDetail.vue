<script setup lang="ts">
import { ArrowLeft } from '@lucide/vue'
import Card from '@/components/ui/card/Card.vue'
import CardHeader from '@/components/ui/card/CardHeader.vue'
import CardContent from '@/components/ui/card/CardContent.vue'
import Badge from '@/components/ui/Badge.vue'
import Separator from '@/components/ui/Separator.vue'
import {
  formatCNY,
  formatPct,
  returnRate,
  type Fund,
} from '@/lib/data'

defineProps<{
  fund: Fund
}>()

const emit = defineEmits<{
  back: []
}>()
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
      <div class="space-y-1">
        <p class="text-xs font-medium uppercase tracking-widest text-muted-foreground">
          定投详情
        </p>
        <h2 class="text-pretty text-xl font-bold">{{ fund.name }}</h2>
      </div>
      <div class="grid grid-cols-3 gap-3">
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
          <div
            v-for="adj in [...fund.adjustments].reverse()"
            :key="adj.id"
            class="rounded-xl border border-border/70 p-3.5"
          >
            <div class="flex items-center justify-between gap-3">
              <span class="text-sm font-medium">{{ adj.action }}</span>
              <span class="font-mono text-xs text-muted-foreground">{{ adj.date }}</span>
            </div>
            <p class="mt-1.5 text-sm text-muted-foreground">{{ adj.reason }}</p>
            <div class="mt-2.5 flex items-center gap-2 text-xs">
              <span class="text-muted-foreground">调整后：</span>
              <Badge v-if="adj.monthlyAmount === null" variant="secondary">
                停止定投
              </Badge>
              <span v-else class="font-mono font-medium">
                月投 {{ formatCNY(adj.monthlyAmount) }}
              </span>
            </div>
            <p v-if="adj.note" class="mt-2 text-xs text-muted-foreground/80">
              备注：{{ adj.note }}
            </p>
          </div>
        </div>
      </div>
    </CardContent>
  </Card>
</template>
