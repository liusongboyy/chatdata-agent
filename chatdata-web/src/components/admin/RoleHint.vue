<script setup lang="ts">
import { computed } from 'vue'
import { checkRoles, type RoleOption, type RoleProblem } from '../../api/admin'

/**
 * 角色输入框下面的提示：**可选角色点一下就填**，填错的当场标出来。
 *
 * <h2>它解决的是什么问题</h2>
 *
 * 权限配置里的角色名必须与 IAM 角色目录里的角色码（`iam_role.role_code`）**逐字符一致**，否则规则静默失效
 * ——不报错、不打日志，只是永远不匹配（见 `docs/permission-config.md` 第四节）。
 * 这个坑真实发生过：`工单问数助手` 的授权里写了角色 `管理员`，而当时角色目录
 * 里没有这个角色，于是一个本该能用的助理对管理员凭空消失。
 *
 * 所以这里做两件事，顺序有讲究：
 *
 * 1. **把可选角色列出来让人点选**——这是治本。手打才是错误的来源
 * 2. **标出已经填错的**——这是治标，但对已经存下来的旧配置同样有用
 *
 * <h2>为什么只提示、不阻止保存</h2>
 *
 * 界面无法确定角色表是不是最新的（别人可能刚在别处加了角色）。**拦住保存**
 * 会把"角色还没建好，先配权限"这个正常流程堵死；而提示足以让人发现打错字。
 * 真正兜底的是启动校验——它读的是 IAM 里当时生效的那份角色目录。
 */
const props = withDefaults(
  defineProps<{
    /** 当前输入框里的原文，如「区域经理、分析师」 */
    value: string
    roles: RoleOption[]
    /**
     * 要不要显示"点一下就填"的建议。
     *
     * 行/列规则是个 **JSON 文本框**，往里面追加角色码没有意义（得追进数组里），
     * 所以那里只保留"填错了"的警告。**点不动的 chip 比不显示更糟**——
     * 用户会以为界面坏了。
     */
    suggest?: boolean
  }>(),
  { suggest: true },
)

const emit = defineEmits<{ 'update:value': [string] }>()

/** 与后端的 splitList 一致：中英文逗号、顿号、空白都算分隔符。 */
function split(raw: string): string[] {
  return raw
    .split(/[、,，\s]+/)
    .map((s) => s.trim())
    .filter(Boolean)
}

const codes = computed(() => split(props.value))
const problems = computed(() => checkRoles(codes.value, props.roles))

/** 有问题的角色，按「不存在」在前——那个更可能是打错字。 */
const bad = computed(() =>
  [...problems.value.entries()]
    .filter((entry): entry is [string, Exclude<RoleProblem, null>] => entry[1] !== null)
    .sort((a, b) => (a[1] === b[1] ? 0 : a[1] === 'missing' ? -1 : 1)),
)

/**
 * 角色目录拿到了才谈得上"不存在"。
 *
 * <p>角色目录来自 IAM（`GET /iam/policy/roles`），IAM 不可用时后端返回 503、
 * 界面把列表置空。此时若照常比对，**每个角色都会被报成"不存在"**——
 * 管理员会以为自己的配置全废了。宁可说"无法校验"，也不给一个假的判决。
 */
const rolesKnown = computed(() => props.roles.length > 0)
const missing = computed(() =>
  rolesKnown.value ? bad.value.filter(([, p]) => p === 'missing').map(([c]) => c) : [],
)
const disabled = computed(() =>
  rolesKnown.value ? bad.value.filter(([, p]) => p === 'disabled').map(([c]) => c) : [],
)

/** 还没填的可用角色——点一下追加到输入框末尾。 */
const suggestions = computed(() =>
  props.roles.filter((r) => r.enabled && !codes.value.includes(r.code)),
)

function append(code: string): void {
  emit('update:value', [...codes.value, code].join('、'))
}
</script>

<template>
  <!-- 打错了才报警。全对时只留建议，不占视觉重量 -->
  <p v-if="missing.length" class="role-hint note note-danger" role="alert">
    这些角色在 <strong>IAM 角色目录</strong>里不存在：{{ missing.join('、') }}。
    <template v-if="props.suggest">
      该授权不会对任何人生效——请从下面点选，或先去角色管理里建好。
    </template>
    <template v-else>
      <!-- 规则里角色失效比授权里更危险：授权失效是"少给了权限"，
           规则失效是"限制没生效"——受限角色照样看到全量/明文 -->
      这条规则<strong>不会生效</strong>，本该被限制的角色会看到全部数据。请改成上面列出的角色码。
    </template>
  </p>
  <p v-else-if="disabled.length" class="role-hint note note-warn">
    这些角色<strong>已停用</strong>：{{ disabled.join('、') }}。停用的角色不参与鉴权，
    引用它的规则同样不会生效。
  </p>

  <p v-if="props.suggest && suggestions.length" class="role-hint role-pick">
    <span class="label">可选角色</span>
    <button
      v-for="r in suggestions"
      :key="r.code"
      type="button"
      class="chip chip-sm"
      :title="r.code"
      @click="append(r.code)"
    >
      {{ r.name || r.code }}
    </button>
  </p>
  <p v-if="roles.length === 0" class="role-hint">
    读不到 IAM 角色目录，暂时无法校验角色名——保存前请自行确认。
  </p>
</template>

<style scoped>
.role-hint {
  margin-top: var(--space-2);
}

.role-hint code {
  font-family: var(--font-mono);
  font-size: 0.92em;
}

/* 建议是"可点的",所以排成一行能换行的 chip */
.role-pick {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: var(--space-2);
  font-size: var(--text-sm);
  color: var(--ink-soft);
}

.chip {
  padding: 4px 10px;
  border: 1px solid var(--line);
  border-radius: var(--radius-pill);
  background: var(--surface);
  font-size: 11px;
  color: var(--ink-soft);
  transition:
    border-color var(--duration-fast) var(--ease-out),
    background-color var(--duration-fast) var(--ease-out),
    color var(--duration-fast) var(--ease-out);
}

.chip:hover {
  border-color: transparent;
  background: var(--accent-soft);
  color: var(--accent-deep);
}

.chip-sm {
  font-size: 10.5px;
}
</style>
