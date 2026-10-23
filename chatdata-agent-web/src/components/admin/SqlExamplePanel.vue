<script setup lang="ts">
import { onMounted, ref } from 'vue'
import {
  createSqlExample,
  deleteSqlExample,
  listSqlExamples,
  updateSqlExample,
  type SqlExample,
  type SqlExampleForm,
} from '../../api/admin'
import { errorMessage } from '../../api/http'

/**
 * SQL 范例库（直连模式的 few-shot）。
 *
 * <h2>为什么只给直连模式看</h2>
 *
 * 建模模式的模型产出的是 DSL，SQL 由服务端渲染——给它看 SQL 范例没有意义。
 * 直连模式的模型**自己写 SQL**，范例才是直接可用的知识。
 *
 * <h2>为什么没有"从审计里自动导入"</h2>
 *
 * 执行成功 ≠ 语义正确。一条把「上个月」丢成全量、但执行成功的查询，
 * 收进范例库就等于**教模型重复那个错误**，而且它会成为后续相似问题的模板。
 * 所以这里只有手工新增/编辑：**放进来就意味着有人确认过它是对的**。
 */
const props = defineProps<{ domainId: number }>()

const examples = ref<SqlExample[]>([])
const loading = ref(false)
const error = ref('')
const notice = ref('')

const editingId = ref<number | null>(null)
const showForm = ref(false)
const form = ref<SqlExampleForm>(emptyForm())
const saving = ref(false)

function emptyForm(): SqlExampleForm {
  return { question: '', sql: '', note: null, keywords: null, enabled: true, sortNo: 0 }
}

async function load(): Promise<void> {
  loading.value = true
  error.value = ''
  try {
    examples.value = await listSqlExamples(props.domainId)
  } catch (e: unknown) {
    error.value = errorMessage(e)
  } finally {
    loading.value = false
  }
}

onMounted(load)

function startCreate(): void {
  editingId.value = null
  form.value = emptyForm()
  showForm.value = true
  notice.value = ''
}

function startEdit(e: SqlExample): void {
  editingId.value = e.id
  form.value = {
    question: e.question,
    sql: e.sql,
    note: e.note,
    keywords: e.keywords,
    enabled: e.enabled,
    sortNo: 0,
  }
  showForm.value = true
  notice.value = ''
}

async function save(): Promise<void> {
  saving.value = true
  error.value = ''
  try {
    if (editingId.value === null) {
      await createSqlExample(props.domainId, form.value)
    } else {
      await updateSqlExample(editingId.value, form.value)
    }
    showForm.value = false
    await load()
  } catch (e: unknown) {
    // 服务端会在这里拒绝写操作 SQL（DELETE/TRUNCATE 之类）——它的提示就是要看的东西
    error.value = errorMessage(e)
  } finally {
    saving.value = false
  }
}

async function remove(e: SqlExample): Promise<void> {
  if (!window.confirm(`删除范例「${e.question}」？`)) return
  try {
    await deleteSqlExample(e.id)
    await load()
  } catch (err: unknown) {
    error.value = errorMessage(err)
  }
}
</script>

<template>
  <section>
    <div class="section-head">
      <h2>SQL 范例</h2>
      <button class="btn btn-primary btn-sm" type="button" @click="startCreate">新增范例</button>
    </div>

    <p class="field-note">
      直连模式下模型自己写 SQL，这几条范例会作为「相似问题的正确写法」进它的提示。
      <strong>只放确认过正确的查询</strong>——执行成功不等于语义正确，一条错的范例会被
      相似问题反复照抄。没有"从审计自动导入"，是刻意的。
    </p>

    <p v-if="notice" class="note">{{ notice }}</p>
    <p v-if="error" class="note note-danger" role="alert">{{ error }}</p>

    <div v-if="showForm" class="card">
      <div class="field">
        <label class="label" for="se-q">示例问法</label>
        <input id="se-q" v-model="form.question" class="input" placeholder="如：保费按档位分布，各档几张" />
      </div>
      <div class="field">
        <label class="label" for="se-sql">正确 SQL</label>
        <textarea id="se-sql" v-model="form.sql" class="input mono" rows="6" placeholder="SELECT …" />
        <p class="field-note">只接受单条 SELECT / WITH。写操作会被服务端拒绝。</p>
      </div>
      <div class="field">
        <label class="label" for="se-note">口径要点</label>
        <input id="se-note" v-model="form.note" class="input" placeholder="如：分档边界是 10000 与 1000" />
      </div>
      <div class="field">
        <label class="label" for="se-kw">检索关键词</label>
        <input id="se-kw" v-model="form.keywords" class="input" placeholder="顿号分隔，如：分档、档位、大额" />
        <p class="field-note">
          问题里出现任一个词，这条范例就会被选中。中文里同一个概念可以字面毫不相干，
          关键词比自动匹配可靠——而且"为什么这条被选中"是看得见的。
        </p>
      </div>
      <label class="checkbox">
        <input v-model="form.enabled" type="checkbox" />
        <span>启用（关闭后不参与检索，但保留在列表里）</span>
      </label>

      <div class="actions">
        <button class="btn btn-primary btn-sm" type="button" :disabled="saving" @click="save">
          {{ saving ? '保存中…' : '保存' }}
        </button>
        <button class="btn btn-quiet btn-sm" type="button" @click="showForm = false">取消</button>
      </div>
    </div>

    <p v-if="loading" class="field-note">加载中…</p>
    <p v-else-if="examples.length === 0" class="field-note">还没有范例。加一条能明显改善同类问题的写法。</p>

    <div v-else class="table-card">
      <table class="data">
        <thead>
          <tr>
            <th>示例问法</th>
            <th>关键词</th>
            <th>启用</th>
            <th />
          </tr>
        </thead>
        <tbody>
          <tr v-for="e in examples" :key="e.id">
            <td>{{ e.question }}</td>
            <td class="sub">{{ e.keywords || '—' }}</td>
            <td>{{ e.enabled ? '是' : '否' }}</td>
            <td class="row-actions">
              <button class="btn btn-quiet btn-sm" type="button" @click="startEdit(e)">编辑</button>
              <button class="btn btn-danger btn-sm" type="button" @click="remove(e)">删除</button>
            </td>
          </tr>
        </tbody>
      </table>
    </div>
  </section>
</template>

<style scoped>
.section-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--space-3);
}

.section-head h2 {
  font-size: 15px;
  font-weight: 700;
}

.card {
  display: flex;
  flex-direction: column;
  gap: var(--space-3);
  margin: var(--space-3) 0;
  box-shadow: var(--shadow-sm);
  border: none;
}

.actions {
  display: flex;
  gap: var(--space-2);
  margin-top: var(--space-1);
}

.table-card {
  margin-top: var(--space-3);
  background: var(--surface);
  border-radius: var(--radius);
  box-shadow: var(--shadow-sm);
  overflow: hidden;
}

.row-actions {
  display: flex;
  gap: 6px;
  justify-content: flex-end;
  white-space: nowrap;
}

.checkbox {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  font-size: 12px;
  color: var(--ink-soft);
  cursor: pointer;
}

.checkbox input {
  accent-color: var(--accent);
}
</style>
