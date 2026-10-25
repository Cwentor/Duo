/**
 * virtual 档全部组件的 {@code ComponentProvider} 注册面（键＝(contract, tier)，经 ServiceLoader
 * 加载），以及 worker/engine 共用的行为剧本解析 {@link BehaviorResolver}（四级优先级：
 * 精确任务名 &gt; 标签 &gt; 通配 pattern &gt; default）。
 *
 * <p>能力元数据（endpointShape / interfaceDirect / supportedFaults 等）在各 Provider 的静态声明里——
 * 启动前校验只读这些声明，不触碰实例；声明与实现接口的对应由 ContractRegistry 强制（§7.5）。
 */
package io.duo.sim.components.provider;
