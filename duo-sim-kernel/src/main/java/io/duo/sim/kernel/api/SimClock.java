package io.duo.sim.kernel.api;

import java.time.Clock;

/**
 * 仿真时钟（§7.4）。M0 仅真实时钟；加速虚拟时钟留 M4 评估（依赖 SUT 可注入 Clock），
 * 接口上预留。
 */
public interface SimClock {

    Clock clock();

    long nowMillis();

    static SimClock real() {
        return new SimClock() {
            private final Clock clock = Clock.systemUTC();

            @Override
            public Clock clock() {
                return clock;
            }

            @Override
            public long nowMillis() {
                return clock.millis();
            }
        };
    }
}
