/**
 * TaskStub 行为模型（设计文档 §9）：{@link BehaviorProfile}（M1 八字段全集——duration/jitter/
 * successRate/exception/logLines + failAt/neverReport/progress）与 {@link TaskBehaviors}
 * （剧本装配工厂，执行时长是调用方唯一覆写字段）。它是任务执行的**行为模型**而非独立组件：
 * 被 VirtualWorker / VirtualEngine / DemoRealWorker 复用——换档后同一 behaviors bindings 仍生效
 * 是 M0 换档验收成立的关键。
 */
package io.duo.sim.components.taskstub;
