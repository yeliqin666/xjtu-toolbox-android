import { defineConfig } from 'vitepress'

export default defineConfig({
  title: '岱宗盒子',
  description: '西安交通大学校园生活一站式工具箱',
  lang: 'zh-CN',
  base: '/xjtu-toolbox-android/',

  head: [
    ['link', { rel: 'icon', type: 'image/png', href: '/xjtu-toolbox-android/logo.png' }],
    ['meta', { name: 'theme-color', content: '#005BAC' }],
    ['meta', { property: 'og:title', content: '岱宗盒子' }],
    ['meta', { property: 'og:description', content: '西安交通大学校园生活一站式工具箱' }],
  ],

  themeConfig: {
    logo: '/logo.png',
    siteTitle: '岱宗盒子',

    nav: [
      { text: '首页', link: '/' },
      { text: '下载', link: '/download' },
      { text: '更新日志', link: '/changelog' },
    ],

    socialLinks: [
      { icon: 'github', link: 'https://github.com/yeliqin666/xjtu-toolbox-android' },
      {
        icon: {
          svg: '<svg role="img" viewBox="0 0 24 24" xmlns="http://www.w3.org/2000/svg"><title>Gitee</title><path d="M11.984 0A12 12 0 0 0 0 12a12 12 0 0 0 12 12 12 12 0 0 0 12-12A12 12 0 0 0 12 0a12 12 0 0 0-.016 0zm6.09 5.333c.328 0 .593.266.592.593v1.482a.594.594 0 0 1-.593.592H9.777c-.982 0-1.778.796-1.778 1.778v5.63c0 .327.266.592.593.592h5.63c.982 0 1.778-.796 1.778-1.778v-.296a.593.593 0 0 0-.592-.593h-4.15a.592.592 0 0 1-.592-.592v-1.482a.593.593 0 0 1 .593-.592h6.815c.327 0 .593.265.593.592v3.408a4 4 0 0 1-4 4H5.926a.593.593 0 0 1-.593-.593V9.778a4.444 4.444 0 0 1 4.445-4.444h8.296Z"/></svg>'
        },
        link: 'https://gitee.com/yeliqin666/xjtu-toolbox-android'
      }
    ],

    footer: {
      message: '基于 GPL-3.0 协议开源 · 与西安交通大学官方无关',
      copyright: 'Copyright © 2024-2025 岱宗盒子 Contributors'
    },

    search: {
      provider: 'local'
    }
  }
})
