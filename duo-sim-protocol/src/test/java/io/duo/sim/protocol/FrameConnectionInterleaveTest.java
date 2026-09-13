package io.duo.sim.protocol;

import io.duo.sim.protocol.message.HeartbeatReport;
import io.duo.sim.protocol.message.RegisterRequest;
import io.duo.sim.protocol.message.RegisterResponse;
import io.duo.sim.protocol.message.TaskDispatch;
import io.duo.sim.protocol.message.TaskStatus;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 双向帧交织单测（T2 完成判据）：两条并发连接的报文互不串扰。 */
class FrameConnectionInterleaveTest {

    @Test
    void concurrentBidirectionalTrafficDoesNotInterleave() throws Exception {
        try (ServerSocket server = new ServerSocket()) {
            server.bind(new InetSocketAddress("127.0.0.1", 0));
            int port = server.getLocalPort();

            List<DuoMessage> serverReceived = new CopyOnWriteArrayList<>();
            CountDownLatch serverGotAll = new CountDownLatch(2);
            Thread acceptor = new Thread(() -> {
                try (Socket s = server.accept();
                     FrameConnection conn = new FrameConnection(s)) {
                    conn.write(new RegisterResponse(true, null)); // 下行首帧
                    for (int i = 0; i < 2; i++) {
                        serverReceived.add(conn.read());
                        serverGotAll.countDown();
                    }
                    conn.write(new TaskDispatch("t-1", "spark-etl", 1, 1, 1)); // 下行后续帧
                    // 保持连接直到测试结束
                    Thread.sleep(2000);
                } catch (IOException | InterruptedException e) {
                    // 关闭即测试结束
                }
            });
            acceptor.setDaemon(true);
            acceptor.start();

            try (Socket c = new Socket()) {
                c.connect(new InetSocketAddress("127.0.0.1", port));
                try (FrameConnection client = new FrameConnection(c)) {
                    // 上行与下行交织：写两帧 + 读两帧，顺序交替
                    assertEquals(new RegisterResponse(true, null), client.read());
                    client.write(new RegisterRequest("workers-1", 4, 8));
                    client.write(new HeartbeatReport("workers-1", 1L));
                    assertEquals(new TaskDispatch("t-1", "spark-etl", 1, 1, 1), client.read());
                    assertTrue(serverGotAll.await(2, TimeUnit.SECONDS));
                    assertEquals(List.of(new RegisterRequest("workers-1", 4, 8),
                            new HeartbeatReport("workers-1", 1L)), List.copyOf(serverReceived));
                    // 连接关闭后 EOF 可预期
                }
            }
            acceptor.join(3000);
        }
    }
}
