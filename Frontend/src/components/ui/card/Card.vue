<template>
  <div
    data-slot="card"
    class="group/card flex flex-col gap-(--card-spacing) overflow-hidden rounded-xl bg-card py-(--card-spacing) text-sm text-card-foreground ring-1 ring-foreground/10 [--card-spacing:--spacing(5)]"
  >
    <slot />
  </div>
</template>
