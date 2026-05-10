<template>
  <div class="showcase-section">
    <h2>功能一览</h2>
    <p class="subtitle">每一个模块都精心设计，让校园生活更简单</p>

    <div class="phones-scroll">
      <div v-for="screen in screens" :key="screen.title" class="phone-item">
        <div class="phone-frame">
          <img v-if="screen.img" :src="screen.img" :alt="screen.title" loading="lazy" />
          <div v-else class="phone-placeholder" :style="{ background: screen.gradient }">
            <span class="p-icon">{{ screen.icon }}</span>
            <span class="p-label">{{ screen.title }}</span>
          </div>
        </div>
        <span class="phone-label">{{ screen.title }}</span>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
interface Screen {
  icon: string
  title: string
  gradient: string
  img?: string
}

const screens: Screen[] = [
  {
    icon: '🗓️',
    title: '智能课表',
    gradient: 'linear-gradient(160deg, #005BAC 0%, #338FD9 100%)',
    img: '/screenshots/schedule.webp',
  },
  {
    icon: '🏫',
    title: '空教室查询',
    gradient: 'linear-gradient(160deg, #1A7FC1 0%, #4FACFE 100%)',
    img: '/screenshots/emptyroom.webp',
  },
  {
    icon: '✅',
    title: '考勤记录',
    gradient: 'linear-gradient(160deg, #0F4C81 0%, #005BAC 100%)',
    img: '/screenshots/attendance.webp',
  },
  {
    icon: '💳',
    title: '校园卡',
    gradient: 'linear-gradient(160deg, #FFB81C 0%, #FF8A00 100%)',
    img: '/screenshots/campuscard.webp',
  },
  {
    icon: '🎫',
    title: '加餐券',
    gradient: 'linear-gradient(160deg, #2E7D32 0%, #66BB6A 100%)',
    img: '/screenshots/coupon.webp',
  },
  {
    icon: '📊',
    title: '成绩查询',
    gradient: 'linear-gradient(160deg, #6A1B9A 0%, #AB47BC 100%)',
    img: '/screenshots/score.webp',
  },
]
</script>
