<template>
  <div class="showcase-section">
    <h2>功能一览</h2>
    <p class="subtitle">真实界面，所见即所得——每一个模块都精心设计</p>
    <div class="phones-scroll">
      <PhoneFrame v-for="s in screens" :key="s.screen" :screen="s.screen" :title="s.title" />
    </div>
  </div>
</template>

<script setup lang="ts">
const screens = [
  { screen: 'schedule',   title: '智能课表' },
  { screen: 'attendance', title: '考勤记录' },
  { screen: 'campuscard', title: '校园卡'   },
  { screen: 'coupon',     title: '加餐券'   },
  { screen: 'emptyroom',  title: '空教室'   },
  { screen: 'score',      title: '成绩查询' },
]
</script>
