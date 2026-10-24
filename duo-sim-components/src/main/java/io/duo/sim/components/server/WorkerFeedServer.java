package io.duo.sim.components.server;

import io.duo.sim.components.scheduler.DispatchSelector;
import io.duo.sim.protocol.FrameConnection;
import io.duo.sim.protocol.message.HeartbeatReport;
import io.duo.sim.protocol.message.RegisterRequest;
import io.duo.sim.protocol.message.RegisterResponse;
import io.duo.sim.protocol.message.SlotReport;
import io.duo.sim.protocol.message.TaskAck;
import io.duo.sim.protocol.message.TaskStatus;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * worker 连接面（Duo 线协议 master 侧，§13）：accept 环 → 注册握手 → 读帧分发 → 失联收尾，
 * 以及槽位记账（{@link DispatchSelector}）的**全部**生命周期——注册/移除/权威刷新/
 * 派发递减/**拒绝回滚（G10）**都由本类接线。
 *
 * <p>为什么是共享件（架构体检卡 4）：两档 scheduler（virtual 的 {@code VirtualScheduler}
 * 与 real 的 {@code DemoScheduler}）此前各自手写这段循环——共享了状态机却没共享连接面，
 * G10 修复 {@code onDispatchRolledBack} 时只改了 virtual 档，real 档漏同步、两档槽位回滚
 * 语义分叉。槽位记账沉到本类后，「漏接」在结构上不可能；消费方只提供业务回调
 * （状态机驱动、事件发布、心跳采样）——两档调度语义同源从口号变结构。
 *
 * <p>收尾纪律：{@link #close()} 关连接 → interrupt acceptor → 关 server → join（1s 上限），
 * 消除 stop/restart 与残留线程的竞态（{@code VirtualScheduler} 2026-10 修复的 join barrier
 * 教训沉到共享件，所有消费方自动获得）。关闭期间不派发失联通知（停机不是故障）。
 *
 * <p>engine 契约（DUO_PORT 但首帧为 task-dispatch、无 worker 注册握手）是**另一协议形状**，
 * 不在本类覆盖范围——真实差异各自实现。
 */
public final class WorkerFeedServer implements AutoCloseable {

    /** 首帧/注册限时（半开连接不占资源）。 */
    private static final int REGISTER_TIMEOUT_MS = 10_000;
    /** accept backlog：万级并发拨号下缺省 50 会拒连（M4 压测教训）。 */
    private static final int ACCEPT_BACKLOG = 4096;
    /** close() 的线程 join 上限。 */
    private static final int JOIN_TIMEOUT_MS = 1_000;

    /**
     * 业务回调：协议机制与槽位记账在 {@link WorkerFeedServer}，消费方只管业务——
     * 状态机驱动、事件发布（各自的汇点：组件事件总线 / SUT 事实发布器）、心跳采样与计量。
     */
    public interface Listener {

        /** worker 注册成功（sut.worker-registered 事实由消费方发布，汇点不同）。 */
        default void onRegistered(String instance) {
        }

        /** 注册阶段失败（半开连接超时/坏首帧）——一级事实，不静默。 */
        default void onRegisterFailed(String error) {
        }

        /** worker 状态回报。REJECTED 的**槽位回滚已在本类完成**，消费方只管状态机语义。 */
        void onStatus(String taskId, String state, String detail, String instance);

        /** 心跳到达（采样率/吞吐计量留在消费方）。 */
        default void onHeartbeat(String instance) {
        }

        /** 连接丢失：选择器移除已完成；状态机失联重排与 sut.instance-lost 由消费方处理。 */
        default void onFeedLost(String instance) {
        }

        /** 连接已 accept（压测 meter 用；缺省忽略）。 */
        default void onAccepted() {
        }
    }

    private final String name;
    private final Listener listener;
    private final DispatchSelector selector = new DispatchSelector();
    private final Map<String, FrameConnection> feeds = new ConcurrentHashMap<>();
    private final List<FrameConnection> conns = new CopyOnWriteArrayList<>();
    private volatile ServerSocket server;
    private volatile Thread acceptor;
    private volatile boolean open;

    public WorkerFeedServer(String name, Listener listener) {
        this.name = name;
        this.listener = listener;
    }

    /** 绑定 127.0.0.1 随机端口并启动 accept 环；返回实际端口。 */
    public int start() throws IOException {
        ServerSocket s = new ServerSocket();
        s.bind(new InetSocketAddress("127.0.0.1", 0), ACCEPT_BACKLOG);
        this.server = s;
        this.open = true;
        this.acceptor = Thread.ofVirtual().name(name + "-accept").start(this::acceptLoop);
        return s.getLocalPort();
    }

    /** 是否在服务（health/endpoints 消费方用）。 */
    public boolean isOpen() {
        ServerSocket s = server;
        return open && s != null && !s.isClosed();
    }

    /** 绑定端口（endpoints()/registry 注册用）。 */
    public int port() {
        return server.getLocalPort();
    }

    /** 槽位视图（消费方 pump 侧 select/onDispatched 用）。 */
    public DispatchSelector selector() {
        return selector;
    }

    /** 已注册实例的连接（派发写入用）；未注册返回 null。 */
    public FrameConnection feed(String instance) {
        return feeds.get(instance);
    }

