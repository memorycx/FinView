<script setup lang="ts">
import { computed, type Component } from 'vue'
import { useRoute } from 'vue-router'
import { Construction } from '@lucide/vue'
import PlaceholderPage from '@/components/PlaceholderPage.vue'

const route = useRoute()

const title = computed(() => String(route.meta.title ?? ''))
const desc = computed(() => String(route.meta.desc ?? ''))
const icon = computed<Component>(
  () => (route.meta.icon as Component | undefined) ?? Construction,
)
</script>

<template>
  <PlaceholderPage :icon="icon" :title="title" :desc="desc" />
</template>
