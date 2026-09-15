package io.duo.sim.embedded.registry;

import io.duo.sim.kernel.api.FaultAction;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;
import java.util.Map;

/**
 * registry 契约的 container 档实现（M4 T38，设计文档 §6）：本地 Docker 按需拉起
 * **真 ZooKeeper 容器**（Testcontainers {@code zookeeper:3.9}，2181 映射到随机宿主端口）。
 *
 * <p>与 embedded 档（{@link CuratorRegistry}）的关系：二者共享 {@link ZkBackedRegistry}
 * 双面骨架（wire 面真实端口 + 门面真实协议往返），差异仅在 ZK 后端进程——
 * TestingServer 在 JVM 内，真容器在 Docker 里。换档零改动范围（§6）：测试代码与拓扑
 * 其余部分；拓扑只需 {@code tier: container}。
 *
 * <p><b>flap 语义差异（计划 D6）</b>：容器档**不支持 registry-flap**——容器重启会更换
 * 宿主映射端口，wire 客户端无法按 D2（复用旧端口）的前提重连；TestingServer 同端口
 * 重启是 JVM 内进程才有的便利。元数据 {@code supportedFaults=∅} 使时间线/热注入在
 * 校验期即被拒绝（§7.2 无降级路径），不拖到运行期。
 *
 * <p>无 Docker 环境：{@code start()} 抛 {@link io.duo.sim.kernel.api.ComponentException}
 * 并携带根因；配套测试以 {@code Assume} 自动 skip（§13「容器档在无 Docker 环境自动 skip」）。
 */
public final class ZookeeperContainerRegistry extends ZkBackedRegistry {

    /** 缺省镜像（ZK 3.9：与 Curator 5.7 客户端协议兼容，embedded 档同版本系）。 */
    public static final String DEFAULT_IMAGE = "zookeeper:3.9";
    private static final int ZK_PORT = 2181;

    private volatile GenericContainer<?> container;
    private final String image;

    public ZookeeperContainerRegistry() {
        this(DEFAULT_IMAGE);
    }

    public ZookeeperContainerRegistry(String image) {
        this.image = image == null || image.isBlank() ? DEFAULT_IMAGE : image;
    }

    @Override
    protected String backendKind() {
        return "container";
    }

    @Override
    protected void startBackend() {
        try {
            container = new GenericContainer<>(DockerImageName.parse(image))
                    .withExposedPorts(ZK_PORT)
                    // ZK 就绪判定：AdminServer(8080) 的绑定日志 + TCP 端口可连双保险，
                    // 端口 8080 未暴露故只作日志信号，不作连通判据
                    .waitingFor(Wait.forLogMessage(".*binding to local address.*", 1)
                            .withStartupTimeout(Duration.ofMinutes(2)));
            container.start();
        } catch (RuntimeException e) {
            // Docker 不可用/镜像拉取失败：包成 ComponentException，携带根因（§12）
            throw new io.duo.sim.kernel.api.ComponentException(
                    "cannot start container registry (" + image + "): " + e.getMessage(), e);
        }
    }

    @Override
    protected void stopBackend() {
        GenericContainer<?> c = container;
        if (c != null) {
            try {
                c.stop();
            } catch (RuntimeException ignored) {
                // 尽力而为
            }
            container = null;
        }
    }

    @Override
    public String connectString() {
        GenericContainer<?> c = container;
        if (c == null || !c.isRunning()) {
            return null;
        }
        return c.getHost() + ":" + c.getMappedPort(ZK_PORT);
    }

    @Override
    protected int port() {
        GenericContainer<?> c = container;
        if (c == null || !c.isRunning()) {
            return -1;
        }
        return c.getMappedPort(ZK_PORT);
    }

    @Override
    protected Map<String, Object> backendInfo() {
        return Map.of("kind", backendKind(), "image", image,
                "connectString", String.valueOf(connectString()));
    }

    // ---- FaultInjectable：容器档不支持 flap（类注释 D6；元数据 supportedFaults=∅）----

    @Override
    public void inject(FaultAction action) {
        throw new UnsupportedOperationException(
                "registry-flap is not supported on container tier (D6): " + action.type());
    }

    @Override
    public void clear(FaultAction action) {
        throw new UnsupportedOperationException(
                "registry-flap is not supported on container tier (D6): " + action.type());
    }
}
