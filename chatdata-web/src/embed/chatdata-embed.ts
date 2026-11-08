/**
 * `<chatdata-ask>` —— 可嵌入问数的 Web Component。
 *
 * <h2>它做什么</h2>
 *
 * <p>宿主页面（CRM、工单系统…）引一行 script，写一个标签，就有了一个问数框：
 *
 * ```html
 * <script src="https://chatdata.example.com/embed/v1/chatdata-embed.js"></script>
 * <chatdata-ask endpoint="https://chatdata.example.com"
 *               assistant="2200" token="<短时令牌>"></chatdata-ask>
 * ```
 *
 * <h2>为什么用 iframe 而不是直接渲染</h2>
 *
 * <p>直接渲染进宿主 DOM 有三个问题：
 *
 * <ol>
 *   <li><b>样式冲突</b>——宿主页面的全局 CSS（尤其 `* { box-sizing }` 与
 *       各种 reset）会打进来，而问数界面的版面是调过的</li>
 *   <li><b>令牌暴露</b>——组件在宿主的 JS 环境里跑，宿主能读它的变量。
 *       而令牌是一张"代表用户"的凭据</li>
 *   <li><b>升级困难</b>——宿主页面里跑的旧版本没法强制更新</li>
 * </ol>
 *
 * <p>iframe 全解决，代价是首屏多一次加载。用 `preconnect` 与延迟挂载缓解。
 *
 * <h2>令牌怎么传</h2>
 *
 * <p><b>不用 postMessage 传递，用 URL fragment（`#token=...`）。</b>
 *
 * <p>fragment 不会被发到服务器（不进 Referer、不进访问日志），
 * 而 iframe 里的页面能读到它。读完立即 `history.replaceState` 清掉，
 * 避免用户从 iframe 里"复制链接"时把令牌带走。
 *
 * <p>postMessage 的问题是时序：宿主必须等 iframe 的 `ready` 消息才能发，
 * 而那个握手一旦写错（比如宿主在 ready 前就发了），表现是"一直白屏"。
 * fragment 没有时序问题。
 *
 * <h2>这个文件不依赖 Vue</h2>
 *
 * <p>它是给宿主页面用的，必须自包含且小。整个组件约 5KB，
 * 而引入 Vue 会让它变成 100KB+——那对"嵌一个小框"来说太重了。
 */

/** 组件的属性。 */
export interface ChatdataAskAttrs {
  /** 问数服务地址，如 `https://chatdata.example.com`。 */
  endpoint: string
  /** 限定的助理 id。**必填**——embed 令牌本身就绑了助理。 */
  assistant: string
  /** 短时令牌。5 分钟过期，宿主应当在快过期时刷新。 */
  token: string
  /** 占位文案。 */
  placeholder?: string
  /** 初始高度（px）。默认 520。 */
  height?: string
}

/** 观察哪些属性变化。改了就重建 iframe。 */
const OBSERVED = ['endpoint', 'assistant', 'token', 'placeholder', 'height'] as const

/**
 * iframe 的路径。
 *
 * <p>`/app/embed` 是主应用里的一个路由——**复用同一套渲染逻辑**。
 * 单独写一个精简版问数页的代价是维护两套，而它们迟早会分叉
 * （比如一边支持了图表切换、另一边没有）。
 */
const EMBED_PATH = '/app/embed'

class ChatdataAskElement extends HTMLElement {

  private frame: HTMLIFrameElement | null = null
  /** 上一次构建用的参数指纹。相同就不重建——避免 token 刷新导致输入框被清空。 */
  private lastKey = ''

  static get observedAttributes(): readonly string[] {
    return OBSERVED
  }

  connectedCallback(): void {
    this.attachShadow({ mode: 'open' })
    this.render()
  }

  disconnectedCallback(): void {
    // 卸载时移除 iframe：不断开的话，宿主把组件从 DOM 摘掉之后
    // 里面的 SSE 连接还在跑
    this.frame?.remove()
    this.frame = null
  }

  attributeChangedCallback(): void {
    // connectedCallback 之前也可能触发（属性在解析时就被设了）
    if (this.shadowRoot) {
      this.render()
    }
  }

  private attrs(): ChatdataAskAttrs | null {
    const endpoint = this.getAttribute('endpoint')
    const assistant = this.getAttribute('assistant')
    const token = this.getAttribute('token')
    if (!endpoint || !assistant || !token) {
      return null
    }
    return {
      endpoint,
      assistant,
      token,
      placeholder: this.getAttribute('placeholder') ?? '问点数据…',
      height: this.getAttribute('height') ?? '520',
    }
  }

