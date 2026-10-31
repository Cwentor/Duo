package io.duo.sim.embedded.store;

import io.duo.sim.kernel.api.ComponentId;
import io.duo.sim.kernel.api.ComponentException;
import io.duo.sim.kernel.api.Event;
import io.duo.sim.kernel.contract.StoreContract;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * store 契约的 JDBC 计量/连接共享骨架（评审 R1，2026-10-30）：{@link H2Store} 与
 * {@link PostgresContainerStore} 的连接打开、事务模板、语句/慢查询计量与 stop 连接清理
 * 此前整块复制（~60 行逐字同，diff 仅异常消息与 kind 载荷）——registry 侧 M4 T38
 * （ZkBackedRegistry）收敛过的同型问题在 store 侧重演。并发敏感语义（计数原子性、
 * 事务回滚路径）自此单点维护，任何一处的修复不再需要两处同步。
 *
 * <p>子类差异经钩子如实声明（不做假收敛），逐条对照原两实现的 diff：
 * <ul>
 *   <li>{@link #meteringLabel()}——异常消息前缀（"H2" / "PostgreSQL"）；</li>
 *   <li>{@link #notRunningPrefix()}——{@code requireRunning} 消息（PG 多 "container" 中缀）；</li>
 *   <li>{@link #meteringEventExtras()}——计量事件载荷附加键（container 档
 *       {@code kind=container}，与 ZookeeperContainerRegistry 的诊断口径一致；embedded 档空表；
 *       合并次序＝基础键在前、附加键在后，与原载荷键序一致）；</li>
 *   <li>{@link #meteringId()} / {@link #slowQueryMillis()} / {@link #fireMeteringEvent(Event)}
 *       ——事件源 id、慢查询阈值与发布通道归子类（init 解析与 ctx 持有不动）。</li>
 * </ul>
 *
 * <p>生命周期归子类（H2 内存库 vs 容器进程是两档的本质差异，本类只持有
 * {@code running} 标志与其读写入口）；{@code jdbcUrl()} 仍由 StoreContract 声明、子类实现。
 */
abstract class JdbcStoreSupport implements StoreContract {

    private final AtomicLong executedStatements = new AtomicLong();
    private final AtomicInteger activeConnections = new AtomicInteger();
    /** 经 {@link #openConnection()} 交给 SUT/测试、由组件在 stop 时兜底关闭的连接。 */
    private final List<Connection> connections = new CopyOnWriteArrayList<>();
    private final Map<String, Long> slowQueries = new ConcurrentHashMap<>();
    private volatile boolean running;

    /** 异常消息前缀（{@code "H2"} / {@code "PostgreSQL"}）。 */
    protected abstract String meteringLabel();

    /** {@code requireRunning} 消息前缀（PG 带 {@code container} 中缀，历史消息保持不变）。 */
    protected abstract String notRunningPrefix();

    /** 计量事件载荷附加键（无附加键返回空表）。 */
    protected abstract Map<String, Object> meteringEventExtras();

    /** 计量事件源 id（组件 id；生命周期字段归子类）。 */
    protected abstract ComponentId meteringId();

    /** 慢查询阈值毫秒（{@code store.slowQueryMillis}，子类 init 解析）。 */
    protected abstract long slowQueryMillis();

    /** 计量事件发布通道（子类持 ctx；ctx 未注入时不发布）。 */
    protected abstract void fireMeteringEvent(Event e);

    // ---- 生命周期标志（子类 start/stop 经此读写；health 亦消费）----

    protected final void markRunning(boolean value) {
        this.running = value;
    }

    protected final boolean isRunning() {
        return running;
    }

    // ---- StoreContract：连接与事务模板（两档唯一实现）----

    @Override
    public Connection openConnection() {
        requireRunning();
        try {
            Connection c = DriverManager.getConnection(jdbcUrl());
            connections.add(c);
            activeConnections.incrementAndGet();
            fireMeteringEvent(Event.sim("sim.store-connection-opened", meteringId().value(),
                    withExtras(Map.of("active", activeConnections.get()))));
            return c;
        } catch (SQLException e) {
            throw new ComponentException(meteringLabel() + " connection failed: "
                    + e.getMessage(), e);
        }
    }

    @Override
    public void inTransaction(Consumer<Connection> work) {
        requireRunning();
        try (Connection c = DriverManager.getConnection(jdbcUrl())) {
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
            throw new ComponentException(meteringLabel() + " transaction failed: "
                    + e.getMessage(), e);
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
        if (millis >= slowQueryMillis()) {
            slowQueries.merge(sql, millis, Math::max);
            fireMeteringEvent(Event.sim("sim.store-slow-query", meteringId().value(),
                    withExtras(Map.of("sql", sql, "millis", millis))));
        }
    }

    /** 慢查询记录（诊断/测试用）。 */
    public Map<String, Long> slowQueries() {
        return Map.copyOf(slowQueries);
    }

    // ---- internal ----

    /** stop 清理段（两档逐字同）：兜底关闭经 {@link #openConnection()} 交出的连接并清零计数。 */
    protected final void closeTrackedConnections() {
        for (Connection c : connections) {
            try {
                c.close();
            } catch (SQLException ignored) {
                // 尽力而为
            }
        }
        connections.clear();
        activeConnections.set(0);
    }

    private void requireRunning() {
        if (!running) {
            throw new ComponentException(notRunningPrefix() + ": " + meteringId());
        }
    }

    /** 计量事件载荷：基础键在前、附加键在后（与收敛前两档各自的键序一致）。 */
    private Map<String, Object> withExtras(Map<String, Object> base) {
        Map<String, Object> extras = meteringEventExtras();
        if (extras.isEmpty()) {
            return base;
        }
        Map<String, Object> merged = new LinkedHashMap<>(base);
        merged.putAll(extras);
        return Map.copyOf(merged);
    }
}
