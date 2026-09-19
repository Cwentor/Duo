package io.duo.sim.examples.acceptance;

import io.duo.sim.embedded.registry.CuratorRegistry;
import io.duo.sim.kernel.api.ComponentContext;
import io.duo.sim.kernel.api.ComponentId;
import io.duo.sim.kernel.api.SimClock;
import io.duo.sim.kernel.contract.RegistryContract;
import io.duo.sim.kernel.core.SimpleEventBus;
import org.apache.curator.framework.CuratorFramework;
import org.apache.curator.framework.CuratorFrameworkFactory;
import org.apache.curator.retry.ExponentialBackoffRetry;
import org.apache.zookeeper.CreateMode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * **档位互通守卫**：real 档 SUT 写下的端点，必须能被内核 registry 门面发现——这是
 * 「virtual 档 scheduler + real 档 worker」这个组合成立的前提（G4 的唯一余项）。
 *
 * <p>为什么需要单独钉住（实测发现）：`TierSwapAcceptanceTest` 的两个档位分别是
 * real×real（`DemoScheduler` + `DemoRealWorker`）与 virtual×virtual，**从未跑过**
 * real 档 SUT × virtual 档 scheduler。而 `DemoScheduler` 注册端点走的是**自己的 ZK 客户端**
 * （硬编码 {@code /duo/endpoints/scheduler}），**不是** registry 组件；
 * `DemoRealWorker.discoverMaster()` 却只问 `ctx.directRegistry()`。于是：
 * <ul>
 *   <li>real×real：两边共用一个 ZK，能发现；</li>
 *   <li>real 档 SUT × virtual 档 scheduler：virtual scheduler 自己的内存 registry 里
 *       **永远没有** SUT 写的那个节点 ⇒ real worker 一直发现不到 master。</li>
 * </ul>
 * 本用例把「跨档位发现」的**事实源约定**钉死：只要 registry 组件的后端是**同一台真实 ZK**
 * （embedded 档，§7.5 的「档位可换而契约不变」），SUT 的端点对内核组件就是可见的；
 * 反之（后端不同源）则必须显式失败并说明原因，**不得**做成"起了但永远发现不了"的假成功。
 */
class ZkSchedulerDiscoveryTest {

    /** 与 `DemoScheduler` 内部 ZkRegistryAccess 相同的端点路径（硬编码在 SUT 内，此处独立写出）。 */
    private static final String SUT_SCHEDULER_PATH = "/duo/endpoints/scheduler";

    @Test
    void kernelRegistryOnSameZkSeesSutWrittenSchedulerEndpoint(@TempDir Path tmp) throws Exception {
        CuratorRegistry registry = new CuratorRegistry(tmp);
        try {
            registry.init(new ComponentContext(new ComponentId("zk"),
                    Map.of(), SimClock.real(), new SimpleEventBus(), Map.of(), Map.of()));
            registry.start();
            String connectString = registry.connectString();
            assertNotNull(connectString, "embedded 档必须暴露真实 ZK 端口");

            // 1) 扮演 real 档 SUT：按 DemoScheduler 的方式写 /duo/endpoints/scheduler
            String sutAddress = "127.0.0.1:25511";
            CuratorFramework sut = CuratorFrameworkFactory.builder()
                    .connectString(connectString)
                    .retryPolicy(new ExponentialBackoffRetry(200, 10))
                    .sessionTimeoutMs(3_000)
                    .connectionTimeoutMs(2_000)
                    .build();
            sut.start();
            try {
                assertTrue(sut.blockUntilConnected(15, TimeUnit.SECONDS), "SUT 侧 ZK 必须连上");
                if (sut.checkExists().forPath(SUT_SCHEDULER_PATH) == null) {
                    sut.create().creatingParentsIfNeeded().withMode(CreateMode.EPHEMERAL)
                            .forPath(SUT_SCHEDULER_PATH, sutAddress.getBytes(StandardCharsets.UTF_8));
                } else {
                    sut.setData().forPath(SUT_SCHEDULER_PATH,
                            sutAddress.getBytes(StandardCharsets.UTF_8));
                }

                // 2) 内核侧：与 DemoRealWorker.discoverMaster() 完全相同的一行调用
                List<String> found = registry.discoverEndpoints("scheduler");
                assertEquals(List.of(sutAddress), found,
                        "real 档 SUT 的端点必须对内核 registry 门面可见"
                                + "（否则 real worker × virtual scheduler 这一档永远发现不到 master）");
                assertEquals(List.of(), registry.discoverEndpoints("worker"),
                        "未知契约返回空，不得编造");
            } finally {
                sut.close();
            }
        } finally {
            registry.stop(io.duo.sim.kernel.api.StopMode.GRACEFUL);
        }
    }

    /**
     * 反向守卫（§12 不静默）：**后端不同源**时（内核 registry 是 virtual 档的独立内存后端），
     * SUT 在另一台 ZK 上写的端点对内核**不可见**——发现为空，而不是"看似成功"。
     *
     * <p>这条用例记录的是**设计约束**而非要修的行为：跨档位组合要求 registry 后端同源
     * （同为 embedded/container 档的真实 ZK）。任何"real SUT × virtual scheduler"的场景
     * 都必须用 embedded 档 registry，否则 worker 会在重试耗尽后显式失败。
     */
    @Test
    void foreignZkEndpointIsInvisibleToIndependentBackend() throws Exception {
        // 内核侧：virtual 档 registry（独立内存后端，不接任何真实 ZK）
        var virtualRegistry = new io.duo.sim.components.registry.VirtualRegistry();
        virtualRegistry.init(new ComponentContext(new ComponentId("zk"), Map.of(),
                SimClock.real(), new SimpleEventBus(), Map.of(), Map.of()));
        virtualRegistry.start();
        try {
            virtualRegistry.registerEndpoint("scheduler", "127.0.0.1:25512");
            assertEquals(List.of("127.0.0.1:25512"),
                    virtualRegistry.discoverEndpoints("scheduler"), "自身写入自身可见");
            // 另一台真实 ZK 上写的东西，virtual 档后端**看不到**（不同源）
            try (org.apache.curator.test.TestingServer other =
                         new org.apache.curator.test.TestingServer(0)) {
                CuratorFramework sut = CuratorFrameworkFactory.builder()
                        .connectString("127.0.0.1:" + other.getPort())
                        .retryPolicy(new ExponentialBackoffRetry(200, 10))
                        .build();
                sut.start();
                try {
                    assertTrue(sut.blockUntilConnected(15, TimeUnit.SECONDS));
                    sut.create().creatingParentsIfNeeded().withMode(CreateMode.EPHEMERAL)
                            .forPath("/duo/endpoints/worker", "127.0.0.1:25513"
                                    .getBytes(StandardCharsets.UTF_8));
                } finally {
                    sut.close();
                }
            }
            assertEquals(List.of(), virtualRegistry.discoverEndpoints("worker"),
                    "不同源后端不得凭空看见外部 ZK 的节点（发现失败要显式暴露，不做假成功）");
        } finally {
            virtualRegistry.stop(io.duo.sim.kernel.api.StopMode.GRACEFUL);
        }
    }

    /** 类型自证：门面确实来自 embedded 档实现（守卫用例自身不因重构而失去意义）。 */
    @Test
    void registryFacadeIsTheEmbeddedTierImplementation() {
        assertTrue(RegistryContract.class.isAssignableFrom(CuratorRegistry.class));
    }
}

