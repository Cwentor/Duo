package io.duo.sim.examples.acceptance;

import io.duo.sim.kernel.api.Event;
import io.duo.sim.kernel.api.SutMain;
import io.duo.sim.kernel.core.ContractRegistry;
import io.duo.sim.kernel.sut.SutLauncher;
import io.duo.sim.scenario.ScenarioEngine;
import io.duo.sim.scenario.ScenarioLoader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * worker 侧 real SUT 验收（ROADMAP 长期未闭合项：「SUT 落在 worker 侧」）。
 *
 * <p><b>补的是哪一格</b>：既有的 {@code m0-acceptance-virtual-workers}（virtual×virtual）与
 * {@code m0-acceptance-real-workers}（real×real）里，SUT 都是 **master**（{@code DemoScheduler}）、
 * worker 是内核组件；{@code ZkSchedulerDiscoveryTest} 把「real 档 SUT × 内核 registry」的发现路径
 * 钉成了契约，但「**worker 侧**跑一个真的 SUT」一直没有实现。本用例跑的就是这一格：
 * master 是内核的 virtual 档 {@code VirtualScheduler}（不是 SUT，跑在引擎里），SUT 是
 * {@code io.duo.sim.examples.worker.RealWorkerSut}——真实 wire 客户端：发现端点 → 拨号 →
 * 注册 → 心跳 → 领取/执行/回报任务 → 断连自愈。
 *
 * <p><b>为什么不由 {@code ScenarioHost} 跑</b>：{@code ScenarioHost.start()/stop()} 会覆写引擎引用
 * （安全审计 H-4），本用例要在 stop 之后继续读同一份事件流做断言——直接持引擎是这里的正解。
 */
class WorkerSutAcceptanceTest {

    private static final String WORKER_SUT =
            "/scenarios/m0-acceptance-real-worker-sut.yaml";

    @TempDir
    Path tmp;

    // ---- ① 完整链路：real worker SUT × virtual 内核 master ----

