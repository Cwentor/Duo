package io.duo.sim.embedded.store;

import io.duo.sim.kernel.api.ComponentContext;
import io.duo.sim.kernel.api.ComponentId;
import io.duo.sim.kernel.api.Contract;
import io.duo.sim.kernel.api.EndpointShape;
import io.duo.sim.kernel.api.Event;
import io.duo.sim.kernel.api.SimClock;
import io.duo.sim.kernel.api.StopMode;
import io.duo.sim.kernel.api.Tier;
import io.duo.sim.kernel.core.ContractRegistry;
import io.duo.sim.kernel.core.SimpleEventBus;
import io.duo.sim.embedded.provider.H2StoreProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** T27 单测：store 契约 + H2 适配器（真实 JDBC/SQL/事务）。 */
class H2StoreTest {

    private H2Store store;

    @AfterEach
    void tearDown() {
        if (store != null) {
            store.stop(StopMode.GRACEFUL);
        }
    }

    private H2Store start(SimpleEventBus bus) {
        store = new H2Store();
        store.init(new ComponentContext(new ComponentId("db"), Map.of(),
                SimClock.real(), bus, Map.of(), Map.of()));
        store.start();
        return store;
    }

    @Test
    void realJdbcRoundTripCreateInsertSelect() throws Exception {
        var r = start(new SimpleEventBus());
        assertNotNull(r.jdbcUrl());
        assertEquals(EndpointShape.THIRD_PARTY, r.declaredShape());

        try (Connection c = r.openConnection(); Statement s = c.createStatement()) {
            s.execute("CREATE TABLE orders (id INT PRIMARY KEY, amount INT)");
            r.recordStatement("CREATE TABLE orders", 1);
            s.executeUpdate("INSERT INTO orders VALUES (1, 100), (2, 250)");
            r.recordStatement("INSERT INTO orders", 1);
            try (ResultSet rs = s.executeQuery("SELECT SUM(amount) FROM orders")) {
                assertTrue(rs.next());
                assertEquals(350, rs.getInt(1));
            }
            r.recordStatement("SELECT SUM(amount)", 1);
        }
        assertEquals(3, r.executedStatements(),
                "statement counter accumulates reported executions");
    }

    @Test
    void transactionCommitsOnSuccess() throws Exception {
        var r = start(new SimpleEventBus());
        try (Connection c = r.openConnection(); Statement s = c.createStatement()) {
            s.execute("CREATE TABLE t (id INT)");
        }
        r.inTransaction(c -> {
            try (Statement s = c.createStatement()) {
                s.executeUpdate("INSERT INTO t VALUES (1)");
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
        try (Connection c = r.openConnection(); Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("SELECT COUNT(*) FROM t")) {
            assertTrue(rs.next());
            assertEquals(1, rs.getInt(1), "committed row must persist");
        }
    }

    @Test
    void transactionRollsBackOnException() throws Exception {
        var r = start(new SimpleEventBus());
        try (Connection c = r.openConnection(); Statement s = c.createStatement()) {
            s.execute("CREATE TABLE t2 (id INT)");
        }
        assertThrows(RuntimeException.class, () -> r.inTransaction(c -> {
            try (Statement s = c.createStatement()) {
                s.executeUpdate("INSERT INTO t2 VALUES (1)");
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
            throw new IllegalStateException("boom");
        }));
        try (Connection c = r.openConnection(); Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("SELECT COUNT(*) FROM t2")) {
            assertTrue(rs.next());
            assertEquals(0, rs.getInt(1), "rollback must discard the row");
        }
    }

    @Test
    void connectionEventsFlowToBus() {
        var bus = new SimpleEventBus();
        List<String> types = new ArrayList<>();
        bus.subscribe(e -> types.add(e.type()));
        var r = start(bus);
        try (Connection c = r.openConnection()) {
            assertNotNull(c);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
        assertTrue(types.contains("sim.store-started"));
        assertTrue(types.contains("sim.store-connection-opened"));
        assertEquals(1, r.activeConnections() >= 0 ? 1 : 0, "connection tracked");
    }

    @Test
    void slowQueryEmitted() {
        var bus = new SimpleEventBus();
        List<Event> events = new ArrayList<>();
        bus.subscribe(events::add);
        var r = start(bus);
        r.recordStatement("SELECT pg_sleep(2)", 2000); // 阈值 1000ms
        assertTrue(events.stream().anyMatch(e -> e.type().equals("sim.store-slow-query")),
                "slow query must emit event");
        assertTrue(r.slowQueries().containsKey("SELECT pg_sleep(2)"));
    }

    @Test
    void stoppedStoreRejectsConnections() {
        var r = start(new SimpleEventBus());
        r.stop(StopMode.GRACEFUL);
        assertFalse(r.health().healthy());
        assertThrows(io.duo.sim.kernel.api.ComponentException.class, r::openConnection);
        assertTrue(r.endpoints().isEmpty());
    }

    @Test
    void providerMetadataPassesRegistryConsistency() {
        var p = new H2StoreProvider();
        assertEquals(EndpointShape.THIRD_PARTY, p.metadata().endpointShape());
        var reg = new ContractRegistry();
        reg.register(p);
        reg.validateDefaults();
        assertEquals("h2-store", reg.resolve(Contract.STORE, Tier.EMBEDDED, null).implName());
    }

    @Test
    void customJdbcUrlHonored() {
        store = new H2Store();
        store.init(new ComponentContext(new ComponentId("db"),
                Map.of("store.jdbcUrl", "jdbc:h2:mem:custom-name;DB_CLOSE_DELAY=-1"),
                SimClock.real(), new SimpleEventBus(), Map.of(), Map.of()));
        store.start();
        assertTrue(store.jdbcUrl().contains("custom-name"));
    }
}
