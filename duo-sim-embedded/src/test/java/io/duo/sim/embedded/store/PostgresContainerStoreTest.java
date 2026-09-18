package io.duo.sim.embedded.store;

import io.duo.sim.kernel.api.ComponentContext;
import io.duo.sim.kernel.api.ComponentException;
import io.duo.sim.kernel.api.ComponentId;
import io.duo.sim.kernel.api.Contract;
import io.duo.sim.kernel.api.EndpointShape;
import io.duo.sim.kernel.api.Event;
import io.duo.sim.kernel.api.EventBus;
import io.duo.sim.kernel.api.SimClock;
import io.duo.sim.kernel.api.StopMode;
import io.duo.sim.kernel.core.SimpleEventBus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;

import java.net.InetSocketAddress;
import java.net.Socket;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * M5：store 契约 container 档（Testcontainers PostgreSQL）——真实容器 + 真实 JDBC 往返，
 * 无 Docker 自动 skip（设计文档 §13「容器档在无 Docker 环境自动 skip」）。
 *
 * <p>{@code @EnabledIf} 而非 {@code Assumptions}：skip 必须在 surefire 计数中可见、可解释
 * （与 {@code ZookeeperContainerRegistryTest} 同口径）。本机无 Docker 时全部用例 skipped；
 * 有 Docker 的环境跑出真实 PostgreSQL 往返（建表/插入/查询、事务回滚、容器停机后 health=down）。
 */
@EnabledIf(value = "io.duo.sim.embedded.store.PostgresContainerStoreTest#dockerAvailable",
        disabledReason = "Docker not available — container tier auto-skipped (§13)")
class PostgresContainerStoreTest {

    private PostgresContainerStore store;

    @AfterEach
    void tearDown() {
        if (store != null) {
            store.stop(StopMode.GRACEFUL);
        }
    }

