<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import {
  checkRoles,
  createAssistant,
  deleteAssistant,
  listAssistants,
  listDomains,
  listRoles,
  parsePerm,
  parseStringList,
  updateAssistant,
  type AssistantForm,
  type AssistantPerm,
  type AssistantRow,
  type Domain,
  type RoleOption,
} from '../../api/admin'
import RoleMultiSelect from '../../components/admin/RoleMultiSelect.vue'
import UserMultiSelect from '../../components/admin/UserMultiSelect.vue'
import { errorMessage } from '../../api/http'
import { usePermissions } from '../../composables/usePermissions'
import { P } from '../../permissions'
import {
  emptyAssistantPolicy,
  parseAssistantPolicy,
  serializeAssistantPolicy,
  validateAssistantPolicy,
  type AssistantPolicy,
} from './policyCodec'


/**
 * 助理管理。
 *
 * ## 助理层与域层的闸门不同
 *
 * 域看「有没有授权内容」（grantRoles / grantUsers 有值，或 isPublic）；
 * 助理看「enabled 开关是否打开」。两者不可统一——ADR-0002 实测的结论。
 *
 * 表单上把这条写出来，因为它是本项目相对厂商改动最大的地方：
 * 厂商在缺 enabled 键时**对任何角色集放行**（包括空集），本项目改成默认拒绝。
 *
 * ## 助理不是数据保护点
 *
 * 它只决定「这个助理可不可用」。数据保护由域层的行/列规则承担——
 * 这一点也写在界面上，免得管理员以为配了助理权限就等于限制了数据。
 */

const { can } = usePermissions()
const assistants = ref<AssistantRow[]>([])
const domains = ref<Domain[]>([])
const loading = ref(false)
const listError = ref('')

const showForm = ref(false)
const editingId = ref<number | null>(null)
const form = ref<AssistantForm>(emptyForm())
const policy = ref<AssistantPolicy>(emptyAssistantPolicy())
/** 高级 JSON 模式；与结构化表单互斥。 */
const showAdvanced = ref(false)
const advancedJson = ref('')

/**
 * IAM 角色目录里的角色，用于校验助理授权里填的角色名。
 *
 * 这条尤其值得校验：**助理与域是两道独立的闸门**，助理授权里的角色若在绑定域上
 * 没有访问权，那个人就是"看得见助理、点进去被拒"。启动校验也会报这一类。
 */
const allRoles = ref<RoleOption[]>([])
const questionsText = ref('')
const saving = ref(false)
const formError = ref('')

const isEditing = computed(() => editingId.value !== null)

const AVATARS = ['🤖', '🛡️', '📊', '🧭', '💡', '🔍', '📈', '🧮']

function emptyForm(): AssistantForm {
  return {
    name: '',
    avatar: '🤖',
    description: '',
    welcome: '',
    domainId: null,
    domainIds: [],
    showSql: false,
    status: 'draft',
    questions: [],
    permJson: null,
  }
}

function pretty(doc: string): string {
  try {
    return JSON.stringify(JSON.parse(doc), null, 2)
  } catch {
    return doc
  }
}

function toggleAdvanced(): void {
  formError.value = ''
  if (!showAdvanced.value) {
    advancedJson.value = pretty(serializeAssistantPolicy(policy.value))
    showAdvanced.value = true
    return
  }
  const parsed = parseAssistantPolicy(advancedJson.value)
  if (parsed.error) {
    formError.value = `高级 JSON 无法解析：${parsed.error}`
    return
  }
  policy.value = parsed.policy
  showAdvanced.value = false
}

function buildPermJson(): string {
  if (showAdvanced.value) {
    const parsed = parseAssistantPolicy(advancedJson.value)
    if (parsed.error) {
      throw new Error(`高级 JSON 无法解析：${parsed.error}`)
    }
    policy.value = parsed.policy
    showAdvanced.value = false
    return serializeAssistantPolicy(policy.value)
  }
  return serializeAssistantPolicy(policy.value)
}

/** 角色目录问题只提示、不阻断保存。 */
const roleWarnings = computed(() => {
  if (allRoles.value.length === 0) return []
  const codes = [...policy.value.grantRoles].filter(Boolean)
  const problems = checkRoles(codes, allRoles.value)
  return [...problems.entries()]
    .filter(([, p]) => p !== null)
    .map(([code, p]) => `${code}（${p === 'missing' ? '不存在' : '已停用'}）`)
})