  private render(): void {
    const attrs = this.attrs()
    const root = this.shadowRoot
    if (!root) {
      return
    }

    if (!attrs) {
      // 缺参数时**明确说出来**，而不是留一个白框。
      // 宿主开发者看到的应该是"你没给 token"，而不是"嵌进去没反应"
      root.innerHTML = `
        <div part="error" style="
          padding: 16px; border: 1px dashed #d64545; border-radius: 8px;
          color: #d64545; font: 13px/1.6 system-ui, sans-serif;">
          &lt;chatdata-ask&gt; 缺少必填属性：endpoint / assistant / token
        </div>`
      this.frame = null
      return
    }

    // 指纹**不含 token**：token 会每 5 分钟换一次，而换 token 不该重建 iframe
    // （那会清空用户正在输入的内容）。token 变化时走 postMessage 更新
    const key = `${attrs.endpoint}|${attrs.assistant}|${attrs.placeholder}|${attrs.height}`
    const tokenChanged = this.lastKey === key && this.frame !== null

    if (tokenChanged) {
      this.pushToken(attrs.token)
      return
    }

    this.lastKey = key
    this.buildFrame(attrs)
  }

  private buildFrame(attrs: ChatdataAskAttrs): void {
    const root = this.shadowRoot!
    const src = new URL(EMBED_PATH, attrs.endpoint)
    src.searchParams.set('assistant', attrs.assistant)
    if (attrs.placeholder) {
      src.searchParams.set('placeholder', attrs.placeholder)
    }
    // **token 走 fragment**：不进服务器日志、不进 Referer
    src.hash = `token=${encodeURIComponent(attrs.token)}`

    const iframe = document.createElement('iframe')
    iframe.src = src.toString()
    iframe.title = '问数'
    iframe.setAttribute('allow', 'clipboard-write')
    /*
     * sandbox 的取舍。
     *
     * **必须给 `allow-same-origin`**——不给的话 iframe 的 origin 变成 `null`，
     * 于是它加载自己的 JS / CSS 都会被 CORS 拦掉（浏览器认为它们是跨源请求，
     * 而 `null` origin 拿不到任何 CORS 头）。表现是**iframe 一片空白**，
     * 控制台里是几条 `blocked by CORS policy`——与「sandbox 配置」看起来毫无关系。
     *
     * 给 `allow-same-origin` 安全吗？**安全**，因为这里是跨域 iframe：
     * 它保留的是**问数服务自己的** origin，不是宿主的。所以它读不到
     * 宿主的 Cookie / localStorage——跨源隔离由浏览器保证，不靠 sandbox。
     *
     * ⚠️ 但如果宿主与问数**同源**（比如嵌在 /app 下的同一个域名），
     * `allow-scripts allow-same-origin` 同时给就等于没 sandbox——
     * iframe 能操作宿主的 DOM。那种部署不该用这个组件，
     * 直接嵌页面即可。
     */
    iframe.setAttribute('sandbox', 'allow-scripts allow-same-origin allow-forms allow-popups')
    iframe.style.cssText = `
      width: 100%; height: ${attrs.height}px; border: 0;
      border-radius: 12px; display: block; background: transparent;`

    // 首屏前显示一个占位，避免"白框闪一下"
    root.innerHTML = ''
    root.appendChild(iframe)
    this.frame = iframe

    // 尽早建立连接：iframe 的首次请求能省一次 DNS + TCP
    this.preconnect(attrs.endpoint)
  }

  private preconnect(endpoint: string): void {
    const origin = new URL(endpoint).origin
    const existing = document.querySelector(`link[rel="preconnect"][href="${origin}"]`)
    if (existing) {
      return
    }
    const link = document.createElement('link')
    link.rel = 'preconnect'
    link.href = origin
    // 插进宿主的 <head>：preconnect 对文档级生效，shadow DOM 里放没用
    document.head.appendChild(link)
  }

  /**
   * 令牌刷新。
   *
   * <p>用 postMessage 而不是重建 iframe——重建会清空用户正在输入的内容。
   *
   * <p>消息里带一个 `channel: 'chatdata-embed'` 标识：宿主页面可能同时嵌了
   * 好几个 iframe（或者自己也在用 postMessage），不带标识会让消息互相串。
   */
  private pushToken(token: string): void {
    const target = new URL(this.getAttribute('endpoint')!).origin
    this.frame?.contentWindow?.postMessage(
      { channel: 'chatdata-embed', type: 'token', token },
      target,
    )
  }
}

/**
 * 注册。
 *
 * <p>重复注册会抛异常（`customElements.define` 同名二次调用）。
 * 宿主页面可能因为路由切换重复加载这个脚本，所以先判断。
 */
export function register(): void {
  if (!customElements.get('chatdata-ask')) {
    customElements.define('chatdata-ask', ChatdataAskElement)
  }
}

register()
