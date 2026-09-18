package io.duo.sim.embedded.provider;

import io.duo.sim.embedded.store.PostgresContainerStore;
import io.duo.sim.kernel.api.CapabilityMetadata;
import io.duo.sim.kernel.api.Contract;
import io.duo.sim.kernel.api.EndpointShape;
import io.duo.sim.kernel.api.Tier;
import io.duo.sim.kernel.api.VirtualComponent;
import io.duo.sim.kernel.spi.ComponentProvider;

import java.util.Set;

/**
 * PostgresContainerStore 提供者：(STORE, CONTAINER) 缺省实现（M5）。
 *
 * <p>元数据：{@code endpointShape=THIRD_PARTY}（容器映射端口 + 真实 PostgreSQL 协议，
 * 供 SUT 用真实 JDBC 驱动连接）+
 * {@code interfaceDirect=false}（**无**同进程 Java 门面——store 契约不暴露直连对象，
 * 与 embedded 档 {@link H2StoreProvider} 同形；registry 容器档的 {@code interfaceDirect=true}
 * 是因为它有 RegistryContract 门面，store 没有，不可照抄）+
 * {@code instanceControl=false} + {@code supportedFaults=∅}（container 档不提供任何
 * FaultInjectable 故障注入：容器不可按组件粒度 flap）+ {@code defaultImpl=true}
 * （(STORE, CONTAINER) 档位唯一实现，不影响 (STORE, EMBEDDED) 的 h2-store 缺省）。
 *
 * <p><b>重启能力</b>：容器档**不支持任何形式的整服重启**。{@code supportedFaults=∅} 只覆盖
 * FaultInjectable 类动作；生命周期动作 {@code crash}/{@code restart} 在校验期被 §7.2 设计
 * 豁免（{@code ScenarioValidator} 的 lifecycle 分支），因此**不能**依赖元数据表达
 * 「容器档也不支持 restart」——该守卫已下沉到
 * {@link PostgresContainerStore#restart()} 显式抛 {@code UnsupportedOperationException}
 * （容器重建换宿主端口，已暴露的 JDBC URL 永久失效且无失败路径）。若将来需要支持重启，
 * 需先引入固定宿主端口（{@code PortBinding}）并实现「保端口重建」。
 */
public final class PostgresContainerStoreProvider implements ComponentProvider {

    @Override
    public Contract contract() {
        return Contract.STORE;
    }

    @Override
    public Tier tier() {
        return Tier.CONTAINER;
    }

    @Override
    public String implName() {
        return "pg-container-store";
    }

    @Override
    public boolean isDefault() {
        return true;
    }

    @Override
    public CapabilityMetadata metadata() {
        return new CapabilityMetadata(EndpointShape.THIRD_PARTY, false, false, Set.of(), true);
    }

    @Override
    public VirtualComponent newComponent() {
        return new PostgresContainerStore();
    }
}
