package io.duo.sim.kernel.core;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * 图算法共享件（评审 2026-10-30 R12）：DFS 后序拓扑排序此前在 {@link WiringResolver#topoOrder}
 * 与 components 侧 {@code SchedulerStateMachine.topoSort} 两份逐字同形（仅异常形态不同）——
 * 并发敏感的环检测与遍历序收敛单点，加一处分叉即启动期语义漂移。
 *
 * <p><b>语义口径（与原两份实现逐一等价）</b>：稳定序＝按 {@code seeds} 输入序展开、后序加入
 * 结果（依赖者后启，§7.1）；环检测＝{@code visiting} 集合重入即环。
 *
 * <p><b>差异如实声明</b>：两处调用方的环异常不同（{@code WiringException} /
 * {@code IllegalArgumentException}），由 {@code cycleException} 工厂显式承载，不静默取齐。
 */
public final class Graphs {

    private Graphs() {
    }

    /**
     * 稳定拓扑排序。
     *
     * @param seeds           展开序（调用方的输入序：节点清单序 / DAG 键插入序）
     * @param deps            邻接表 {@code id → 依赖 id 列表}（缺失键视为无依赖）
     * @param cycleException  环异常工厂（入参＝环涉及节点 id），承载各调用方钉死的异常形态
     * @return 依赖者后启的拓扑序（{@code List.copyOf} 前由调用方自决）
     */
    public static List<String> topoOrder(Iterable<String> seeds, Map<String, List<String>> deps,
                                         Function<String, ? extends RuntimeException> cycleException) {
        List<String> result = new java.util.ArrayList<>();
        Set<String> done = new LinkedHashSet<>();
        Set<String> visiting = new LinkedHashSet<>();
        for (String seed : seeds) {
            visit(seed, deps, done, visiting, result, cycleException);
        }
        return result;
    }

    private static void visit(String id, Map<String, List<String>> deps,
                              Set<String> done, Set<String> visiting, List<String> result,
                              Function<String, ? extends RuntimeException> cycleException) {
        if (done.contains(id)) {
            return;
        }
        if (!visiting.add(id)) {
            throw cycleException.apply(id);
        }
        for (String d : deps.getOrDefault(id, List.of())) {
            visit(d, deps, done, visiting, result, cycleException);
        }
        visiting.remove(id);
        done.add(id);
        result.add(id);
    }
}
