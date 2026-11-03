<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { modelingCheck, type ConfigViolation } from '../../api/admin'
import { errorMessage } from '../../api/http'

/**
 * 域覆盖度：这个域**配得够不够用**。
 *
 * ## 与启动校验的分工
 *
 * 启动校验管"能不能用"——域加载失败、没有授权、关联引用不存在的表，
 * 那些是 ERROR，会拒绝启动。
 *
 * 这一页管"好不好用"——缺 AVG、缺去重计数、缺时间口径。这些**不报错**，
 * 域照常能用，只是某些问法会被模型硬凑成形态不对的答案。
 * 而按项目的实测，这类缺口正是准确率的最大来源：
 * 保险域只补三条聚合登记，通过率就从 74.2% 升到 83.3%，代码一行未改。
 *
 * ## 为什么不提供"一键修复"
 *
 * 补哪条 AVG、给哪列加去重计数是**业务判断**。自动补会造出
 * 「把 AVG 加到 ID 列上」这种错误，而那比没配更糟——它看起来配好了。
 */
const props = defineProps<{ domainId: number; datasourceId: number | null; mode: string }>()

const violations = ref<ConfigViolation[]>([])
const loading = ref(false)
const error = ref('')

/** 直连模式不做建模检查——后端 isRaw() 直接返回空，前端不必发请求。 */
const isRaw = computed(() => props.mode === 'RAW')

const errors = computed(() =>
  violations.value.filter((v) => severityOf(v) === 'ERROR'))

const warnings = computed(() =>
  violations.value.filter((v) => severityOf(v) === 'WARNING'))

/**
 * 严重度。
 *
 * 后端目前没在返回值里带 severity（它是 Category 的属性），
 * 而前端要分组显示。这里按类别名判断——**与后端 Category 的枚举名一致**。
 *
 * 之所以不另外映射一张表：类别改名时两边都要改，那正是会漂移的地方。
 * 真正的修法是让接口返回 severity，这一期先用约定。
 */
function severityOf(v: ConfigViolation): ViolationSeverityLocal {
  return ERROR_CATEGORIES.has(v.category) ? 'ERROR' : 'WARNING'
}
type ViolationSeverityLocal = 'ERROR' | 'WARNING'

/**
 * 这些类别是 ERROR（域不可用或数据会被过度暴露）。
 *
 * 与后端 `EngineConfigViolation.Category` 里标了 {@code Severity.ERROR} 的一致。
 */
const ERROR_CATEGORIES = new Set([
  'DOMAIN_NOT_LOADABLE',
  'NO_GRANT',
  'DATASOURCE_UNUSABLE',
  'NO_VISIBLE_TABLE',
  'BROKEN_JOIN_REF',
  'NO_FIELD',
  'DANGLING_RULE_ROLE',
  'RAW_DOMAIN_WITH_RULES',
  'BROKEN_DERIVED_METRIC',
  'BROKEN_METRIC_CONDITION',
])

/** 类别的中文名。后端没返回，前端按枚举名映射。 */
const CATEGORY_LABELS: Record<string, string> = {
  DOMAIN_NOT_LOADABLE: '域加载失败',
  NO_GRANT: '未设置访问授权',
  DATASOURCE_UNUSABLE: '数据源不可用',
  NO_VISIBLE_TABLE: '没有可用的表',
  BROKEN_JOIN_REF: '表关联引用无效',
  NO_FIELD: '没有可用字段',
  DANGLING_GRANT_ROLE: '授权里的角色不存在',
  DANGLING_RULE_ROLE: '行列规则里的角色不存在',
  ASSISTANT_DEAD_END: '助理无法真正使用',
  RAW_DOMAIN_WITH_RULES: '直连模式配了行/列规则',
  BROKEN_DERIVED_METRIC: '派生指标引用无效',
  BROKEN_METRIC_CONDITION: '指标统计条件无效',
  THIN_MODELING: '建模覆盖度不足',
}

function categoryLabel(category: string): string {
  return CATEGORY_LABELS[category] ?? category
}

onMounted(load)
watch(() => props.domainId, load)

