/**
 * store 契约的真数据库实现（M2 T27 / M5）：{@link H2Store}（JVM 内 H2 内存库——真实 JDBC、
 * 真实 SQL 引擎，embedded 档）与 {@link PostgresContainerStore}（Testcontainers 真 PostgreSQL
 * 容器——方言、事务、错误语义都是 PostgreSQL 本身的，container 档）。两者端点形态都是
 * THIRD_PARTY（JDBC URL），SUT 用真实 JDBC 驱动连接；store 契约无同进程 Java 门面
 * （interfaceDirect=false）。
 */
package io.duo.sim.embedded.store;
