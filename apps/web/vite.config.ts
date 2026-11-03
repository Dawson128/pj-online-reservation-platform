import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

export default defineConfig({
  plugins: [vue()],
  server: {
    port: 5173,
    strictPort: true,
    proxy: {
      '/api/m1': 'http://127.0.0.1:8081',
      '/api/m2': 'http://127.0.0.1:8082',
      '/api/m3': 'http://127.0.0.1:8083',
    },
  },
})
