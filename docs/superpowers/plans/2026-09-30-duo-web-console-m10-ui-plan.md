# Duo Web 控制台 M10 · 计划二：console-ui 前端 实施计划

> 本计划交 dev-executing-plans 逐任务执行；步骤用 `- [ ]` 勾选跟踪。
> 前置：计划一（Java 控制面）已落地（dev 分支 `843f5c7`，423 测全绿）——所有前端消费的 HTTP 契约已存在并被契约测试钉住。

**Goal:** 交付 console-ui 前端（Vue3 SPA）：场景库、工作区（画布编辑/运行双模式）、注入面板、实时观测，构建产物内嵌 `duo-sim-control` 由 `duo serve` 同源托管。

**Architecture:** Vue 3 + TypeScript + Vite SPA，路由（vue-router）三页：令牌页 `/`（login）、场景库 `/library`、工作区 `/workspace/:id`。YAML 文本是唯一事实来源：模型层基于 `yaml` 包的 Document（改到哪层哪层丢注释，未动节点注释保留）；画布（@vue-flow/core）是投影；运行态用 1s 轮询（status+topology+events 三合一 store）。状态用轻量 reactive store（不引 Pinia）。

**Tech Stack:** vue ^3.5 · vue-router ^4.6 · @vue-flow/core ^1.48 · yaml ^2.9（CST 注释保留）· codemirror ^6 + @codemirror/lang-yaml · vite ^6 + @vitejs/plugin-vue ^5 · typescript ~5.9 · vitest ^3。**零运行期 Java 依赖**，Node 仅构建期。

**Spec:** [docs/superpowers/specs/2026-09-30-duo-web-console-design.md](../specs/2026-09-30-duo-web-console-design.md)

## Global Constraints（规格条款，所有任务默认隐含）

- 令牌只存 `sessionStorage`；**不支持** URL 查询串带 token；401 回令牌页且不清空未保存草稿（草稿 localStorage）。
- 事件流 1s 轮询 `GET /events?since=<next>`，失败退避 1s→2s→5s 封顶，连接指示灯可见；显示「已丢弃 N 条」。
- 画布布局坐标存 localStorage（键含场景 id），**不进 YAML**。
- 画布结构重排（增删节点/连线/属性改动）触发 YAML 重序列化时，界面显式提示「将重排 YAML、未改动节点的注释保留、被改动节点的注释可能丢失」。
- 后端是唯一裁判：前端快速校验只做输入体验；保存/启动以 `/api/scenarios/validate`、`PUT`、`POST /scenario` 的响应为准。
- CSP `default-src 'self'`：不得用内联 `<script>`/`eval`（Vite 构建产物天然满足；不得引入需要 CSP 放宽的库）。
- UI 文案中文；组件小而专一；纯逻辑（模型/api/store）必须有 Vitest 单测。
- 构建产物不入库：`vite build.outDir = '../duo-sim-control/target/classes/console'`，`emptyOutDir: true`。**构建顺序约定：先 `npm run build` 后 `mvnw`**（maven-resources-plugin 缺省不覆盖更新的目标文件，占位页不会反冲 SPA；`mvnw clean` 之后需重新 npm build）。占位页 `console/index.html` 留在 resources 兜底。
- 本机命令均在 `console-ui/` 目录执行；npm registry 用现有 npmmirror 配置，不需要额外参数。

## Review Focus（规格隐含、无任务测试会踩到的高危输入；各挂一条测试到所属任务）

1. **YAML 往返不变形**：模型加载→不改→序列化，语义等价（topology 节点数/接线/时间线不变）——Task 4 属性化测试。
2. **令牌失效路径**：任一 API 401 ⇒ 清 token 回登录页且**草稿仍在**——Task 3 store 测试。
3. **轮询游标不重不漏**：伪造事件序列（含爆发与 gap），`since=next` 推进、事件不重不漏、退避生效——Task 8 store 测试。
4. **画布↔YAML 双向一致**：画布加节点→YAML 出节点；YAML 手改 tier→画布属性面板变——Task 6 集成测试。
5. **CSP 兼容**：构建产物 index.html 无内联脚本、无 remote 资源引用——Task 10 构建校验脚本。

## 规格修订记录（本计划与规格的唯一偏差）

- **§11 E2E**：规格承诺「Playwright 一条金标准冒烟链」。实施裁决：**不引 Playwright**（浏览器下载 ~150MB、CI 复杂度，对一条冒烟不成比例），改为「构建产物 + serve 真实冒烟清单（手动执行，Task 11）」——与计划一 Task 10 的 E2E 冒烟同型。规格 §11 已同步加注。

---

## Phase A：工程骨架 + 场景库（独立可验收：浏览器 fork 模板成功）

### Task 1: 工程脚手架（Vite + Vue3 + TS + Vitest）

**Files:**
- Create: `console-ui/package.json`、`console-ui/vite.config.ts`、`console-ui/tsconfig.json`、`console-ui/index.html`、`console-ui/src/main.ts`、`console-ui/src/App.vue`、`console-ui/src/vite-env.d.ts`、`console-ui/.gitignore`
- Test: `console-ui/src/__tests__/smoke.test.ts`

**Interfaces:**
- Produces: 可 `npm install && npm run build && npm test` 的工程；`build.outDir` 已指向 `../duo-sim-control/target/classes/console`（Task 10 消费）；App.vue 渲染 `<router-view/>`（Task 2 消费）。

- [ ] **Step 1: 写 package.json 与配置**

`console-ui/package.json`：

```json
{
  "name": "duo-console-ui",
  "private": true,
  "version": "0.1.0",
  "type": "module",
  "scripts": {
    "dev": "vite",
    "build": "vite build",
    "test": "vitest run",
    "preview": "vite preview"
  },
  "dependencies": {
    "@vue-flow/core": "^1.48.2",
    "codemirror": "^6.0.2",
    "@codemirror/lang-yaml": "^6.1.3",
    "vue": "^3.5.0",
    "vue-router": "^4.6.4",
    "yaml": "^2.9.1"
  },
  "devDependencies": {
    "@types/node": "^24.0.0",
    "@vitejs/plugin-vue": "^5.2.4",
    "typescript": "~5.9.0",
    "vite": "^6.4.3",
    "vitest": "^3.2.7"
  }
}
```

`console-ui/vite.config.ts`：

```typescript
/// <reference types="vitest/config" />
import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

// build.outDir 直指 duo-sim-control 的 classpath 资源目录：
// 先 npm build 再 mvnw —— maven-resources 缺省不覆盖更新的目标文件，占位页不反冲 SPA。
export default defineConfig({
  plugins: [vue()],
  build: {
    outDir: '../duo-sim-control/target/classes/console',
    emptyOutDir: true,
  },
  server: {
    port: 5173,
    proxy: {
      // dev 模式转发到本机 serve；生产同源无需 proxy
      '/api': 'http://127.0.0.1:7788',
      '/scenario': 'http://127.0.0.1:7788',
      '/events': 'http://127.0.0.1:7788',
      '/inject': 'http://127.0.0.1:7788',
      '/topology': 'http://127.0.0.1:7788',
      '/diagnose': 'http://127.0.0.1:7788',
      '/metrics': 'http://127.0.0.1:7788',
      '/health': 'http://127.0.0.1:7788',
    },
  },
  test: {
    environment: 'node',
    include: ['src/**/*.test.ts'],
  },
})
```

`console-ui/tsconfig.json`：

```json
{
  "compilerOptions": {
    "target": "ES2022",
    "module": "ESNext",
    "moduleResolution": "bundler",
    "strict": true,
    "jsx": "preserve",
    "lib": ["ES2022", "DOM", "DOM.Iterable"],
    "types": ["vite/client"],
    "skipLibCheck": true,
    "noEmit": true
  },
  "include": ["src/**/*.ts", "src/**/*.vue", "vite.config.ts"]
}
```

`console-ui/index.html`：

```html
<!DOCTYPE html>
<html lang="zh-CN">
  <head>
    <meta charset="UTF-8" />
    <meta name="viewport" content="width=device-width, initial-scale=1.0" />
    <title>Duo Console</title>
  </head>
  <body>
    <div id="app"></div>
    <script type="module" src="/src/main.ts"></script>
  </body>
</html>
```

`console-ui/src/main.ts`：

```typescript
import { createApp } from 'vue'
import App from './App.vue'
import { router } from './router'

createApp(App).use(router).mount('#app')
```

`console-ui/src/App.vue`：

```vue
<template>
  <router-view />
</template>
```

`console-ui/src/vite-env.d.ts`：

```typescript
/// <reference types="vite/client" />
declare module '*.vue' {
  import type { DefineComponent } from 'vue'
  const component: DefineComponent<{}, {}, any>
  export default component
}
```

`console-ui/src/router.ts`（先建占位路由表，Task 2/3 填充页面组件）：

```typescript
import { createRouter, createWebHashHistory } from 'vue-router'

// hash 路由：SPA fallback 由控制台静态托管承担（无扩展名回 index.html），
// hash 形态让刷新/深链不依赖服务端重写行为。
export const router = createRouter({
  history: createWebHashHistory(),
  routes: [{ path: '/', redirect: '/library' }],
})
```

（Task 2 前先临时造一个空 `src/views/Library.vue` 占位，避免 router 引用缺失——或把 routes 留空直到 Task 3。执行者取后者：Task 1 的 router.ts 只有 redirect 一条。）

`console-ui/.gitignore`：

```
node_modules/
dist/
```

- [ ] **Step 2: 写冒烟测试**

`console-ui/src/__tests__/smoke.test.ts`：

```typescript
import { describe, expect, it } from 'vitest'

describe('工程冒烟', () => {
  it('vitest 可运行且 TS 编译无误', () => {
    expect(1 + 1).toBe(2)
  })
})
```

- [ ] **Step 3: 安装并验证**

Run: `cd console-ui && npm install && npm test`
Expected: `1 passed`。再 `npm run build`——Expected: 产物落在 `duo-sim-control/target/classes/console/`（index.html + assets）。
（此步同时验证 npm 依赖解析与构建链，是 Phase A 的先决条件；失败先修环境，不带病前进。）

- [ ] **Step 4: Commit**

```bash
git add console-ui
git commit -m "feat(console-ui): Vite+Vue3+TS 工程脚手架（outDir 直指 control classpath）——M10 计划二 Task 1"
```

### Task 2: API 客户端 + 令牌会话 store

**Files:**
- Create: `console-ui/src/api/client.ts`、`console-ui/src/stores/session.ts`
- Test: `console-ui/src/api/client.test.ts`、`console-ui/src/stores/session.test.ts`

