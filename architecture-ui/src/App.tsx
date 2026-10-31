import { useCallback, useEffect, useMemo, useState, type MouseEvent as ReactMouseEvent } from 'react'
import {
  Background,
  BackgroundVariant,
  Controls,
  Handle,
  MiniMap,
  NodeToolbar,
  Position,
  ReactFlow,
  ReactFlowProvider,
  useEdgesState,
  useNodesState,
  useReactFlow,
  useStore,
  type Edge,
  type Node,
  type NodeProps,
} from '@xyflow/react'
import '@xyflow/react/dist/style.css'
import {
  EDGE_KINDS,
  initialEdges,
  initialNodes,
  MODULES,
  type CardData,
  type EdgeRef,
  type ModuleKey,
} from './graph'

type AppNode = Node<CardData, 'card'> | Node<Record<string, unknown>, 'section'>

const FOCUS_ZOOM = 1.15

/** 箭头图例：一段线 + 实心箭头，颜色与真实边一致。 */
function ArrowGlyph({ color, dash }: { color: string; dash?: string }) {
  return (
    <svg width="30" height="10" viewBox="0 0 30 10" aria-hidden>
      <line
        x1="1" y1="5" x2="22" y2="5"
        stroke={color} strokeWidth="2" strokeDasharray={dash}
      />
      <path d="M22 1 L29 5 L22 9 Z" fill={color} />
    </svg>
  )
}

/** 卡片节点：标题 + 模块色签 + 关键类；聚焦时挂 NodeToolbar 显示 passport 卡。 */
function CardNode({ id, data, selected }: NodeProps<AppNode>) {
  const d = data as CardData
  const m = MODULES[d.module]
  const focused = !!d.focused
  const dimmed = !!d.dimmed

  return (
    <>
      <NodeToolbar nodeId={id} isVisible={focused} position={Position.Left} offset={18}>
        <Passport nodeId={id} d={d} />
      </NodeToolbar>

      <div
        className="arch-card"
        data-sut={d.sut ? 'true' : undefined}
        data-focused={focused ? 'true' : undefined}
        data-dimmed={dimmed ? 'true' : undefined}
        style={{
          background: m.bg,
          borderColor: focused || selected ? '#0f172a' : m.border,
        }}
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
    </>
  )
}

