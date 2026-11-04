<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import {
  boundDomains,
  createModel,
  listModels,
  listRoles,
  setModelEnabled,
  testModel,
  testModelDraft,
  updateModel,
  type AdminModel,
  type AdminModelForm,
  type ModelProbeResult,
  type RoleOption,
} from '../../api/admin'
import { errorMessage } from '../../api/http'
import RoleMultiSelect from '../../components/admin/RoleMultiSelect.vue'
import { usePermissions } from '../../composables/usePermissions'
import { P } from '../../permissions'

/**
 * 模型管理。
 *
 * ## 为什么模型是配置而不是部署参数
 *
 * 单价变化、新模型上线都很频繁；写进 application.yml 就意味着每次都要重启一次。
 * 而「哪个角色能用哪个模型」是业务决策，该由管理员在界面上改。
 *
 * ## 三件必须说清楚的事
 *
 * 1. **密钥只进不出**——保存后只显示「已配置」，编辑时留空 = 不改
 * 2. **单价要么全配、要么全不配**——配一半会算成半对，比不配更难查
 * 3. **停用而不是删除**——审计里存的是模型键，删了历史成本就断链
 *
 * ## 供应商预设
 *
 * base-url 是这个系统区分供应商的唯一判据（全走 OpenAI 兼容协议）。
 * 让管理员手打 URL 极易错（少个 /v1、多个空格），而错了的表现是 404 或
 * 奇怪的解析错误。所以给预设，选了自动填。
 */

const { can } = usePermissions()

/**
 * 供应商预设。
 *
 * `custom` 的 baseUrl 为空：让管理员自己填，用来接 vLLM / Ollama / 代理。
 * **不做厂商枚举的硬编码校验**——base-url 是唯一判据，加一个供应商
 * 应该只是加一行配置，不是发一次版。
 */
const VENDORS = [
  { key: 'ark', name: '火山方舟', baseUrl: 'https://ark.cn-beijing.volces.com/api/coding/v3' },
  { key: 'openai', name: 'OpenAI', baseUrl: 'https://api.openai.com/v1' },
  { key: 'bailian', name: '阿里百炼', baseUrl: 'https://dashscope.aliyuncs.com/compatible-mode/v1' },
  { key: 'deepseek', name: 'DeepSeek 官方', baseUrl: 'https://api.deepseek.com/v1' },
  { key: 'custom', name: '自定义…', baseUrl: '' },
]

const list = ref<AdminModel[]>([])
const roles = ref<RoleOption[]>([])
const loading = ref(false)
const listError = ref('')

const showForm = ref(false)
const editingKey = ref<string | null>(null)
const form = ref<AdminModelForm>(emptyForm())
const saving = ref(false)
const formError = ref('')

const testing = ref(false)
const probe = ref<ModelProbeResult | null>(null)

/** 行内测试结果，按 key 存。 */
const rowProbe = ref<Record<string, ModelProbeResult | null>>({})

/** 停用前查出来的域绑定。 */
const bindings = ref<Record<string, Array<{ id: number; name: string }>>>({})

/** 表单里的供应商选择。只在新建/换供应商时驱动 base-url。 */
const vendor = ref<string>('ark')

function emptyForm(): AdminModelForm {
  return {
    key: '',
    displayName: '',
    description: '',
    baseUrl: VENDORS[0]!.baseUrl,
    apiKey: '',
    modelName: '',
    allowedRoles: [],
    priceInput: null,
    priceCached: null,
    priceOutput: null,
    isDefault: false,
    enabled: true,
  }
}

const isEditing = computed(() => editingKey.value !== null)

async function load(): Promise<void> {
  loading.value = true
  listError.value = ''
  try {
    list.value = await listModels()
    // 角色目录拿不到不该让整页空白：模型列表本身仍然可用，
    // 只是"可用角色"那一栏会显示成裸码
    try {
      roles.value = await listRoles()
    } catch {
      roles.value = []
    }
  } catch (e: unknown) {
    listError.value = errorMessage(e)
  } finally {
    loading.value = false
  }
}

