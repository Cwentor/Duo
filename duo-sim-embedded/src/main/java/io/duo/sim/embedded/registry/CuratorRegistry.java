package io.duo.sim.embedded.registry;

import io.duo.sim.kernel.api.ComponentException;
import io.duo.sim.kernel.api.Event;
import io.duo.sim.kernel.api.FaultAction;
import org.apache.curator.test.TestingServer;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/**
 * registry 契约的 embedded 档实现（M2 T24）：JVM 内运行**真实 ZooKeeper**
 * （Curator TestingServer），暴露真实端口供 wire 客户端连接，同时提供**同进程门面**
 * 让框架自有组件（VirtualWorker 等，只 speak {@link io.duo.sim.kernel.contract.RegistryContract}）
 * 经真实协议交互。
 *
 * <p>本类是 {@link ZkBackedRegistry} 的 TestingServer 特化（M4 T38 抽取双面骨架后，
 * 这里只剩后端生命周期 + flap 注入）。container 档特化见
 * {@code ZookeeperContainerRegistry}（Testcontainers ZK）。
 *
 * <p><b>与 virtual 档的语义差异（计划 D3）</b>：virtual 档 flap 后**快照重放**（对 SUT 透明）；
 * embedded 档 flap 后**会话与临时节点真实丢失**，任何消费方都必须自行重连并重建节点——
 * 这是真实 ZK 行为，也是 M2 要验证的 SUT 逻辑。见 {@link io.duo.sim.kernel.contract.RegistryContract} javadoc。
 *
 * <p>节点路径与 virtual 档一致（{@code /duo/endpoints/&lt;contract&gt;}），端点由本适配器
 * 以 **EPHEMERAL 节点**写入（{@code registerEndpoint}）——门面会话断开/闪断即消失，
 * 这正是 embedded 档"消费方必须自愈"的前提（与 virtual 档的快照重放相反，见 D3）。
 * SUT 侧自行注册的临时节点同样按真实 ZK 语义随会话消失。
 */
public final class CuratorRegistry extends ZkBackedRegistry {

    private volatile TestingServer server;
    private final Path tempDir;

    public CuratorRegistry() {
        this(null);
    }

    /** @param tempDir TestingServer 数据目录（Windows 下建议显式指定，见计划风险 1） */
    public CuratorRegistry(Path tempDir) {
        this.tempDir = tempDir;
    }

    @Override
    protected String backendKind() {
        return "embedded";
    }

    @Override
    protected void startBackend() throws Exception {
        if (tempDir != null) {
            Files.createDirectories(tempDir);
        }
        server = specFor(tempDir);
        server.start();
    }

    @Override
    protected void stopBackend() {
        try {
            if (server != null) {
                server.close();
            }
        } catch (IOException ignored) {
            // 尽力而为
        }
    }

    @Override
    public String connectString() {
        return server == null ? null : server.getConnectString();
    }

    /** 真实 ZK 端口（TestingServer 直取）。 */
    @Override
    protected int port() {
        return server == null ? -1 : server.getPort();
    }

    // ---- FaultInjectable（T25：registry-flap = TestingServer 整服闪断）----

    /**
     * registry-flap（embedded 档语义，计划 D2/D3）：**TestingServer 整服闪断**——
     * 所有会话失效、所有临时节点消失（真实 ZK 行为，**无快照重放**）。
     * 任何消费方（SUT 的 wire 客户端、框架组件的门面）都必须自行重连并重建节点。
     *
     * <p>实现：关闭 server（会话全部断开、数据目录临时内容按 spec.deleteDataDirectoryOnClose
     * 处理）+ 重建 server + 门面重连。端口按 spec 复用（InstanceSpec 固定则同端口；
     * 若端口漂移会记录在 {@code sim.registry-flap-started} 载荷，供诊断）。
     */
    @Override
    public void inject(FaultAction action) {
        if (!FaultAction.REGISTRY_FLAP.equals(action.type())) {
            throw new UnsupportedOperationException("unsupported fault: " + action.type());
        }
        requireRunning();
        if (flapping) {
            return; // 幂等
        }
        flapping = true;
        int oldPort = server == null ? -1 : server.getPort();
        try {
            // 1) 关停整服：会话断开、临时节点全部消失
            closeQuietly(facadeClient);
            facadeClient = null;
            if (server != null) {
                server.close();
            }
            fire(Event.sim("sim.registry-flap-started", id.value(),
                    Map.of("kind", "embedded", "oldPort", oldPort)));

            // 2) 重建整服（**复用旧端口**，D2：端口漂移会让 wire 客户端永远连不上）
            //    + 门面重连（新会话，无节点）
            server = serverOnPort(tempDir, oldPort);
            server.start();
            facadeClient = newFacadeClient(connectString());
            facadeClient.start();
            facadeClient.blockUntilConnected();
            ensurePath(ENDPOINTS_ROOT);
            flapping = false;
            fire(Event.sim("sim.registry-flap-cleared", id.value(),
                    Map.of("kind", "embedded", "newPort", server.getPort(),
                            "portStable", server.getPort() == oldPort)));
        } catch (Exception e) {
            flapping = false;
            throw new ComponentException("registry-flap (TestingServer restart) failed: "
                    + e.getMessage(), e);
        }
    }

    @Override
    public void clear(FaultAction action) {
        // 闪断是瞬时动作（restart 即完成），无持续状态需清除；与 virtual 档语义不同
        // （virtual 档 flap 有持续窗口 + 快照重放）
        if (!FaultAction.REGISTRY_FLAP.equals(action.type())) {
            throw new UnsupportedOperationException("unsupported fault: " + action.type());
        }
    }

    private static TestingServer specFor(Path tempDir) throws Exception {
        return serverOnPort(tempDir, 0);
    }

    /**
     * 在指定端口创建 TestingServer（port=0 → 自动分配）。
     * flap 恢复时传入旧端口以保证 wire 客户端可重连（D2）。
     */
    private static TestingServer serverOnPort(Path tempDir, int port) throws Exception {
        var spec = new org.apache.curator.test.InstanceSpec(
                tempDir == null ? null : tempDir.toFile(),
                port, port == 0 ? 0 : port + 1, port == 0 ? 0 : port + 2, true, -1);
        return new TestingServer(spec, true);
    }
}
