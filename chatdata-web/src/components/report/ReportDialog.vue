<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { errorMessage } from '../../api/http'
import { useModalGuard } from '../../composables/useModalGuard'

/**
 * 生成报告的弹窗：勾选要合并的轮次。
 *
 * ## 为什么是勾选而不是"全部合并"
 *
 * 报告是**要发出去的东西**。一个 20 轮的会话里可能只有 3 轮相关，
 * 全包进去的报告没人愿意读。
 *
 * 也不能让模型判断哪几轮相关——模型判断错了，读者看到一份混进无关内容的报告，
 * 而作者不知道。勾选成本是一次点击，错了的代价是信任。
 *
 * ## 默认全选
 *
 * 但**跳过不可选的**（失败的消息没有数据）。默认全选让"只是想全部导出"
 * 的用户不用点任何东西。
 */

interface Candidate {
  messageId: string
  question: string
  selectable: boolean
  success: boolean
  hasAnalysis: boolean
  createdAt: string
}

const props = defineProps<{
  conversationId: string
  open: boolean
}>()

const emit = defineEmits<{ close: [] }>()

const router = useRouter()
const openRef = computed({
  get: () => props.open,
  set: (v: boolean) => { if (!v) emit('close') },
})

const candidates = ref<Candidate[]>([])
const selected = ref<Set<string>>(new Set())
const loading = ref(false)
const generating = ref(false)
const error = ref('')

const { dialogEl } = useModalGuard(openRef, () => emit('close'))

onMounted(load)

async function load(): Promise<void> {
  loading.value = true
  error.value = ''
  try {
    const res = await fetch(`/api/ask/reports/candidates/${props.conversationId}`, {
      credentials: 'same-origin',
      headers: { Accept: 'application/json' },
    })
    if (!res.ok) {
      error.value = res.status === 404 ? '会话不存在' : `读取失败（HTTP ${res.status}）`
      return
    }
    candidates.value = (await res.json()) as Candidate[]
    // 默认全选**可选的**
    selected.value = new Set(
      candidates.value.filter((c) => c.selectable).map((c) => c.messageId),
    )
  } catch (e: unknown) {
    error.value = errorMessage(e)
  } finally {
    loading.value = false
  }
}

function toggle(id: string): void {
  const next = new Set(selected.value)
  if (next.has(id)) {
    next.delete(id)
  } else {
    next.add(id)
  }
  selected.value = next
}

const selectedCount = computed(() => selected.value.size)
const analysisCount = computed(
  () => candidates.value.filter((c) => selected.value.has(c.messageId) && c.hasAnalysis).length,
)

async function generate(): Promise<void> {
  if (selectedCount.value === 0) {
    error.value = '至少选一条问答'
    return
  }
  generating.value = true
  error.value = ''
  try {
    const csrf = readCsrf()
    const res = await fetch(`/api/ask/reports/conversations/${props.conversationId}`, {
      method: 'POST',
      credentials: 'same-origin',
      headers: {
        'Content-Type': 'application/json',
        ...(csrf ? { 'X-XSRF-TOKEN': csrf } : {}),
      },
      body: JSON.stringify({ messageIds: [...selected.value] }),
    })
    if (!res.ok) {
      const body = await res.json().catch(() => null)
      error.value = body?.message ?? `生成失败（HTTP ${res.status}）`
      return
    }
    const report = (await res.json()) as { id: string }
    emit('close')
    await router.push(`/report/${report.id}`)
  } catch (e: unknown) {
    error.value = errorMessage(e)
  } finally {
    generating.value = false
  }
}

/** 网关的 CSRF token 在 `XSRF-TOKEN` Cookie 里。 */
function readCsrf(): string | null {
  const m = document.cookie.match(/(?:^|;\s*)XSRF-TOKEN=([^;]+)/)
  return m && m[1] ? decodeURIComponent(m[1]) : null
}

function when(iso: string): string {
  return iso ? iso.replace('T', ' ').slice(5, 16) : ''
}
</script>

<template>
  <div v-if="open" class="modal-mask">
    <div ref="dialogEl" class="modal" role="dialog" aria-modal="true" tabindex="-1"
         aria-label="生成分析报告">
      <h2 class="modal-title">生成分析报告</h2>
      <p class="sub">
        勾选要合并进报告的问答。带「分析」标记的有结论，其余只作为数据附上。
      </p>

      <p v-if="loading" class="note">加载中…</p>

      <div v-else-if="candidates.length === 0" class="note">
        这段会话里还没有成功的问答，无法生成报告。
      </div>

      <template v-else>
        <ul class="list">
          <li
            v-for="c in candidates"
            :key="c.messageId"
            class="item"
            :class="{ disabled: !c.selectable, checked: selected.has(c.messageId) }"
          >
            <label>
              <input
                type="checkbox"
                :checked="selected.has(c.messageId)"
                :disabled="!c.selectable"
                @change="toggle(c.messageId)"
              />
              <span class="q">{{ c.question }}</span>
              <span v-if="c.hasAnalysis" class="tag tag-analysis">分析</span>
              <span v-else-if="c.selectable" class="tag">数据</span>
              <span v-if="!c.selectable" class="tag tag-off">无数据</span>
              <span class="time">{{ when(c.createdAt) }}</span>
            </label>
          </li>
        </ul>

        <p class="summary">
          已选 {{ selectedCount }} 条，其中 {{ analysisCount }} 条带分析。
        </p>
      </template>

      <p v-if="error" class="note note-danger" role="alert">{{ error }}</p>

      <div class="modal-actions">
        <div class="spacer" />
        <button class="btn btn-quiet" type="button" @click="emit('close')">取消</button>
        <button
          class="btn btn-primary"
          type="button"
          :disabled="generating || selectedCount === 0"
          @click="generate"
        >
          {{ generating ? '生成中…' : '生成报告' }}
        </button>
      </div>
    </div>
  </div>
</template>

<style scoped>
.sub {
  color: #64748b;
  font-size: 0.82rem;
  line-height: 1.65;
  margin: 0 0 16px;
}

.note {
  color: #64748b;
  font-size: 0.85rem;
  padding: 12px 0;
}

.note-danger {
  color: #b91c1c;
  background: #fef2f2;
  border: 1px solid #fecaca;
  border-radius: 9px;
  padding: 10px 14px;
  margin: 12px 0 0;
}

.list {
  list-style: none;
  margin: 0;
  padding: 0;
  max-height: 320px;
  overflow-y: auto;
  border: 1px solid #e2e8f0;
  border-radius: 10px;
}

.item {
  border-bottom: 1px solid #f1f5f9;
}

.item:last-child {
  border-bottom: none;
}

.item.disabled {
  opacity: 0.5;
}

.item.checked {
  background: #f8fafc;
}

.item label {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 10px 14px;
  cursor: pointer;
  font-size: 0.85rem;
}

.item.disabled label {
  cursor: not-allowed;
}

.q {
  flex: 1;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.tag {
  flex-shrink: 0;
  font-size: 0.68rem;
  padding: 1px 7px;
  border-radius: 4px;
  background: #f1f5f9;
  color: #64748b;
}

.tag-analysis {
  background: #eef2ff;
  color: #4338ca;
}

.tag-off {
  background: #fef3c7;
  color: #b45309;
}

.time {
  flex-shrink: 0;
  color: #94a3b8;
  font-size: 0.75rem;
}

.summary {
  color: #64748b;
  font-size: 0.8rem;
  margin: 10px 0 0;
}

.spacer {
  flex: 1;
}
</style>
