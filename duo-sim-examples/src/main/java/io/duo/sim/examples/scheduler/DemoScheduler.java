package io.duo.sim.examples.scheduler;

import io.duo.sim.kernel.api.SutContext;
import io.duo.sim.kernel.api.SutMain;
import io.duo.sim.kernel.contract.RegistryContract;
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * demo-scheduler（计划 T13，SUT 参考实现）：in-process SUT（实现 SutMain）。
 *
 * <p>职责：向 registry 注册自身 Duo 端点（临时节点，计划 §2 发现路径）→ 接受 worker
 * 拨号注册 → 驱动 SchedulerStateMachine（DAG + 有界重试 + 失败转移，负载均衡取 freeSlots
 * 最大的实例）→ 发布 sut.* 事实 → DAG 全部终态后 {@code run()} 返回（场景结束信号，
 * 计划 §2/§7.3）。onStop 注册协作停止。
 *
 * <p>配置项（节点 config）：{@code dag.tasks}（逗号分隔）、
 * {@code dag.deps.<task>}（逗号分隔上游，可省略）。M0 固定 5 任务演示拓扑由默认值给出。
 */
public final class DemoScheduler implements SutMain {

    private final AtomicBoolean running = new AtomicBoolean(false);
    private ServerSocket server;
    private final List<FrameConnection> workerConns = new CopyOnWriteArrayList<>();
    private final Map<String, WorkerFeed> feeds = new ConcurrentHashMap<>();
    private SchedulerStateMachine stateMachine;
    private SutContext ctx;
    private volatile int dispatchCursor;

    /** worker 连接的下行推送。 */
    private static final class WorkerFeed {
        final String name;
        final FrameConnection conn;
        final AtomicBoolean busy = new AtomicBoolean(false);

        WorkerFeed(String instanceName, FrameConnection conn) {
            this.name = instanceName;
            this.conn = conn;
        }
    }

    @Override
    public void run(SutContext ctx) throws Exception {
        this.ctx = ctx;
        running.set(true);

        // 1) 绑定自身 Duo 端点并注册到 registry（发现路径，§2）
        server = new ServerSocket();
        server.bind(new InetSocketAddress("127.0.0.1", 0));
        RegistryContract registry = ctx.direct(io.duo.sim.kernel.api.Contract.REGISTRY,
                RegistryContract.class)
                .orElseThrow(() -> new IllegalStateException(
                        "demo-scheduler requires direct registry binding"));
        registry.registerEndpoint("scheduler", "127.0.0.1:" + server.getLocalPort());
        ctx.ready(); // 就绪：端点已可被发现

        // 2) DAG（config 可覆盖；M0 默认 5 任务演示拓扑，含不稳定任务）
        Map<String, List<String>> dag = readDag(ctx.config());
        if (dag.isEmpty()) {
            throw new IllegalStateException("demo-scheduler: empty DAG (check dag.tasks)");
        }
        stateMachine = new SchedulerStateMachine(dag, new StateListener());
        ctx.events().publish("sut.scheduler-started",
                Map.of("tasks", String.join(",", stateMachine.topoTasks())));

        // 3) accept worker 拨号
        Thread acceptor = Thread.ofVirtual().name("demo-scheduler-accept").start(this::acceptLoop);
        try {
            // 4) 等全部任务终态（状态机驱动；run() 返回即 sut.exited）
            while (running.get() && !stateMachine.allTerminal()) {
                pumpDispatches();
                Thread.sleep(50);
            }
            ctx.events().publish("sut.dag-terminal", Map.of());
        } finally {
            running.set(false);
            acceptor.interrupt();
            closeAll();
        }
    }

    private void acceptLoop() {
        try {
            while (running.get()) {
                Socket s = server.accept();
                var feed = register(s);
                Thread.ofVirtual().name("demo-scheduler-feed-" + feed.name)
                        .start(() -> readFeed(feed));
            }
        } catch (IOException e) {
            // server 关闭
        }
    }