onMounted(load)

function startCreate(): void {
  editingKey.value = null
  form.value = emptyForm()
  vendor.value = 'ark'
  formError.value = ''
  probe.value = null
  showForm.value = true
}

function startEdit(m: AdminModel): void {
  editingKey.value = m.key
  form.value = {
    key: m.key,
    displayName: m.displayName,
    description: m.description ?? '',
    baseUrl: m.baseUrl,
    // **密钥不回显**，留空表示不改
    apiKey: '',
    modelName: m.modelName,
    allowedRoles: [...m.allowedRoles],
    priceInput: m.priceInput,
    priceCached: m.priceCached,
    priceOutput: m.priceOutput,
    isDefault: m.isDefault,
    enabled: m.enabled,
  }
  // 匹配已有的 base-url 到预设，匹配不上就是自定义
  vendor.value = VENDORS.find((v) => v.baseUrl && v.baseUrl === m.baseUrl)?.key ?? 'custom'
  formError.value = ''
  probe.value = null
  showForm.value = true
}

/**
 * 选供应商时自动填 base-url。
 *
 * 只改地址，不动其它字段——管理员可能已经改过地址或填好了密钥。
 * 换供应商通常意味着密钥也要换，但这里**不替他清空**：
 * 清空是不可逆的（他得重新找密钥），而留着只是多改一格。
 */
function onVendorChange(): void {
  const v = VENDORS.find((x) => x.key === vendor.value)
  if (v && v.baseUrl) {
    form.value.baseUrl = v.baseUrl
  }
}

async function runTest(): Promise<void> {
  testing.value = true
  probe.value = null
  try {
    probe.value = await testModelDraft(editingKey.value, form.value)
  } catch (e: unknown) {
    probe.value = { ok: false, message: errorMessage(e), elapsedMs: 0 }
  } finally {
    testing.value = false
  }
}

async function testRow(m: AdminModel): Promise<void> {
  rowProbe.value = { ...rowProbe.value, [m.key]: { ok: true, message: '测试中…', elapsedMs: 0 } }
  try {
    rowProbe.value = { ...rowProbe.value, [m.key]: await testModel(m.key) }
  } catch (e: unknown) {
    rowProbe.value = { ...rowProbe.value, [m.key]: { ok: false, message: errorMessage(e), elapsedMs: 0 } }
  }
}

async function save(): Promise<void> {
  saving.value = true
  formError.value = ''
  try {
    if (editingKey.value !== null) {
      await updateModel(editingKey.value, form.value)
    } else {
      await createModel(form.value)
    }
    showForm.value = false
    await load()
  } catch (e: unknown) {
    formError.value = errorMessage(e)
  } finally {
    saving.value = false
  }
}

/**
 * 启停。
 *
 * **停用前先查域绑定**：停用之后那些域会立刻问不了，而管理员的本意
 * 通常只是"把它从问数下拉里摘掉"。不提示的话他会在几分钟后收到报障。
 */
async function toggle(m: AdminModel): Promise<void> {
  const next = !m.enabled

  if (!next) {
    let bound: Array<{ id: number; name: string }> = []
    try {
      bound = await boundDomains(m.key)
      bindings.value = { ...bindings.value, [m.key]: bound }
    } catch {
      // 查不到绑定不阻止停用——那只是提示，不是守卫
    }
    const warn = bound.length
      ? `\n\n⚠️ 有 ${bound.length} 个域绑定着它：${bound.map((d) => d.name).join('、')}。`
        + '停用后这些域会立刻无法问数。'
      : ''
    if (!window.confirm(`停用模型「${m.displayName}」？${warn}\n\n历史审计记录不受影响。`)) {
      return
    }
  }

  try {
    await setModelEnabled(m.key, next)
    await load()
  } catch (e: unknown) {
    window.alert(errorMessage(e))
  }
}

