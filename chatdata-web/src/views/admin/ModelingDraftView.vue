<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import {
  createDomain,
  createTable,
  generateModelingDraft,
  listDatasourceTables,
  listDatasources,
  saveJoins,
  type Datasource,
  type DomainForm,
  type DraftColumn,
  type DraftTable,
  type ModelingDraft,
  type TableMeta,
} from '../../api/admin'
import { errorMessage } from '../../api/http'

/**
 * 建模助手：从数据源生成域草稿。
 *
 * ## 为什么这一页存在
 *
 * 评测证明建模覆盖度是准确率最大的杠杆（保险域补三条聚合登记就从 74.2%
 * 升到 83.3%，代码一行未改），但手工建模极贵——AdventureWorks 28 表花了
 * 大半天。这一页把那部分机械劳动压掉，管理员只做业务判断。
 *
 * ## 为什么不自动落库
 *
 * 推断必然有错。**推断错的东西直接生效是这个项目最怕的那种失败**：
 * 不报错、只是数字不对。所以草稿里每一条都带理由和置信度，人看过、
 * 改过、点了「确认」才写进语义域。
 */
// 刻意不用 useRouter：成功后**不自动跳转**，见 createdDomainId 的注释。
// 去向交给用户点——建完那一刻他才知道自己下一步要看什么。

const datasources = ref<Datasource[]>([])
const selectedDs = ref<number | null>(null)
const availableTables = ref<TableMeta[]>([])
const selectedTables = ref<string[]>([])

/**
 * 表清单的筛选与搜索。
 *
 * ## 为什么必须有
 *
 * 实测数据源有 500+ 张表。一个没有搜索、没有分页的平铺列表在那种规模下
 * 根本没法用——滚到第 300 行已经忘了自己在找什么。
 *
 * ## 为什么默认只显示前 N 条
 *
 * 一次渲染 500 个复选框会明显卡顿，而用户第一眼只需要"大概有哪些"。
 * 搜索 / 翻页把范围收窄到能一次看完的数量。
 */
const tableKeyword = ref('')
/**
 * 当前选中的库。
 *
 * 多库实例里，500 张表通常分散在几个库里。不按库收窄的话，
 * 搜索和翻页都还是在全局范围里找——先把范围缩到"我现在要建模的那个库"
 * 才是主要路径，搜索是它的补充。
 */
const tableSchema = ref('')

/** 全部可选库，按字母序。 */
const schemas = computed(() => {
  const set = new Set<string>()
  for (const t of availableTables.value) {
    if (t.schema?.trim()) set.add(t.schema)
  }
  return Array.from(set).sort()
})
/** 只看带注释的表——没注释的通常是中间表或临时表 */
const onlyDocumented = ref(false)
const tablePage = ref(0)
const TABLE_PAGE_SIZE = 60

/** 按关键字与筛选条件过滤后的表。 */
const filteredTables = computed(() => {
  const kw = tableKeyword.value.trim().toLowerCase()
  return availableTables.value.filter((t) => {
    if (tableSchema.value && t.schema !== tableSchema.value) return false
    if (onlyDocumented.value && !t.comment?.trim()) return false
    if (!kw) return true
    // 同时搜表名和注释：用户往往记得"那张订单表"而不是它的英文名
    return t.name.toLowerCase().includes(kw)
      || (t.comment ?? '').toLowerCase().includes(kw)
      || (t.schema ?? '').toLowerCase().includes(kw)
  })
})

const tablePageCount = computed(
  () => Math.max(1, Math.ceil(filteredTables.value.length / TABLE_PAGE_SIZE)),
)

/** 当前页要渲染的表。 */
const visibleTables = computed(() => {
  const start = tablePage.value * TABLE_PAGE_SIZE
  return filteredTables.value.slice(start, start + TABLE_PAGE_SIZE)
})

/** 搜索条件变了要回到第一页，否则会停在一个空页上。 */
function onFilterChange(): void {
  tablePage.value = 0
}

