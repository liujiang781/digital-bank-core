import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

// 开发期 /api 代理到网关（gateway 实际端口 8090，与 deploy/start-all.ps1 一致；可用 GATEWAY_URL 覆盖）
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
        target: process.env.GATEWAY_URL || 'http://localhost:8090',
        changeOrigin: true
      }
    }
  }
})
