<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, ref, watch } from 'vue'
import type { OrgView } from '../api/admin'

/**
 * 组织树形选择器。
 *
 * ## 为什么不用原生 `<select>`
 *
 * 组织是树。平铺的下拉列表看不出上下级，选「上海门店」时也看不出它挂在
 * 「华东 / 零售」下面，重名或近名的组织很容易选错——而组织决定数据范围
 * （行级规则的动态变量取的就是主组织），选错是静默的口径错误。
 *
 * ## 为什么用 Teleport + fixed
 *
 * 它总是嵌在弹窗里，而弹窗是 `overflow: auto`。普通的 `position: absolute`
 * 会被弹窗裁掉，树稍微深一点就有一半看不见。所以面板挂到 body 上，
 * 用触发器的 `getBoundingClientRect()` 定位；下方空间不够时向上翻。
 *
 * ## 结构以 parentId 为准
 *
 * `path` 是物化路径，看着方便但它是派生数据；一旦与 parentId 不一致，
 * 按 path 组树会出现错位或凭空消失的节点。这里与组织管理页保持同一判据。
 * 父节点不存在的节点（脏数据）与成环的节点都会被补成根节点，不让它们消失。
 */
const props = withDefaults(
  defineProps<{
    modelValue: number | null
    orgs: OrgView[]
    /** 未选中时触发器里的提示 */
    placeholder?: string
    /** 下拉里"不指定"那一行的文案 */
    emptyLabel?: string
    /** 是否允许清空为 null */
    allowEmpty?: boolean
    disabled?: boolean
  }>(),
  {
    placeholder: '请选择组织',
    emptyLabel: '不指定',
    allowEmpty: true,
    disabled: false,
  },
)

const emit = defineEmits<{ 'update:modelValue': [value: number | null] }>()

interface TreeNode {
  org: OrgView
  depth: number
  children: TreeNode[]
}

interface Row {
  org: OrgView
  depth: number
  hasChildren: boolean
  expanded: boolean
}

type OptionRow = { kind: 'empty' } | { kind: 'node'; row: Row }

const open = ref(false)
const filter = ref('')
const expanded = ref<Set<number>>(new Set())
const activeIndex = ref(0)

const triggerRef = ref<HTMLButtonElement | null>(null)
const panelRef = ref<HTMLElement | null>(null)
const searchRef = ref<HTMLInputElement | null>(null)
const panelStyle = ref<Record<string, string>>({})

const byId = computed(() => new Map(props.orgs.map((o) => [o.id, o])))

const selected = computed(() =>
  props.modelValue == null ? null : (byId.value.get(props.modelValue) ?? null),
)

const selectedLabel = computed(() =>
  selected.value ? `${selected.value.name}（${selected.value.code}）` : '',
)

/**
 * 由 parentId 组树。
 *
 * <p>三处兜底，都是为了"别让节点消失"：
 * <ul>
 *   <li>父节点不存在的节点提升为根</li>
 *   <li>父链成环的节点（从根走不到）也提升为根</li>
 *   <li>`visited` 防止坏数据把递归转成死循环</li>
 * </ul>
 */
const forest = computed<TreeNode[]>(() => {
  const childrenMap = new Map<number | null, OrgView[]>()
  for (const org of props.orgs) {
    const key = org.parentId
    const list = childrenMap.get(key)
    if (list) list.push(org)
    else childrenMap.set(key, [org])
  }

  const visited = new Set<number>()

  const build = (org: OrgView, depth: number): TreeNode => {
    visited.add(org.id)
    const children = (childrenMap.get(org.id) ?? [])
      .filter((child) => !visited.has(child.id))
      .map((child) => build(child, depth + 1))
    return { org, depth, children }
  }

  const roots: TreeNode[] = []
  for (const org of props.orgs) {
    if (org.parentId == null || !byId.value.has(org.parentId)) {
      if (!visited.has(org.id)) roots.push(build(org, 0))
    }
  }
  // 父链成环：上面一个都没轮到，按原顺序补成根
  for (const org of props.orgs) {
    if (!visited.has(org.id)) roots.push(build(org, 0))
  }
  return roots
})

/** 默认全展开的规模上限，与组织管理页保持一致。 */
const AUTO_EXPAND_LIMIT = 200

const needle = computed(() => filter.value.trim().toLowerCase())