function prevPage(): void {
  if (tablePage.value > 0) tablePage.value--
}

function nextPage(): void {
  if (tablePage.value < tablePageCount.value - 1) tablePage.value++
}

/**
 * 选中项的键：带库名。
 *
 * <p>多库实例里两个库都可能有 `orders`，只按表名做键会让勾一个把另一个也勾上——
 * 而错的表现是"建模建到了别的库的表"，不报错。
 */
function tableKey(t: TableMeta): string {
  return t.schema ? `${t.schema}.${t.name}` : t.name
}

/** 当前筛选结果是否全被选中（"全选"作用在筛选结果上，不是全部表）。 */
const allFilteredSelected = computed(
  () => filteredTables.value.length > 0
    && filteredTables.value.every((t) => selectedTables.value.includes(tableKey(t))),
)

/**
 * 全选/取消全选**当前筛选结果**。
 *
 * 刻意不是"全部表"：500 张表时一键全选几乎肯定是误操作，
 * 而用户搜索之后想要的是"这些结果全要"。
 */
function toggleAllFiltered(): void {
  const names = filteredTables.value.map((t) => tableKey(t))
  if (allFilteredSelected.value) {
    selectedTables.value = selectedTables.value.filter((n) => !names.includes(n))
  } else {
    selectedTables.value = Array.from(new Set([...selectedTables.value, ...names]))
  }
}

function selectTable(name: string, on: boolean): void {
  if (on) {
    if (!selectedTables.value.includes(name)) selectedTables.value = [...selectedTables.value, name]
  } else {
    selectedTables.value = selectedTables.value.filter((n) => n !== name)
  }
}

function clearSelection(): void {
  selectedTables.value = []
}

const draft = ref<ModelingDraft | null>(null)
const loading = ref(false)
const generating = ref(false)
const error = ref('')
const message = ref('')

/** 确认落库时填的域信息 */
const domainName = ref('')
const domainMode = ref<'MODELED' | 'RAW'>('MODELED')

/** 编辑中的业务名。键 = `表名.列名`，用户改过才写回 draft */
const aliasEdits = ref<Record<string, string>>({})

/**
 * 刚创建成功的域 id。
 *
 * <p>成功后**不自动跳转**，而是给两个明确去向：「查看还缺什么」和「回列表」。
 *
 * <p>自动跳走的话，管理员会被弹到一个与刚才操作无关的页面；
 * 而建完那一刻他脑子里还有业务场景（"哦对，我还需要能问平均客单价"），
 * 正是该看覆盖度的时候。过一小时再看，他要先回忆自己当初想配什么。
 */
const createdDomainId = ref<number | null>(null)

onMounted(load)

async function load(): Promise<void> {
  try {
    datasources.value = await listDatasources()
    const first = datasources.value[0]
    if (first) {
      selectedDs.value = first.id
      await loadTables()
    }
  } catch (e) {
    error.value = errorMessage(e)
  }
}

async function loadTables(): Promise<void> {
  if (selectedDs.value == null) return
  availableTables.value = []
  selectedTables.value = []
  draft.value = null
  tableKeyword.value = ''
  tableSchema.value = ''
  onlyDocumented.value = false
  tablePage.value = 0
  try {
    availableTables.value = await listDatasourceTables(selectedDs.value)
  } catch (e) {
    error.value = `读取表清单失败：${errorMessage(e)}`
  }
}

async function generate(): Promise<void> {
  if (selectedDs.value == null) return
  generating.value = true
  error.value = ''
  message.value = ''
  try {
    // 后端只认表名；多库同名表的情况暂时按"全都要"处理，
    // 草稿里会按库分别列出（TableDraft 带 schema）
    const names = selectedTables.value.map((k) => k.includes('.') ? k.slice(k.lastIndexOf('.') + 1) : k)
    draft.value = await generateModelingDraft(
      selectedDs.value,
      names.length ? names : undefined,
    )
    aliasEdits.value = {}
    createdDomainId.value = null
    const ds = datasources.value.find((d) => d.id === selectedDs.value)
    if (!domainName.value && ds) domainName.value = `${ds.name}分析域`
  } catch (e) {
    error.value = `生成草稿失败：${errorMessage(e)}`
  } finally {
    generating.value = false
  }
}

