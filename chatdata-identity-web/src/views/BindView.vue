<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { confirmBind, previewBind, type BindPreview } from '../api/channel'
import { me } from '../api/auth'
import { errorMessage } from '../api/http'
import { session } from '../composables/useSession'

/**
 * 渠道绑定页（`/bind?ticket=...`）。
 *
 * ## 它是飞书那条链路在 Web 侧的落点
 *
 * 用户在飞书里发消息 → 未绑定 → 机器人回一条带 ticket 的链接 → 点进来是这页。
 *
 * ## 用户看到什么，取决于两件事
 *
 * 1. 有没有登录 —— 没登录时先跳登录页，登录后回到这里（returnTo）
 * 2. 票据有没有效 —— 失效时明说「请回飞书重新发消息」，而不是一个空白页
 *
 * ## 为什么要先"预览"再"确认"
 *
 * 直接消费票据的话，用户点开链接、愣了一下、按浏览器返回，再进来就失效了。
 * 预览只读，确认才消费。
 */
const route = useRoute()
const router = useRouter()

const ticket = computed(() => {
  const t = route.query['ticket']
  return typeof t === 'string' ? t : ''
})

const preview = ref<BindPreview | null>(null)
const loading = ref(true)
const submitting = ref(false)
const error = ref('')
const done = ref(false)

onMounted(async () => {
  try {
    // **先单独确认登录态**，而不是靠 previewBind 的返回。
    //
    // previewBind 打的是 /iam/channel/bind，未登录时被过滤链拦成 401，
    // 于是它抛异常、走 catch——根本走不到下面那个 `!p.authenticated` 分支。
    // 结果就是未登录的用户看到「请求失败（HTTP 401）」而不是被送去登录。
    //
    // /iam/me 不同：未登录时它返回 200 + authenticated:false，是个可判定的状态。
    const principal = await me()
    if (!principal.authenticated) {
      await router.replace({ name: 'login', query: { returnTo: route.fullPath } })
      return
    }
    // 登录态来自 /iam/me，同步到全局 session，
    // 让路由守卫与后续页面看到一致的身份
    session.setUser(principal)

    preview.value = await previewBind(ticket.value)
  } catch (e: unknown) {
    error.value = errorMessage(e)
  } finally {
    loading.value = false
  }
})

async function confirm(): Promise<void> {
  if (submitting.value) return
  submitting.value = true
  error.value = ''
  try {
    await confirmBind(ticket.value)
    done.value = true
  } catch (e: unknown) {
    error.value = errorMessage(e)
  } finally {
    submitting.value = false
  }
}

function channelLabel(channel: string | undefined): string {
  const known: Record<string, string> = { feishu: '飞书', dingtalk: '钉钉', wecom: '企业微信' }
  return channel ? known[channel] ?? channel : '渠道'
}
</script>

<template>
  <section class="bind">
    <div class="card">
      <!-- 加载中 -->
      <template v-if="loading">
        <span class="spinner" aria-hidden="true" />
        <p class="sub">正在读取绑定信息…</p>
      </template>

      <!-- 已绑定 -->
      <template v-else-if="done">
        <span class="mark ok" aria-hidden="true">✓</span>
        <h1 class="page-title">绑定成功</h1>
        <p class="sub">
          现在可以回 {{ channelLabel(preview?.channel) }} 直接提问了。
          你的权限与在问数页面看到的完全一致。
        </p>
        <a class="btn btn-primary" href="/app/ask">去问数</a>
      </template>

      <!-- 票据无效 / 接口报错 -->
      <template v-else-if="error || preview?.valid === false">
        <span class="mark" aria-hidden="true">!</span>
        <h1 class="page-title">无法完成绑定</h1>
        <p class="sub">{{ error || preview?.message || '链接无效。' }}</p>
      </template>

      <!-- 正常：确认 -->
      <template v-else>
        <span class="mark" aria-hidden="true">@</span>
        <h1 class="page-title">绑定 {{ channelLabel(preview?.channel) }} 账号</h1>
        <p class="sub">
          {{ channelLabel(preview?.channel) }}账号
          <strong>{{ preview?.displayName || preview?.externalIdMasked }}</strong>
          <template v-if="preview?.externalIdMasked">（{{ preview.externalIdMasked }}）</template>
          将绑定到当前登录账号 <strong>{{ preview?.currentUser }}</strong>。
        </p>
        <p class="sub note">
          绑定后，你在{{ channelLabel(preview?.channel) }}里提的问题会以这个账号的身份执行，
          能看的数据与这里完全相同。不是你本人的账号请勿绑定。
        </p>
        <div class="acts">
          <button class="btn btn-primary" type="button" :disabled="submitting" @click="confirm">
            {{ submitting ? '绑定中…' : '确认绑定' }}
          </button>
          <a class="btn btn-quiet" href="/app/ask">不是我，取消</a>
        </div>
      </template>
    </div>
  </section>
</template>

<style scoped>
.bind {
  display: grid;
  place-items: center;
  min-height: 100dvh;
  padding: var(--space-6) var(--space-4);
}

.card {
  width: min(520px, 100%);
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: var(--space-4);
  padding: var(--space-6);
  background: var(--surface);
  border: 1px solid var(--line);
  border-radius: 16px;
  box-shadow: 0 12px 40px rgba(46, 58, 120, 0.08);
  text-align: center;
}

.mark {
  display: grid;
  place-items: center;
  width: 48px;
  height: 48px;
  border-radius: 50%;
  background: color-mix(in srgb, var(--accent) 14%, transparent);
  color: var(--accent-deep);
  font-size: 22px;
  font-weight: 700;
}

.mark.ok {
  background: color-mix(in srgb, #19a974 16%, transparent);
  color: #0f7a52;
}

.page-title {
  font-size: var(--text-xl);
  font-weight: 700;
}

.sub {
  color: var(--ink-soft);
  line-height: 1.7;
}

.note {
  font-size: var(--text-sm);
}

.acts {
  display: flex;
  gap: var(--space-3);
  flex-wrap: wrap;
  justify-content: center;
}

.spinner {
  width: 22px;
  height: 22px;
  border: 2px solid var(--line);
  border-top-color: var(--accent);
  border-radius: 50%;
  animation: spin 0.8s linear infinite;
}

@keyframes spin {
  to {
    transform: rotate(360deg);
  }
}
</style>