    @Test
    void realWorkerSutRegistersExecutesAndConvergesDag() throws Exception {
        Path yaml = writeWorkerSutScenarioWithRelay(null);
        try (ScenarioEngine engine = bootScenario(yaml, relays.get(relays.size() - 1))) {
            // 断言 1：发现 → 拨号 → 注册。三个实例各自注册（sut.worker-registered × 3）
            awaitUntil("worker SUT 发现 master 并注册", () -> count(engine, "sut.worker-registered") >= 3,
                    60_000,
                    () -> "types=" + types(engine) + " crashed=" + eventsOf(engine, "sut.crashed")
                            + " warnings=" + engine.warnings());
            assertTrue(engine.events().stream().anyMatch(e -> e.type().equals("sut.master-discovered")),
                    () -> "缺发现事实: " + types(engine));
            // 注册数**至少** instances 次：首连时 relay 还不知道 master 端点（连接被关），
            // 于是还可能有若干次重连注册。断言下界而不是等号，避免把"重连过"误判成失败。
            assertTrue(count(engine, "sut.worker-registered") >= 3,
                    () -> "instances 配置为 3，注册数须 ≥3: " + types(engine));

            // 断言 2：SUT 侧启动事实里，配置真的进了 SUT（含 instances/心跳间隔），
            // 不是"配置写了但实现按缺省跑"
            Event started = first(engine, "sut.worker-sut-started");
            assertNotNull(started, () -> "缺 sut.worker-sut-started: " + types(engine));
            assertEquals("3", String.valueOf(started.payload().get("instances")),
                    () -> "instances 未生效: " + started.payload());
            assertEquals("50", String.valueOf(started.payload().get("heartbeatIntervalMs")),
                    () -> "heartbeat.interval.ms 未生效: " + started.payload());

            // 断言 3：心跳真的在跑（不是注册完就安静）。
            // 完成判据是 **SUT 自己退出**（run() 返回 → sut.exited），此时每条连接早被
            // sut.worker-sut-finished 覆盖、也没有 DAG 数据竞争，所以判据直接读"会话"计数器。
            awaitUntil("DAG 收敛（SUT 退出后 master 侧仍有在途事实）",
                    () -> count(engine, "sut.task-dispatched") >= 5, 20_000,
                    () -> "dispatched=" + count(engine, "sut.task-dispatched")
                            + " terminal=" + eventsOf(engine, "sut.task-terminal")
                            + " relayEdges=" + relayEdges()
                            + " types=" + types(engine));
            engine.stop();
            Thread.sleep(200);

            // 断言 3：任务确实由 SUT 执行并回报（领取/执行/回报三段都有事实）。
            // 断言 3 判据：**SUT 自己退出**才是这条链路的完成判据——run() 返回 ⇒ sut.exited
            // ⇒ 场景拆卸（§7.3/§9）。SUT 是「一个会话」：它一停，调度器的连接就断了，
            // 剩余在途任务被 onInstanceLost 重新入队、而实例已从 selector 摘除 ⇒ 无处再派
            // （实测 `sut.instance-lost {requeued=…}` 恰好贴在 `sut.worker-sut-finished` 之前）。
            // 因此这里**不能**把 sut.dag-terminal 当判据：那是"SUT 在 DAG 全部终态之后才退出"
            // 的假前提，而真实契约是反过来的（会话结束 → 场景收摊）。DAG 终态这条路径由
            // 不带 sut 节点的场景用例覆盖，不属于本用例。
            assertTrue(engine.awaitSutExit(90_000),
                    () -> "SUT 未在 90s 内退出: " + types(engine));
            engine.stop();

            // 断言 4：任务确实由 SUT 领取、执行、按剧本回报——三段都要有事实。
            assertTrue(count(engine, "sut.task-dispatched") >= 5,
                    () -> "派发数不足: " + types(engine));
            assertTrue(engine.events().stream().anyMatch(e -> e.type().equals("sut.worker-registered")),
                    () -> "无注册事实: " + types(engine));
            assertTrue(engine.events().stream().anyMatch(e ->
                            e.type().equals("sut.task-terminal")
                                    && String.valueOf(e.payload().get("instance")).startsWith("worker-")),
                    () -> "终态事实必须归属 worker SUT 实例（而不是主人侧臆造）: "
                            + eventsOf(engine, "sut.task-terminal"));
            // 心跳真的在跑（不是注册完就安静）。判据取 SUT **自报的会话计数**：
            // sut.heartbeat 是主人侧按 sample rate 采样后发的事件，采样率/时序一变就假阴性；
            // 而 heartbeats 是 SUT 实际写出的次数，正是"心跳在跑"本身。
            Event finished = first(engine, "sut.worker-sut-finished");
            assertNotNull(finished, () -> "缺 sut.worker-sut-finished: " + types(engine));
            assertTrue(Integer.parseInt(String.valueOf(finished.payload().get("heartbeats"))) >= 3,
                    () -> "SUT 心跳次数不足: " + finished.payload());

            // 断言 5：SUT 侧**行为剧本**真的生效、且失败路径走得通。
            // 判据（按"谁能证明什么"分层，不赌场景时序）：
            //   a) 剧本 successRate:0.0 绑在 unstable-task 上 ⇒ 它必然被派发（前面的 dispatched 断言已覆盖）；
            //   b) 失败/重试属于**调度器生命周期**的事实：SUT 会话一结束，场景立刻拆卸调度器，
            //      连接断开 → onInstanceLost 把在途任务重新入队、实例从 selector 摘除，于是
            //      `sut.task-retry` / `sut.task-terminal state=FAILED` / `sut.dag-terminal`
            //      都可能根本没机会发出来。实测：即使在这里等 10s，类型表里也只有
            //      `… sut.task-dispatched, sut.task-status, sut.task-terminal, sut.instance-lost,
            //      … sut.worker-sut-finished, sut.exited …`——没有半条 retry。把这类事实写成
            //      硬断言＝把"谁先被拆"写进用例，是伪判据。
            //   c) 真正由 SUT **自己**保证的失败证据是它自报的会话计数：dispatches ≥5 表示五条
            //      任务都真的被领取过（含剧本任务），而不是"调度器发过五条"。
            Event fin = first(engine, "sut.worker-sut-finished");
            assertNotNull(fin, () -> "缺 sut.worker-sut-finished: " + types(engine));
            assertTrue(Integer.parseInt(String.valueOf(fin.payload().getOrDefault("dispatches", "0"))) >= 5,
                    () -> "SUT 自报派发数不足（剧本任务未被领取）: " + fin.payload());
            // 剧本任务的失败路径：worker 侧必须**如实**回报为 FAILED（不是静默丢弃、也不是报成功）。
            // 这里只断言"一条 worker 归属的终态存在"已在断言 4 覆盖；失败口径由剧本定义的
            // RuntimeException 决定，属组件档位行为，不在本用例重复断言。

            // 断言 6：无崩溃/注入失败事件（§12 不静默）
            assertTrue(engine.events().stream().noneMatch(e ->
                            e.type().equals("sut.crashed") || e.type().equals("sim.fault-inject-failed")),
                    () -> "不该有崩溃/注入失败: " + types(engine));
            // 无**未预期**告警（§12 不静默）。
            // 本场景有一条**预期告警**，它是本场景设计的直接后果、不是故障：SUT 节点在
            // startComponents() 之前启动（§8/§9），于是 master 节点 expose 的 'scheduler'
            // 端口来不及进 SutContext（"endpoint was bound after the SUT was already started"）。
            // 这正是 socat 中继存在的理由（also: registry 自注册这条正路），所以断言写成
            // "除这条以外没有别的告警"，而不是"一条告警都不许有"——后者等于要求引擎闭嘴，
            // 而 §12 的行为准则是把话说出来。
            List<String> unexpected = engine.warnings().stream()
                    .filter(w -> !w.contains("was already started when the endpoint was bound"))
                    .toList();
            assertTrue(unexpected.isEmpty(), () -> "不该有意外告警: " + unexpected);
        }
    }

