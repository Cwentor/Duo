<script setup lang="ts">
import { computed, ref } from 'vue'
import { api, CapabilityRow } from '../api/client'
import { runtime } from '../stores/runtime'
import { buildFaultAction, COMPONENT_SCOPED } from './inject-logic'

const props = defineProps<{ nodeId: string }>()

const node = computed(() => runtime.nodes.find((n) => n.id === props.nodeId))
const action = ref('')
const instance = ref<number | null>(null)
const result = ref('')
const providers = ref<CapabilityRow[]>([])

// 能力清单全量拉一次，按**当前选中节点**的契约实时过滤——切节点下拉即时跟随（终审 I-3）
async function loadCapabilities() {
  try {
    providers.value = (await api.capabilities()).providers
  } catch {
    providers.value = []
  }
}
loadCapabilities()

const actions = computed(() =>
  providers.value
    .filter((p) => p.contract === nodeContract() && p.supportedFaults.length > 0)
    .flatMap((p) => p.supportedFaults))

function nodeContract(): string {
  return node.value?.contract ?? ''
}

async function inject() {
  result.value = ''
  try {
    const f = buildFaultAction(action.value, props.nodeId, instance.value)
    const r = await api.inject(f)
    result.value = r.success ? '已下达' : `失败：${r.reason}`
  } catch (e: any) {
    result.value = String(e?.message ?? e)
  }
}

async function clear() {
  result.value = ''
  try {
    const f = buildFaultAction(action.value, props.nodeId, instance.value)
    const r = await api.clearInject(f)
    result.value = r.success ? '已清除' : `失败：${r.reason}`
  } catch (e: any) {
    result.value = String(e?.message ?? e)
  }
}
</script>

<template>
  <aside class="inject">
    <h3>注入 · {{ nodeId }}</h3>
    <p v-if="node" class="meta">{{ node.contract }} / {{ node.tier }}
      <span v-if="node.healthy === false" class="warn">（组件异常）</span></p>
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
    <div>
      <button :disabled="!action" @click="inject">⚡ 立即注入</button>
      <button :disabled="!action" @click="clear">撤销注入</button>
    </div>
    <p v-if="result">{{ result }}</p>
  </aside>
</template>

<style scoped>
.inject { border-left: 1px solid #ccc; padding: 12px; width: 260px; overflow: auto; }
.inject button { margin: 6px 6px 0 0; cursor: pointer; }
.meta { color: #888; font-size: 12px; }
.warn { color: #d9a44a; }
</style>