/** 语义护照：选中节点的职责、上下游连线、关键类与文档入口。 */
function Passport({ nodeId, d }: { nodeId: string; d: CardData }) {
  const m = MODULES[d.module]
  const incoming = d.incoming ?? []
  const outgoing = d.outgoing ?? []
  const [copied, setCopied] = useState(false)

  const copyLink = useCallback(async () => {
    const url = `${location.origin}${location.pathname}#${nodeId}`
    try {
      await navigator.clipboard.writeText(url)
      setCopied(true)
      window.setTimeout(() => setCopied(false), 1400)
    } catch {
      setCopied(false)
    }
  }, [nodeId])

  const row = (e: EdgeRef, dir: 'in' | 'out') => (
    <li key={e.id}>
      <span className="arch-passport-arrow" style={{ color: EDGE_KINDS[e.kind].stroke }}>
        {dir === 'in' ? '←' : '→'}
      </span>
      <span className="arch-passport-edge-target">{e.title}</span>
      {e.label && e.label !== e.title && (
        <span className="arch-passport-edge-label">{e.label}</span>
      )}
    </li>
  )

  return (
    <div className="arch-passport" style={{ borderColor: m.border }}>
      <div className="arch-passport-head">
        <span className="arch-passport-kicker">SEMANTIC PASSPORT</span>
        <button className="arch-passport-copy" onClick={copyLink}>
          {copied ? '已复制' : 'Copy link'}
        </button>
      </div>
      <div className="arch-passport-title" style={{ color: m.text }}>{d.title}</div>
      <div className="arch-passport-chips">
        <span className="arch-passport-chip" style={{ background: m.border }}>{m.label}</span>
        {d.sut && <span className="arch-passport-chip arch-passport-chip-sut">被测系统</span>}
      </div>

      <p className="arch-passport-desc">{d.desc}</p>

      <div className="arch-passport-counts">
        <span>{incoming.length} incoming</span>
        <span>{outgoing.length} outgoing</span>
      </div>

      {incoming.length > 0 && (
        <div className="arch-passport-group">
          <h5>INCOMING · {incoming.length}</h5>
          <ul>{incoming.map((e) => row(e, 'in'))}</ul>
        </div>
      )}
      {outgoing.length > 0 && (
        <div className="arch-passport-group">
          <h5>OUTGOING · {outgoing.length}</h5>
          <ul>{outgoing.map((e) => row(e, 'out'))}</ul>
        </div>
      )}

      <div className="arch-passport-classes">
        <h5>关键类</h5>
        <code>{d.classes.join('  ·  ')}</code>
      </div>

      {d.doc && (
        <a className="arch-passport-doc" href={d.doc.url} target="_blank" rel="noreferrer">
          {d.doc.label} ↗
        </a>
      )}
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
  const [focusId, setFocusId] = useState<string | null>(null)
  const [nodes, , onNodesChange] = useNodesState<AppNode>(initialNodes as AppNode[])
  const [edges, , onEdgesChange] = useEdgesState(initialEdges)
  const { setCenter, getZoom } = useReactFlow()
  // 整图缩放下边标签只有 6px 高，纯噪声；放大到能读时才显示（0.05 量化，避免平移时狂重渲染）。
  const zoom = useStore((s) => Math.round(s.transform[2] * 20) / 20)
  const showEdgeLabels = zoom >= 0.75

  /** 点击节点：视口把该节点拉到画面中央并放大，同时打开 passport。 */
  const focusNode = useCallback(
    (node: Node) => {
      setFocusId(node.id)
      const w = node.measured?.width ?? 210
      const h = node.measured?.height ?? 84
      // 节点居中偏右：左侧留出 passport 卡的位置。
      const zoom = Math.max(FOCUS_ZOOM, getZoom())
      setCenter(node.position.x + w / 2 - 170 / zoom, node.position.y + h / 2, {
        zoom,
        duration: 420,
      })
    },
    [setCenter, getZoom],
  )

  const onNodeClick = useCallback(
    (_: ReactMouseEvent, node: Node) => {
      if (node.type !== 'card') return
      focusNode(node)
    },
    [focusNode],
  )

  const clearFocus = useCallback(() => setFocusId(null), [])

  // Esc 退出聚焦；URL hash 可直接定位到某个节点（passport 里的 Copy link 生成它）。
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') clearFocus()
    }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [clearFocus])

  useEffect(() => {
    const id = decodeURIComponent(location.hash.replace(/^#/, ''))
    if (!id) return
    const t = window.setTimeout(() => {
      const n = initialNodes.find((x) => x.id === id)
      if (n) focusNode(n as Node)
    }, 200)
    return () => window.clearTimeout(t)
  }, [focusNode])

  const { viewNodes, viewEdges } = useMemo<{ viewNodes: AppNode[]; viewEdges: Edge[] }>(() => {
    if (!focusId) {
      return {
        viewNodes: nodes.map((n) => ({
          ...n,
          data: { ...n.data, dimmed: false, focused: false },
        })) as AppNode[],
        viewEdges: edges.map((e) => ({
          ...e,
          label: showEdgeLabels ? e.label : undefined,
          labelStyle: { fontSize: 11, fill: '#334155', fontWeight: 600 },
          labelBgStyle: { fill: '#ffffff', fillOpacity: 0.94, stroke: '#e2e8f0' },
          labelBgPadding: [6, 3] as [number, number],
          labelBgBorderRadius: 5,
        })),
      }
    }

    const titleOf = (id: string) =>
      String((initialNodes.find((n) => n.id === id)?.data as CardData | undefined)?.title ?? id)
    const ref = (e: Edge): EdgeRef => ({
      id: e.id,
      label: String(e.label ?? ''),
      title: '',
      kind: ((e.data as { kind?: string } | undefined)?.kind ?? 'dep') as EdgeRef['kind'],
    })

    const incoming = edges.filter((e) => e.target === focusId)
    const outgoing = edges.filter((e) => e.source === focusId)
    const related = new Set<string>([focusId])
    incoming.forEach((e) => related.add(e.source))
    outgoing.forEach((e) => related.add(e.target))

    return {
      viewNodes: nodes.map((n) => {
        const isFocus = n.id === focusId
        return {
          ...n,
          zIndex: isFocus ? 20 : undefined,
          data: {
            ...n.data,
            focused: isFocus,
            dimmed: !related.has(n.id) && n.type === 'card',
            incoming: isFocus
              ? incoming.map((e) => ({ ...ref(e), title: titleOf(e.source) }))
              : undefined,
            outgoing: isFocus
              ? outgoing.map((e) => ({ ...ref(e), title: titleOf(e.target) }))
              : undefined,
          },
        }
      }) as AppNode[],
      viewEdges: edges.map((e) => {
        const on = e.source === focusId || e.target === focusId
        return {
          ...e,
          label: showEdgeLabels && on ? e.label : undefined,
          style: { ...e.style, opacity: on ? 1 : 0.07 },
          labelStyle: { fontSize: 11, fill: '#334155', fontWeight: 600 },
          labelBgStyle: { fill: '#ffffff', fillOpacity: 0.94, stroke: '#e2e8f0' },
          labelBgPadding: [6, 3] as [number, number],
          labelBgBorderRadius: 5,
          zIndex: on ? 2 : 0,
        }
      }),
    }
  }, [nodes, edges, focusId, showEdgeLabels])

  return (
    <div className="arch-root">
      <header className="arch-header">
        <h1>Duo 架构图</h1>
        <p>
          <b>点击节点聚焦</b>（放大 + 语义护照 + 上下游高亮） · <kbd>Esc</kbd> 或点空白退出 ·
          拖拽平移 / 滚轮缩放
        </p>
        <a href="https://github.com/Cwentor/Duo" target="_blank" rel="noreferrer">
          github.com/Cwentor/Duo ↗
        </a>
      </header>

      <div className="arch-canvas">
        <ReactFlow
          nodes={viewNodes}
          edges={viewEdges}
          onNodesChange={onNodesChange}
          onEdgesChange={onEdgesChange}
          nodeTypes={nodeTypes}
          onNodeClick={onNodeClick}
          onPaneClick={clearFocus}
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
          <div className="arch-legend-block">
            <span className="arch-legend-head">模块</span>
            {LEGEND.map((k) => (
              <span key={k} className="arch-legend-item">
                <i style={{ background: MODULES[k].border }} />
                {MODULES[k].label}
              </span>
            ))}
          </div>
          <div className="arch-legend-block arch-legend-block-edge">
            <span className="arch-legend-head">连线（箭头＝流向）</span>
            {(Object.keys(EDGE_KINDS) as (keyof typeof EDGE_KINDS)[]).map((k) => (
              <span key={k} className="arch-legend-item" title={EDGE_KINDS[k].hint}>
                <ArrowGlyph color={EDGE_KINDS[k].stroke} dash={EDGE_KINDS[k].dash} />
                {EDGE_KINDS[k].label}
              </span>
            ))}
          </div>
        </aside>
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
