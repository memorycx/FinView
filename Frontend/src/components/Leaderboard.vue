<script setup lang="ts">
import { computed } from 'vue'
import Card from '@/components/ui/card/Card.vue'
import CardHeader from '@/components/ui/card/CardHeader.vue'
import CardContent from '@/components/ui/card/CardContent.vue'
import Badge from '@/components/ui/Badge.vue'
import {
  leaderboard,
  formatPct,
  returnRate,
} from '@/lib/data'

const emit = defineEmits<{
  select: [id: string, active: boolean]
}>()

const max = computed(() =>
  Math.max(...leaderboard.map((f) => Math.abs(returnRate(f)))),
)

function barWidth(rate: number) {
  return `${(Math.abs(rate) / max.value) * 100}%`
}
</script>

<template>
  <Card class="h-full">
    <CardHeader class="gap-1">
      <h2 class="text-xl font-bold">按收益率排序</h2>
      <p class="text-xs font-medium uppercase tracking-widest text-muted-foreground">收益排行榜</p>
    </CardHeader>
    <CardContent class="space-y-1.5">
      <button
        v-for="(f, i) in leaderboard"
        :key="f.id"
        type="button"
        class="flex w-full items-center gap-4 rounded-xl px-4 py-3 text-left transition-colors hover:bg-muted/60"
        @click="emit('select', f.id, f.active)"
      >
        <span class="w-6 shrink-0 font-mono text-base tabular-nums text-muted-foreground">
          {{ i + 1 }}
        </span>
        <div class="min-w-0 flex-1">
          <div class="flex items-center gap-2">
            <p class="truncate text-base font-semibold">{{ f.name }}</p>
            <Badge
              v-if="!f.active"
              variant="secondary"
              class="shrink-0 font-normal text-muted-foreground"
            >
              归档
            </Badge>
          </div>
          <div class="mt-2 h-2 w-full overflow-hidden rounded-full bg-muted">
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
