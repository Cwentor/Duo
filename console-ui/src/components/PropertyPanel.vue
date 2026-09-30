<script setup lang="ts">
import { ref, watch } from 'vue'
import type { NodeView } from '../model/scenario'

const props = defineProps<{ node: NodeView | null }>()
const emit = defineEmits<{
  update: [patch: Partial<NodeView>]
  remove: []
}>()

const TIER = ['virtual', 'embedded', 'container', 'real']

const draft = ref<{ tier: string; count: number | null; sut: boolean; configText: string }>({
  tier: 'virtual', count: null, sut: false, configText: '',
})

watch(() => props.node?.id, () => {
  // 只在**选中节点切换**时重载草稿——画布全量重投影（改别的节点）不得清空当前编辑（终审 I-5）
  const n = props.node
  if (n) {
    draft.value = {
      tier: n.tier,
      count: n.count,
      sut: n.sut,
      configText: Object.entries(n.config).map(([k, v]) => `${k}: ${v}`).join('\n'),
    }
  }
}, { immediate: true })

function apply() {
  if (!props.node) return
  const config: Record<string, string> = {}
  for (const line of draft.value.configText.split('\n')) {
    const i = line.indexOf(':')
    if (i > 0) config[line.slice(0, i).trim()] = line.slice(i + 1).trim()
  }
  const count = draft.value.count
  emit('update', {
    tier: draft.value.tier,
    count: count == null || count < 1 ? null : count,
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
      <input type="number" min="1" v-model.number="draft.count" />
    </label>
    <label><input type="checkbox" v-model="draft.sut" /> SUT（全场景恰好一个）</label>
    <label>config（每行 `key: value`，外部输入档白名单键才有效）
      <textarea v-model="draft.configText" rows="4" />
    </label>
    <button @click="apply">应用到画布</button>
    <button class="danger" @click="emit('remove')">删除节点</button>
  </aside>
  <aside v-else class="props"><p>选中一个节点查看属性</p></aside>
</template>

<style scoped>
.props { border-left: 1px solid #ccc; padding: 12px; width: 280px; overflow: auto; }
.props label { display: block; margin-bottom: 10px; font-size: 13px; }
.props input, .props select, .props textarea { width: 100%; box-sizing: border-box; }
.props button { margin-right: 6px; cursor: pointer; }
.danger { color: #c0392b; }
.contract { color: #4a90d9; font-size: 12px; }
</style>
