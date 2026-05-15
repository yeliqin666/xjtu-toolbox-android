import DefaultTheme from 'vitepress/theme'
import './custom.css'
import AppShowcase from '../components/AppShowcase.vue'
import DownloadCard from '../components/DownloadCard.vue'
import PhoneFrame from '../components/PhoneFrame.vue'

export default {
  extends: DefaultTheme,
  enhanceApp({ app }: { app: any }) {
    app.component('AppShowcase', AppShowcase)
    app.component('DownloadCard', DownloadCard)
    app.component('PhoneFrame', PhoneFrame)
  }
}
