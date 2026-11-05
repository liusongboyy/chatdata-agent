<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { bindStatus, unbindChannel, type BindStatus } from '../api/channel'
import { errorMessage } from '../api/http'

/**
 * 我的账号：查看并解除渠道绑定。
 *
 * ## 为什么需要这一页
 *
 * 绑定是单向的——用户点了确认就绑上了，但**没有任何自助的回头路**。
 * 绑错了（用自己的账号绑了别人的飞书）、换号了、离职交接，都只能找管理员
 * 去数据库里改。而管理员多半不知道那张表在哪。
 *
 * 解绑的价值不在于操作频率，在于**「绑错了也不怕」这件事本身**——
 * 没有退路的功能，用户会不敢点。
 *
 * ## 为什么不做成管理台的一页
 *
 * 管理台是给管理员配别人的。这一页是**每个人看自己**的，
 * 判据是"登录了"，不是"有管理权限"。放进 /admin 下，
 * 普通用户就进不去了——而那正是最需要它的人。
 *
 * ## 只显示掩码后的 id
 *
 * 完整的 open_id 对人没有任何意义，而它是一份可被冒用的标识。
 * 用户需要确认的是「绑的是不是我」，前后几位足够。
 */

const status = ref<BindStatus | null>(null)
const loading = ref(true)
const error = ref('')
const unbinding = ref(false)
const done = ref(false)

const CHANNEL_LABELS: Record<string, string> = {
  feishu: '飞书',
  dingtalk: '钉钉',
  wecom: '企业微信',
}

const channelLabel = computed(() => {
  const p = status.value?.externalProvider
  return p ? (CHANNEL_LABELS[p] ?? p) : null
})

const bound = computed(() => !!status.value?.externalProvider)

onMounted(load)

async function load(): Promise<void> {
  loading.value = true
  error.value = ''
  try {
    status.value = await bindStatus()
  } catch (e: unknown) {
    error.value = errorMessage(e)
  } finally {
    loading.value = false
  }
}

/**
 * 解除绑定。
 *
 * **要二次确认。** 解绑之后飞书里再发消息会被当成未绑定，需要重新走一遍
 * 「发消息 → 点链接 → 登录 → 确认」才能恢复。那不是灾难，但也不是
 * 一次误点该承担的后果。
 */
async function unbind(): Promise<void> {
  const label = channelLabel.value ?? '渠道'
  const ok = window.confirm(
    `解除与${label}账号的绑定？\n\n` +
      `解除后，你在${label}里发消息将无法直接提问——需要重新走一遍绑定流程。\n` +
      `已经问过的历史记录不受影响。`,
  )
  if (!ok) return

  unbinding.value = true
  error.value = ''
  try {
    await unbindChannel()
    done.value = true
    await load()
  } catch (e: unknown) {
    error.value = errorMessage(e)
  } finally {
    unbinding.value = false
  }
}

function goAsk(): void {
  window.location.assign('/app/ask')
}
</script>

