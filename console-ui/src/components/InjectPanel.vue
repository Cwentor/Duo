<script setup lang="ts">
import { computed, ref } from 'vue'
import { api, CapabilityRow } from '../api/client'
import { runtime } from '../stores/runtime'
import {
  availableActions, buildFaultAction, needsInstanceIndex, isClearable, COMPONENT_SCOPED,
} from './inject-logic'

const props = defineProps<{ nodeId: string }>()

const node = computed(() => runtime.nodes.find((n) => n.id === props.nodeId))
const action = ref('')
const instance = ref<number | null>(null)
const result = ref('')
const providers = ref<CapabilityRow[]>([])
const capsError = ref('')

// 能力清单全量拉一次，按**当前选中节点的 (contract,tier)** 实时过滤——切节点下拉即时跟随（终审 I-3）。
// 独立评审 Minor：早期实现失败时静默 providers=[]，会让下拉谎报「只有 crash/restart」（§12 不静默）
// ——现在显式把失败原因摆到面板上，用户不会误以为这是该节点的全部能力。
async function loadCapabilities() {
  try {
    providers.value = (await api.capabilities()).providers
    capsError.value = ''
  } catch (e: any) {
    providers.value = []
    capsError.value = `能力元数据加载失败：${String(e?.message ?? e)}`
  }
}
loadCapabilities()

// 终审发现（2026-10-01）：原实现只按 contract 过滤 ⇒ ① worker/real 继承 worker/virtual
// 的 4 个动作，注入必失败（实测 freeze → "component does not implement FaultInjectable"）；
// ② crash/restart 是生命周期动作、不依赖 supportedFaults，因而从不出现 ⇒ G-W2 冒烟
// 第 5 步「注入 crash」在 UI 上根本无路可走。现按 (contract,tier) 精确匹配 + 补生命周期动作。
const actions = computed(() => {
  const n = node.value
  if (!n) return []
  return availableActions({ contract: n.contract, tier: n.tier, sut: n.sut }, providers.value)
})

// 是否向用户索要实例号：仅当该档位组件实现 InstanceControl 且动作非组件级。
// 大小写归一与 inject-logic 同口径（/topology 原样回显 YAML，可能是 `Virtual`）。
const instanceControl = computed(() => {
  const n = node.value
  if (!n) return false
  const eq = (a: string, b: string) => a.trim().toLowerCase() === b.trim().toLowerCase()
  return providers.value.some((p) => eq(p.contract, n.contract) && eq(p.tier, n.tier)
    && p.instanceControl)
})
const askInstance = computed(() => needsInstanceIndex(action.value, instanceControl.value))

// 「撤销注入」只在**该档位确实声明了此动作**时出现（I-10：filestore 的 crash 是可清除的，
// 而 restart 不是——判据是档位声明，不是动作名字）
const clearable = computed(() => {
  const n = node.value
  if (!n || !action.value) return false
  return isClearable(action.value, { contract: n.contract, tier: n.tier }, providers.value)
})

// 动作切换时重置实例号，避免带着上一个动作的残留值提交（终审 I-3 同族问题）
function onActionChange() {
  instance.value = null
  result.value = ''
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
    <p v-if="capsError" class="warn">{{ capsError }}</p>
    <p v-if="node?.sut" class="warn">SUT 节点不可注入（§7.2）</p>
    <template v-else>
      <label>动作
        <select v-model="action" @change="onActionChange">
          <option v-for="a in actions" :key="a" :value="a">
            {{ a }}{{ COMPONENT_SCOPED.has(a) ? '（组件级）' : '' }}
          </option>
        </select>
      </label>
      <label v-if="askInstance">实例号（1 起）
        <input type="number" min="1" v-model.number="instance" />
      </label>
      <div>
        <button :disabled="!action" @click="inject">⚡ 立即注入</button>
        <button v-if="clearable" @click="clear">撤销注入</button>
      </div>
    </template>
    <p v-if="result">{{ result }}</p>
  </aside>
</template>

<style scoped>
.inject { border-left: 1px solid #ccc; padding: 12px; width: 260px; overflow: auto; }
.inject button { margin: 6px 6px 0 0; cursor: pointer; }
.meta { color: #888; font-size: 12px; }
.warn { color: #d9a44a; }
</style>