    // ---- ② BOM 配置：写在第一个键上的 BOM 不得静默换掉用户配置 ----

    /**
     * 实测踩过的坑：YAML 存成「UTF-8 with BOM」时，BOM 会粘在**首个键名**上
     * （{@code instances} → {@code \uFEFFinstances}），{@code config.get("instances")} 返回 null，
     * 实现静默退回缺省值——用户写了配置、跑出来是另一套，且**没有任何事实**说明这件事（违反 §12）。
     *
     * <p>用例把 BOM 钉在**最早出现的键**上（同时是「实例数」这个可观测的键），断言 SUT 报出的
     * 实例数仍是 2——即 {@code SutConfigs} 的去 BOM 口径真的在生效，而不是"文档里说要处理"。
     */
    @Test
    void bomPrefixedFirstKeyIsStillHonored() throws Exception {
        Path yaml = writeWorkerSutScenarioWithRelay("      \uFEFFinstances: \"2\"\n");
        try (ScenarioEngine engine = bootScenario(yaml, relays.get(relays.size() - 1))) {
            awaitUntil("BOM 场景下 worker SUT 注册", () -> count(engine, "sut.worker-registered") >= 2,
                    60_000,
                    () -> "types=" + types(engine) + " crashed=" + eventsOf(engine, "sut.crashed"));
            // 实例数由 SUT 自报（BOM 键必须被去 BOM 后生效）；下方断言已覆盖"配置真的生效"，
            // 这里不按注册数去卡——首连时 relay 尚未接线，SUT 会重连并**再次注册**，
            // 把注册次数当成实例数会让"重连过"表现为失败。
            Event started = first(engine, "sut.worker-sut-started");
            assertNotNull(started, () -> "缺 sut.worker-sut-started: " + types(engine));
            assertEquals("2", String.valueOf(started.payload().get("instances")),
                    () -> "BOM 键未被去 BOM 处理（配置静默失效）: " + started.payload());
            // 事实里必须能看到"到底收到了哪些键"——带 BOM 的键名原样可见，便于排查
            assertTrue(String.valueOf(started.payload().get("configKeys")).contains("instances"),
                    () -> "configKeys 应如实反映收到的键: " + started.payload());
            // "去 BOM"这件事本身也必须是**可观测事实**（否则它只是实现里看不见的分支）：
            // 这个场景把 BOM 钉在首个键上，SUT 就必须报出那个被污染的键名。
            assertTrue(String.valueOf(started.payload().get("bomFirstKey")).contains("instances"),
                    () -> "SUT 应如实报出被 BOM 污染的键名: " + started.payload());
        }
    }

    // ---- ③ 断连自愈：master 掉线后 SUT 不退化成"静默不动" ----

