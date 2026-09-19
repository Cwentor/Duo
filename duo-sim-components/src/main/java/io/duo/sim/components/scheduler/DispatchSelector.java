package io.duo.sim.components.scheduler;

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
    /** 最近一次 SlotReport 上报的槽位数＝调度侧已知容量（退还时的上界，防止凭空加账）。 */
    private final Map<String, Integer> lastKnown = new ConcurrentHashMap<>();
    private final AtomicInteger cursor = new AtomicInteger();

    /** 注册实例（连接建立时）；初始 known=false，SlotReport 到达后才可被选中。 */
    public void register(String instanceName) {
        freeSlots.put(instanceName, new AtomicInteger(-1)); // -1 = unknown
    }

    /** SlotReport 权威刷新。 */
    public void onSlotReport(String instanceName, int slots) {
        freeSlots.computeIfAbsent(instanceName, k -> new AtomicInteger()).set(slots);
        lastKnown.put(instanceName, slots);
    }

    /** 派发成功：本地递减（SlotReport 到达时会被权威值覆盖）。 */
    public void onDispatched(String instanceName) {
        AtomicInteger v = freeSlots.get(instanceName);
        if (v != null) {
            v.updateAndGet(cur -> Math.max(0, cur - 1));
        }
    }

    /**
     * 派发被 worker **显式拒绝**：退还 {@link #onDispatched} 的本地递减（G10 修复，P1）。
     *
     * <p>为什么必须退：被拒的任务没有占用 worker 的槽位，但本地账已经记了一笔；实例只有
     * 一格容量时账就永久停在 0，{@link #select()} 永远返回 null ⇒ 被拒任务无处可派、
     * 整个 DAG 卡在待派发态（实测：派发事实与拒绝事实各只有 1 条，DAG 永不收敛）。
     *
     * <p>上限：只退到调度侧**已知的**容量为止（{@code lastKnown}），不做无根据的加账——
     * 退还值仍可能比 worker 真实空闲数保守（worker 若因自身原因不空闲，它会用 SlotReport
     * 或下一次拒绝把真相带回来），但**绝不会**凭空超过 worker 上报的槽位数。
     */
    public void onDispatchRolledBack(String instanceName) {
        AtomicInteger v = freeSlots.get(instanceName);
        if (v == null) {
            return; // 实例已移除（连接丢失）：重派由状态机的失联路径负责
        }
        int cap = lastKnown.getOrDefault(instanceName, Integer.MAX_VALUE);
        v.updateAndGet(cur -> Math.min(cap, cur + 1));
    }

    /** 派发失败/实例移除。 */
    public void onRemoved(String instanceName) {
        freeSlots.remove(instanceName);
        lastKnown.remove(instanceName);
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
