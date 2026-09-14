package io.duo.sim.embedded.store;

import io.duo.sim.kernel.api.ComponentContext;
import io.duo.sim.kernel.api.ComponentException;
import io.duo.sim.kernel.api.ComponentId;
import io.duo.sim.kernel.api.EndpointShape;
import io.duo.sim.kernel.api.Event;
import io.duo.sim.kernel.api.ExposedEndpoint;
import io.duo.sim.kernel.api.HealthReport;
import io.duo.sim.kernel.api.StopMode;
import io.duo.sim.kernel.api.VirtualComponent;
import io.duo.sim.kernel.contract.StoreContract;

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
 * store 契约的 embedded 档实现（M2 T27）：**H2 内存数据库**（真实 JDBC、真实 SQL 引擎）。
 *
 * <p>端点形态 THIRD_PARTY：{@code endpoints()} 返回 {@code jdbcUrl}，SUT 可用真实 JDBC
 * 驱动连接（与 registry 的 wire 面同思路）。内存模式缺省
 * {@code jdbc:h2:mem:<id>;DB_CLOSE_DELAY=-1}——同一 JVM 内多连接共享库。
 *
 * <p>可观测（§5）：{@code sim.store-connection-opened}、慢查询
 * （{@code sim.store-slow-query}，阈值 {@code store.slowQueryMillis}，缺省 1000ms）。
 * 语句计数与慢查询经 {@link #recordStatement(String, long)} 在 SQL 执行处累计
 * （SUT/测试直接 JDBC 执行时由调用方报告，避免重量级连接代理）。
 */
public final class H2Store implements VirtualComponent, StoreContract {

    private volatile ComponentId id;
    private volatile ComponentContext ctx;
    private volatile boolean running;
    private volatile String jdbcUrl;
    private volatile long slowQueryMillis = 1000;

    private final AtomicLong executedStatements = new AtomicLong();
    private final AtomicInteger activeConnections = new AtomicInteger();
    private final List<Connection> connections = new CopyOnWriteArrayList<>();
    private final Map<String, Long> slowQueries = new ConcurrentHashMap<>();

    @Override
    public ComponentId id() {
        return id;
    }

    @Override
    public void init(ComponentContext ctx) {
        this.ctx = ctx;
        this.id = ctx.id();
        String configured = ctx.config().get(KEY_JDBC_URL);
        this.jdbcUrl = configured != null ? configured
                : "jdbc:h2:mem:duo-" + ctx.id().value() + ";DB_CLOSE_DELAY=-1";
        String slow = ctx.config().get(KEY_SLOW_QUERY_MILLIS);
        if (slow != null) {
            this.slowQueryMillis = Long.parseLong(slow.trim());
        }
    }

    @Override
    public void start() throws ComponentException {
        try {
            Class.forName("org.h2.Driver");
            try (Connection probe = DriverManager.getConnection(jdbcUrl)) {
                probe.isValid(1);
            }
            running = true;
            fire(Event.sim("sim.store-started", id.value(), Map.of("jdbcUrl", jdbcUrl)));
        } catch (Exception e) {
            throw new ComponentException("cannot start H2 store: " + e.getMessage(), e);
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
        fire(Event.sim(mode == StopMode.CRASH ? "sim.store-crashed" : "sim.store-stopped",
                id.value(), Map.of()));
    }

    @Override
    public void restart() {
        stop(StopMode.GRACEFUL);
        start();
        fire(Event.sim("sim.store-restarted", id.value(), Map.of()));
    }

    @Override
    public HealthReport health() {
        if (!running) {
            return HealthReport.down("H2 store not running");
        }
        try (Connection c = DriverManager.getConnection(jdbcUrl)) {
            return c.isValid(1) ? HealthReport.ok() : HealthReport.down("H2 not valid");
        } catch (SQLException e) {
            return HealthReport.down("H2 unreachable: " + e.getMessage());
        }
    }

    @Override
    public List<ExposedEndpoint> endpoints() {
        return running
                ? List.of(new ExposedEndpoint(io.duo.sim.kernel.api.Contract.STORE,
                        "jdbc", jdbcUrl))
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
                    Map.of("active", activeConnections.get())));
            return c;
        } catch (SQLException e) {
            throw new ComponentException("H2 connection failed: " + e.getMessage(), e);
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
            throw new ComponentException("H2 transaction failed: " + e.getMessage(), e);
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
                    Map.of("sql", sql, "millis", millis)));
        }
    }

    /** 慢查询记录（诊断/测试用）。 */
    public Map<String, Long> slowQueries() {
        return Map.copyOf(slowQueries);
    }

    private void requireRunning() {
        if (!running) {
            throw new ComponentException("H2 store is not running: " + id);
        }
    }

    private void fire(Event e) {
        if (ctx != null) {
            ctx.eventBus().publish(e);
        }
    }
}
