import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

export default defineConfig({
  plugins: [vue()],

  // IAM 的界面挂在 /iam/app/ 下，**不能也用 /app/**：那是 ask 问数页的路径，
  // 网关同时代理两个前端时 /app/** 会撞车。
  base: '/iam/app/',

  server: {
    port: 5273,
    // 开发时把 IAM 的接口代理过去：登录、验证码、CSRF、授权服务器端点都要同源
    proxy: {
      // 只代理接口，不能写成 '/iam'：那会把 /iam/app/ 自己的页面也转发给后端，
      // 开发时看到的永远是 9000 上的旧构建产物。
      '^/iam/(auth|me|admin)': { target: 'http://127.0.0.1:9000', changeOrigin: true },
      '/oauth2': { target: 'http://127.0.0.1:9000', changeOrigin: true },
      '/captcha': { target: 'http://127.0.0.1:9000', changeOrigin: true },
      '/login': { target: 'http://127.0.0.1:9000', changeOrigin: true },
    },
  },

  build: {
    // 产物必须落在 static/iam/app/：Spring 把 static/ 映射到根路径，
    // 只有这样才能正好对应 /iam/app/ 这个外部路径
    outDir: '../chatdata-agent-iam/src/main/resources/static/iam/app',
    emptyOutDir: true,
    sourcemap: false,
  },
})
