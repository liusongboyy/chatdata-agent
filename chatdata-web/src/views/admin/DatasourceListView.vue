<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import {
  createDatasource,
  deleteDatasource,
  listDatasources,
  testDatasource,
  testSavedDatasource,
  updateDatasource,
  updateDatasourcePassword,
  type Datasource,
  type DatasourceForm,
} from '../../api/admin'
import { errorMessage } from '../../api/http'
import { usePermissions } from '../../composables/usePermissions'
import { P } from '../../permissions'


/**
 * 数据源管理。
 *
 * 三种数据库类型（MySQL / Doris / PostgreSQL / Oracle / SQLServer）的默认驱动与 URL 模板
 * 是从后端 `SqlDialects` 支持列表里对齐的。选类型自动带出驱动与 URL 骨架，
 * 省掉最容易拼错的一步。
 */
const { can } = usePermissions()
const DB_TYPES = [
  {
    value: 'MYSQL5.7',
    label: 'MySQL',
    driver: 'com.mysql.cj.jdbc.Driver',
    url: 'jdbc:mysql://127.0.0.1:3306/db?useUnicode=true&characterEncoding=UTF-8&useSSL=false&allowPublicKeyRetrieval=true',
  },
  {
    value: 'Doris',
    label: 'Apache Doris',
    driver: 'com.mysql.cj.jdbc.Driver',
    url: 'jdbc:mysql://127.0.0.1:9030/db?useUnicode=true&characterEncoding=UTF-8',
  },
  {
    value: 'POSTGRESQL',
    label: 'PostgreSQL',
    driver: 'org.postgresql.Driver',
    url: 'jdbc:postgresql://127.0.0.1:5432/db',
  },
  {
    value: 'ORACLE',
    label: 'Oracle',
    driver: 'oracle.jdbc.OracleDriver',
    url: 'jdbc:oracle:thin:@127.0.0.1:1521/ORCL',
  },
  {
    value: 'SQLSERVER',
    label: 'SQL Server',
    driver: 'com.microsoft.sqlserver.jdbc.SQLServerDriver',
    url: 'jdbc:sqlserver://127.0.0.1:1433;DatabaseName=db',
  },
]

const list = ref<Datasource[]>([])
const loading = ref(false)
const listError = ref('')

const showForm = ref(false)
const editingId = ref<number | null>(null)
const form = ref<DatasourceForm>(emptyForm())
const saving = ref(false)
const formError = ref('')

const testing = ref(false)
const probe = ref<{ ok: boolean; message: string } | null>(null)

/** 行内试连的结果，按 id 存。 */
const rowProbe = ref<Record<number, { ok: boolean; message: string } | null>>({})

function emptyForm(): DatasourceForm {
  const t = DB_TYPES[0]!
  return {
    name: '',
    dbType: t.value,
    driverClass: t.driver,
    url: t.url,
    username: '',
    password: '',
    enabled: true,
  }
}

const isEditing = computed(() => editingId.value !== null)

async function load(): Promise<void> {
  loading.value = true
  listError.value = ''
  try {
    list.value = await listDatasources()
  } catch (e: unknown) {
    listError.value = errorMessage(e)
  } finally {
    loading.value = false
  }
}

onMounted(load)

function startCreate(): void {
  editingId.value = null
  form.value = emptyForm()
  formError.value = ''
  probe.value = null
  showForm.value = true
}

function startEdit(d: Datasource): void {
  editingId.value = d.id
  form.value = {
    name: d.name,
    dbType: d.dbType,
    driverClass: d.driverClass,
    url: d.url,
    username: d.username ?? '',
    // 密码不回显，留空表示不改
    password: '',
    enabled: d.enabled,
  }
  formError.value = ''
  probe.value = null
  showForm.value = true
}

function onTypeChange(): void {
  const t = DB_TYPES.find((x) => x.value === form.value.dbType)
  if (t) {
    form.value.driverClass = t.driver
    form.value.url = t.url
  }
}

async function runTest(): Promise<void> {
  testing.value = true
  probe.value = null
  try {
    probe.value = await testDatasource(form.value)
  } catch (e: unknown) {
    probe.value = { ok: false, message: errorMessage(e) }
  } finally {
    testing.value = false
  }
}

async function save(): Promise<void> {
  saving.value = true
  formError.value = ''
  try {
    if (editingId.value !== null) {
      await updateDatasource(editingId.value, form.value)
      // **密码要单独调一个端点**：`updateDatasource` 里写着"不动密码"，
      // 这是后端刻意的拆分（合成的接口迟早有人改个名字就把密码清掉）。
      // 留空表示沿用已存密码——与界面上那句提示一致。
      if (form.value.password) {
        await updateDatasourcePassword(editingId.value, form.value.password)
      }
    } else {
      await createDatasource(form.value)
    }
    showForm.value = false
    await load()
  } catch (e: unknown) {
    formError.value = errorMessage(e)
  } finally {
    saving.value = false
  }
}

async function testRow(d: Datasource): Promise<void> {
  rowProbe.value = { ...rowProbe.value, [d.id]: { ok: true, message: '测试中…' } }
  try {
    rowProbe.value = { ...rowProbe.value, [d.id]: await testSavedDatasource(d.id) }
  } catch (e: unknown) {
    rowProbe.value = { ...rowProbe.value, [d.id]: { ok: false, message: errorMessage(e) } }
  }
}

