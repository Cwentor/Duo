export const layoutKey = (id: string) => `duo-layout-${id}`
export const draftKey = (id: string) => `duo-draft-${id}`

export interface NodePos {
  x: number
  y: number
}

export function loadLayout(id: string): Record<string, NodePos> {
  try {
    return JSON.parse(localStorage.getItem(layoutKey(id)) ?? '{}')
  } catch {
    return {}
  }
}

export function saveLayout(id: string, pos: Record<string, NodePos>) {
  localStorage.setItem(layoutKey(id), JSON.stringify(pos))
}
