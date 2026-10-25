/**
 * worker 侧参考实现族：{@code DemoRealWorker}（kernel-hosted 的 real 档 worker，非 SUT——
 * 复用 TaskStub 行为模型，换档后同一 behaviors bindings 仍生效）、{@code RealWorkerSut}
 * （worker 侧 SUT——被调度方协议对端：发现 → 拨号注册 → 心跳 → 领取/执行/回报 → 断连自愈，
 * 不做任何调度判定）、{@code SutConfigs}（示例共享的 config 读取口径，含 UTF-8 BOM 剥离——
 * 「用户写了配置却被静默吞掉」的实测防御）。
 */
package io.duo.sim.examples.worker;
