package io.duo.sim.control;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

/**
 * 故障注入的**人可读因果链**（M8 交付物 3）。
 *
 * <p>为什么单独一个 logger 名（{@code io.duo.sim.fault}）而不是混在控制面日志里：
 * 一次注入的因果链要从几千行日志里捞得出来，必须有一个**稳定可 grep 的入口**。
 * 事件流（events.jsonl）是机器事实源，本类是它的人读镜像——两者**同源同序**：
 * 都在 {@code ScenarioRuntime} 判定之后调用，不额外推测语义。
 *
 * <p>失败必须记录（§12 不静默）：注入失败在事件流里是 {@code sim.fault-inject-failed}，
 * 在日志里就是 {@link #failed} 这一行，两条通道都能定位到同一个原因字符串。
 */
public final class FaultLog {

    private static final Logger LOG = LoggerFactory.getLogger("io.duo.sim.fault");

    private FaultLog() {
    }

    /** 注入已下达（对应事件 {@code sim.fault-injected}）。 */
    public static void injected(String componentId, Integer instanceIndex, String action) {
        LOG.info("FAULT inject action={} target={}{}",
                action, componentId, instanceIndex == null ? "" : ("[" + instanceIndex + "]"));
    }

    /** 注入被拒绝（对应事件 {@code sim.fault-inject-failed}），reason 来自内核。 */
    public static void failed(String componentId, String action, String reason) {
        LOG.warn("FAULT reject action={} target={} reason={}",
                action, componentId, reason == null ? "<none>" : reason);
    }

    /** 注入被清除（带 duration 的动作到期）。 */
    public static void cleared(String componentId, String action) {
        LOG.info("FAULT clear action={} target={}", action, componentId);
    }

    /** 场景生命周期（便于把注入放进"哪个场景的第几秒"）。 */
    public static void scenario(String phase, Map<String, Object> detail) {
        LOG.info("FAULT scenario {} {}", phase, detail == null ? Map.of() : detail);
    }
}