/** 搜索时不显示"不指定"——此时用户想找的是某个组织，不是清空。 */
const showEmpty = computed(() => props.allowEmpty && needle.value === '')

/** 命中过滤的节点 ∪ 它们的全部祖先；未过滤时为 null。 */
const matchedIds = computed<Set<number> | null>(() => {
  const value = needle.value
  if (value === '') return null
  const keep = new Set<number>()
  for (const org of props.orgs) {
    const hit = org.name.toLowerCase().includes(value) || org.code.toLowerCase().includes(value)
    if (!hit) continue

    keep.add(org.id)
    const seen = new Set<number>()
    let parent = org.parentId
    while (parent != null && !seen.has(parent)) {
      seen.add(parent)
      keep.add(parent)
      parent = byId.value.get(parent)?.parentId ?? null
    }
  }
  return keep
})

/** 按展开状态拍平，供渲染与键盘导航共用。过滤时强制展开。 */
const rows = computed<Row[]>(() => {
  const visible = matchedIds.value
  const out: Row[] = []

  const walk = (nodes: TreeNode[]): void => {
    for (const node of nodes) {
      if (visible && !visible.has(node.org.id)) continue
      const isExpanded = visible ? true : expanded.value.has(node.org.id)
      out.push({
        org: node.org,
        depth: node.depth,
        hasChildren: node.children.length > 0,
        expanded: isExpanded,
      })
      if (node.children.length > 0 && isExpanded) walk(node.children)
    }
  }

  walk(forest.value)
  return out
})

const options = computed<OptionRow[]>(() => {
  const list: OptionRow[] = []
  if (showEmpty.value) list.push({ kind: 'empty' })
  for (const row of rows.value) list.push({ kind: 'node', row })
  return list
})

watch(
  () => props.orgs,
  (orgs) => {
    // 首次加载默认全展开：组织规模小，一屏看完整棵树比逐个点开有用。
    // 但几千个组织时全展开等于一次渲染几千行，面板只露出十几行——
    // 超过上限只展开根节点，搜索时仍会强制展开命中链。
    const next = new Set(expanded.value)
    if (orgs.length <= AUTO_EXPAND_LIMIT) {
      for (const org of orgs) next.add(org.id)
    } else {
      for (const org of orgs) {
        if (org.parentId == null) next.add(org.id)
      }
    }
    expanded.value = next
  },
  { immediate: true },
)

watch(activeIndex, () => scrollActive())

// 搜索词一变，候选集就变了；不重置的话高亮停在"第几个"而不是"哪一个"
watch(needle, () => {
  if (!open.value) return
  activeIndex.value = 0
})

function updatePosition(): void {
  const trigger = triggerRef.value
  if (!trigger) return

  const rect = trigger.getBoundingClientRect()
  const gap = 4
  const below = window.innerHeight - rect.bottom - gap - 8
  const above = rect.top - gap - 8
  const openUp = below < 160 && above > below

  panelStyle.value = {
    position: 'fixed',
    left: `${rect.left}px`,
    width: `${Math.max(rect.width, 260)}px`,
    maxHeight: `${Math.max(120, Math.min(320, openUp ? above : below))}px`,
    ...(openUp
      ? { bottom: `${window.innerHeight - rect.top + gap}px` }
      : { top: `${rect.bottom + gap}px` }),
  }
}

function scrollActive(): void {
  if (!open.value) return
  const index = activeIndex.value
  if (index < 0) return
  const el = panelRef.value?.querySelector<HTMLElement>(`[data-opt-index="${index}"]`)
  el?.scrollIntoView({ block: 'nearest' })
}

/** 选中项可能在折叠的分支里——打开时把祖先链展开，否则看不到它。 */
function ensureVisible(id: number | null): void {
  if (id == null) return
  const next = new Set(expanded.value)
  const seen = new Set<number>()
  let parent = byId.value.get(id)?.parentId ?? null
  while (parent != null && !seen.has(parent)) {
    seen.add(parent)
    next.add(parent)
    parent = byId.value.get(parent)?.parentId ?? null
  }
  expanded.value = next
}

function openPanel(): void {
  if (props.disabled) return
  filter.value = ''
  ensureVisible(props.modelValue)
  open.value = true
  updatePosition()

  const index = options.value.findIndex(
    (option) => option.kind === 'node' && option.row.org.id === props.modelValue,
  )
  activeIndex.value = index >= 0 ? index : 0

  void nextTick(() => {
    searchRef.value?.focus()
    scrollActive()
  })
}

