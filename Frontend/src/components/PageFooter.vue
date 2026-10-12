<script setup lang="ts">
/**
 * 页面脚注：每个界面底部同一句免责声明（原来只有「投资看板」有，现在各页面复用）。
 *
 * 用法：作为页面根容器（纵向 flex、至少一屏高）的**最后一个子节点**。
 * - mt-auto 把它顶到底部：内容短时贴视口底、内容长时自然跟在后面；
 * - 外层 pt-8 + 内层 border-t / pt-6 保证内容再长，分隔线上下也留得出空白
 *   （mt-auto 在没有富余高度时会归零，间距不能只靠它）。
 */
</script>

<template>
  <footer class="mt-auto shrink-0 pt-8 lg:pt-4">
    <p class="border-t border-border pt-6 text-center text-xs text-muted-foreground lg:pt-4">
      数据仅用于展示与分析，不构成任何投资建议
    </p>
  </footer>
</template>
