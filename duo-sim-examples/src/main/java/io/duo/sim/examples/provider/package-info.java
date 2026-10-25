/**
 * examples 的档位注册面：{@code DemoSchedulerProvider}（(SCHEDULER, REAL)）与
 * {@code DemoRealWorkerProvider}（(WORKER, REAL)）。demo-scheduler 是 SUT——不走
 * VirtualComponent 生命周期（内核不实例化它，ScenarioEngine.startSut() 负责），
 * Provider 声明为「适配性 VirtualComponent」仅为向契约注册表提供元数据（§8 规则 1）。
 */
package io.duo.sim.examples.provider;
