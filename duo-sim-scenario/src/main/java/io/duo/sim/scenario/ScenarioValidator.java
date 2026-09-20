package io.duo.sim.scenario;

import io.duo.sim.kernel.api.CapabilityMetadata;
import io.duo.sim.kernel.api.Contract;
import io.duo.sim.kernel.api.Tier;
import io.duo.sim.kernel.core.ContractRegistry;
import io.duo.sim.scenario.model.Scenario;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 场景校验器（§8）：规则 1–8 全量，外加规则 9–11（安全审计 2026-09-20 的信任边界）。
 * 规则 1（契约注册/档位实现/交互型档位）、3（路径前提）读注册表静态元数据；
 * 规则 2 做简写补全与静态一致性检查；6 校验时间线动作；7 校验剧本绑定；
 * 8 固定端口可用性；4 external 节点端点声明与 ready 探针。
 *
 * <p><b>规则 9–11 只在 {@link InputTrust#EXTERNAL} 档生效</b>（控制面 {@code POST /scenario}
 * 等外部输入）。它们不改变「本机 YAML 文件」的能力——本地文件与命令行同级信任，
 * 能力保持原样（这张网只兜外部输入）。
 */
public final class ScenarioValidator {

    /** 校验报告：失败列表（启动前快速失败）+ 警告（如 M0 不支持的 assertions 节）。 */
    public record Report(List<String> errors, List<String> warnings) {
        public boolean ok() {
            return errors.isEmpty();
        }
    }

    /** 输入信任档：本机配置（不限）／外部输入（规则 9–11 生效）。 */
    public enum InputTrust { CONFIG, EXTERNAL }

    // ---- 外部输入档的规模上限（审计 H-2：无认证 DoS 的次级防线）----

    /** 拓扑节点数上限。 */
    public static final int MAX_NODES = 256;
    /** 实例总数上限（各节点 count 之和）。 */
    public static final int MAX_INSTANCES = 4096;
    /** 时间线条目上限。 */
    public static final int MAX_TIMELINE = 1000;
    /** 断言条数上限。 */
    public static final int MAX_ASSERTIONS = 200;
    /** 单节点实例数上限。 */
    public static final int MAX_INSTANCES_PER_NODE = 1024;

    /** 允许被外部输入加载的 SUT 主类命名空间（框架自身；其余需本机配置档）。 */
    private static final List<String> TRUSTED_MAIN_PREFIXES = List.of("io.duo.sim.", "com.duo.");

    /**
     * 外部输入档下允许出现在 {@code config}/{@code capacity} 里的**框架自有**键。
     *
     * <p>为什么需要白名单：{@code config} 是任意 KV 透传给组件实现的，而组件的键里有
     * {@code demo.endpoint.host/port/path} 这类会驱动**出站 HTTP 探针**的键（审计 M-2/H-3：
     * 用外部输入把本机变成 SSRF 跳板），也有直接被当成文件路径写出去的键。控制面收的是
     * **不可信字节**，因此默认拒绝：认识的框架键放行，其余键要组件实现自己声明
     * （{@code CapabilityMetadata.trustedConfigKeys}，声明即生效：见
 * {@link #effectiveTrustedConfigKeys}），否则校验期失败（不静默）。
     */
    private static final Set<String> TRUSTED_CONFIG_KEYS = Set.of(
            // 内核通用
            "autoStart", "count",
            // ready 探针（host/port/path 由 ReadyProbe 解析；此处仅要求不是绝对路径/URL）
            "ready.type", "ready.host", "ready.port", "ready.path", "ready.timeout",
            // 框架内置组件的既有口径（见每个实现 ctx.config() 的读取点）
            "capacity.cpu", "capacity.memGB", "capacity.slots",
            "dag.tasks", "dag.states", "dag.tick",
            "heartbeat.interval", "slow.factor", "slow.window",
            "message.maxDepthPerTopic", "scheduler.heartbeatSampleRate",
            "worker.slowFactor",
            // 剧本（BehaviorResolver 的 behaviors.* / 默认档所有键）
            "behaviors.default.duration", "behaviors.default.jitter",
            "behaviors.default.successRate", "behaviors.default.exception",
            "behaviors.default.logLines", "behaviors.default.failAt",
            "behaviors.default.neverReport", "behaviors.default.progress");

    /** 外部输入档下禁止出现在 config 键里的「路径/URL」形态。 */
    private static final java.util.regex.Pattern ABSOLUTE_PATH =
            java.util.regex.Pattern.compile("^(?:[A-Za-z]:[\\\\/]|[\\\\/]{1,2}[^\\\\/])");
    private static final java.util.regex.Pattern URL_SCHEME =
            java.util.regex.Pattern.compile("^[A-Za-z][A-Za-z0-9+.-]*://");
    private static final java.util.regex.Pattern SCHEME_PREFIX =
            java.util.regex.Pattern.compile("^(?:file|jdbc|jar|classpath|ftp|ldap|rmi|gopher):",
                    java.util.regex.Pattern.CASE_INSENSITIVE);

    private final ContractRegistry registry;
    private final InputTrust trust;

    public ScenarioValidator(ContractRegistry registry) {
        this(registry, InputTrust.CONFIG);
    }

    public ScenarioValidator(ContractRegistry registry, InputTrust trust) {
        this.registry = registry;
        this.trust = trust;
    }

    public Report validate(Scenario scenario) {
        List<String> errors = new ArrayList<>();
        List<String> warnings = new ArrayList<>();

        // 规则 5：节点 id 唯一、sut 恰好一个
        Map<String, Scenario.NodeSpec> byId = new LinkedHashMap<>();
        int sutCount = 0;
        for (var n : scenario.nodes()) {
            if (n.id() == null || n.id().isBlank()) {
                errors.add("node without id in topology");
                continue;
            }
            if (byId.put(n.id(), n) != null) {
                errors.add("duplicate node id: " + n.id());
            }
            if (n.sut()) {
                sutCount++;
            }
        }
        if (sutCount != 1) {
            errors.add("exactly one node must be marked sut: true, found " + sutCount);
        }

        // G11：autoStart:false 只对**内核可自启动**的节点有意义——SUT 与 external 节点
        // 都由 startSut 单独处理，写 autoStart:false 是"看似生效实则无效"的静默陷阱（§12）。
        for (var n : byId.values()) {
            if (!n.autoStart() && (n.sut() || isExternal(n))) {
                errors.add("node " + n.id() + ": autoStart:false is meaningless for "
                        + (n.sut() ? "the sut node" : "an external node")
                        + " (both are started by startSut(), not by startComponents())");
            }
        }

        // 规则 1：契约已注册 + 档位有实现 + 交互型档位拒绝 embedded/container
        Map<String, CapabilityMetadata> metadataByNode = new LinkedHashMap<>();
        for (var n : byId.values()) {
            try {
                if (n.contract() == null || n.contract().isBlank()) {
                    throw new IllegalArgumentException("contract missing");
                }
                if (n.tier() == null || n.tier().isBlank()) {
                    throw new IllegalArgumentException("tier missing");
                }
                var contract = Contract.fromYaml(n.contract());
                var tier = Tier.fromYaml(n.tier());
                if (InteractiveTierGuard.rejects(n.contract(), tier)) {
                    throw new IllegalArgumentException("interactive contract '"
                            + n.contract() + "' has no " + tier + " implementation form (§6)");
                }
                var provider = registry.resolve(contract, tier, n.impl());
                metadataByNode.put(n.id(), provider.metadata());
            } catch (RuntimeException e) {
                errors.add("node " + n.id() + ": " + e.getMessage());
            }
        }

        // 规则 2/3：wiring 简写补全 + 静态路径校验
        for (var n : byId.values()) {
            for (var w : n.wiring().entrySet()) {
                var slot = w.getValue();
                var target = byId.get(slot.node());
                if (target == null) {
                    errors.add("node " + n.id() + ": slot '" + w.getKey()
                            + "' references unknown node '" + slot.node() + "'");
                    continue;
                }
                String expected = slot.contract() == null ? w.getKey() : slot.contract();
                if (!expected.equalsIgnoreCase(target.contract())) {
                    if (slot.contract() == null) {
                        errors.add("node " + n.id() + ": shorthand slot '" + w.getKey()
                                + "' requires slot name == registered contract name (rule 2), "
                                + "target '" + target.id() + "' is '" + target.contract() + "'");
                    } else {
                        errors.add("node " + n.id() + ": slot '" + w.getKey()
                                + "' expects contract '" + expected + "' but target '"
                                + target.id() + "' is '" + target.contract() + "'");
                    }
                    continue;
                }
                CapabilityMetadata m = metadataByNode.get(target.id());
                if (m == null) {
                    continue; // 规则 1 已报
                }
                boolean hasEndpoint = m.endpointShape() != io.duo.sim.kernel.api.EndpointShape.NONE;
                String path = slot.path() == null ? (hasEndpoint ? "wire" : "direct")
                        : slot.path();
                boolean consumerExternal = isExternal(n);
                if ("wire".equals(path) && !hasEndpoint) {
                    errors.add("node " + n.id() + ": slot '" + w.getKey()
                            + "' targets endpoint-less '" + target.id()
                            + "'; upgrade target tier or use direct path");
                }
                if ("direct".equals(path)) {
                    if (consumerExternal) {
                        errors.add("node " + n.id() + " is external: slot '" + w.getKey()
                                + "' cannot use interface-direct (§6)");
                    }
                    if (!m.interfaceDirect()) {
                        errors.add("node " + n.id() + ": slot '" + w.getKey() + "' targets '"
                                + target.id() + "' which does not support interface-direct");
                    }
                }
            }
        }

        // 规则 4：external 节点——被引用槽必须有显式端口；必须声明 ready
        for (var n : byId.values()) {
            if (!isExternal(n)) {
                continue;
            }
            Set<String> exposedContracts = new HashSet<>();
            for (var e : n.exposes()) {
                if (e.port() == null || e.port() == 0) {
                    errors.add("external node " + n.id() + ": expose '" + e.contract()
                            + "' must declare an explicit port");
                } else {
                    exposedContracts.add(e.contract().toLowerCase());
                }
            }
            for (var other : byId.values()) {
                if (other == n) {
                    continue;
                }
                for (var w : other.wiring().entrySet()) {
                    if (w.getValue().node().equals(n.id())
                            && !exposedContracts.contains(
                            w.getValue().contract().toLowerCase())) {
                        errors.add("external node " + n.id() + " referenced by '"
                                + other.id() + ":" + w.getKey() + "' exposes no port for it");
                    }
                }
            }
            // M6：端点配置文件是内核→SUT 端点告知的主途径（§7.3），external 节点必须声明
            if (n.launch().configOut() == null || n.launch().configOut().isBlank()) {
                errors.add("external node " + n.id() + " must declare launch.configOut "
                        + "(endpoint config file — §7.3 主途径)");
            }
            // M6：探针声明与端口可用性在**启动前**校验（§12 快速失败，不拖到运行期）
            try {
                io.duo.sim.kernel.sut.ReadyProbe.spec(n.config(), firstExposedPort(n));
            } catch (RuntimeException e) {
                errors.add("external node " + n.id() + ": " + e.getMessage());
            }
        }

        // 规则 8：固定端口（非 0）可用性
        for (var n : byId.values()) {
            for (var e : n.exposes()) {
                if (e.port() != null && e.port() > 0 && isPortBusy(e.port())) {
                    errors.add("node " + n.id() + ": fixed port " + e.port()
                            + " is already in use");
                }
            }
        }

        // 规则 6：时间线动作静态校验
        for (var t : scenario.timeline()) {
            if (t.action() == null || t.action().isBlank()) {
                errors.add("timeline entry without action");
                continue;
            }
            String target = t.target();
            if (target == null || target.isBlank()) {
                errors.add("timeline action '" + t.action() + "' has no target");
                continue;
            }
            int bracket = target.indexOf('[');
            String componentId = bracket < 0 ? target : target.substring(0, bracket);
            Integer index = null;
            if (bracket >= 0 && target.endsWith("]")) {
                try {
                    index = Integer.parseInt(target.substring(bracket + 1, target.length() - 1));
                } catch (NumberFormatException e) {
                    errors.add("timeline target '" + target + "': bad instance index");
                }
            }
            var targetNode = byId.get(componentId);
            if (targetNode == null) {
                errors.add("timeline target '" + componentId + "' is not a node");
                continue;
            }
            // custom-hook（§7.2 豁免，T22）：target 可为 SUT，且不受 supportedFaults/
            // instanceControl 约束——它是用户钩子，不是故障注入动作
            if ("custom-hook".equals(t.action())) {
                continue;
            }
            if (targetNode.sut()) {
                errors.add("timeline target '" + componentId
                        + "' is SUT (§7.2; custom-hook excepted)");
            }
            var m = metadataByNode.get(componentId);
            if (m != null) {
                if (index != null && (index < 1
                        || (targetNode.count() != null && index > targetNode.count()))) {
                    errors.add("timeline target '" + target + "' index out of [1,count]");
                }
                boolean lifecycle = "crash".equals(t.action()) || "restart".equals(t.action());
                if (!lifecycle && !m.supportedFaults().contains(t.action())) {
                    errors.add("timeline action '" + t.action() + "' unsupported by '"
                            + componentId + "' (§7.2 无降级)");
                }
                if (index != null && !m.instanceControl()) {
                    errors.add("timeline instance index on '" + componentId
                            + "' requires instanceControl capability");
                }
                // M2 验收 MEDIUM 修正：两档 flap 的 duration 语义不对称——virtual 档是
                // 持续窗口（duration 有效），embedded 档是瞬时整服 restart（duration 被
                // 忽略）。对 embedded 节点误写 duration 给显式警告，不静默
                if ("registry-flap".equals(t.action())
                        && t.duration() != null && !t.duration().isBlank()
                        && "embedded".equalsIgnoreCase(targetNode.tier())) {
                    warnings.add("timeline 'registry-flap' on embedded registry '"
                            + componentId + "': duration '" + t.duration()
                            + "' is ignored (embedded flap is an instantaneous full restart; "
                            + "only the virtual tier supports a timed window)");
                }
            }
        }

        // 规则 7：每个具名 profile 至少一个绑定
        var beh = scenario.behaviors();
        for (var pname : beh.profiles().keySet()) {
            if ("default".equals(pname)) {
                continue;
            }
            boolean bound = beh.bindings().stream()
                    .anyMatch(b -> pname.equals(b.profile()));
            if (!bound) {
                errors.add("behavior profile '" + pname + "' has no binding (rule 7)");
            }
        }
        for (var b : beh.bindings()) {
            if (!beh.profiles().containsKey(b.profile())) {
                errors.add("binding references unknown profile '" + b.profile() + "'");
            }
        }

        // M1 T20：assertions 节解析校验（未知断言名/形态错误即启动前失败，不静默忽略）
        if (scenario.assertions() != null && !scenario.assertions().isEmpty()) {
            try {
                io.duo.sim.kernel.assertion.AssertionParser.parse(scenario.assertions());
            } catch (RuntimeException e) {
                errors.add("assertions: " + e.getMessage());
            }
        }

        // 规则 9–11：外部输入的信任边界（安全审计 2026-09-20）
        if (trust == InputTrust.EXTERNAL) {
            checkExternalInput(scenario, byId, metadataByNode, errors);
        }

        return new Report(errors, warnings);
    }

    /**
     * 规则 9–11（仅外部输入档）。规则 9＝配置键白名单；规则 10＝机器级副作用禁入
     * （配置路径/URL、任意类加载）；规则 11＝规模有界。
     */
    private void checkExternalInput(Scenario scenario, Map<String, Scenario.NodeSpec> byId,
                                    Map<String, CapabilityMetadata> metadataByNode,
                                    List<String> errors) {
        // 规则 11：规模上限（先做，避免后面的逐节点检查在大拓扑上白跑）
        if (byId.size() > MAX_NODES) {
            errors.add("topology has " + byId.size() + " nodes; external input allows at most "
                    + MAX_NODES);
        }
        int instances = 0;
        for (var n : byId.values()) {
            // 实例数＝展开后的单元数：count 是「每实例的单元数」语义时由组件自行乘，
            // 校验层只保证「声明的实例数」有界（真正的内存由实例数×单元数决定，
            // 故同时限制 count 本身，见 MAX_INSTANCES_PER_NODE）。
            int count = n.count() == null ? 1 : n.count();
            instances += count;
            if (count > MAX_INSTANCES_PER_NODE) {
                errors.add("node " + n.id() + ": count " + count
                        + " exceeds external input limit " + MAX_INSTANCES_PER_NODE);
            }
        }
        if (instances > MAX_INSTANCES) {
            errors.add("topology declares " + instances + " instances; external input allows at"
                    + " most " + MAX_INSTANCES);
        }
        if (scenario.timeline().size() > MAX_TIMELINE) {
            errors.add("timeline has " + scenario.timeline().size()
                    + " entries; external input allows at most " + MAX_TIMELINE);
        }
        if (scenario.assertions() != null && scenario.assertions().size() > MAX_ASSERTIONS) {
            errors.add("assertions has " + scenario.assertions().size()
                    + " entries; external input allows at most " + MAX_ASSERTIONS);
        }

        for (var n : byId.values()) {
            // 规则 10①：外部进程启动（任意命令执行）——审计 C-1 的直接利用路径
            if (isExternal(n)) {
                boolean wantsProcess = n.launch().allowExternalProcess()
                        || (n.launch().command() != null && !n.launch().command().isBlank());
                if (wantsProcess) {
                    errors.add("node " + n.id() + ": launching an external process is not allowed"
                            + " for external input (launch.command /"
                            + " launch.allowExternalProcess); run it from a local scenario file"
                            + " or the CLI instead");
                }
                checkConfigPath(n, "launch.configOut", n.launch().configOut(), errors);
            }
            // 规则 10②：任意类加载（SUT 主类）
            if (n.launch() != null && n.launch().main() != null
                    && !n.launch().main().isBlank() && !isTrustedMainClass(n.launch().main())) {
                errors.add("node " + n.id() + ": sut main class '" + n.launch().main()
                        + "' is outside the framework namespace; loading arbitrary classes from"
                        + " external input is not allowed (io.duo.sim.* / com.duo.* only)");
            }
            // 规则 11：config/capacity 键白名单＝静态白名单 ∪ **本节点 provider 自己声明的键**
            // （安全审计复核 M-2：声明不生效就等于没声明；见 isTrustedConfigKey 的用例）
            Set<String> allowed = effectiveTrustedConfigKeys(
                    metadataByNode.get(n.id()));
            for (var e : n.config().entrySet()) {
                checkExternalConfigEntry(n, allowed, e.getKey(), e.getValue(), errors);
            }
            for (var e : n.capacity().entrySet()) {
                checkExternalConfigEntry(n, allowed, e.getKey(), e.getValue(), errors);
            }
        }
    }

    /**
     * 生效的可信 config 键集合＝静态白名单 ∪ provider 经
     * {@link CapabilityMetadata#withTrustedConfigKeys} 声明的键。
     *
     * <p>安全审计 2026-09-20 复核 M-2：D11/类注释此前宣称组件可自行扩展白名单，但全仓
     * 没有任何读取点，是句空承诺。这里把它接上——**声明即可生效**，且声明面是注册期静态
     * 数据（不是运行期输入），因此不引入新的信任口子。
     */
    public static Set<String> effectiveTrustedConfigKeys(CapabilityMetadata meta) {
        Set<String> allowed = new LinkedHashSet<>(TRUSTED_CONFIG_KEYS);
        if (meta != null && meta.trustedConfigKeys() != null) {
            allowed.addAll(meta.trustedConfigKeys());
        }
        return allowed;
    }

    /** 单个键是否被接受（静态白名单 ∪ provider 声明键）。 */
    public static boolean isTrustedConfigKey(String key, CapabilityMetadata meta) {
        return key != null && effectiveTrustedConfigKeys(meta).contains(key);
    }

    private static void checkExternalConfigEntry(Scenario.NodeSpec n, Set<String> allowed,
                                                 String key, String value, List<String> errors) {
        if (!allowed.contains(key)) {
            errors.add("node " + n.id() + ": config key '" + key + "' is not accepted for"
                    + " external input (known keys only; the component implementation may"
                    + " declare more via CapabilityMetadata.withTrustedConfigKeys)");
        }
        checkConfigPath(n, "config." + key, value, errors);
    }

    /** 规则 10③：配置值不得是绝对路径或带 scheme 的 URL（机器级副作用的入口）。 */
    private static void checkConfigPath(Scenario.NodeSpec n, String where, String value,
                                        List<String> errors) {
        if (value == null || value.isBlank()) {
            return;
        }
        String v = value.trim();
        if (ABSOLUTE_PATH.matcher(v).find() || URL_SCHEME.matcher(v).find()
                || SCHEME_PREFIX.matcher(v).find()) {
            errors.add("node " + n.id() + ": " + where + " must not be an absolute path or a"
                    + " URL for external input (got '" + v + "')");
        }
    }

    /** 外部输入档允许加载的 SUT 主类命名空间（框架自身）。 */
    public static boolean isTrustedMainClass(String main) {
        return TRUSTED_MAIN_PREFIXES.stream().anyMatch(main::startsWith);
    }

    /** 简写补全：wiring 值只有 node 字符串时 contract=null，由调用方按"槽名即契约名"处理。 */
    public static Map<String, Scenario.WiringSpec> withShorthandFilled(
            Map<String, String> shorthand) {
        Map<String, Scenario.WiringSpec> out = new LinkedHashMap<>();
        shorthand.forEach((slot, node) -> out.put(slot,
                new Scenario.WiringSpec(node, slot, null)));
        return out;
    }

    private static boolean isExternal(Scenario.NodeSpec n) {
        return n.launch() != null && "external".equals(n.launch().mode());
    }

    /** external 节点的兜底探针端口（首个非 0 expose 端口；无则 0＝由 ReadyProbe 报错）。 */
    private static int firstExposedPort(Scenario.NodeSpec n) {
        return n.exposes().stream()
                .filter(e -> e.port() != null && e.port() > 0)
                .mapToInt(Scenario.ExposeSpec::port)
                .findFirst()
                .orElse(0);
    }

    private static boolean isPortBusy(int port) {
        try (java.net.ServerSocket s = new java.net.ServerSocket()) {
            s.bind(new java.net.InetSocketAddress("127.0.0.1", port));
            return false;
        } catch (Exception e) {
            return true;
        }
    }
}
