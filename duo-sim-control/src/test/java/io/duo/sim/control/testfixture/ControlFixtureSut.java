package io.duo.sim.control.testfixture;

import io.duo.sim.kernel.api.Contract;
import io.duo.sim.kernel.api.SutContext;
import io.duo.sim.kernel.api.SutMain;
import io.duo.sim.kernel.contract.RegistryContract;
import io.duo.sim.protocol.DuoCodec;
import io.duo.sim.protocol.DuoMessage;
import io.duo.sim.protocol.FrameConnection;
import io.duo.sim.protocol.message.HeartbeatReport;
import io.duo.sim.protocol.message.RegisterRequest;
import io.duo.sim.protocol.message.RegisterResponse;
import io.duo.sim.protocol.message.SlotReport;
import io.duo.sim.protocol.message.TaskAck;
import io.duo.sim.protocol.message.TaskDispatch;
import io.duo.sim.protocol.message.TaskStatus;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 最小 SUT 夹具（测试源码，不是产品代码）：扮演 {@code scheduler} 的**端点 + 连接应答**。
 *
 * <p>它做四件事：绑定 Duo 端口 → <b>注册端点供 worker 发现</b> → {@code ready()} →
 * accept worker 拨号、应答注册并受理心跳/槽位/回报，**收齐期望数量后自行退出**。
 *
 * <h2>为什么需要它</h2>
 * {@code ScenarioEngine.startSut()} 强制「SUT 节点必须声明 {@code launch}」，且 worker 侧组件
 * 启动时要求 {@code registry.discoverEndpoints("scheduler")} 非空——<b>注册端点这一步正是
 * "谁是 SUT" 的实质</b>（见 {@code DemoScheduler.DirectRegistryAccess.register}）。缺了它，
 * worker 会在 20 次发现重试后启动失败
 * （{@code ComponentException: scheduler endpoint not discovered}）。
 *
 * <h2>为什么 run() 必须返回（本轮修掉的真实缺陷）</h2>
 * §7.3 的场景结束条件是 <b>SUT 退出</b>（{@code sut.exited → sutExit}</b>），不是时间到：
 * {@code duration} 是 worker 的剧本参数，与引擎结束无关。夹具若永不返回，
 * {@code ScenarioHost.awaitFinish()} 就永远等不到终态——宿主停在 {@code RUNNING}、
 * 断言表为空（{@code /assertions} 只见 {@code passed=true, assertions=[]}），
 * "等到了终态" 与 "根本没结束" 在调用方看来一模一样。因此这里显式收敛：
 * <b>收齐 {@code expectedWorkers} 个实例的注册 + 心跳，再让稳态维持
 * {@code SETTLE_MS}，然后返回</b>（确定性条件，非随机；见 {@code startSettleClock}）。
 *
 * <h2>职责边界</h2>
 * 只有存在性、可发现性、连接应答与**退出**，<b>没有调度语义</b>（不派发任务、不判 DAG 终态、
 * 不做失联重派）。调度语义的归属是 examples 的 {@code DemoScheduler}；控制面契约测试验证的是
 * 适配层（启动 / 状态 / 事件游标 / 拓扑 / 断言 / 停止 / 注入转发），不是调度算法。
 *
 * <h2>为什么用 {@code readFrame()} 而不是 {@code read()}</h2>
 * 读侧单线程是硬约束（{@code FrameConnection} 类注释）：{@code read()} 分两步
 * （先帧头、后载荷），若本夹具用它读下一帧，就会在读到帧头后阻塞，而真正的读方
 * （worker 在注册应答之后才启动的读线程）会从流中间截走载荷 → 帧错位 →
 * {@code ProtocolViolationException} 打断连接。{@code readFrame()} 整帧读入则不存在这个窗口。
 */
public final class ControlFixtureSut implements SutMain {