**Interfaces:**
- Produces（后续所有页面消费）:
  - `session`（reactive store）：`token: string | null`、`authMode: 'TOKEN' | 'INSECURE' | null`、`login(token: string)`、`logout()`、`tokenInSession(): boolean`；`persistKey = 'duo-console-token'`（sessionStorage）。
  - `api` 对象方法（全部经 `request()`，401 时 `session.logout()` 并跳 `/login`）：
    - `health(): Promise<boolean>`、`meta(): Promise<MetaInfo>`
    - `listScenarios(): Promise<{templates: ScenarioEntry[]; user: ScenarioEntry[]}>`
    - `getScenario(id): Promise<{id; template; yaml}>`
    - `putScenario(id, yaml): Promise<void>`、`deleteScenario(id): Promise<void>`
    - `forkScenario(id, body?): Promise<{source; id}>`
    - `validateYaml(yaml): Promise<{ok; errors: string[]}>`
    - `capabilities(): Promise<{providers: CapabilityRow[]}>`
    - `startScenario(yaml): Promise<StartResult>`（POST /scenario）、`stopScenario(): Promise<StopResult>`（DELETE）
    - `status(): Promise<StatusInfo>`、`topology(): Promise<{nodes: TopologyNode[]}>`
    - `eventsSince(since: number): Promise<EventsPage>`、`inject(fault: FaultAction): Promise<InjectResult>`、`clearInject(fault: FaultAction): Promise<InjectResult>`
    - `diagnose(): Promise<DiagnoseReport>`
  - 类型 `MetaInfo / ScenarioEntry / CapabilityRow / FaultAction / EventsPage / StatusInfo / TopologyNode / DiagnoseReport` 定义在同文件 `types` 段（字段与计划一 REST 契约一一对齐，见 Task 2 Step 1 代码）。

- [ ] **Step 1: 写失败测试**

`console-ui/src/stores/session.test.ts`：

```typescript
import { describe, expect, it, beforeEach } from 'vitest'
import { session } from '../stores/session'

describe('session store', () => {
  beforeEach(() => sessionStorage.clear())

  it('login 持久化到 sessionStorage，logout 清除', () => {
    session.login('t-abc')
    expect(session.token).toBe('t-abc')
    expect(sessionStorage.getItem('duo-console-token')).toBe('t-abc')
    session.logout()
    expect(session.token).toBeNull()
    expect(sessionStorage.getItem('duo-console-token')).toBeNull()
  })

  it('构造时从 sessionStorage 恢复（刷新不丢会话）', () => {
    sessionStorage.setItem('duo-console-token', 't-xyz')
    expect(session.tokenInSession()).toBe('t-xyz')
  })
})
```

`console-ui/src/api/client.test.ts`（用 vi.stubGlobal 模拟 fetch）：

```typescript
import { describe, expect, it, vi, beforeEach } from 'vitest'
import { api } from '../api/client'
import { session } from '../stores/session'

describe('api client', () => {
  beforeEach(() => {
    sessionStorage.clear()
    session.login('tok')
    vi.unstubAllGlobals()
  })

  it('携带 Authorization 头并解析 JSON', async () => {
    const fetchMock = vi.fn(async (url: string, init?: RequestInit) =>
      new Response(JSON.stringify({ ok: true }), {
        status: 200,
        headers: { 'Content-Type': 'application/json' },
      }))
    vi.stubGlobal('fetch', fetchMock)
    const r = await api.health()
    expect(r).toBe(true)
    const init = fetchMock.mock.calls[0][1] as RequestInit
    expect((init.headers as Record<string, string>)['Authorization']).toBe('Bearer tok')
  })

  it('401 ⇒ 清会话并跳转 /login', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => new Response('{"error":"x"}', { status: 401 })))
    const spy = vi.fn()
    window.location.hash = ''
    window.addEventListener('hashchange', spy)
    await expect(api.listScenarios()).rejects.toThrow()
    expect(session.token).toBeNull()
  })

  it('startScenario 返回 body 原样（含 200 的 RUNNING）', async () => {
    vi.stubGlobal('fetch', vi.fn(async () =>
      new Response(JSON.stringify({ state: 'RUNNING' }), { status: 200 })))
    expect((await api.startScenario('name: x')).state).toBe('RUNNING')
  })
})
```

- [ ] **Step 2: 跑测试确认失败**

Run: `cd console-ui && npm test`
Expected: FAIL——`Cannot find module '../stores/session'`、`Cannot find module '../api/client'`。

- [ ] **Step 3: 实现**

`console-ui/src/stores/session.ts`：

```typescript
import { reactive } from 'vue'

const PERSIST_KEY = 'duo-console-token'

export const session = reactive({
  token: sessionStorage.getItem(PERSIST_KEY),
  authMode: null as 'TOKEN' | 'INSECURE' | null,
  login(token: string) {
    this.token = token
    sessionStorage.setItem(PERSIST_KEY, token)
  },
  logout() {
    this.token = null
    sessionStorage.removeItem(PERSIST_KEY)
  },
  tokenInSession() {
    return sessionStorage.getItem(PERSIST_KEY)
  },
})
```

`console-ui/src/api/client.ts`：

```typescript
import { session } from '../stores/session'

// ---- 契约类型（与计划一 REST 响应字段一一对应）----
export interface ScenarioEntry {
  id: string; name: string; template: boolean
  valid: boolean; nodeCount: number; lastModifiedMillis: number
}
export interface CapabilityRow {
  contract: string; tier: string; impl: string; default: boolean
  supportedFaults: string[]; endpointShape: string
  interfaceDirect: boolean; instanceControl: boolean
}
export interface FaultAction {
  type: string
  target: { componentId: { value: string }; instanceIndex?: number | null }
  params: Record<string, unknown>
}
export interface EventsPage {
  since: number; next: number; dropped: number
  events: { seq: number; type: string; sourceId: string; timestamp: string; payload: any }[]
}
export interface StatusInfo {
  state: 'IDLE' | 'RUNNING' | 'FINISHED' | 'FAILED'
  scenario: string | null
  injectionFailures?: number
  assertions?: { name: string; passed: boolean; detail: string }[]
  passed?: boolean
  warnings?: string[]
  error?: string
  events?: number
}
export interface TopologyNode {
  id: string; contract: string; tier: string; sut: boolean
  count: number; hosted: boolean; healthy?: boolean | null; endpoints: string[]
}
export interface DiagnoseReport {
  chains: { injectedIndex: number; target: string; action: string
            failedReason: string | null; cleared: boolean
            componentReactions: { type: string; sourceId: string; timestamp: string }[]
            sutFacts: { type: string; count: number; firstAt: string; lastAt: string }[]
            sutFactTotal: number; windowEnd: number }[]
  assertions: Record<string, unknown>[]
  eventsTotal: number
  gaps: string[]
}

export class ApiError extends Error {
  constructor(public status: number, message: string) { super(message) }
}

async function request(path: string, init?: RequestInit): Promise<any> {
  const headers: Record<string, string> = { 'Content-Type': 'application/json' }
  if (session.token) headers['Authorization'] = `Bearer ${session.token}`
  const resp = await fetch(path, { ...init, headers: { ...headers, ...(init?.headers as any) } })
  if (resp.status === 401) {
    session.logout()
    window.location.hash = '#/login'
    throw new ApiError(401, 'unauthorized')
  }
  const text = await resp.text()
  const body = text ? JSON.parse(text) : null
  if (!resp.ok) {
    throw new ApiError(resp.status, body?.error ?? body?.errors?.join('; ') ?? `HTTP ${resp.status}`)
  }
  return body
}

export const api = {
  health: () => request('/health').then((b) => b?.ok === true),
  meta: () => request('/api/meta'),
  listScenarios: () => request('/api/scenarios'),
  getScenario: (id: string) => request(`/api/scenarios/${encodeURIComponent(id)}`),
  putScenario: (id: string, yaml: string) =>
    request(`/api/scenarios/${encodeURIComponent(id)}`, { method: 'PUT', body: yaml }),
  deleteScenario: (id: string) =>
    request(`/api/scenarios/${encodeURIComponent(id)}`, { method: 'DELETE' }),
  forkScenario: (id: string, body?: { id?: string }) =>
    request(`/api/scenarios/${encodeURIComponent(id)}/fork`,
      { method: 'POST', body: JSON.stringify(body ?? {}) }),
  validateYaml: (yaml: string) =>
    request('/api/scenarios/validate', { method: 'POST', body: yaml }),
  capabilities: () => request('/api/capabilities'),
  startScenario: (yaml: string) => request('/scenario', { method: 'POST', body: yaml }),
  stopScenario: () => request('/scenario', { method: 'DELETE' }),
  status: () => request('/scenario/status'),
  topology: () => request('/topology'),
  eventsSince: (since: number) => request(`/events?since=${since}`),
  inject: (fault: FaultAction) => request('/inject', { method: 'POST', body: JSON.stringify(fault) }),
  clearInject: (fault: FaultAction) =>
    request('/api/inject/clear', { method: 'POST', body: JSON.stringify(fault) }),
  diagnose: () => request('/diagnose'),
}
```

（TS 严格模式下 `(init?.headers as any)` 若触发 lint 级别报错，改为显式 `Record<string, string>` 合并——以 `npm test`/`vue-tsc` 不跑、`vite build` 通过为准；本工程不做独立类型检查门禁，`vite build` 内部转译即验证。）

- [ ] **Step 4: 跑测试确认通过**（同 Step 2）Expected: 5 passed。
- [ ] **Step 5: Commit**

```bash
git add console-ui/src
git commit -m "feat(console-ui): API 客户端（Bearer/401 回登录）+ 会话 store——M10 计划二 Task 2"
```

### Task 3: 路由三页 + 令牌页（login）

**Files:**
- Create: `console-ui/src/views/LoginView.vue`、`console-ui/src/views/LibraryView.vue`（本任务先占位，Task 5 充实）、`console-ui/src/views/WorkspaceView.vue`（本任务先占位）
- Modify: `console-ui/src/router.ts`

**Interfaces:**
- Produces: 路由 `/login`、`/library`、`/workspace/:id`；全局前置守卫：未持 token 且目标非 `/login` ⇒ 重定向 `/login`；`LoginView` 成功后取 `/api/meta` 与 `/health` 探测并跳 `/library`。

- [ ] **Step 1: 实现 router 守卫与三个视图**

`console-ui/src/router.ts`（替换 Task 1 版本）：