async function load(): Promise<void> {
  loading.value = true
  listError.value = ''
  try {
    const [as, ds] = await Promise.all([listAssistants(), listDomains()])
    assistants.value = as
    domains.value = ds
  } catch (e: unknown) {
    listError.value = errorMessage(e)
  } finally {
    loading.value = false
  }
}

onMounted(async () => {
  // 角色拿不到不影响页面可用，故单独 catch
  listRoles()
    .then((r) => (allRoles.value = r))
    .catch(() => (allRoles.value = []))
  await load()
})

function startCreate(): void {
  editingId.value = null
  form.value = emptyForm()
  policy.value = emptyAssistantPolicy()
  showAdvanced.value = false
  advancedJson.value = ''
  questionsText.value = ''
  formError.value = ''
  showForm.value = true
}

function startEdit(a: AssistantRow): void {
  editingId.value = a.id
  const p = parsePerm(a.permJson) as AssistantPerm
  form.value = {
    name: a.name,
    avatar: a.avatar || '🤖',
    description: a.description ?? '',
    welcome: a.welcome ?? '',
    domainId: a.domainId,
    domainIds: parseStringList(a.domainIds),
    showSql: a.showSql,
    status: a.status,
    questions: parseStringList(a.questionsJson),
    permJson: a.permJson,
  }
  const parsed = parseAssistantPolicy(a.permJson)
  policy.value = parsed.error
    ? {
        // 坏 JSON 打开时按默认拒绝，并把原因留给 formError；高级模式仍能看到原文修复
        ...emptyAssistantPolicy(),
        enabled: (p as AssistantPerm).enabled === true,
        grantRoles: (p.grantRoles ?? []) as string[],
        grantUsers: (p.grantUsers ?? []) as string[],
      }
    : parsed.policy
  showAdvanced.value = parsed.error !== ''
  advancedJson.value = parsed.error ? (a.permJson ?? '') : ''
  questionsText.value = parseStringList(a.questionsJson).join('\n')
  formError.value = parsed.error ? `现有策略无法解析，请直接在高级 JSON 中修复：${parsed.error}` : ''
  showForm.value = true
}

async function save(): Promise<void> {
  saving.value = true
  formError.value = ''
  try {
    form.value.permJson = buildPermJson()
    form.value.questions = questionsText.value
      .split('\n')
      .map((x) => x.trim())
      .filter(Boolean)

    const issues = validateAssistantPolicy(policy.value)
    if (issues.length > 0) {
      formError.value = issues.join('；')
      saving.value = false
      return
    }

    if (editingId.value !== null) {
      await updateAssistant(editingId.value, form.value)
    } else {
      await createAssistant(form.value)
    }
    showForm.value = false
    await load()
  } catch (e: unknown) {
    formError.value = errorMessage(e)
  } finally {
    saving.value = false
  }
}

async function remove(a: AssistantRow): Promise<void> {
  if (!window.confirm(`删除助理「${a.name}」？`)) return
  try {
    await deleteAssistant(a.id)
    await load()
  } catch (e: unknown) {
    window.alert(errorMessage(e))
  }
}

function domainName(id: number | null): string {
  if (id === null) return '未绑定'
  return domains.value.find((d) => d.id === id)?.name ?? `#${id}`
}

/**
 * 列表里的访问控制状态。
 *
 * 助理策略只有 `enabled` 一个开关，没有版本号——这里曾显示 `v1/v0`
 * （由开关拼出来），看起来像策略版本，实际是"启用/关闭"。改成直说。
 */
function policyState(a: AssistantRow): { label: string; tone: string } {
  if (!a.permJson || !a.permJson.trim()) return { label: '未配置', tone: 'state-missing' }
  const parsed = parseAssistantPolicy(a.permJson)
  if (parsed.error) return { label: '解析失败', tone: 'state-bad' }
  return parsed.policy.enabled
    ? { label: '已启用', tone: '' }
    : { label: '已关闭', tone: 'state-missing' }
}

const STATUS_LABEL: Record<string, string> = {
  online: '已上线',
  draft: '草稿',
  offline: '已下线',
}
</script>