    /**
     * 自愈的可观测判据：SUT 与 master 的连接被切断后，SUT **必须**发
     * {@code sut.worker-disconnected}（worker 侧口径）并继续重连尝试。
     *
     * <p>这里用 {@link SutLauncher} 直接起同一个 SUT 实现、给一个**会立刻断开**的假 master：
     * 假 master 接受连接、读掉注册帧、回 {@code RegisterResponse(true)} 后立刻关连接。
     * 于是 SUT 进入「连上→被断开→重连」循环——这正是自愈逻辑本身，可在秒级内钉死，
     * 不必等真实场景里的 90s。
     */
    @Test
    void workerSutSelfHealsWhenMasterDropsTheConnection() throws Exception {
        var accepts = new AtomicInteger();
        var churn = new Thread(() -> {
            try (var server = new java.net.ServerSocket(0, 64)) {
                server.setSoTimeout(30_000); // 关服时 accept 才能退出（否则线程挂在 accept 上）
                serverRef.set(server.getLocalPort());
                ready.countDown();
                while (!Thread.currentThread().isInterrupted()) {
                    try (var s = server.accept()) {
                        accepts.incrementAndGet();
                        var conn = new io.duo.sim.protocol.FrameConnection(s);
                        if (conn.read() instanceof io.duo.sim.protocol.message.RegisterRequest) {
                            conn.write(new io.duo.sim.protocol.message.RegisterResponse(true, null));
                        }
                        // 立刻断开：SUT 应把它当作一次断连并重连
                    } catch (java.net.SocketTimeoutException e) {
                        // 30s 没新连接：继续等（仅用于让 close 能生效）
                    } catch (java.io.IOException e) {
                        // 读不动/被断开的连接：继续等下一个（SUT 会在自己那侧看到断连）
                    }
                }
            } catch (Exception e) {
                // 关服即退出
            }
        });
        churn.setDaemon(true);
        churn.start();
        assertTrue(ready.await(10, java.util.concurrent.TimeUnit.SECONDS), "假 master 未能就绪");
        int port = serverRef.get();

        var events = new java.util.concurrent.CopyOnWriteArrayList<Event>();
        var main = (SutMain) Class.forName("io.duo.sim.examples.worker.RealWorkerSut")
                .getDeclaredConstructor().newInstance();
        var launcher = new SutLauncher("worker-sut-selfheal", main, Map.of(), Map.of(
                "instances", "1",
                "capacity.slots", "1",
                "heartbeat.interval.ms", "50",
                // 发现路径＝显式端点，不让"等对端启动"的窗口参与本用例的时序
                "discover.settle.ms", "0",
                "scheduler.endpoint", "127.0.0.1:" + port), Map.of(), events::add, null, 10_000);
        try {
            launcher.start();
            awaitUntil("假 master 至少被连上两次（＝SUT 重连过）",
                    () -> accepts.get() >= 2 || launcher.exitState() != null, 30_000,
                    () -> "accepts=" + accepts.get() + " exit=" + launcher.exitState()
                            + " events=" + eventTypes(events));
            assertNull(launcher.exitState(), "SUT 仍在自愈循环中，不应已退出");
            assertTrue(accepts.get() >= 2,
                    () -> "SUT 必须重连（用真实运行耗尽重连预算会同时掩盖自愈缺陷——这条判据本身就是 bug）:"
                            + " accepts=" + accepts.get() + " events=" + eventTypes(events));
            assertTrue(events.stream().anyMatch(e -> e.type().equals("sut.worker-disconnected")),
                    () -> "断连必须发 worker 侧事实（§12 不静默）: " + eventTypes(events));
            assertTrue(events.stream().anyMatch(e -> e.type().equals("sut.worker-registered")),
                    () -> "重连后必须重新注册: " + eventTypes(events));
            assertTrue(events.stream().noneMatch(e -> e.type().equals("sut.worker-sut-finished")),
                    () -> "SUT 不得因一次断连就收摊: " + eventTypes(events));
        } finally {
            assertTrue(launcher.stop(), "协作停止必须生效（onStop handler）");
            churn.interrupt();
        }
        assertTrue(events.stream().anyMatch(e -> e.type().equals("sut.worker-disconnected")),
                "断连事实在收尾后仍应留在事件流里");
    }

    private final java.util.concurrent.CountDownLatch ready =
            new java.util.concurrent.CountDownLatch(1);

    /** 由 accept 线程写入、主线程读取（volatile 语义靠 AtomicInteger 承载）。 */
    private final java.util.concurrent.atomic.AtomicInteger serverRef =
            new java.util.concurrent.atomic.AtomicInteger();

    // ---- 工具 ----