    /** Docker 探活（§13 无 Docker 自动 skip；@EnabledIf 使 skip 在 surefire 计数中可见）。 */
    static boolean dockerAvailable() {
        // CI 回归 job 用 -Dduo.docker.enabled=false 显式关闭容器档：让「常规回归零 Docker 依赖」
        // 成为确定性事实，而不是「恰好这台机器没 Docker」（skip 必须可见、可解释）
        if ("false".equalsIgnoreCase(System.getProperty("duo.docker.enabled", "true"))) {
            return false;
        }
        try (Socket s = new Socket()) {
            String endpoint = System.getProperty("duo.docker.host", "");
            if (!endpoint.isBlank()) {
                // 显式 DOCKER_HOST（tcp://host:port 形式）
                var uri = java.net.URI.create(endpoint.replaceFirst("^tcp://", "http://"));
                s.connect(new InetSocketAddress(uri.getHost(),
                        uri.getPort() > 0 ? uri.getPort() : 2375), 1000);
                return true;
            }
            // 缺省 npipe/unix socket 不可直接探测端口；以 docker 客户端探活为准
            Process p = new ProcessBuilder("docker", "info", "--format", "{{.ServerVersion}}")
                    .redirectErrorStream(true).start();
            return p.waitFor(5, TimeUnit.SECONDS) && p.exitValue() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    private PostgresContainerStore start(EventBus bus, Map<String, String> config) {
        store = new PostgresContainerStore();
        store.init(new ComponentContext(new ComponentId("pg"), config,
                SimClock.real(), bus, Map.of(), Map.of()));
        store.start();
        return store;
    }

    private PostgresContainerStore start(EventBus bus) {
        return start(bus, Map.of());
    }

    @Test
    void startsRealContainerAndExposesJdbcEndpoint() {
        var bus = new SimpleEventBus();
        List<Event> events = new ArrayList<>();
        bus.subscribe(events::add);
        var s = start(bus);

        assertNotNull(s.jdbcUrl());
        assertTrue(s.jdbcUrl().startsWith("jdbc:postgresql://"),
                "container 档必须暴露真实 PostgreSQL JDBC URL，实际：" + s.jdbcUrl());
        assertEquals(EndpointShape.THIRD_PARTY, s.declaredShape());
        assertEquals(PostgresContainerStore.DEFAULT_IMAGE, s.image(), "缺省镜像须为固定 tag");

        var eps = s.endpoints();
        assertEquals(1, eps.size(), "运行时须暴露恰好一个 store 端点");
        assertEquals(Contract.STORE, eps.get(0).contract());
        assertEquals("jdbc", eps.get(0).kind(), "kind 命名与 H2Store 同口径");
        assertEquals(s.jdbcUrl(), eps.get(0).address());

        assertTrue(s.health().healthy(), "容器起来后 health 必须为 up");
        // 启动事件带 kind=container（诊断可读：与 embedded 档区分）
        assertTrue(events.stream().anyMatch(e -> e.type().equals("sim.store-started")
                        && PostgresContainerStore.KIND_CONTAINER.equals(e.payload().get("kind"))),
                "store-started 须带 kind=container");
    }

    @Test
    void realJdbcRoundTripCreateInsertSelect() throws Exception {
        var s = start(new SimpleEventBus());
        try (Connection c = s.openConnection(); Statement st = c.createStatement()) {
            st.execute("CREATE TABLE orders (id INT PRIMARY KEY, amount INT)");
            s.recordStatement("CREATE TABLE orders", 1);
            st.executeUpdate("INSERT INTO orders VALUES (1, 100), (2, 250)");
            s.recordStatement("INSERT INTO orders", 1);
            try (ResultSet rs = st.executeQuery("SELECT SUM(amount) FROM orders")) {
                assertTrue(rs.next());
                assertEquals(350, rs.getInt(1), "真实 SQL 往返须取回插入值");
            }
            s.recordStatement("SELECT SUM(amount)", 1);
        }
        assertEquals(3, s.executedStatements(), "语句计数须累计已报告的执行");
        assertTrue(s.activeConnections() >= 1, "活跃连接数须可观测，实际：" + s.activeConnections());
    }

    @Test
    void transactionRollsBackOnException() throws Exception {
        var s = start(new SimpleEventBus());
        try (Connection c = s.openConnection(); Statement st = c.createStatement()) {
            st.execute("CREATE TABLE t2 (id INT)");
        }
        assertThrows(RuntimeException.class, () -> s.inTransaction(c -> {
            try (Statement st = c.createStatement()) {
                st.executeUpdate("INSERT INTO t2 VALUES (1)");
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
            throw new IllegalStateException("boom");
        }));
        try (Connection c = s.openConnection(); Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM t2")) {
            assertTrue(rs.next());
            assertEquals(0, rs.getInt(1), "异常回滚须丢弃该行");
        }
    }

    @Test
    void slowQueryHonorsConfiguredThreshold() {
        var bus = new SimpleEventBus();
        List<Event> events = new ArrayList<>();
        bus.subscribe(events::add);
        // 阈值由 store.slowQueryMillis 配置（与 H2Store 同键同语义）
        var s = start(bus, Map.of("store.slowQueryMillis", "10"));
        s.recordStatement("SELECT pg_sleep(1)", 1);
        assertTrue(events.stream().noneMatch(e -> e.type().equals("sim.store-slow-query")),
                "低于阈值不得发慢查询事件");
        s.recordStatement("SELECT pg_sleep(2)", 20);
        assertTrue(events.stream().anyMatch(e -> e.type().equals("sim.store-slow-query")),
                "超过配置阈值须发慢查询事件");
        assertTrue(s.slowQueries().containsKey("SELECT pg_sleep(2)"));
    }

    @Test
    void stoppedStoreIsDownAndRejectsConnections() {
        var s = start(new SimpleEventBus());
        s.stop(StopMode.GRACEFUL);
        assertFalse(s.health().healthy(), "stop(GRACEFUL) 后 health 必须为 down");
        assertTrue(s.endpoints().isEmpty(), "停机后不得再暴露端点");
        assertThrows(ComponentException.class, s::openConnection, "停机后不得再开连接");
    }

    @Test
    void restartOnContainerTierIsExplicitlyRejected() {
        var s = start(new SimpleEventBus());
        var e = assertThrows(UnsupportedOperationException.class, s::restart,
                "容器档 restart 必须显式拒绝，不得静默换宿主端口");
        assertTrue(e.getMessage().contains("restart"),
                "失败原因须点明被拒绝的动作，实际：" + e.getMessage());
        assertTrue(e.getMessage().contains("container"),
                "失败原因须点明档位，实际：" + e.getMessage());
        // 守卫不改变既有运行状态：容器仍在线、端点仍可用
        assertTrue(s.health().healthy(), "被拒绝的 restart 不得破坏运行中的容器");
    }
}
