<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'

/**
 * 顶栏用户菜单。
 *
 * 把退出收进下拉，是为了给"账号信息 / 修改密码"留位置——
 * 再往顶栏平铺按钮，横向空间很快就只剩图标了。
 * 额外条目走默认插槽，加一项不需要改组件本身。
 */
const props = defineProps<{
  name: string
  username?: string | undefined
  meta?: string | undefined
}>()

const emit = defineEmits<{ logout: [] }>()

const open = ref(false)
const root = ref<HTMLElement | null>(null)

const avatar = computed(() => props.name.trim().charAt(0).toUpperCase() || '?')

function close(): void {
  open.value = false
}

function onPointerDown(event: PointerEvent): void {
  if (open.value && root.value && !root.value.contains(event.target as Node)) close()
}

function onKeydown(event: KeyboardEvent): void {
  if (event.key === 'Escape') close()
}

function onLogout(): void {
  close()
  emit('logout')
}

onMounted(() => {
  document.addEventListener('pointerdown', onPointerDown)
  document.addEventListener('keydown', onKeydown)
})

onBeforeUnmount(() => {
  document.removeEventListener('pointerdown', onPointerDown)
  document.removeEventListener('keydown', onKeydown)
})
</script>

<template>
  <div ref="root" class="user-menu">
    <button
      type="button"
      class="user-trigger"
      aria-haspopup="menu"
      :aria-expanded="open"
      @click="open = !open"
    >
      <span class="user-avatar" aria-hidden="true">{{ avatar }}</span>
      <span class="user-identity">
        <b>{{ name }}</b>
        <small v-if="meta">{{ meta }}</small>
      </span>
      <svg class="user-caret" viewBox="0 0 12 12" aria-hidden="true">
        <path d="M2.5 4.5 6 8l3.5-3.5" fill="none" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round" />
      </svg>
    </button>

    <Transition name="user-pop">
      <div v-if="open" class="user-pop" role="menu" @click="close">
        <div class="user-pop-head">
          <b>{{ name }}</b>
          <span v-if="username" class="user-pop-account mono">@{{ username }}</span>
        </div>

        <slot />

        <div class="user-pop-sep" />
        <button type="button" class="user-pop-item user-pop-danger" role="menuitem" @click="onLogout">
          退出登录
        </button>
      </div>
    </Transition>
  </div>
</template>

<style scoped>
.user-menu {
  position: relative;
}

.user-trigger {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 3px 8px 3px 3px;
  border: none;
  border-radius: var(--radius-pill);
  background: transparent;
  color: inherit;
  cursor: pointer;
}

.user-trigger:hover,
.user-trigger[aria-expanded='true'] {
  background: var(--surface-sunk);
}

.user-avatar {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 32px;
  height: 32px;
  flex: none;
  border-radius: 50%;
  background: linear-gradient(135deg, #dfe4ff, #eef0ff);
  color: var(--accent-deep);
  font-size: 12px;
  font-weight: 700;
}

.user-identity {
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  line-height: 1.15;
}

.user-identity b {
  font-size: 12px;
  font-weight: 600;
  color: var(--ink);
}

.user-identity small {
  margin-top: 2px;
  font-size: 9.5px;
  color: var(--ink-faint);
}

.user-caret {
  width: 12px;
  height: 12px;
  color: var(--ink-faint);
  transition: transform 0.15s ease;
}

.user-trigger[aria-expanded='true'] .user-caret {
  transform: rotate(180deg);
}

.user-pop {
  position: absolute;
  top: calc(100% + 10px);
  right: 0;
  z-index: 40;
  width: 220px;
  padding: 6px;
  border: 1px solid var(--line);
  border-radius: var(--radius);
  background: var(--surface);
  box-shadow: var(--shadow-lg);
}

.user-pop-head {
  display: flex;
  flex-direction: column;
  gap: 2px;
  padding: 10px 10px 8px;
}

.user-pop-head b {
  font-size: var(--text-sm);
  font-weight: 650;
  color: var(--ink);
}

.user-pop-account {
  font-size: 10px;
  color: var(--ink-faint);
}

.user-pop-sep {
  height: 1px;
  margin: 5px 4px;
  background: var(--line);
}

.user-pop-item {
  display: flex;
  align-items: center;
  width: 100%;
  padding: 9px 10px;
  border: none;
  border-radius: var(--radius-sm);
  background: transparent;
  color: var(--ink-soft);
  font-size: var(--text-sm);
  text-align: left;
  text-decoration: none;
  cursor: pointer;
}

.user-pop-item:hover {
  background: var(--surface-sunk);
  color: var(--ink);
}

.user-pop-danger {
  color: var(--danger, #d64545);
}

.user-pop-danger:hover {
  background: color-mix(in srgb, var(--danger, #d64545) 8%, transparent);
  color: var(--danger, #d64545);
}

/* 插槽里的条目（如"管理台"）与内置条目同一套外观 */
:slotted(.user-pop-item) {
  display: flex;
  align-items: center;
  width: 100%;
  padding: 9px 10px;
  border-radius: var(--radius-sm);
  color: var(--ink-soft);
  font-size: var(--text-sm);
  text-decoration: none;
}

:slotted(.user-pop-item:hover) {
  background: var(--surface-sunk);
  color: var(--ink);
}

.user-pop-enter-active,
.user-pop-leave-active {
  transition:
    opacity 0.14s ease,
    transform 0.14s ease;
}

.user-pop-enter-from,
.user-pop-leave-to {
  opacity: 0;
  transform: translateY(-4px);
}

@media (max-width: 720px) {
  .user-identity {
    display: none;
  }
}
</style>
