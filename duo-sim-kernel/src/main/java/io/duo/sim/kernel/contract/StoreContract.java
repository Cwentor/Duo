package io.duo.sim.kernel.contract;

import io.duo.sim.kernel.api.EndpointShape;

import java.sql.Connection;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * store 契约（§5：关系库——连接、事务、方言差异）。**M2 T27 随 H2 适配器反推**（YAGNI：
 * 字段集只包含 H2 实现需要暴露给 SUT/测试的语义，不做通用 JDBC 抽象）。
 *
 * <p>必发事件：连接建立/关闭（{@code sim.store-connection-opened/closed}）、
 * 慢查询（{@code sim.store-slow-query}，阈值可配）。
 * 可观测点：活跃连接数、已执行语句数。
 * 配置项：{@code jdbcUrl}（缺省 h2 内存模式）、{@code slowQueryMillis}。
 *
 * <p>端点形态：embedded 档＝THIRD_PARTY（JDBC URL，SUT 可用真实 JDBC 驱动连接）；
 * virtual 档＝NONE（内存桩，interface-direct）。
 */
public interface StoreContract {

    /** JDBC 连接串（SUT wire 连接用；embedded 档为真实 H2 URL）。 */
    String jdbcUrl();

    /** 打开连接（调用方负责关闭；内核在 SUT 停止时统一清理）。 */
    Connection openConnection();

    /** 在事务中执行（提交/回滚由实现保证；异常回滚）。 */
    void inTransaction(Consumer<Connection> work);

    /** 已执行语句计数（可观测点）。 */
    long executedStatements();

    /** 活跃连接数（可观测点）。 */
    int activeConnections();

    /** 端点形态约定（供能力元数据一致性校验核对）。 */
    EndpointShape declaredShape();

    /** 配置键名（实现与场景共享）。 */
    String KEY_JDBC_URL = "store.jdbcUrl";
    String KEY_SLOW_QUERY_MILLIS = "store.slowQueryMillis";
}
