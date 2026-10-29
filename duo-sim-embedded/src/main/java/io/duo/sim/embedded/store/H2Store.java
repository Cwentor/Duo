package io.duo.sim.embedded.store;

import io.duo.sim.kernel.api.ComponentContext;
import io.duo.sim.kernel.api.CapabilityMetadata;
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
 * 连接/事务/计量层与 container 档共享同一骨架（{@link JdbcStoreSupport}，评审 R1）；
 * 本类只保留 H2 特有面：URL 生成与探活、restart 同 URL 语义、health。
 */
public final class H2Store extends JdbcStoreSupport implements VirtualComponent {

    private volatile ComponentId id;
    private volatile ComponentContext ctx;
    private volatile String jdbcUrl;
    private volatile long slowQueryMillis = 1000;

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
            markRunning(true);
            // 安全审计 2026-09-20 复核 M-5：embedded 档同源——store.jdbcUrl 可由输入指定，
            // 于是连接串（可能带 user/password）会经事件流进 events.jsonl 与 CI artifact。
            // 故这里也掩码凭据：与 container 档同一把尺子（报告当初只点了 container 档）。
            fire(Event.sim("sim.store-started", id.value(),
                    Map.of("jdbcUrl", CapabilityMetadata.redactUrlCredentials(
                            jdbcUrl, List.of(String.valueOf(ctx.config().get(KEY_JDBC_URL)))))));
        } catch (Exception e) {
            throw new ComponentException("cannot start H2 store: " + e.getMessage(), e);
        }
    }

    @Override
    public void stop(StopMode mode) {
        markRunning(false);
        closeTrackedConnections();
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
        if (!isRunning()) {
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
        return isRunning()
                ? List.of(new ExposedEndpoint(io.duo.sim.kernel.api.Contract.STORE,
                        "jdbc", jdbcUrl))
                : List.of();
    }

    @Override
    public EndpointShape declaredShape() {
        return EndpointShape.THIRD_PARTY;
    }

    // ---- JdbcStoreSupport 钩子（差异点如实声明）----

    @Override
    protected String meteringLabel() {
        return "H2";
    }

    @Override
    protected String notRunningPrefix() {
        return "H2 store is not running";
    }

    @Override
    protected Map<String, Object> meteringEventExtras() {
        return Map.of();
    }

    @Override
    protected ComponentId meteringId() {
        return id;
    }

    @Override
    protected long slowQueryMillis() {
        return slowQueryMillis;
    }

    @Override
    protected void fireMeteringEvent(Event e) {
        fire(e);
    }

    // ---- StoreContract ----

    @Override
    public String jdbcUrl() {
        return jdbcUrl;
    }

    // ---- internal ----

    private void fire(Event e) {
        if (ctx != null) {
            ctx.eventBus().publish(e);
        }
    }
}
