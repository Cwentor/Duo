package io.duo.sim.kernel.api;

import java.util.Map;

/**
 * 组件健康快照（§7.1 health()）。
 *
 * @param healthy 是否健康
 * @param detail 人可读说明（不健康时＝原因）
 * @param metrics 附加度量（可空）
 */
public record HealthReport(boolean healthy, String detail, Map<String, Object> metrics) {

    /** 健康快照（detail=ok、无指标）。 */
    public static HealthReport ok() {
        return new HealthReport(true, "ok", Map.of());
    }

    /** 不健康快照，detail 给人可读原因。 */
    public static HealthReport down(String detail) {
        return new HealthReport(false, detail, Map.of());
    }
}
