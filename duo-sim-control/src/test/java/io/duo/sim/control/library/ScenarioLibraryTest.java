package io.duo.sim.control.library;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.NoSuchFileException;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** M10 计划一 Task 3：场景库存储层——id 白名单、模板只读、fork 语义、外部输入档校验。 */
class ScenarioLibraryTest {

    private static final String HELLO_YAML = """
            name: my-drill
            topology:
              - id: zk
                contract: registry
                tier: virtual
              - id: master
                contract: scheduler
                tier: virtual
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
                default: { duration: 3s, jitter: 0.0, successRate: 1.0 }
              bindings:
                - node: workers
                  profile: default
            timeline: []
            assertions:
              - noTaskLost: { requireAllSuccess: true }
            """;

    /** assertThrows(Executable) 不接受受检异常：包一层转 unchecked（异常类型仍由 assertThrows 断言）。 */
    private interface IoCall {
        void run() throws Exception;
    }

    private static org.junit.jupiter.api.function.Executable quiet(IoCall call) {
        return () -> {
            try {
                call.run();
            } catch (RuntimeException e) {
                throw e; // 保持 assertThrows 的异常类型断言不被包装破坏
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        };
    }

    @Test
    void validIdRejectsTraversalAndFancyCharacters(@TempDir Path dir) {
        assertFalse(ScenarioLibrary.validId("../etc/passwd"));
        assertFalse(ScenarioLibrary.validId("a/b"));
        assertFalse(ScenarioLibrary.validId("a\\b"));
        assertFalse(ScenarioLibrary.validId(""));
        assertFalse(ScenarioLibrary.validId("a b"));
        assertFalse(ScenarioLibrary.validId("场景一"));
        assertTrue(ScenarioLibrary.validId("my-drill-01"));
        assertTrue(ScenarioLibrary.validId("m3_inject.demo"));
    }

    @Test
    void templatesAreReadOnlyAndListed(@TempDir Path dir) throws Exception {
        var lib = new ScenarioLibrary(dir);
        var entries = lib.list();
        assertTrue(entries.stream().anyMatch(e -> e.template()
                && e.id().equals("worker-crash-failover")), "template from index.txt must be listed");
        assertThrows(IllegalStateException.class,
                quiet(() -> lib.put("worker-crash-failover", HELLO_YAML)), "template is read-only");
        assertThrows(IllegalStateException.class,
                quiet(() -> lib.delete("worker-crash-failover")), "template cannot be deleted");
    }

    @Test
    void putGetDeleteRoundTrip(@TempDir Path dir) throws Exception {
        var lib = new ScenarioLibrary(dir);
        lib.put("my-drill-01", HELLO_YAML);
        var c = lib.get("my-drill-01");
        assertFalse(c.template());
        assertEquals(HELLO_YAML, c.yaml());
        lib.delete("my-drill-01");
        assertThrows(NoSuchFileException.class, () -> lib.get("my-drill-01"));
    }

    @Test
    void forkCopiesIntoUserNamespace(@TempDir Path dir) throws Exception {
        var lib = new ScenarioLibrary(dir);
        String newId = lib.fork("worker-crash-failover", "my-fork");
        assertEquals("my-fork", newId);
        assertTrue(lib.get("my-fork").yaml().contains("name:"));
        assertThrows(IllegalStateException.class,
                quiet(() -> lib.fork("worker-crash-failover", "my-fork")),
                "fork onto existing id must fail loudly");
    }

    @Test
    void validateYamlCatchesSchemaErrorsAndExternalInputViolations(@TempDir Path dir) throws Exception {
        var lib = new ScenarioLibrary(dir);
        assertTrue(lib.validateYaml(HELLO_YAML).isEmpty(), "well-formed scenario must validate");
        assertFalse(lib.validateYaml("name: [broken").isEmpty(), "parse error must be reported");
        assertFalse(lib.validateYaml("""
                name: bad
                topology:
                  - id: m
                    contract: scheduler
                    tier: real
                    sut: true
                    launch: { mode: external, command: "calc.exe" }
                timeline: []
                assertions: []
                """).isEmpty(), "external-input profile must reject launch.command");
    }
}
