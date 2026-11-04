<script setup lang="ts">
import { computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { logout as bffLogout } from '../../api/auth'
import UserMenu from '../../components/UserMenu.vue'
import { session } from '../../composables/useSession'
import { usePermissions } from '../../composables/usePermissions'
import { ADMIN_PAGES, pageAllowed } from '../../router'

/**
 * 管理面的外壳：顶部导航 + 内容区。
 *
 * 与问数页分开成两个壳，是因为两者的信息密度差得远——问数是单列宽阅读，
 * 管理面是多列表格。硬塞进同一个布局里只会互相迁就。
 */
const route = useRoute()
const router = useRouter()
const { granted } = usePermissions()

/**
 * 导航项。
 *
 * ⚠️ **路径不带 `/app` 前缀。**
 *
 * router 用 `createWebHistory('/app/')` 声明了 base，路由路径是**相对 base** 的。
 * 写成 `/app/admin/datasources` 会被拼成 `/app/app/admin/datasources`——
 * 实测踩到过：点导航跳不过去，地址栏多一层 `/app`。
 *
 * 清单来自 `router.ts` 的 `ADMIN_PAGES`，与路由守卫读同一份——
 * 两处各写一遍的话，迟早出现"菜单里看得见、点进去被弹回"。
 */
/**
 * 导航项。
 *
 * 判据走 {@link pageAllowed}，与路由守卫**同一个函数**。
 * 自己再写一遍（哪怕逻辑一样）迟早漂移，而漂移的表现是
 * "菜单里看得见、点进去被弹回"，或反过来"地址栏能进、菜单里找不到"。
 */
const NAV = computed(() =>
  ADMIN_PAGES.filter((page) => pageAllowed(page, granted.value)).map((page) => ({
    name: page.name,
    path: page.path,
    label: page.label,
  })),
)

function isActive(path: string): boolean {
  return route.path.startsWith(path)
}

async function backToAsk(): Promise<void> {
  await router.push('/ask')
}

async function doLogout(): Promise<void> {
  try {
    await bffLogout()
  } finally {
    session.clear()
    await router.replace({ name: 'login' })
  }
}
</script>

<template>
  <div class="shell">
    <header class="topbar">
      <div class="brand">
        <span class="logo" aria-hidden="true">问</span>
        <span class="wordmark">问数</span>
        <span class="brand-meta mono">CONFIG</span>
      </div>

      <nav class="nav" aria-label="管理台导航">
        <RouterLink
          v-for="item in NAV"
          :key="item.name"
          :to="item.path"
          class="nav-item"
          :class="{ 'nav-item-active': isActive(item.path) }"
        >
          {{ item.label }}
        </RouterLink>
      </nav>

      <button class="ask-link" type="button" @click="backToAsk">去问数</button>

      <UserMenu
        :name="session.user.value?.realname || session.user.value?.username || '我'"
        :username="session.user.value?.username"
        meta="管理台"
        @logout="doLogout"
      />
    </header>

    <main class="content">
      <RouterView />
    </main>
  </div>
</template>

<style scoped>
.shell {
  min-height: 100dvh;
  display: grid;
  grid-template-rows: auto 1fr;
}

.topbar {
  display: flex;
  align-items: center;
  gap: var(--space-5);
  padding: 0 32px;
  height: 68px;
  background: color-mix(in srgb, var(--surface) 82%, transparent);
  backdrop-filter: blur(14px);
  border-bottom: 1px solid var(--line);
  position: sticky;
  top: 0;
  z-index: 10;
}

.brand {
  display: flex;
  align-items: center;
  gap: 10px;
}

.logo {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 31px;
  height: 31px;
  border-radius: 9px;
  background: var(--gradient-accent);
  color: #fff;
  font-size: 13px;
  font-weight: 800;
}

.wordmark {
  font-family: var(--font-display);
  font-size: var(--text-base);
  font-weight: 750;
  letter-spacing: 0.04em;
  color: var(--ink);
}

.brand-meta {
  margin-left: var(--space-1);
  font-size: 9px;
  letter-spacing: 0.16em;
  color: var(--ink-faint);
}

/* 导航是一组分段控件，不是页眉下划线——C 方向靠"浮起的白药丸"表达当前项 */
.nav {
  display: flex;
  gap: 4px;
  padding: 4px;
  border-radius: 11px;
  background: var(--surface-sunk);
  overflow-x: auto;
}

.nav-item {
  display: inline-flex;
  align-items: center;
  padding: 7px 15px;
  border-radius: 8px;
  font-size: 12.5px;
  font-weight: 550;
  color: var(--ink-soft);
  text-decoration: none;
  white-space: nowrap;
  transition:
    color var(--duration-fast) var(--ease-out),
    background-color var(--duration-fast) var(--ease-out),
    box-shadow var(--duration-fast) var(--ease-out);
}

.nav-item:hover {
  color: var(--ink);
}

.nav-item-active {
  background: var(--surface);
  color: var(--accent-deep);
  font-weight: 650;
  box-shadow: 0 2px 8px rgba(46, 58, 120, 0.09);
}

.ask-link {
  margin-left: auto;
  padding: 0;
  border: none;
  background: transparent;
  font-size: var(--text-sm);
  color: var(--ink-soft);
}

.ask-link:hover {
  color: var(--accent-deep);
}

.content {
  padding: 28px 34px 40px;
  max-width: 1280px;
  width: 100%;
  margin: 0 auto;
}

@media (max-width: 860px) {
  .topbar {
    height: auto;
    flex-wrap: wrap;
    gap: var(--space-3);
    padding: 12px 16px;
  }

  .nav {
    order: 3;
    width: 100%;
  }

  .ask-link {
    display: none;
  }

  .brand-meta {
    display: none;
  }

  .content {
    padding: var(--space-5) var(--space-4) var(--space-6);
  }
}
</style>