async function load(): Promise<void> {
  if (isRaw.value) {
    violations.value = []
    return
  }
  loading.value = true
  error.value = ''
  try {
    violations.value = await modelingCheck(props.domainId)
  } catch (e) {
    error.value = `读取覆盖度失败：${errorMessage(e)}`
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <div class="coverage">
    <p v-if="isRaw" class="raw-note">
      直连模式由模型自己写 SQL，不做建模检查。
      这条路径只做表级授权——行级/列级规则即使配了也不生效。
    </p>

    <template v-else>
      <div v-if="loading" class="loading">检查中…</div>
      <p v-else-if="error" class="error">{{ error }}</p>

      <div v-else-if="violations.length === 0" class="clean">
        <span class="icon">✓</span>
        <div>
          <strong>没有发现明显短板</strong>
          <p>这个域的建模覆盖度看起来是完整的。评测里最常见的几类缺口都没有出现。</p>
        </div>
      </div>

      <template v-else>
        <div class="summary">
          <span v-if="errors.length" class="pill danger">{{ errors.length }} 个严重问题</span>
          <span v-if="warnings.length" class="pill warn">{{ warnings.length }} 条改进建议</span>
        </div>

        <section v-if="errors.length" class="group">
          <h3 class="group-title danger">严重 · 会影响正确性或数据安全</h3>
          <article
            v-for="(v, i) in errors"
            :key="`e-${i}`"
            class="item danger"
          >
            <div class="item-head">
              <span class="badge">{{ categoryLabel(v.category) }}</span>
            </div>
            <p class="msg">{{ v.message }}</p>
            <p v-if="v.location" class="loc">
              <span class="loc-target">{{ v.location.table }}<template v-if="v.location.column">.{{ v.location.column }}</template></span>
              <span class="loc-hint">{{ v.location.hint }}</span>
            </p>
          </article>
        </section>

        <section v-if="warnings.length" class="group">
          <h3 class="group-title warn">建议 · 不影响能用，但会影响好用</h3>
          <article
            v-for="(v, i) in warnings"
            :key="`w-${i}`"
            class="item warn"
          >
            <div class="item-head">
              <span class="badge">{{ categoryLabel(v.category) }}</span>
            </div>
            <p class="msg">{{ v.message }}</p>
            <p v-if="v.location" class="loc">
              <span class="loc-target">{{ v.location.table }}<template v-if="v.location.column">.{{ v.location.column }}</template></span>
              <span class="loc-hint">{{ v.location.hint }}</span>
            </p>
          </article>
        </section>

        <p class="footnote">
          这些检查只看配置，不查询数据。改完之后回到「注册表」补充对应列即可，
          不需要重启服务。
        </p>
      </template>
    </template>
  </div>
</template>

<style scoped>
.coverage { padding: 4px 0; }
.loading { color: #64748b; font-size: 0.86rem; padding: 20px 0; }
.error { color: #b91c1c; background: #fef2f2; border: 1px solid #fecaca; border-radius: 9px; padding: 10px 14px; font-size: 0.84rem; }
.raw-note { color: #64748b; background: #f8fafc; border: 1px solid #e2e8f0; border-radius: 9px; padding: 12px 16px; font-size: 0.85rem; line-height: 1.6; }

.clean { display: flex; align-items: flex-start; gap: 12px; background: #ecfdf5; border: 1px solid #a7f3d0; border-radius: 10px; padding: 16px 18px; }
.clean .icon { color: #047857; font-size: 1.3rem; font-weight: 700; line-height: 1; }
.clean strong { color: #065f46; font-size: 0.92rem; }
.clean p { color: #047857; font-size: 0.83rem; margin: 4px 0 0; line-height: 1.55; }

.summary { display: flex; gap: 8px; margin-bottom: 16px; }
.pill { font-size: 0.78rem; font-weight: 600; border-radius: 999px; padding: 3px 12px; }
.pill.danger { background: #fee2e2; color: #b91c1c; }
.pill.warn { background: #fef3c7; color: #b45309; }

.group { margin-bottom: 22px; }
.group-title { font-size: 0.86rem; margin: 0 0 10px; font-weight: 600; }
.group-title.danger { color: #b91c1c; }
.group-title.warn { color: #b45309; }

.item { border-radius: 10px; padding: 12px 16px; margin-bottom: 8px; border: 1px solid; }
.item.danger { background: #fef2f2; border-color: #fecaca; }
.item.warn { background: #fffbeb; border-color: #fde68a; }
.item-head { margin-bottom: 6px; }
.badge { font-size: 0.74rem; font-weight: 600; border-radius: 5px; padding: 2px 8px; background: #fff; color: #475569; border: 1px solid #e2e8f0; }
.msg { font-size: 0.85rem; color: #334155; margin: 0; line-height: 1.65; }

.loc { display: flex; align-items: center; gap: 8px; margin: 8px 0 0; font-size: 0.8rem; }
.loc-target { font-family: ui-monospace, monospace; background: #fff; border: 1px solid #e2e8f0; border-radius: 6px; padding: 2px 8px; color: #4338ca; }
.loc-hint { color: #64748b; }

.footnote { color: #94a3b8; font-size: 0.78rem; margin-top: 8px; line-height: 1.6; }
</style>
