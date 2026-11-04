<script setup lang="ts">
import CostPanel from './CostPanel.vue'

/**
 * 用量页。
 *
 * ## 为什么它单独成页
 *
 * 原先它是「健康度」的第三个标签。那是错的归类——健康度回答
 * 「系统跑得好不好」，用量回答「消耗了多少」。更重要的是：管理员在
 * 「模型」页配完单价之后，**下一个动作一定是想看看花了多少**。
 * 而从「健康度」点进去再切标签，没人会去找。
 *
 * ## 为什么标题是「用量」而不是「成本」
 *
 * 这个页面的**主线是 token**，金额是它的派生值——而派生值经常算不出来：
 * 模型没配单价、老数据没采到 token。那时页面上一片「未配单价」，
 * 而标题还写着「成本」，人第一眼就会开始找那个不存在的金额。
 *
 * 叫「用量」之后，标题承诺的和页面给出的是一致的：消耗了多少 token 一定看得到，
 * 折成多少钱是**在单价配了的前提下**的增值信息。
 *
 * ## 页面本身很简单
 *
 * 内容全在 `CostPanel` 里。这里只做外壳：标题、说明、时间范围。
 * 之所以不把内容直接搬进来，是因为「健康度」里如果哪天还想嵌一份摘要，
 * 复用同一个组件比维护两份口径更安全——**用量的口径只能有一份**。
 */
</script>

<template>
  <section>
    <header class="page-head">
      <div>
        <h1 class="page-title">用量</h1>
        <p class="sub">
          每次提问消耗了多少 token；配了单价的模型会再折成金额。
          数据来自审计日志，随问数实时产生——<strong>没有预聚合</strong>，所以时间范围越大越慢。
        </p>
      </div>
    </header>

    <CostPanel />
  </section>
</template>

<style scoped>
.page-head { margin-bottom: 20px; }
.page-head h1 { font-size: 1.35rem; margin: 0 0 6px; }
.sub { color: #64748b; font-size: 0.85rem; margin: 0; max-width: 70ch; line-height: 1.6; }
</style>