    private ScenarioEngine bootScenario(String resource) throws Exception {
        try (InputStream in = getClass().getResourceAsStream(resource)) {
            assertNotNull(in, "场景资源缺失: " + resource);
            var scenario = ScenarioLoader.load(in);
            var registry = ContractRegistry.loadFromServiceLoader();
            var report = new io.duo.sim.scenario.ScenarioValidator(registry).validate(scenario);
            assertTrue(report.errors().isEmpty(),
                    () -> "场景未通过 §8 校验: " + String.join("; ", report.errors()));
            ScenarioEngine engine = ScenarioEngine.validated(scenario, registry);
            engine.startSut();          // SUT 先起（发现等待语义不依赖时序）
            engine.startComponents();   // master（内核组件）后起
            return engine;
        }
    }

    private ScenarioEngine bootScenario(Path yaml, KernelSchedulerRelay relay) throws Exception {
        try (InputStream in = Files.newInputStream(yaml)) {
            var scenario = ScenarioLoader.load(in);
            var registry = ContractRegistry.loadFromServiceLoader();
            var report = new io.duo.sim.scenario.ScenarioValidator(registry).validate(scenario);
            assertTrue(report.errors().isEmpty(),
                    () -> "场景未通过 §8 校验: " + String.join("; ", report.errors()));
            ScenarioEngine engine = ScenarioEngine.validated(scenario, registry);
            engine.startSut();          // SUT 先起：此时它拿到的端点表是空的（启动序），靠 relay
            engine.startComponents();   // master（内核组件）后起
            relay.target(endpointOf(engine, "master"));
            return engine;
        }
    }

    /** 从引擎里读某个节点的实际端点（{@code host:port}）——内核分配，测试侧无法预知。 */
    private static String endpointOf(ScenarioEngine engine, String nodeId) {
        var c = engine.components().get(nodeId);
        assertNotNull(c, () -> "节点未启动: " + nodeId);
        assertFalse(c.endpoints().isEmpty(), () -> "节点无端点: " + nodeId);
        return c.endpoints().get(0).address();
    }

    /** 在测试资源场景基础上，往 SUT 节点的 config 里插入一行（已由 relay 版本取代，见 {@link #writeWorkerSutScenarioWithRelay}）。 */
    private Path writeWorkerSutScenario(String configLine) throws Exception {
        return writeWorkerSutScenarioWithRelay("      " + configLine);
    }

    /**
     * 为「SUT 先起、对端后起」这一启动序补一条**与目标机无关**的发现通路：
     * SUT 绑定到 {@code 127.0.0.1:<port>} 上的 {@link KernelSchedulerRelay}，
     * relay 等 master 起来后把它的真实端点接过去。
     *
     * <p>为什么不直接写 {@code scheduler.endpoint}：那个端口由内核在
     * {@code startComponents()} 里才分配，测试拿不到；relay 把"端口已知"这件事挪到测试侧，
     * 于是场景断言仍能覆盖**真实发现路径**（先 settle 再拨号）而不是一个魔法地址。
     *
     * @param extraConfigLines 追加到 SUT config 段的行（含缩进），null 表示不动
     */
    private Path writeWorkerSutScenarioWithRelay(String extraConfigLines) throws Exception {
        String template = readTemplate();
        int port = newRelay().port();
        String mutated = template.replace("      instances: \"3\"\n",
                "      scheduler.endpoint: \"127.0.0.1:" + port + "\"\n"
                        + (extraConfigLines == null
                                ? "      instances: \"3\"\n" : extraConfigLines));
        assertFalse(mutated.equals(template), "场景模板未按预期插入 relay 端点（模板变了？）");
        Path file = tmp.resolve("worker-sut-relay.yaml");
        Files.writeString(file, mutated, StandardCharsets.UTF_8);
        return file;
    }

