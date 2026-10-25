package io.duo.sim.kernel.api;

import java.time.Instant;
import java.util.Map;
import java.util.Set;

/**
 * 框架事件（§7.4）。type 以 {@code sim.} 前缀（框架事件）或 {@code sut.} 前缀（SUT 相关事实）命名。
 *
 * @param type 事件类型；前缀即命名空间（由 {@link #sim}/{@link #sut} 工厂守卫，不靠调用方自觉）
 * @param sourceId 发布方标识（组件 id；SUT 经 SutContext 发布的统一为 {@code "sut"}）
 * @param timestamp 发布时刻（工厂取 {@code Instant.now()}）
 * @param payload 载荷键值；可为 null（REST 投影落为空 Map，见 {@link EventProjections}）
 */
public record Event(String type, String sourceId, Instant timestamp, Map<String, Object> payload) {

    /** 框架事件命名空间。 */
    public static final String SIM_PREFIX = "sim.";
    /** SUT 相关事实命名空间。 */
    public static final String SUT_PREFIX = "sut.";

    /** 框架事件类型清单（M0 子集 + M1 T16 的 fault-cleared）。 */
    public static final Set<String> SIM_EVENT_TYPES = Set.of(
            "sim.scenario-started",
            "sim.scenario-finished",
            "sim.component-started",
            "sim.component-stopped",
            "sim.component-crashed",
            "sim.fault-injected",
            "sim.registry-node-changed",
            "sim.fault-cleared",
            "sim.registry-flap-started",
            "sim.registry-flap-cleared",
            "sim.fault-inject-failed",
            "sim.sut-exited",
            "sim.sut-crashed",
            // M6：external SUT 生命周期与端点告知（§7.3；内核探测，非 SUT 事实）
            "sim.external-process-started",
            "sim.external-endpoint",
            "sim.external-sut-ready",
            "sim.external-process-left-running");

    /** 构造框架事件（type 非 {@code sim.} 前缀即拒绝）。 */
    public static Event sim(String type, String sourceId, Map<String, Object> payload) {
        if (!type.startsWith(SIM_PREFIX)) {
            throw new IllegalArgumentException("framework events must use sim. prefix: " + type);
        }
        return new Event(type, sourceId, Instant.now(), payload);
    }

    /** 构造 SUT 事实（type 非 {@code sut.} 前缀即拒绝）。 */
    public static Event sut(String type, String sourceId, Map<String, Object> payload) {
        if (!type.startsWith(SUT_PREFIX)) {
            throw new IllegalArgumentException("SUT facts must use sut. prefix: " + type);
        }
        return new Event(type, sourceId, Instant.now(), payload);
    }
}
