/**
 * 控制面内核适配层（M3 T32）：{@link ScenarioHost} 持有 {@code ScenarioEngine} 生命周期，
 * 向 REST / CLI 暴露统一操作面（启动/停止/状态/事件增量/注入转发/结果/拓扑——§10 约束：
 * 零内核改动，只用既有公开 API）；{@link FaultDiagnostics} 把一次注入重建成四段可判定的
 * 完整因果链（注入→组件反应→SUT 事实→断言）；{@link FaultLog} 是事件流的人读镜像
 * （稳定可 grep 的 {@code io.duo.sim.fault} logger，与事件流同源同序）。
 *
 * <p>REST 端点与管道在 {@code control.rest}，CLI 在 {@code control.cli}，
 * 场景库与指标在 {@code control.library} / {@code control.metrics}。
 */
package io.duo.sim.control;