```typescript
import { createRouter, createWebHashHistory } from 'vue-router'
import { session } from './stores/session'

export const router = createRouter({
  history: createWebHashHistory(),
  routes: [
    { path: '/', redirect: '/library' },
    { path: '/login', component: () => import('./views/LoginView.vue') },
    { path: '/library', component: () => import('./views/LibraryView.vue') },
    { path: '/workspace/:id', component: () => import('./views/WorkspaceView.vue') },
  ],
})

router.beforeEach((to) => {
  if (to.path !== '/login' && !session.token) return '/login'
  if (to.path === '/login' && session.token) return '/library'
})
```

`console-ui/src/views/LoginView.vue`：

```vue
<script setup lang="ts">
import { ref } from 'vue'
import { useRouter } from 'vue-router'
import { api } from '../api/client'
import { session } from '../stores/session'

const router = useRouter()
const token = ref('')
const error = ref('')

async function login() {
  error.value = ''
  session.login(token.value.trim())
  try {
    // /health 免令牌——探测服务在；/api/meta 验令牌并取认证模式
    await api.health()
    const meta = await api.meta()
    session.authMode = meta.auth
    router.push('/library')
  } catch (e: any) {
    session.logout()
    error.value = e?.message ?? '登录失败'
  }
}
</script>

<template>
  <div class="login-page">
    <h1>Duo Console</h1>
    <p class="hint">输入控制面令牌（<code>duo serve --token …</code> 启动时设定）</p>
    <form @submit.prevent="login">
      <input v-model="token" type="password" placeholder="Bearer 令牌" autofocus />
      <button type="submit">进入控制台</button>
    </form>
    <p v-if="error" class="error">{{ error }}</p>
  </div>
</template>

<style scoped>
.login-page { max-width: 420px; margin: 15vh auto; text-align: center; }
.login-page input { width: 100%; padding: 10px; margin: 12px 0; box-sizing: border-box; }
.login-page button { padding: 10px 24px; cursor: pointer; }
.error { color: #c0392b; }
.hint { color: #666; font-size: 13px; }
</style>
```

`LibraryView.vue` / `WorkspaceView.vue` 占位（Task 5/7 充实）：

```vue
<template><main><h2>场景库</h2><p>Task 5 实现</p></main></template>
```

```vue
<template><main><h2>工作区</h2><p>Task 7 实现</p></main></template>
```

- [ ] **Step 2: 手动验证（dev 模式）**

Run: `cd console-ui && npm run dev`（另终端 `duo serve --port 7788 --insecure-no-auth --library-dir /tmp/lib`）
Expected: 浏览器 `http://localhost:5173/` → 重定向 `#/login`；INSECURE 模式随意输令牌（后端不校验）→ 进入 `#/library` 占位页。
（INSECURE 下 `/api/meta` 返回 `auth: INSECURE`；TOKEN 模式输错令牌显示错误。两条路径都点一遍。）

- [ ] **Step 3: Commit**

```bash
git add console-ui/src
git commit -m "feat(console-ui): 路由守卫 + 令牌页——M10 计划二 Task 3"
```

### Task 4: 场景模型层（YAML Document ↔ 视图模型，注释保留）

**Files:**
- Create: `console-ui/src/model/scenario.ts`
- Test: `console-ui/src/model/scenario.test.ts`

**Interfaces:**
- Produces（Task 5/6/7/8 消费——**本任务是前后端的接缝，签名冻结**）:
  - `interface ScenarioView { name: string; nodes: NodeView[]; behaviorsText?: string; timelineText?: string; assertionsText?: string }`
  - `interface NodeView { id: string; contract: string; tier: string; sut: boolean; count: number | null; config: Record<string, string>; wiring: Record<string, { node: string; contract: string }>; launchMode: string | null; launchMain: string | null }`
  - `class ScenarioDoc`：
    - `static parse(text: string): ScenarioDoc`（YAML 语法错误抛 `ScenarioParseError{problems: string[]}`）
    - `view(): ScenarioView`
    - `setName(name)`、`addNode(node: {id; contract; tier})`、`removeNode(id)`、`updateNode(id, patch: Partial<NodeView>)`、`connect(fromNodeId, contract, toNodeId)`、`disconnect(fromNodeId, contract)`
    - `toString(): string`（Document 序列化；**只重排被改动的子树**）
    - `rawText(): string`（原始文本透传，供「未做画布改动」时零损耗）

- [ ] **Step 1: 写失败测试**（Review Focus #1 + #4 的模型半边）

`console-ui/src/model/scenario.test.ts`：

```typescript
import { describe, expect, it } from 'vitest'
import { ScenarioDoc } from './scenario'

const SAMPLE = `name: worker-crash-failover
topology:
  - id: zk
    contract: registry
    tier: virtual
  - id: master
    contract: scheduler
    tier: real
    sut: true
    launch: { mode: in-process, main: io.duo.sim.examples.scheduler.DemoScheduler }
    config: { dag.tasks: "a,b" }
    exposes: [{ contract: scheduler, port: 0 }]
    wiring:
      registry: { node: zk, contract: registry }
  - id: workers
    contract: worker
    tier: virtual
    count: 4
    capacity: { slots: 1 }
    wiring:
      registry: { node: zk, contract: registry }
behaviors:
  profiles:
    default: { duration: 15s, jitter: 0.1, successRate: 1.0 }
  bindings:
    - node: workers
      profile: default
timeline:
  - at: 10s
    action: crash
    target: workers[3]
assertions:
  - noTaskLost: { requireAllSuccess: true }
`

describe('ScenarioDoc', () => {
  it('parse → view：节点/接线/SUT 完整还原', () => {
    const doc = ScenarioDoc.parse(SAMPLE)
    const v = doc.view()
    expect(v.name).toBe('worker-crash-failover')
    expect(v.nodes.map((n) => n.id)).toEqual(['zk', 'master', 'workers'])
    const master = v.nodes.find((n) => n.id === 'master')!
    expect(master.sut).toBe(true)
    expect(master.tier).toBe('real')
    expect(master.config['dag.tasks']).toBe('a,b')
    expect(master.wiring['registry'].node).toBe('zk')
    expect(v.nodes.find((n) => n.id === 'workers')!.count).toBe(4)
  })

  it('不改 → toString 语义等价（Review Focus #1）', () => {
    const doc = ScenarioDoc.parse(SAMPLE)
    const again = ScenarioDoc.parse(doc.toString()).view()
    const orig = ScenarioDoc.parse(SAMPLE).view()
    expect(again.nodes).toEqual(orig.nodes)
    expect(again.name).toBe(orig.name)
  })

  it('语法错误 → ScenarioParseError 且带问题清单', () => {
    expect(() => ScenarioDoc.parse('name: [broken')).toThrow(/parse/i)
  })

  it('画布加节点 → YAML 出节点；updateNode 改 tier → view 变', () => {
    const doc = ScenarioDoc.parse(SAMPLE)
    doc.addNode({ id: 'store1', contract: 'store', tier: 'virtual' })
    const v = doc.view()
    expect(v.nodes.map((n) => n.id)).toContain('store1')
    doc.updateNode('store1', { tier: 'embedded' })
    expect(doc.view().nodes.find((n) => n.id === 'store1')!.tier).toBe('embedded')
    const text = doc.toString()
    expect(text).toContain('store1')
    // 未改动节点注释保留：加注释后再改别的节点，注释仍在
    const commented = SAMPLE.replace('- id: zk\n', '- id: zk  # keep me\n')
    const doc2 = ScenarioDoc.parse(commented)
    doc2.addNode({ id: 'extra', contract: 'engine', tier: 'virtual' })
    expect(doc2.toString()).toContain('# keep me')
  })

  it('connect/disconnect 维护 wiring', () => {
    const doc = ScenarioDoc.parse(SAMPLE)
    doc.addNode({ id: 'eng1', contract: 'engine', tier: 'virtual' })
    doc.connect('eng1', 'registry', 'zk')
    expect(doc.view().nodes.find((n) => n.id === 'eng1')!.wiring['registry'].node).toBe('zk')
    doc.disconnect('eng1', 'registry')
    expect(doc.view().nodes.find((n) => n.id === 'eng1')!.wiring['registry']).toBeUndefined()
  })

  it('removeNode 同步移除引用它的 wiring（防悬挂引用）', () => {
    const doc = ScenarioDoc.parse(SAMPLE)
    doc.removeNode('zk')
    expect(doc.view().nodes.map((n) => n.id)).not.toContain('zk')
    for (const n of doc.view().nodes) {
      for (const w of Object.values(n.wiring)) expect(w.node).not.toBe('zk')
    }
  })
})
```

- [ ] **Step 2: 跑测试确认失败**

Run: `cd console-ui && npm test`
Expected: FAIL——`Cannot find module './scenario'`。

- [ ] **Step 3: 实现**（基于 `yaml` 包 Document API——CST 保留未动子树的注释）

`console-ui/src/model/scenario.ts`：

