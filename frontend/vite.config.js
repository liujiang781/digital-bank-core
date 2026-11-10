import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

// 开发期 /api 代理到网关（gateway，默认 8080）
// 生产/演示期由网关或 Nginx 直接托管，见 ../deploy
export default defineConfig({
  plugins: [vue()],
  build: {
    rollupOptions: {
      output: {
        // element-plus 单独拆包，避免主包过大
        manualChunks: {
          'element-plus': ['element-plus']
        }
      }
    }
  },
  server: {
    port: 5173,
    proxy: {
      '/api': {
        target: 'http://localhost:8080',
        changeOrigin: true
      }
    }
  }
})
