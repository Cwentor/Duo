package io.duo.sim.embedded.registry;

import io.duo.sim.embedded.provider.ZookeeperContainerProvider;
import io.duo.sim.kernel.api.ComponentId;
import io.duo.sim.kernel.api.Contract;
import io.duo.sim.kernel.api.EndpointShape;
import io.duo.sim.kernel.api.FaultAction;
import io.duo.sim.kernel.api.Tier;
import io.duo.sim.kernel.core.ContractRegistry;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * M4 独立验收缺陷 2 整改：容器档**纯元数据/守卫**用例**脱离 Docker 门控**——
 * 本类不标 {@code @EnabledIf(dockerAvailable)}，无 Docker 环境同样执行，
 * 使「容器档不支持的故障必须显式拒绝、不得静默降级」这一安全属性在任何机器上都被验证。
 *
 * <p>背景（独立验收报告 HIGH）：{@code restart} 属生命周期动作，{@code ScenarioValidator}
 * 对 {@code crash}/{@code restart} 显式豁免 {@code supportedFaults} 校验（§7.2 设计使然），
 * 而 {@link ZkBackedRegistry#restart()} 不经过 {@code inject()}/{@code clear()}，
 * 故容器档的 {@code UnsupportedOperationException} 守卫对其形同虚设——容器重启换宿主端口后
 * wire 客户端（D2：复用旧端口）永远连不上，且无失败路径，表现为永久挂起。
 *
 * <p>本类同时覆盖：SPI 解析不破坏 embedded 档缺省、无 Docker 时 {@code start()} 快速失败
 * 且携带根因（§13「自动 skip」的前提是失败可见、不挂死）。
 */
class ZookeeperContainerRegistryGuardTest {

    private static final FaultAction.ComponentAddress ADDR =
            FaultAction.ComponentAddress.of(new ComponentId("zk"));

    // ---- 元数据（纯静态，无 Docker 依赖）----

    @Test
    void providerMetadataDeclaresNoSupportedFaultsAndNoInstanceControl() {
        var p = new ZookeeperContainerProvider();
        assertEquals(Tier.CONTAINER, p.tier());
        assertEquals(Contract.REGISTRY, p.contract());
        assertEquals("zk-container-registry", p.implName());
        assertTrue(p.isDefault(), "container tier registry is the (registry, container) default");
        assertEquals(EndpointShape.THIRD_PARTY, p.metadata().endpointShape());
        assertTrue(p.metadata().interfaceDirect(), "同进程门面（与 embedded 档 D1b 同构）");
        assertTrue(p.metadata().supportedFaults().isEmpty(),
                "D6：容器档不支持任何 FaultInjectable 类故障");
        assertFalse(p.metadata().instanceControl());
        assertTrue(p.metadata().defaultImpl());
    }

    /** SPI：{@code (registry, container)} 可解析到容器 provider，且 embedded 档缺省未被破坏。 */
    @Test
    void spiResolvesContainerTierWithoutBreakingEmbeddedDefault() {
        var reg = ContractRegistry.loadFromServiceLoader();
        var container = reg.resolve(Contract.REGISTRY, Tier.CONTAINER, null);
        assertEquals("zk-container-registry", container.implName());
        assertTrue(reg.hasImplementation(Contract.REGISTRY, Tier.CONTAINER));

        // 新增容器 provider 不得改变 embedded 档缺省实现
        var embedded = reg.resolve(Contract.REGISTRY, Tier.EMBEDDED, null);
        assertEquals("curator-registry", embedded.implName(),
                "新增容器 provider 不得改变 embedded 档缺省实现");
    }

    // ---- restart 守卫（缺陷 1 的核心；不依赖 Docker，因为守卫必须先于任何容器操作生效）----

    /**
     * 容器档 {@code restart()} 必须**显式拒绝**（§7.2 无降级），不得走
     * {@link ZkBackedRegistry#restart()} 的 stop→start（换宿主端口 → wire 永久连不上）。
     */
    @Test
    void restartOnContainerTierIsExplicitlyRejected() {
        var r = new ZookeeperContainerRegistry();
        var e = assertThrows(UnsupportedOperationException.class, r::restart,
                "容器档 restart 必须抛 UnsupportedOperationException，不得静默重启换端口");
        assertTrue(e.getMessage().contains("restart"),
                "失败原因须点明被拒绝的动作，实际：" + e.getMessage());
        assertTrue(e.getMessage().contains("container"),
                "失败原因须点明档位，实际：" + e.getMessage());
    }

    /** FLAP 热注入同样显式拒绝（既有行为，锁进无 Docker 可跑的用例）。 */
    @Test
    void flapInjectionOnContainerTierIsExplicitlyRejected() {
        var r = new ZookeeperContainerRegistry();
        assertThrows(UnsupportedOperationException.class,
                () -> r.inject(new FaultAction(FaultAction.REGISTRY_FLAP, ADDR, Map.of(), null)));
        assertThrows(UnsupportedOperationException.class,
                () -> r.clear(new FaultAction(FaultAction.REGISTRY_FLAP, ADDR, Map.of(), null)));
    }

    /**
     * guard 必须**先于**任何容器/Docker 访问生效：本机无 Docker 时，若守卫缺失，
     * {@code restart()} 会走到 {@code start()} 并抛 Docker 相关的 {@code ComponentException}
     * ——本用例以「异常类型」把守卫是否前置钉死。
     */
    @Test
    void restartGuardPrecedesAnyDockerAccess() {
        var r = new ZookeeperContainerRegistry();
        Throwable t = assertThrows(Throwable.class, r::restart);
        assertTrue(t instanceof UnsupportedOperationException,
                "守卫须先于 Docker 访问，实际异常：" + t.getClass().getName() + " / " + t.getMessage());
    }
}
