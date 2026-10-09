<template>
  <img
    class="brand-logo-image"
    :class="{ 'brand-logo-plain': plain }"
    :src="brandLogoUrl"
    :alt="alt"
    :width="size"
    :height="size"
    :style="{ '--brand-logo-size': `${size}px` }"
    draggable="false"
  />
</template>

<script setup>
import { brandLogoUrl } from '../brand'

defineProps({
  size: { type: Number, default: 32 },
  plain: { type: Boolean, default: false },
  alt: { type: String, default: 'AI 心理咨询师标志' }
})
</script>

<style scoped>
.brand-logo-image {
  display: block;
  width: var(--brand-logo-size);
  height: var(--brand-logo-size);
  flex: 0 0 auto;
  box-sizing: border-box;
  padding: 3px;
  object-fit: contain;
  border-radius: 50%;
  background: #fff;
}
.brand-logo-plain {
  padding: 0;
  border-radius: 0;
  background: transparent;
}
</style>