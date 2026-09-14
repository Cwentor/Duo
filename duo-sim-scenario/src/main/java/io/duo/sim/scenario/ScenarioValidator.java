package io.duo.sim.scenario;

import io.duo.sim.kernel.api.CapabilityMetadata;
import io.duo.sim.kernel.api.Contract;
import io.duo.sim.kernel.api.Tier;
import io.duo.sim.kernel.core.ContractRegistry;
import io.duo.sim.scenario.model.Scenario;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 场景校验器（§8）：规则 1–8 全量。
 * 规则 1（契约注册/档位实现/交互型档位）、3（路径前提）读注册表静态元数据；
 * 规则 2 做简写补全与静态一致性检查；6 校验时间线动作；7 校验剧本绑定；
 * 8 固定端口可用性；4 external 节点端点声明与 ready 探针。
 */
public final class ScenarioValidator {

    /** 校验报告：失败列表（启动前快速失败）+ 警告（如 M0 不支持的 assertions 节）。 */
    public record Report(List<String> errors, List<String> warnings) {
        public boolean ok() {
            return errors.isEmpty();
        }
    }

    private final ContractRegistry registry;

    public ScenarioValidator(ContractRegistry registry) {
        this.registry = registry;
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
            if (!n.config().containsKey("ready.type")) {
                errors.add("external node " + n.id() + " must declare a ready probe "
                        + "(config: {ready.type: tcp, ready.port: ..., ready.timeout: ...})");
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

        return new Report(errors, warnings);
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

    private static boolean isPortBusy(int port) {
        try (java.net.ServerSocket s = new java.net.ServerSocket()) {
            s.bind(new java.net.InetSocketAddress("127.0.0.1", port));
            return false;
        } catch (Exception e) {
            return true;
        }
    }
}
