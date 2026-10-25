/** fork 缺省命名：{source}-copy，撞名追加 -2/-3…（与 REST 缺省语义互补的前端体验层）。 */
export function forkTargetId(sourceId: string, existingIds: string[]): string {
  let candidate = `${sourceId}-copy`
  for (let i = 2; existingIds.includes(candidate); i++) {
    candidate = `${sourceId}-copy-${i}`
  }
  return candidate
}
