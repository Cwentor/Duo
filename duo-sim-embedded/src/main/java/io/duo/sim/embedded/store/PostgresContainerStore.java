package io.duo.sim.embedded.store;

import io.duo.sim.kernel.api.ComponentContext;
import io.duo.sim.kernel.api.ComponentException;
import io.duo.sim.kernel.api.ComponentId;
import io.duo.sim.kernel.api.Contract;
import io.duo.sim.kernel.api.EndpointShape;
import io.duo.sim.kernel.api.Event;
import io.duo.sim.kernel.api.ExposedEndpoint;
import io.duo.sim.kernel.api.HealthReport;
import io.duo.sim.kernel.api.StopMode;
import io.duo.sim.kernel.api.VirtualComponent;
import io.duo.sim.kernel.contract.StoreContract;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * store 契约的 container 档实现（M5）：本地 Docker 按需拉起**真 PostgreSQL 容器**
 * （Testcontainers {@code PostgreSQLContainer}，固定镜像 tag，5432 映射到随机宿主端口），
 * 并把 JDBC URL 暴露给 SUT——SUT 用**真实 JDBC 驱动**连的是真第三方数据库，
 * 方言、事务、错误语义都是 PostgreSQL 本身的（对应设计文档 §6「store 契约＝关系库：
 * 连接、事务、方言差异」，container 档＝真实第三方数据库）。
 *
 * <p><b>档位定位与端点形态</b>：{@code declaredShape()=THIRD_PARTY}，{@code endpoints()} 在运行时
 * 暴露 {@code new ExposedEndpoint(Contract.STORE, "jdbc", jdbcUrl)}（kind 命名与
 * {@link H2Store} 同口径）。之所以是 THIRD_PARTY 而不是 NONE/DUO_PORT：对外协议是
 * **PostgreSQL 自己的 wire 协议**（JDBC），既不是框架自定义端口，也没有同进程 Java 门面
 * （{@code interfaceDirect=false}，与 H2 档一致——SUT 必须走真实驱动）。
 *
 * <p><b>与 embedded 档（{@link H2Store}）的差别</b>：
 * <ul>
 *   <li>进程位置：H2 在 JVM 内（内存库），PostgreSQL 在 Docker 容器里（独立进程、独立生命周期）；</li>
 *   <li>连接串：H2 由本类按组件 id 生成且可由 {@code store.jdbcUrl} 覆盖；container 档的
 *       host:port 由 Testcontainers 运行时映射决定，**不可预知也不可配置**；</li>
 *   <li>就绪语义：容器启动后仍要等 PostgreSQL 真正接受连接（Testcontainers 的
 *       {@code LogMessageWaitStrategy} + 本类启动期的 JDBC 探活）；</li>
 *   <li>重启语义：H2 可 stop→start（同 URL），container 档**不可**（见下）。</li>
 * </ul>
 *
 * <p><b>可观测（§5）</b>：{@code sim.store-started}（载荷带 {@code kind=container}，
 * 与 {@code ZookeeperContainerRegistry} 的诊断口径一致）、{@code sim.store-stopped} /
 * {@code sim.store-crashed}、{@code sim.store-connection-opened}、
 * {@code sim.store-slow-query}（阈值 {@code store.slowQueryMillis}，缺省 1000ms）。
 * 语句计数与慢查询经 {@link #recordStatement(String, long)} 在 SQL 执行处累计
 * （SUT/测试直接 JDBC 执行时由调用方报告，避免重量级连接代理——与 H2Store 同设计）。
 *
 * <p><b>为什么不发 {@code sim.store-connection-closed}</b>：与 H2Store 一致——SUT 拿到的
 * 是裸 {@link Connection}，关闭动作发生在 SUT 侧，本组件无连接代理因而观测不到；
 * 若将来需要该事件，应引入连接代理（StoreContract javadoc 已把该事件列为「必发」，
 * 属既有实现与契约之间的已知落差，本类不单方面改变语义）。
 *
 * <p><b>{@code store.jdbcUrl} 配置键在本档显式拒绝</b>：container 档的 URL 由容器的映射
 * 端口决定，接受一个配置 URL 只能被静默忽略（§7.2「无降级路径」）或造成「配了却没用」的
 * 假象，故 {@link #init(ComponentContext)} 在检测到非空 {@code store.jdbcUrl} 时直接抛
 * {@link ComponentException} 并说明理由。要指定库/用户/口令请用构造参数或换 embedded 档。
 *
 * <p><b>{@code restart()} 守卫理由</b>（与 {@code ZookeeperContainerRegistry} 同因同解）：
 * 容器重建会**更换宿主映射端口**，而 {@code endpoints()} 暴露的 JDBC URL 是启动期快照，
 * SUT 侧连接池/客户端只会复用旧端点，永远连不上且没有失败路径——表现为永久挂起。
 * {@code restart} 属生命周期动作，{@code ScenarioValidator} 对 {@code crash}/{@code restart}
 * 显式豁免 {@code supportedFaults} 校验（§7.2 设计使然），故**不能**靠元数据表达该限制，
 * 守卫必须下沉到 {@link #restart()} 本体（显式抛 {@link UnsupportedOperationException}）。
 * 若将来需要支持，应改为固定宿主端口（{@code PortBinding}）后实现「保端口重建」。
 *
 * <p><b>依赖版本说明（实测，勿随手改动）</b>：本模块的核心 {@code org.testcontainers:testcontainers}
 * 为 2.0.5，而 {@code org.testcontainers:postgresql} 在 Maven Central 只发布到 1.21.4
 * （2.0.x 拆成 {@code org.testcontainers:testcontainers-postgresql}，包名亦改为
 * {@code org.testcontainers.postgresql}），故此处是「2.0.5 核心 + 1.20.4 postgresql 模块」的混用。
 * 已用字节码级探针（常量池 Methodref/Fieldref × 反射解析）逐一核对：本类用到的路径
 * （构造、{@code configure()}、{@code start()}、{@code getJdbcUrl()}、{@code getHost()}、
 * {@code getMappedPort(int)}、{@code isRunning()}、{@code stop()}）全部可解析
 * （{@code getHost/getMappedPort/isRunning} 经 2.0.5 的 {@code ContainerState} 接口解析，
 * 生命周期钩子 {@code configure()/waitUntilContainerStarted()/containerIsStarted()} 在 2.0.5
 * 仍被调用）。**已知不可用面**：{@code withInitScripts}（database-commons 1.20.4 的
 * {@code ScriptUtils}/{@code ScriptSplitter} 引用 2.0.5 已移除的 shaded commons-io/lang3）、
 * {@code jdbc:tc:} 容器驱动（{@code ContainerDatabaseDriver} 同因）、R2DBC 变体
 * （2.0.5 无 {@code org.testcontainers.r2dbc.*}）——三者均不在本类路径上。
 */
public final class PostgresContainerStore implements VirtualComponent, StoreContract {

    /** 缺省镜像（固定 tag：不用 latest，保证可复现；alpine 体积小、启动快）。 */
    public static final String DEFAULT_IMAGE = "postgres:16-alpine";

    /** 事件载荷 kind（诊断可读：与 embedded 档区分，口径同 ZookeeperContainerRegistry）。 */
    public static final String KIND_CONTAINER = "container";

    /** 容器内 PostgreSQL 端口。 */
    private static final int PG_PORT = 5432;
    /** 缺省库/用户/口令（模拟环境固定值，非生产口令）。 */
    private static final String DEFAULT_DATABASE = "duo";
    private static final String DEFAULT_USERNAME = "duo";
    private static final String DEFAULT_PASSWORD = "duo";

    private volatile ComponentId id;
    private volatile ComponentContext ctx;
    private volatile boolean running;
    private volatile String jdbcUrl;
    private volatile long slowQueryMillis = 1000;
    /** 容器句柄（start 建立，stop 释放）。 */
    private volatile PostgreSQLContainer container;
    private final String image;

    private final AtomicLong executedStatements = new AtomicLong();
    private final AtomicInteger activeConnections = new AtomicInteger();
    /** 经 {@link #openConnection()} 交给 SUT/测试、由本组件在 stop 时兜底关闭的连接。 */
    private final List<Connection> connections = new CopyOnWriteArrayList<>();
    private final Map<String, Long> slowQueries = new ConcurrentHashMap<>();

    public PostgresContainerStore() {
        this(DEFAULT_IMAGE);
    }

    public PostgresContainerStore(String image) {
        this.image = image == null || image.isBlank() ? DEFAULT_IMAGE : image;
    }

    @Override
    public ComponentId id() {
        return id;
    }

    @Override
    public void init(ComponentContext ctx) {
        this.ctx = ctx;
        this.id = ctx.id();
        String configured = ctx.config().get(KEY_JDBC_URL);
        if (configured != null && !configured.isBlank()) {
            // 显式拒绝而非静默忽略（§7.2 无降级路径）：container 档的 URL 只能来自容器映射端口
            throw new ComponentException("store.jdbcUrl is not supported on container tier ("
                    + configured + "): the JDBC URL of a container-tier store is derived from the "
                    + "container's mapped endpoint at start(); use the embedded tier (h2-store) if a "
                    + "fixed JDBC URL is required");
        }
        String slow = ctx.config().get(KEY_SLOW_QUERY_MILLIS);
        if (slow != null) {
            this.slowQueryMillis = Long.parseLong(slow.trim());
        }
    }

    @Override
    public void start() throws ComponentException {
        try {
            // 1) 拉起真 PostgreSQL 容器（Docker 不可用/镜像缺失时由 Testcontainers 抛错，
            //    下面包成 ComponentException 并携带根因，§12）
            PostgreSQLContainer c = new PostgreSQLContainer(DockerImageName.parse(image));
            c.withDatabaseName(DEFAULT_DATABASE);
            c.withUsername(DEFAULT_USERNAME);
            c.withPassword(DEFAULT_PASSWORD);
            // 先登记句柄再 start：start 中途失败也要能兜底停掉半开容器（下面 catch 分支）
            container = c;
            c.start();

            // 2) 真实 JDBC 驱动探活（容器起来 ≠ 数据库可连；Testcontainers 的日志等待策略
            //    已覆盖大部分，这里再以真实驱动确认一次，失败即启动失败）
            Class.forName("org.postgresql.Driver");
            this.jdbcUrl = jdbcUrlWithCredentials(c);
            try (Connection probe = DriverManager.getConnection(jdbcUrl)) {
                if (!probe.isValid(2)) {
                    throw new SQLException("JDBC probe reported an invalid connection");
                }
            }
            running = true;
            fire(Event.sim("sim.store-started", id.value(),
                    Map.of("jdbcUrl", jdbcUrl, "kind", KIND_CONTAINER, "image", image)));
        } catch (Exception e) {
            // 启动失败不留半开容器（与 ZookeeperContainerRegistry.stopBackend 的尽力而为一致）
            stopContainerQuietly();
            throw new ComponentException(
                    "cannot start PostgreSQL container store (" + image + "): " + e.getMessage(), e);
        }
    }

    @Override
    public void stop(StopMode mode) {
        running = false;
        for (Connection c : connections) {
            try {
                c.close();
            } catch (SQLException ignored) {
                // 尽力而为
            }
        }
        connections.clear();
        activeConnections.set(0);
        stopContainerQuietly();
        fire(Event.sim(mode == StopMode.CRASH ? "sim.store-crashed" : "sim.store-stopped",
                id.value(), Map.of("kind", KIND_CONTAINER)));
    }

    /**
     * 生命周期 {@code restart} 守卫：容器重建换宿主端口 → 已暴露的 JDBC URL 失效且无失败
     * 路径（详见类注释）。{@code ScenarioValidator} 对生命周期动作豁免 {@code supportedFaults}
     * 校验，故此处必须显式拒绝（§7.2「无降级」）。
     */
    @Override
    public void restart() {
        throw new UnsupportedOperationException("restart is not supported on container tier "
                + "(Testcontainers remaps the host port on container recreation; the JDBC URL "
                + "exposed to the SUT would be stale with no failure path — no degradation "
                + "path, §7.2)");
    }

    @Override
    public HealthReport health() {
        if (!running) {
            return HealthReport.down("PostgreSQL container store not running");
        }
        PostgreSQLContainer c = container;
        if (c == null) {
            return HealthReport.down("PostgreSQL container is not running");
        }
        try {
            // 容器句柄状态（2.0.5 下经 ContainerState.isRunning()：containerId 为空直接 false，
            // Docker 侧异常被其内部吞掉；此处再兜一层，保证 health() 永不抛异常）
            if (!c.isRunning()) {
                return HealthReport.down("PostgreSQL container is not running");
            }
        } catch (RuntimeException e) {
            return HealthReport.down("PostgreSQL container state unavailable: " + e.getMessage());
        }
        try (Connection conn = DriverManager.getConnection(jdbcUrl)) {
            return conn.isValid(1) ? HealthReport.ok()
                    : HealthReport.down("PostgreSQL connection not valid");
        } catch (SQLException e) {
            return HealthReport.down("PostgreSQL unreachable: " + e.getMessage());
        }
    }

    @Override
    public List<ExposedEndpoint> endpoints() {
        return running
                ? List.of(new ExposedEndpoint(Contract.STORE, "jdbc", jdbcUrl))
                : List.of();
    }

    @Override
    public EndpointShape declaredShape() {
        return EndpointShape.THIRD_PARTY;
    }

    // ---- StoreContract ----

    @Override
    public String jdbcUrl() {
        return jdbcUrl;
    }

    @Override
    public Connection openConnection() {
        requireRunning();
        try {
            Connection c = DriverManager.getConnection(jdbcUrl);
            connections.add(c);
            activeConnections.incrementAndGet();
            fire(Event.sim("sim.store-connection-opened", id.value(),
                    Map.of("active", activeConnections.get(), "kind", KIND_CONTAINER)));
            return c;
        } catch (SQLException e) {
            throw new ComponentException("PostgreSQL connection failed: " + e.getMessage(), e);
        }
    }

    @Override
    public void inTransaction(Consumer<Connection> work) {
        requireRunning();
        try (Connection c = DriverManager.getConnection(jdbcUrl)) {
            activeConnections.incrementAndGet();
            boolean autoCommit = c.getAutoCommit();
            c.setAutoCommit(false);
            try {
                work.accept(c);
                c.commit();
            } catch (RuntimeException e) {
                try {
                    c.rollback();
                } catch (SQLException ignored) {
                    // 回滚失败已由原始异常主导
                }
                throw e;
            } finally {
                c.setAutoCommit(autoCommit);
            }
        } catch (SQLException e) {
            throw new ComponentException("PostgreSQL transaction failed: " + e.getMessage(), e);
        } finally {
            activeConnections.decrementAndGet();
        }
    }

    @Override
    public long executedStatements() {
        return executedStatements.get();
    }

    @Override
    public int activeConnections() {
        return activeConnections.get();
    }

    /** 语句计数入口（SQL 执行处调用；SUT/测试可自行报告）。 */
    public void recordStatement(String sql, long millis) {
        executedStatements.incrementAndGet();
        if (millis >= slowQueryMillis) {
            slowQueries.merge(sql, millis, Math::max);
            fire(Event.sim("sim.store-slow-query", id.value(),
                    Map.of("sql", sql, "millis", millis, "kind", KIND_CONTAINER)));
        }
    }

    /** 慢查询记录（诊断/测试用）。 */
    public Map<String, Long> slowQueries() {
        return Map.copyOf(slowQueries);
    }

    /** 容器内 PostgreSQL 镜像（诊断/测试用）。 */
    public String image() {
        return image;
    }

    // ---- internal ----

    /**
     * 端点 URL ＝ 容器 JDBC URL + 内嵌凭据。
     *
     * <p>为什么内嵌：{@code endpoints()} 暴露的地址要让 SUT **只凭一个 URL** 就能连上
     * （与 H2 档「URL 自足」的手感一致）。Testcontainers 1.20.4 的
     * {@code PostgreSQLContainer.getJdbcUrl()} 只给 {@code jdbc:postgresql://host:port/db}
     * 不带 user/password，故此处显式补上；分隔符按 URL 是否已带查询串选择。
     */
    private static String jdbcUrlWithCredentials(PostgreSQLContainer c) {
        return appendCredentials(c.getJdbcUrl(), c.getUsername(), c.getPassword());
    }

    /**
     * 拼装带凭据的 JDBC URL（包级可见以便无 Docker 直接单测——容器启动后才能拿到
     * {@code getJdbcUrl()}，故把纯字符串逻辑拆出来，使该逻辑在任何机器上可验证）。
     */
    static String appendCredentials(String baseUrl, String username, String password) {
        String separator = baseUrl.contains("?") ? "&" : "?";
        return baseUrl + separator + "user=" + username + "&password=" + password;
    }

    private void stopContainerQuietly() {
        PostgreSQLContainer c = container;
        if (c != null) {
            try {
                c.stop();
            } catch (RuntimeException ignored) {
                // 尽力而为
            }
            container = null;
        }
    }

    private void requireRunning() {
        if (!running) {
            throw new ComponentException("PostgreSQL container store is not running: " + id);
        }
    }

    private void fire(Event e) {
        if (ctx != null) {
            ctx.eventBus().publish(e);
        }
    }
}
