package io.duo.sim.scenario;

import io.duo.sim.kernel.api.CapabilityMetadata;
import io.duo.sim.kernel.api.ComponentId;
import io.duo.sim.kernel.api.Contract;
import io.duo.sim.kernel.api.HealthReport;
import io.duo.sim.kernel.api.StopMode;
import io.duo.sim.kernel.api.Tier;
import io.duo.sim.kernel.api.VirtualComponent;
import io.duo.sim.kernel.core.ContractRegistry;
import io.duo.sim.kernel.spi.ComponentProvider;
import io.duo.sim.scenario.model.Scenario;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ScenarioValidatorTest {

    // ---- 测试替身（kernel-api 可见）----

    static class RealStub implements VirtualComponent,
            io.duo.sim.kernel.api.FaultInjectable {
        @Override public ComponentId id() {
            return new ComponentId("stub");
        }
        @Override public void init(io.duo.sim.kernel.api.ComponentContext ctx) { }
        @Override public void start() { }
        @Override public void stop(StopMode mode) { }
        @Override public void restart() { }
        @Override public HealthReport health() { return HealthReport.ok(); }
        @Override public List<io.duo.sim.kernel.api.ExposedEndpoint> endpoints() {
            return List.of();
        }

        @Override public void inject(io.duo.sim.kernel.api.FaultAction a) { }
        @Override public void clear(io.duo.sim.kernel.api.FaultAction a) { }
    }

    static ComponentProvider provider(Contract c, Tier t, String name, boolean def,
                                      CapabilityMetadata m) {
        return new ComponentProvider() {
            @Override public Contract contract() {
                return c;
            }
            @Override public Tier tier() {
                return t;
            }
            @Override public String implName() {
                return name;
            }
            @Override public boolean isDefault() {
                return def;
            }
            @Override public CapabilityMetadata metadata() {
                return m;
            }
            @Override public VirtualComponent newComponent() {
                return new RealStub();
            }
        };
    }

    private static ContractRegistry registry() {
        var r = new ContractRegistry();
        r.register(provider(Contract.REGISTRY, Tier.VIRTUAL, "reg", true,
                CapabilityMetadata.inProcessDirect(Set.of())));
        r.register(provider(Contract.WORKER, Tier.VIRTUAL, "vw", true,
                // 元数据 instanceControl=false：stub 不实现 InstanceControl（§7.5 一致性）
                CapabilityMetadata.duoPort(false, Set.of())));
        r.register(provider(Contract.SCHEDULER, Tier.REAL, "ds", true,
                CapabilityMetadata.duoPort(false, Set.of())));
        r.validateDefaults();
        return r;
    }

    private static Scenario.NodeSpec node(String id, String contract, String tier, boolean sut,
                                          Map<String, Scenario.WiringSpec> wiring) {
        return new Scenario.NodeSpec(id, contract, tier, sut, null, Map.of(), List.of(),
                wiring == null ? Map.of() : wiring, null, Map.of(), null);
    }

    private Scenario scenario(List<Scenario.NodeSpec> nodes) {
        return new Scenario("test", nodes, new Scenario.Behaviors(Map.of(), List.of()),
                List.of(), List.of());
    }

    // ---- 规则 5 ----

    @Test
    void rule5ExactlyOneSut() {
        var v = new ScenarioValidator(registry());
        var r1 = v.validate(scenario(List.of(
                node("zk", "registry", "virtual", false, null))));
        assertFalse(r1.ok());
        assertTrue(String.join(";", r1.errors()).contains("exactly one node"));
        var r2 = v.validate(scenario(List.of(
                node("zk", "registry", "virtual", false, null),
                node("m", "scheduler", "real", true, null),
                node("m2", "scheduler", "real", true, null))));
        assertTrue(String.join(";", r2.errors()).contains("found 2"));
    }

    // ---- 规则 1/2/3 ----

    @Test
    void interactiveContractRejectsEmbeddedTier() {
        var v = new ScenarioValidator(registry());
        var r = v.validate(scenario(List.of(
                node("m", "scheduler", "real", true, null),
                node("w", "worker", "embedded", false, null))));
        assertTrue(String.join(";", r.errors()).contains("no EMBEDDED implementation form"));
    }

    @Test
    void shorthandSlotContractMismatchFails() {
        var v = new ScenarioValidator(registry());
        var r = v.validate(scenario(List.of(
                node("m", "scheduler", "real", true,
                        Map.of("registry", new Scenario.WiringSpec("zk", null, null))),
                node("zk", "worker", "virtual", false, null))));
        assertTrue(String.join(";", r.errors()).contains("shorthand"));
    }

    @Test
    void directToEndpointTargetWarnsViaInference() {
        // workers(VIRTUAL, DUO_PORT) → wire；消费方 master(REAL) in-process → wire 合法
        var v = new ScenarioValidator(registry());
        var r = v.validate(scenario(List.of(
                node("m", "scheduler", "real", true,
                        Map.of("workers", new Scenario.WiringSpec("w", "worker", null))),
                node("w", "worker", "virtual", false, null))));
        assertTrue(r.ok(), () -> String.join(";", r.errors()));
    }

    @Test
    void directFromExternalConsumerFails() {
        var v = new ScenarioValidator(registry());
        var ext = new Scenario.NodeSpec("m", "scheduler", "real", true,
                new Scenario.Launch("external", null, "build/x.properties"),
                Map.of("ready.type", "tcp"), List.of(),
                Map.of("registry", new Scenario.WiringSpec("zk", "registry", "direct")),
                null, Map.of(), null);
        var r = v.validate(scenario(List.of(
                ext, node("zk", "registry", "virtual", false, null))));
        assertTrue(String.join(";", r.errors()).contains("interface-direct"));
    }

    // ---- 规则 4 ----

    @Test
    void externalNeedsExplicitPortAndReady() {
        var v = new ScenarioValidator(registry());
        var ext = new Scenario.NodeSpec("m", "scheduler", "real", true,
                new Scenario.Launch("external", null, "build/x.properties"),
                Map.of(), // 无 ready
                List.of(new Scenario.ExposeSpec("scheduler", 0, "127.0.0.1")),
                Map.of(), null, Map.of(), null);
        var r = v.validate(scenario(List.of(ext)));
        var all = String.join(";", r.errors());
        assertTrue(all.contains("must declare an explicit port"));
        assertTrue(all.contains("ready probe"));
    }

    // ---- 规则 4（M6：configOut / 探针声明在启动前校验）----

    @Test
    void externalRequiresConfigOut() {
        var v = new ScenarioValidator(registry());
        int port = freePort();
        var ext = new Scenario.NodeSpec("m", "scheduler", "real", true,
                new Scenario.Launch("external", null, null), // 缺 configOut
                Map.of("ready.type", "tcp", "ready.port", String.valueOf(port)),
                List.of(new Scenario.ExposeSpec("scheduler", port, "127.0.0.1")),
                Map.of(), null, Map.of(), null);
        var r = v.validate(scenario(List.of(ext)));
        assertTrue(String.join(";", r.errors()).contains("launch.configOut"),
                () -> String.join(";", r.errors()));
    }

    @Test
    void externalRejectsUnknownProbeType() {
        var v = new ScenarioValidator(registry());
        int port = freePort();
        var ext = new Scenario.NodeSpec("m", "scheduler", "real", true,
                new Scenario.Launch("external", null, "build/x.properties"),
                Map.of("ready.type", "udp", "ready.port", String.valueOf(port)),
                List.of(new Scenario.ExposeSpec("scheduler", port, "127.0.0.1")),
                Map.of(), null, Map.of(), null);
        var r = v.validate(scenario(List.of(ext)));
        assertTrue(String.join(";", r.errors()).contains("unsupported ready.type"),
                () -> String.join(";", r.errors()));
    }

    @Test
    void externalProbeWithoutPortOrExposesFails() {
        var v = new ScenarioValidator(registry());
        var ext = new Scenario.NodeSpec("m", "scheduler", "real", true,
                new Scenario.Launch("external", null, "build/x.properties"),
                Map.of("ready.type", "tcp"),
                List.of(), Map.of(), null, Map.of(), null);
        var r = v.validate(scenario(List.of(ext)));
        assertTrue(String.join(";", r.errors()).contains("needs a port"),
                () -> String.join(";", r.errors()));
    }

    @Test
    void externalProbeWithBadTimeoutUnitFails() {
        var v = new ScenarioValidator(registry());
        int port = freePort();
        var ext = new Scenario.NodeSpec("m", "scheduler", "real", true,
                new Scenario.Launch("external", null, "build/x.properties", "${java} -jar s.jar"),
                Map.of("ready.type", "http", "ready.port", String.valueOf(port),
                        "ready.timeout", "30"), // 无单位（Durations 拒绝）
                List.of(new Scenario.ExposeSpec("scheduler", port, "127.0.0.1")),
                Map.of(), null, Map.of(), null);
        var r = v.validate(scenario(List.of(ext)));
        assertTrue(String.join(";", r.errors()).contains("lacks a unit"),
                () -> String.join(";", r.errors()));
    }

    @Test
    void wellFormedExternalNodePasses() {
        // external SUT 消费 wire 面（worker 有端点）＋ 显式端口 ＋ configOut ＋ 探针声明
        var v = new ScenarioValidator(registry());
        int port = freePort();
        var ext = new Scenario.NodeSpec("m", "scheduler", "real", true,
                new Scenario.Launch("external", null, "build/x.properties",
                        "${java} -jar third-party.jar"),
                Map.of("ready.type", "tcp", "ready.port", String.valueOf(port),
                        "ready.timeout", "30s"),
                List.of(new Scenario.ExposeSpec("scheduler", port, "127.0.0.1")),
                Map.of("worker", new Scenario.WiringSpec("w", "worker", null)),
                null, Map.of(), null);
        var r = v.validate(scenario(List.of(ext, node("w", "worker", "virtual", false, null))));
        assertTrue(r.ok(), () -> String.join(";", r.errors()));
    }

    /** 取一个当前空闲端口（规则 8 会检查固定端口占用）。 */
    private static int freePort() {
        try (java.net.ServerSocket s = new java.net.ServerSocket(0)) {
            return s.getLocalPort();
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
    }

    // ---- 规则 6 ----

    @Test
    void timelineSutTargetRejectedExceptCustomHook() {
        var v = new ScenarioValidator(registry());
        var tl = new Scenario("t",
                List.of(node("m", "scheduler", "real", true, null)),
                new Scenario.Behaviors(Map.of(), List.of()),
                List.of(new Scenario.TimelineEntry("5s", "crash", "m", null, Map.of()),
                        new Scenario.TimelineEntry("6s", "custom-hook", "m", null, Map.of())),
                List.of());
        var r = v.validate(tl);
        assertEquals(1, r.errors().stream().filter(e -> e.contains("is SUT")).count());
    }

    @Test
    void timelineIndexOutOfRangeRejected() {
        var v = new ScenarioValidator(registry());
        var wNode = new Scenario.NodeSpec("w", "worker", "virtual", false, null, Map.of(),
                List.of(), Map.of(), 3, Map.of(), null);
        var tl = new Scenario("t",
                List.of(node("m", "scheduler", "real", true, null), wNode),
                new Scenario.Behaviors(Map.of(), List.of()),
                List.of(new Scenario.TimelineEntry("5s", "crash", "w[4]", null, Map.of()),
                        new Scenario.TimelineEntry("6s", "crash", "w[3]", null, Map.of())),
                List.of());
        var r = v.validate(tl);
        assertEquals(1, r.errors().stream().filter(e -> e.contains("out of")).count());
    }

    // ---- 规则 7 ----

    @Test
    void unboundNamedProfileRejected() {
        var v = new ScenarioValidator(registry());
        var s = new Scenario("t",
                List.of(node("m", "scheduler", "real", true, null)),
                new Scenario.Behaviors(Map.of("named-x", Map.of("duration", "3s")), List.of()),
                List.of(), List.of());
        var r = v.validate(s);
        assertTrue(String.join(";", r.errors()).contains("no binding"));
    }

    // ---- M2 验收 MEDIUM：embedded flap 的 duration 语义不对称警告 ----

    @Test
    void embeddedFlapWithDurationProducesWarning() {
        // embedded 档 flap 是瞬时 restart（duration 被忽略）→ 显式警告，不静默
        var reg = new ContractRegistry();
        reg.register(provider(Contract.REGISTRY, Tier.EMBEDDED, "curator-reg", true,
                new io.duo.sim.kernel.api.CapabilityMetadata(
                        io.duo.sim.kernel.api.EndpointShape.THIRD_PARTY, true, false,
                        java.util.Set.of(io.duo.sim.kernel.api.FaultAction.REGISTRY_FLAP),
                        true)));
        reg.register(provider(Contract.SCHEDULER, Tier.REAL, "ds", true,
                io.duo.sim.kernel.api.CapabilityMetadata.duoPort(false, java.util.Set.of())));
        reg.validateDefaults();
        var embeddedZk = new Scenario.NodeSpec("zk", "registry", "embedded", false, null,
                Map.of(), List.of(), Map.of(), null, Map.of(), null);
        var scenario = new Scenario("t",
                List.of(embeddedZk, node("m", "scheduler", "real", true, null)),
                new Scenario.Behaviors(Map.of(), List.of()),
                List.of(new Scenario.TimelineEntry("10s", "registry-flap", "zk", "5s", Map.of())),
                List.of());
        var r = new ScenarioValidator(reg).validate(scenario);
        assertTrue(r.ok(), () -> "should warn not error: " + r.errors());
        assertTrue(r.warnings().stream().anyMatch(w -> w.contains("duration")
                        && w.contains("embedded")),
                () -> "expected embedded-duration warning, got: " + r.warnings());
    }

    @Test
    void virtualFlapWithDurationProducesNoWarning() {
        // virtual 档 flap 有持续窗口 → duration 有效，无警告
        var v = new ScenarioValidator(registry());
        var scenario = new Scenario("t",
                List.of(node("zk", "registry", "virtual", false, null),
                        node("m", "scheduler", "real", true, null)),
                new Scenario.Behaviors(Map.of(), List.of()),
                List.of(new Scenario.TimelineEntry("10s", "registry-flap", "zk", "5s", Map.of())),
                List.of());
        var r = v.validate(scenario);
        assertTrue(r.warnings().stream().noneMatch(w -> w.contains("embedded")),
                () -> "virtual tier must not warn: " + r.warnings());
    }

    // ---- M0 assertions 警告 ----

    @Test
    void validAssertionsSectionPassesValidation() {
        // M1 T20：assertions 从"M0 忽略警告"升级为解析校验（合法断言通过）
        var v = new ScenarioValidator(registry());
        var s = new Scenario("t",
                List.of(node("m", "scheduler", "real", true, null)),
                new Scenario.Behaviors(Map.of(), List.of()),
                List.of(), List.of(Map.of("noTaskLost", Map.of("requireAllSuccess", true))));
        var r = v.validate(s);
        assertTrue(r.ok(), r.errors().toString());
        assertTrue(r.warnings().isEmpty());
    }

    @Test
    void unknownAssertionFailsValidation() {
        var v = new ScenarioValidator(registry());
        var s = new Scenario("t",
                List.of(node("m", "scheduler", "real", true, null)),
                new Scenario.Behaviors(Map.of(), List.of()),
                List.of(), List.of(Map.of("bogusAssertion", Map.of())));
        var r = v.validate(s);
        assertFalse(r.ok());
        assertTrue(String.join(";", r.errors()).contains("unknown assertion"));
    }
}
