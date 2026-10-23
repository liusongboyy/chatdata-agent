<script setup lang="ts">
import { session } from '../composables/useSession'

/**
 * 没有任何管理页权限时的落地页。
 *
 * 存在的理由：以前登录后硬跳 `/admin/users`，只有"角色"权限的人会被守卫弹回，
 * 而弹回的目标又是 `/admin/users` —— 来回跳，界面看起来像坏了。
 *
 * 这里给一句能读懂的话和一条出路，而不是空白页。
 */
</script>

<template>
  <section class="no-access">
    <div class="card">
      <span class="mark" aria-hidden="true">!</span>
      <h1 class="page-title">没有可用的管理页</h1>
      <p class="sub">
        当前账号
        <strong>{{ session.user.value?.displayName ?? session.user.value?.username }}</strong>
        没有任何管理页面的功能权限。需要的话，请联系管理员在「角色」页为你的角色勾选相应权限。
      </p>
      <div class="roles">
        <span
          v-for="r in session.user.value?.roles ?? []"
          :key="r"
          class="tag"
        >{{ r }}</span>
        <span v-if="(session.user.value?.roles ?? []).length === 0" class="sub">（无角色）</span>
      </div>
      <a class="btn btn-primary" href="/app/ask">去问数</a>
    </div>
  </section>
</template>

<style scoped>
.no-access {
  display: grid;
  place-items: center;
  min-height: 60dvh;
  padding: var(--space-6) var(--space-4);
}

.card {
  display: flex;
  flex-direction: column;
  align-items: center;
  width: min(34rem, 100%);
  padding: 40px 36px;
  background: var(--surface);
  border-radius: var(--radius-lg);
  box-shadow: var(--shadow);
  text-align: center;
}

.mark {
  display: grid;
  place-items: center;
  width: 46px;
  height: 46px;
  margin-bottom: 16px;
  border-radius: 14px;
  background: var(--warn-soft);
  color: var(--warn);
  font-size: 20px;
  font-weight: 800;
}

.sub {
  color: var(--ink-soft);
  font-size: 12.5px;
  line-height: 1.75;
}

.roles {
  display: flex;
  flex-wrap: wrap;
  justify-content: center;
  gap: 6px;
  margin: 14px 0 20px;
}

.btn {
  display: inline-block;
  text-decoration: none;
}
</style>
