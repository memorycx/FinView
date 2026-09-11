<script setup lang="ts">
import type { Component } from 'vue'
import { Construction } from '@lucide/vue'
import Card from '@/components/ui/card/Card.vue'

defineProps<{
  icon: Component
  title: string
  desc: string
}>()
</script>

<template>
  <div
    class="mx-auto flex min-h-[70vh] w-full max-w-[1400px] items-center justify-center px-4 py-16 sm:px-6 lg:px-8"
  >
    <Card
      class="flex w-full max-w-lg flex-col items-center gap-4 px-8 py-14 text-center"
    >
      <div
        class="flex size-16 items-center justify-center rounded-2xl bg-brand/10 text-brand"
      >
        <component :is="icon" class="size-8" />
      </div>
      <h2 class="text-2xl font-bold tracking-tight">{{ title }}</h2>
      <p class="text-sm leading-relaxed text-muted-foreground">{{ desc }}</p>
      <span
        class="mt-2 inline-flex items-center gap-1.5 rounded-full bg-brand/10 px-3 py-1 text-xs font-medium text-brand"
      >
        <Construction class="size-3.5" />
        功能建设中 · 敬请期待
      </span>
    </Card>
  </div>
</template>