/** 可用角色的展示文本。空列表 = 不限制，**不是"谁都不可用"**。 */
function rolesLabel(m: AdminModel): string {
  if (m.allowedRoles.length === 0) return '全部（不限制）'
  const byCode = new Map(roles.value.map((r) => [r.code, r.name]))
  return m.allowedRoles.map((c) => byCode.get(c) ?? c).join('、')
}

/** 单价展示。未配时显示「未配单价」而不是 ¥0。 */
function priceLabel(m: AdminModel): string {
  if (!m.priced) return '未配单价'
  const fmt = (v: number | null) => (v === null ? '—' : `¥${v}`)
  return `输入 ${fmt(m.priceInput)} / 缓存 ${fmt(m.priceCached)} / 输出 ${fmt(m.priceOutput)}`
}

/** 表单里凭空的"全不配"与"配一半"要当场提示，别等保存被拒。 */
const priceWarning = computed(() => {
  const { priceInput: i, priceCached: c, priceOutput: o } = form.value
  const any = i !== null || c !== null || o !== null
  if (!any) return ''
  if (i === null || o === null) {
    return '单价要么全配（输入、输出必填），要么全不配。配一半会让成本算成半对。'
  }
  if ((i !== null && i <= 0) || (c !== null && c <= 0) || (o !== null && o <= 0)) {
    return '单价必须大于 0。不配请全部留空——0 元会被显示成「免费」，而它其实是「不知道」。'
  }
  return ''
})
</script>

