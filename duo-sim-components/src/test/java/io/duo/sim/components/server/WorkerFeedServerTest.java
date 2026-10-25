package io.duo.sim.components.server;

import io.duo.sim.protocol.FrameConnection;
import io.duo.sim.protocol.message.HeartbeatReport;
import io.duo.sim.protocol.message.RegisterRequest;
import io.duo.sim.protocol.message.RegisterResponse;
import io.duo.sim.protocol.message.SlotReport;
import io.duo.sim.protocol.message.TaskStatus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * worker 连接面（两档 scheduler 共用件）单测：真 socket 走完整线协议——注册握手、
 * 槽位记账、**G10 拒绝回滚（本类存在的核心理由）**、失联回调、close join 语义。
 * 两档 scheduler 各自的行为面由 VirtualSchedulerTest（零改动）与 examples 验收守卫。
 */
class WorkerFeedServerTest {

    /** 记录型回调替身：事实面全量收集，轮询断言（协议面是异步的）。 */
    static final class RecordingListener implements WorkerFeedServer.Listener {
        final List<String> registered = new CopyOnWriteArrayList<>();
        final List<String> registerFailed = new CopyOnWriteArrayList<>();
        final List<String> heartbeats = new CopyOnWriteArrayList<>();
        final List<String> lost = new CopyOnWriteArrayList<>();
        final List<Map<String, String>> statuses = new CopyOnWriteArrayList<>();
        final AtomicReference<String> awaitLost = new AtomicReference<>();

        @Override
        public void onRegistered(String instance) {
            registered.add(instance);
        }

        @Override
        public void onRegisterFailed(String error) {
            registerFailed.add(error);
        }

        @Override
        public void onHeartbeat(String instance) {
            heartbeats.add(instance);
        }

        @Override
        public void onFeedLost(String instance) {
            lost.add(instance);
        }

        @Override
        public void onStatus(String taskId, String state, String detail, String instance) {
            statuses.add(Map.of("taskId", String.valueOf(taskId), "state", String.valueOf(state),
                    "detail", String.valueOf(detail), "instance", String.valueOf(instance)));
        }
    }

    private WorkerFeedServer server;
    private RecordingListener listener = new RecordingListener();
    private final Map<String, FrameConnection> clients = new ConcurrentHashMap<>();

    @AfterEach
    void tearDown() {
        clients.forEach((k, c) -> {
            try {
                c.close();
            } catch (Exception ignored) {
                // 尽力而为
            }
        });
        if (server != null) {
            server.close();
        }
    }

    /** 连一个「真 worker」：注册握手 + SlotReport，返回连接。 */
    private FrameConnection connectWorker(String name, int slots) throws Exception {
        Socket socket = new Socket();
        socket.connect(new InetSocketAddress("127.0.0.1", server.port()), 3_000);
        FrameConnection conn = new FrameConnection(socket);
        conn.write(new RegisterRequest(name, 4, 8));
        var resp = conn.read();
        assertTrue(resp instanceof RegisterResponse rr && rr.accepted(),
                "register handshake must be accepted: " + resp);
        conn.write(new SlotReport(name, slots, slots));
        clients.put(name, conn);
        return conn;
    }

    private static void await(BooleanSupplier cond, String what) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 3_000;
        while (System.currentTimeMillis() < deadline) {
            if (cond.getAsBoolean()) {
                return;
            }
            Thread.sleep(20);
        }
        assertTrue(cond.getAsBoolean(), "timeout waiting for: " + what);
    }

    private interface BooleanSupplier {
        boolean getAsBoolean();
    }

    private void startServer() throws Exception {
        server = new WorkerFeedServer("wfs-test", listener);
        server.start();
    }

    @Test
    void registerHandshakePublishesFeedAndRoutesSlotsAndStatus() throws Exception {
        startServer();
        FrameConnection w1 = connectWorker("workers-1", 2);
        await(() -> !listener.registered.isEmpty(), "onRegistered");
        assertEquals(List.of("workers-1"), listener.registered);
        assertEquals(List.of("workers-1"), server.registeredInstances());

        // 槽位权威刷新：上报 2 格 → 可选中
        await(() -> server.selector().freeSlotsOf("workers-1") == 2, "slot report");
        assertEquals("workers-1", server.selector().select());

        // 状态回报路由（回调面）
        w1.write(new TaskStatus("t1", "workers-1", TaskStatus.SUCCESS, null));
        await(() -> !listener.statuses.isEmpty(), "onStatus");
        assertEquals("SUCCESS", listener.statuses.get(0).get("state"));
        assertEquals("t1", listener.statuses.get(0).get("taskId"));

        // 心跳路由
        w1.write(new HeartbeatReport("workers-1", 1));
        await(() -> !listener.heartbeats.isEmpty(), "onHeartbeat");
    }

    /**
     * G10 的结构性守卫（本共享件存在的核心理由）：worker 显式拒绝必须退还本地槽位预留——
     * 单格容量被拒后必须可再次选中，否则被拒任务停在待派发态、DAG 永不收敛。
     */
    @Test
    void rejectedStatusRollsBackLocalSlotReservation() throws Exception {
        startServer();
        FrameConnection w1 = connectWorker("workers-1", 1);
        await(() -> server.selector().freeSlotsOf("workers-1") == 1, "slot report");

        assertEquals("workers-1", server.selector().select());
        server.selector().onDispatched("workers-1"); // pump 侧本地递减
        assertNull(server.selector().select(), "single slot must be exhausted after dispatch");

        w1.write(new TaskStatus("t1", "workers-1", TaskStatus.REJECTED, "no free slot"));
        await(() -> server.selector().select() != null,
                "REJECTED must roll back the local slot reservation");
        assertEquals("workers-1", server.selector().select());
        // 回滚上限＝已上报容量：绝不凭空超过 worker 上报的槽位数
        assertEquals(1, server.selector().freeSlotsOf("workers-1"));
    }

    @Test
    void feedLostOnDisconnectRemovesViewAndNotifiesListener() throws Exception {
        startServer();
        connectWorker("workers-1", 1);
        await(() -> server.selector().knownCount() == 1, "slot report");
        clients.remove("workers-1").close();
        await(() -> !listener.lost.isEmpty(), "onFeedLost");
        assertEquals(List.of("workers-1"), listener.lost);
        assertEquals(0, server.selector().knownCount(), "selector view must be removed");
        assertTrue(server.registeredInstances().isEmpty());
    }

    @Test
    void badFirstFrameIsRejectedLoudly() throws Exception {
        startServer();
        Socket socket = new Socket();
        socket.connect(new InetSocketAddress("127.0.0.1", server.port()), 3_000);
        FrameConnection conn = new FrameConnection(socket);
        clients.put("bad", conn);
        conn.write(new HeartbeatReport("not-a-register", 1)); // 坏首帧
        await(() -> !listener.registerFailed.isEmpty(), "onRegisterFailed");
        assertTrue(server.registeredInstances().isEmpty(), "bad frame must not register a feed");
    }

    @Test
    void closeIsIdempotentAndStopsAccepting() throws Exception {
        startServer();
        assertTrue(server.isOpen());
        server.close();
        assertFalse(server.isOpen());
        // 幂等：二次 close 不抛
        server.close();
        // 新连接被拒（server 已关）
        Socket probe = new Socket();
        try {
            probe.connect(new InetSocketAddress("127.0.0.1", server.port()), 1_000);
            // 某些平台关闭中的 socket 可能短暂可连：再断言 isOpen 已翻
            assertFalse(server.isOpen());
        } catch (Exception expected) {
            // 连接被拒＝预期
        } finally {
            probe.close();
        }
    }
}
