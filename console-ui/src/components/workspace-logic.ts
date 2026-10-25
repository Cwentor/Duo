// 工作区纯函数助手：布局坐标与草稿，分别按场景 id 作键存 localStorage（不随会话/刷新丢失）。

/** 布局坐标的 localStorage 键（场景 id 作用域）。 */
export const layoutKey = (id: string) => `duo-layout-${id}`
/** 未保存草稿（YAML 文本）的 localStorage 键（规格 §10：401/刷新不丢）。 */
export const draftKey = (id: string) => `duo-draft-${id}`

/** 节点画布坐标。 */
export interface NodePos {
  x: number
  y: number
}

/** 读布局（损坏 JSON 容错为空表——布局丢失不该挡住编辑）。 */
export function loadLayout(id: string): Record<string, NodePos> {
  try {
    return JSON.parse(localStorage.getItem(layoutKey(id)) ?? '{}')
  } catch {
    return {}
  }
}

/** 写布局（整表覆盖；坐标只影响展示，不入 YAML——规格 §5.6）。 */
export function saveLayout(id: string, pos: Record<string, NodePos>) {
  localStorage.setItem(layoutKey(id), JSON.stringify(pos))
}