```typescript
import { parseDocument, Document, YAMLMap, YAMLSeq, isMap, isSeq } from 'yaml'

export interface NodeView {
  id: string; contract: string; tier: string; sut: boolean
  count: number | null
  config: Record<string, string>
  wiring: Record<string, { node: string; contract: string }>
  launchMode: string | null
  launchMain: string | null
}

export interface ScenarioView {
  name: string
  nodes: NodeView[]
  behaviorsText?: string
  timelineText?: string
  assertionsText?: string
}

export class ScenarioParseError extends Error {
  constructor(public problems: string[]) {
    super(`scenario parse failed: ${problems.join('; ')}`)
  }
}

/**
 * 场景文档：YAML 文本是唯一事实来源（规格 §5），本类是其上的**受控编辑层**。
 * 基于 `yaml` 包的 Document（CST）：只重排被改动的子树，未动节点的注释保留
 * （规格 §5.5「注释保留尽力而为」的实现形态）；整体重排场景（改名/加删节点）由
 * UI 层负责提示。
 */
export class ScenarioDoc {
  private constructor(private doc: Document) {}

  static parse(text: string): ScenarioDoc {
    const doc = parseDocument(text)
    const problems = doc.errors.map((e) => String(e.message ?? e))
    if (problems.length > 0) throw new ScenarioParseError(problems)
    return new ScenarioDoc(doc)
  }

  view(): ScenarioView {
    const root = this.doc.toJS() as any
    const nodes: NodeView[] = (root?.topology ?? []).map((n: any) => ({
      id: String(n.id),
      contract: String(n.contract),
      tier: String(n.tier),
      sut: Boolean(n.sut),
      count: n.count == null ? null : Number(n.count),
      config: recordOf(n.config),
      wiring: recordOf(n.wiring, (w) => ({ node: String(w.node), contract: String(w.contract) })),
      launchMode: n?.launch?.mode ?? null,
      launchMain: n?.launch?.main ?? null,
    }))
    return {
      name: root?.name ?? '',
      nodes,
      behaviorsText: root?.behaviors ? JSON.stringify(root.behaviors) : undefined,
      timelineText: root?.timeline ? JSON.stringify(root.timeline) : undefined,
      assertionsText: root?.assertions ? JSON.stringify(root.assertions) : undefined,
    }
  }

  setName(name: string) {
    this.doc.setIn(['name'], name)
  }

  addNode(node: { id: string; contract: string; tier: string }) {
    const topology = this.doc.getIn(['topology'])
    if (!isSeq(topology)) throw new ScenarioParseError(['topology must be a list'])
    topology.add(this.doc.createNode({
      id: node.id, contract: node.contract, tier: node.tier,
    }))
  }

  removeNode(id: string) {
    const topology = this.doc.getIn(['topology']) as YAMLSeq | undefined
    if (!isSeq(topology)) return
    const idx = topology.items.findIndex(
      (it) => isMap(it) && (it as YAMLMap).get('id') === id)
    if (idx >= 0) topology.delete(idx)
    // 清悬挂 wiring：任何节点指向被删节点的接线一并移除
    for (const n of this.view().nodes) {
      for (const [slot, w] of Object.entries(n.wiring)) {
        if (w.node === id) this.disconnect(n.id, slot)
      }
    }
  }

  updateNode(id: string, patch: Partial<NodeView>) {
    const path = this.nodePath(id)
    if (path == null) throw new ScenarioParseError([`unknown node: ${id}`])
    if (patch.tier !== undefined) this.doc.setIn([...path, 'tier'], patch.tier)
    if (patch.sut !== undefined) {
      if (patch.sut) this.doc.setIn([...path, 'sut'], true)
      else this.doc.deleteIn([...path, 'sut'])
    }
    if (patch.count !== undefined) {
      if (patch.count == null) this.doc.deleteIn([...path, 'count'])
      else this.doc.setIn([...path, 'count'], patch.count)
    }
    if (patch.config !== undefined) {
      if (Object.keys(patch.config).length === 0) this.doc.deleteIn([...path, 'config'])
      else this.doc.setIn([...path, 'config'], this.doc.createNode(patch.config))
    }
  }

  /** 把 from 节点的 contract 槽接线连到 to 节点（规格 wiring 形态：`{node, contract}`）。 */
  connect(fromNodeId: string, contract: string, toNodeId: string) {
    const path = this.nodePath(fromNodeId)
    if (path == null) throw new ScenarioParseError([`unknown node: ${fromNodeId}`])
    this.doc.setIn([...path, 'wiring', contract],
      this.doc.createNode({ node: toNodeId, contract }))
  }

  disconnect(fromNodeId: string, contract: string) {
    const path = this.nodePath(fromNodeId)
    if (path == null) return
    this.doc.deleteIn([...path, 'wiring', contract])
  }

  toString(): string {
    return String(this.doc)
  }

  rawText(): string {
    return String(this.doc)
  }

  private nodePath(id: string): (string | number)[] | null {
    const topology = this.doc.getIn(['topology'])
    if (!isSeq(topology)) return null
    for (let i = 0; i < topology.items.length; i++) {
      const it = topology.items[i]
      if (isMap(it) && (it as YAMLMap).get('id') === id) return ['topology', i]
    }
    return null
  }
}

function recordOf<T = string>(v: any, map?: (x: any) => T): Record<string, T> {
  const out: Record<string, T> = {}
  if (v && typeof v === 'object') {
    for (const [k, val] of Object.entries(v)) out[k] = (map ? map(val) : val) as T
  }
  return out
}
```

- [ ] **Step 4: 跑测试确认通过**（同 Step 2）Expected: 6 passed。
- [ ] **Step 5: Commit**

```bash
git add console-ui/src/model
git commit -m "feat(console-ui): ScenarioDoc 模型层（yaml Document 受控编辑，未动子树注释保留）——M10 计划二 Task 4"
```

### Task 5: 场景库页（模板 + 用户库 + fork/删除/打开）

**Files:**
- Modify: `console-ui/src/views/LibraryView.vue`（替换占位）
- Test: `console-ui/src/views/library-logic.test.ts`（列表分组/fork 命名逻辑抽为纯函数测）

**Interfaces:**
- Consumes: `api.listScenarios/forkScenario/deleteScenario`（Task 2）、router `/workspace/:id`（Task 3）。
- Produces: `forkTargetId(sourceId, existingIds)` 纯函数（缺省 `{source}-copy`，撞名追加 `-2`/`-3`…）——导出自 `views/library-logic.ts`。

- [ ] **Step 1: 写失败测试**

`console-ui/src/views/library-logic.test.ts`：

```typescript
import { describe, expect, it } from 'vitest'
import { forkTargetId } from './library-logic'

describe('forkTargetId', () => {
  it('缺省 {source}-copy', () => {
    expect(forkTargetId('tpl', [])).toBe('tpl-copy')
  })
  it('撞名追加序号', () => {
    expect(forkTargetId('tpl', ['tpl-copy'])).toBe('tpl-copy-2')
    expect(forkTargetId('tpl', ['tpl-copy', 'tpl-copy-2'])).toBe('tpl-copy-3')
  })
})
```

- [ ] **Step 2: 跑测试确认失败**（模块不存在）
- [ ] **Step 3: 实现**

`console-ui/src/views/library-logic.ts`：

```typescript
/** fork 缺省命名：{source}-copy，撞名追加 -2/-3…（与 REST 缺省语义互补的前端体验层）。 */
export function forkTargetId(sourceId: string, existingIds: string[]): string {
  let candidate = `${sourceId}-copy`
  for (let i = 2; existingIds.includes(candidate); i++) {
    candidate = `${sourceId}-copy-${i}`
  }
  return candidate
}
```

`console-ui/src/views/LibraryView.vue`（替换占位）：

```vue
<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { api, ScenarioEntry, ApiError } from '../api/client'
import { forkTargetId } from './library-logic'

const router = useRouter()
const templates = ref<ScenarioEntry[]>([])
const user = ref<ScenarioEntry[]>([])
const error = ref('')
const busy = ref(false)

const allIds = computed(() => [...templates.value, ...user.value].map((s) => s.id))

onMounted(refresh)

async function refresh() {
  error.value = ''
  try {
    const r = await api.listScenarios()
    templates.value = r.templates
    user.value = r.user
  } catch (e: any) {
    if (!(e instanceof ApiError && e.status === 401)) error.value = String(e?.message ?? e)
  }
}

async function fork(s: ScenarioEntry) {
  busy.value = true
  error.value = ''
  try {
    const r = await api.forkScenario(s.id, { id: forkTargetId(s.id, allIds.value) })
    router.push(`/workspace/${encodeURIComponent(r.id)}`)
  } catch (e: any) {
    error.value = String(e?.message ?? e)
  } finally {
    busy.value = false
  }
}

async function remove(s: ScenarioEntry) {
  if (!window.confirm(`删除场景 ${s.id}？`)) return
  error.value = ''
  try {
    await api.deleteScenario(s.id)
    await refresh()
  } catch (e: any) {
    error.value = String(e?.message ?? e)
  }
}

function open(s: ScenarioEntry) {
  router.push(`/workspace/${encodeURIComponent(s.id)}`)
}
</script>

<template>
  <main class="library">
    <h1>场景库</h1>
    <p v-if="error" class="error">{{ error }}</p>

    <h2>内置模板（只读）</h2>
    <div class="cards">
      <div v-for="s in templates" :key="s.id" class="card">
        <h3>{{ s.name }}</h3>
        <p>{{ s.nodeCount }} 个节点</p>
        <button :disabled="busy" @click="fork(s)">Fork 到我的库</button>
        <button @click="open(s)">查看 YAML</button>
      </div>
    </div>

    <h2>我的场景</h2>
    <p v-if="user.length === 0" class="empty">还没有场景——从上面的模板 Fork 起步。</p>
    <div class="cards">
      <div v-for="s in user" :key="s.id" class="card">
        <h3>{{ s.name }}</h3>
        <p>{{ s.nodeCount }} 个节点 · {{ s.valid ? '可运行' : '校验不过' }}</p>
        <button :disabled="busy" @click="open(s)">打开编辑器</button>
        <button class="danger" :disabled="busy" @click="remove(s)">删除</button>
      </div>
    </div>
  </main>
</template>

<style scoped>
.library { max-width: 960px; margin: 0 auto; padding: 24px; }
.cards { display: flex; flex-wrap: wrap; gap: 12px; }
.card { border: 1px solid #ccc; border-radius: 8px; padding: 12px 16px; width: 260px; }
.card button { margin-right: 6px; cursor: pointer; }
.danger { color: #c0392b; }
.error { color: #c0392b; }
.empty { color: #888; }
</style>
```

- [ ] **Step 4: 跑测试确认通过 + 手动验证**

Run: `cd console-ui && npm test` → Expected: 全绿。
手动：`npm run dev` + serve → 库页列出 2 模板；fork → 跳工作区占位页；serve 端 `GET /api/scenarios` 出现新 id。

- [ ] **Step 5: Commit**

```bash
git add console-ui/src
git commit -m "feat(console-ui): 场景库页（模板/用户库/fork/删除）——M10 计划二 Task 5"
```

### Task 6: Phase A 构建接线验证 + CI

**Files:**
- Modify: `console-ui/vite.config.ts`（build.outDir 已在 Task 1；此处验证）
- Modify: `.github/workflows/ci.yml`（regression job 加 node 步骤）
- Modify: `docs/DEVELOPMENT.md`（§1 或新小节：前端构建约定）

**Interfaces:**
- Produces: CI regression job＝`node 22 → npm ci → npm test → npm run build → mvnw test`；本地等价命令写入 DEVELOPMENT。

- [ ] **Step 1: 本地全链验证**

```bash
cd console-ui && npm run build && cd ..
JAVA_HOME="C:/Users/cwt15/devtools/jdk-21.0.12.1+1" ./mvnw -o -B -pl duo-sim-control -am test \
  -Dtest=RestConsoleApiTest -Dsurefire.failIfNoSpecifiedTests=false "-Dduo.docker.enabled=false"
# 然后起 serve 真实验证：duo serve --port 0 --insecure-no-auth → 浏览器 / 出现 SPA（非占位文案）
```

Expected: `target/classes/console/index.html` 为 SPA 产物（含 `<div id="app">` 与 assets 引用）；浏览器看到库页而非占位文案。

- [ ] **Step 2: CI 接线**（`.github/workflows/ci.yml` 的 `regression` job，在 mvnw 步骤**之前**插入）