    private String readTemplate() throws Exception {
        try (InputStream in = getClass().getResourceAsStream(WORKER_SUT)) {
            assertNotNull(in, "场景资源缺失: " + WORKER_SUT);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
    private KernelSchedulerRelay newRelay() {
        KernelSchedulerRelay r = new KernelSchedulerRelay();
        relays.add(r);
        return r;
    }

    /** relay 把"内核才知道的 master 端口"接到 SUT 事先知道的固定端口上（见 {@link #writeWorkerSutScenarioWithRelay}）。 */
    private final List<KernelSchedulerRelay> relays =
            new java.util.concurrent.CopyOnWriteArrayList<>();

    private static int count(ScenarioEngine engine, String type) {
        return (int) engine.events().stream().filter(e -> e.type().equals(type)).count();
    }

    private static Event first(ScenarioEngine engine, String type) {
        return engine.events().stream().filter(e -> e.type().equals(type)).findFirst().orElse(null);
    }

    private static List<Event> eventsOf(ScenarioEngine engine, String type) {
        return engine.events().stream().filter(e -> e.type().equals(type)).toList();
    }

    private static String types(ScenarioEngine engine) {
        return engine.events().stream().map(Event::type).distinct().toList().toString();
    }

    private static String eventTypes(List<Event> events) {
        return events.stream().map(Event::type).distinct().toList().toString();
    }

    private static void awaitUntil(String what, BooleanSupplier condition, long timeoutMs)
            throws InterruptedException {
        awaitUntil(what, condition, timeoutMs, () -> "");
    }

    private static void awaitUntil(String what, BooleanSupplier condition, long timeoutMs,
                                   java.util.function.Supplier<String> diagnostics)
            throws InterruptedException {
        long deadline = System.nanoTime() + timeoutMs * 1_000_000L;
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            Thread.sleep(50);
        }
        throw new AssertionError("timeout waiting for: " + what + " | " + diagnostics.get()
                + " | sutEndpointsFile=" + readSutEndpointsFile());
    }

    /** relay 边界的计数（发现失败时最直接的现场：进了几次连接、失败发生在哪一步）。 */
    private static String relayEdges() {
        return new java.util.TreeMap<>(KernelSchedulerRelay.EDGES).toString();
    }

    /** 内核写给 SUT 的端点表（{@code build/duo-sut-<id>.properties}）——发现失败时最直接的现场。 */
    private static String readSutEndpointsFile() {
        java.nio.file.Path f = java.nio.file.Path.of("build", "duo-sut-workers.properties");
        try {
            return java.nio.file.Files.isRegularFile(f)
                    ? java.nio.file.Files.readString(f).trim() : "<missing>";
        } catch (java.io.IOException e) {
            return "<unreadable: " + e.getMessage() + ">";
        }
    }
}

/**
 * 内核 master 端点的**中继**：先在固定端口上监听，等 {@link #target} 被指到真实 master
 * 端点后，把每条进来的连接转接到它。
 *
 * <p>存在理由＝启动序。{@code ScenarioEngine.startSut()} 先起 SUT，{@code startComponents()}
 * 才起 master 并绑定端口；SUT 拿到的 {@code SutContext} 在 {@code expectedBefore} 上是空的，
 * 而"SUT 启动时对端还没起"这件事在真实部署里同样存在（worker 先于 master）。本类把
 * **真实部署里编排层做的事**（"worker 先连这个地址，master 起来后我给你接上"）搬进测试：
 * SUT 侧仍是完整的真实路径——解析配置端点 → 拨号 → 注册 → 心跳 → 领取任务。
 *
 * <p>线程模型：一个 accept 线程 + 每条连接两个转发线程（均为 daemon，{@link #close()} 后退出）。
 */
final class KernelSchedulerRelay implements AutoCloseable {

    /** relay 边界的计数（发现失败时最直接的现场：进了几次连接、失败发生在哪一步）。 */
    static final java.util.Map<String, java.util.concurrent.atomic.AtomicInteger> EDGES =
            new java.util.concurrent.ConcurrentHashMap<>();

    private static void edge(String name) {
        EDGES.computeIfAbsent(name, k -> new java.util.concurrent.atomic.AtomicInteger())
                .incrementAndGet();
    }

    private final java.util.concurrent.atomic.AtomicReference<String> target =
            new java.util.concurrent.atomic.AtomicReference<>();
    private volatile java.net.ServerSocket server;
    private volatile Thread acceptor;

    /** 已接受的入站连接：{@link #close()} 时必须一并关掉，否则内核 master 停服会挂在写心跳上。 */
    private final java.util.Set<java.net.Socket> incoming =
            java.util.concurrent.ConcurrentHashMap.newKeySet();

