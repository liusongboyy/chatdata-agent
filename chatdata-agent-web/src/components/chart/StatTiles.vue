<script setup lang="ts">
import { seriesColor } from './palette'

/**
 * 数字卡。
 *
 * dataviz 的 form 判断里：**一个值就是数字卡，不画图**。
 * 单条柱或两片饼是把数字包装成图，反而更难读。
 *
 * 规格：
 * - `label` 句式大小写、**不带冒号**
 * - `value` 半粗、**用比例数字**——`tabular-nums` 会让每个数字占 `0` 的宽度，
 *   大字号下 `121` 看起来是散的。等宽数字留给需要纵向对齐的表格列与轴刻度
 */
defineProps<{
  items: { label: string; value: string }[]
}>()
</script>

<template>
  <ul class="tiles">
    <li v-for="(it, i) in items" :key="it.label" class="tile">
      <span class="label">{{ it.label }}</span>
      <span class="value">{{ it.value }}</span>
      <span
        class="accent"
        :style="{ background: items.length > 1 ? seriesColor(i) : 'var(--accent)' }"
        aria-hidden="true"
      />
    </li>
  </ul>
</template>

<style scoped>
.tiles {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(180px, 1fr));
  gap: var(--space-4);
  margin: 0;
  padding: 0;
  list-style: none;
}

.tile {
  position: relative;
  display: flex;
  flex-direction: column;
  gap: 6px;
  padding: 16px 18px;
  background: var(--surface);
  border-radius: var(--radius);
  box-shadow: var(--shadow-sm);
  overflow: hidden;
}

.label {
  font-size: 11px;
  font-weight: 500;
  color: var(--ink-soft);
}

/* 大号数字：比例数字（不加 tabular-nums），半粗 */
.value {
  font-size: clamp(1.75rem, 1.4rem + 1.4vw, 2.5rem);
  font-weight: 650;
  line-height: 1.1;
  color: var(--ink);
  word-break: break-all;
}

/* 左侧一道色条：多个指标时它是"系列身份"（与图表同一套 CVD 校验过的调色板）；
   只有一个指标时没有身份要区分，用强调色即可——
   否则页面上最抢眼的会是一个与业务无关的蓝条 */
.accent {
  position: absolute;
  left: 0;
  top: 0;
  right: 0;
  height: 3px;
}
</style>