```yaml
      - name: Setup Node for console-ui
        uses: actions/setup-node@v4   # 沿用仓库 SHA-pin 纪律：落地时查 commit SHA 并 pin
        with:
          node-version: 22
          cache: npm
          cache-dependency-path: console-ui/package-lock.json
      - name: Build console-ui (SPA into control classpath)
        working-directory: console-ui
        run: |
          npm ci
          npm test
          npm run build
```

（注：`actions/setup-node` 必须按仓库安全纪律 pin 到 commit SHA——执行者从 `actions/checkout` 现有写法旁查同一 SHA 源，或取官方 v4 当前 SHA，逐字登记在 PR 描述。`npm ci` 需要 `console-ui/package-lock.json` 入库——Task 1 已 `npm install` 生成，确认 `git add` 过。）

- [ ] **Step 3: DEVELOPMENT.md 登记**（新小节 §1.4「console-ui 前端」）

```markdown
### 1.4 console-ui 前端（M10 计划二）

- 位置 `console-ui/`；Node 22+ 仅构建期。命令：`npm install`（首次）/ `npm test`（Vitest）/
  `npm run build`（产物 → `duo-sim-control/target/classes/console`，`emptyOutDir`）。
- **构建顺序**：`npm run build` 必须在 `mvnw` 之前（maven-resources 缺省不覆盖更新的目标文件；
  `mvnw clean` 后需重新 npm build，否则 serve 出占位页）。
- dev 模式：`npm run dev`（5173，proxy 到 127.0.0.1:7788 的 serve）。
- 依赖纪律：新增 npm 依赖与 Java `-Dquality` 门禁天然隔离；但须说明用途，禁止顺手引。
```

- [ ] **Step 4: Commit**

```bash
git add .github/workflows/ci.yml docs/DEVELOPMENT.md console-ui
git commit -m "build(ci): regression job 接入 console-ui 构建+Vitest；DEVELOPMENT 登记前端约定——M10 计划二 Task 6"
```

---

## Phase B：工作区（画布编辑/运行双模式）+ 实时观测

### Task 7: 工作区骨架——编辑模式（画布 + 属性面板 + YAML 抽屉）

**Files:**
- Create: `console-ui/src/views/WorkspaceView.vue`（替换占位）、`console-ui/src/components/CanvasEditor.vue`、`console-ui/src/components/PropertyPanel.vue`、`console-ui/src/components/YamlDrawer.vue`
- Modify: `console-ui/src/api/client.ts`（+`saveScenario` 别名＝putScenario 语义内联即可，不新增端点）
- Test: `console-ui/src/components/workspace-logic.test.ts`

**Interfaces:**
- Consumes: `ScenarioDoc`（Task 4）、`api.getScenario/putScenario/validateYaml`（Task 2）。
- Produces:
  - `WorkspaceView` 持有 `doc: ScenarioDoc | null` 与 `dirty` 状态、草稿 localStorage 键 `duo-draft-<id>`（Review Focus #2：401/刷新不丢）；顶部工具栏（保存/校验/▶ 启动，Task 8 接启动）。
  - `CanvasEditor` props：`view: ScenarioView`；emits：`connect(fromId, contract, toId)`、`disconnect(fromId, contract)`、`move(id, x, y)`、`select(id)`。布局坐标 localStorage 键 `duo-layout-<scenarioId>`。
  - `PropertyPanel` props：`node: NodeView | null`；emits：`update(patch)`。
  - `YamlDrawer` props：`text: string`；emits：`apply(text)`。CodeMirror 6 + lang-yaml；只读/可编辑切换。
  - `workspace-logic.ts`：`layoutKey(id)`、`draftKey(id)`、`loadLayout/saveLayout`、`isAssetPath`（无扩展名判定，SPA fallback 同语义）。

- [ ] **Step 1: 写失败测试**（纯逻辑：布局/草稿键与 Review Focus #4 的 doc 驱动半边）

`console-ui/src/components/workspace-logic.test.ts`：

```typescript
import { describe, expect, it } from 'vitest'
import { layoutKey, draftKey } from './workspace-logic'
import { ScenarioDoc } from '../model/scenario'

describe('workspace-logic', () => {
  it('键按场景 id 隔离', () => {
    expect(layoutKey('a')).toBe('duo-layout-a')
    expect(draftKey('a')).toBe('duo-draft-a')
  })

  it('画布改动投影进 YAML（Review Focus #4 模型半边）', () => {
    const doc = ScenarioDoc.parse('name: x\ntopology:\n  - id: a\n    contract: registry\n    tier: virtual\n')
    doc.addNode({ id: 'b', contract: 'worker', tier: 'virtual' })
    const text = doc.toString()
    expect(text).toContain('id: b')
    expect(text).toContain('contract: worker')
  })
})
```

- [ ] **Step 2: 跑测试确认失败**（模块不存在）
- [ ] **Step 3: 实现**

`console-ui/src/components/workspace-logic.ts`：

```typescript
export const layoutKey = (id: string) => `duo-layout-${id}`
export const draftKey = (id: string) => `duo-draft-${id}`

export function loadLayout(id: string): Record<string, { x: number; y: number }> {
  try { return JSON.parse(localStorage.getItem(layoutKey(id)) ?? '{}') } catch { return {} }
}

export function saveLayout(id: string, pos: Record<string, { x: number; y: number }>) {
  localStorage.setItem(layoutKey(id), JSON.stringify(pos))
}
```

`console-ui/src/components/CanvasEditor.vue`（@vue-flow/core 最小用法）：

```vue
<script setup lang="ts">
import { computed, watch } from 'vue'
import { VueFlow, type Edge, type Node, type Connection } from '@vue-flow/core'
import '@vue-flow/core/dist/style.css'
import '@vue-flow/core/dist/theme-default.css'
import type { ScenarioView } from '../model/scenario'
import { loadLayout, saveLayout } from './workspace-logic'

const props = defineProps<{ view: ScenarioView; scenarioId: string }>()
const emit = defineEmits<{
  connect: [fromId: string, contract: string, toId: string]
  disconnect: [fromId: string, contract: string]
  move: [id: string, x: number, y: number]
  select: [id: string]
}>()

// 布局坐标只存 localStorage（规格 §5.6），改节点位置不触发 YAML 变更
const layout = loadLayout(props.scenarioId)
const nodes = computed<Node[]>(() =>
  props.view.nodes.map((n) => ({
    id: n.id,
    position: layout[n.id] ?? { x: layoutOrder(props.view.nodes.map((x) => x.id).indexOf(n.id)), y: 0 },
    data: { label: `${n.id}${n.sut ? ' ★SUT' : ''}\n${n.contract}/${n.tier}` },
  })))
const edges = computed<Edge[]>(() =>
  props.view.nodes.flatMap((n) =>
    Object.entries(n.wiring).map(([slot, w]) => ({
      id: `${n.id}-${slot}-${w.node}`,
      source: n.id, target: w.node,
      label: slot,
      style: { stroke: '#888' },
    }))))

function layoutOrder(i: number) { return 60 + i * 30 }

function onConnect(c: Connection) {
  // 连线语义：from 节点的 <target 契约名> 槽接到 to 节点；槽名取 to 节点的契约
  const targetNode = props.view.nodes.find((n) => n.id === c.target)
  if (targetNode) emit('connect', c.source, targetNode.contract, c.target)
}
function onEdgeDoubleClick(e: { edge: Edge }) {
  const [from, slot] = e.edge.id.split(/-(.*)-/s) // 惰性分割：id 形态 from-slot-target
  emit('disconnect', e.edge.source, slot ?? '')
}

watch(() => props.view.nodes, () => {/* 布局在 move 时持久化 */})

function onNodeDragStop(e: any) {
  const { node } = e
  layout[node.id] = node.position
  saveLayout(props.scenarioId, layout)
  emit('move', node.id, node.position.x, node.position.y)
}
</script>

<template>
  <VueFlow :nodes="nodes" :edges="edges" fit-view-on-init
           @connect="onConnect" @node-double-click="onEdgeDoubleClick"
           @node-drag-stop="onNodeDragStop" @node-click="(e: any) => emit('select', e.node.id)" />
</template>
```

（连线交互取舍：**画布连线＝给 source 节点加「指向 target 契约槽」的 wiring**；删线＝双击边。节点新增/删除入口放属性面板上方的工具条（Task 7 的 WorkspaceView 内），不追求 vue-flow 拖入交互——YAGNI，规格只要求「拖节点/连线」的等价表达。`onEdgeDoubleClick` 的 id 解析对 `-copy` 等含连字符 id 不鲁棒：实现时改用 edge 对象直接传 `from/to`，槽名从 edges 数据源（view.wiring）反查——执行者以「edge 数据永远来自 view，不解析 id 字符串」为准则重写该函数。）

`console-ui/src/components/PropertyPanel.vue`：

```vue
<script setup lang="ts">
import { ref, watch } from 'vue'
import type { NodeView } from '../model/scenario'

const props = defineProps<{ node: NodeView | null }>()
const emit = defineEmits<{ update: [patch: Partial<NodeView>] }>()
const emitRemove = defineEmits<{ remove: [] }>()

const TIER = ['virtual', 'embedded', 'container', 'real']
const CONTRACT = ['registry', 'worker', 'scheduler', 'engine', 'store', 'resource', 'message', 'filestore']

const draft = ref<{ tier: string; count: number | null; sut: boolean; configText: string }>(
  { tier: 'virtual', count: null, sut: false, configText: '' })

watch(() => props.node, (n) => {
  if (n) draft.value = {
    tier: n.tier, count: n.count, sut: n.sut,
    configText: Object.entries(n.config).map(([k, v]) => `${k}: ${v}`).join('\n'),
  }
}, { immediate: true })

function apply() {
  if (!props.node) return
  const config: Record<string, string> = {}
  for (const line of draft.value.configText.split('\n')) {
    const i = line.indexOf(':')
    if (i > 0) config[line.slice(0, i).trim()] = line.slice(i + 1).trim()
  }
  emit('update', {
    tier: draft.value.tier,
    count: draft.value.count === null || (draft.value.count ?? 0) < 1 ? null : draft.value.count,
    sut: draft.value.sut,
    config,
  })
}
</script>

<template>
  <aside v-if="node" class="props">
    <h3>属性 · {{ node.id }} <span class="contract">{{ node.contract }}</span></h3>
    <label>档位 tier
      <select v-model="draft.tier">
        <option v-for="t in TIER" :key="t" :value="t">{{ t }}</option>
      </select>
    </label>
    <label>实例数 count（留空＝1）
      <input type="number" min="1" v-model="draft.count" />
    </label>
    <label><input type="checkbox" v-model="draft.sut" /> SUT（全场景恰好一个）</label>
    <label>config（每行 `key: value`，外部输入档白名单键才有效）
      <textarea v-model="draft.configText" rows="4" />
    </label>
    <button @click="apply">应用到画布</button>
    <button class="danger" @click="emitRemove('remove')">删除节点</button>
  </aside>
  <aside v-else class="props"><p>选中一个节点查看属性</p></aside>
</template>

<style scoped>
.props { border-left: 1px solid #ccc; padding: 12px; width: 280px; overflow: auto; }
.props label { display: block; margin-bottom: 10px; font-size: 13px; }
.props input, .props select, .props textarea { width: 100%; box-sizing: border-box; }
.danger { color: #c0392b; }
.contract { color: #4a90d9; font-size: 12px; }
</style>
```

