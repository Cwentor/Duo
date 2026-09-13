package io.duo.sim.kernel.api;

import java.util.Map;

/**
 * 对外暴露的协议端点（§7.1/§7.3）。带类型：TCP（host:port）或 FS_PATH（路径），
 * 与 SutContext 端点清单、stdout 行格式 {@code duo.endpoint.<contract>=<endpoint>} 同口径。
 */
public record ExposedEndpoint(Contract contract, String kind, String address) {

    public static final String KIND_TCP = "tcp";
    public static final String KIND_FS = "fs";

    public static ExposedEndpoint tcp(Contract contract, String host, int port) {
        return new ExposedEndpoint(contract, KIND_TCP, host + ":" + port);
    }

    public static ExposedEndpoint fs(Contract contract, String path) {
        return new ExposedEndpoint(contract, KIND_FS, path);
    }

    /** 行格式端点串（供 stdout 兜底/配置文件，§7.3）。 */
    public String asLine() {
        return "duo.endpoint." + contract.name().toLowerCase() + "=" + address;
    }

    /** 供端点清单/配置文件的键值对。 */
    public Map<String, String> asConfigEntry() {
        return Map.of("duo.endpoint." + contract.name().toLowerCase(), address);
    }
}
