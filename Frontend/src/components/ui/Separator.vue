<script setup lang="ts">
import { computed } from 'vue'
import { cn } from '@/lib/utils'

const props = withDefaults(
  defineProps<{
    orientation?: 'horizontal' | 'vertical'
  }>(),
  { orientation: 'horizontal' },
)

const classes = computed(() =>
  cn(
    'shrink-0 bg-border',
    props.orientation === 'horizontal' ? 'h-px w-full' : 'w-px self-stretch',
  ),
)
</script>

<template>
  <div role="separator" :data-orientation="orientation" :class="classes" />
</template>
