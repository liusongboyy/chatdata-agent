import { nextTick, onBeforeUnmount, onMounted, ref, type Ref, watch } from 'vue'

/**
 * 弹层的行为补齐：**不误关、Esc 能关、焦点在弹层里**。
 *
 * ## 为什么需要它
 *
 * 本项目没有引入组件库，弹层是手写的 `.modal-mask` + `.modal`。手写很容易漏三件事：
 *
 * 1. **遮罩点击关闭会误触。** `.modal` 有 `max-height: 88dvh; overflow: auto`，
 *    内容一长就在**内部**滚动。鼠标在滚动条上、或在边缘按下再抬起，
 *    都会落到遮罩上，表单当场消失。十几个格子填到一半丢掉一次，
 *    没人愿意再填第二遍。
 * 2. **`@keydown.esc` 挂在 div 上不灵。** 它只在 div 自己有焦点时触发，
 *    而焦点通常在某个输入框里——于是按 Esc 什么也不会发生。
 * 3. **焦点留在背后的页面上。** 键盘 Tab 会跑到列表里，屏幕阅读器也读不到弹层。
 *
 * ## 取舍：遮罩**完全**不响应点击
 *
 * 也可以做成"点到遮罩只是不关、但仍吃掉这次点击"，效果一样而实现更绕。
 * 直接不让它成为关闭入口，用户就不会养成"点外面关掉"的习惯——
 * 而这个习惯在这些页面上代价太大。
 *
 * ## 用法
 *
 * ```ts
 * const { dialogEl, onMaskClick } = useModalGuard(showForm, closeForm)
 * ```
 *
 * 模板：
 * ```html
 * <div v-if="showForm" class="modal-mask">
 *   <div ref="dialogEl" class="modal" tabindex="-1" role="dialog" aria-modal="true">
 * ```
 *
 * **遮罩上不要再写 `@click.self`**——那正是要修的东西。
 *
 * @param open    弹层是否打开
 * @param onClose 关闭动作。**由调用方决定要不要确认**——这里只管触发，
 *                不替它判断"该不该问一句"，因为不同页面的"改过没有"判据不同
 */
export function useModalGuard(open: Ref<boolean>, onClose: () => void) {
  /** 绑到 `.modal` 上，用于把焦点移进去。 */
  const dialogEl = ref<HTMLElement | null>(null)

  function onKeydown(e: KeyboardEvent): void {
    if (!open.value) return
    if (e.key === 'Escape') {
      e.preventDefault()
      onClose()
    }
  }

  onMounted(() => window.addEventListener('keydown', onKeydown))
  onBeforeUnmount(() => window.removeEventListener('keydown', onKeydown))

  watch(open, async (isOpen) => {
    if (!isOpen) return
    // 焦点移进弹层：否则键盘操作仍作用在背后的列表上，
    // 而"按了 Esc 没反应"正是这么来的
    await nextTick()
    dialogEl.value?.focus()
  })

  return { dialogEl }
}
