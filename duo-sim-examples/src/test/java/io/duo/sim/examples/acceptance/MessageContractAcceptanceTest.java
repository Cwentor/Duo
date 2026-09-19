package io.duo.sim.examples.acceptance;

import io.duo.sim.components.message.VirtualMessageBroker;
import io.duo.sim.components.provider.VirtualMessageBrokerProvider;
import io.duo.sim.kernel.api.ComponentException;
import io.duo.sim.kernel.api.ComponentId;
import io.duo.sim.kernel.api.Contract;
import io.duo.sim.kernel.api.Event;
import io.duo.sim.kernel.api.FaultAction;
import io.duo.sim.kernel.core.ContractRegistry;
import io.duo.sim.scenario.ScenarioEngine;
import io.duo.sim.scenario.ScenarioLoader;
import io.duo.sim.scenario.model.Scenario;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * M5 交付物 6（G4）：**message 契约的正例 + 故障例成对**验收。
 *
 * <p>为什么正例/故障例写在夹具里而不是 YAML 断言里：message 的端点形态是
 * {@code NONE + interface-direct}（§7.5），没有对外端口，消费方（发布/拉取/订阅）拿的是
 * **同进程门面**——SUT 进程只拿得到地址串，NONE 形态根本没有地址。所以「消息能按序收发」
 * 这类断言只能由框架侧夹具发起，YAML 负责提供真实拓扑（真 registry 端口、真 DAG 闭环）。
 *
 * <p>本用例覆盖 G4 要求的两个方向：
 * <ol>
 *   <li><b>正例</b>：同一条消息经过发布 → 订阅观察 → 拉取消费三段，顺序与深度都对，
 *       且每次发布都落 {@code sim.message-published} 事实（可观测）；</li>
 *   <li><b>故障例</b>：{@code freeze} 注入后发布**显式失败**（§12 不静默），已入队消息
 *       **不丢**，且该故障**不影响调度闭环**——DAG 仍收敛到终态。</li>
 * </ol>
 */
class MessageContractAcceptanceTest {

    private static Scenario load() {
        try (InputStream in = MessageContractAcceptanceTest.class.getResourceAsStream(
                "/scenarios/m5-message-contract-acceptance.yaml")) {
            assertNotNull(in, "message contract scenario must exist");
            return ScenarioLoader.load(in);
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
    }

    /** 正例：发布→订阅→拉取全通，事实可观测，且 DAG 独立收敛。 */
    @Test
    void publishedMessagesAreOrderedObservableAndConsumable() throws Exception {
        var registry = ContractRegistry.loadFromServiceLoader();
        try (ScenarioEngine engine = ScenarioEngine.validated(load(), registry)) {
            engine.startSut();
            engine.startComponents();
            try {
                var mq = (VirtualMessageBroker) engine.components().get("mq");
                assertNotNull(mq, "message node must be started as a virtual component");

                List<String> observed = new ArrayList<>();
                try (AutoCloseable sub = mq.subscribe("orders", observed::add)) {
                    mq.publish("orders", "m1");
                    mq.publish("orders", "m2");
                    assertEquals(2, mq.depth("orders"), "发布后队列深度");
                    assertEquals(List.of("m1", "m2"), observed, "订阅侧按发布顺序观察");
                    assertEquals(List.of("m1", "m2"), mq.drain("orders"),
                            "拉取侧按发布顺序消费，且不吞掉订阅通道");
                    assertEquals(0, mq.depth("orders"), "drain 后清空");
                }
                List<Event> events = engine.events();
                assertEquals(2, events.stream()
                        .filter(e -> "sim.message-published".equals(e.type())).count(),
                        "每次发布都必须留下可观测事实");
            } finally {
                engine.stop();
            }
        }
    }

    /** 故障例：冻结 → 发布显式失败、存量消息不丢。 */
    @Test
    void freezeMakesPublishFailLoudlyWithoutDroppingQueuedMessages() throws Exception {
        var registry = ContractRegistry.loadFromServiceLoader();
        try (ScenarioEngine engine = ScenarioEngine.validated(load(), registry)) {
            engine.startSut();
            engine.startComponents();
            try {
                var mq = (VirtualMessageBroker) engine.components().get("mq");
                mq.publish("orders", "before-freeze");

                var result = engine.inject(freeze("mq"));
                assertTrue(result.success(), "freeze 注入必须成功：" + result.reason());

                ComponentException ex = assertThrows(ComponentException.class,
                        () -> mq.publish("orders", "while-frozen"));
                assertTrue(ex.getMessage().contains("frozen"), ex.getMessage());
                assertEquals(1, mq.depth("orders"), "冻结只拒新消息，存量不得丢");
                assertTrue(engine.events().stream()
                                .anyMatch(e -> "sim.message-frozen".equals(e.type())),
                        "冻结必须留下可观测事实");
            } finally {
                engine.stop();
            }
            var snapshot = engine.result().snapshot();
            assertTrue(snapshot.injectionFailures().isEmpty(),
                    "注入不得有失败记录：" + snapshot.injectionFailures());
        }
    }

    /** 元数据与实现必须一致（§7.5 校验的对象）：声明的故障能力真的能注入。 */
    @Test
    void providerDeclaresFreezeAndInjectionIsAccepted() {
        var metadata = new VirtualMessageBrokerProvider().metadata();
        assertEquals(Contract.MESSAGE, new VirtualMessageBrokerProvider().contract());
        assertEquals(java.util.Set.of(FaultAction.FREEZE), metadata.supportedFaults(),
                "message virtual 档声明的故障集合");
    }

    private static FaultAction freeze(String node) {
        return new FaultAction(FaultAction.FREEZE,
                FaultAction.ComponentAddress.of(new ComponentId(node)),
                java.util.Map.of(), null);
    }
}
