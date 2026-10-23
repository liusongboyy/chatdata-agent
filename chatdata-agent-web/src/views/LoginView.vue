<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { bffLoginUrl } from '../api/auth'

/**
 * 登录页：**本应用不再有登录表单**。
 *
 * 身份归 IAM，浏览器通过网关的 BFF 走授权码流程。这里只做一次跳转，
 * 并保留一个手动按钮——自动跳转被浏览器拦下时（极少见）用户还有出路。
 *
 * 为什么删掉表单而不是留着：留一个"本应用的登录框"会让人以为有两套账号，
 * 而实际上密码只存在 IAM 一处。
 */
const failed = ref(false)

onMounted(() => {
  try {
    window.location.assign(bffLoginUrl())
  } catch {
    failed.value = true
  }
})
</script>

<template>
  <main class="shell">
    <div class="glow glow-a" aria-hidden="true" />
    <div class="glow glow-b" aria-hidden="true" />

    <header class="brand">
      <span class="logo" aria-hidden="true">问</span>
      <span class="wordmark">问数</span>
      <small class="mono">CHATDATA AGENT</small>
    </header>

    <section class="panel">
      <span class="spinner" aria-hidden="true" />
      <h1>正在跳转登录…</h1>
      <p class="sub">账号由「账号中心」统一管理，稍候会自动进入。</p>
      <p v-if="failed" class="note note-danger" role="alert">
        自动跳转失败，请点击下面的按钮。
      </p>
      <a class="btn btn-primary submit" :href="bffLoginUrl()">前往登录</a>
      <p class="fine">密码只保存在账号中心一处</p>
    </section>
  </main>
</template>

<style scoped>
.shell {
  position: relative;
  min-height: 100dvh;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 26px;
  padding: var(--space-6) var(--space-5);
  overflow: hidden;
}

.glow {
  position: absolute;
  border-radius: 50%;
  pointer-events: none;
}

.glow-a {
  width: 720px;
  height: 720px;
  left: -240px;
  top: -320px;
  background: radial-gradient(circle, rgba(91, 108, 255, 0.22), transparent 66%);
}

.glow-b {
  width: 640px;
  height: 640px;
  right: -220px;
  bottom: -340px;
  background: radial-gradient(circle, rgba(139, 92, 246, 0.2), transparent 66%);
}

.brand {
  position: relative;
  z-index: 2;
  display: flex;
  align-items: center;
  gap: 10px;
  font-size: 15px;
  font-weight: 700;
  letter-spacing: 0.04em;
}

.logo {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 34px;
  height: 34px;
  border-radius: 10px;
  background: var(--gradient-accent);
  color: #fff;
  font-size: 15px;
  font-weight: 800;
  box-shadow: 0 8px 18px rgba(91, 108, 255, 0.28);
}

.brand small {
  margin-left: 4px;
  font-size: 9px;
  font-weight: 500;
  letter-spacing: 0.16em;
  color: var(--ink-faint);
}

.panel {
  position: relative;
  z-index: 2;
  display: flex;
  flex-direction: column;
  align-items: center;
  width: min(400px, 100%);
  padding: 34px 32px 28px;
  background: var(--surface);
  border-radius: var(--radius-lg);
  box-shadow: var(--shadow);
  text-align: center;
}

.spinner {
  width: 30px;
  height: 30px;
  margin-bottom: 18px;
  border: 3px solid var(--accent-soft);
  border-top-color: var(--accent);
  border-radius: 50%;
  animation: spin 0.8s linear infinite;
}

@keyframes spin {
  to {
    transform: rotate(360deg);
  }
}

.panel h1 {
  font-size: 20px;
  font-weight: 750;
}

.sub {
  margin-top: 8px;
  font-size: 12px;
  color: var(--ink-soft);
}

.note {
  width: 100%;
  margin-top: 16px;
  text-align: left;
}

.submit {
  width: 100%;
  height: 42px;
  margin-top: 22px;
  text-decoration: none;
}

.fine {
  margin-top: 12px;
  font-size: 10px;
  color: var(--ink-faint);
}
</style>
