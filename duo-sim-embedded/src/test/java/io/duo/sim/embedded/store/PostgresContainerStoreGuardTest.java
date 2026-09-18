package io.duo.sim.embedded.store;

import io.duo.sim.embedded.provider.H2StoreProvider;
import io.duo.sim.embedded.provider.PostgresContainerStoreProvider;
import io.duo.sim.kernel.api.ComponentContext;
import io.duo.sim.kernel.api.ComponentException;
import io.duo.sim.kernel.api.ComponentId;
import io.duo.sim.kernel.api.Contract;
import io.duo.sim.kernel.api.EndpointShape;
import io.duo.sim.kernel.api.SimClock;
import io.duo.sim.kernel.api.Tier;
import io.duo.sim.kernel.core.ContractRegistry;
import io.duo.sim.kernel.core.SimpleEventBus;
import org.junit.jupiter.api.Test;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.sql.DriverManager;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * M5：store 契约 container 档的**纯元数据/守卫**用例**脱离 Docker 门控**——本类不标
 * {@code @EnabledIf(dockerAvailable)}，无 Docker 环境同样执行，使「容器档不支持的用法必须
 * 显式拒绝、不得静默降级」这一安全属性在任何机器上都被验证（范式同
 * {@code ZookeeperContainerRegistryGuardTest}，即 M4 独立验收缺陷 2 的整改）。
 *
 * <p>背景：{@code restart} 属生命周期动作，{@code ScenarioValidator} 对
 * {@code crash}/{@code restart} 显式豁免 {@code supportedFaults} 校验（§7.2 设计使然），
 * 故容器档的 {@code UnsupportedOperationException} 守卫无法用元数据表达，必须下沉到
 * {@link PostgresContainerStore#restart()} 本体；本类把该守卫钉死在「不依赖 Docker 也能验证」。
 */
class PostgresContainerStoreGuardTest {

    private static ComponentContext ctx(Map<String, String> config) {
        return new ComponentContext(new ComponentId("pg"), config,
                SimClock.real(), new SimpleEventBus(), Map.of(), Map.of());
    }

    // ---- 元数据（纯静态，无 Docker 依赖）----

    @Test
    void providerMetadataDeclaresThirdPartyWithoutFaultsAndNoInstanceControl() {
        var p = new PostgresContainerStoreProvider();
        assertEquals(Contract.STORE, p.contract());
        assertEquals(Tier.CONTAINER, p.tier());
        assertEquals("pg-container-store", p.implName());
        assertTrue(p.isDefault(), "container 档 store 是该档位缺省实现");
        assertEquals(EndpointShape.THIRD_PARTY, p.metadata().endpointShape());
        assertFalse(p.metadata().interfaceDirect(), "store 无同进程门面（与 H2 档同形）");
        assertFalse(p.metadata().instanceControl());
        assertTrue(p.metadata().supportedFaults().isEmpty(),
                "container 档不提供任何 FaultInjectable 故障注入");
        assertTrue(p.metadata().defaultImpl());
        assertNotNull(p.newComponent(), "provider 须能构造组件（构造期不得触碰 Docker）");
    }

    // ---- 注册期一致性（纯静态，无 Docker 依赖）----

    /**
     * 注册期一致性校验（§7.5）：provider 必须能被内核接受（{@code THIRD_PARTY} +
     * {@code supportedFaults=∅} + {@code interfaceDirect=false} 三者自洽），且
     * **不得改变** {@code (STORE, EMBEDDED)} 的缺省实现。
     *
     * <p>本用例直接构造注册表（不读 ServiceLoader）——SPI 注册行由仓库维护者手工添加，
     * 因此这里把「加了注册行之后一定会通过」这件事在无 Docker、无 SPI 改动的前提下先验证掉。
     */
    @Test
    void providerPassesRegistryConsistencyAndKeepsEmbeddedDefault() {
        var reg = new ContractRegistry();
        reg.register(new H2StoreProvider());
        reg.register(new PostgresContainerStoreProvider());
        reg.validateDefaults();
        assertEquals("pg-container-store", reg.resolve(Contract.STORE, Tier.CONTAINER, null).implName());
        assertEquals("h2-store", reg.resolve(Contract.STORE, Tier.EMBEDDED, null).implName(),
                "新增 container provider 不得改变 embedded 档缺省实现");
    }

    // ---- 守卫（必须先于任何容器/Docker 访问生效）----

    /**
     * 容器档 {@code restart()} 必须**显式拒绝**（§7.2 无降级），不得走 stop→start
     * （容器重建换宿主端口 → 已暴露的 JDBC URL 永久失效且无失败路径）。
     */
    @Test
    void restartOnContainerTierIsExplicitlyRejected() {
        var s = new PostgresContainerStore();
        s.init(ctx(Map.of()));
        var e = assertThrows(UnsupportedOperationException.class, s::restart,
                "容器档 restart 必须抛 UnsupportedOperationException，不得静默重建换端口");
        assertTrue(e.getMessage().contains("restart"),
                "失败原因须点明被拒绝的动作，实际：" + e.getMessage());
        assertTrue(e.getMessage().contains("container"),
                "失败原因须点明档位，实际：" + e.getMessage());
    }

    /**
     * guard 必须**先于**任何容器/Docker 访问生效：本机无 Docker 时，若守卫缺失，
     * {@code restart()} 会走到 {@code start()} 并抛 Docker 相关的 {@code ComponentException}
     * ——本用例以「异常类型」把守卫是否前置钉死。
     */
    @Test
    void restartGuardPrecedesAnyDockerAccess() {
        var s = new PostgresContainerStore();
        s.init(ctx(Map.of()));
        Throwable t = assertThrows(Throwable.class, s::restart);
        assertTrue(t instanceof UnsupportedOperationException,
                "守卫须先于 Docker 访问，实际异常：" + t.getClass().getName() + " / " + t.getMessage());
    }

    /**
     * {@code store.jdbcUrl} 在 container 档**显式拒绝**（§7.2 无降级路径）：container 档的
     * URL 只能来自容器映射端口，接受配置 URL 只能被静默忽略——那正是「配了却没用」的静默失败。
     */
    @Test
    void configuredJdbcUrlIsExplicitlyRejectedOnContainerTier() {
        var s = new PostgresContainerStore();
        var e = assertThrows(ComponentException.class,
                () -> s.init(ctx(Map.of("store.jdbcUrl", "jdbc:postgresql://localhost:5432/duo"))),
                "container 档须拒绝 store.jdbcUrl，不得静默忽略");
        assertTrue(e.getMessage().contains("store.jdbcUrl"),
                "失败原因须点明被拒绝的配置键，实际：" + e.getMessage());
        assertTrue(e.getMessage().contains("container"),
                "失败原因须点明档位，实际：" + e.getMessage());
    }

    /** 镜像缺省为固定 tag（不得 latest，保证可复现）。 */
    @Test
    void defaultImageIsPinnedToFixedTag() {
        assertTrue(PostgresContainerStore.DEFAULT_IMAGE.startsWith("postgres:"),
                "缺省镜像须为 postgres:<tag>，实际：" + PostgresContainerStore.DEFAULT_IMAGE);
        assertFalse(PostgresContainerStore.DEFAULT_IMAGE.endsWith(":latest"),
                "不得使用 latest 浮动 tag");
        assertEquals(PostgresContainerStore.DEFAULT_IMAGE, new PostgresContainerStore().image());
        assertEquals("postgres:15-alpine", new PostgresContainerStore("postgres:15-alpine").image());
        assertEquals(PostgresContainerStore.DEFAULT_IMAGE, new PostgresContainerStore("  ").image());
    }

    // ---- 版本混用下的构造期链接（无需 Docker）----

    /**
     * 核心 {@code testcontainers:2.0.5} 与 {@code postgresql:1.20.4} 混用时，最先暴露问题的
     * 位置是**构造期的方法解析**（如等待策略构造器、{@code DockerImageName} 兼容性断言）。
     * 本用例把 {@code start()} 的构造路径在不启容器（不碰 Docker）的前提下走一遍：
     * 任何 {@code NoSuchMethodError}/{@code NoClassDefFoundError} 都会在此失败，
     * 而不必等到有 Docker 的机器上才发现。
     */
    @Test
    void containerConstructionPathLinksWithoutDocker() {
        PostgreSQLContainer c =
                new PostgreSQLContainer(DockerImageName.parse(PostgresContainerStore.DEFAULT_IMAGE));
        c.withDatabaseName("duo");
        c.withUsername("duo");
        c.withPassword("duo");
        assertNotNull(c);
        assertFalse(c.isRunning(), "未 start 的容器不得报告 running（此判定须不触碰 Docker）");
    }

    // ---- 端点 URL 拼装（纯字符串，无需 Docker）----

    /**
     * 真实 JDBC 驱动须在**运行期**可用（pom 里是 compile scope：主代码用真实驱动连容器）。
     * 只验类可加载 + DriverManager 能识别 {@code jdbc:postgresql:} 前缀，不建立任何连接。
     */
    @Test
    void postgresJdbcDriverIsOnClasspathAndAcceptsJdbcPrefix() throws Exception {
        Class<?> driverClass = Class.forName("org.postgresql.Driver");
        assertNotNull(driverClass, "org.postgresql.Driver 须在 classpath 上（compile scope 依赖）");
        assertNotNull(DriverManager.getDriver("jdbc:postgresql://localhost:5432/duo"),
                "DriverManager 须能按 jdbc:postgresql: 前缀找到驱动");
    }

    /** 无查询串时以 {@code ?} 起头内嵌凭据（SUT 只凭一个 URL 即可连接）。 */
    @Test
    void endpointUrlEmbedsCredentialsWithoutQueryString() {
        assertEquals("jdbc:postgresql://localhost:32768/duo?user=duo&password=duo",
                PostgresContainerStore.appendCredentials(
                        "jdbc:postgresql://localhost:32768/duo", "duo", "duo"));
    }

    /** 已有查询串时以 {@code &} 追加，不得产生第二个 {@code ?}。 */
    @Test
    void endpointUrlAppendsToExistingQueryString() {
        String url = PostgresContainerStore.appendCredentials(
                "jdbc:postgresql://localhost:32768/duo?sslmode=disable", "u", "p");
        assertEquals("jdbc:postgresql://localhost:32768/duo?sslmode=disable&user=u&password=p", url);
        assertEquals(1, url.chars().filter(ch -> ch == '?').count(), "只允许一个查询串起始符");
    }
}
