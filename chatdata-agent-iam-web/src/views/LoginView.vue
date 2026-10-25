<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { captchaUrl, ensureCsrf, login, newCheckKey } from '../api/auth'
import { errorMessage } from '../api/http'
import { session } from '../composables/useSession'
import { firstAllowedPath } from '../router'

/**
 * 登录页。
 *
 * ## 它同时是授权码流程的一环
 *
 * 授权服务器在未认证时会把人送到 `/app/login?returnTo=/oauth2/authorize?...`。
 * 登录成功后必须**跳回 returnTo**，否则授权码流程就断在这里——
 * 用户看到的是"登录成功了但没进系统"。
 *
 * ## 它不签发令牌
 *
 * 登录接口只建立服务端会话（SESSION Cookie）。令牌由授权码流程签发。
 *
 * ## 版式
 *
 * 样张 C（`.scratch/ui-redesign/c-friendly.html`）：浅蓝灰底 + 双色渐变，
 * 左侧是问数结果预览、右侧是登录卡。左侧只是示意，不接真实接口——
 * 它的作用是让人在一秒内明白"登录进来能做什么"，不该为了它多打一次查询。
 */
const route = useRoute()
const router = useRouter()

const username = ref('')
const password = ref('')
const captcha = ref('')
const checkKey = ref(newCheckKey())
const submitting = ref(false)
const error = ref('')

const captchaSrc = computed(() => captchaUrl(checkKey.value))

function refreshCaptcha(): void {
  // 换 key 就是换图：后端按 key 存验证码，旧 key 的那张立即失效
  checkKey.value = newCheckKey()
  captcha.value = ''
}

/**
 * 归一化 returnTo：只接受同源地址，返回 path+query。
 *
 * 授权服务器存下来的是**绝对地址**（`http://host/oauth2/authorize?...`），
 * 而 `//evil.com` 这类协议相对写法必须挡掉——否则就是一个开放重定向。
 */
function safeReturnTo(value: unknown): string | null {
  if (typeof value !== 'string' || value.trim() === '') return null
  try {
    const url = new URL(value, window.location.origin)
    if (url.origin !== window.location.origin) return null
    return url.pathname + url.search + url.hash
  } catch {
    return null
  }
}

async function submit(): Promise<void> {
  if (submitting.value) return
  error.value = ''

  if (!username.value.trim() || !password.value) {
    error.value = '请填写账号与密码'
    return
  }
  if (!captcha.value.trim()) {
    error.value = '请填写验证码'
    return
  }

  submitting.value = true
  try {
    await ensureCsrf()
    const principal = await login(
      username.value.trim(),
      password.value,
      captcha.value.trim(),
      checkKey.value,
    )
    session.setUser(principal)

    const target = safeReturnTo(route.query.returnTo)
    if (target && target.startsWith('/oauth2')) {
      // 授权服务器的端点：整页跳转，让浏览器带着会话继续授权码流程
      window.location.assign(target)
      return
    }
    // 没有 returnTo 时跳**第一个有权限的页面**。写死 /admin/users 的话，
    // 只有「角色」权限的人会被守卫弹回来，而弹回的目标又是 /admin/users——来回跳
    await router.replace(target ?? firstAllowedPath(principal.permissions ?? []) ?? '/no-access')
  } catch (e: unknown) {
    error.value = errorMessage(e)
    // 验证码一次性，失败后必须换一张
    refreshCaptcha()
  } finally {
    submitting.value = false
  }
}

onMounted(async () => {
  // 先取一次 CSRF：写请求要带 X-XSRF-TOKEN，而它来自这次响应下发的 Cookie
  try {
    await ensureCsrf()
  } catch (e: unknown) {
    error.value = `初始化失败：${errorMessage(e)}`
  }
})
</script>