<template>
  <section>
    <header class="page-head">
      <div>
        <h1 class="page-title">模型</h1>
        <p class="sub">
          问数使用的模型。可来自不同厂商——只要兼容 OpenAI 协议，换供应商只改接口地址。
          密钥加密存储，保存后不再回显。
        </p>
      </div>
      <button
        v-if="can(P.ASK_ADMIN_MODELS_WRITE)"
        class="btn btn-primary"
        type="button"
        @click="startCreate"
      >
        新增模型
      </button>
    </header>

    <p v-if="listError" class="note note-danger" role="alert">{{ listError }}</p>
    <div v-else-if="loading" class="note">加载中…</div>

    <div v-else-if="list.length === 0" class="note">
      还没有模型。点右上角「新增模型」开始——问数没有模型时无法提问。
    </div>

    <div v-else class="cards">
      <article v-for="m in list" :key="m.key" class="card" :class="{ off: !m.enabled }">
        <div class="card-head">
          <h2 class="card-title">{{ m.displayName }}</h2>
          <span v-if="m.isDefault" class="tag tag-default">默认</span>
          <span class="tag tag-key mono">{{ m.key }}</span>
          <span v-if="!m.enabled" class="tag tag-off">已停用</span>
        </div>

        <p v-if="m.description" class="desc">{{ m.description }}</p>

        <dl class="meta">
          <div>
            <dt>地址</dt>
            <dd class="mono ellipsis" :title="m.baseUrl">{{ m.baseUrl }}</dd>
          </div>
          <div>
            <dt>模型名</dt>
            <dd class="mono">{{ m.modelName }}</dd>
          </div>
          <div>
            <dt>密钥</dt>
            <dd>
              <span v-if="m.hasApiKey" class="ok">已配置</span>
              <span v-else class="warn">未配置</span>
            </dd>
          </div>
          <div>
            <dt>单价</dt>
            <dd :class="{ muted: !m.priced }">{{ priceLabel(m) }}</dd>
          </div>
          <div>
            <dt>可用角色</dt>
            <dd>{{ rolesLabel(m) }}</dd>
          </div>
        </dl>

        <p class="footnote">
          单价单位：元 / 百万 token。
          <span v-if="m.priced && m.pricedAt">配于 {{ m.pricedAt.slice(0, 10) }}。</span>
          <span v-if="!m.priced">未配单价时能统计 token，但算不出费用——成本看板会显示「未配单价」。</span>
        </p>

        <p v-if="rowProbe[m.key]" class="note probe" :class="rowProbe[m.key]?.ok ? 'note-ok' : 'note-danger'">
          {{ rowProbe[m.key]?.message }}
        </p>

        <div class="card-actions">
          <button
            v-if="can(P.ASK_ADMIN_MODELS_TEST)"
            class="btn btn-quiet btn-sm"
            type="button"
            @click="testRow(m)"
          >
            测试
          </button>
          <button
            v-if="can(P.ASK_ADMIN_MODELS_WRITE)"
            class="btn btn-quiet btn-sm"
            type="button"
            @click="startEdit(m)"
          >
            编辑
          </button>
          <button
            v-if="can(P.ASK_ADMIN_MODELS_WRITE)"
            class="btn btn-quiet btn-sm"
            type="button"
            @click="toggle(m)"
          >
            {{ m.enabled ? '停用' : '启用' }}
          </button>
        </div>
      </article>
    </div>

    <!-- 弹层：字段多，内联会把列表挤得没法看 -->
    <div v-if="showForm" class="modal-mask" @click.self="showForm = false">
      <div class="modal" role="dialog" aria-modal="true" :aria-label="isEditing ? '编辑模型' : '新增模型'">
        <h2 class="modal-title">{{ isEditing ? '编辑模型' : '新增模型' }}</h2>

        <div class="form">
          <div class="two-col">
            <div class="field">
              <label class="label" for="md-key">模型键</label>
              <input
                id="md-key"
                v-model="form.key"
                class="input mono"
                :disabled="isEditing"
                placeholder="如 flash"
              />
              <span class="field-note">
                审计里存的是它，<strong>创建后不可改</strong>（改了历史成本会断链）。
                只能用小写字母、数字、下划线、连字符。
              </span>
            </div>
            <div class="field">
              <label class="label" for="md-name">显示名</label>
              <input id="md-name" v-model="form.displayName" class="input" placeholder="如 DeepSeek V4 Flash（快速）" />
            </div>
          </div>

          <div class="field">
            <label class="label" for="md-desc">用途说明</label>
            <input id="md-desc" v-model="form.description" class="input" placeholder="写用途不写参数，如「日常问数，响应快、成本低」" />
            <span class="field-note">问数页的模型下拉里会显示它——写「复杂分析用」比「2.0元/百万token」有用。</span>
          </div>

          <div class="field">
            <label class="label" for="md-vendor">厂商</label>
            <select id="md-vendor" v-model="vendor" class="input" @change="onVendorChange">
              <option v-for="v in VENDORS" :key="v.key" :value="v.key">{{ v.name }}</option>
            </select>
            <span class="field-note">
              选了只**自动填地址**，不改其它字段。自定义用来接 vLLM / Ollama / 代理。
            </span>
          </div>

          <div class="field">
            <label class="label" for="md-url">接口地址</label>
            <textarea id="md-url" v-model="form.baseUrl" class="input mono" rows="2" />
          </div>

          <div class="two-col">
            <div class="field">
              <label class="label" for="md-model">模型名</label>
              <input id="md-model" v-model="form.modelName" class="input mono" placeholder="供应商侧的准确名称" />
            </div>
            <div class="field">
              <label class="label" for="md-apikey">
                密钥
                <span v-if="isEditing" class="hint">（留空表示不修改）</span>
              </label>
              <input
                id="md-apikey"
                v-model="form.apiKey"
                class="input"
                type="password"
                autocomplete="new-password"
                :placeholder="isEditing ? '已配置，留空不改' : 'sk-...'"
              />
            </div>
          </div>

          <div class="field">
            <label class="label" for="md-roles">可用角色</label>
            <RoleMultiSelect id="md-roles" v-model="form.allowedRoles" :roles="roles" />
            <span class="field-note">
              **不选任何角色 = 不限制**（所有人都能用），不是「谁都不可用」。
              想让某个模型只给少数人用，就只选那几个人持有的角色。
            </span>
          </div>

          <fieldset class="prices">
            <legend class="label">单价（元 / 百万 token）</legend>
            <div class="three-col">
              <div class="field">
                <label class="label label-sm" for="md-pi">输入</label>
                <input id="md-pi" v-model.number="form.priceInput" class="input mono" type="number" step="0.001" min="0" placeholder="留空" />
              </div>
              <div class="field">
                <label class="label label-sm" for="md-pc">缓存输入</label>
                <input id="md-pc" v-model.number="form.priceCached" class="input mono" type="number" step="0.001" min="0" placeholder="留空" />
              </div>
              <div class="field">
                <label class="label label-sm" for="md-po">输出</label>
                <input id="md-po" v-model.number="form.priceOutput" class="input mono" type="number" step="0.001" min="0" placeholder="留空" />
              </div>
            </div>
            <p v-if="priceWarning" class="field-note note note-warn" role="status">{{ priceWarning }}</p>
            <p v-else class="field-note">
              全留空 = 算不出钱（自建模型就是这种情况），成本看板会显示「未配单价」。
              缓存单价是「缓存命中的那部分输入」的价，通常便宜十倍。
            </p>
          </fieldset>

          <label class="checkbox">
            <input v-model="form.isDefault" type="checkbox" />
            <span>设为全局默认（用户没选、域也没绑定时用它）</span>
          </label>
          <label class="checkbox">
            <input v-model="form.enabled" type="checkbox" />
            <span>启用</span>
          </label>
        </div>

        <p v-if="probe" class="note probe" :class="probe.ok ? 'note-ok' : 'note-danger'" role="status">
          {{ probe.message }}
        </p>
        <p v-if="formError" class="note note-danger" role="alert">{{ formError }}</p>

        <div class="modal-actions">
          <button class="btn btn-quiet" type="button" :disabled="testing" @click="runTest">
            {{ testing ? '测试中…' : '测试连通性' }}
          </button>
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
.ellipsis { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }

