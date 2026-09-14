package io.duo.sim.examples.scheduler;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 派发选择器（M1 T19(a)）：维护调度侧每实例的 freeSlots 视图，按
 * **freeSlots &gt; 0 且最大者**选择，平局按轮转游标（消除"永远落到迭代序第一个 feed"
 * 的旧缺陷——那会让 4 条并行任务全部堆到同一 worker，workers[3] 空载）。
 *
 * <p>视图一致性：派发时本地递减（{@link #onDispatched}）；worker 的 SlotReport 到达时
 * 权威刷新（{@link #onSlotReport}）。未收到过 SlotReport 的实例不参与选择（known=false），
 * 避免把任务派给尚未就绪的连接。
 */
public final class DispatchSelector {

    private final Map<String, AtomicInteger> freeSlots = new ConcurrentHashMap<>();
    private final AtomicInteger cursor = new AtomicInteger();

    /** 注册实例（连接建立时）；初始 known=false，SlotReport 到达后才可被选中。 */
    public void register(String instanceName) {
        freeSlots.put(instanceName, new AtomicInteger(-1)); // -1 = unknown
    }

    /** SlotReport 权威刷新。 */
    public void onSlotReport(String instanceName, int slots) {
        freeSlots.computeIfAbsent(instanceName, k -> new AtomicInteger()).set(slots);
    }

    /** 派发成功：本地递减（SlotReport 到达时会被权威值覆盖）。 */
    public void onDispatched(String instanceName) {
        AtomicInteger v = freeSlots.get(instanceName);
        if (v != null) {
            v.updateAndGet(cur -> Math.max(0, cur - 1));
        }
    }

    /** 派发失败/实例移除。 */
    public void onRemoved(String instanceName) {
        freeSlots.remove(instanceName);
    }

    public int freeSlotsOf(String instanceName) {
        AtomicInteger v = freeSlots.get(instanceName);
        return v == null ? -1 : v.get();
    }

    /**
     * 选择目标实例：freeSlots &gt; 0 且最大者；平局按 name 排序 + 轮转游标。
     *
     * @return 实例名；无可选（全部耗尽或未就绪）返回 null
     */
    public String select() {
        List<Map.Entry<String, AtomicInteger>> candidates = freeSlots.entrySet().stream()
                .filter(e -> e.getValue().get() > 0)
                .sorted(Comparator.comparing(Map.Entry::getKey))
                .toList();
        if (candidates.isEmpty()) {
            return null;
        }
        int max = candidates.stream().mapToInt(e -> e.getValue().get()).max().orElse(0);
        List<Map.Entry<String, AtomicInteger>> best = candidates.stream()
                .filter(e -> e.getValue().get() == max)
                .toList();
        int idx = Math.floorMod(cursor.getAndIncrement(), best.size());
        return best.get(idx).getKey();
    }

    /** 当前已知实例数（测试用）。 */
    public int knownCount() {
        return (int) freeSlots.values().stream().filter(v -> v.get() >= 0).count();
    }
}
