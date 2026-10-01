import type { FaultAction } from '../api/client'

/** 组件级动作（计划一取证：freeze/slow/resource-exhaust 内核拒绝实例寻址）。 */
export const COMPONENT_SCOPED = new Set(['freeze', 'slow', 'resource-exhaust'])

/**
 * 生命周期动作：内核 `ScenarioRuntime.dispatch` 对 crash/restart 走
 * `stop/restart(StopMode.CRASH)` 分支，**不查 `supportedFaults`**（`ScenarioValidator`
 * 同口径：`!lifecycle && !supportedFaults.contains(...)`）——故任何非 SUT 节点都能注入。
 * 二者**不可"清除"**（生命周期不是 FaultInjectable 状态，`ScenarioRuntime.clear` 注释在案）。
 *
 * 已知边界（有意保留，不在前端硬编码）：容器档 `restart()` 显式抛
 * `UnsupportedOperationException`（换宿主端口会让 wire 永久挂起），该事实
 * **无法用 `CapabilityMetadata` 表达**、按设计下沉为实现层守卫
 * （`ZookeeperContainerRegistry` / `PostgresContainerStore` 类注释）。元数据驱动的下拉
 * 因此拿不到该信号——注入失败会带 reason 显式回给用户（§12 不静默），不在 SPA 里再抄一份
 * 档位名单（那正是本架构要避免的耦合）。
 */
export const LIFECYCLE_ACTIONS = ['crash', 'restart']

/** 该动作能否被「撤销注入」——生命周期动作不可清除，给了按钮必然失败。 */
export function isClearable(type: string): boolean {
  return !LIFECYCLE_ACTIONS.includes(type)
}

/** 能力元数据行（`/api/capabilities` 的 `providers` 元素；只取本模块用到的字段）。 */
export interface CapabilityLike {
  contract: string
  tier: string
  supportedFaults: string[]
}

/**
 * 目标节点形状（`/topology` 节点子集）。
 *
 * 为什么按 **(contract, tier)** 精确匹配而不是只按 contract：同一契约的不同档位能力不同
 * ——实测 `worker/virtual` 声明 4 个动作而 `worker/real` 声明 0 个；只按 contract 过滤会把
 * virtual 的动作错误地摆到 real 节点上，注入必然失败（终审发现，2026-10-01 实测取证）。
 */
export interface TargetNodeLike {
  contract: string
  tier: string
  sut?: boolean
}

/**
 * 该节点在注入面板里可选的动作清单。
 *
 * 三条规则，均以**取证到的事实**为准：
 * 1. SUT 节点恒为空——`ScenarioRuntime.precheck` 先拦 `target must not be SUT`（§7.2）；
 * 2. 生命周期动作 crash/restart 对所有非 SUT 节点可用（不查 `supportedFaults`，见上）；
 * 3. 其余动作＝**同 (contract, tier)** 行的 `supportedFaults` 并集。
 *
 * 当前 UI 只选一个节点、一个动作，故并集排序稳定即可（生命周期在前，便于「注入 crash」
 * 这条金标准路径一眼可见——G-W2 冒烟第 5 步）。
 */
export function availableActions(node: TargetNodeLike,
                                 caps: readonly CapabilityLike[]): string[] {
  if (node.sut) return []
  const tierFaults = caps
    .filter((p) => p.contract === node.contract && p.tier === node.tier)
    .flatMap((p) => p.supportedFaults)
  // 去重：filestore/virtual 把 crash 也声明进了 supportedFaults，直接拼接会出现两个 crash
  return [...new Set([...LIFECYCLE_ACTIONS, ...tierFaults])]
}

/**
 * 是否需要向用户索要实例号。
 *
 * 实例号只在组件实现 `InstanceControl`（能力元数据 `instanceControl=true`）时才有意义；
 * 组件级动作恒不带实例号。对无 `instanceControl` 的档位索要实例号，等于引导用户必然失败
 * （内核 `asInstanceControl` 会抛 `no instance control`）。
 */
export function needsInstanceIndex(type: string, instanceControl: boolean): boolean {
  return instanceControl && !COMPONENT_SCOPED.has(type)
}

export function buildFaultAction(type: string, nodeId: string,
                                 instanceIndex: number | null): FaultAction {
  const componentId = { value: nodeId }
  return COMPONENT_SCOPED.has(type)
    ? { type, target: { componentId }, params: {} }
    : { type, target: { componentId, instanceIndex }, params: {} }
}