<template>
  <section>
    <header class="page-head">
      <div>
        <h1 class="page-title">助理</h1>
        <p class="sub">
          助理绑定数据域并控制可见性。<strong>它不限制数据</strong>——数据保护由域层的行/列规则承担。
        </p>
      </div>
      <button v-if="can(P.ASK_ADMIN_ASSISTANTS_WRITE)" class="btn btn-primary" type="button" @click="startCreate">新增助理</button>
    </header>

    <p v-if="listError" class="note note-danger" role="alert">{{ listError }}</p>
    <div v-else-if="loading" class="note">加载中…</div>
    <div v-else-if="assistants.length === 0" class="note">还没有助理。</div>

    <div v-else class="cards">
      <article v-for="a in assistants" :key="a.id" class="card">
        <div class="card-head">
          <span class="avatar">{{ a.avatar || '🤖' }}</span>
          <h2 class="card-title">{{ a.name }}</h2>
          <span class="tag" :class="a.status === 'online' ? '' : 'tag-off'">
            {{ STATUS_LABEL[a.status] ?? a.status }}
          </span>
        </div>

        <p v-if="a.description" class="sub">{{ a.description }}</p>

        <dl class="meta">
          <div>
            <dt>数据域</dt>
            <dd>{{ domainName(a.domainId) }}</dd>
          </div>
          <div>
            <dt>访问权限</dt>
            <dd>
              <span class="state" :class="policyState(a).tone">{{ policyState(a).label }}</span>
              <template v-if="(parsePerm(a.permJson) as AssistantPerm).enabled">
                · {{
                  ((parsePerm(a.permJson) as AssistantPerm).grantRoles ?? []).join('、') ||
                  ((parsePerm(a.permJson) as AssistantPerm).grantUsers ?? []).join('、') ||
                  '名单为空，谁都进不去'
                }}
              </template>
              <template v-else-if="policyState(a).label === '已关闭'">
                · 拒绝所有访问
              </template>
            </dd>
          </div>
          <div>
            <dt>推荐问题</dt>
            <dd>{{ parseStringList(a.questionsJson).length }} 条</dd>
          </div>
        </dl>

        <div class="card-actions">
          <button v-if="can(P.ASK_ADMIN_ASSISTANTS_WRITE)" class="btn btn-quiet btn-sm" type="button" @click="startEdit(a)">编辑</button>
          <button v-if="can(P.ASK_ADMIN_ASSISTANTS_DELETE)" class="btn btn-danger btn-sm" type="button" @click="remove(a)">删除</button>
        </div>
      </article>
    </div>

    <div v-if="showForm" class="modal-mask" @click.self="showForm = false">
      <div class="modal" role="dialog" aria-modal="true">
        <h2 class="modal-title">{{ isEditing ? '编辑助理' : '新增助理' }}</h2>

        <div class="form">
          <div class="two-col">
            <div class="field">
              <label class="label" for="as-name">名称</label>
              <input id="as-name" v-model="form.name" class="input" placeholder="如：保险问数助手" />
            </div>
            <div class="field">
              <label class="label" for="as-status">状态</label>
              <select id="as-status" v-model="form.status" class="input">
                <option value="draft">草稿</option>
                <option value="online">已上线</option>
                <option value="offline">已下线</option>
              </select>
            </div>
          </div>

          <div class="field">
            <span class="label">头像</span>
            <div class="avatars">
              <button
                v-for="e in AVATARS"
                :key="e"
                type="button"
                class="avatar-pick"
                :class="{ 'avatar-on': form.avatar === e }"
                @click="form.avatar = e"
              >
                {{ e }}
              </button>
            </div>
          </div>

          <div class="two-col">
            <div class="field">
              <label class="label" for="as-desc">描述</label>
              <input id="as-desc" v-model="form.description" class="input" />
            </div>
            <div class="field">
              <label class="label" for="as-welcome">欢迎语</label>
              <input id="as-welcome" v-model="form.welcome" class="input" />
            </div>
          </div>

          <div class="field">
            <label class="label" for="as-domain">主数据域</label>
            <select id="as-domain" v-model="form.domainId" class="input">
              <option :value="null">未绑定</option>
              <option v-for="d in domains" :key="d.id" :value="d.id">{{ d.name }}</option>
            </select>
            <p class="field-note">
              问数时用这个域。<strong>多域智能路由尚未实现</strong>——绑定了多个域时，
              界面会让你自己选用哪个，而不是替服务端假装会路由。
            </p>
          </div>

          <label class="checkbox">
            <input v-model="form.showSql" type="checkbox" />
            <span>向用户展示 SQL</span>
          </label>

          <fieldset v-if="!showAdvanced" class="grants">
            <legend class="label">访问权限（策略）</legend>

            <label class="checkbox">
              <input v-model="policy.enabled" type="checkbox" />
              <span>启用访问控制</span>
            </label>

            <p v-if="!policy.enabled" class="field-note">
              未启用时<strong>拒绝所有访问</strong>。厂商在缺这个开关时是对任何角色放行，
              本项目改成了默认拒绝。
            </p>

            <div class="field">
              <label class="label" for="as-roles">授权角色</label>
              <RoleMultiSelect id="as-roles" v-model="policy.grantRoles" :roles="allRoles" />
            </div>
            <div class="field">
              <label class="label" for="as-users">授权用户</label>
              <UserMultiSelect id="as-users" v-model="policy.grantUsers" />
            </div>
            <p v-if="roleWarnings.length" class="field-note note note-warn" role="status">
              角色目录提示：{{ roleWarnings.join('、') }}。停用/不存在的角色引用不会生效，但仍可保存。
            </p>
            <p class="field-note">
              上线中的助理必须至少填一项，否则保存会被拒绝——一个谁都能进的助理
              与一个谁都进不去的助理，都不是配置者想要的结果。
            </p>
          </fieldset>

          <div class="advanced">
            <button type="button" class="btn btn-quiet btn-sm" @click="toggleAdvanced">
              {{ showAdvanced ? '← 返回结构化编辑' : '高级：查看/编辑 JSON' }}
            </button>
            <template v-if="showAdvanced">
              <p class="field-note">保存时以这段 JSON 为准；未知字段会保留。</p>
              <textarea v-model="advancedJson" class="input mono" rows="8" spellcheck="false" />
            </template>
          </div>

          <div class="field">
            <label class="label" for="as-q">推荐问题（每行一条）</label>
            <textarea id="as-q" v-model="questionsText" class="input" rows="4" />
          </div>
        </div>

        <p v-if="formError" class="note note-danger" role="alert">{{ formError }}</p>

        <div class="modal-actions">
          <div class="spacer" />
          <button class="btn btn-quiet" type="button" @click="showForm = false">取消</button>
          <button class="btn btn-primary" type="button" :disabled="saving" @click="save">
            {{ saving ? '保存中…' : '保存' }}
          </button>
        </div>
      </div>
    </div>
  </section>
