package io.duo.sim.scenario;

import io.duo.sim.scenario.model.Scenario;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * DSL 解析用例（M5/G5）：节点级 {@code ready} 兼容别名、profile 列表字段归一。
 * 这两处此前是「只能写一种形态」或「写了取值被污染」的断链（DSL §1.4 与 §8 偏差 3）。
 */
class ScenarioLoaderTest {

    private static final String HEAD = """
            name: loader-probe
            topology:
              - id: workers
                contract: worker
                tier: virtual
                count: 1
              - id: m
                contract: message
                tier: external
                exposes:
                  - { contract: message, port: 19092 }
            """;

    private static Scenario load(String yaml) {
        return ScenarioLoader.load(new ByteArrayInputStream(
                yaml.getBytes(StandardCharsets.UTF_8)));
    }

    /** 把追加片段整体缩进为 `m` 节点的字段（4 空格），避免测试自身写错层级。 */
    private static Scenario loadNodeFields(String extra) {
        String indented = extra.lines()
                .map(l -> l.isBlank() ? l : "    " + l)
                .collect(Collectors.joining("\n"));
        return load(HEAD + indented + "\n");
    }

    private static Scenario.NodeSpec node(Scenario s, String id) {
        return s.nodes().stream().filter(n -> n.id().equals(id)).findFirst().orElseThrow();
    }

    @Test
    void autoStartDefaultsToTrueAndCanBeTurnedOff() {
        // G11：不写 autoStart ⇒ 与既有场景语义一致（声明即启动）
        assertTrue(node(load(HEAD), "workers").autoStart(), "缺省必须为 true（既有场景零改动）");
        assertFalse(node(loadNodeFields("autoStart: false"), "m").autoStart(),
                "autoStart: false 必须被解析（此前写进 YAML 会被静默忽略）");
    }

    @Test
    void unknownNodeKeyIsRejectedNeverSilentlyIgnored() {
        // G11/§12：拼错的键此前被静默忽略（用户以为生效了），现在必须显式报错并列出可用键
        var e = assertThrows(IllegalArgumentException.class,
                () -> loadNodeFields("autoStarts: false"));
        assertTrue(e.getMessage().contains("unknown key 'autoStarts'"),
                "报错必须点出具体键名，实际: " + e.getMessage());
        assertTrue(e.getMessage().contains("autoStart"),
                "报错必须列出受支持的键（含正确拼写），实际: " + e.getMessage());
    }

    @Test
    void nodeLevelReadyIsAcceptedAsAlias() {
        var s = loadNodeFields("""
                launch:
                  mode: external
                  configOut: build/m.properties
                ready: { type: tcp, port: 19092, timeout: 45s }
                """);
        var n = node(s, "m");
        assertEquals("tcp", n.config().get("ready.type"));
        assertEquals("19092", n.config().get("ready.port"));
        assertEquals("45s", n.config().get("ready.timeout"));
    }

    @Test
    void launchReadyStillWorks() {
        var s = loadNodeFields("""
                launch:
                  mode: external
                  configOut: build/m.properties
                  ready: { type: http, port: 19092, path: /healthz }
                """);
        var n = node(s, "m");
        assertEquals("http", n.config().get("ready.type"));
        assertEquals("/healthz", n.config().get("ready.path"));
    }

    @Test
    void conflictingReadyDeclarationsAreRejectedLoudly() {
        var e = assertThrows(IllegalArgumentException.class, () -> loadNodeFields("""
                launch:
                  mode: external
                  configOut: build/m.properties
                  ready: { type: tcp, port: 19092 }
                ready: { type: tcp, port: 29092 }
                """));
        assertTrue(e.getMessage().contains("ready.port"), e.getMessage());
        assertTrue(e.getMessage().contains("conflicting"), e.getMessage());
    }

    @Test
    void identicalReadyDeclarationsAreAccepted() {
        var s = loadNodeFields("""
                launch:
                  mode: external
                  configOut: build/m.properties
                  ready: { type: tcp, port: 19092 }
                ready: { type: tcp, port: 19092 }
                """);
        assertEquals("19092", node(s, "m").config().get("ready.port"));
    }

    @Test
    void yamlListProfileFieldIsFlattenedToCommaString() {
        // logLines: [a, b] 若直接 List.toString() 会变成 "[a, b]"（带方括号）→ 取值被污染
        var s = load(HEAD + """
                behaviors:
                  profiles:
                    default: { duration: 1s, logLines: [load start, load done] }
                  bindings:
                    - node: workers
                      profile: default
                """);
        assertEquals("load start,load done",
                s.behaviors().profiles().get("default").get("logLines"));
    }

    @Test
    void plainStringProfileFieldsUnchanged() {
        var s = load(HEAD + """
                behaviors:
                  profiles:
                    p1: { duration: 5s, jitter: 20%, failAt: 60% }
                  bindings:
                    - node: workers
                      match: { taskName: "spark-*" }
                      profile: p1
                """);
        var p1 = s.behaviors().profiles().get("p1");
        // loader 不做语义解析（百分号原样交给 BehaviorResolver），但要保证不被改写
        assertEquals("20%", p1.get("jitter"));
        assertEquals("60%", p1.get("failAt"));
        List<Scenario.Binding> bindings = s.behaviors().bindings();
        assertEquals(1, bindings.size());
        assertEquals("spark-*", bindings.get(0).taskName());
    }
}