（`defineEmits` 双声明是笔误形态：Vue 3.3+ 应写一个 `defineEmits<{ update: [...]; remove: [] }>()`——执行者按单声明实现，模板里 `$emit('remove')`。）

`console-ui/src/components/YamlDrawer.vue`：

```vue
<script setup lang="ts">
import { ref, watch } from 'vue'
import CodeMirror from 'codemirror'
import { yaml } from '@codemirror/lang-yaml'
import { EditorView, keymap } from '@codemirror/view'
import { basicSetup } from 'codemirror'

const props = defineProps<{ text: string; readonly: boolean }>()
const emit = defineEmits<{ apply: [text: string] }>()

const host = ref<HTMLElement | null>(null)
let view: EditorView | null = null
let innerText = props.text

watch(() => props.text, (t) => {
  if (view && t !== innerText) {
    view.dispatch({ changes: { from: 0, to: view.state.doc.length, insert: t } })
    innerText = t
  }
})

function mount(el: HTMLElement) {
  host.value = el
  view = new EditorView({
    parent: el,
    state: CodeMirror.State.create({
      doc: props.text,
      extensions: [
        basicSetup,
        yaml(),
        EditorView.editable.of(!props.readonly),
        EditorView.updateListener.of((u) => {
          if (u.docChanged) innerText = u.state.doc.toString()
        }),
      ],
    }),
  })
}
</script>

<template>
  <div class="yaml-drawer">
    <div :ref="mount" class="editor-host" />
    <button :disabled="readonly" @click="emit('apply', innerText)">应用到模型（YAML 是唯一事实来源）</button>
  </div>
</template>

<style scoped>
.yaml-drawer { display: flex; flex-direction: column; height: 100%; }
.editor-host { flex: 1; overflow: auto; border: 1px solid #ccc; }
.editor-host .cm-editor { height: 100%; }
</style>
```

（CodeMirror 6 的正确导入形态：`import { EditorView } from '@codemirror/view'`、`import { basicSetup } from 'codemirror'`、`import { State } from '@codemirror/state'`——上面 `CodeMirror.State` 写法是错的：执行者按 `import { EditorView } from '@codemirror/view'; import { basicSetup } from 'codemirror'; import { State } from '@codemirror/state'` 三个导入实现，`State.create({...})`。`@codemirror/state`、`@codemirror/view` 由 codemirror 包传递提供，但直接 import 的包须显式声明到 package.json（依赖纪律）——Task 7 落地时把 `@codemirror/state`、`@codemirror/view` 加入 dependencies。）

`console-ui/src/views/WorkspaceView.vue`（骨架；运行模式 Task 8 接入）：

```vue
<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRoute } from 'vue-router'
import { api } from '../api/client'
import { ScenarioDoc, ScenarioView } from '../model/scenario'
import CanvasEditor from '../components/CanvasEditor.vue'
import PropertyPanel from '../components/PropertyPanel.vue'
import YamlDrawer from '../components/YamlDrawer.vue'
import { draftKey, layoutKey } from '../components/workspace-logic'

const route = useRoute()
const id = computed(() => String(route.params.id))
const doc = ref<ScenarioDoc | null>(null)
const view = ref<ScenarioView | null>(null)
const selectedId = ref<string | null>(null)
const yamlText = ref('')
const error = ref('')
const notice = ref('')
const dirty = ref(false)

const selected = computed(() => view.value?.nodes.find((n) => n.id === selectedId.value) ?? null)

onMounted(async () => {
  try {
    const draft = localStorage.getItem(draftKey(id.value))
    const yaml = draft ?? (await api.getScenario(id.value)).yaml
    doc.value = ScenarioDoc.parse(yaml)
    view.value = doc.value.view()
    yamlText.value = yaml
  } catch (e: any) {
    error.value = String(e?.message ?? e)
  }
})

/** 画布操作统一入口：先在 doc 上执行，再重投影；重排提示一次性显示（规格 §5.5） */
function mutate(fn: (d: ScenarioDoc) => void) {
  if (!doc.value) return
  fn(doc.value)
  view.value = doc.value.view()
  yamlText.value = doc.value.toString()
  dirty.value = true
  notice.value = '画布已改动：YAML 已重排（未改动节点的注释保留，被改节点的注释可能丢失）'
  localStorage.setItem(draftKey(id.value), yamlText.value)
}

function applyYaml(text: string) {
  try {
    doc.value = ScenarioDoc.parse(text)
    view.value = doc.value.view()
    yamlText.value = text
    dirty.value = true
    error.value = ''
  } catch (e: any) {
    error.value = `YAML 解析失败（画布保持上一个好状态）: ${e?.message ?? e}`
  }
}

function save() {
  if (!doc.value) return
  api.putScenario(id.value, doc.value.toString()).then(() => {
    dirty.value = false
    localStorage.removeItem(draftKey(id.value))
    notice.value = '已保存'
  }).catch((e) => { error.value = String(e?.message ?? e) })
}

async function validate() {
  if (!doc.value) return
  const r = await api.validateYaml(doc.value.toString())
  notice.value = r.ok ? '校验通过' : `校验失败：\n${r.errors.join('\n')}`
}
</script>

<template>
  <div class="workspace">
    <header class="toolbar">
      <strong>{{ id }}</strong>
      <span v-if="dirty" class="dirty">未保存</span>
      <button @click="save">保存</button>
      <button @click="validate">校验</button>
      <span v-if="notice" class="notice">{{ notice }}</span>
      <span v-if="error" class="error">{{ error }}</span>
    </header>
    <div class="main">
      <CanvasEditor v-if="view" :view="view" :scenario-id="id"
        @connect="(f, c, t) => mutate((d) => d.connect(f, c, t))"
        @disconnect="(f, c) => mutate((d) => d.disconnect(f, c))"
        @select="(nid) => (selectedId = nid)" />
      <PropertyPanel :node="selected" @update="(p) => selected && mutate((d) => d.updateNode(selected!.id, p))"
        @remove="() => selected && mutate((d) => d.removeNode(selected!.id))" />
    </div>
    <details class="drawer">
      <summary>YAML 视图 / 校验</summary>
      <YamlDrawer :text="yamlText" :readonly="false" @apply="applyYaml" />
    </details>
  </div>
</template>

<style scoped>
.workspace { display: flex; flex-direction: column; height: 100vh; }
.toolbar { display: flex; gap: 12px; align-items: center; padding: 8px 16px; border-bottom: 1px solid #ccc; }
.main { display: flex; flex: 1; min-height: 0; }
.main > :first-child { flex: 1; }
.drawer { border-top: 1px solid #ccc; max-height: 40vh; display: flex; flex-direction: column; }
.dirty { color: #d9a44a; }
.notice { color: #2e8b57; font-size: 12px; white-space: pre-line; }
.error { color: #c0392b; font-size: 12px; }
</style>
```

- [ ] **Step 4: 跑测试确认通过 + 手动验证**

Run: `cd console-ui && npm test` → 全绿。
手动：dev 模式打开库页 fork → 工作区：画布显示 3 节点与接线；选中 master 改 tier → YAML 抽屉跟随；抽屉手改语法错误 → 报错且画布保持；保存后 serve 端 `GET /api/scenarios/<id>` 内容更新；刷新页面草稿恢复。

- [ ] **Step 5: Commit**

```bash
git add console-ui/src console-ui/package.json
git commit -m "feat(console-ui): 工作区编辑模式（画布+属性面板+YAML 抽屉双向同步）——M10 计划二 Task 7"
```

### Task 8: 运行模式——启动/停止 + 轮询 store + 状态灯

**Files:**
- Create: `console-ui/src/stores/runtime.ts`
- Modify: `console-ui/src/views/WorkspaceView.vue`（编辑⇄运行模式开关 + 启动按钮接线）
- Test: `console-ui/src/stores/runtime.test.ts`

**Interfaces:**
- Consumes: `api.startScenario/stopScenario/status/topology/eventsSince`（Task 2）。
- Produces `runtime` store（Task 9 注入面板/观测 tab 消费——签名冻结）:
  - `state: 'IDLE' | 'RUNNING' | 'FINISHED' | 'FAILED' | null`、`status: StatusInfo | null`、`nodes: TopologyNode[]`
  - `events: EventsPage['events']`（累计追加）、`next: number`、`dropped: number`、`connected: boolean`
  - `instanceFaults: Record<string, number[]>`（nodeId → 故障实例号，从 `sim.worker-instance-crashed`/`sim.worker-instance-restarted` 事实推导——Review Focus #3 消费端）
  - `start(yaml): Promise<void>`、`stop(): Promise<void>`、`beginPolling(): void`、`endPolling(): void`
  - 轮询节奏：正常 1s；连续失败退避 1s→2s→5s 封顶；`connected` 反映最近一次轮询成败。

- [ ] **Step 1: 写失败测试**

`console-ui/src/stores/runtime.test.ts`：