/** 某列的当前业务名（用户改过就用改的）。 */
function aliasOf(table: DraftTable, col: DraftColumn): string {
  return aliasEdits.value[`${table.name}.${col.col}`] ?? col.alias
}

function setAlias(table: DraftTable, col: DraftColumn, value: string): void {
  aliasEdits.value = { ...aliasEdits.value, [`${table.name}.${col.col}`]: value }
}

/**
 * 业务名是否还是机器生成的。
 *
 * 改过就算确认——用户看过并认可了这个名字，那是我们要的。
 */
function aliasConfirmed(table: DraftTable, col: DraftColumn): boolean {
  const edited = aliasEdits.value[`${table.name}.${col.col}`]
  return col.aliasConfirmed || (edited != null && edited.trim() !== '')
}

/** 还没确认的业务名数量。落库前要拦住。 */
const unconfirmedCount = computed(() => {
  if (!draft.value) return 0
  let n = 0
  for (const t of draft.value.tables) {
    for (const c of t.columns) {
      if (c.enabled && !aliasConfirmed(t, c)) n++
    }
  }
  return n
})

const enabledColumnCount = computed(() => {
  if (!draft.value) return 0
  return draft.value.tables.reduce(
    (n, t) => n + t.columns.filter((c) => c.enabled).length,
    0,
  )
})

/**
 * 落库。
 *
 * 复用现有的 createDomain / createTable / saveJoins——**不新增写接口**。
 * 草稿路径若自带一套写入逻辑，早晚与手工路径分叉。
 */
async function confirmAndSave(): Promise<void> {
  if (!draft.value) return
  if (unconfirmedCount.value > 0) {
    error.value = `还有 ${unconfirmedCount.value} 个业务名没有确认。业务名不确认就上线，`
      + '用户会照着一个不准确的名字提问。'
    return
  }
  loading.value = true
  error.value = ''
  message.value = ''
  try {
    const domainId = await createDomain({
      name: domainName.value.trim(),
      description: '',
      datasourceId: selectedDs.value!,
      status: 'ENABLED',
      mode: domainMode.value,
    } as DomainForm)

    for (const table of draft.value.tables) {
      await createTable(domainId, {
        name: table.name,
        label: table.label,
        mode: 'TABLE',
        sqlText: null,
        columns: table.columns.map((c) => ({
          col: c.col,
          type: c.type,
          agg: c.agg[0] ?? '',
          alias: aliasOf(table, c),
          enabled: c.enabled,
        })),
        enabled: true,
        sortNo: 0,
      } as Parameters<typeof createTable>[1])
    }

    if (draft.value.joins.length > 0) {
      await saveJoins(domainId, JSON.stringify(draft.value.joins.map((j) => ({
        left: j.left,
        leftCol: j.leftColumn,
        right: j.right,
        rightCol: j.rightColumn,
        type: 'LEFT JOIN',
      }))))
    }

    message.value = `已创建域「${domainName.value}」（${draft.value.tables.length} 张表）`
    createdDomainId.value = domainId
  } catch (e) {
    error.value = `保存失败：${errorMessage(e)}`
  } finally {
    loading.value = false
  }
}

function confidenceClass(c: DraftColumn['confidence']): string {
  return c === 'HIGH' ? 'conf-high' : c === 'MEDIUM' ? 'conf-mid' : 'conf-low'
}

function aggText(c: DraftColumn): string {
  return c.agg.length ? c.agg.join(' + ') : '—'
}
</script>

