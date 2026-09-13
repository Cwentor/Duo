package io.duo.sim.kernel.api;

import java.util.Map;

/** 组件健康快照（§7.1 health()）。 */
public record HealthReport(boolean healthy, String detail, Map<String, Object> metrics) {

    public static HealthReport ok() {
        return new HealthReport(true, "ok", Map.of());
    }

    public static HealthReport down(String detail) {
        return new HealthReport(false, detail, Map.of());
    }
}
