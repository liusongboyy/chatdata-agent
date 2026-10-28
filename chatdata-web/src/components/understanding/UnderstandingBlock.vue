<script setup lang="ts">
import type { AskUnderstanding } from '../../api/ask'

/**
 * 「我理解成了」——把系统实际理解到的事实摊开给用户。
 *
 * <h2>它为什么必须常驻、且必须显示空槽</h2>
 *
 * 问数最危险的失败形态是**静默语义错误**：问题里的约束被丢掉，DSL 解析成功、
 * 无异常、审计里结构合法——监控看不到，只有对着业务数字才能发现。
 * 唯一能拦住它的位置就是这里：用户看到「筛选：无」而自己明明说了「华东」，
 * 立刻就知道这次答案不能信。
 *
 * 所以：
 * - **不折叠**。需要点一下才看到的东西，挡不住任何错误。
 * - **空槽也要画出来**。空槽若隐藏，"漏了条件"就正好变成看不见的那一项。
 * - 空槽**不着重色**。我们并不知道"筛选为空"是漏了还是本来就没有——
 *   一律标红会让人很快学会忽略这一块，那比不显示更糟。它只负责说实话。
 */
const props = defineProps<{ understanding: AskUnderstanding }>()

/** 运算符的展示写法。事实由后端给原文，翻译放在这里——口径变了不必改后端。 */
const OP_LABEL: Record<string, string> = {
  '=': '=',
  '!=': '≠',
  '<>': '≠',
  '>': '>',
  '<': '<',
  '>=': '≥',
  '<=': '≤',
  in: '属于',
  like: '包含',
}

function opLabel(op: string): string {
  return OP_LABEL[op] ?? OP_LABEL[op.toLowerCase()] ?? op
}

/** 同环比：说清对照期是什么，否则用户不知道"增长率"是在和谁比。 */
function compareText(label: string): string {
  return label === '同比' ? '同比（对比上一年同期）' : '环比（对比上一个月）'
}

/** 时间：口径名 + 具体日期。具体日期比口径名重要——口径歧义正是答错的常见来源。 */
function timeText(t: NonNullable<AskUnderstanding['time']>): string {
  const dates = t.from && t.to ? `${t.from} ~ ${t.to}` : ''
  if (t.kind && dates) return `${t.kind}（${dates}）`
  return t.kind || dates || '不限时间'
}
</script>

<template>
  <dl class="understanding" aria-label="系统对本次提问的理解">
    <div class="item">
      <dt>指标</dt>
      <dd v-if="props.understanding.metrics.length">
        {{ props.understanding.metrics.join('、') }}
      </dd>
      <dd v-else class="empty">无</dd>
    </div>

    <div class="item">
      <dt>维度</dt>
      <dd v-if="props.understanding.dimensions.length">
        {{ props.understanding.dimensions.join('、') }}
      </dd>
      <dd v-else class="empty">不分组</dd>
    </div>

    <div class="item">
      <dt>时间</dt>
      <!-- null 不是"没填"，是"这次查询没有任何时间限制"——全时段是个有后果的状态，
           必须说出来，不能留白 -->
      <dd v-if="props.understanding.time">
        {{ timeText(props.understanding.time) }}
      </dd>
      <dd v-else class="empty">不限时间（全时段）</dd>
    </div>

    <div class="item">
      <dt>筛选</dt>
      <dd v-if="props.understanding.filters.length">
        <span v-for="(f, i) in props.understanding.filters" :key="i" class="filter">
          <span v-if="i > 0" class="sep">且</span>
          {{ f.field }} {{ opLabel(f.op) }} {{ f.value ?? '' }}
        </span>
      </dd>
      <dd v-else class="empty">无</dd>
    </div>

    <div class="item">
      <dt>对比</dt>
      <dd v-if="props.understanding.compare">{{ compareText(props.understanding.compare) }}</dd>
      <dd v-else class="empty">无</dd>
    </div>

    <!-- 数据范围单独一条：它解释的是"为什么我看到的数字和别人不一样"，
         那是问数最常见的追问之一，而界面上此前没有任何地方回答它 -->
    <div class="item scope">
      <dt>数据范围</dt>
      <dd v-if="props.understanding.dataScope.restricted">
        {{ props.understanding.dataScope.text ?? '受你的数据权限限制' }}
        <span class="hint">（由你的数据权限决定）</span>
      </dd>
      <dd v-else class="empty">全部数据</dd>
    </div>
  </dl>
</template>

<style scoped>
/* 一条浅色横幅：解释紧跟着的那份结果，不抢注意力 */
.understanding {
  display: flex;
  flex-wrap: wrap;
  gap: 8px 18px;
  margin: 0 0 2px;
  padding: 11px 13px;
  background: var(--surface-sunk);
  border-radius: 11px;
  font-size: 11.5px;
  line-height: 1.6;
}

.item {
  display: flex;
  gap: 7px;
  align-items: baseline;
  min-width: 0;
}

dt {
  flex: none;
  padding: 1px 6px;
  border-radius: 5px;
  background: var(--accent-soft);
  color: var(--accent-deep);
  font-size: 9.5px;
  font-weight: 600;
}

dd {
  margin: 0;
  color: var(--ink);
  min-width: 0;
}

/* 空槽：可见，但不喊。理由见组件注释 */
.empty {
  color: var(--ink-faint);
}

.filter + .filter {
  margin-left: 8px;
}

.sep {
  color: var(--ink-faint);
  margin-right: 4px;
}

.hint {
  color: var(--ink-faint);
}

/* 数据范围换行显示——它常常比其它几项长，挤在同一行会把整条撑开 */
.scope {
  flex-basis: 100%;
}
</style>
