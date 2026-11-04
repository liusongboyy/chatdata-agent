<script setup lang="ts">
import CostPanel from './CostPanel.vue'

/**
 * 成本页。
 *
 * ## 为什么它单独成页
 *
 * 原先它是「健康度」的第三个标签。那是错的归类——健康度回答
 * 「系统跑得好不好」，成本回答「钱花在哪」。更重要的是：管理员在
 * 「模型」页配完单价之后，**下一个动作一定是想看看花了多少**。
 * 而从「健康度」点进去再切标签，没人会去找。
 *
 * ## 页面本身很简单
 *
 * 内容全在 `CostPanel` 里。这里只做外壳：标题、说明、时间范围。
 * 之所以不把内容直接搬进来，是因为「健康度」里如果哪天还想嵌一份摘要，
 * 复用同一个组件比维护两份口径更安全——**成本的口径只能有一份**。
 */
</script>

<template>
  <section>
    <header class="page-head">
      <div>
        <h1 class="page-title">成本</h1>
        <p class="sub">
          每次提问消耗了多少 token、按配置的单价折成多少钱。
          数据来自审计日志，随问数实时产生——**没有预聚合**，所以时间范围越大越慢。
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
