import { describe, expect, it } from 'vitest'
import {
  availableActions, buildFaultAction, COMPONENT_SCOPED, LIFECYCLE_ACTIONS, needsInstanceIndex,
  isClearable,
} from './inject-logic'

/** 取证形状＝`GET /api/capabilities` 的 `providers` 子集（计划一实测，2026-10-01）。 */
const CAPS = [
  { contract: 'worker', tier: 'virtual', supportedFaults: ['freeze', 'task-kill', 'resource-exhaust', 'slow'], instanceControl: true },
  { contract: 'worker', tier: 'real', supportedFaults: [], instanceControl: true },
  { contract: 'scheduler', tier: 'virtual', supportedFaults: ['freeze'], instanceControl: false },
  { contract: 'registry', tier: 'virtual', supportedFaults: ['registry-flap'], instanceControl: false },
  { contract: 'registry', tier: 'container', supportedFaults: [], instanceControl: false },
  // 实测这一行把 `crash` 也声明进了 supportedFaults（与生命周期动作重叠）——去重路径的
  // 唯一真实夹具，缺了它下面「没有重复 crash」的断言会因为命中「未知 (contract,tier)」
  // 分支而**因错误的理由通过**。
  { contract: 'filestore', tier: 'virtual', supportedFaults: ['crash'], instanceControl: false },
]

describe('buildFaultAction', () => {
  it('组件级动作（freeze/slow/resource-exhaust）不带 instanceIndex', () => {
    const f = buildFaultAction('freeze', 'workers', 3)
    expect(f.target).toEqual({ componentId: { value: 'workers' } })
    expect(f.type).toBe('freeze')
  })
  it('实例级动作（crash/restart/task-kill）带 instanceIndex', () => {
    const f = buildFaultAction('crash', 'workers', 3)
    expect(f.target.instanceIndex).toBe(3)
  })
  it('COMPONENT_SCOPED 清单与计划一实测一致', () => {
    expect([...COMPONENT_SCOPED].sort()).toEqual(['freeze', 'resource-exhaust', 'slow'])
  })
})

// ---- 终审发现（2026-10-01 实测取证，详见 ledger Ruling）----
describe('availableActions', () => {
  it('worker/virtual 节点：生命周期动作 + 该档位 supportedFaults', () => {
    expect(availableActions({ contract: 'worker', tier: 'virtual' }, CAPS))
      .toEqual(['crash', 'restart', 'freeze', 'task-kill', 'resource-exhaust', 'slow'])
  })

  it('crash/restart 是生命周期动作，不依赖 supportedFaults 声明（G-W2 冒烟第 5 步的唯一通路）', () => {
    // 取证：worker/real 的 supportedFaults=∅，但内核 dispatch 对 crash/restart 走
    // stop/restart 生命周期分支（ScenarioRuntime.dispatch），实测注入成功。
    expect(availableActions({ contract: 'worker', tier: 'real' }, CAPS)).toEqual(['crash', 'restart'])
  })

  it('按 (contract,tier) 精确匹配——worker/real 不得继承 worker/virtual 的动作', () => {
    const a = availableActions({ contract: 'worker', tier: 'real' }, CAPS)
    expect(a).not.toContain('freeze')
    expect(a).not.toContain('task-kill')
    expect(a).not.toContain('slow')
  })

  it('SUT 节点不提供任何注入动作（§7.2 target 不得为 SUT）', () => {
    expect(availableActions({ contract: 'scheduler', tier: 'virtual', sut: true }, CAPS)).toEqual([])
  })

  it('registry/container（supportedFaults=∅）只剩生命周期动作', () => {
    expect(availableActions({ contract: 'registry', tier: 'container' }, CAPS)).toEqual(['crash', 'restart'])
  })

  it('filestore/virtual 把 crash 也声明进 supportedFaults 时不出现重复项', () => {
    const a = availableActions({ contract: 'filestore', tier: 'virtual' }, CAPS)
    expect(a).toEqual(['crash', 'restart'])
    expect(a.filter((x) => x === 'crash')).toHaveLength(1)
  })

  it('未知 (contract,tier) 不返回任何档位动作，不误给别的档位', () => {
    // 用一个**夹具里不存在**的组合，确保走的是「无匹配行」分支，而非碰巧命中某行的空 supportedFaults
    expect(availableActions({ contract: 'message', tier: 'container' }, CAPS)).toEqual(['crash', 'restart'])
  })

  it('LIFECYCLE_ACTIONS 即 crash/restart', () => {
    expect(LIFECYCLE_ACTIONS).toEqual(['crash', 'restart'])
  })
})

