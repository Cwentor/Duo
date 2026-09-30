<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { VueFlow, type Connection, type Edge, type EdgeMouseEvent, type Node } from '@vue-flow/core'
import '@vue-flow/core/dist/style.css'
import '@vue-flow/core/dist/theme-default.css'
import type { ScenarioView } from '../model/scenario'
import { loadLayout, saveLayout, type NodePos } from './workspace-logic'

const props = defineProps<{ view: ScenarioView; scenarioId: string;
                            faults?: Record<string, number[]> }>()
const emit = defineEmits<{
  connect: [fromId: string, contract: string, toId: string]
  disconnect: [fromId: string, contract: string]
  select: [id: string]
}>()

// 布局坐标只存 localStorage（规格 §5.6）：改位置不触发 YAML 变更
const layout: Record<string, NodePos> = loadLayout(props.scenarioId)

const nodes = ref<Node[]>([])

function rebuildNodes() {
  const ids = new Set(props.view.nodes.map((n) => n.id))
  nodes.value = props.view.nodes.map((n, i) => {
    // 运行态状态灯：实例故障数来自事件推导（终审 I-4——规格 §4「N/M 实例故障」）
    const faultCount = props.faults?.[n.id]?.length ?? 0
    const faultLine = faultCount > 0 ? `\n⚠ ${faultCount}/${n.count ?? 1} 实例故障` : ''
    return {
      id: n.id,
      position: layout[n.id] ?? { x: 80 + (i % 3) * 220, y: 60 + Math.floor(i / 3) * 120 },
      data: { label: `${n.id}${n.sut ? ' ★SUT' : ''}\n${n.contract}/${n.tier}${faultLine}` },
    }
  })
  // 清理已删节点的残留布局
  for (const k of Object.keys(layout)) {
    if (!ids.has(k)) delete layout[k]
  }
}

watch(() => [props.view, props.faults], rebuildNodes, { immediate: true, deep: true })

const edges = computed<Edge[]>(() =>
  props.view.nodes.flatMap((n) =>
    Object.entries(n.wiring).map(([slot, w]) => ({
      id: `${n.id}::${slot}::${w.node}`,
      source: n.id,
      target: w.node,
      label: slot,
      style: { stroke: '#888' },
    }))))

function onConnect(c: Connection) {
  // 连线语义：给 source 节点的「target 契约」槽接线（wiring: {node, contract}）
  const targetNode = props.view.nodes.find((n) => n.id === c.target)
  if (targetNode) emit('connect', c.source!, targetNode.contract, c.target!)
}

function onEdgeDoubleClick(e: EdgeMouseEvent) {
  // 断线：edge 数据永远来自 view（wiring），从 id 三段式反查槽名
  const parts = e.edge.id.split('::')
  if (parts.length === 3) emit('disconnect', parts[0], parts[1])
}

function onNodeDragStop(_e: unknown, node: Node) {
  layout[node.id] = { ...node.position }
  saveLayout(props.scenarioId, layout)
}
</script>

<template>
  <div class="canvas-host">
    <VueFlow
      :nodes="nodes"
      :edges="edges"
      fit-view-on-init
      @connect="onConnect"
      @edge-double-click="onEdgeDoubleClick"
      @node-drag-stop="onNodeDragStop"
      @node-click="(e: any) => emit('select', e.node.id)"
    />
  </div>
</template>

<style scoped>
.canvas-host { width: 100%; height: 100%; }
</style>
