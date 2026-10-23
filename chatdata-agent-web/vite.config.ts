import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

export default defineConfig({
  plugins: [vue()],

  // 构建产物挂在后端静态资源目录的 app/ 下，故 base 必须是 /app/。
  // 不设的话 index.html 会去根路径找 assets，表现为**页面白屏、控制台一堆 404**。
  base: '/app/',

  server: {
    port: 5173,
    // 开发时把 /sys 与 /api 代理到后端，避免跨域。
    // 生产构建产物直接放进 chatdata-agent-ask 的 static 目录，同源，不需要代理。
    proxy: {
      '/sys': { target: 'http://127.0.0.1:8080', changeOrigin: true },
      '/api': { target: 'http://127.0.0.1:8080', changeOrigin: true },
    },
  },

  build: {
    // 构建产物直接落到后端的静态资源目录，省掉一步拷贝。
    // 若该目录不存在，Vite 会创建它。
    outDir: '../chatdata-agent-ask/src/main/resources/static/app',
    emptyOutDir: true,
    // 问数界面不需要 sourcemap 进生产包
    sourcemap: false,
  },
})