async function remove(d: Datasource): Promise<void> {
  if (!window.confirm(`删除数据源「${d.name}」？此操作不可恢复。`)) return
  try {
    await deleteDatasource(d.id)
    await load()
  } catch (e: unknown) {
    // 被域引用时后端会拒绝并说明原因——直接展示，不要吞掉
    window.alert(errorMessage(e))
  }
}
</script>

<template>
  <section>
    <header class="page-head">
      <div>
        <h1 class="page-title">数据源</h1>
        <p class="sub">语义域从这里取数。密码加密存储，保存后不再回显。</p>
      </div>
      <button v-if="can(P.ASK_ADMIN_DATASOURCES_WRITE)" class="btn btn-primary" type="button" @click="startCreate">新增数据源</button>
    </header>

    <p v-if="listError" class="note note-danger" role="alert">{{ listError }}</p>

    <div v-else-if="loading" class="note">加载中…</div>

    <div v-else-if="list.length === 0" class="note">
      还没有数据源。点右上角「新增数据源」开始。
    </div>

    <div v-else class="cards">
      <article v-for="d in list" :key="d.id" class="card">
        <div class="card-head">
          <h2 class="card-title">{{ d.name }}</h2>
          <span class="tag">{{ d.dbType }}</span>
          <span v-if="!d.enabled" class="tag tag-off">已停用</span>
        </div>

        <dl class="meta">
          <div>
            <dt>URL</dt>
            <dd class="mono ellipsis" :title="d.url">{{ d.url }}</dd>
          </div>
          <div>
            <dt>账号</dt>
            <dd class="mono">{{ d.username || '—' }}</dd>
          </div>
          <div>
            <dt>驱动</dt>
            <dd class="mono ellipsis" :title="d.driverClass">{{ d.driverClass }}</dd>
          </div>
        </dl>

        <p v-if="rowProbe[d.id]" class="note" :class="rowProbe[d.id]?.ok ? '' : 'note-danger'">
          {{ rowProbe[d.id]?.message }}
        </p>

        <div class="card-actions">
          <button v-if="can(P.ASK_ADMIN_DATASOURCES_TEST)" class="btn btn-quiet btn-sm" type="button" @click="testRow(d)">测试连接</button>
          <button v-if="can(P.ASK_ADMIN_DATASOURCES_WRITE)" class="btn btn-quiet btn-sm" type="button" @click="startEdit(d)">编辑</button>
          <button v-if="can(P.ASK_ADMIN_DATASOURCES_DELETE)" class="btn btn-danger btn-sm" type="button" @click="remove(d)">删除</button>
        </div>
      </article>
    </div>

    <!-- 表单用弹层而不是内联展开：字段多，内联会把列表挤得没法看 -->
    <div v-if="showForm" class="modal-mask" @click.self="showForm = false">
      <div class="modal" role="dialog" aria-modal="true" :aria-label="isEditing ? '编辑数据源' : '新增数据源'">
        <h2 class="modal-title">{{ isEditing ? '编辑数据源' : '新增数据源' }}</h2>

        <div class="form">
          <div class="field">
            <label class="label" for="ds-name">名称</label>
            <input id="ds-name" v-model="form.name" class="input" placeholder="如：本地问数库" />
          </div>

          <div class="field">
            <label class="label" for="ds-type">数据库类型</label>
            <select id="ds-type" v-model="form.dbType" class="input" @change="onTypeChange">
              <option v-for="t in DB_TYPES" :key="t.value" :value="t.value">{{ t.label }}</option>
            </select>
          </div>

          <div class="field">
            <label class="label" for="ds-driver">驱动类</label>
            <input id="ds-driver" v-model="form.driverClass" class="input mono" />
          </div>

          <div class="field">
            <label class="label" for="ds-url">JDBC URL</label>
            <textarea id="ds-url" v-model="form.url" class="input mono" rows="2" />
          </div>

          <div class="two-col">
            <div class="field">
              <label class="label" for="ds-user">账号</label>
              <input id="ds-user" v-model="form.username" class="input" autocomplete="off" />
            </div>
            <div class="field">
              <label class="label" for="ds-pass">
                密码
                <span v-if="isEditing" class="hint">（留空表示不修改）</span>
              </label>
              <input
                id="ds-pass"
                v-model="form.password"
                class="input"
                type="password"
                autocomplete="new-password"
              />
            </div>
          </div>

          <label class="checkbox">
            <input v-model="form.enabled" type="checkbox" />
            <span>启用</span>
          </label>
        </div>

        <!-- 试连结果就地显示在按钮下方，不弹 toast——
             失败信息可能多行（后端会给出原始报错），toast 装不下 -->
        <p
          v-if="probe"
          class="note probe"
          :class="probe.ok ? '' : 'note-danger'"
          role="status"
        >
          {{ probe.message }}
        </p>

        <p v-if="formError" class="note note-danger" role="alert">{{ formError }}</p>

        <div class="modal-actions">
          <button class="btn btn-quiet" type="button" :disabled="testing" @click="runTest">
            {{ testing ? '测试中…' : '测试连接' }}
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
.ellipsis {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

/* ── 弹层 ── */
.hint {
  font-weight: 400;
  letter-spacing: 0;
  text-transform: none;
  color: var(--ink-faint);
}

.checkbox {
  display: flex;
  align-items: center;
  gap: var(--space-2);
  font-size: var(--text-sm);
  cursor: pointer;
}

/* 试连失败的信息可能是多行（后端会带原始报错），必须允许换行 */
.probe {
  margin-top: var(--space-4);
  white-space: pre-wrap;
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
