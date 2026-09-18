package io.duo.sim.components.filestore;

import io.duo.sim.components.provider.VirtualFilestoreProvider;
import io.duo.sim.kernel.api.ComponentContext;
import io.duo.sim.kernel.api.ComponentException;
import io.duo.sim.kernel.api.ComponentId;
import io.duo.sim.kernel.api.Contract;
import io.duo.sim.kernel.api.EndpointShape;
import io.duo.sim.kernel.api.Event;
import io.duo.sim.kernel.api.SimClock;
import io.duo.sim.kernel.api.StopMode;
import io.duo.sim.kernel.api.Tier;
import io.duo.sim.kernel.core.SimpleEventBus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * filestore 契约 virtual 档单测（M5 交付物 1）：真实文件 IO + 越界显式拒绝 + 生命周期语义。
 */
class VirtualFilestoreTest {

    private final SimpleEventBus bus = new SimpleEventBus();
    private final List<Event> events = new ArrayList<>();
    private VirtualFilestore store;

    private VirtualFilestore start(Map<String, String> config) throws Exception {
        bus.subscribe(events::add);
        store = new VirtualFilestore();
        store.init(new ComponentContext(new ComponentId("files"), config,
                SimClock.real(), bus, Map.of(), Map.of()));
        store.start();
        return store;
    }

    @AfterEach
    void tearDown() {
        if (store != null) {
            store.stop(StopMode.GRACEFUL);
        }
    }

    @Test
    void startCreatesRootAndExposesFsPathEndpoint() throws Exception {
        start(Map.of());
        assertTrue(Files.isDirectory(store.root()), "root dir must exist");
        assertEquals(1, store.endpoints().size());
        var ep = store.endpoints().get(0);
        assertEquals(Contract.FILESTORE, ep.contract());
        assertEquals("fs", ep.kind()); // FS_PATH 形态：路径而非 host:port
        assertEquals(store.root().toString(), ep.address());
        assertTrue(events.stream().anyMatch(e -> "sim.filestore-started".equals(e.type())));
        assertTrue(store.health().healthy());
    }

    @Test
    void writeReadListDeleteRoundTrip() throws Exception {
        start(Map.of());
        assertEquals(5, store.write("a/b.txt", "hello"));
        assertEquals("hello", store.read("a/b.txt"));
        assertEquals(List.of("a/b.txt"), store.list());
        store.delete("a/b.txt");
        assertTrue(store.list().isEmpty());
        assertTrue(events.stream().anyMatch(e -> "sim.filestore-written".equals(e.type())));
    }

    @Test
    void pathEscapingRootIsRejectedExplicitly() throws Exception {
        start(Map.of());
        // 越界（../ 逃逸）必须显式拒绝：静默写到根目录之外是真实缺陷来源
        assertThrows(IllegalArgumentException.class, () -> store.resolve("../evil.txt"));
        assertThrows(IllegalArgumentException.class, () -> store.write("../evil.txt", "x"));
        assertThrows(IllegalArgumentException.class, () -> store.resolve(""));
        assertFalse(Files.exists(store.root().getParent().resolve("evil.txt")));
    }

    @Test
    void readMissingFileFailsExplicitly() throws Exception {
        start(Map.of());
        var ex = assertThrows(UncheckedIOException.class, () -> store.read("nope.txt"));
        assertTrue(ex.getCause() instanceof NoSuchFileException,
                () -> "cause 应为 NoSuchFileException，实际 " + ex.getCause());
        // 删除不存在的文件同样显式失败（不假装删成功）
        var del = assertThrows(UncheckedIOException.class, () -> store.delete("nope.txt"));
        assertTrue(del.getCause() instanceof NoSuchFileException,
                () -> "cause 应为 NoSuchFileException，实际 " + del.getCause());
    }

    @Test
    void operationsBeforeStartFailExplicitly() {
        bus.subscribe(events::add);
        store = new VirtualFilestore();
        store.init(new ComponentContext(new ComponentId("files"), Map.of(),
                SimClock.real(), bus, Map.of(), Map.of()));
        assertFalse(store.health().healthy());
        assertTrue(store.endpoints().isEmpty());
        assertThrows(ComponentException.class, () -> store.write("a.txt", "x"));
    }

    @Test
    void stopDeletesOwnedRootButKeepsConfiguredRoot(@TempDir Path configured) throws Exception {
        start(Map.of("filestore.root", configured.toString()));
        assertTrue(Files.isDirectory(configured));
        store.stop(StopMode.GRACEFUL);
        assertTrue(Files.isDirectory(configured), "用户指定的根目录不得被删除");

        start(Map.of());
        Path owned = store.root();
        store.stop(StopMode.GRACEFUL);
        assertFalse(Files.exists(owned), "自建临时根目录应在 stop 时清理");
    }

    @Test
    void restartKeepsRootPathButClearsContent() throws Exception {
        start(Map.of());
        store.write("keep.txt", "v1");
        Path before = store.root();
        store.restart();
        assertEquals(before, store.root(), "§7.1：端点（根路径）与身份保留");
        assertTrue(store.list().isEmpty(), "§7.1：内部状态视为全新实例（内容清空）");
        assertTrue(events.stream().anyMatch(e -> "sim.filestore-restarted".equals(e.type())));
    }

    @Test
    void providerMetadataMatchesContractRules() {
        var p = new VirtualFilestoreProvider();
        assertEquals(Contract.FILESTORE, p.contract());
        assertEquals(Tier.VIRTUAL, p.tier());
        assertEquals("virtual-filestore", p.implName());
        assertTrue(p.isDefault());
        assertEquals(EndpointShape.FS_PATH, p.metadata().endpointShape());
        assertTrue(p.metadata().interfaceDirect(), "同进程门面可用");
        assertTrue(p.metadata().supportedFaults().isEmpty());
    }
}