<template>
  <section class="modeling">
    <header class="page-head">
      <div>
        <h1>建模助手</h1>
        <p class="sub">
          选几张表，自动推断列角色、聚合方式与表间关联，生成一份<strong>可编辑的域草稿</strong>。
          确认后才写进语义域——推断错的东西直接生效，是这个项目最怕的那种失败。
        </p>
      </div>
    </header>

    <!-- 第一步：选数据源与表 -->
    <div class="panel">
      <div class="row">
        <label class="field">
          <span>数据源</span>
          <select v-model.number="selectedDs" @change="loadTables">
            <option v-for="d in datasources" :key="d.id" :value="d.id">
              {{ d.name }}（{{ d.dbType }}）
            </option>
          </select>
        </label>
        <label class="field">
          <span>域名称</span>
          <input v-model="domainName" placeholder="如：销售分析域" />
        </label>
        <label class="field">
          <span>模式</span>
          <select v-model="domainMode">
            <option value="MODELED">建模（推荐）</option>
            <option value="RAW">直连</option>
          </select>
        </label>
      </div>

      <div class="tables">
        <!-- 搜索 + 筛选。500+ 张表时这是主要入口，不是可选装饰 -->
        <div class="table-toolbar">
          <select
            v-if="schemas.length > 0"
            v-model="tableSchema"
            class="schema-select"
            @change="onFilterChange"
          >
            <option value="">全部库（{{ schemas.length }}）</option>
            <option v-for="s in schemas" :key="s" :value="s">{{ s }}</option>
          </select>
          <input
            v-model="tableKeyword"
            class="search"
            type="search"
            placeholder="搜索表名 / 注释 / 库名…"
            @input="onFilterChange"
          />
          <label class="check">
            <input v-model="onlyDocumented" type="checkbox" @change="onFilterChange" />
            <span>只看有注释的</span>
          </label>
          <span class="count">
            {{ filteredTables.length }} / {{ availableTables.length }} 张
          </span>
        </div>

        <div class="tables-head">
          <label class="check">
            <input
              type="checkbox"
              :checked="allFilteredSelected"
              :indeterminate="selectedTables.length > 0 && !allFilteredSelected"
              @change="toggleAllFiltered"
            />
            <span>{{ tableKeyword || onlyDocumented ? '全选筛选结果' : '全选当前页' }}</span>
          </label>
          <span v-if="selectedTables.length" class="selected-count">
            已选 {{ selectedTables.length }} 张
            <button class="link" @click="clearSelection">清空</button>
          </span>
          <span v-else class="hint">不选则用数据源里的全部表</span>
        </div>

        <div class="table-grid">
          <label
            v-for="t in visibleTables"
            :key="`${t.schema ?? ''}.${t.name}`"
            class="table-item"
            :title="`${t.schema ? t.schema + '.' : ''}${t.name}${t.comment ? ' — ' + t.comment : ''}`"
          >
            <input
              type="checkbox"
              :checked="selectedTables.includes(tableKey(t))"
              @change="selectTable(tableKey(t), ($event.target as HTMLInputElement).checked)"
            />
            <span class="text">
              <span class="line1">
                <span v-if="t.schema" class="schema-badge">{{ t.schema }}</span>
                <span class="name">{{ t.name }}</span>
              </span>
              <span v-if="t.comment" class="comment">{{ t.comment }}</span>
              <span v-else class="no-comment">无注释</span>
            </span>
          </label>
        </div>

        <p v-if="visibleTables.length === 0" class="empty">
          没有匹配的表。试试换个关键字，或取消「只看有注释的」。
        </p>

        <div v-if="tablePageCount > 1" class="pager">
          <button :disabled="tablePage === 0" @click="prevPage">上一页</button>
          <span>{{ tablePage + 1 }} / {{ tablePageCount }}</span>
          <button :disabled="tablePage >= tablePageCount - 1" @click="nextPage">下一页</button>
        </div>
      </div>

      <div class="actions">
        <button class="primary" :disabled="generating || selectedDs == null" @click="generate">
          {{ generating ? '推断中…' : '生成草稿' }}
        </button>
      </div>
    </div>

    <p v-if="error" class="error">{{ error }}</p>
    <div v-if="message" class="ok">
      <span>{{ message }}</span>
      <template v-if="createdDomainId != null">
        <RouterLink
          class="ok-link primary"
          :to="`/admin/domains/${createdDomainId}/tables`"
        >
          查看还缺什么 →
        </RouterLink>
        <RouterLink class="ok-link" to="/admin/domains">回语义域列表</RouterLink>
      </template>
    </div>

    <!-- 第二步：草稿 -->
    <template v-if="draft">
      <div class="stats">
        <div class="stat"><b>{{ draft.stats.tables }}</b><span>张表</span></div>
        <div class="stat"><b>{{ draft.stats.columns }}</b><span>个列</span></div>
        <div class="stat"><b>{{ draft.stats.joins }}</b><span>条关联</span></div>
        <div class="stat"><b>{{ draft.stats.elapsedMs }}ms</b><span>耗时</span></div>
        <div class="stat accent"><b>{{ enabledColumnCount }}</b><span>启用列</span></div>
      </div>

      <div v-if="draft.warnings.length" class="warnings">
        <strong>需要人工确认</strong>
        <ul>
          <li v-for="w in draft.warnings" :key="w">{{ w }}</li>
        </ul>
      </div>

      <div v-for="table in draft.tables" :key="table.name" class="panel table-panel">
        <h2>
          {{ table.name }}
          <span v-if="table.label && table.label !== table.name" class="label">{{ table.label }}</span>
        </h2>
        <table class="draft-table">
          <thead>
            <tr>
              <th class="w-on">启用</th>
              <th>列</th>
              <th>类型</th>
              <th>建议聚合</th>
              <th>业务名（可改）</th>
              <th>置信度</th>
              <th class="reason-col">理由</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="col in table.columns" :key="col.col" :class="{ off: !col.enabled }">
              <td class="w-on">
                <input v-model="col.enabled" type="checkbox" />
              </td>
              <td class="mono">{{ col.col }}</td>
              <td class="mono dim">{{ col.type }}</td>
              <td>
                <span v-if="col.agg.length" class="agg">{{ aggText(col) }}</span>
                <span v-else class="dim">—</span>
              </td>
              <td>
                <input
                  class="alias"
                  :class="{ need: !aliasConfirmed(table, col) }"
                  :value="aliasOf(table, col)"
                  @input="setAlias(table, col, ($event.target as HTMLInputElement).value)"
                />
              </td>
              <td>
                <span class="conf" :class="confidenceClass(col.confidence)">
                  {{ col.confidence }}
                </span>
              </td>
              <td class="reason-col reason">{{ col.reason }}</td>
            </tr>
          </tbody>
        </table>
      </div>

      <div v-if="draft.joins.length" class="panel">
        <h2>推断出的表间关联</h2>
        <table class="draft-table">
          <thead>
            <tr>
              <th>左表.列</th>
              <th>右表.列</th>
              <th>置信度</th>
              <th class="reason-col">理由</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="j in draft.joins" :key="`${j.left}.${j.leftColumn}-${j.right}`">
              <td class="mono">{{ j.left }}.{{ j.leftColumn }}</td>
              <td class="mono">{{ j.right }}.{{ j.rightColumn }}</td>
              <td>
                <span class="conf" :class="j.confidence === 'FOREIGN_KEY' ? 'conf-high' : 'conf-mid'">
                  {{ j.confidence === 'FOREIGN_KEY' ? '外键' : '按命名' }}
                </span>
              </td>
              <td class="reason-col reason">{{ j.reason }}</td>
            </tr>
          </tbody>
        </table>
        <p class="hint">
          按命名推断的关联<strong>需要确认</strong>。挑错会 JOIN 到错的表——
          SQL 合法、行数看着也正常，只是数字不对。
        </p>
      </div>

      <div class="footer-bar">
        <span v-if="unconfirmedCount > 0" class="warn-inline">
          还有 {{ unconfirmedCount }} 个业务名未确认
        </span>
        <span v-else class="ok-inline">所有业务名已确认</span>
        <button class="primary" :disabled="loading" @click="confirmAndSave">
          {{ loading ? '保存中…' : '确认并创建语义域' }}
        </button>
      </div>
    </template>
  </section>
