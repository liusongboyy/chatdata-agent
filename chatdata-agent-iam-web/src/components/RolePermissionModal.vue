<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import {
  getRolePermissions,
  listPermissionCatalog,
  saveRolePermissions,
  type PermissionNode,
  type RoleView,
} from '../api/admin'
import { errorMessage } from '../api/http'

/**
 * 角色的功能权限配置。
 *
 * ## 三级勾选
 *
 * 模块（不可勾选）→ 页面 → 动作。勾页面 = 全选它的动作；勾任一动作会自动带上页面码
 * （后端也会补，这里同步做是为了界面即时正确）。
 *
 * ## 为什么 super_admin 是只读的
 *
 * 它是**内置超管**：在代码里展开为全部权限，不落 iam_role_permission。
 * 让它可编辑的话，一次误操作就能把所有人锁在管理台外面——而那时已经没人能改回来了。
 *
 * 注意它**不是** chat2bi：后者是「问数管理台」这个业务角色，可编辑、可收窄。
 * 两者拆开是为了让运维一眼认出哪一个才是那道保险。
 */
const props = defineProps<{ role: RoleView }>()
const emit = defineEmits<{ close: []; saved: [count: number] }>()

const catalog = ref<PermissionNode[]>([])
const selected = ref<ReadonlySet<string>>(new Set())
const loading = ref(true)
const saving = ref(false)
const error = ref('')

/** 内置超管：只读。 */
const isBuiltinSuper = computed(() => props.role.code === 'super_admin')

async function load(): Promise<void> {
  loading.value = true
  error.value = ''
  try {
    const [tree, codes] = await Promise.all([
      listPermissionCatalog(),
      getRolePermissions(props.role.id),
    ])
    catalog.value = tree
    selected.value = new Set(codes)
  } catch (e: unknown) {
    error.value = errorMessage(e)
  } finally {
    loading.value = false
  }
}

watch(() => props.role.id, load, { immediate: true })

/** 一个页面节点下的全部码（页面自身 + 动作）。 */
function codesOfPage(page: PermissionNode): string[] {
  return [page.code, ...page.children.map((c) => c.code)]
}

function isPageFullySelected(page: PermissionNode): boolean {
  return codesOfPage(page).every((c) => selected.value.has(c))
}

function isPagePartiallySelected(page: PermissionNode): boolean {
  const hit = codesOfPage(page).filter((c) => selected.value.has(c)).length
  return hit > 0 && hit < codesOfPage(page).length
}

function togglePage(page: PermissionNode, on: boolean): void {
  const next = new Set(selected.value)
  for (const code of codesOfPage(page)) {
    if (on) next.add(code)
    else next.delete(code)
  }
  selected.value = next
}

/** 勾一个动作时自动带上它的页面码，取消时不动页面码（页面可能还有别的动作）。 */
function toggleAction(page: PermissionNode, code: string, on: boolean): void {
  const next = new Set(selected.value)
  if (on) {
    next.add(code)
    next.add(page.code)
  } else {
    next.delete(code)
  }
  selected.value = next
}

const selectedCount = computed(() => selected.value.size)

async function save(): Promise<void> {
  saving.value = true
  error.value = ''
  try {
    const saved = await saveRolePermissions(props.role.id, [...selected.value])
    emit('saved', saved)
  } catch (e: unknown) {
    error.value = errorMessage(e)
  } finally {
    saving.value = false
  }
}
</script>

<template>
  <div class="modal-mask" @click.self="emit('close')">
    <div class="modal modal-wide" role="dialog" aria-modal="true" aria-label="配置功能权限">
      <h2 class="modal-title">功能权限 · {{ role.name }}</h2>
      <p class="sub">
        决定这个角色能看到哪些<strong>页面、按钮与 tab</strong>。
        数据权限（能看哪些<strong>行和列</strong>）不在 IAM 配置，见问数管理台的
        数据域/助理页面。
      </p>

      <p v-if="isBuiltinSuper" class="note note-warn" role="status">
        「{{ role.code }}」是<strong>内置超级管理员</strong>，自动拥有全部权限，不能单独配置。
        如需限制某人，请给他一个自定义角色。
      </p>

      <p v-if="loading" class="note">加载中…</p>
      <p v-if="error" class="note note-danger" role="alert">{{ error }}</p>

      <div v-if="!loading" class="tree">
        <section v-for="module in catalog" :key="module.code" class="module">
          <h3 class="module-title">{{ module.label }}</h3>

          <div v-for="page in module.children" :key="page.code" class="page">
            <label class="row row-page">
              <input
                type="checkbox"
                :checked="isPageFullySelected(page)"
                :indeterminate.prop="isPagePartiallySelected(page)"
                :disabled="isBuiltinSuper"
                @change="togglePage(page, ($event.target as HTMLInputElement).checked)"
              />
              <span class="row-label">{{ page.label }}</span>
              <span class="row-code mono">{{ page.code }}</span>
            </label>

            <label v-for="action in page.children" :key="action.code" class="row row-action">
              <input
                type="checkbox"
                :checked="selected.has(action.code)"
                :disabled="isBuiltinSuper"
                @change="
                  toggleAction(page, action.code, ($event.target as HTMLInputElement).checked)
                "
              />
              <span class="row-label">
                {{ action.label }}
                <span v-if="action.sensitive" class="tag tag-warn">敏感</span>
              </span>
              <span class="row-code mono">{{ action.code }}</span>
            </label>
          </div>
        </section>
      </div>

      <div class="modal-actions">
        <span class="count mono">已选 {{ selectedCount }} 项</span>
        <div class="spacer" />
        <button class="btn btn-quiet" type="button" @click="emit('close')">取消</button>
        <button
          class="btn btn-primary"
          type="button"
          :disabled="saving || isBuiltinSuper"
          @click="save"
        >
          {{ saving ? '保存中…' : '保存' }}
        </button>
      </div>
    </div>
  </div>
</template>

<style scoped>
.modal-wide {
  max-width: 46rem;
  max-height: 86dvh;
  overflow-y: auto;
}

.sub {
  color: var(--ink-soft);
  font-size: var(--text-sm);
  line-height: 1.7;
  margin-bottom: var(--space-4);
}

.tree {
  display: grid;
  gap: var(--space-5);
}

.module-title {
  font-size: var(--text-xs);
  font-weight: 650;
  letter-spacing: var(--track-label);
  color: var(--ink-faint);
  text-transform: uppercase;
  margin-bottom: var(--space-2);
  padding-bottom: var(--space-1);
  border-bottom: 1px solid var(--line);
}

.page {
  margin-bottom: var(--space-3);
}

.row {
  display: flex;
  align-items: center;
  gap: var(--space-2);
  padding: var(--space-1) 0;
  font-size: var(--text-sm);
  cursor: pointer;
}

.row-page {
  font-weight: 600;
}

.row-action {
  padding-left: var(--space-5);
  color: var(--ink-soft);
}

.row-label {
  display: inline-flex;
  align-items: center;
  gap: var(--space-2);
}

.row-code {
  margin-left: auto;
  font-size: var(--text-xs);
  color: var(--ink-faint);
}

.modal-actions {
  display: flex;
  align-items: center;
  gap: var(--space-2);
  margin-top: var(--space-5);
}

.spacer {
  flex: 1;
}

.count {
  font-size: var(--text-xs);
  color: var(--ink-faint);
}
</style>