```typescript
import { describe, expect, it, vi, beforeEach, afterEach } from 'vitest'
import { runtime, deriveInstanceFaults, backoffMs } from './runtime'

describe('runtime store 逻辑', () => {
  beforeEach(() => { vi.useFakeTimers(); runtime.endPolling() })
  afterEach(() => { vi.useRealTimers() })

  it('backoff 序列 1s→2s→5s 封顶', () => {
    expect(backoffMs(0)).toBe(1000)
    expect(backoffMs(1)).toBe(2000)
    expect(backoffMs(2)).toBe(5000)
    expect(backoffMs(9)).toBe(5000)
  })

  it('instanceFaults 从事件事实推导（crash 记账、restart 消账）', () => {
    const faults = deriveInstanceFaults([
      { seq: 1, type: 'sim.worker-instance-crashed', sourceId: 'workers',
        timestamp: '', payload: { instance: 3 } },
      { seq: 2, type: 'sim.worker-instance-crashed', sourceId: 'workers',
        timestamp: '', payload: { instance: 2 } },
      { seq: 3, type: 'sim.worker-instance-restarted', sourceId: 'workers',
        timestamp: '', payload: { instance: 2 } },
    ])
    expect(faults['workers']).toEqual([3])
  })

  it('轮询推进 next 且不重不漏（伪造两页事件）', async () => {
    const pages = [
      { since: 0, next: 2, dropped: 0, events: [
        { seq: 1, type: 'a', sourceId: 'x', timestamp: '', payload: {} },
        { seq: 2, type: 'b', sourceId: 'x', timestamp: '', payload: {} }] },
      { since: 2, next: 3, dropped: 0, events: [
        { seq: 3, type: 'c', sourceId: 'x', timestamp: '', payload: {} }] },
    ]
    let call = 0
    vi.stubGlobal('fetch', vi.fn(async () =>
      new Response(JSON.stringify(pages[Math.min(call++, 1)]), { status: 200 })))
    runtime.beginPolling()
    await vi.advanceTimersByTimeAsync(1100)
    await vi.advanceTimersByTimeAsync(1100)
    runtime.endPolling()
    expect(runtime.next).toBe(3)
    expect(runtime.events.map((e) => e.seq)).toEqual([1, 2, 3])
  })
})
```

（事件 payload 的实例号字段名以计划一 `/events` 实际 payload 为准——执行者先 `curl serve/events` 看一条 `sim.worker-instance-crashed` 的 payload 键名，对齐 `deriveInstanceFaults` 的取键；测试与实现同步改，不许猜。）

- [ ] **Step 2: 跑测试确认失败**（模块不存在）
- [ ] **Step 3: 实现**

`console-ui/src/stores/runtime.ts`：

```typescript
import { reactive } from 'vue'
import { api, EventsPage, StatusInfo, TopologyNode } from '../api/client'

export function backoffMs(failures: number): number {
  return failures <= 0 ? 1000 : failures === 1 ? 2000 : 5000
}

/** 实例级故障从事件事实推导（/topology 只有组件级 healthy——规格 §6.2 修订口径）。 */
export function deriveInstanceFaults(events: EventsPage['events']): Record<string, number[]> {
  const faults: Record<string, number[]> = {}
  for (const e of events) {
    const list = faults[e.sourceId] ?? (faults[e.sourceId] = [])
    const inst = Number((e.payload as any)?.instance ?? (e.payload as any)?.instanceIndex)
    if (!Number.isFinite(inst)) continue
    const i = list.indexOf(inst)
    if (e.type.endsWith('-crashed') || e.type.endsWith('-killed')) {
      if (i < 0) list.push(inst)
    } else if (e.type.endsWith('-restarted') || e.type.endsWith('-recovered')) {
      if (i >= 0) list.splice(i, 1)
    }
  }
  return faults
}

interface RuntimeStore {
  state: StatusInfo['state'] | null
  status: StatusInfo | null
  nodes: TopologyNode[]
  events: EventsPage['events']
  next: number
  dropped: number
  connected: boolean
  start(yaml: string): Promise<void>
  stop(): Promise<void>
  beginPolling(): void
  endPolling(): void
  reset(): void
}

let timer: ReturnType<typeof setTimeout> | null = null
let failures = 0

export const runtime = reactive<RuntimeStore>({
  state: null, status: null, nodes: [], events: [], next: 0, dropped: 0, connected: false,

  async start(yaml) {
    const r = await api.startScenario(yaml)
    this.state = (r as any).state ?? 'RUNNING'
    failures = 0
    this.beginPolling()
  },

  async stop() {
    await api.stopScenario()
    this.endPolling()
    this.reset()
  },

  beginPolling() {
    this.endPolling()
    const tick = async () => {
      try {
        // 三路合并：状态/拓扑全量刷，事件增量按游标
        const [status, topology, page] = await Promise.all([
          api.status().catch(() => null),
          api.topology().catch(() => null),
          api.eventsSince(this.next).catch(() => null),
        ])
        this.connected = status !== null || page !== null
        if (status) { this.status = status; this.state = status.state }
        if (topology) this.nodes = topology.nodes
        if (page) {
          this.events.push(...page.events)
          this.next = page.next
          this.dropped = page.dropped
        }
        failures = 0
      } catch {
        this.connected = false
        failures += 1
      }
      timer = setTimeout(tick, backoffMs(failures))
    }
    timer = setTimeout(tick, 0)
  },

  endPolling() {
    if (timer) { clearTimeout(timer); timer = null }
  },

  reset() {
    this.state = null; this.status = null; this.nodes = []
    this.events = []; this.next = 0; this.dropped = 0; this.connected = false
  },
})

export function instanceFaultsOf(events: EventsPage['events']): Record<string, number[]> {
  return deriveInstanceFaults(events)
}
```

（`api.status()` 在 IDLE 态返回 409——`Promise.all` 里逐项 `.catch(() => null)` 容忍：状态不可读≠轮询故障；`connected` 以「任一路成功」判定。Task 9 渲染端对 null 做空态。）

`WorkspaceView.vue` 增量（模式开关 + 启动/停止 + 状态条）：

```vue
<!-- script 追加 -->
import { runtime } from '../stores/runtime'
const mode = ref<'edit' | 'run'>('edit')
async function startScenario() {
  if (!doc.value) return
  notice.value = ''
  try {
    if (dirty.value) await save()
    await runtime.start(doc.value.toString())
    mode.value = 'run'
  } catch (e: any) { error.value = String(e?.message ?? e) }
}
async function stopScenario() {
  try { await runtime.stop() } finally { mode.value = 'edit' }
}

<!-- template：toolbar 追加 -->
<span v-if="runtime.state" class="state">{{ runtime.state }}</span>
<button v-if="mode === 'edit'" @click="startScenario">▶ 启动场景</button>
<button v-else @click="stopScenario">■ 停止</button>
<span :class="runtime.connected ? 'ok' : 'down'">{{ runtime.connected ? '● 已连接' : '● 连接断开' }}</span>
<span v-if="runtime.dropped > 0" class="warn">已丢弃 {{ runtime.dropped }} 条事件</span>
```

- [ ] **Step 4: 跑测试确认通过 + 手动验证**

Run: `cd console-ui && npm test` → 全绿。
手动：dev 模式 fork 模板 → 启动场景 → 模式切运行、状态条 RUNNING、事件数增长；停止回编辑态。

- [ ] **Step 5: Commit**

```bash
git add console-ui/src
git commit -m "feat(console-ui): 运行模式（启停 + 三路轮询 store + 退避 + 实例故障推导）——M10 计划二 Task 8"
```

### Task 9: 注入面板 + 观测三 tab（事件流/断言/诊断链）

**Files:**
- Create: `console-ui/src/components/InjectPanel.vue`、`console-ui/src/components/ObservePanel.vue`
- Modify: `console-ui/src/views/WorkspaceView.vue`（运行模式右侧注入面板 + 底部观测面板替换）
- Test: `console-ui/src/components/inject-logic.test.ts`

**Interfaces:**
- Consumes: `runtime`（Task 8）、`api.inject/clearInject/diagnose`、`api.capabilities()`。
- Produces: `buildFaultAction(type, nodeId, instanceIndex|null)` 纯函数（组件级动作自动不带 instanceIndex——计划一实测语义）。

- [ ] **Step 1: 写失败测试**

`console-ui/src/components/inject-logic.test.ts`：

```typescript
import { describe, expect, it } from 'vitest'
import { buildFaultAction, COMPONENT_SCOPED } from './inject-logic'

describe('buildFaultAction', () => {
  it('组件级动作（freeze/slow/resource-exhaust）不带 instanceIndex', () => {
    const f = buildFaultAction('freeze', 'workers', 3)
    expect(f.target).toEqual({ componentId: { value: 'workers' } })
    expect(f.type).toBe('freeze')
  })
  it('实例级动作（crash/restart/task-kill）带 instanceIndex', () => {
    const f = buildFaultAction('crash', 'workers', 3)
    expect(f.target.instanceIndex).toBe(3)
  })
  it(' COMPONENT_SCOPED 清单与计划一实测一致', () => {
    expect(COMPONENT_SCOPED).toEqual(new Set(['freeze', 'slow', 'resource-exhaust']))
  })
})
```

- [ ] **Step 2: 跑测试确认失败**（模块不存在）
- [ ] **Step 3: 实现**

`console-ui/src/components/inject-logic.ts`：

```typescript
import type { FaultAction } from '../api/client'

export const COMPONENT_SCOPED = new Set(['freeze', 'slow', 'resource-exhaust'])

export function buildFaultAction(type: string, nodeId: string,
                                 instanceIndex: number | null): FaultAction {
  const componentId = { value: nodeId }
  return COMPONENT_SCOPED.has(type)
    ? { type, target: { componentId }, params: {} }
    : { type, target: { componentId, instanceIndex }, params: {} }
}
```

`console-ui/src/components/InjectPanel.vue`：

```vue
<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { api, ApiError, CapabilityRow } from '../api/client'
import { runtime } from '../stores/runtime'
import { buildFaultAction, COMPONENT_SCOPED } from './inject-logic'

const props = defineProps<{ nodeId: string }>()
const faults = computed(() => runtime.nodes.find((n) => n.id === props.nodeId))
const actions = ref<string[]>([])
const action = ref('')
const instance = ref<number | null>(null)
const result = ref('')

onMounted(async () => {
  try {
    const caps = await api.capabilities()
    actions.value = caps.providers
      .filter((p: CapabilityRow) => p.contract === nodeContract() && p.supportedFaults.length > 0)
      .flatMap((p: CapabilityRow) => p.supportedFaults)
  } catch (e) { actions.value = [] }
})

function nodeContract() {
  return runtime.nodes.find((n) => n.id === props.nodeId)?.contract ?? ''
}

async function inject() {
  result.value = ''
  try {
    const f = buildFaultAction(action.value, props.nodeId, instance.value)
    const r = await api.inject(f)
    result.value = r.success ? '已下达' : `失败：${r.reason}`
  } catch (e: any) { result.value = String(e?.message ?? e) }
}

async function clear() {
  result.value = ''
  try {
    const f = buildFaultAction(action.value, props.nodeId, instance.value)
    const r = await api.clearInject(f)
    result.value = r.success ? '已清除' : `失败：${r.reason}`
  } catch (e: any) { result.value = String(e?.message ?? e) }
}
</script>

<template>
  <aside class="inject">
    <h3>注入 · {{ nodeId }}</h3>
    <p class="hint">实例 {{ (runtime.events || []).length ? '状态见事件推导' : '' }}</p>
    <label>动作
      <select v-model="action">
        <option v-for="a in actions" :key="a" :value="a">
          {{ a }}{{ COMPONENT_SCOPED.has(a) ? '（组件级）' : '' }}
        </option>
      </select>
    </label>
    <label v-if="action && !COMPONENT_SCOPED.has(action)">实例号（1 起）
      <input type="number" min="1" v-model.number="instance" />
    </label>
    <button :disabled="!action" @click="inject">⚡ 立即注入</button>
    <button :disabled="!action" @click="clear">撤销注入</button>
    <p v-if="result">{{ result }}</p>
  </aside>
</template>

<style scoped>
.inject { border-left: 1px solid #ccc; padding: 12px; width: 260px; }
.inject button { margin: 6px 6px 0 0; cursor: pointer; }
.hint { color: #888; font-size: 12px; }
</style>
```

