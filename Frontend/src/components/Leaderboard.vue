<script setup lang="ts">
import { computed } from 'vue'
import { X } from '@lucide/vue'
import Card from '@/components/ui/card/Card.vue'
import CardHeader from '@/components/ui/card/CardHeader.vue'
import CardContent from '@/components/ui/card/CardContent.vue'
import Badge from '@/components/ui/Badge.vue'
import { useRequest } from '@/composables/useApi'
import { getFundLeaderboard } from '@/api'
import { formatPct, returnRate } from '@/lib/finance'
import type { AllocationBucket, Fund } from '@/api/types'

const props = defineProps<{
  /** 「资产配置结构」里点选的分类：只显示该分类的基金；null = 不筛选 */
  filterBucket?: AllocationBucket | null
}>()

const emit = defineEmits<{
  select: [id: string, archived: boolean]
  /** 点筛选标记上的 ✕ */
  clear: []
}>()

/** 筛选标记上的短名，与「资产配置结构」列表里的叫法一致 */
const BUCKET_LABELS: Record<AllocationBucket, string> = {
  equityFund: '股基',
  bondFund: '债基',
  stock: '股票',
  bond: '债券',
  cash: '现金',
}

/** 分类筛选：桶 key 与后端 AssetService.bucketOf 同口径（基金按 asset_type 细分） */
function matchesBucket(f: Fund, bucket: AllocationBucket) {
  if (bucket === 'bondFund') return f.category === 'fund' && f.assetType === 'bond'
  if (bucket === 'equityFund') return f.category === 'fund' && f.assetType !== 'bond'
  return f.category === bucket
}

const { data: leaderboard } = useRequest(() => getFundLeaderboard())

/** 当前展示的排行（筛选后，排名随之重排） */
const list = computed(() =>
  props.filterBucket
    ? (leaderboard.value ?? []).filter((f) => matchesBucket(f, props.filterBucket!))
    : (leaderboard.value ?? []),
)

const max = computed(() => Math.max(0, ...list.value.map((f) => Math.abs(returnRate(f)))))

function barWidth(rate: number) {
  return `${(Math.abs(rate) / max.value) * 100}%`
}
</script>

<template>
  <Card class="h-full">
    <CardHeader class="gap-1">
      <div class="flex items-start justify-between gap-3">
        <div class="space-y-1">
          <h2 class="text-xl font-semibold tracking-tight text-foreground">按收益率排序</h2>
          <p class="text-xs text-muted-foreground">收益排行榜</p>
        </div>
        <!-- 从「资产配置结构」点过来的筛选：轻量标记，点 ✕ 还原 -->
        <button
          v-if="filterBucket"
          type="button"
          class="flex shrink-0 items-center gap-1 rounded-full bg-muted/70 px-2.5 py-1 text-xs text-muted-foreground transition-colors hover:bg-muted hover:text-foreground"
          @click="emit('clear')"
        >
          只看 {{ BUCKET_LABELS[filterBucket] }}
          <X class="size-3" aria-hidden="true" />
        </button>
      </div>
    </CardHeader>
    <!-- lg 起卡片高度由页面锁死，行数变化不再改变卡片高度：超出就在这块里滚 -->
    <CardContent class="space-y-2.5 lg:min-h-0 lg:flex-1 lg:overflow-y-auto">
      <p v-if="!list.length" class="px-4 py-6 text-center text-xs text-muted-foreground">
        该分类暂无可排行的资产
      </p>
      <button
        v-for="(f, i) in list"
        :key="f.id"
        type="button"
        class="flex w-full items-center gap-3 rounded-xl px-3.5 py-4 text-left transition-colors hover:bg-muted/60"
        @click="emit('select', f.id, f.archived)"
      >
        <span class="w-6 shrink-0 font-mono text-base tabular-nums text-muted-foreground">
          {{ i + 1 }}
        </span>
        <div class="min-w-0 flex-1">
          <div class="flex items-center gap-2">
            <p class="truncate text-base font-semibold">{{ f.name }}</p>
            <Badge
              v-if="f.archived"
              variant="secondary"
              class="shrink-0 font-normal text-muted-foreground"
            >
              归档
            </Badge>
          </div>
          <div class="mt-1.5 h-1.5 w-full overflow-hidden rounded-full bg-muted">
            <span
              class="block h-full rounded-full"
              :style="{
                width: barWidth(returnRate(f)),
                backgroundColor: returnRate(f) >= 0 ? 'var(--gain)' : 'var(--loss)',
              }"
            />
          </div>
        </div>
        <span
          class="w-20 shrink-0 text-right font-mono text-base font-bold tabular-nums"
          :style="{ color: returnRate(f) >= 0 ? 'var(--gain)' : 'var(--loss)' }"
        >
          {{ formatPct(returnRate(f)) }}
        </span>
      </button>
    </CardContent>
  </Card>
</template>