    private WorkerFeed register(Socket s) throws IOException {
        var conn = new FrameConnection(s);
        var first = conn.read();
        if (!(first instanceof RegisterRequest req)) {
            conn.write(new RegisterResponse(false, "first frame must be register"));
            conn.close();
            throw new IOException("bad first frame");
        }
        conn.write(new RegisterResponse(true, null));
        var feed = new WorkerFeed(req.instanceName(), conn);
        workerConns.add(conn);
        feeds.put(req.instanceName(), feed);
        ctx.events().publish("sut.worker-registered",
                Map.of("instance", req.instanceName()));
        return feed;
    }

    private void readFeed(WorkerFeed feed) {
        try {
            while (running.get()) {
                var msg = feed.conn.read();
                if (msg instanceof TaskStatus ts) {
                    stateMachine.onStatus(ts.taskId(), ts.state(), ts.detail());
                } else if (msg instanceof HeartbeatReport) {
                    ctx.events().publish("sut.heartbeat",
                            Map.of("instance", ((HeartbeatReport) msg).instanceName()));
                } else if (msg instanceof TaskAck) {
                    // M0：受理即视为在途，无需额外动作
                }
            }
        } catch (IOException e) {
            feeds.remove(feed.name);
        }
    }

    /** 负载均衡：freeSlots 最大的空闲 feed；无可用实例则跳过本轮。 */
    private void pumpDispatches() {
        for (String task : stateMachine.dispatchable()) {
            WorkerFeed best = null;
            for (var f : feeds.values()) {
                if (f.busy.compareAndSet(false, true)) {
                    if (best == null) {
                        best = f;
                    } else {
                        f.busy.set(false);
                    }
                }
            }
            if (best == null) {
                return; // 本轮无空闲 worker
            }
            try {
                int attempt = stateMachine.dispatch(task, best.name);
                best.conn.write(new TaskDispatch(task, task, attempt, 1, 1));
                ctx.events().publish("sut.task-dispatched",
                        Map.of("taskId", task, "attempt", attempt,
                                "instance", best.name));
            } catch (IOException e) {
                feeds.remove(best.name);
            } finally {
                best.busy.set(false);
            }
        }
    }

    private Map<String, List<String>> readDag(Map<String, String> config) {
        String tasksCfg = config.getOrDefault("dag.tasks",
                "load-orders,clean-orders,build-features,unstable-task,aggregate-report");
        List<String> tasks = List.of(tasksCfg.split(","));
        Map<String, List<String>> dag = new HashMap<>();
        for (String t : tasks) {
            String deps = config.get("dag.deps." + t.trim());
            dag.put(t.trim(), deps == null || deps.isBlank() ? List.of()
                    : List.of(deps.split(",")));
        }
        return dag;
    }

    /** 状态机事实 → sut.* 事件（§7.3）。 */
    private final class StateListener implements SchedulerStateMachine.Listener {

        @Override
        public void onDispatch(String taskId, String taskName, int attempt, String instance) {
            ctx.events().publish("sut.task-dispatched",
                    Map.of("taskId", taskId, "attempt", attempt, "instance", instance));
        }

        @Override
        public void onStatus(String taskId, String state, String detail, int attempt) {
            ctx.events().publish("sut.task-status",
                    Map.of("taskId", taskId, "state", state, "attempt", attempt));
            if (!"RETRYING".equals(state)) {
                ctx.events().publish("sut.task-terminal",
                        Map.of("taskId", taskId, "state", state));
            }
        }

        @Override
        public void onRetry(String taskId, int nextAttempt) {
            ctx.events().publish("sut.task-retry",
                    Map.of("taskId", taskId, "nextAttempt", nextAttempt));
        }

        @Override
        public void onAllTerminal() {
            ctx.events().publish("sut.dag-terminal", Map.of());
        }
    }

    private void closeAll() {
        for (var c : workerConns) {
            try {
                c.close();
            } catch (IOException ignored) {
                // 忽略
            }
        }
        workerConns.clear();
        if (server != null && !server.isClosed()) {
            try {
                server.close();
            } catch (IOException ignored) {
                // 忽略
            }
        }
    }
}
