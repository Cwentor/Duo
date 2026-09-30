import { parseDocument, YAMLMap, YAMLSeq, isMap, isSeq, Document } from 'yaml'

export interface NodeView {
  id: string
  contract: string
  tier: string
  sut: boolean
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
 * （规格 §5.5「注释保留尽力而为」的实现形态）；画布重排提示由 UI 层负责。
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
    const root = (this.doc.toJS() ?? {}) as any
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
    if (!isSeq(topology)) {
      // 空/缺失 topology：从零建立列表（新场景骨架起步用）
      this.doc.setIn(['topology'], this.doc.createNode([node]))
      return
    }
    ;(topology as YAMLSeq).add(this.doc.createNode({ ...node }))
  }

  removeNode(id: string) {
    const topology = this.doc.getIn(['topology']) as YAMLSeq | undefined
    if (!isSeq(topology)) return
    const idx = topology.items.findIndex((it) => isMap(it) && (it as YAMLMap).get('id') === id)
    if (idx >= 0) topology.delete(idx)
    // 清悬挂 wiring：任何节点指向被删节点的接线一并移除（§12 精神：不留隐性坏引用）
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

  /** 把 from 节点的 contract 槽接线连到 to 节点（DSL wiring 形态：`{node, contract}`）。 */
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