<template>
  <main class="login">
    <div class="glow glow-a" aria-hidden="true" />
    <div class="glow glow-b" aria-hidden="true" />

    <header class="lg-top">
      <span class="logo" aria-hidden="true">问</span>
      <span class="wordmark">问数</span>
      <small class="mono">CHATDATA AGENT</small>
    </header>

    <section class="hero">
      <span class="pill"><i aria-hidden="true" />账号中心 · 安全登录</span>
      <h1>欢迎回来，<span>今天想查点什么？</span></h1>
      <p>一个账号，连接你的组织、角色与数据权限。</p>
    </section>

    <div class="cards">
      <!-- 问数效果预览。刻意用静态内容：它的作用是"让人知道这是什么产品"，
           不该为了它多打一次查询；也不该把真实数据放到登录页上 -->
      <aside class="card chat" aria-hidden="true">
        <div class="chat-head">
          <b>问数助手</b>
          <span>预览</span>
        </div>
        <div class="bubble me">上个月华东的有效保单保费合计是多少？</div>
        <div class="bubble ai">
          <small>已理解你的问题</small>
          正在查询「华东 / 有效保单 / 上个月」…
        </div>
        <div class="answer">
          <div class="val">627,604.<small>31</small></div>
          <div class="cap">保费合计 · 环比 +12.4%</div>
          <div class="mini-bars">
            <i style="height: 40%" /><i style="height: 55%" /><i style="height: 47%" />
            <i style="height: 68%" /><i style="height: 61%" /><i style="height: 84%" />
            <i style="height: 74%" /><i style="height: 96%" />
          </div>
        </div>
        <div class="chat-foot"><i aria-hidden="true" />已生成 SQL，可查看取数过程</div>
      </aside>

      <section class="card form-card">
        <h2>登录账号中心</h2>
        <p class="sub">使用你的管理员或业务账号继续</p>

        <form class="form" @submit.prevent="submit">
          <label class="field">
            <span class="label">账号</span>
            <input
              v-model="username"
              class="input"
              type="text"
              autocomplete="username"
              :disabled="submitting"
            />
          </label>

          <label class="field">
            <span class="label">密码</span>
            <input
              v-model="password"
              class="input"
              type="password"
              autocomplete="current-password"
              :disabled="submitting"
            />
          </label>

          <label class="field">
            <span class="label">验证码</span>
            <div class="captcha-row">
              <input
                v-model="captcha"
                class="input"
                type="text"
                maxlength="4"
                autocomplete="off"
                :disabled="submitting"
              />
              <button type="button" class="captcha-box" title="点击换一张" @click="refreshCaptcha">
                <img :src="captchaSrc" alt="验证码，点击换一张" />
              </button>
            </div>
          </label>

          <p v-if="error" class="note note-danger" role="alert">{{ error }}</p>

          <button class="btn btn-primary submit" type="submit" :disabled="submitting">
            {{ submitting ? '登录中…' : '登 录' }}
          </button>
        </form>

        <p class="fine">登录即表示同意组织的安全策略 · 账号异常请联系管理员</p>
      </section>
    </div>
  </main>
</template>

<style scoped>
.login {
  position: relative;
  min-height: 100dvh;
  display: flex;
  flex-direction: column;
  align-items: center;
  padding: var(--space-6) var(--space-5) var(--space-7);
  overflow: hidden;
}

/* 背景的两团渐变光。只做氛围，不承载信息；低透明度避免影响文字对比度 */
.glow {
  position: absolute;
  border-radius: 50%;
  pointer-events: none;
}

.glow-a {
  width: 760px;
  height: 760px;
  left: -240px;
  top: -300px;
  background: radial-gradient(circle, rgba(0, 0, 0, 0.12), transparent 66%);
}

.glow-b {
  width: 680px;
  height: 680px;
  right: -200px;
  bottom: -320px;
  background: radial-gradient(circle, rgba(0, 0, 0, 0.05), transparent 66%);
}

.lg-top {
  position: relative;
  z-index: 2;
  display: flex;
  align-items: center;
  align-self: flex-start;
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
  box-shadow: 0 8px 18px rgba(0, 0, 0, 0.14);
}

.lg-top small {
  margin-left: 4px;
  font-size: 9px;
  font-weight: 500;
  letter-spacing: 0.16em;
  color: var(--ink-faint);
}

.hero {
  position: relative;
  z-index: 2;
  max-width: 640px;
  margin-top: var(--space-7);
  text-align: center;
}

.pill {
  display: inline-flex;
  align-items: center;
  gap: 7px;
  padding: 6px 12px;
  border-radius: var(--radius-pill);
  background: var(--surface);
  box-shadow: 0 4px 14px rgba(46, 58, 120, 0.07);
  font-size: 11px;
  color: var(--ink-soft);
}

.pill i {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: var(--ok);
}

.hero h1 {
  margin: 18px 0 0;
  font-size: clamp(1.9rem, 1.2rem + 2.4vw, 2.8rem);
  line-height: 1.24;
  font-weight: 750;
  letter-spacing: -0.02em;
}

.hero h1 span {
  /* 不再加底色：黄底实看太跳。强调靠字重与断句，不靠颜色 */
  color: var(--ink);
}

.hero p {
  margin: 12px 0 0;
  font-size: var(--text-sm);
  color: var(--ink-soft);
}