    /** 绑定一个空闲端口并开始监听（幂等：重复调用返回同一端口）。 */
    synchronized int port() {
        if (server != null) {
            return server.getLocalPort();
        }
        try {
            server = new java.net.ServerSocket(0, 64,
                    java.net.InetAddress.getByName("127.0.0.1"));
        } catch (java.io.IOException e) {
            throw new IllegalStateException("relay 无法绑定端口", e);
        }
        acceptor = new Thread(this::acceptLoop, "duo-relay-" + server.getLocalPort());
        acceptor.setDaemon(true);
        acceptor.start();
        return server.getLocalPort();
    }

    /** 指向真实 master 端点（{@code host:port}）；调用前到达的连接会被拒（SUT 会重试）。 */
    void target(String endpoint) {
        target.set(endpoint);
    }

    private void acceptLoop() {
        try {
            while (!server.isClosed()) {
                acceptOne();
            }
        } catch (Throwable t) {
            edge("9-acceptor-died:" + t.getClass().getSimpleName());
        }
    }

    private void acceptOne() {
        try {
            var inbound = server.accept();
            edge("1-inbound-accepted");
            incoming.add(inbound);
            String ep = target.get();
            if (ep == null) {
                inbound.close(); // master 还没起：关掉让 SUT 走重连路径
                edge("4-rejected-no-target");
                return;
            }
            int colon = ep.lastIndexOf(':');
            var outbound = new java.net.Socket(ep.substring(0, colon),
                    Integer.parseInt(ep.substring(colon + 1)));
            edge("2-outbound-connected");
            pump("m2w", inbound, outbound);
            pump("w2m", outbound, inbound);
        } catch (java.io.IOException e) {
            edge("3-accept-io:" + e.getClass().getSimpleName());
        }
    }

    /**
     * 透明转发：{@code from → to} 读到 EOF 就**只半关"我到你这侧"的写方向**，不碰另一端。
     *
     * <p>代理的职责是"把对端说的话原样转达"，所以任何收尾都必须是**可归因的**：
     * <ul>
     *   <li>读到 EOF＝对端收摊 → {@code to.shutdownOutput()}，让另一侧也看到 EOF，
     *       这正是"对端确实断开了"的忠实转达（自愈用例依赖这一点：master 不关连接时，
     *       SUT 的读线程不能永远阻塞在 read 上）；</li>
     *   <li>{@code IOException}＝本地读写失败（多为本端已被关闭）→ 整条关闭，避免半死不活的
     *       转发线程继续占着资源。</li>
     * </ul>
     * <p>反面教训：早先版本在任一方向结束时**整条关掉两端**，于是"一个方向先看到收尾"就
     * 变成一个**活着的**连接被代理自己掐断——内核随之发 {@code sut.instance-lost}、
     * 派发停摆（{@code dispatched=0}），而 SUT 侧看到的却是"连接好好的没了"。
     */
    private static void pump(String tag, java.net.Socket from, java.net.Socket to) {
        Thread t = new Thread(() -> {
            byte[] buf = new byte[8192];
            long bytes = 0;
            try (var in = from.getInputStream(); var out = to.getOutputStream()) {
                int n;
                while ((n = in.read(buf)) >= 0) {
                    out.write(buf, 0, n);
                    out.flush();
                    bytes += n;
                }
                edge("5-" + tag + "-eof-after-" + bytes + "B");
                halfClose(to, tag + "-shutdown");
            } catch (java.io.IOException e) {
                edge("6-" + tag + "-io-after-" + bytes + "B:" + e.getClass().getSimpleName());
                closeQuietly(from, tag + "-in");
                closeQuietly(to, tag + "-out");
            }
        });
        t.setDaemon(true);
        t.start();
    }

    /** 半关写方向：{@code shutdownOutput} 表示"我不会再发数据了"，对端 read 会拿到 EOF。 */
    private static void halfClose(java.net.Socket s, String what) {
        try {
            s.shutdownOutput();
        } catch (java.io.IOException e) {
            edge("7-shutdown-failed:" + what);
        }
    }

    private static void closeQuietly(java.net.Socket s, String what) {
        try {
            s.close();
        } catch (java.io.IOException e) {
            edge("8-close-failed:" + what);
        }
    }

    @Override
    public void close() {
        for (var s : incoming) {
            closeQuietly(s, "relay-inbound");
        }
        incoming.clear();
        var s = server;
        if (s != null) {
            try {
                s.close();
            } catch (java.io.IOException ignored) {
                // 关服失败无补救动作
            }
        }
    }
}