describe('needsInstanceIndex', () => {
  it('instanceControl=true 且非组件级动作 ⇒ 需要实例号', () => {
    expect(needsInstanceIndex('crash', true)).toBe(true)
    expect(needsInstanceIndex('task-kill', true)).toBe(true)
  })
  it('组件级动作永不问实例号', () => {
    expect(needsInstanceIndex('freeze', true)).toBe(false)
  })
  it('无 instanceControl 的档位不问实例号（问了必然失败）', () => {
    expect(needsInstanceIndex('registry-flap', false)).toBe(false)
    expect(needsInstanceIndex('crash', false)).toBe(false)
  })
})

// ---- 独立评审发现（2026-10-01，I-9/I-10）----

describe('availableActions 大小写归一（I-9）', () => {
  // /api/capabilities 输出小写（RestControlServer 显式 toLowerCase），而 /topology 原样回显
  // YAML（ScenarioHost 只做 put("tier", n.tier())），但 DSL 校验用 equalsIgnoreCase 接受任意大小写
  // ——实测 `tier: Virtual` 的场景：内核接受 freeze/slow/resource-exhaust，而 UI 下拉只剩
  // [crash,restart]，I-8a 的症状以「静默」形式复发。
  it('tier 写成 Virtual（YAML 原样回显）也要匹配到 virtual 的能力', () => {
    expect(availableActions({ contract: 'worker', tier: 'Virtual' }, CAPS))
      .toEqual(availableActions({ contract: 'worker', tier: 'virtual' }, CAPS))
  })
  it('contract 写成 Worker 同样匹配', () => {
    expect(availableActions({ contract: 'Worker', tier: 'VIRTUAL' }, CAPS))
      .toEqual(availableActions({ contract: 'worker', tier: 'virtual' }, CAPS))
  })
  it('能力行一侧的大小写也归一并匹配', () => {
    const mixed = [{ contract: 'Worker', tier: 'Virtual', supportedFaults: ['freeze'], instanceControl: false }]
    expect(availableActions({ contract: 'worker', tier: 'virtual' }, mixed))
      .toEqual(['crash', 'restart', 'freeze'])
  })
  it('两侧大小写都不同也能匹配', () => {
    const mixed = [{ contract: 'FILESTORE', tier: 'Virtual', supportedFaults: ['crash'], instanceControl: false }]
    expect(availableActions({ contract: 'filestore', tier: 'virtual' }, mixed))
      .toEqual(['crash', 'restart'])
  })
})

describe('isClearable（I-10）', () => {
  // 评审取证：ScenarioRuntime.clear 不按动作类型分派，只调 fi.clear(action)；VirtualFilestore
  // 实现 FaultInjectable 且 supportedFaults()={crash}，其 clear() 显式接受 CRASH——实测
  // `clear crash` 于 filestore 返回 {"success":true}（sim.filestore-crashed → sim.fault-cleared）。
  // 故「crash/restart 一律不可清除」是错的：判据应是**该档位是否把此动作声明进 supportedFaults**。
  it('filestore/virtual 声明了 crash ⇒ 可清除（原实现一律隐藏 = 功能倒退）', () => {
    expect(isClearable('crash', { contract: 'filestore', tier: 'virtual' }, CAPS)).toBe(true)
  })
  it('worker/virtual 未声明 crash（生命周期动作）⇒ 不可清除', () => {
    expect(isClearable('crash', { contract: 'worker', tier: 'virtual' }, CAPS)).toBe(false)
  })
  it('restart 从不被声明 ⇒ 任何档位都不可清除', () => {
    expect(isClearable('restart', { contract: 'worker', tier: 'virtual' }, CAPS)).toBe(false)
    expect(isClearable('restart', { contract: 'filestore', tier: 'virtual' }, CAPS)).toBe(false)
  })
  it('普通组件级动作只要该档位声明了就等够清除', () => {
    expect(isClearable('freeze', { contract: 'worker', tier: 'virtual' }, CAPS)).toBe(true)
  })
  it('档位没声明的动作不可清除', () => {
    expect(isClearable('freeze', { contract: 'worker', tier: 'real' }, CAPS)).toBe(false)
  })
  it('大小写归一后仍可判定（复用 I-9 的归一口径）', () => {
    expect(isClearable('crash', { contract: 'Filestore', tier: 'VIRTUAL' }, CAPS)).toBe(true)
  })
})
