package io.duo.sim.scenario;

/**
 * 带单位的时长解析（M1 T16/T23）——委托 {@link io.duo.sim.kernel.util.Durations}
 * （kernel 承载共用实现，components 与 scenario 同源）。
 */
public final class Durations {

    private Durations() {
    }

    public static long parseMillis(String text) {
        return io.duo.sim.kernel.util.Durations.parseMillis(text);
    }
}
