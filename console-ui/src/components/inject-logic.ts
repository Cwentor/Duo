import type { FaultAction } from '../api/client'

/** 组件级动作（计划一取证：freeze/slow/resource-exhaust 内核拒绝实例寻址）。 */
export const COMPONENT_SCOPED = new Set(['freeze', 'slow', 'resource-exhaust'])

/**
 * 生命周期动作：内核 `ScenarioRuntime.dispatch` 对 crash/restart 走
 * `stop/restart(StopMode.CRASH)` 分支，**不查 `supportedFaults`**（`ScenarioValidator`
 * 同口径：`!lifecycle && !supportedFaults.contains(...)`）——故任何非 SUT 节点都能注入。
 *
 * 注意 crash 与 restart 有一处关键差别：`ScenarioRuntime.clear` **不按动作类型分派**，
 * 只要求组件实现 `FaultInjectable` 并调 `fi.clear(action)`——故「能否清除」取决于
 * **该档位是否把此动作声明进 `supportedFaults`**（实测 `VirtualFilestore.supportedFaults()
 * ={crash}` 且其 `clear()` 显式接受 CRASH；`restart` 则无任何实现声明）。
 * 因此本清单只表达「可选动作」，**不表达可否清除**（见 `isClearable`）。
 *
 * 已知边界（有意保留，不在前端硬编码）：容器档 `restart()` 显式抛
 * `UnsupportedOperationException`（换宿主端口会让 wire 永久挂起），该事实
 * **无法用 `CapabilityMetadata` 表达**、按设计下沉为实现层守卫
 * （`ZookeeperContainerRegistry` / `PostgresContainerStore` 类注释）。元数据驱动的下拉
 * 因此拿不到该信号——注入失败会带 reason 显式回给用户（§12 不静默），不在 SPA 里再抄一份
 * 档位名单（那正是本架构要避免的耦合）。
 */
export const LIFECYCLE_ACTIONS = ['crash', 'restart']

/** 能力元数据行（`/api/capabilities` 的 `providers` 元素；只取本模块用到的字段）。 */
export interface CapabilityLike {
  contract: string
  tier: string
  supportedFaults: string[]
  instanceControl?: boolean
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
 * 大小写归一（I-9，独立评审发现）。
 *
 * `/api/capabilities` 显式输出小写（`RestControlServer` 对 contract/tier 做 `toLowerCase`），
 * 但 `/topology` **原样回显 YAML** 里的 `contract`/`tier`（`ScenarioHost` 只做
 * `put("tier", n.tier())`），而 DSL 校验侧大小写不敏感（`Tier.fromYaml`/`Contract.fromYaml`
 * 走 `trim().toUpperCase()`）。所以 `tier: Virtual` 是完全合法的场景，而旧实现用 `===`
 * 比较会匹配不到任何能力行，让下拉**静默**退化成只剩 `[crash, restart]`——正是本模块要修的
 * I-8a 症状以静默形式复发。
 */
function norm(s: string): string {
  return s.trim().toLowerCase()
}

/** 该 (contract,tier) 档位声明的动作（已归一）；无匹配行返回 `[]`。 */
function declaredFaults(node: TargetNodeLike,
                        caps: readonly CapabilityLike[]): string[] {
  return caps
    .filter((p) => norm(p.contract) === norm(node.contract) && norm(p.tier) === norm(node.tier))
    .flatMap((p) => p.supportedFaults.map(norm))
}

/**
 * 该节点在注入面板里可选的动作清单。
 *
 * 三条规则，均以**取证到的事实**为准：
 * 1. SUT 节点恒为空——`ScenarioRuntime.precheck` 先拦 `target must not be SUT`（§7.2）；
 * 2. 生命周期动作 crash/restart 对所有非 SUT 节点可用（不查 `supportedFaults`，见上）；
 * 3. 其余动作＝**同 (contract, tier)** 行的 `supportedFaults` 并集。
 *
 * 生命周期动作排在最前，便于「注入 crash」这条金标准路径一眼可见（G-W2 冒烟第 5 步）。
 *
 * 已知边界（Minor，独立评审记录，不改）：Java `Set` 迭代序随 JVM 变化，故
 * `supportedFaults` 那一段的**尾随顺序**不稳定——调用方不应依赖其内部次序（crash/restart
 * 前缀是稳定的）。
 */
export function availableActions(node: TargetNodeLike,
                                 caps: readonly CapabilityLike[]): string[] {
  if (node.sut) return []
  // 去重：filestore/virtual 把 crash 也声明进了 supportedFaults，直接拼接会出现两个 crash
  return [...new Set([...LIFECYCLE_ACTIONS, ...declaredFaults(node, caps)])]
}

/**
 * 该动作能否被「撤销注入」。
 *
 * 判据＝**该 (contract,tier) 是否把此动作声明进 `supportedFaults`**，而不是动作名字。
 * 依据（独立评审取证，I-10）：`ScenarioRuntime.clear` 不按类型分派，只要求组件实现
 * `FaultInjectable` 并调 `fi.clear(action)`。`VirtualFilestore` 声明 `{crash}` 且其
 * `clear()` 显式接受 CRASH ⇒ 实测 `clear crash` 返回 `{"success":true}`
 * （事件 `sim.filestore-crashed` → `sim.fault-cleared`）；而 `restart` 无任何实现声明，
 * 故恒不可清除。旧实现按动作名一律隐藏 crash 的清除按钮，相对上一版是**功能倒退**
 * （那时 filestore 的 crash 是可清除的）。
 */
export function isClearable(type: string, node: TargetNodeLike,
                            caps: readonly CapabilityLike[]): boolean {
  return declaredFaults(node, caps).includes(norm(type))
}

/**
 * 是否需要向用户索要实例号。
 *
 * 实例号只在组件实现 `InstanceControl`（能力元数据 `instanceControl=true`）时才有意义；
 * 组件级动作恒不带实例号。对无 `instanceControl` 的档位索要实例号，等于引导用户必然失败
 * （内核 `asInstanceControl` 会抛 `no instance control`）。
 */
export function needsInstanceIndex(type: string, instanceControl: boolean): boolean {
  return instanceControl && !COMPONENT_SCOPED.has(norm(type))
}

export function buildFaultAction(type: string, nodeId: string,
                                 instanceIndex: number | null): FaultAction {
  const componentId = { value: nodeId }
  return COMPONENT_SCOPED.has(norm(type))
    ? { type, target: { componentId }, params: {} }
    : { type, target: { componentId, instanceIndex }, params: {} }
}