</template>

<style scoped>
.modeling { padding: 24px; max-width: 1400px; }
.page-head h1 { font-size: 1.35rem; margin: 0 0 6px; }
.sub { color: #64748b; font-size: 0.85rem; margin: 0 0 18px; max-width: 78ch; line-height: 1.6; }
.panel { background: #fff; border: 1px solid #e2e8f0; border-radius: 12px; padding: 16px; margin-bottom: 16px; }
.row { display: flex; gap: 16px; flex-wrap: wrap; margin-bottom: 16px; }
.field { display: flex; flex-direction: column; gap: 4px; min-width: 220px; }
.field span { font-size: 0.78rem; color: #475569; font-weight: 500; }
.field select, .field input { padding: 8px 10px; border: 1px solid #cbd5e1; border-radius: 8px; font-size: 0.85rem; }
.tables-head { display: flex; align-items: center; gap: 12px; margin-bottom: 10px; }
.check { display: flex; align-items: center; gap: 6px; font-size: 0.85rem; font-weight: 500; cursor: pointer; }
.hint { color: #94a3b8; font-size: 0.78rem; }
.table-toolbar { display: flex; align-items: center; gap: 14px; margin-bottom: 10px; flex-wrap: wrap; }
.search { flex: 1; min-width: 240px; max-width: 380px; padding: 8px 12px; border: 1px solid #cbd5e1; border-radius: 9px; font-size: 0.86rem; }
.search:focus { outline: none; border-color: #6366f1; box-shadow: 0 0 0 3px #6366f118; }
.count { color: #64748b; font-size: 0.8rem; margin-left: auto; font-variant-numeric: tabular-nums; }
.selected-count { color: #4f46e5; font-size: 0.8rem; display: flex; align-items: center; gap: 8px; }
.link { background: none; border: none; color: #6366f1; cursor: pointer; font-size: 0.8rem; padding: 0; text-decoration: underline; }
.no-comment { color: #cbd5e1; font-size: 0.72rem; font-style: italic; }
.empty { color: #94a3b8; font-size: 0.83rem; text-align: center; padding: 24px 0; margin: 0; }
.pager { display: flex; align-items: center; justify-content: center; gap: 12px; margin-top: 10px; font-size: 0.8rem; color: #64748b; }
.pager button { padding: 4px 12px; border: 1px solid #e2e8f0; background: #fff; border-radius: 7px; cursor: pointer; font-size: 0.8rem; }
.pager button:disabled { opacity: 0.4; cursor: not-allowed; }
.table-grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(230px, 1fr)); gap: 6px; max-height: 320px; overflow-y: auto; }
.schema-select { padding: 8px 10px; border: 1px solid #cbd5e1; border-radius: 9px; font-size: 0.84rem; max-width: 200px; }

/*
 * 表项：两行布局。
 *
 * 原来是一行 flex，长表名会把注释挤出去、甚至和相邻项重叠——
 * 实测长表名（如 fact_order_detail_daily）会盖到下一个复选框上。
 * 改成 min-width:0 + 单行省略后，再长的名字也只占自己那一格。
 */
.table-item {
  display: flex; align-items: flex-start; gap: 7px;
  padding: 6px 8px; border-radius: 7px; font-size: 0.82rem; cursor: pointer;
  min-width: 0; overflow: hidden;
}
.table-item:hover { background: #f8fafc; }
.table-item input { margin-top: 2px; flex-shrink: 0; }
.table-item .text { display: flex; flex-direction: column; gap: 1px; min-width: 0; flex: 1; }
.table-item .line1 { display: flex; align-items: center; gap: 5px; min-width: 0; }
.table-item .schema-badge {
  flex-shrink: 0; font-size: 0.68rem; padding: 0 5px; border-radius: 4px;
  background: #eef2ff; color: #4338ca; max-width: 96px;
  overflow: hidden; text-overflow: ellipsis; white-space: nowrap;
}
.table-item .name {
  font-family: ui-monospace, monospace; min-width: 0;
  overflow: hidden; text-overflow: ellipsis; white-space: nowrap;
}
.table-item .comment {
  color: #94a3b8; font-size: 0.73rem; min-width: 0;
  overflow: hidden; text-overflow: ellipsis; white-space: nowrap;
}
.actions { margin-top: 14px; }
button.primary { background: #0f172a; color: #fff; border: none; border-radius: 9px; padding: 9px 18px; font-size: 0.86rem; cursor: pointer; }
button.primary:disabled { opacity: 0.45; cursor: not-allowed; }
.error { color: #b91c1c; background: #fef2f2; border: 1px solid #fecaca; border-radius: 9px; padding: 10px 14px; font-size: 0.84rem; }
.ok {
  display: flex; align-items: center; gap: 14px; flex-wrap: wrap;
  color: #047857; background: #ecfdf5; border: 1px solid #a7f3d0;
  border-radius: 9px; padding: 10px 14px; font-size: 0.84rem;
}
.ok-link { color: #047857; text-decoration: underline; font-size: 0.83rem; }
.ok-link.primary { font-weight: 600; }
.stats { display: flex; gap: 10px; margin-bottom: 16px; flex-wrap: wrap; }
.stat { background: #fff; border: 1px solid #e2e8f0; border-radius: 10px; padding: 10px 18px; min-width: 92px; }
.stat b { display: block; font-size: 1.3rem; }
.stat span { color: #64748b; font-size: 0.76rem; }
.stat.accent b { color: #4f46e5; }
.warnings { background: #fffbeb; border: 1px solid #fde68a; border-radius: 10px; padding: 12px 16px; margin-bottom: 16px; font-size: 0.83rem; }
.warnings ul { margin: 6px 0 0; padding-left: 18px; }
.warnings li { margin: 3px 0; }
.table-panel h2 { font-size: 0.98rem; margin: 0 0 10px; font-family: ui-monospace, monospace; }
.table-panel h2 .label { font-family: inherit; color: #64748b; font-weight: 400; font-size: 0.82rem; margin-left: 8px; }
.draft-table { width: 100%; border-collapse: collapse; font-size: 0.82rem; }
.draft-table th { text-align: left; padding: 7px 8px; background: #f8fafc; color: #475569; font-weight: 600; font-size: 0.78rem; }
.draft-table td { padding: 6px 8px; border-top: 1px solid #f1f5f9; vertical-align: middle; }
.draft-table tr.off { opacity: 0.45; }
.w-on { width: 42px; text-align: center; }
.mono { font-family: ui-monospace, monospace; }
.dim { color: #94a3b8; }
.agg { background: #eef2ff; color: #3730a3; border-radius: 5px; padding: 1px 7px; font-size: 0.74rem; font-weight: 600; }
.alias { width: 100%; min-width: 130px; padding: 4px 8px; border: 1px solid #e2e8f0; border-radius: 6px; font-size: 0.82rem; }
.alias:focus { outline: none; border-color: #6366f1; }
.alias.need { border-color: #fbbf24; background: #fffdf5; }
.conf { font-size: 0.72rem; font-weight: 600; border-radius: 5px; padding: 1px 6px; }
.conf-high { color: #047857; background: #d1fae5; }
.conf-mid { color: #b45309; background: #fef3c7; }
.conf-low { color: #b91c1c; background: #fee2e2; }
.reason-col { width: 34%; }
.reason { color: #64748b; font-size: 0.76rem; line-height: 1.45; }
.footer-bar { position: sticky; bottom: 0; display: flex; align-items: center; justify-content: flex-end; gap: 16px; padding: 12px 16px; background: #fffffff2; backdrop-filter: blur(4px); border-top: 1px solid #e2e8f0; }
.warn-inline { color: #b45309; font-size: 0.83rem; }
.ok-inline { color: #047857; font-size: 0.83rem; }
</style>
