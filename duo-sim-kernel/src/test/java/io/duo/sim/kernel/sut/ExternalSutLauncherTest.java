package io.duo.sim.kernel.sut;

import io.duo.sim.kernel.api.ComponentException;
import io.duo.sim.kernel.api.Event;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * M6 交付物 1 单测：external SUT 启动器——端点配置文件（主途径）、stdout 兜底途径、
 * ready 探针（tcp）、ready 前退出/超时的启动失败路径、ready 后退出/崩溃的事实事件、
 * attach 形态、以及「场景结束不杀进程 / 启动失败即销毁」（决策 D7/D9）。
 *
 * <p>被测进程是 {@code src/test/resources/external/FakeTcpSut.java}：**零 Duo 依赖**的独立 JVM
 * （JDK 单文件源码模式启动），即「不可改码第三方进程」的最小化身。
 */
class ExternalSutLauncherTest {

    private static final String JAVA = Path.of(System.getProperty("java.home"), "bin",
            System.getProperty("os.name", "").toLowerCase().contains("win")
                    ? "java.exe" : "java").toString();

    private static Path fakeSutSource() throws Exception {
        var url = ExternalSutLauncherTest.class.getResource("/external/FakeTcpSut.java");
        assertNotNull(url, "fake external SUT source must be on the test classpath");
        return Path.of(url.toURI());
    }

    private static int freePort() throws Exception {
        try (ServerSocket s = new ServerSocket(0)) {
            return s.getLocalPort();
        }
    }

    private static List<String> command(String mode, int port) throws Exception {
        return List.of(JAVA, fakeSutSource().toString(), String.valueOf(port), mode);
    }

