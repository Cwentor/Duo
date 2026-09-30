import type { FaultAction } from '../api/client'

/** 组件级动作（计划一取证：freeze/slow/resource-exhaust 内核拒绝实例寻址）。 */
export const COMPONENT_SCOPED = new Set(['freeze', 'slow', 'resource-exhaust'])

export function buildFaultAction(type: string, nodeId: string,
                                 instanceIndex: number | null): FaultAction {
  const componentId = { value: nodeId }
  return COMPONENT_SCOPED.has(type)
    ? { type, target: { componentId }, params: {} }
    : { type, target: { componentId, instanceIndex }, params: {} }
}