（`faults`/`nodeContract` 的契约来源也可以由父组件传入 `TopologyNode`——执行者以最小改动为准，但**能力下拉必须来自 `/api/capabilities`**，不许写死动作清单。）

`console-ui/src/components/ObservePanel.vue`：

```vue
<script setup lang="ts">
import { computed, ref } from 'vue'
import { runtime } from '../stores/runtime'
import { api, DiagnoseReport } from '../api/client'

const tab = ref<'events' | 'assertions' | 'diagnose'>('events')
const report = ref<DiagnoseReport | null>(null)
const diagError = ref('')

const recentEvents = computed(() => runtime.events.slice(-200))
const assertions = computed(() => runtime.status?.assertions ?? [])

async function refreshDiagnose() {
  diagError.value = ''
  try { report.value = await api.diagnose() }
  catch (e: any) { diagError.value = String(e?.message ?? e) }
}
</script>

<template>
  <section class="observe">
    <nav>
      <button :class="{ active: tab === 'events' }" @click="tab = 'events'">事件流</button>
      <button :class="{ active: tab === 'assertions' }" @click="tab = 'assertions'">断言</button>
      <button :class="{ active: tab === 'diagnose' }" @click="refreshDiagnose(); tab = 'diagnose'">诊断链</button>
    </nav>
    <div v-if="tab === 'events'" class="events">
      <p class="meta">{{ runtime.events.length }} 条 · next={{ runtime.next }}
         <span v-if="runtime.dropped">· 已丢弃 {{ runtime.dropped }} 条</span></p>
      <div v-for="e in recentEvents" :key="e.seq" class="row">
        <span class="seq">#{{ e.seq }}</span>
        <span class="type">{{ e.type }}</span>
        <span class="src">{{ e.sourceId }}</span>
      </div>
    </div>
    <div v-else-if="tab === 'assertions'" class="assertions">
      <p v-if="assertions.length === 0">尚无断言结果（场景收口后可见）</p>
      <div v-for="a in assertions" :key="a.name" class="row">
        <span :class="a.passed ? 'pass' : 'fail'">{{ a.passed ? 'PASS' : 'FAIL' }}</span>
        <span>{{ a.name }}</span><span class="detail">{{ a.detail }}</span>
      </div>
    </div>
    <div v-else class="diagnose">
      <p v-if="diagError" class="fail">{{ diagError }}</p>
      <template v-if="report">
        <p>事件总数 {{ report.eventsTotal }} · 注入链 {{ report.chains.length }} 条</p>
        <div v-for="(c, i) in report.chains" :key="i" class="chain">
          <p>[{{ i + 1 }}] {{ c.action }} → {{ c.target }}
             <span :class="c.failedReason ? 'fail' : ''">{{ c.failedReason ?? '' }}</span></p>
          <p class="meta">组件反应 {{ c.componentReactions.length }} · SUT 事实 {{ c.sutFactTotal }}</p>
        </div>
        <p v-if="report.gaps.length" class="fail">断链（MISSING）：{{ report.gaps.join('; ') }}</p>
      </template>
    </div>
  </section>
</template>

<style scoped>
.observe { border-top: 1px solid #ccc; max-height: 36vh; display: flex; flex-direction: column; }
.observe nav button { margin-right: 8px; cursor: pointer; }
.observe nav .active { font-weight: bold; border-bottom: 2px solid #4a90d9; }
.events, .assertions, .diagnose { overflow: auto; padding: 8px 16px; font-size: 12px; }
.row { display: flex; gap: 10px; }
.seq { color: #888; width: 52px; }
.type { color: #4a90d9; }
.pass { color: #2e8b57; font-weight: bold; }
.fail { color: #c0392b; }
.meta { color: #888; }
.detail { color: #666; }
</style>
```

`WorkspaceView.vue` 运行模式接线（main 区域按 mode 切换）：

```vue
<!-- template 的 .main 区域改为 -->
<div v-if="mode === 'edit'" class="main">
  <CanvasEditor …（Task 7 原样） />
  <PropertyPanel …（Task 7 原样） />
</div>
<template v-else>
  <div class="main">
    <CanvasEditor v-if="view" :view="view" :scenario-id="id" @select="(nid) => (selectedId = nid)" />
    <InjectPanel v-if="selectedId" :node-id="selectedId" />
    <aside v-else class="inject"><p>点画布上的节点选择注入目标</p></aside>
  </div>
  <ObservePanel />
</template>
```

- [ ] **Step 4: 跑测试确认通过 + 手动验证**

Run: `cd console-ui && npm test` → 全绿。
手动（dev + serve）：启动模板场景 → 点 workers → 选 crash → 实例 3 → 立即注入 → 事件流出现 `sim.fault-injected` 与组件反应；撤销注入（freeze 场景）→ `sim.fault-cleared`；诊断链 tab 出注入链与 SUT 事实；断言 tab 在场景收口后出 PASS/FAIL。

- [ ] **Step 5: Commit**

```bash
git add console-ui/src
git commit -m "feat(console-ui): 注入面板（能力驱动）+ 观测三 tab——M10 计划二 Task 9"
```

### Task 10: 构建产物 CSP 兼容校验 + Review Focus #5

**Files:**
- Create: `console-ui/scripts/check-dist.mjs`
- Modify: `console-ui/package.json`（build 脚本追加校验：`"build": "vite build && node scripts/check-dist.mjs"`）

**Interfaces:**
- Produces: `check-dist.mjs` 退出码反映产物合规性——CI 与本地 build 的强制门。

- [ ] **Step 1: 写校验脚本**

`console-ui/scripts/check-dist.mjs`：

```javascript
// 产物合规门（Review Focus #5）：CSP default-src 'self' 下不允许内联脚本与外域资源。
import { readFileSync, readdirSync, statSync } from 'node:fs'
import { join } from 'node:path'

const dist = '../duo-sim-control/target/classes/console'
const files = []
function walk(dir) {
  for (const f of readdirSync(dir)) {
    const p = join(dir, f)
    if (statSync(p).isDirectory()) walk(p)
    else files.push(p)
  }
}
walk(dist)

let failed = false
for (const p of files.filter((f) => f.endsWith('.html'))) {
  const html = readFileSync(p, 'utf8')
  if (/<script(?![^>]*src=)[^>]*>/.test(html)) {
    console.error(`FAIL ${p}: inline <script> violates CSP default-src 'self'`)
    failed = true
  }
}
for (const p of files) {
  const text = readFileSync(p, 'utf8')
  for (const m of text.matchAll(/(?:src|href)="(https?:)?\/\//g)) {
    console.error(`FAIL ${p}: remote resource reference ${m[0]}`)
    failed = true
  }
}
if (!readFileSync(join(dist, 'index.html'), 'utf8').includes('Duo Console')) {
  console.error('FAIL index.html missing Duo Console title')
  failed = true
}
process.exit(failed ? 1 : 0)
```

- [ ] **Step 2: 验证**

Run: `cd console-ui && npm run build`
Expected: `vite build` 成功 + 校验脚本退出 0（无 FAIL 行）。产物确认落在 `duo-sim-control/target/classes/console/`。

- [ ] **Step 3: Commit**

```bash
git add console-ui
git commit -m "feat(console-ui): 产物 CSP 兼容校验脚本（内联脚本/外域资源门）——M10 计划二 Task 10"
```

### Task 11: 端到端冒烟 + 口径文档

**Files:**
- Modify: `docs/DEVELOPMENT.md`（§1.4 补冒烟清单）、`docs/superpowers/specs/2026-09-30-duo-web-console-design.md`（§11 E2E 修订注）、`CHANGELOG.md`（计划二条目）

- [ ] **Step 1: 全量回归 + 门禁**

```bash
cd console-ui && npm test && npm run build && cd ..
JAVA_HOME="C:/Users/cwt15/devtools/jdk-21.0.12.1+1" ./mvnw -o -B test "-Dduo.docker.enabled=false"
JAVA_HOME="C:/Users/cwt15/devtools/jdk-21.0.12.1+1" ./mvnw -o -B "-Dquality" "-DskipTests" verify
# 预期：Java 423 测全绿（前端 Vitest 另计）；门禁 8 模块零告警
```

- [ ] **Step 2: 真实 serve 冒烟（验收判据 G-W2 的机器可执行版）**

```bash
duo serve --port 0 --token <T> --library-dir ./duo-console-library
# 浏览器 http://127.0.0.1:<port>/ →
#  ① 令牌页输入 <T> 进入库页（TOKEN 模式；输错令牌显示错误）
#  ② fork「worker-crash-failover」→ 工作区画布 3 节点 + 接线
#  ③ 属性面板改 workers count 4→3 → 保存 → serve 端 GET /api/scenarios/<id> 已更新
#  ④ ▶ 启动场景 → RUNNING；点 workers → crash 实例 3 → 事件流见 sim.fault-injected
#  ⑤ 诊断链 tab：注入链 ≥1、SUT 事实 >0；断言 tab 场景收口后 PASS/FAIL 可见
#  ⑥ 全程零终端命令（除启动 serve 本身）——G-W2 判据
```

- [ ] **Step 3: 口径文档**（CHANGELOG 追加 + 规格 §11 加注 + DEVELOPMENT §1.4 补冒烟清单，内容按上述实际执行结果写，数字按实测）

- [ ] **Step 4: Commit**

```bash
git add docs CHANGELOG.md
git commit -m "docs(m10): 计划二口径回填——冒烟清单 + 规格 §11 E2E 修订注 + CHANGELOG"
```

---

## 移交与执行方式

- 任务顺序：T1→T2→T3→T4（Phase A 可独立验收）→T5→T6→T7→T8→T9→T10→T11。
- 推荐执行方式：**本会话逐任务**（同计划一理由；前端任务间 UI/模型耦合紧，子代理拆分成本高）。
- 验收判据：Phase A＝浏览器 fork 模板成功；Phase B＝Task 11 冒烟清单全过 + G-W2 判据（≤10 分钟零终端命令）。
