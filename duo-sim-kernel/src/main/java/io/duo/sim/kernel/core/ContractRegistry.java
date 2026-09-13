package io.duo.sim.kernel.core;

import io.duo.sim.kernel.api.CapabilityMetadata;
import io.duo.sim.kernel.api.Contract;
import io.duo.sim.kernel.api.FaultInjectable;
import io.duo.sim.kernel.api.InstanceControl;
import io.duo.sim.kernel.api.Tier;
import io.duo.sim.kernel.api.VirtualComponent;
import io.duo.sim.kernel.spi.ComponentProvider;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;

/**
 * 契约注册表（§7.5）：ServiceLoader 加载 + 注册期一致性校验 + 缺省唯一性。
 * 启动前所有校验（§8 规则 1/3/6）只读本表暴露的静态能力，不触碰实例。
 */
public final class ContractRegistry {

    /** 一致性/缺省唯一性违规。 */
    public static class RegistrationException extends RuntimeException {
        public RegistrationException(String message) {
            super(message);
        }
    }

    private final Map<Key, List<ComponentProvider>> providers = new HashMap<>();

    private record Key(Contract contract, Tier tier) {
    }

    /** 加载 classpath 上全部 ServiceLoader provider。 */
    public static ContractRegistry loadFromServiceLoader() {
        ContractRegistry r = new ContractRegistry();
        for (ComponentProvider p : ServiceLoader.load(ComponentProvider.class)) {
            r.register(p);
        }
        return r;
    }

    /** 注册（含一致性校验，违规抛 {@link RegistrationException}）。 */
    public void register(ComponentProvider p) {
        if (p == null) {
            throw new RegistrationException("provider must not be null");
        }
        CapabilityMetadata m = p.metadata();
        if (m == null) {
            throw new RegistrationException("capability metadata must not be null: " + p.implName());
        }
        Class<?> implClass = p.newComponent().getClass();

        if (m.endpointShape() == io.duo.sim.kernel.api.EndpointShape.NONE && !m.interfaceDirect()) {
            throw new RegistrationException("implementation declared with endpointShape=NONE "
                    + "must provide interfaceDirect=true (no consumption path otherwise): "
                    + p.contract() + "/" + p.tier() + "/" + p.implName());
        }
        if (m.instanceControl() && !InstanceControl.class.isAssignableFrom(implClass)) {
            throw new RegistrationException("metadata.instanceControl=true but implementation "
                    + "does not implement InstanceControl: " + implClass.getName());
        }
        if (!m.supportedFaults().isEmpty() && !FaultInjectable.class.isAssignableFrom(implClass)) {
            throw new RegistrationException("metadata.supportedFaults non-empty but implementation "
                    + "does not implement FaultInjectable: " + implClass.getName());
        }

        Key key = new Key(p.contract(), p.tier());
        List<ComponentProvider> list = providers.computeIfAbsent(key, k -> new ArrayList<>());
        boolean dupName = list.stream().anyMatch(x -> x.implName().equals(p.implName()));
        if (dupName) {
            throw new RegistrationException("duplicate implName within (contract, tier): "
                    + key + "/" + p.implName());
        }
        list.add(p);
    }

    /**
     * 校验已注册的每个 (contract, tier) 组恰好一个缺省实现（§7.5 确定性）。
     * 在场景启动前由 ScenarioEngine 调用；违规抛 {@link RegistrationException}。
     */
    public void validateDefaults() {
        for (Map.Entry<Key, List<ComponentProvider>> e : providers.entrySet()) {
            long defaults = e.getValue().stream().filter(ComponentProvider::isDefault).count();
            if (defaults == 0) {
                throw new RegistrationException("no default implementation for " + e.getKey());
            }
            if (defaults > 1) {
                throw new RegistrationException("multiple default implementations for "
                        + e.getKey() + " (found " + defaults + ")");
            }
        }
    }

    /** 解析实现（规则 1/3：契约注册 + 档位有实现 + interface-direct 目标提供同进程适配的前提）。 */
    public ComponentProvider resolve(Contract contract, Tier tier, String implName) {
        List<ComponentProvider> list = providers.get(new Key(contract, tier));
        if (list == null || list.isEmpty()) {
            throw new RegistrationException("no implementation registered for "
                    + contract + "/" + tier);
        }
        if (implName != null) {
            return list.stream().filter(p -> p.implName().equals(implName)).findFirst()
                    .orElseThrow(() -> new RegistrationException(
                            "impl not found: " + implName + " in " + contract + "/" + tier));
        }
        return list.stream().filter(ComponentProvider::isDefault).findFirst()
                .orElseThrow(() -> new RegistrationException(
                        "no default implementation for " + contract + "/" + tier));
    }

    /** 是否存在该 (contract, tier) 的实现。 */
    public boolean hasImplementation(Contract contract, Tier tier) {
        List<ComponentProvider> list = providers.get(new Key(contract, tier));
        return list != null && !list.isEmpty();
    }
}