</template>

<style scoped>
/* 宽屏两列：单列时卡片内容只占左侧一小条，右侧整片留白显得空 */
@media (min-width: 1100px) {
  .cards {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}

.avatar {
  display: grid;
  place-items: center;
  width: 34px;
  height: 34px;
  flex: none;
  border-radius: 11px;
  background: var(--accent-soft);
  font-size: 1.05em;
  line-height: 1;
}

.warn-text {
  color: var(--warn);
}

.state-missing {
  color: var(--warn);
  background: var(--warn-soft, #fff4e0);
}

.state-bad {
  color: var(--danger, #b42318);
  background: var(--danger-soft, #fdecec);
}

/* ── 弹层 ── */
.avatars {
  display: flex;
  flex-wrap: wrap;
  gap: var(--space-2);
}

.avatar-pick {
  width: 2.4em;
  height: 2.4em;
  display: grid;
  place-items: center;
  font-size: 1.1em;
  background: var(--surface-sunk);
  border: 1px solid var(--line-strong);
  border-radius: var(--radius);
  transition:
    border-color var(--duration-fast) var(--ease-out),
    background-color var(--duration-fast) var(--ease-out),
    transform var(--duration-fast) var(--ease-out);
}

.avatar-pick:hover {
  transform: translateY(-1px);
}

.avatar-on {
  border-color: var(--accent);
  background: var(--accent-soft);
}

.grants {
  border: 1px solid var(--line);
  border-radius: var(--radius);
  padding: var(--space-4);
  margin: 0;
  display: flex;
  flex-direction: column;
  gap: var(--space-3);
}

.grants legend {
  padding: 0 var(--space-2);
}

.checkbox {
  display: flex;
  align-items: center;
  gap: var(--space-2);
  font-size: var(--text-sm);
  cursor: pointer;
}

.spacer {
  flex: 1;
}

@media (max-width: 560px) {
  .two-col {
    grid-template-columns: 1fr;
  }
}
</style>
