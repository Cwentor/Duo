package io.duo.sim.components.provider;

import io.duo.sim.components.filestore.VirtualFilestore;
import io.duo.sim.kernel.api.CapabilityMetadata;
import io.duo.sim.kernel.api.Contract;
import io.duo.sim.kernel.api.EndpointShape;
import io.duo.sim.kernel.api.Tier;
import io.duo.sim.kernel.api.VirtualComponent;
import io.duo.sim.kernel.spi.ComponentProvider;

/**
 * VirtualFilestore 提供者（(FILESTORE, VIRTUAL) 缺省实现，M5 交付物 1）。
 *
 * <p>元数据：{@code endpointShape=FS_PATH}（§7.5 四个形态中唯一表达「文件系统路径」的形态；
 * 校验期只读本静态元数据，不调 {@code endpoints()}）+
 * {@code interfaceDirect=true}（同进程门面可用）+
 * {@code supportedFaults={crash}}（M5 交付物 6：为 filestore 契约提供故障例——挂载丢失；
 * 实现 {@code FaultInjectable}——§7.5 一致性校验要求二者对应。未声明的动作仍被显式拒绝）。
 */
public final class VirtualFilestoreProvider implements ComponentProvider {

    @Override
    public Contract contract() {
        return Contract.FILESTORE;
    }

    @Override
    public Tier tier() {
        return Tier.VIRTUAL;
    }

    @Override
    public String implName() {
        return "virtual-filestore";
    }

    @Override
    public boolean isDefault() {
        return true;
    }

    @Override
    public CapabilityMetadata metadata() {
        return new CapabilityMetadata(EndpointShape.FS_PATH, true, false,
                VirtualFilestore.supportedFaults(), true);
    }

    @Override
    public VirtualComponent newComponent() {
        return new VirtualFilestore();
    }
}
