package io.duo.sim.scenario;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * M6 决策 D7：external SUT 命令行切分与占位符展开（{@code ${java}} / {@code ${java.home}}）。
 * 占位符的意义是让场景 YAML **不写死本机 JDK 路径**（跨机器/跨 CI 可复用）。
 */
class ScenarioEngineCommandTest {

    @Test
    void blankCommandMeansAttachMode() {
        assertTrue(ScenarioEngine.splitCommand(null).isEmpty());
        assertTrue(ScenarioEngine.splitCommand("").isEmpty());
        assertTrue(ScenarioEngine.splitCommand("   ").isEmpty());
    }

    @Test
    void splitsOnWhitespace() {
        assertEquals(List.of("java", "-jar", "sut.jar"),
                ScenarioEngine.splitCommand("java -jar sut.jar"));
        assertEquals(List.of("java", "-jar", "sut.jar"),
                ScenarioEngine.splitCommand("  java   -jar\tsut.jar  "));
    }

    @Test
    void honoursSingleAndDoubleQuotes() {
        assertEquals(List.of("java", "-cp", "a b/c", "Main"),
                ScenarioEngine.splitCommand("java -cp \"a b/c\" Main"));
        assertEquals(List.of("java", "-cp", "a b/c", "Main"),
                ScenarioEngine.splitCommand("java -cp 'a b/c' Main"));
    }

    @Test
    void expandsJavaPlaceholdersWithoutHardcodingLocalPaths() {
        String javaHome = System.getProperty("java.home");
        List<String> tokens = ScenarioEngine.splitCommand(
                "${java} -jar \"${java.home}/lib/sut.jar\"");
        assertEquals(3, tokens.size());
        assertTrue(tokens.get(0).startsWith(javaHome), tokens.get(0));
        assertTrue(tokens.get(0).endsWith("java") || tokens.get(0).endsWith("java.exe"),
                tokens.get(0));
        assertEquals(javaHome + "/lib/sut.jar", tokens.get(2));
    }

    @Test
    void leavesUnknownTokensAlone() {
        assertEquals(List.of("${unknown}", "x"), ScenarioEngine.splitCommand("${unknown} x"));
    }
}
