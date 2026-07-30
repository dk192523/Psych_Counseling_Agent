<template>
  <div class="ai-avatar-fallback" :class="type">
    <span v-if="type === 'psych'">🧭</span>
    <span v-else>🤖</span>
  </div>
</template>

<script setup>
defineProps({
  type: {
    type: String,
    default: 'default'
  }
})
</script>

<style scoped>
.ai-avatar-fallback {
  width: 100%;
  height: 100%;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 18px;
  border-radius: 50%;
}

.psych {
  background: linear-gradient(45deg, #2c7a7b, #4fd1c5);
}

.default {
  background: linear-gradient(45deg, #3f51b5, #5677fc);
}
</style> 
