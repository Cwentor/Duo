<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRoute } from 'vue-router'
import { api } from '../api/client'
import { ScenarioDoc, ScenarioView } from '../model/scenario'
import CanvasEditor from '../components/CanvasEditor.vue'
import PropertyPanel from '../components/PropertyPanel.vue'
import YamlDrawer from '../components/YamlDrawer.vue'
import { draftKey } from '../components/workspace-logic'

const route = useRoute()
const id = computed(() => String(route.params.id))
const doc = ref<ScenarioDoc | null>(null)
const view = ref<ScenarioView | null>(null)
const selectedId = ref<string | null>(null)
const yamlText = ref('')
const error = ref('')
const notice = ref('')
const dirty = ref(false)
const newNode = ref<{ id: string; contract: string; tier: string }>({
  id: '', contract: 'registry', tier: 'virtual',
})

const CONTRACTS = ['registry', 'worker', 'scheduler', 'engine', 'store', 'resource', 'message', 'filestore']
const TIER = ['virtual', 'embedded', 'container', 'real']

const selected = computed(() => view.value?.nodes.find((n) => n.id === selectedId.value) ?? null)

onMounted(async () => {
  try {
    // 草稿优先（401/刷新不丢未保存内容——规格 §10）；无草稿取服务端
    const draft = localStorage.getItem(draftKey(id.value))
    const yaml = draft ?? (await api.getScenario(id.value)).yaml
    doc.value = ScenarioDoc.parse(yaml)
    view.value = doc.value.view()
    yamlText.value = yaml
  } catch (e: any) {
    error.value = String(e?.message ?? e)
  }
})

/** 画布操作统一入口：先在 doc 上执行，再重投影 + 落草稿 + 重排提示（规格 §5.5） */
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
    localStorage.setItem(draftKey(id.value), text)
  } catch (e: any) {
    // 解析失败：画布保持上一个好状态（规格 §5.2），错误逐条可见
    error.value = `YAML 解析失败（画布保持上一个好状态）: ${e?.message ?? e}`
  }
}

function save() {
  if (!doc.value) return
  return api.putScenario(id.value, doc.value.toString()).then(() => {
    dirty.value = false
    localStorage.removeItem(draftKey(id.value))
    notice.value = '已保存'
  }).catch((e) => { error.value = String(e?.message ?? e) })
}

async function validate() {
  if (!doc.value) return
  try {
    const r = await api.validateYaml(doc.value.toString())
    notice.value = r.ok ? '校验通过' : `校验失败：\n${r.errors.join('\n')}`
  } catch (e: any) {
    error.value = String(e?.message ?? e)
  }
}

function addNode() {
  const nid = newNode.value.id.trim()
  if (!nid) { error.value = '节点 id 不能为空'; return }
  if (view.value?.nodes.some((n) => n.id === nid)) { error.value = `节点 id 已存在: ${nid}`; return }
  error.value = ''
  mutate((d) => d.addNode({ ...newNode.value, id: nid }))
  selectedId.value = nid
  newNode.value = { id: '', contract: 'registry', tier: 'virtual' }
}
</script>

<template>
  <div class="workspace">
    <header class="toolbar">
      <strong>{{ id }}</strong>
      <span v-if="dirty" class="dirty">未保存</span>
      <button @click="save">保存</button>
      <button @click="validate">校验</button>
      <input v-model="newNode.id" placeholder="新节点 id" class="new-id" />
      <select v-model="newNode.contract">
        <option v-for="c in CONTRACTS" :key="c" :value="c">{{ c }}</option>
      </select>
      <select v-model="newNode.tier">
        <option v-for="t in TIER" :key="t" :value="t">{{ t }}</option>
      </select>
      <button @click="addNode">＋ 添加节点</button>
      <span v-if="notice" class="notice">{{ notice }}</span>
      <span v-if="error" class="error">{{ error }}</span>
    </header>
    <div class="main">
      <CanvasEditor
        v-if="view"
        :view="view"
        :scenario-id="id"
        @connect="(f, c, t) => mutate((d) => d.connect(f, c, t))"
        @disconnect="(f, c) => mutate((d) => d.disconnect(f, c))"
        @select="(nid) => (selectedId = nid)"
      />
      <PropertyPanel
        :node="selected"
        @update="(p) => selected && mutate((d) => d.updateNode(selected!.id, p))"
        @remove="() => selected && mutate((d) => d.removeNode(selected!.id))"
      />
    </div>
    <details class="drawer">
      <summary>YAML 视图 / 校验</summary>
      <YamlDrawer :text="yamlText" :readonly="false" @apply="applyYaml" />
    </details>
  </div>
</template>

<style scoped>
.workspace { display: flex; flex-direction: column; height: 100vh; }
.toolbar { display: flex; gap: 8px; align-items: center; padding: 8px 16px; border-bottom: 1px solid #ccc; flex-wrap: wrap; }
.new-id { width: 130px; }
.main { display: flex; flex: 1; min-height: 0; }
.main > :first-child { flex: 1; }
.drawer { border-top: 1px solid #ccc; max-height: 40vh; display: flex; flex-direction: column; }
.dirty { color: #d9a44a; }
.notice { color: #2e8b57; font-size: 12px; white-space: pre-line; }
.error { color: #c0392b; font-size: 12px; }
</style>
