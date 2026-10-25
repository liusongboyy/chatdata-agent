<script setup lang="ts">
import { computed, ref } from 'vue'
import type { RoleOption } from '../../api/admin'

/**
 * 角色多选。
 *
 * 保存的是 `role.code`——引擎按它逐字符匹配。显示名只用于辨认，
 * 所以 chip 上以显示名为主、code 放 title；停用/缺失角色单独标色。
 */
const props = defineProps<{
  modelValue: string[]
  roles: RoleOption[]
  placeholder?: string
}>()

const emit = defineEmits<{ 'update:modelValue': [value: string[]] }>()

const keyword = ref('')
const open = ref(false)

const byCode = computed(() => new Map(props.roles.map((r) => [r.code, r])))

const selected = computed(() =>
  props.modelValue.map((code) => ({
    code,
    option: byCode.value.get(code),
  })),
)

const candidates = computed(() => {
  const kw = keyword.value.trim().toLowerCase()
  return props.roles.filter(
    (r) =>
      !props.modelValue.includes(r.code) &&
      (!kw || r.code.toLowerCase().includes(kw) || (r.name ?? '').toLowerCase().includes(kw)),
  )
})

function add(code: string): void {
  if (!props.modelValue.includes(code)) {
    emit('update:modelValue', [...props.modelValue, code])
  }
  keyword.value = ''
  open.value = true
}

function remove(code: string): void {
  emit(
    'update:modelValue',
    props.modelValue.filter((c) => c !== code),
  )
}

function onFocusOut(e: FocusEvent): void {
  const root = e.currentTarget as HTMLElement | null
  if (!root?.contains(e.relatedTarget as Node | null)) open.value = false
}

function onEnter(): void {
  const first = candidates.value[0]
  if (first) add(first.code)
}
</script>

<template>
  <div class="ms" @focusout="onFocusOut">
    <div class="ms-box" @click="open = true">
      <span
        v-for="s in selected"
        :key="s.code"
        class="chip"
        :class="{ 'chip-warn': s.option && !s.option.enabled, 'chip-danger': !s.option }"
        :title="s.option?.enabled === false ? '角色已停用，引用它的规则不会生效' : !s.option ? '角色目录里不存在这个码' : s.code"
      >
        {{ s.option ? (s.option.name && s.option.name !== s.option.code ? `${s.option.name} (${s.option.code})` : s.option.code) : s.code }}
        <button type="button" class="chip-x" aria-label="移除" @click.stop="remove(s.code)">×</button>
      </span>
      <input
        v-model="keyword"
        class="ms-input"
        :placeholder="selected.length === 0 ? (placeholder ?? '搜索并选择角色') : ''"
        @focus="open = true"
        @keydown.enter.prevent="onEnter"
        @keydown.esc="open = false"
      />
    </div>

    <div v-if="open && candidates.length" class="ms-pop">
      <button
        v-for="r in candidates"
        :key="r.code"
        type="button"
        class="ms-opt"
        @mousedown.prevent="add(r.code)"
      >
        <span class="ms-name">{{ r.name || r.code }}</span>
        <span class="ms-code mono">{{ r.code }}</span>
        <span v-if="!r.enabled" class="ms-off">已停用</span>
      </button>
    </div>
    <p v-else-if="open && keyword && candidates.length === 0" class="ms-empty">没有匹配的角色</p>
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
  color: var(--ink);
}

.chip-warn {
  background: var(--warn-soft, #fff4e0);
}

.chip-danger {
  background: var(--danger-soft, #fdecec);
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
  min-width: 100px;
  border: none;
  outline: none;
  background: transparent;
  font: inherit;
  font-size: 11.5px;
  color: var(--ink);
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
  color: var(--ink);
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

.ms-empty {
  position: absolute;
  z-index: 20;
  top: calc(100% + 4px);
  left: 0;
  right: 0;
  margin: 0;
  padding: 8px 10px;
  border: 1px solid var(--line);
  border-radius: var(--radius);
  background: var(--surface);
  font-size: 11px;
  color: var(--ink-faint);
}
</style>