<template>
  <section class="account">
    <header class="head">
      <div>
        <h1>我的账号</h1>
        <p class="sub">这里管理你自己的渠道绑定。账号与权限由管理员维护，不在此页修改。</p>
      </div>
      <button class="btn btn-quiet btn-sm" type="button" @click="goAsk">去问数</button>
    </header>

    <p v-if="error" class="note note-danger" role="alert">{{ error }}</p>

    <div v-if="loading" class="note">加载中…</div>

    <template v-else-if="status">
      <article class="card">
        <div class="card-head">
          <h2>账号</h2>
        </div>
        <dl class="meta">
          <div>
            <dt>登录名</dt>
            <dd class="mono">{{ status.username ?? '—' }}</dd>
          </div>
          <div>
            <dt>显示名</dt>
            <dd>{{ status.displayName || '—' }}</dd>
          </div>
        </dl>
        <p class="hint">
          登录名与显示名由管理员在账号中心维护。要改请找管理员。
        </p>
      </article>

      <article class="card">
        <div class="card-head">
          <h2>渠道绑定</h2>
          <span v-if="bound" class="tag tag-on">已绑定</span>
          <span v-else class="tag">未绑定</span>
        </div>

        <template v-if="bound">
          <dl class="meta">
            <div>
              <dt>渠道</dt>
              <dd>{{ channelLabel }}</dd>
            </div>
            <div>
              <dt>账号标识</dt>
              <!-- 只给掩码：完整 id 对人没意义，而它是一份可被冒用的标识 -->
              <dd class="mono">{{ status.externalIdMasked || '—' }}</dd>
            </div>
          </dl>

          <p class="hint">
            绑定后，你在{{ channelLabel }}里提的问题会以**这个账号**的身份执行，
            能看的数据与问数页面完全相同。
          </p>

          <p v-if="done" class="note note-ok" role="status">
            已解除绑定。现在{{ channelLabel }}里发消息不会直接提问了。
          </p>

          <div class="acts">
            <button class="btn btn-danger" type="button" :disabled="unbinding" @click="unbind">
              {{ unbinding ? '解除中…' : '解除绑定' }}
            </button>
          </div>
          <p class="fine">
            解除后如需重新绑定：在{{ channelLabel }}里发一条消息，点机器人给的链接，
            登录后确认即可。历史问答不受影响。
          </p>
        </template>

        <template v-else>
          <p class="sub">
            还没有绑定{{ channelLabel ? `（上次绑的是${channelLabel}）` : '' }}任何渠道。
          </p>
          <p class="hint">
            要绑定：在飞书里给问数机器人发一条消息，它会回你一个链接，点开登录后确认即可。
            绑定之后就能在飞书里直接提问，不必打开浏览器。
          </p>
          <p v-if="done" class="note note-ok" role="status">已解除绑定。</p>
        </template>
      </article>
    </template>

    <p class="back">
      <RouterLink to="/admin/users">← 回管理台</RouterLink>
    </p>
  </section>
</template>

<style scoped>
.account {
  max-width: 720px;
  margin: 0 auto;
  padding: var(--space-6) var(--space-5) 64px;
}

.head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: var(--space-4);
  margin-bottom: var(--space-5);
}

.head h1 {
  font-size: var(--text-xl);
  font-weight: 700;
  margin: 0 0 6px;
}

.sub {
  color: var(--ink-soft);
  font-size: var(--text-sm);
  line-height: 1.65;
  margin: 0;
  max-width: 60ch;
}

.card {
  padding: var(--space-5);
  margin-bottom: var(--space-4);
  background: var(--surface);
  border: 1px solid var(--line);
  border-radius: var(--radius-lg);
}

.card-head {
  display: flex;
  align-items: center;
  gap: var(--space-3);
  margin-bottom: var(--space-4);
}

.card-head h2 {
  font-size: var(--text-base);
  font-weight: 650;
  margin: 0;
}

.tag {
  padding: 1px 8px;
  border-radius: 4px;
  background: var(--surface-sunk);
  color: var(--ink-faint);
  font-size: 0.7rem;
}

.tag-on {
  background: #d1fae5;
  color: #047857;
}

.meta {
  display: grid;
  gap: var(--space-3);
  margin: 0 0 var(--space-4);
}

.meta div {
  display: grid;
  grid-template-columns: 90px 1fr;
  gap: var(--space-3);
  align-items: baseline;
}

.meta dt {
  color: var(--ink-faint);
  font-size: var(--text-sm);
}

.meta dd {
  margin: 0;
  font-size: var(--text-sm);
  color: var(--ink);
}

.hint {
  color: var(--ink-soft);
  font-size: var(--text-sm);
  line-height: 1.7;
  margin: 0 0 var(--space-4);
}

.fine {
  color: var(--ink-faint);
  font-size: 0.76rem;
  line-height: 1.65;
  margin: var(--space-3) 0 0;
}

.acts {
  display: flex;
  gap: var(--space-3);
}

.note-ok {
  background: #ecfdf5;
  border: 1px solid #a7f3d0;
  color: #047857;
  border-radius: 9px;
  padding: 10px 14px;
  margin: 0 0 var(--space-4);
  font-size: var(--text-sm);
}

.back {
  margin-top: var(--space-5);
  font-size: var(--text-sm);
}

.back a {
  color: var(--ink-faint);
  text-decoration: none;
}

.back a:hover {
  color: var(--ink);
}

@media (max-width: 560px) {
  .meta div {
    grid-template-columns: 1fr;
    gap: 2px;
  }
}
</style>