    /**
     * 稳态观察窗（缺省）：收齐全部实例的注册与心跳之后再跑这么久，让控制面在"运行中"
     * 有一个**可预期**的窗口去做它的几步调用。
     *
     * <p>取值依据：本模块的控制面用例在 POST 之后还要走 status / topology / events /
     * 双启动 / DELETE 若干次本机 HTTP 往返，几百毫秒的窗口在慢机上会恰好擦边。
     * 3s 是"足够宽但不拖慢整轮测试"的量级——它不是等某个异步操作，而是明确宣告
     * "场景会活这么久"。
     */
    private static final long HOLD_MS = 3_000;
    /** 等待 worker 注册/心跳的封顶时间：到不了也退出，绝不让场景假 RUNNING。 */
    private static final long WAIT_CAP_MS = 10_000;
    /** worker 空闲一次不算断连：多久没有帧就认为该连接已死（只清理该连接）。 */
    private static final int READ_TIMEOUT_MS = 2_000;
    /**
     * 派发事实与终态事实之间的间隔：让控制面有一次以上的轮询机会观察到"任务在跑"。
     * 200ms 与调用方的轮询粒度（100ms/tick）同量级，足以让"运行中 → 已完成"成为可观测的
     * 两个状态，而不是同一瞬间的两个事件。
     */
    private static final long TERMINAL_GAP_MS = 200;

    /** 本次运行的观察窗（由节点 config {@code fixture.holdMs} 覆盖，见 {@link #awaitSettle}）。 */
    private volatile long holdMs = HOLD_MS;

    private final AtomicBoolean running = new AtomicBoolean(true);
    private final AtomicInteger registered = new AtomicInteger();
    private final AtomicInteger heartbeats = new AtomicInteger();

    private volatile ServerSocket server;
    private volatile Thread acceptor;

    @Override
    public void run(SutContext ctx) throws Exception {
        holdMs = readHoldMs(ctx);
        server = new ServerSocket();
        server.bind(new InetSocketAddress("127.0.0.1", 0), 64);

        // 关键的一步：把 scheduler 端点写进 registry（worker 的启动等待依赖它）。
        RegistryContract facade = ctx.direct(Contract.REGISTRY, RegistryContract.class)
                .orElseThrow(() -> new IllegalStateException(
                        "control fixture SUT requires a direct registry facade "
                                + "(场景须把 registry 接到 virtual registry 节点上)"));
        facade.registerEndpoint("scheduler", "127.0.0.1:" + server.getLocalPort());

        ctx.onStop(this::shutdown);
        ctx.ready();
        ctx.events().publish("sut.fixture-started",
                Map.of("endpoint", "127.0.0.1:" + server.getLocalPort()));

        acceptor = Thread.ofVirtual().name("fixture-sut-accept").start(this::acceptLoop);
        int expected = expectedWorkers(ctx);
        awaitSettle(expected);
        // 收尾前用协议发一个真实的「任务派发 + 终态」事实对：noTaskLost 断言在**零派发**时判
        // 失败（"no task was dispatched"，见 Assertions.noTaskLost），而本夹具的职责边界是不做
        // 调度。两条事实只是"这个 taskId 有始有终"，让控制面能走到断言评估这一段——
        // 这是对**结束与断言通路**的检查，不是对调度算法的检查（调度语义在 examples 的
        // DemoScheduler，那里会真派发真执行真回报）。
        ctx.events().publish("sut.task-dispatched",
                Map.of("taskId", "fixture-task-1", "taskName", "fixture-task-1", "attempt", 1,
                        "instance", "fixture-sut",
                        "detail", "fixture SUT has no scheduler semantics"));
        // 派发与终态之间留一个可观测的间隔：两者同刻发生的话，"运行中"与"已完成"在
        // 控制面的两次轮询之间不可区分，那条终止态用例就只能靠运气。
        Thread.sleep(TERMINAL_GAP_MS);
        ctx.events().publish("sut.task-terminal",
                Map.of("taskId", "fixture-task-1", "state", "SUCCESS",
                        "detail", "fixture SUT has no scheduler semantics", "source", "fixture"));
        ctx.events().publish("sut.fixture-finished",
                Map.of("registered", registered.get(), "heartbeats", heartbeats.get(),
                        "expectedWorkers", expected));
        // run() 返回 ⇒ SutLauncher 发 sut.exited ⇒ 场景进入终态（§7.3）
    }