function closePanel(focusTrigger = false): void {
  open.value = false
  filter.value = ''
  if (focusTrigger) triggerRef.value?.focus()
}

function toggle(): void {
  if (open.value) closePanel()
  else openPanel()
}

function choose(id: number | null): void {
  emit('update:modelValue', id)
  closePanel(true)
}

function toggleExpand(id: number): void {
  const next = new Set(expanded.value)
  if (next.has(id)) next.delete(id)
  else next.add(id)
  expanded.value = next
}

function activate(): void {
  const option = options.value[activeIndex.value]
  if (!option) return
  if (option.kind === 'empty') choose(null)
  else choose(option.row.org.id)
}

function activeNodeRow(): Row | null {
  const option = options.value[activeIndex.value]
  if (!option || option.kind !== 'node') return null
  return option.row
}

function onSearchKeydown(event: KeyboardEvent): void {
  const count = options.value.length

  switch (event.key) {
    case 'ArrowDown':
      event.preventDefault()
      if (count > 0) activeIndex.value = Math.min(activeIndex.value + 1, count - 1)
      break
    case 'ArrowUp':
      event.preventDefault()
      if (count > 0) activeIndex.value = Math.max(activeIndex.value - 1, 0)
      break
    case 'Home':
      event.preventDefault()
      activeIndex.value = 0
      break
    case 'End':
      event.preventDefault()
      activeIndex.value = Math.max(count - 1, 0)
      break
    case 'Enter':
      event.preventDefault()
      activate()
      break
    case 'Escape':
      event.preventDefault()
      closePanel(true)
      break
    case 'ArrowRight': {
      event.preventDefault()
      const row = activeNodeRow()
      if (row && row.hasChildren && !row.expanded) toggleExpand(row.org.id)
      break
    }
    case 'ArrowLeft': {
      event.preventDefault()
      const row = activeNodeRow()
      if (row && row.hasChildren && row.expanded) toggleExpand(row.org.id)
      break
    }
    case 'Tab':
      closePanel()
      break
  }
}

function onPointerDown(event: PointerEvent): void {
  const target = event.target as Node | null
  if (!target) return
  if (triggerRef.value?.contains(target) || panelRef.value?.contains(target)) return
  closePanel()
}

function onWindowChange(): void {
  if (open.value) updatePosition()
}

watch(open, (value) => {
  if (value) {
    window.addEventListener('pointerdown', onPointerDown, true)
    window.addEventListener('scroll', onWindowChange, true)
    window.addEventListener('resize', onWindowChange)
  } else {
    window.removeEventListener('pointerdown', onPointerDown, true)
    window.removeEventListener('scroll', onWindowChange, true)
    window.removeEventListener('resize', onWindowChange)
  }
})

onBeforeUnmount(() => {
  window.removeEventListener('pointerdown', onPointerDown, true)
  window.removeEventListener('scroll', onWindowChange, true)
  window.removeEventListener('resize', onWindowChange)
})
</script>

