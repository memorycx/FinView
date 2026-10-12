<script setup lang="ts">
import { Moon, Sun } from '@lucide/vue'
import Button from '@/components/ui/Button.vue'
import { useTheme } from '@/composables/useTheme'

const { isDark, toggleTheme } = useTheme()
</script>

<template>
  <Button
    variant="outline"
    size="icon"
    class="rounded-full"
    :aria-label="isDark ? '切换到浅色主题' : '切换到深色主题'"
    @click="toggleTheme"
  >
    <Sun v-if="isDark" class="size-4" />
    <Moon v-else class="size-4" />
  </Button>
</template>
