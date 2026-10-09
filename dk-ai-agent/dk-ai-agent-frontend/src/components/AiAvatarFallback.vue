<template>
  <div class="ai-avatar-fallback" :class="type">
    <img :src="brandLogoUrl" alt="AI 心理咨询师头像" width="36" height="36" draggable="false" />
  </div>
</template>

<script setup>
import { brandLogoUrl } from '../brand'

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
  border-radius: 50%;
  overflow: hidden;
  background: #fff;
}

.ai-avatar-fallback img {
  display: block;
  width: 100%;
  height: 100%;
  box-sizing: border-box;
  padding: 4px;
  object-fit: contain;
}
</style>