    /** 期望的 worker 实例数：由场景节点 {@code count} 经 config 传入，缺省按 1 处理。 */
    private static int expectedWorkers(SutContext ctx) {
        String raw = ctx.config().get("fixture.expectedWorkers");
        try {
            return raw == null || raw.isBlank() ? 1 : Math.max(1, Integer.parseInt(raw.trim()));
        } catch (NumberFormatException e) {
            return 1;
        }
    }

    /** 观察窗：config {@code fixture.holdMs}（毫秒）覆盖缺省；解析不出就用缺省，不静默变成 0。 */
    private static long readHoldMs(SutContext ctx) {
        String raw = ctx.config().get("fixture.holdMs");
        try {
            return raw == null || raw.isBlank() ? HOLD_MS : Math.max(0, Long.parseLong(raw.trim()));
        } catch (NumberFormatException e) {
            return HOLD_MS;
        }
    }

    /**
     * 等到「注册数达标 且 心跳数达标」后再观察 {@code holdMs}，然后返回。
     *
     * <p>为什么必须无条件返回：SUT 不退出，引擎就没有终态（§7.3 的结束条件是 SUT 退出，
     * 不是 {@code duration} 到点），宿主会永远停在 RUNNING——调用方看到的是"一直在跑"，
     * 而不是"卡住了"。所有路径都收敛，是这个夹具能被信赖的前提。
     *
     * <p>为什么观察窗是确定性的而不是 sleep 堆积：控制面用例在 POST 与随后几步 HTTP 调用
     * 之间需要一个**稳定存在**的 RUNNING 窗口。窗口靠"齐了 N 个实例再退"这个可判定条件
     * 打开，而不是靠碰运气。{@code fixture.holdMs} 可调，但只在**本机配置档**可用——
     * 外部输入档只接受白名单键，夹具不会为此破坏那条边界。
     */
    private void awaitSettle(int expected) throws InterruptedException {
        long deadline = System.currentTimeMillis() + holdMs + WAIT_CAP_MS;
        while (running.get() && System.currentTimeMillis() < deadline) {
            if (registered.get() >= expected && heartbeats.get() >= expected) {
                Thread.sleep(holdMs);
                return;
            }
            Thread.sleep(20);
        }
    }

    private void acceptLoop() {
        try {
            while (running.get()) {
                Socket s = server.accept();
                Thread.ofVirtual().name("fixture-sut-conn").start(() -> serve(s));
            }
        } catch (IOException e) {
            // 关闭 server → accept 抛错即退出环（正常拆除路径）
        }
    }

    private void serve(Socket s) {
        try (s; var conn = new FrameConnection(s)) {
            while (running.get()) {
                byte[] frame;
                try {
                    frame = conn.readFrame();
                } catch (SocketTimeoutException e) {
                    continue; // 空闲：连接仍活，继续等（不让 SLA 打断正常的长连接）
                }
                DuoMessage msg = decode(frame);
                if (msg instanceof RegisterRequest) {
                    registered.incrementAndGet();
                    conn.write(new RegisterResponse(true, null));
                } else if (msg instanceof HeartbeatReport) {
                    heartbeats.incrementAndGet();
                } else if (msg instanceof SlotReport || msg instanceof TaskAck
                        || msg instanceof TaskStatus) {
                    // 本夹具不派发任务，故这些只会是空转；受理但不解释
                } else if (msg instanceof TaskDispatch d) {
                    // 不派发就不该收到；真收到只可能来自注入的故障路径——显式拒绝，不静默
                    conn.write(new TaskStatus(d.taskId(), "fixture-sut",
                            TaskStatus.REJECTED, "fixture-sut-does-not-dispatch"));
                }
                // 未知帧类型：显式忽略（不回显、不猜），由 decode 已过滤
            }
        } catch (IOException e) {
            // 连接断开：worker 侧自行处理（本夹具不做失联判定）
        }
    }

    /** 帧 → 消息；解不出的字节流不是本夹具的语义，返回 null 由调用方忽略。 */
    private static DuoMessage decode(byte[] frame) {
        try {
            return DuoCodec.decode(frame);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private void shutdown() {
        running.set(false);
        ServerSocket s = server;
        if (s != null) {
            try {
                s.close();
            } catch (IOException ignored) {
                // 关闭失败无后续动作
            }
        }
        Thread a = acceptor;
        if (a != null) {
            a.interrupt();
        }
    }
}
