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
 *
 * <p><b>§6.1 判定表唯一权威</b>（卡 3a）：{@link #decide} 是接线路径判定的唯一实现，
 * 本类的 {@link #resolveNode}（抛异常）与 scenario 侧 {@code ScenarioValidator}（批量收集）
 * 都消费它的结论、各自格式化消息——“同一判定两处实现”自此在结构上不可能。
 */
public final class WiringResolver {

    /** kernel 解析所需的最小节点视图（scenario.NodeSpec 适配到它）。 */
    public record NodeView(String id, String contract, TierView tier,
                           Map<String, SlotView> wiring, boolean external) {
    }

    public record SlotView(String targetNodeId, String contract, String path /* wire|direct|null */) {
    }

    public enum TierView { VIRTUAL, EMBEDDED, CONTAINER, REAL }

    /** §6.1 判定表的结构化失败（消费方各自映射为自己的消息文案）。 */
    public enum SlotFailure {
        /** path ∉ {wire, direct}。 */
        UNKNOWN_PATH,
        /** wire（显式或推断不出）指向无端点目标。 */
        WIRE_WITHOUT_ENDPOINT,
        /** direct 且消费方为 external 节点（只能走 wire）。 */
        DIRECT_EXTERNAL_CONSUMER,
        /** direct 且目标未声明 interfaceDirect。 */
        DIRECT_NOT_SUPPORTED
    }

    /** {@link #decide} 的结论：ok 时 path 非空 failure 为 null；fail 时反之。 */
    public record SlotDecision(io.duo.sim.kernel.api.ComponentContext.ConnectionPath path,
                               SlotFailure failure) {

        public static SlotDecision ok(io.duo.sim.kernel.api.ComponentContext.ConnectionPath path) {
            return new SlotDecision(path, null);
        }

        public static SlotDecision fail(SlotFailure failure) {
            return new SlotDecision(null, failure);
        }
    }

    /**
     * §6.1 接线判定表（唯一权威）：
     *
     * <ul>
     *   <li>{@code explicitPath == null} 时缺省推断——目标端点形态非 NONE → wire，否则 direct；</li>
     *   <li>wire：目标无端点 → {@link SlotFailure#WIRE_WITHOUT_ENDPOINT}；</li>
     *   <li>direct：消费方 external → {@link SlotFailure#DIRECT_EXTERNAL_CONSUMER}
     *       （先于能力检查，与既有 checkDirect 顺序一致）；目标 !interfaceDirect →
     *       {@link SlotFailure#DIRECT_NOT_SUPPORTED}；</li>
     *   <li>其余 path 值 → {@link SlotFailure#UNKNOWN_PATH}。</li>
     * </ul>
     */
    public static SlotDecision decide(boolean consumerExternal, String explicitPath,
                                      boolean targetHasEndpoint, boolean targetInterfaceDirect) {
        String path = explicitPath == null ? (targetHasEndpoint ? "wire" : "direct") : explicitPath;
        return switch (path) {
            case "wire" -> targetHasEndpoint
                    ? SlotDecision.ok(io.duo.sim.kernel.api.ComponentContext.ConnectionPath.WIRE)
                    : SlotDecision.fail(SlotFailure.WIRE_WITHOUT_ENDPOINT);
            case "direct" -> consumerExternal
                    ? SlotDecision.fail(SlotFailure.DIRECT_EXTERNAL_CONSUMER)
                    : !targetInterfaceDirect
                            ? SlotDecision.fail(SlotFailure.DIRECT_NOT_SUPPORTED)
                            : SlotDecision.ok(io.duo.sim.kernel.api.ComponentContext.ConnectionPath.DIRECT);
            default -> SlotDecision.fail(SlotFailure.UNKNOWN_PATH);
        };
    }

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
            SlotDecision decision = decide(node.external(), sv.path(), hasEndpoint,
                    m.interfaceDirect());
            io.duo.sim.kernel.api.ComponentContext.ConnectionPath path;
            if (decision.failure() != null) {
                throw wiringException(decision.failure(), node, target, slotName, sv);
            }
            path = decision.path();
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

    /** 判定失败 → 解析期异常（文案与判定表收敛前逐字一致——既有测试断言即冻结面）。 */
    private static WiringException wiringException(SlotFailure failure, NodeView node,
                                                   NodeView target, String slotName, SlotView sv) {
        return switch (failure) {
            case WIRE_WITHOUT_ENDPOINT -> new WiringException("explicit path=wire to endpoint-less "
                    + "target " + target.id() + ": no reachable endpoint");
            case DIRECT_EXTERNAL_CONSUMER -> new WiringException("slot '" + slotName
                    + "' resolves to interface-direct, but consumer '" + node.id()
                    + "' is external (only wire-protocol)");
            case DIRECT_NOT_SUPPORTED -> new WiringException("slot '" + slotName + "' targets '"
                    + target.id() + "' which does not support interface-direct"
                    + " (no same-process adapter)");
            case UNKNOWN_PATH -> new WiringException("unknown path '" + sv.path()
                    + "' on slot '" + slotName + "' (expect wire|direct)");
        };
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