.card.off { opacity: 0.62; }
.card-actions { display: flex; gap: var(--space-2); flex-wrap: wrap; }

.tag-key { font-size: 0.7rem; }
.tag-default { background: #eef2ff; color: #4338ca; }

.desc { font-size: var(--text-sm); color: var(--ink-muted); margin: 0 0 var(--space-3); }

.ok { color: #047857; }
.warn { color: #b45309; }
.muted { color: var(--ink-faint); }

.footnote {
  font-size: 0.76rem; color: var(--ink-faint);
  margin: var(--space-3) 0 0; line-height: 1.6;
}

.hint {
  font-weight: 400; letter-spacing: 0; text-transform: none; color: var(--ink-faint);
}

.field-note {
  display: block;
  font-size: 0.76rem;
  color: var(--ink-faint);
  margin-top: 4px;
  line-height: 1.6;
}

.prices {
  border: 1px solid var(--line-soft);
  border-radius: var(--radius-md);
  padding: var(--space-4);
  margin: 0;
}
.three-col { display: grid; grid-template-columns: repeat(3, 1fr); gap: var(--space-3); }
.two-col { display: grid; grid-template-columns: 1fr 1fr; gap: var(--space-3); }
.label-sm { font-size: 0.72rem; }

.checkbox {
  display: flex; align-items: center; gap: var(--space-2);
  font-size: var(--text-sm); cursor: pointer;
}

/* 失败信息可能是多行（带供应商原文），必须允许换行 */
.probe { margin-top: var(--space-4); white-space: pre-wrap; }
.note-ok { background: #ecfdf5; border: 1px solid #a7f3d0; color: #047857; border-radius: 9px; padding: 10px 14px; }
.spacer { flex: 1; }

@media (max-width: 560px) {
  .two-col, .three-col { grid-template-columns: 1fr; }
}
</style>
