package io.duo.sim.protocol;

import io.duo.sim.protocol.message.HeartbeatReport;
import io.duo.sim.protocol.message.TaskStatus;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 单连接**多线程写**的帧完整性守卫。
 *
 * <p>背景（诚实记录）：一次全量回归里 {@code VirtualWorkerTest} 间歇失败，根因是**测试夹具**的竞态
 * （假 master 先 `received.add(注册请求)` 再写注册响应，测试据此提前在同一连接上派发），
 * 表现为 worker 握手读到 {@code TaskDispatch} → 实例离线。修夹具后该失败消失。
 *
 * <p>但生产代码确实**违反**了 {@code FrameConnection} 原先声明的「单线程写」契约：
 * {@code VirtualWorker} 会在同一条连接上由心跳主循环线程、下行读线程（拒绝回报）与任务线程
 * （终态回报/槽位）并发 {@code write}。{@code BufferedOutputStream} 的缓冲与 {@code count}
 * 为共享可变状态，未串行化时理论上可截断/交错帧。**本用例在加固前亦通过**（3 次运行未复现损坏），
 * 故这里是**回归守卫**而非「缺陷已被复现」的证据；加固见 {@code FrameConnection.write} 的写锁。
 */
class FrameConnectionConcurrentWriteTest {

    private static final int WRITERS = 8;
    private static final int PER_WRITER = 200;

    /** 帧体足够大（> BufferedOutputStream 缓冲）才会真正跨线程交错。 */
    private static String padding(int writer) {
        return ("payload-" + writer + "-").repeat(400);
    }

    @Test
    void concurrentWritesFromManyThreadsStayFrameAligned() throws Exception {
        try (ServerSocket server = new ServerSocket()) {
            server.bind(new InetSocketAddress("127.0.0.1", 0));
            int port = server.getLocalPort();

            List<DuoMessage> received = new ArrayList<>();
            AtomicReference<Throwable> readerFailure = new AtomicReference<>();
            CountDownLatch done = new CountDownLatch(1);
            Thread readerThread = new Thread(() -> {
                try (Socket s = server.accept();
                     FrameConnection conn = new FrameConnection(s)) {
                    for (int i = 0; i < WRITERS * PER_WRITER; i++) {
                        received.add(conn.read());
                    }
                } catch (Throwable t) {
                    readerFailure.set(t);
                } finally {
                    done.countDown();
                }
            }, "concurrent-write-reader");
            readerThread.setDaemon(true);
            readerThread.start();

            try (Socket c = new Socket()) {
                c.connect(new InetSocketAddress("127.0.0.1", port), 5_000);
                try (FrameConnection client = new FrameConnection(c)) {
                    List<Thread> writers = new ArrayList<>();
                    AtomicReference<Throwable> writeFailure = new AtomicReference<>();
                    CountDownLatch start = new CountDownLatch(1);
                    for (int w = 0; w < WRITERS; w++) {
                        int writer = w;
                        Thread t = new Thread(() -> {
                            try {
                                start.await();
                                for (int i = 0; i < PER_WRITER; i++) {
                                    // 混合小帧与大帧：小帧走缓冲、大帧直写，交错窗口最大
                                    if (i % 2 == 0) {
                                        client.write(new HeartbeatReport(
                                                "w-" + writer, i));
                                    } else {
                                        client.write(new TaskStatus("t-" + writer + "-" + i,
                                                "w-" + writer, TaskStatus.SUCCESS,
                                                padding(writer) + i));
                                    }
                                }
                            } catch (Throwable e) {
                                writeFailure.compareAndSet(null, e);
                            }
                        }, "writer-" + w);
                        t.setDaemon(true);
                        writers.add(t);
                        t.start();
                    }
                    start.countDown();
                    for (Thread t : writers) {
                        t.join(30_000);
                    }
                    assertNull(writeFailure.get(), () -> "写线程异常: " + writeFailure.get());
                }
            }

            assertTrue(done.await(30, TimeUnit.SECONDS), "读端未在超时内收到全部帧");
            assertNull(readerFailure.get(),
                    () -> "帧流被损坏（读到畸形/错位帧）: " + readerFailure.get());
            assertEquals(WRITERS * PER_WRITER, received.size(), "帧数不符（丢失或多余）");

            // 每帧内容必须完整对应某个写者的一次写入（无重复、无错配、无截断）
            Set<String> expected = new HashSet<>();
            for (int w = 0; w < WRITERS; w++) {
                for (int i = 0; i < PER_WRITER; i++) {
                    expected.add(i % 2 == 0 ? "hb:w-" + w + ":" + i
                            : "st:t-" + w + "-" + i);
                }
            }
            Set<String> actual = new HashSet<>();
            for (DuoMessage m : received) {
                if (m instanceof HeartbeatReport hb) {
                    actual.add("hb:" + hb.instanceName() + ":" + hb.seq());
                } else if (m instanceof TaskStatus ts) {
                    // detail 必须完好（截断/错配会在此暴露）
                    assertEquals(padding(Integer.parseInt(
                                    ts.taskId().split("-")[1])) + ts.taskId().split("-")[2],
                            ts.detail(), () -> "帧体被截断或错配: " + ts.taskId());
                    actual.add("st:" + ts.taskId());
                } else {
                    throw new AssertionError("意外的报文类型: " + m.getClass().getSimpleName());
                }
            }
            assertEquals(expected, actual, "收到的帧集合与写入集合不一致");
        }
    }