.cards {
  position: relative;
  z-index: 2;
  display: grid;
  grid-template-columns: 1fr 1.05fr;
  gap: 26px;
  width: min(980px, 100%);
  margin-top: var(--space-6);
}

.card {
  background: var(--surface);
  border-radius: var(--radius-lg);
  box-shadow: var(--shadow);
}

/* ── 左侧预览 ── */
.chat {
  display: flex;
  flex-direction: column;
  gap: 14px;
  padding: 26px 26px 24px;
}

.chat-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 2px;
}

.chat-head b {
  font-size: 13px;
  font-weight: 700;
}

.chat-head span {
  font-size: 10px;
  color: var(--ink-faint);
}

.bubble {
  max-width: 86%;
  padding: 11px 14px;
  border-radius: 14px;
  font-size: 12.5px;
  line-height: 1.65;
}

.bubble.me {
  align-self: flex-end;
  background: var(--gradient-accent);
  color: #fff;
  border-bottom-right-radius: 4px;
}

.bubble.ai {
  align-self: flex-start;
  background: var(--surface-sunk);
  color: var(--ink);
  border-bottom-left-radius: 4px;
}

.bubble.ai small {
  display: block;
  margin-bottom: 4px;
  font-size: 9.5px;
  color: var(--ink-faint);
}

.answer {
  margin-top: 2px;
  padding: 14px 16px;
  border: 1px solid var(--line);
  border-radius: 12px;
  background: linear-gradient(180deg, #f7f8ff, #fff);
}

.answer .val {
  font-family: var(--font-mono);
  font-size: 28px;
  font-weight: 700;
  line-height: 1;
  letter-spacing: -0.02em;
}

.answer .val small {
  font-size: 14px;
  color: var(--ink-soft);
}

.answer .cap {
  margin-top: 6px;
  font-size: 10px;
  color: var(--ink-faint);
}

.mini-bars {
  display: flex;
  align-items: flex-end;
  gap: 5px;
  height: 42px;
  margin-top: 12px;
}

.mini-bars i {
  flex: 1;
  border-radius: 3px 3px 0 0;
  background: linear-gradient(to top, var(--ink), hsl(0 0% 62%));
}

.chat-foot {
  display: flex;
  align-items: center;
  gap: 7px;
  margin-top: auto;
  padding-top: 6px;
  font-size: 10px;
  color: var(--ink-faint);
}

.chat-foot i {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: var(--ok);
}

/* ── 右侧登录 ── */
.form-card {
  padding: 30px 32px 26px;
}

.form-card h2 {
  margin: 0;
  font-size: 22px;
  font-weight: 750;
  letter-spacing: -0.01em;
}

.form-card .sub {
  margin: 7px 0 22px;
  font-size: 12px;
  color: var(--ink-soft);
}

.form {
  display: flex;
  flex-direction: column;
  gap: 14px;
}

.field {
  display: flex;
  flex-direction: column;
  gap: 7px;
}

.label {
  font-size: 11px;
  font-weight: 600;
  color: var(--ink-soft);
}

.input {
  height: 42px;
  padding: 0 13px;
  background: var(--surface-sunk);
  border: 1px solid var(--line-strong);
  border-radius: var(--radius);
  font-size: var(--text-sm);
}

.input:focus {
  outline: none;
  border-color: var(--ink);
  background: var(--surface);
  box-shadow: var(--focus-ring);
}

.captcha-row {
  display: flex;
  gap: 9px;
}

.captcha-row .input {
  flex: 1;
}

.captcha-box {
  display: grid;
  place-items: center;
  width: 108px;
  height: 42px;
  flex: none;
  padding: 0;
  overflow: hidden;
  background: #f1f3ff;
  border: 1px solid var(--line-strong);
  border-radius: 10px;
}

.captcha-box img {
  display: block;
  width: 100%;
  height: 100%;
  object-fit: cover;
}

.submit {
  width: 100%;
  height: 44px;
  margin-top: 4px;
  font-size: 13px;
  letter-spacing: 0.08em;
}

.fine {
  margin: 14px 0 0;
  text-align: center;
  font-size: 10.5px;
  color: var(--ink-faint);
}

@media (max-width: 860px) {
  .login {
    padding: var(--space-5) var(--space-4);
  }

  .hero {
    margin-top: var(--space-6);
  }

  .cards {
    grid-template-columns: 1fr;
    gap: var(--space-4);
    max-width: 520px;
  }

  /* 窄屏隐藏预览：登录才是这一屏唯一的任务 */
  .chat {
    display: none;
  }

  .form-card {
    padding: var(--space-5);
  }
}
</style>
