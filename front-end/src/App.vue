<script setup>
import { RouterLink, RouterView } from 'vue-router'
</script>

<template>
  <div class="min-h-screen bg-stone-50 text-stone-800 md:flex">
    <aside class="border-b border-stone-200 bg-white p-4 md:w-40 md:shrink-0 md:border-r md:border-b-0">
      <h1 class="mb-6 text-2xl font-semibold">落叶</h1>
      <nav aria-label="主导航" class="flex flex-wrap gap-4 text-emerald-800 md:flex-col">
        <RouterLink to="/chat" active-class="font-bold">对话</RouterLink>
        <RouterLink to="/memory" active-class="font-bold">记忆</RouterLink>
        <RouterLink to="/knowledge" active-class="font-bold">知识库</RouterLink>
        <RouterLink to="/notes" active-class="font-bold">笔记</RouterLink>
        <RouterLink to="/settings" active-class="font-bold">设置</RouterLink>
      </nav>
    </aside>
    <main class="min-w-0 flex-1 p-6"><RouterView /></main>
  </div>
</template>