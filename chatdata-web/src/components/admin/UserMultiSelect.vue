<script setup lang="ts">
import { onMounted, ref, watch } from 'vue'
import { searchUsers, type UserOption } from '../../api/admin'
import { errorMessage } from '../../api/http'

/**
 * 用户多选。
 *
 * `grantUsers` 存的是 **username**；显示名只用于辨认。用户目录是搜索式的
 * （IAM 可能有十万用户），所以这里按关键词取前 20 条，不在前端拉全量。
 *
 * 已选用户如果不在当前搜索结果里，chip 退化为 username——不会因为"没搜到"
 * 就把已保存的配置丢掉。
 */
const props = defineProps<{
  modelValue: string[]
  placeholder?: string
}>()

const emit = defineEmits<{ 'update:modelValue': [value: string[]] }>()

const keyword = ref('')
const open = ref(false)
const loading = ref(false)
const error = ref('')
const options = ref<UserOption[]>([])
const cache = ref(new Map<string, UserOption>())

let timer: ReturnType<typeof setTimeout> | null = null
let seq = 0

async function load(): Promise<void> {
  const mine = ++seq
  loading.value = true
  error.value = ''
  try {
    const list = await searchUsers(keyword.value)
    if (mine !== seq) return
    options.value = list
    for (const u of list) cache.value.set(u.username, u)
  } catch (e: unknown) {
    if (mine !== seq) return
    error.value = errorMessage(e)
  } finally {
    if (mine === seq) loading.value = false
  }
}

onMounted(load)

watch(keyword, () => {
  if (timer) clearTimeout(timer)
  timer = setTimeout(load, 250)
})

function add(username: string): void {
  if (!props.modelValue.includes(username)) {
    emit('update:modelValue', [...props.modelValue, username])
  }
  keyword.value = ''
  open.value = true
}

function remove(username: string): void {
  emit(
    'update:modelValue',
    props.modelValue.filter((u) => u !== username),
  )
}

function label(username: string): string {
  const u = cache.value.get(username)
  return u?.displayName ? `${u.displayName} (${username})` : username
}

function disabled(username: string): boolean {
  return cache.value.get(username)?.status === 'DISABLED'
}

function onFocusOut(e: FocusEvent): void {
  const root = e.currentTarget as HTMLElement | null
  if (!root?.contains(e.relatedTarget as Node | null)) open.value = false
}

function onEnter(): void {
  const first = options.value.find((u) => !props.modelValue.includes(u.username))
  if (first) add(first.username)
}
</script>

<template>
  <div class="ms" @focusout="onFocusOut">
    <div class="ms-box" @click="open = true">
      <span
        v-for="u in props.modelValue"
        :key="u"
        class="chip"
        :class="{ 'chip-warn': disabled(u) }"
        :title="disabled(u) ? '用户已停用，规则不会生效' : u"
      >
        {{ label(u) }}
        <button type="button" class="chip-x" aria-label="移除" @click.stop="remove(u)">×</button>
      </span>
      <input
        v-model="keyword"
        class="ms-input"
        :placeholder="props.modelValue.length === 0 ? (placeholder ?? '搜索账号或姓名') : ''"
        @focus="open = true"
        @keydown.enter.prevent="onEnter"
        @keydown.esc="open = false"
      />
    </div>

    <div v-if="open" class="ms-pop">
      <p v-if="loading" class="ms-hint">搜索中…</p>
      <p v-else-if="error" class="ms-hint ms-err">{{ error }}</p>
      <template v-else>
        <button
          v-for="u in options"
          :key="u.username"
          type="button"
          class="ms-opt"
          @mousedown.prevent="add(u.username)"
        >
          <span class="ms-name">{{ u.displayName || u.username }}</span>
          <span class="ms-code mono">{{ u.username }}</span>
          <span v-if="u.status === 'DISABLED'" class="ms-off">已停用</span>
        </button>
        <p v-if="options.length === 0" class="ms-hint">没有匹配的用户</p>
      </template>
    </div>
  </div>
</template>

<style scoped>
.ms {
  position: relative;
}

.ms-box {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 5px;
  min-height: 36px;
  padding: 4px 7px;
  border: 1px solid var(--line);
  border-radius: var(--radius);
  background: var(--surface);
  cursor: text;
}

.ms-box:focus-within {
  border-color: var(--ink);
}

.chip {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  padding: 2px 5px 2px 8px;
  border-radius: var(--radius-pill);
  background: var(--accent-soft);
  font-size: 11px;
}

.chip-warn {
  background: var(--warn-soft, #fff4e0);
}

.chip-x {
  border: none;
  background: transparent;
  padding: 0 2px;
  color: var(--ink-faint);
  cursor: pointer;
  font-size: 13px;
  line-height: 1;
}

.chip-x:hover {
  color: var(--danger, #b42318);
}

.ms-input {
  flex: 1;
  min-width: 120px;
  border: none;
  outline: none;
  background: transparent;
  font: inherit;
  font-size: 11.5px;
}

.ms-pop {
  position: absolute;
  z-index: 20;
  top: calc(100% + 4px);
  left: 0;
  right: 0;
  max-height: 220px;
  overflow: auto;
  padding: 4px;
  border: 1px solid var(--line);
  border-radius: var(--radius);
  background: var(--surface);
  box-shadow: var(--shadow-md, 0 12px 28px rgb(0 0 0 / 12%));
}

.ms-opt {
  display: flex;
  align-items: baseline;
  gap: 8px;
  width: 100%;
  padding: 7px 8px;
  border: none;
  border-radius: 7px;
  background: transparent;
  text-align: left;
  cursor: pointer;
}

.ms-opt:hover {
  background: var(--surface-sunk);
}

.ms-name {
  font-size: 12px;
}

.ms-code {
  font-size: 10px;
  color: var(--ink-faint);
}

.ms-off {
  margin-left: auto;
  font-size: 10px;
  color: var(--warn);
}

.ms-hint {
  margin: 0;
  padding: 8px 10px;
  font-size: 11px;
  color: var(--ink-faint);
}

.ms-err {
  color: var(--danger, #b42318);
}
</style>