    private static void awaitUntil(String what, BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + 10_000_000_000L;
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            Thread.sleep(50);
        }
        throw new AssertionError("timeout waiting for: " + what);
    }

    private static ExternalSutLauncher launcher(String sutId, List<String> command,
                                                Map<String, String> config,
                                                Map<String, String> endpoints,
                                                List<Event> events, Path configOut,
                                                int fallbackPort) {
        return new ExternalSutLauncher(sutId, command, config, endpoints, events::add,
                configOut, fallbackPort);
    }

    // ---- 主途径 + 兜底途径 + ready ----

    @Test
    void readyProbeWritesEndpointConfigAndParsesStdoutFallback(@org.junit.jupiter.api.io.TempDir Path tmp)
            throws Exception {
        int port = freePort();
        List<Event> events = new CopyOnWriteArrayList<>();
        Path configOut = tmp.resolve("nested/sut.properties");
        var l = launcher("master", command("hold", port),
                Map.of("ready.type", "tcp", "ready.port", String.valueOf(port),
                        "ready.timeout", "30s", "clusterName", "demo"),
                Map.of("registry", "127.0.0.1:2181"), events, configOut, 0);
        try {
            l.start();

            assertTrue(l.readyConfirmed(), "ready 探针必须确认");
            assertTrue(l.leftRunning(), "hold 模式进程须存活");

            // 主途径：端点配置文件（duo.endpoint.<contract>=<endpoint> + 节点 config）
            assertTrue(Files.exists(configOut), "端点配置文件必须生成: " + configOut);
            String content = Files.readString(configOut);
            assertTrue(content.contains("duo.endpoint.registry=127.0.0.1:2181"),
                    () -> "endpoint line missing:\n" + content);
            assertTrue(content.contains("clusterName=demo"), () -> "config missing:\n" + content);
            assertFalse(content.contains("ready."),
                    () -> "内核探针声明不得写进 SUT 配置:\n" + content);

            // 兜底途径：stdout 端点宣告
            awaitUntil("stdout endpoint announcement", () -> l.discoveredEndpoints().containsKey("scheduler"));
            assertEquals("127.0.0.1:" + port, l.discoveredEndpoints().get("scheduler"));

            // 事件面
            assertTrue(events.stream().anyMatch(e -> e.type().equals("sim.external-process-started")),
                    () -> "no process-started event: " + types(events));
            assertTrue(events.stream().anyMatch(e -> e.type().equals("sim.external-endpoint")
                            && "stdout".equals(e.payload().get("source"))),
                    () -> "no stdout endpoint event: " + types(events));
            assertTrue(events.stream().anyMatch(e -> e.type().equals("sim.external-sut-ready")),
                    () -> "no ready event: " + types(events));
        } finally {
            kill(l);
        }
    }

    @Test
    void readyProbeUsesExposesFallbackPort(@org.junit.jupiter.api.io.TempDir Path tmp) throws Exception {
        int port = freePort();
        List<Event> events = new CopyOnWriteArrayList<>();
        // 不声明 ready.port：兜底端口由调用方（引擎按 exposes 首个非 0 端口）传入
        var l = launcher("master", command("hold", port),
                Map.of("ready.type", "tcp", "ready.timeout", "30s"), Map.of(), events,
                tmp.resolve("sut.properties"), port);
        try {
            l.start();
            assertTrue(l.readyConfirmed());
            assertEquals(port, l.readySpec().port());
        } finally {
            kill(l);
        }
    }

    // ---- 启动失败路径 ----

    @Test
    void readyTimeoutIsStartupFailureAndKillsChild(@org.junit.jupiter.api.io.TempDir Path tmp) throws Exception {
        int port = freePort();
        List<Event> events = new CopyOnWriteArrayList<>();
        var l = launcher("master", command("never-ready", port),
                Map.of("ready.type", "tcp", "ready.port", String.valueOf(port),
                        "ready.timeout", "1200ms"), Map.of(), events, tmp.resolve("sut.properties"), 0);
        long begin = System.nanoTime();
        var ex = assertThrows(ComponentException.class, l::start);
        long elapsedMs = (System.nanoTime() - begin) / 1_000_000;
        assertTrue(ex.getMessage().contains("ready timeout"), ex.getMessage());
        assertTrue(elapsedMs < 20_000, "超时须按声明生效（实测 " + elapsedMs + "ms）");
        assertFalse(l.leftRunning(), "启动失败必须销毁子进程（决策 D9）");
    }

    @Test
    void exitBeforeReadyReportsRealRootCauseFast(@org.junit.jupiter.api.io.TempDir Path tmp) throws Exception {
        int port = freePort();
        List<Event> events = new CopyOnWriteArrayList<>();
        var l = launcher("master", command("exit-now", port),
                Map.of("ready.type", "tcp", "ready.port", String.valueOf(port),
                        "ready.timeout", "30s"), Map.of(), events, tmp.resolve("sut.properties"), 0);
        long begin = System.nanoTime();
        var ex = assertThrows(ComponentException.class, l::start);
        long elapsedMs = (System.nanoTime() - begin) / 1_000_000;
        assertTrue(ex.getMessage().contains("exited before ready"), ex.getMessage());
        assertTrue(elapsedMs < 15_000, "须快速失败（实测 " + elapsedMs + "ms），不得等满 ready 超时");
        assertFalse(events.stream().anyMatch(e -> e.type().startsWith("sut.")),
                "ready 前退出不产生 SUT 事实事件（由启动失败路径报根因）");
    }

    @Test
    void crashBeforeReadyReportsRealRootCause(@org.junit.jupiter.api.io.TempDir Path tmp) throws Exception {
        int port = freePort();
        List<Event> events = new CopyOnWriteArrayList<>();
        var l = launcher("master", command("crash-now", port),
                Map.of("ready.type", "tcp", "ready.port", String.valueOf(port),
                        "ready.timeout", "30s"), Map.of(), events, tmp.resolve("sut.properties"), 0);
        var ex = assertThrows(ComponentException.class, l::start);
        assertTrue(ex.getMessage().contains("exited before ready"), ex.getMessage());
        assertFalse(l.leftRunning());
    }

    // ---- ready 后的生命周期事实 ----

    @Test
    void normalExitAfterReadyPublishesSutExited(@org.junit.jupiter.api.io.TempDir Path tmp) throws Exception {
        int port = freePort();
        List<Event> events = new CopyOnWriteArrayList<>();
        var l = launcher("master", command("exit0", port),
                Map.of("ready.type", "tcp", "ready.port", String.valueOf(port),
                        "ready.timeout", "30s"), Map.of(), events, tmp.resolve("sut.properties"), 0);
        l.start();
        assertTrue(l.awaitExit(20_000), "进程须在 20s 内退出");
        assertEquals(0, l.exitCode());
        awaitUntil("sut.exited event", () -> events.stream().anyMatch(e -> e.type().equals("sut.exited")));
        assertFalse(events.stream().anyMatch(e -> e.type().equals("sut.crashed")),
                () -> "正常退出不得报崩溃: " + types(events));
        assertFalse(l.leftRunning());
    }

    @Test
    void crashAfterReadyPublishesSutCrashedWithExitCode(@org.junit.jupiter.api.io.TempDir Path tmp) throws Exception {
        int port = freePort();
        List<Event> events = new CopyOnWriteArrayList<>();
        var l = launcher("master", command("crash", port),
                Map.of("ready.type", "tcp", "ready.port", String.valueOf(port),
                        "ready.timeout", "30s"), Map.of(), events, tmp.resolve("sut.properties"), 0);
        l.start();
        assertTrue(l.awaitExit(20_000), "进程须在 20s 内退出");
        assertNotNull(l.exitCode());
        assertTrue(l.exitCode() != 0, "异常退出码须非 0，实测 " + l.exitCode());
        awaitUntil("sut.crashed event", () -> events.stream().anyMatch(e -> e.type().equals("sut.crashed")));
        Event crashed = events.stream().filter(e -> e.type().equals("sut.crashed")).findFirst().orElseThrow();
        assertEquals(l.exitCode(), crashed.payload().get("exitCode"));
    }

    // ---- attach 形态（用户自行启动的进程） ----

    @Test
    void attachModeOnlyProbesReady(@org.junit.jupiter.api.io.TempDir Path tmp) throws Exception {
        List<Event> events = new CopyOnWriteArrayList<>();
        Path configOut = tmp.resolve("sut.properties");
        try (ServerSocket userProcess = new ServerSocket(0)) { // 模拟用户自行启动的进程
            int port = userProcess.getLocalPort();
            var l = launcher("master", List.of(),
                    Map.of("ready.type", "tcp", "ready.port", String.valueOf(port),
                            "ready.timeout", "10s"),
                    Map.of("registry", "127.0.0.1:2181"), events, configOut, 0);
            l.start();

            assertTrue(l.readyConfirmed(), "attach 形态只依赖探针");
            assertNull(l.process(), "attach 形态没有内核持有的进程句柄");
            assertFalse(l.leftRunning(), "attach 形态内核不拥有进程");
            assertTrue(Files.readString(configOut).contains("duo.endpoint.registry=127.0.0.1:2181"),
                    "attach 形态同样写端点配置文件");
            assertFalse(events.stream().anyMatch(e -> e.type().equals("sim.external-process-started")),
                    "attach 形态不得报 process-started");
        }
    }

    @Test
    void closeDestroysSpawnedProcessAndReleasesPipes(@org.junit.jupiter.api.io.TempDir Path tmp)
            throws Exception {
        // 安全审计 2026-09-20 M-7 + INFO-1：代起形态由内核持有句柄 ⇒ 收尾必须销毁进程并释放
        // stdin/stdout/stderr。此前 close() 是空实现，进程与三个 FD 都会留在机器上。
        int port = freePort();
        List<Event> events = new CopyOnWriteArrayList<>();
        var l = launcher("master", command("hold", port),
                Map.of("ready.type", "tcp", "ready.port", String.valueOf(port),
                        "ready.timeout", "30s"), Map.of(), events, tmp.resolve("sut.properties"), 0);
        l.start();
        try {
            assertTrue(l.leftRunning(), "ready 后进程须存活");
            l.close(); // 代起形态：句柄归内核，收尾即销毁
            assertTrue(l.awaitExit(20_000), "close() 必须终止代起的子进程，不得留孤儿 JVM");
            assertFalse(l.leftRunning(), "close() 后不得再报存活");
            assertFalse(l.process().isAlive());
            // 关流不得抛异常、也不得卡住：Windows 上若先关流会永久卡在 FileDescriptor.close0
            l.close(); // 幂等
        } finally {
            kill(l);
        }
    }

    @Test
    void attachModeCloseLeavesUserProcessAlone(@org.junit.jupiter.api.io.TempDir Path tmp)
            throws Exception {
        // §7.3 的真正落点：attach 形态内核从未持有进程，close() 更不能碰用户进程。
        List<Event> events = new CopyOnWriteArrayList<>();
        try (ServerSocket userProcess = new ServerSocket(0)) {
            int port = userProcess.getLocalPort();
            var l = launcher("master", List.of(),
                    Map.of("ready.type", "tcp", "ready.port", String.valueOf(port),
                            "ready.timeout", "10s"), Map.of(), events,
                    tmp.resolve("sut.properties"), 0);
            l.start();
            l.close();
            assertNull(l.process(), "attach 形态没有内核持有的进程句柄");
            assertTrue(userProcess.isBound() && !userProcess.isClosed(),
                    "close() 不得关闭用户自行启动的进程（§7.3）");
        }
    }

    // ---- 端点行解析（纯函数） ----

    @Test
    void endpointLineParsingIsStrictAtLineStart() {
        assertArrayEquals2(new String[]{"registry", "127.0.0.1:2181"},
                ExternalSutLauncher.parseEndpointLine("duo.endpoint.registry=127.0.0.1:2181"));
        assertArrayEquals2(new String[]{"registry", "127.0.0.1:2181"},
                ExternalSutLauncher.parseEndpointLine("  duo.endpoint.registry=127.0.0.1:2181  "));
        assertArrayEquals2(new String[]{"filestore", "/tmp/duo-fs"},
                ExternalSutLauncher.parseEndpointLine("duo.endpoint.filestore=/tmp/duo-fs"));
        // 日志前缀/回显不算宣告（避免把引用的配置文本误判为端点）
        assertNull(ExternalSutLauncher.parseEndpointLine("[INFO] duo.endpoint.registry=127.0.0.1:2181"));
        assertNull(ExternalSutLauncher.parseEndpointLine("learned duo.endpoint.registry=x"));
        assertNull(ExternalSutLauncher.parseEndpointLine("duo.endpoint.registry"));
        assertNull(ExternalSutLauncher.parseEndpointLine("duo.endpoint.=127.0.0.1:1"));
        assertNull(ExternalSutLauncher.parseEndpointLine("duo.endpoint.registry="));
        assertNull(ExternalSutLauncher.parseEndpointLine("nothing here"));
        assertNull(ExternalSutLauncher.parseEndpointLine(null));
    }

    private static void assertArrayEquals2(String[] expected, String[] actual) {
        assertNotNull(actual, "expected " + String.join(",", expected));
        assertEquals(2, actual.length);
        assertEquals(expected[0], actual[0]);
        assertEquals(expected[1], actual[1]);
    }

    private static String types(List<Event> events) {
        return events.stream().map(Event::type).distinct().toList().toString();
    }

    /** 测试自己拥有子进程（内核不杀），收尾由测试负责。 */
    private static void kill(ExternalSutLauncher l) {
        Process p = l.process();
        if (p != null && p.isAlive()) {
            // 先释放本进程持有的管道句柄（审计 M-7 后 close() 会关流），再强杀：
            // destroyForcibly() 之后立刻 exitValue() 会因管道未排空而阻塞——句柄先关掉就没有这个问题。
            l.close();
            p.destroyForcibly();
        }
    }

    static {
        // 保证 java 可执行文件存在（CI 与本地一致），否则给出可诊断的失败
        assertTrue(new File(JAVA).exists(), "java executable not found: " + JAVA);
    }
}
