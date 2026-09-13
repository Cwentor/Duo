package io.duo.sim.kernel.core;

import io.duo.sim.kernel.api.CapabilityMetadata;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 接线解析（§6/§8 规则 2/3，计划 T9）。
 *
 * <p>输入为“中立拓扑视图”（scenario 模块提供 NodeView 实现），kernel 不反向依赖 scenario。
 * 输出每节点的 {@link io.duo.sim.kernel.api.ComponentContext.WiringBinding}。
 */
public final class WiringResolver {

    /** kernel 解析所需的最小节点视图（scenario.NodeSpec 适配到它）。 */
    public record NodeView(String id, String contract, TierView tier,
                           Map<String, SlotView> wiring, boolean external) {
    }

    public record SlotView(String targetNodeId, String contract, String path /* wire|direct|null */) {
    }

    public enum TierView { VIRTUAL, EMBEDDED, CONTAINER, REAL }

    /** 接线解析失败（规则 2/3 违规，或 external 节点缺端点）。 */
    public static class WiringException extends RuntimeException {
        public WiringException(String message) {
            super(message);
        }
    }

    /**
     * 解析单个节点的 wiring 槽。direct 路径消费方必须 in-process（§6）；
     * wire 路径要求目标端点形态 ≠ NONE，否则提示升档；direct 要求目标 interfaceDirect=true。
     */
    public static Map<String, io.duo.sim.kernel.api.ComponentContext.WiringBinding>
    resolveNode(NodeView node, Map<String, NodeView> byId,
                java.util.function.BiFunction<String, String, CapabilityMetadata> metadataLookup,
                java.util.function.BiFunction<String, String, Object> directBindingLookup,
                java.util.function.BiFunction<String, String, String> wireEndpointLookup) {
        Map<String, io.duo.sim.kernel.api.ComponentContext.WiringBinding> out =
                new LinkedHashMap<>();
        for (Map.Entry<String, SlotView> slot : node.wiring().entrySet()) {
            String slotName = slot.getKey();
            SlotView sv = slot.getValue();
            NodeView target = byId.get(sv.targetNodeId());
            if (target == null) {
                throw new WiringException("node '" + node.id() + "': wiring slot '" + slotName
                        + "' references unknown node '" + sv.targetNodeId() + "'");
            }
            // 规则 2：契约一致（槽声明契约 == 目标节点契约）
            if (!sv.contract().equalsIgnoreCase(target.contract())) {
                throw new WiringException("node '" + node.id() + "': slot '" + slotName
                        + "' expects contract '" + sv.contract() + "' but target '"
                        + target.id() + "' has contract '" + target.contract() + "'");
            }
            CapabilityMetadata m = metadataLookup.apply(target.contract(),
                    target.tier().name());
            if (m == null) {
                throw new WiringException("no implementation metadata for "
                        + target.contract() + "/" + target.tier() + " (target of slot '"
                        + slotName + "')");
            }
            boolean hasEndpoint = m.endpointShape() != io.duo.sim.kernel.api.EndpointShape.NONE;
            io.duo.sim.kernel.api.ComponentContext.ConnectionPath path;
            if (sv.path() != null) {
                // 显式 path 同样校验（§6）
                switch (sv.path()) {
                    case "wire" -> {
                        if (!hasEndpoint) {
                            throw new WiringException("explicit path=wire to endpoint-less target "
                                    + target.id() + ": no reachable endpoint");
                        }
                        path = io.duo.sim.kernel.api.ComponentContext.ConnectionPath.WIRE;
                    }
                    case "direct" -> {
                        checkDirect(node, target, slotName, m);
                        path = io.duo.sim.kernel.api.ComponentContext.ConnectionPath.DIRECT;
                    }
                    default -> throw new WiringException("unknown path '" + sv.path()
                            + "' on slot '" + slotName + "' (expect wire|direct)");
                }
            } else {
                // 缺省推断（§6/§7.5）：端点形态非 NONE → wire；否则 direct（消费方须 in-process）
                if (hasEndpoint) {
                    path = io.duo.sim.kernel.api.ComponentContext.ConnectionPath.WIRE;
                } else {
                    checkDirect(node, target, slotName, m);
                    path = io.duo.sim.kernel.api.ComponentContext.ConnectionPath.DIRECT;
                }
            }
            if (path == io.duo.sim.kernel.api.ComponentContext.ConnectionPath.WIRE) {
                String addr = wireEndpointLookup.apply(slotName, target.id());
                if (addr == null) {
                    throw new WiringException("no wire endpoint available for slot '" + slotName
                            + "' of node " + node.id());
                }
                out.put(slotName, new io.duo.sim.kernel.api.ComponentContext.WiringBinding(
                        slotName, target.id(),
                        io.duo.sim.kernel.api.Contract.fromYaml(target.contract()),
                        path, null, addr));
            } else {
                Object direct = directBindingLookup.apply(slotName, target.id());
                if (direct == null) {
                    throw new WiringException("no direct binding available for slot '" + slotName
                            + "' of node " + node.id());
                }
                out.put(slotName, new io.duo.sim.kernel.api.ComponentContext.WiringBinding(
                        slotName, target.id(),
                        io.duo.sim.kernel.api.Contract.fromYaml(target.contract()),
                        path, direct, null));
            }
        }
        return out;
    }

    private static void checkDirect(NodeView consumer, NodeView target, String slotName,
                                   CapabilityMetadata m) {
        if (consumer.external()) {
            throw new WiringException("slot '" + slotName + "' resolves to interface-direct, "
                    + "but consumer '" + consumer.id() + "' is external (only wire-protocol)");
        }
        if (!m.interfaceDirect()) {
            throw new WiringException("slot '" + slotName + "' targets '" + target.id()
                    + "' which does not support interface-direct (no same-process adapter)");
        }
    }

    /**
     * 按 wiring 依赖做拓扑排序（§7.1：依赖者后启）。返回节点 id 列表（稳定：输入序优先）。
     * 遇环抛错（拓扑排序失败＝接线成环，非合法场景）。
     */
    public static List<String> topoOrder(List<NodeView> nodes) {
        Map<String, List<String>> deps = new LinkedHashMap<>();
        for (NodeView n : nodes) {
            deps.put(n.id(), n.wiring().values().stream().map(SlotView::targetNodeId).toList());
        }
        List<String> result = new java.util.ArrayList<>();
        java.util.Set<String> done = new java.util.LinkedHashSet<>();
        java.util.Set<String> visiting = new java.util.LinkedHashSet<>();
        for (NodeView n : nodes) {
            visit(n.id(), deps, done, visiting, result);
        }
        return result;
    }

    private static void visit(String id, Map<String, List<String>> deps,
                              java.util.Set<String> done, java.util.Set<String> visiting,
                              List<String> result) {
        if (done.contains(id)) {
            return;
        }
        if (!visiting.add(id)) {
            throw new WiringException("wiring cycle detected involving '" + id + "'");
        }
        for (String d : deps.getOrDefault(id, List.of())) {
            visit(d, deps, done, visiting, result);
        }
        visiting.remove(id);
        done.add(id);
        result.add(id);
    }
}
