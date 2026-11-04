import { createApp } from 'vue'
import App from './App.vue'
import { router } from './app/router'
import './style.css'
import './shared/ui/theme.css'

createApp(App).use(router).mount('#app')
