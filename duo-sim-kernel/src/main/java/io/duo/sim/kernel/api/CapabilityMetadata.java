package io.duo.sim.kernel.api;

import java.util.Set;

/**
 * 能力元数据（§7.5）：实现注册时声明的静态能力，**启动前校验的唯一事实源**。
 *
 * <p>注册期一致性校验（§7.5）：{@code endpointShape=NONE ⇒ interfaceDirect=true}；
 * {@code instanceControl=true} 必须实现 InstanceControl；{@code supportedFaults} 非空
 * 必须实现 FaultInjectable。由 ContractRegistry 强制。
 *
 * <p><b>两个字段互相独立</b>（设计 §7.5 原文：「v2：两字段独立」）：{@code interfaceDirect} 只说明
 * 「同进程能不能直接拿到接口对象」，{@code endpointShape} 只说明「有没有可拨号的端点」。两者都成立的
 * 实现是合法的，而且真实存在——{@code VirtualScheduler} 既暴露 DUO_PORT 供 worker 拨号，又实现
 * {@code SchedulerContract} 供同进程的 SUT 用门面调用。消费路径是**逐槽**解析的（{@code WiringResolver}）：
 * 槽上写了 {@code path: direct} 就走门面，写了 {@code path: wire} 或没写而目标有端点就走线协议；
 * 两者互不影响，因为每条槽各自选一条路。
 *
 * <p>{@code trustedConfigKeys}（安全审计 2026-09-20 规则 11 的配套）：控制面收的是**不可信
 * 字节**，{@code config} 里认不出的键默认拒绝——实现若要支持额外的外部可传键，必须在此
 * **显式声明**，而不是靠"没人知道它"获得安全。默认空集 ⇒ 既有实现零改动、向后兼容。
 *
 * <p><b>声明必须真的生效</b>（安全审计复核 M-2）：校验器把本字段与
 * {@code ScenarioValidator.TRUSTED_CONFIG_KEYS} **取并集**（见
 * {@code ScenarioValidator.isTrustedConfigKey(String, Set)}）。复核前这里只有 getter、
 * 没有任何读取点，"组件可自行扩展白名单"是句空承诺；现在它是一条**可执行**的路径。
 */
public record CapabilityMetadata(EndpointShape endpointShape,
                                 boolean interfaceDirect,
                                 boolean instanceControl,
                                 Set<String> supportedFaults,
                                 boolean defaultImpl,
                                 Set<String> trustedConfigKeys) {

    public CapabilityMetadata(EndpointShape endpointShape, boolean interfaceDirect,
                              boolean instanceControl, Set<String> supportedFaults,
                              boolean defaultImpl) {
        this(endpointShape, interfaceDirect, instanceControl, supportedFaults, defaultImpl,
                Set.of());
    }

    public CapabilityMetadata {
        trustedConfigKeys = trustedConfigKeys == null ? Set.of() : Set.copyOf(trustedConfigKeys);
    }

    public static CapabilityMetadata inProcessDirect(Set<String> supportedFaults) {
        return new CapabilityMetadata(EndpointShape.NONE, true, false, supportedFaults, false);
    }

    public static CapabilityMetadata duoPort(boolean instanceControl, Set<String> supportedFaults) {
        return new CapabilityMetadata(EndpointShape.DUO_PORT, false, instanceControl,
                supportedFaults, false);
    }

    /**
     * Duo 线协议端口 **且** 提供进程内 Java 接口（{@code interfaceDirect=true}）。
     *
     * <p>两个字段独立（设计 §7.5 v2），这条组合对「既暴露可拨号端点、又实现契约接口」的实现是合法的
     * （消费路径由 {@code WiringResolver} 逐槽解析：写了 {@code path: direct} 就走门面）。**但用之前
     * 先问一句"接口真的在吗"**：本方法只写元数据，不校验契约接口是否存在，声明了却没有实现＝把一个
     * 校验期错误推迟成运行期错误。示例：{@code VirtualScheduler} 实现了 {@code SchedulerContract}，
     * 它若需要同进程门面就可以用这条；而当前它只被 worker 拨号，所以如实声明 {@code duoPort}。
     */
    public static CapabilityMetadata duoPortWithDirect(boolean instanceControl,
                                                       Set<String> supportedFaults) {
        return new CapabilityMetadata(EndpointShape.DUO_PORT, true, instanceControl,
                supportedFaults, false);
    }

    public CapabilityMetadata withDefault(boolean isDefault) {
        return new CapabilityMetadata(endpointShape, interfaceDirect, instanceControl,
                supportedFaults, isDefault, trustedConfigKeys);
    }

    /** 追加外部可传的 config 键（实现注册时声明；返回新实例）。 */
    public CapabilityMetadata withTrustedConfigKeys(Set<String> keys) {
        return new CapabilityMetadata(endpointShape, interfaceDirect, instanceControl,
                supportedFaults, defaultImpl, keys);
    }

    /**
     * 从连接串里剥掉凭据值（安全审计 2026-09-20 复核 M-5）。
     *
     * <p>放在内核 API 里，是因为**事件载荷是跨模块的公开面**（组件 → 事件总线 → JSONL → CI）：
     * {@code sim.store-started} 回显完整 JDBC URL 会把口令写进被上传、被回读、被 diff 的
     * 审查材料。
     *
     * <p>口径：URL 的**结构**（scheme/host/port/db）是诊断真正需要的信息，保留；凭据段的
     * **值**替换为 {@code <redacted>}，键名保留——"这里本来内嵌了口令"本身是审查者需要
     * 看见的事实，不该被一起抹掉。
     */
    public static String redactUrlCredentials(String url) {
        return redactUrlCredentials(url, null);
    }

    /**
     * 同上，另有明确条目需要抹除时（如 {@code store.jdbcUrl} 的整条自定义连接串）传入。
     *
     * @param extraValues 额外的字面量（非 null 时逐个替换为 {@code <redacted>}）
     */
    public static String redactUrlCredentials(String url, java.util.Collection<String> extraValues) {
        if (url == null || url.isEmpty()) {
            return url;
        }
        String out = url.replaceAll("(?i)\\b(password|passwd|pwd|user|username)\\s*=\\s*[^&;\\s]*",
                "$1=<redacted>");
        if (extraValues != null) {
            for (String v : extraValues) {
                if (v != null && !v.isEmpty()) {
                    out = out.replace(v, "<redacted>");
                }
            }
        }
        return out;
    }
}
