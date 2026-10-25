import { parseDocument, YAMLMap, YAMLSeq, isMap, isSeq, Document } from 'yaml'

/** 拓扑节点在画布/属性面板上的投影（只读视图——事实源是 YAML 文本）。 */
export interface NodeView {
  /** 节点 id（拓扑内唯一）。 */
  id: string
  /** 角色契约。 */
  contract: string
  /** 实现档位。 */
  tier: string
  /** 是否 SUT（场景内恰好一个）。 */
  sut: boolean
  /** 声明实例数（null＝YAML 未写 count，生效值由服务端定缺省）。 */
  count: number | null
  /** 节点 config 键值（launch.ready.* 已被加载器展平进来）。 */
  config: Record<string, string>
  /** 接线槽：槽名 → {目标节点, 契约}（path 不建模——由服务端校验器推断/检查）。 */
  wiring: Record<string, { node: string; contract: string }>
  /** launch.mode（in-process / external；非 SUT 节点为 null）。 */
  launchMode: string | null
  /** launch.main（in-process SUT 的主类）。 */
  launchMain: string | null
}

/** 场景概览视图：拓扑已投影为节点；behaviors/timeline/assertions 保留原始节的文本形态。 */
export interface ScenarioView {
  /** 场景名。 */
  name: string
  nodes: NodeView[]
  /** 以下为对应 YAML 节的 JSON 串（不投影成结构——编辑往返不丢信息）。 */
  behaviorsText?: string
  timelineText?: string
  assertionsText?: string
}

/** 解析失败（problems 携带 yaml 库的全部错误，UI 逐条展示）。 */
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

  /** 解析 YAML 文本（有错误即抛 ScenarioParseError，不产生半成品实例）。 */
  static parse(text: string): ScenarioDoc {
    const doc = parseDocument(text)
    const problems = doc.errors.map((e) => String(e.message ?? e))
    if (problems.length > 0) throw new ScenarioParseError(problems)
    return new ScenarioDoc(doc)
  }

  /** 全量投影：YAML → {name, nodes, 原始节文本}（每次变更后重算）。 */
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

  /** 追加节点骨架（id/contract/tier；其余字段留给属性面板补）。 */
  addNode(node: { id: string; contract: string; tier: string }) {
    const topology = this.doc.getIn(['topology'])
    if (!isSeq(topology)) {
      // 空/缺失 topology：从零建立列表（新场景骨架起步用）
      this.doc.setIn(['topology'], this.doc.createNode([node]))
      return
    }
    ;(topology as YAMLSeq).add(this.doc.createNode({ ...node }))
  }

  /** 删除节点，并连带移除所有指向它的接线。 */
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

  /** 字段级补丁（tier/sut/count/config；写「假值/空」即删键，保持 YAML 干净）。 */
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

  /** 移除一槽接线。 */
  disconnect(fromNodeId: string, contract: string) {
    const path = this.nodePath(fromNodeId)
    if (path == null) return
    this.doc.deleteIn([...path, 'wiring', contract])
  }

  /** 序列化回 YAML 文本（未改动子树的注释保留——CST 文档的卖点）。 */
  toString(): string {
    return String(this.doc)
  }

  /** 同 toString（语义别名：取原始文本）。 */
  rawText(): string {
    return String(this.doc)
  }

  /** 定位节点在 Document 中的路径（['topology', i]；找不到返回 null）。 */
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

/** 把 YAML 值转 Record（可选对每个值套 map；非对象输入落空表）。 */
function recordOf<T = string>(v: any, map?: (x: any) => T): Record<string, T> {
  const out: Record<string, T> = {}
  if (v && typeof v === 'object') {
    for (const [k, val] of Object.entries(v)) out[k] = (map ? map(val) : val) as T
  }
  return out
}
