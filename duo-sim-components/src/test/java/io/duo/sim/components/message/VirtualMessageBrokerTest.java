package io.duo.sim.components.message;

import io.duo.sim.components.provider.VirtualMessageBrokerProvider;
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

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * message 契约 virtual 档单测（M5 交付物 1）：顺序/隔离/订阅观察/容量显式拒绝。
 */
class VirtualMessageBrokerTest {

    private final SimpleEventBus bus = new SimpleEventBus();
    private final List<Event> events = new ArrayList<>();
    private VirtualMessageBroker broker;

    private VirtualMessageBroker start(Map<String, String> config) throws Exception {
        bus.subscribe(events::add);
        broker = new VirtualMessageBroker();
        broker.init(new ComponentContext(new ComponentId("mq"), config,
                SimClock.real(), bus, Map.of(), Map.of()));
        broker.start();
        return broker;
    }

    @AfterEach
    void tearDown() {
        if (broker != null) {
            broker.stop(StopMode.GRACEFUL);
        }
    }

    @Test
    void publishDrainKeepsOrderPerTopic() throws Exception {
        start(Map.of());
        broker.publish("orders", "m1");
        broker.publish("orders", "m2");
        broker.publish("events", "e1");
        assertEquals(2, broker.depth("orders"));
        assertEquals(List.of("m1", "m2"), broker.drain("orders"));
        assertEquals(0, broker.depth("orders"), "drain 后清空");
        assertEquals(List.of("e1"), broker.drain("events"), "主题之间互不干扰");
        assertEquals(List.of("orders", "events"), broker.topics());
        assertTrue(events.stream().anyMatch(e -> "sim.message-published".equals(e.type())));
    }

    @Test
    void subscriberObservesMessagesWithoutConsumingThem() throws Exception {
        start(Map.of());
        List<String> seen = new ArrayList<>();
        AutoCloseable sub = broker.subscribe("orders", seen::add);
        broker.publish("orders", "a");
        broker.publish("orders", "b");
        assertEquals(List.of("a", "b"), seen, "订阅按发布顺序同步回调");
        assertEquals(List.of("a", "b"), broker.drain("orders"), "订阅是观察，不消费");
        sub.close();
        broker.publish("orders", "c");
        assertEquals(List.of("a", "b"), seen, "取消订阅后不再回调");
    }

    @Test
    void exceedingMaxDepthIsRejectedExplicitly() throws Exception {
        start(Map.of("message.maxDepthPerTopic", "2"));
        broker.publish("orders", "m1");
        broker.publish("orders", "m2");
        var ex = assertThrows(ComponentException.class, () -> broker.publish("orders", "m3"));
        assertTrue(ex.getMessage().contains("maxDepthPerTopic"), ex.getMessage());
        assertEquals(2, broker.depth("orders"), "被拒的消息不得入队");
    }

    @Test
    void blankTopicIsRejectedAndUnknownTopicIsEmpty() throws Exception {
        start(Map.of());
        assertThrows(IllegalArgumentException.class, () -> broker.publish("", "x"));
        assertEquals(0, broker.depth("never-used"));
        assertTrue(broker.drain("never-used").isEmpty());
    }

    @Test
    void operationsBeforeStartAndAfterStopFailExplicitly() throws Exception {
        bus.subscribe(events::add);
        broker = new VirtualMessageBroker();
        broker.init(new ComponentContext(new ComponentId("mq"), Map.of(),
                SimClock.real(), bus, Map.of(), Map.of()));
        assertThrows(ComponentException.class, () -> broker.publish("t", "x"));
        broker.start();
        broker.publish("t", "x");
        broker.stop(StopMode.GRACEFUL);
        assertThrows(ComponentException.class, () -> broker.publish("t", "y"));
        assertTrue(events.stream().anyMatch(e -> "sim.message-stopped".equals(e.type())));
    }

    @Test
    void restartClearsTopicsAndSubscribers() throws Exception {
        start(Map.of());
        broker.publish("orders", "m1");
        broker.subscribe("orders", m -> { });
        broker.restart();
        assertTrue(broker.topics().isEmpty(), "重启＝内部状态全新");
        assertTrue(events.stream().anyMatch(e -> "sim.message-restarted".equals(e.type())));
    }

    @Test
    void providerMetadataIsInProcessDirect() {
        var p = new VirtualMessageBrokerProvider();
        assertEquals(Contract.MESSAGE, p.contract());
        assertEquals(Tier.VIRTUAL, p.tier());
        assertEquals("virtual-message-broker", p.implName());
        assertTrue(p.isDefault());
        // §7.5 强制一致性：NONE ⇒ interfaceDirect
        assertEquals(EndpointShape.NONE, p.metadata().endpointShape());
        assertTrue(p.metadata().interfaceDirect());
    }
}
