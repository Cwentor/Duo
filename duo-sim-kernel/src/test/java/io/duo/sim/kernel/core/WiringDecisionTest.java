package io.duo.sim.kernel.core;

import io.duo.sim.kernel.api.ComponentContext.ConnectionPath;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * §6.1 接线判定表唯一权威（{@link WiringResolver#decide}）的单元测试。
 *
 * <p>卡 3a 前，同一张判定表在 {@code ScenarioValidator} 规则 2/3（批量收集）与
 * {@code WiringResolver.resolveNode}（抛异常）两处平行实现；判定逻辑自此只此一份，
 * 两侧各自格式化消息。测试覆盖判定表每一行、缺省推断两条、检查顺序与 ok/fail 不变量。
 */
class WiringDecisionTest {

    @Test
    void defaultInferencePrefersWireWhenTargetHasEndpoint() {
        var d = WiringResolver.decide(false, null, true, false);
        assertEquals(ConnectionPath.WIRE, d.path());
        assertNull(d.failure());
    }

    @Test
    void defaultInferenceFallsBackToDirectWithoutEndpoint() {
        var d = WiringResolver.decide(false, null, false, true);
        assertEquals(ConnectionPath.DIRECT, d.path());
        assertNull(d.failure());
    }

    @Test
    void externalConsumerCannotUseDirectEvenWhenInferred() {
        var d = WiringResolver.decide(true, null, false, true);
        assertEquals(WiringResolver.SlotFailure.DIRECT_EXTERNAL_CONSUMER, d.failure());
        assertNull(d.path());
    }

    @Test
    void explicitWireToEndpointlessTargetFails() {
        var d = WiringResolver.decide(false, "wire", false, false);
        assertEquals(WiringResolver.SlotFailure.WIRE_WITHOUT_ENDPOINT, d.failure());
    }

    @Test
    void explicitDirectToNonDirectCapableTargetFails() {
        var d = WiringResolver.decide(false, "direct", true, false);
        assertEquals(WiringResolver.SlotFailure.DIRECT_NOT_SUPPORTED, d.failure());
    }

    @Test
    void externalConsumerCheckedBeforeDirectCapability() {
        // 两个前置都命中时报 DIRECT_EXTERNAL_CONSUMER——与既有 checkDirect 的检查序一致
        var d = WiringResolver.decide(true, "direct", true, false);
        assertEquals(WiringResolver.SlotFailure.DIRECT_EXTERNAL_CONSUMER, d.failure());
    }

    @Test
    void explicitDirectOkWhenTargetSupportsIt() {
        var d = WiringResolver.decide(false, "direct", true, true);
        assertEquals(ConnectionPath.DIRECT, d.path());
        assertNull(d.failure());
    }

    @Test
    void unknownPathValueFailsClosed() {
        // 校验器此前漏掉这一行（垃圾 path 过静态校验、启动期才炸）——判定表现在把它当作一等失败
        var d = WiringResolver.decide(false, "bogus", true, true);
        assertEquals(WiringResolver.SlotFailure.UNKNOWN_PATH, d.failure());
    }

    @Test
    void decisionInvariantsHold() {
        var ok = WiringResolver.decide(false, null, true, false);
        assertNotNull(ok.path());
        assertNull(ok.failure());
        var fail = WiringResolver.decide(false, "bogus", true, true);
        assertNull(fail.path());
        assertNotNull(fail.failure());
    }
}