    /** 当前已注册实例名（诊断/测试用）。 */
    public List<String> registeredInstances() {
        return new ArrayList<>(feeds.keySet());
    }

    // ---- accept / 注册 / 读帧 ----

    private void acceptLoop() {
        try {
            while (open) {
                Socket s = server.accept();
                listener.onAccepted();
                // 每连接独立虚拟线程：注册/读取失败只影响该连接，不拖垮 accept 环
                // （M4 压测修复：旧实现 register 在 accept 线程串行执行，一个坏连接
                //  即可让整个 acceptor 静默死亡，已建连接永远无人处理）
                Thread.ofVirtual().name(name + "-conn").start(() -> handleConnection(s));
            }
        } catch (IOException e) {
            // server 关闭（停止/重启）
        }
    }

    /** 单连接处理：注册（限时）→ 稳态读取。注册阶段失败仅丢弃该连接（§12 不静默）。 */
    private void handleConnection(Socket s) {
        String registeredName = null;
        try {
            s.setSoTimeout(REGISTER_TIMEOUT_MS); // 首帧/注册限时：半开连接不阻塞资源
            registeredName = register(s);
            s.setSoTimeout(0); // 稳态读可无限期等待下行
            readFeed(registeredName, s);
        } catch (IOException | RuntimeException e) {
            // 注册阶段失败：仅丢弃该连接并记一级事件；
            // 稳态 IOException 在 readFeed 内处理（onFeedLost）；
            // 稳态 RuntimeException（状态机异常等）→ 防御性按失联收敛
            if (registeredName == null) {
                listener.onRegisterFailed(String.valueOf(e.getMessage()));
            } else if (open) {
                feedLost(registeredName);
            }
            try {
                s.close();
            } catch (IOException ignored) {
                // 尽力而为
            }
        }
    }

    private String register(Socket s) throws IOException {
        FrameConnection conn = new FrameConnection(s);
        var first = conn.read();
        if (!(first instanceof RegisterRequest req)) {
            conn.write(new RegisterResponse(false, "first frame must be register"));
            conn.close();
            throw new IOException("bad first frame");
        }
        conn.write(new RegisterResponse(true, null));
        conns.add(conn);
        feeds.put(req.instanceName(), conn);
        selector.register(req.instanceName());
        listener.onRegistered(req.instanceName());
        return req.instanceName();
    }

    private void readFeed(String instance, Socket s) {
        FrameConnection conn = feeds.get(instance);
        if (conn == null) {
            return; // 已被并发移除（失联/关闭）
        }
        try {
            while (open) {
                var msg = conn.read();
                if (msg instanceof TaskStatus ts) {
                    // 线协议 TaskStatus 自带 instanceName：透传给状态事件（断言数据契约）。
                    // G10 修复（P1）：worker **显式拒绝**时必须退还派发时的本地槽位预留，
                    // 否则「最后一格容量」被被拒任务永久占用 ⇒ 被拒任务停在待派发态、
                    // DAG 永不收敛。回滚沉在此处（共享件），消费方无法漏接。
                    // 仅回滚本地记账；权威数值仍只由 worker 的 SlotReport 决定。
                    if (TaskStatus.REJECTED.equals(ts.state())) {
                        selector.onDispatchRolledBack(instance);
                    }
                    listener.onStatus(ts.taskId(), ts.state(), ts.detail(),
                            ts.instanceName());
                } else if (msg instanceof HeartbeatReport hb) {
                    listener.onHeartbeat(hb.instanceName());
                } else if (msg instanceof SlotReport sr) {
                    if (instance.equals(sr.instanceName())) {
                        selector.onSlotReport(instance, sr.freeSlots());
                    }
                } else if (msg instanceof TaskAck) {
                    // 受理即视为在途，无需额外动作
                }
            }
        } catch (IOException e) {
            if (open) {
                feedLost(instance);
            }
        }
    }

    /** 连接丢失 → 移除连接与槽位视图，通知消费方做失联重排。 */
    private void feedLost(String instance) {
        FrameConnection conn = feeds.remove(instance);
        if (conn != null) {
            conns.remove(conn);
        }
        selector.onRemoved(instance);
        listener.onFeedLost(instance);
    }

    /** 消费方主动丢弃一条连接（如派发写入失败）：走失联路径（移除 + 通知）。 */
    public void drop(String instance) {
        feedLost(instance);
    }

    /**
     * 收摊：关连接 → interrupt acceptor → 关 server → join（1s 上限）。
     * 关闭期间（open=false）不再派发失联通知——停机不是故障。
     */
    @Override
    public void close() {
        open = false;
        for (FrameConnection c : conns) {
            try {
                c.close();
            } catch (IOException ignored) {
                // 忽略关闭异常
            }
        }
        conns.clear();
        feeds.clear();
        Thread a = acceptor;
        if (a != null) {
            a.interrupt();
        }
        ServerSocket s = server;
        if (s != null && !s.isClosed()) {
            try {
                s.close();
            } catch (IOException ignored) {
                // 忽略关闭异常
            }
        }
        // 等待 acceptor 退出，避免消费方 restart()/stop() 与残留线程竞态
        if (a != null) {
            try {
                a.join(JOIN_TIMEOUT_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
