/**
 * 指标导出（M8 交付物 1，决策 D4＝零依赖手写，不引 Prometheus 客户端）：
 * {@link MetricsCollector}——从事件流与组件状态**读数**的薄层（§10 零内核改动的受益者），
 * 输出 Prometheus 文本格式；{@code _total} 计数器按「已消费事件下标游标」单向推进
 * （不丢、不重、单调）。18 个指标族的口径与已知边界逐条见 docs/METRICS.md。
 */
package io.duo.sim.control.metrics;
