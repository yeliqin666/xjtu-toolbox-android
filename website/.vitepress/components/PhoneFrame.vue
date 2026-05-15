<template>
  <div class="phone-item">
    <div class="phone-outer">
      <div class="phone-notch"></div>
      <div class="phone-screen">
        <iframe
          :src="demoSrc"
          :title="title"
          sandbox="allow-scripts allow-same-origin"
          loading="lazy"
          scrolling="no"
        ></iframe>
      </div>
    </div>
    <span class="phone-label">{{ title }}</span>
  </div>
</template>

<script setup lang="ts">
import { withBase, useData } from 'vitepress'
import { computed } from 'vue'

const props = defineProps<{
  screen: string
  title: string
}>()

const { isDark } = useData()

const demoSrc = computed(() =>
  withBase(`/demo/daizon/index.html?screen=${props.screen}&dark=${isDark.value}`)
)
</script>

<style scoped>
.phone-item {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 14px;
  flex: 0 0 auto;
}

.phone-outer {
  position: relative;
  width: 220px;
  height: 440px;
  border-radius: 36px;
  border: 7px solid var(--vp-c-divider);
  background: var(--vp-c-bg-soft);
  box-shadow:
    0 0 0 1px var(--vp-c-divider),
    0 24px 64px rgba(0,0,0,0.18),
    inset 0 1px 0 rgba(255,255,255,0.12);
  overflow: hidden;
  transition:
    transform 0.35s cubic-bezier(0.34,1.56,0.64,1),
    box-shadow 0.35s ease;
}

.phone-outer:hover {
  transform: translateY(-10px) scale(1.04);
  box-shadow:
    0 0 0 1px var(--vp-c-divider),
    0 40px 80px rgba(0,91,172,0.22),
    inset 0 1px 0 rgba(255,255,255,0.12);
}

.phone-notch {
  position: absolute;
  top: 8px;
  left: 50%;
  transform: translateX(-50%);
  width: 72px;
  height: 6px;
  background: var(--vp-c-divider);
  border-radius: 3px;
  z-index: 10;
  pointer-events: none;
}

.phone-screen {
  position: relative;
  width: 100%;
  height: 100%;
  border-radius: 30px;
  overflow: hidden;
  background: var(--vp-c-bg);
}

.phone-screen iframe {
  position: absolute;
  top: 0;
  left: 0;
  /* Render at standard mobile viewport, then scale down */
  width: 360px;
  height: 720px;
  border: none;
  display: block;
  /* phone-outer inner content area is ~206px wide; 206/360 ≈ 0.572 */
  transform: scale(calc(206 / 360));
  transform-origin: top left;
}

.phone-label {
  font-size: 0.9rem;
  font-weight: 600;
  color: var(--vp-c-text-2);
  letter-spacing: 0.02em;
}
</style>
