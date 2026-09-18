package io.duo.sim.components.provider;

import io.duo.sim.components.filestore.VirtualFilestore;
import io.duo.sim.kernel.api.CapabilityMetadata;
import io.duo.sim.kernel.api.Contract;
import io.duo.sim.kernel.api.EndpointShape;
import io.duo.sim.kernel.api.Tier;
import io.duo.sim.kernel.api.VirtualComponent;
import io.duo.sim.kernel.spi.ComponentProvider;

import java.util.Set;

/**
 * VirtualFilestore 提供者（(FILESTORE, VIRTUAL) 缺省实现，M5 交付物 1）。
 *
 * <p>元数据：{@code endpointShape=FS_PATH}（§7.5 四个形态中唯一表达「文件系统路径」的形态；
 * 校验期只读本静态元数据，不调 {@code endpoints()}）+
 * {@code interfaceDirect=true}（同进程门面可用）+ {@code supportedFaults=∅}
 * （文件系统桩暂不声明故障动作；未声明的动作在校验期即被拒绝，不做静默降级）。
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
        return new CapabilityMetadata(EndpointShape.FS_PATH, true, false, Set.of(), true);
    }

    @Override
    public VirtualComponent newComponent() {
        return new VirtualFilestore();
    }
}
