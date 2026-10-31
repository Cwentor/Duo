package io.duo.sim.kernel.api;

import java.util.Map;

/**
 * 对外暴露的协议端点（§7.1/§7.3）。带类型：TCP（host:port）或 FS_PATH（路径），
 * 与 SutContext 端点清单、stdout 行格式 {@code duo.endpoint.<contract>=<endpoint>} 同口径。
 *
 * @param contract 该端点服务的契约
 * @param kind 端点类型（{@link #KIND_TCP} 或 {@link #KIND_FS}）
 * @param address 地址串（tcp＝host:port；fs＝路径）
 */
public record ExposedEndpoint(Contract contract, String kind, String address) {

    /** TCP 端点（address＝host:port）。 */
    public static final String KIND_TCP = "tcp";
    /** 文件系统路径端点（address＝路径；filestore 契约的 FS_PATH 形态）。 */
    public static final String KIND_FS = "fs";

    /** 端点告知行的固定前缀（§7.3 行/键格式 {@code duo.endpoint.<contract>=<endpoint>}；全仓唯一拼写点）。 */
    public static final String LINE_PREFIX = "duo.endpoint.";

    /** 构造 TCP 端点（address＝host:port）。 */
    public static ExposedEndpoint tcp(Contract contract, String host, int port) {
        return new ExposedEndpoint(contract, KIND_TCP, host + ":" + port);
    }

    /** 构造文件系统路径端点。 */
    public static ExposedEndpoint fs(Contract contract, String path) {
        return new ExposedEndpoint(contract, KIND_FS, path);
    }

    /** 行格式端点串（供 stdout 兜底/配置文件，§7.3）。 */
    public String asLine() {
        return LINE_PREFIX + contract.name().toLowerCase() + "=" + address;
    }

    /** 供端点清单/配置文件的键值对。 */
    public Map<String, String> asConfigEntry() {
        return Map.of(LINE_PREFIX + contract.name().toLowerCase(), address);
    }
}