    /** 读端在流被破坏后不应继续返回正常帧（防「静默错位」被当成成功）。 */
    @Test
    void corruptedStreamIsDetectedNotSilentlyAccepted() throws Exception {
        try (ServerSocket server = new ServerSocket()) {
            server.bind(new InetSocketAddress("127.0.0.1", 0));
            int port = server.getLocalPort();
            AtomicReference<Throwable> failure = new AtomicReference<>();
            CountDownLatch done = new CountDownLatch(1);
            Thread readerThread = new Thread(() -> {
                try (Socket s = server.accept();
                     FrameConnection conn = new FrameConnection(s)) {
                    conn.read();
                } catch (Throwable t) {
                    failure.set(t);
                } finally {
                    done.countDown();
                }
            }, "corrupt-reader");
            readerThread.setDaemon(true);
            readerThread.start();

            try (Socket c = new Socket()) {
                c.connect(new InetSocketAddress("127.0.0.1", port), 5_000);
                // 直接写一段畸形字节（非 Duo 帧头）
                c.getOutputStream().write(new byte[]{1, 2, 3, 4, 5, 6, 7, 8, 9, 10});
                c.getOutputStream().flush();
            }
            assertTrue(done.await(10, TimeUnit.SECONDS), "读端未收敛");
            // 必须显式报错（§12 不静默）：ProtocolViolationException（bad magic）
            assertTrue(failure.get() instanceof ProtocolViolationException,
                    () -> "畸形帧必须显式报错，实际: " + failure.get());
        }
    }

    /**
     * 背压下的小帧并发写（最易交错：{@code BufferedOutputStream} 的缓冲被多线程共享，
     * 且每帧都 flush）。读端故意放慢以制造发送缓冲回压，扩大交错窗口。
     */
    @Test
    void smallFramesUnderBackpressureStayIntact() throws Exception {
        final int writers = 16;
        final int perWriter = 300;
        try (ServerSocket server = new ServerSocket()) {
            server.bind(new InetSocketAddress("127.0.0.1", 0));
            int port = server.getLocalPort();
            List<DuoMessage> received = new ArrayList<>();
            AtomicReference<Throwable> readerFailure = new AtomicReference<>();
            CountDownLatch done = new CountDownLatch(1);
            Thread readerThread = new Thread(() -> {
                try (Socket s = server.accept();
                     FrameConnection conn = new FrameConnection(s)) {
                    for (int i = 0; i < writers * perWriter; i++) {
                        received.add(conn.read());
                        if (i % 25 == 0) {
                            Thread.sleep(2); // 放慢读端 → 发送缓冲回压
                        }
                    }
                } catch (Throwable t) {
                    readerFailure.set(t);
                } finally {
                    done.countDown();
                }
            }, "backpressure-reader");
            readerThread.setDaemon(true);
            readerThread.start();

            try (Socket c = new Socket()) {
                c.connect(new InetSocketAddress("127.0.0.1", port), 5_000);
                try (FrameConnection client = new FrameConnection(c)) {
                    CountDownLatch start = new CountDownLatch(1);
                    List<Thread> threads = new ArrayList<>();
                    AtomicReference<Throwable> writeFailure = new AtomicReference<>();
                    for (int w = 0; w < writers; w++) {
                        int writer = w;
                        Thread t = new Thread(() -> {
                            try {
                                start.await();
                                for (int i = 0; i < perWriter; i++) {
                                    client.write(new HeartbeatReport("w-" + writer, i));
                                }
                            } catch (Throwable e) {
                                writeFailure.compareAndSet(null, e);
                            }
                        }, "bp-writer-" + w);
                        t.setDaemon(true);
                        threads.add(t);
                        t.start();
                    }
                    start.countDown();
                    for (Thread t : threads) {
                        t.join(60_000);
                    }
                    assertNull(writeFailure.get(), () -> "写线程异常: " + writeFailure.get());
                }
            }
            assertTrue(done.await(60, TimeUnit.SECONDS), "读端未在超时内收齐");
            assertNull(readerFailure.get(),
                    () -> "帧流被损坏（读到畸形/错位帧）: " + readerFailure.get());
            assertEquals(writers * perWriter, received.size(), "帧数不符");
            Set<String> actual = new HashSet<>();
            for (DuoMessage m : received) {
                assertTrue(m instanceof HeartbeatReport,
                        () -> "错位帧: " + m.getClass().getSimpleName());
                HeartbeatReport hb = (HeartbeatReport) m;
                actual.add(hb.instanceName() + ":" + hb.seq());
            }
            assertEquals(writers * perWriter, actual.size(), "存在重复/丢失帧");
        }
    }
}
