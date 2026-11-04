import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

export default defineConfig({
  plugins: [vue()],

  // 构建产物挂在后端静态资源目录的 app/ 下，故 base 必须是 /app/。
  // 不设的话 index.html 会去根路径找 assets，表现为**页面白屏、控制台一堆 404**。
  base: '/app/',

  server: {
    port: 5173,
    // 开发时把 /sys 与 /api 代理到**网关**（不是直接代理到 ask），避免跨域。
    // 生产构建产物直接放进 chatdata-query-service 的 static 目录，同源，不需要代理。
    //
    // ⚠️ 端口是 8082 不是 8080：8080 被本地 agentscope-service 的 Docker 容器占着
    // （做 aistio 接入时起的）。8082 是 gateway 的默认端口，见 chatdata-gateway 的
    // application.yml。
    proxy: {
      '/sys': { target: 'http://127.0.0.1:8082', changeOrigin: true },
      '/api': { target: 'http://127.0.0.1:8082', changeOrigin: true },
    },
  },

  build: {
    // 构建产物直接落到后端的静态资源目录，省掉一步拷贝。
    // 若该目录不存在，Vite 会创建它。
    outDir: '../chatdata-query-service/src/main/resources/static/app',
    emptyOutDir: true,
    // 问数界面不需要 sourcemap 进生产包
    sourcemap: false,
  },
})
