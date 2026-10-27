import { useCallback, useMemo, useState, type MouseEvent as ReactMouseEvent } from 'react'
import {
  Background,
  BackgroundVariant,
  Controls,
  Handle,
  MiniMap,
  Position,
  ReactFlow,
  ReactFlowProvider,
  useEdgesState,
  useNodesState,
  type Edge,
  type Node,
  type NodeProps,
} from '@xyflow/react'
import '@xyflow/react/dist/style.css'
import { initialEdges, initialNodes, MODULES, type CardData, type ModuleKey } from './graph'

type AppNode = Node<CardData, 'card'> | Node<Record<string, unknown>, 'section'>

/** 卡片节点：标题 + 模块色签 + 关键类两行；四向句柄供不同走向的边使用。 */
function CardNode({ data, selected }: NodeProps<AppNode>) {
  const d = data as CardData
  const m = MODULES[d.module]
  return (
    <div
      className="arch-card"
      style={{
        background: m.bg,
        borderColor: selected ? '#0f172a' : m.border,
        boxShadow: selected ? '0 0 0 2px #0f172a33' : '0 1px 3px #0f172a1a',
      }}
      data-sut={d.sut ? 'true' : undefined}
    >
      <Handle type="target" position={Position.Left} id="l" />
      <Handle type="target" position={Position.Top} id="t" />
      <Handle type="source" position={Position.Right} id="r" />
      <Handle type="source" position={Position.Bottom} id="b" />
      <div className="arch-card-title" style={{ color: m.text }}>
        {d.sut && <span className="arch-sut-badge">SUT</span>}
        {d.title}
      </div>
      <div className="arch-card-tag" style={{ background: m.border }}>
        {m.label}
      </div>
      <ul className="arch-card-classes">
        {d.classes.map((c) => (
          <li key={c}>{c}</li>
        ))}
      </ul>
    </div>
  )
}

/** 分组区块：虚线圆角框 + 左上角标签，仅作视觉分区，不参与连线。 */
function SectionNode({ data }: NodeProps<AppNode>) {
  return (
    <div className="arch-section">
      <span className="arch-section-label">{(data as { label?: string }).label}</span>
    </div>
  )
}

const nodeTypes = { card: CardNode, section: SectionNode }

const LEGEND: ModuleKey[] = [
  'scenario', 'kernel', 'contract', 'protocol',
  'virtual', 'embedded', 'container', 'real',
  'control', 'web', 'obs', 'junit',
]

function Canvas() {
  const [selected, setSelected] = useState<Node<CardData> | null>(null)
  // 非受控内部状态 + 官方 change 回调：节点可拖拽、可选中（受控无 onNodesChange 时选中事件永远不回写）。
  const [nodes, , onNodesChange] = useNodesState<AppNode>(initialNodes as AppNode[])
  const [edges, , onEdgesChange] = useEdgesState(initialEdges)

  const onNodeClick = useCallback((_: ReactMouseEvent, node: Node) => {
    setSelected(node.type === 'card' ? (node as Node<CardData>) : null)
  }, [])
  const onPaneClick = useCallback(() => setSelected(null), [])

  const edgeList: Edge[] = useMemo(
    () =>
      edges.map((e) => ({
        ...e,
        labelStyle: { fontSize: 10, fill: '#475569' },
        labelBgStyle: { fill: '#f8fafcdd' },
      })),
    [edges],
  )

  return (
    <div className="arch-root">
      <header className="arch-header">
        <h1>Duo 架构图</h1>
        <p>
          通用可组合虚拟大数据仿真系统 · 灰线＝依赖/装配 · 青色虚线＝事件流（唯一事实来源） ·
          金线＝SUT 生命周期 · <b>点击节点查看职责与关键类</b> · 拖拽平移 / 滚轮缩放
        </p>
        <a href="https://github.com/Cwentor/Duo" target="_blank" rel="noreferrer">
          github.com/Cwentor/Duo ↗
        </a>
      </header>

      <div className="arch-canvas">
        <ReactFlow
          nodes={nodes}
          edges={edgeList}
          onNodesChange={onNodesChange}
          onEdgesChange={onEdgesChange}
          nodeTypes={nodeTypes}
          onNodeClick={onNodeClick}
          onPaneClick={onPaneClick}
          zoomOnDoubleClick={false}
          fitView
          fitViewOptions={{ padding: 0.08, maxZoom: 1 }}
          minZoom={0.2}
          proOptions={{ hideAttribution: true }}
        >
          <Background variant={BackgroundVariant.Dots} gap={18} size={1.4} color="#cbd5e1" />
          <MiniMap
            pannable
            zoomable
            bgColor="#f1f5f9"
            maskColor="#0f172a14"
            nodeColor={(n) => MODULES[(n.data as CardData | undefined)?.module ?? 'input'].border}
            nodeStrokeColor={(n) => MODULES[(n.data as CardData | undefined)?.module ?? 'input'].text}
          />
          <Controls position="bottom-right" showInteractive={false} />
        </ReactFlow>

        <aside className="arch-legend">
          {LEGEND.map((k) => (
            <span key={k} className="arch-legend-item">
              <i style={{ background: MODULES[k].border }} />
              {MODULES[k].label}
            </span>
          ))}
        </aside>

        {selected && (
          <aside className="arch-panel">
            <button className="arch-panel-close" onClick={() => setSelected(null)} aria-label="关闭">
              ×
            </button>
            <div
              className="arch-panel-title"
              style={{ color: MODULES[selected.data.module].text }}
            >
              {selected.data.title}
            </div>
            <div
              className="arch-panel-module"
              style={{ background: MODULES[selected.data.module].bg, color: MODULES[selected.data.module].text }}
            >
              {MODULES[selected.data.module].label}
              {selected.data.sut ? ' · SUT' : ''}
            </div>
            <p className="arch-panel-desc">{selected.data.desc}</p>
            <div className="arch-panel-classes">
              <h4>关键类</h4>
              <code>{selected.data.classes.join('  ·  ')}</code>
            </div>
            {selected.data.doc && (
              <a href={selected.data.doc.url} target="_blank" rel="noreferrer">
                📄 {selected.data.doc.label} ↗
              </a>
            )}
          </aside>
        )}
      </div>
    </div>
  )
}

export default function App() {
  return (
    <ReactFlowProvider>
      <Canvas />
    </ReactFlowProvider>
  )
}
