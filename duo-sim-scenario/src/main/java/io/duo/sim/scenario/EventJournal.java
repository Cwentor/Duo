package io.duo.sim.scenario;

import io.duo.sim.kernel.api.Event;
import io.duo.sim.kernel.core.SimpleEventBus;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

/**
 * 场景事件日志（内存流 + JSONL 录制 + 世代闸门）：事件经总线单一订阅点进入，
 * 内存流与录制共用同一道有界闸门（{@link EventRecorder#MAX_BUFFERED_EVENTS}）——
 * 两道门宽度一致 ⇒ 同一份事件在两个接收端**要么都留、要么都丢**，不会出现
 * 「录制说没有、内存说有」的分叉（安全审计 2026-09-20 复核 H-4/H-5）。
 *
 * <p><b>有界 + O(1) 追加</b>：早先用 {@code CopyOnWriteArrayList} 记录，每次 {@code add}
 * 都整表复制 ⇒ 单场景 O(n²)，万级 worker 的百万级事件会把控制面拖垮且无上限。
 * 现为默认同步的 {@link ArrayList} 加显式锁（追加 O(1)、读 O(1) 取下标），超限丢弃并计数，
 * 经 {@link #droppedEvents()} / 收尾告警显式上报（§12 不静默）。
 *
 * <p><b>世代闸门</b>：同名场景重跑时，新旧引擎写**同一个** {@code events.jsonl}；
 * 控制面顶替旧引擎是异步收摊的，若旧引擎的 flush 落在新的之后，就会把新录制整份覆盖成旧的。
 * 故写入前比对「活动世代」（宿主经 {@link #withEpochGuard} 注入；默认恒返回自身世代），
 * 过期即放弃落盘并告警。
 */
final class EventJournal {

    private static final AtomicLong EPOCHS = new AtomicLong();

    private final String scenarioName;
    private final List<Event> recorded = new ArrayList<>();
    private final Object lock = new Object();
    private long dropped;
    private final EventRecorder recorder;
    private final long epoch = EPOCHS.incrementAndGet();
    private LongSupplier epochGuard = this::currentEpoch;

    EventJournal(SimpleEventBus bus, String scenarioName) {
        this.scenarioName = scenarioName;
        this.recorder = EventRecorder.to(Path.of("build", "scenarios",
                scenarioName, "events.jsonl"));
        // 内存流与录制同一订阅点（构造即订阅）：保证两条流事件数一致（T21）
        bus.subscribe(this::onEvent);
        bus.subscribe(recorder::onEvent);
    }

    private long currentEpoch() {
        return epoch;
    }

    /** 本日志的录制世代号（宿主用它做「现任/历史」判定）。 */
    long epoch() {
        return epoch;
    }

    /**
     * 绑定宿主的活动世代供应器（控制面用）：返回的号 ≠ {@link #epoch()} 时，本日志已不是
     * 现任场景，不再拥有覆盖录制文件的权限。
     */
    EventJournal withEpochGuard(LongSupplier activeEpoch) {
        this.epochGuard = Objects.requireNonNull(activeEpoch, "activeEpoch");
        return this;
    }

    /** 事件落流（唯一写入口）。达上限后停止累积并计数——静默丢事件会让 /events 与断言评估看到的事件流「缺一块却不说话」。 */
    private void onEvent(Event e) {
        synchronized (lock) {
            if (recorded.size() >= EventRecorder.MAX_BUFFERED_EVENTS) {
                dropped++;
                return;
            }
            recorded.add(e);
        }
    }

    /** 稳定快照（1-based 下标切片语义的基础，M3 D3；O(n) 拷贝，读侧不加长锁）。 */
    List<Event> events() {
        synchronized (lock) {
            return List.copyOf(recorded);
        }
    }

    /**
     * 增量切片（卡 7）：锁内只拷贝下标 &gt;= {@code since} 的部分——O(新增)，
     * 供 /events 高频轮询；全量 {@link #events()} 留给断言评估/诊断等低频路径。
     * 只追加流（达上限即停）保证下标稳定，切片语义与全量快照一致（M3 D3）。
     */
    List<Event> eventsSince(int since) {
        synchronized (lock) {
            int from = Math.max(since, 0);
            if (from >= recorded.size()) {
                return List.of();
            }
            return List.copyOf(recorded.subList(from, recorded.size()));
        }
    }

    /** 事件数（免整表拷贝的自省入口；{@code /status} 的 {@code events} 用它）。 */
    int eventCount() {
        synchronized (lock) {
            return recorded.size();
        }
    }

    /** 因内存流上限而被丢弃的事件数（0 ＝ 未溢出）。 */
    long droppedEvents() {
        synchronized (lock) {
            return dropped;
        }
    }

    /** 录制文件路径（供审查/比对，T21）。 */
    Path recordingPath() {
        return recorder.outputFile();
    }

    /**
     * 场景收尾的录制侧（stop() 的一步）：世代闸门判定 → flush → 溢出告警。
     * 三类告警（内存溢出 / 世代过期放弃写盘 / 录制缓冲溢出）双写 warnings 与 result。
     */
    void finish(List<String> warnings, ScenarioResult result) {
        long memoryDropped = droppedEvents();
        if (memoryDropped > 0) {
            // 内存流与录制缓冲同一道闸门：这里溢出说明事件流被截断，必须说出来（§12）
            record(warnings, result, "in-memory event stream limit reached: " + memoryDropped
                    + " events dropped (same limit as " + EventRecorder.MAX_BUFFERED_EVENTS
                    + "; /events and assertion review see a truncated stream)");
        }
        // 录制的覆盖权限归"现任"引擎：过期引擎放弃写盘，避免抹掉新场景的录制（H-4 复核；
        // A7 起 flushIfCurrent 写后复核也可能返回 false——两种过期都走这条显式告警）
        if (!recorder.flushIfCurrent(epochGuard, epoch)) {
            record(warnings, result, "event recording abandoned: this scenario was superseded by a "
                    + "newer one running with the same name (build/scenarios/"
                    + scenarioName + "/events.jsonl belongs to the current run)");
        }
        if (recorder.droppedEvents() > 0) {
            // 有界缓冲的可见代价（审计 H-4 / §12）：丢了多少必须说出来，不静默
            record(warnings, result, "event recording buffer limit reached: "
                    + recorder.droppedEvents() + " events dropped from build/scenarios/"
                    + scenarioName + "/events.jsonl");
        }
    }

    private static void record(List<String> warnings, ScenarioResult result, String hint) {
        warnings.add(hint);
        result.recordWarning(hint);
    }
}