<template>
  <div class="org-tree-select">
    <button
      ref="triggerRef"
      type="button"
      class="input org-trigger"
      :disabled="disabled"
      :aria-expanded="open"
      aria-haspopup="tree"
      @click="toggle"
    >
      <span class="org-trigger-text" :class="{ 'is-placeholder': !selected }">
        {{ selected ? selectedLabel : placeholder }}
      </span>
      <span class="org-caret" aria-hidden="true">▾</span>
    </button>

    <Teleport to="body">
      <div
        v-if="open"
        ref="panelRef"
        class="org-panel"
        :style="panelStyle"
        role="tree"
        aria-label="组织树"
      >
        <div class="org-search">
          <input
            ref="searchRef"
            v-model="filter"
            class="input"
            type="text"
            placeholder="搜索名称或编码"
            aria-label="搜索组织"
            @keydown="onSearchKeydown"
          />
        </div>

        <div class="org-options">
          <div
            v-if="showEmpty"
            class="org-option org-empty"
            :class="{ 'is-active': activeIndex === 0, 'is-selected': modelValue == null }"
            data-opt-index="0"
            role="treeitem"
            aria-level="1"
            :aria-selected="modelValue == null"
            @mouseenter="activeIndex = 0"
            @click="choose(null)"
          >
            <span class="org-empty-spacer" aria-hidden="true"></span>
            <span class="org-label">{{ emptyLabel }}</span>
            <span v-if="modelValue == null" class="org-check" aria-hidden="true">✓</span>
          </div>

          <div
            v-for="(row, index) in rows"
            :key="row.org.id"
            class="org-option"
            :class="{
              'is-active': activeIndex === index + (showEmpty ? 1 : 0),
              'is-selected': row.org.id === modelValue,
            }"
            :data-opt-index="index + (showEmpty ? 1 : 0)"
            role="treeitem"
            :aria-level="row.depth + 1"
            :aria-selected="row.org.id === modelValue"
            :aria-expanded="row.hasChildren ? row.expanded : undefined"
            :style="{ paddingLeft: `${row.depth * 1.15 + 0.55}rem` }"
            @mouseenter="activeIndex = index + (showEmpty ? 1 : 0)"
            @click="choose(row.org.id)"
          >
            <button
              v-if="row.hasChildren"
              class="org-twist"
              type="button"
              :aria-label="row.expanded ? '收起' : '展开'"
              @click.stop="toggleExpand(row.org.id)"
            >
              {{ row.expanded ? '−' : '+' }}
            </button>
            <span v-else class="org-twist org-twist-leaf" aria-hidden="true"></span>

            <span class="org-label">{{ row.org.name }}</span>
            <span class="org-code">{{ row.org.code }}</span>
            <span v-if="!row.org.enabled" class="org-tag">停用</span>
            <span v-if="row.org.id === modelValue" class="org-check" aria-hidden="true">✓</span>
          </div>

          <p v-if="rows.length === 0" class="org-none">没有匹配的组织</p>
        </div>
      </div>
    </Teleport>
  </div>
</template>

<style scoped>
.org-tree-select {
  position: relative;
}

.org-trigger {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--space-2);
  text-align: left;
  cursor: pointer;
}

.org-trigger:disabled {
  opacity: 0.55;
  cursor: not-allowed;
}

.org-trigger-text {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.org-trigger-text.is-placeholder {
  color: var(--ink-faint);
}

.org-caret {
  flex: none;
  color: var(--ink-soft);
  font-size: 0.9em;
}

.org-panel {
  display: flex;
  flex-direction: column;
  overflow: hidden;
  background: var(--surface);
  border: 1px solid var(--line);
  border-radius: var(--radius);
  box-shadow: var(--shadow-lg);
  z-index: 60;
}

.org-search {
  flex: none;
  padding: var(--space-2);
  border-bottom: 1px solid var(--line);
}

.org-options {
  min-height: 0;
  overflow-y: auto;
  padding: var(--space-1) 0;
}

.org-option {
  display: flex;
  align-items: center;
  gap: var(--space-2);
  padding: 0.38rem 0.6rem;
  font-size: var(--text-sm);
  white-space: nowrap;
  cursor: pointer;
}

.org-option.is-active {
  background: var(--accent-soft);
}

.org-option.is-selected .org-label {
  color: var(--accent-deep);
  font-weight: 600;
}

.org-empty-spacer {
  width: 1.15rem;
  flex: none;
}

.org-twist {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 1.15rem;
  height: 1.15rem;
  flex: none;
  padding: 0;
  border: 1px solid var(--line-strong);
  border-radius: var(--radius-sm);
  background: var(--paper);
  color: var(--ink-soft);
  font-size: var(--text-xs);
  line-height: 1;
  cursor: pointer;
}

.org-twist:hover {
  border-color: var(--accent);
  color: var(--accent-deep);
}

/* 叶子占位：宽度与 twist 一致，名字才会对齐 */
.org-twist-leaf {
  border-color: transparent;
  background: transparent;
  cursor: default;
}

.org-label {
  overflow: hidden;
  text-overflow: ellipsis;
}

.org-code {
  color: var(--ink-faint);
  font-family: var(--font-mono);
  font-size: var(--text-xs);
}

.org-tag {
  padding: 0 0.4em;
  border-radius: var(--radius-sm);
  background: var(--surface-sunk);
  color: var(--ink-soft);
  font-size: var(--text-xs);
}

.org-check {
  margin-left: auto;
  color: var(--accent-deep);
  font-weight: 700;
}

.org-none {
  margin: 0;
  padding: var(--space-3) var(--space-4);
  color: var(--ink-soft);
  font-size: var(--text-sm);
}
</style>
