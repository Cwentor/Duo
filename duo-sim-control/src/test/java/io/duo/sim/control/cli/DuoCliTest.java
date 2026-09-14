package io.duo.sim.control.cli;

import io.duo.sim.control.ScenarioHost;
import io.duo.sim.kernel.api.Event;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** T34 CLI 单测：参数解析、同进程 inject 空态与 attached 注入。 */
class DuoCliTest {

    private static final String FAST = """
            name: cli-smoke
            topology:
              - id: zk
                contract: registry
                tier: virtual
              - id: master
                contract: scheduler
                tier: real
                sut: true
                launch: { mode: in-process, main: io.duo.sim.examples.scheduler.DemoScheduler }
                config: { dag.tasks: "a" }
                exposes: [{ contract: scheduler, port: 0 }]
                wiring:
                  registry: { node: zk, contract: registry }
              - id: workers
                contract: worker
                tier: virtual
                count: 1
                capacity: { slots: 1 }
                wiring:
                  registry: { node: zk, contract: registry }
            behaviors:
              profiles:
                default: { duration: 10s, jitter: 0.0, successRate: 1.0 }
              bindings:
                - node: workers
                  profile: default
            timeline: []
            assertions: []
            """;

    @Test
    void helpReturnsZero() {
        assertEquals(0, DuoCli.run("help"));
        assertEquals(0, DuoCli.run());
    }

    @Test
    void unknownCommandReturnsOne() {
        assertEquals(1, DuoCli.run("bogus"));
    }

    @Test
    void injectWithoutAttachedScenarioFailsNotSilent() {
        assertEquals(1, DuoCli.run("inject", "crash", "workers[1]"));
    }

    @Test
    void runKeepThenInjectThenDetach() throws Exception {
        Path f = Files.createTempFile("duo-cli", ".yaml");
        Files.writeString(f, FAST, StandardCharsets.UTF_8);

        // run --keep：注册到进程级注册表（不 --wait，场景保持在途）
        assertEquals(0, DuoCli.run("run", f.toString(), "--keep"));
        assertNotNull(ScenarioHost.attached("default"));

        // 同进程注入：crash workers[1]
        int rc = DuoCli.run("inject", "crash", "workers[1]");
        assertEquals(0, rc, "injection must succeed on attached scenario");

        // status 可读（RUNNING 或已推进）
        assertEquals(0, DuoCli.run("status"));

        // 清理：detach 并停止
        ScenarioHost attached = ScenarioHost.attached("default");
        attached.stop();
        attached.detach("default");
        assertEquals(1, DuoCli.run("inject", "crash", "workers[1]"),
                "after detach, inject must fail (no attached scenario)");
    }

    @Test
    void eventsOnIdleHostIsEmpty() {
        assertEquals(0, DuoCli.run("events"));
    }

    @Test
    void assertOnIdleHostFailsWithExitOne() {
        // 空态 host 的 passed=false → 退出码 1（断言库口径一致）
        assertEquals(1, DuoCli.run("assert"));
    }

    private static void assertNotNull(Object o) {
        if (o == null) {
            throw new AssertionError("expected non-null");
        }
    }
}
