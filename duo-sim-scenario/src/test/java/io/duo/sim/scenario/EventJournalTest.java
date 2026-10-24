package io.duo.sim.scenario;

import io.duo.sim.kernel.api.Event;
import io.duo.sim.kernel.core.SimpleEventBus;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * EventJournal（内存流 + 录制 + 世代闸门）单元测试：卡 2 拆分前这段逻辑藏在
 * ScenarioEngine 内部、只能整场景端到端覆盖；现在接缝上直接单测。
 */
class EventJournalTest {

    @Test
    void recordsEventsThroughSingleSubscriptionPoint() {
        SimpleEventBus bus = new SimpleEventBus();
        EventJournal journal = new EventJournal(bus, "journal-unit-happy");
        bus.publish(Event.sim("sim.component-started", "zk", Map.of()));
        bus.publish(Event.sim("sim.component-started", "workers", Map.of()));

        assertEquals(2, journal.eventCount());
        assertEquals(2, journal.events().size());
        assertEquals("zk", journal.events().get(0).sourceId());
        assertEquals(0L, journal.droppedEvents());
        assertTrue(journal.recordingPath().endsWith("events.jsonl"),
                "recording path must point at the scenario recording file");
    }

    /** 卡 7：增量切片语义（/events 高频轮询走它，不再整表拷贝）——只含下标 >= since 的事件。 */
    @Test
    void eventsSinceReturnsOnlyTheTailWithoutFullCopy() {
        SimpleEventBus bus = new SimpleEventBus();
        EventJournal journal = new EventJournal(bus, "journal-unit-tail");
        bus.publish(Event.sim("sim.component-started", "zk", Map.of()));
        bus.publish(Event.sim("sim.component-started", "master", Map.of()));
        bus.publish(Event.sim("sim.component-stopped", "zk", Map.of()));

        assertEquals(3, journal.eventsSince(0).size());
        var tail = journal.eventsSince(2);
        assertEquals(1, tail.size());
        assertEquals("sim.component-stopped", tail.get(0).type());
        assertTrue(journal.eventsSince(3).isEmpty(), "since beyond the end yields empty");
        assertTrue(journal.eventsSince(99).isEmpty(), "far beyond the end yields empty");
        assertEquals(3, journal.eventCount(), "增量读取不得影响事件流本体");
    }

    @Test
    void supersededEpochGivesUpRecordingAndWarns() {
        // 同名场景重跑：旧日志的活动世代已被顶替 → 放弃写盘并告警；现任日志正常收尾
        EventJournal current = new EventJournal(new SimpleEventBus(), "journal-unit-current");
        SimpleEventBus staleBus = new SimpleEventBus();
        EventJournal stale = new EventJournal(staleBus, "journal-unit-stale");
        stale.withEpochGuard(current::epoch);
        staleBus.publish(Event.sim("sim.scenario-finished", "sut", Map.of()));

        List<String> staleWarnings = new ArrayList<>();
        stale.finish(staleWarnings, ScenarioResult.create());
        assertTrue(staleWarnings.stream().anyMatch(w -> w.contains("superseded")),
                "stale journal must warn that it gave up writing the recording");

        List<String> currentWarnings = new ArrayList<>();
        current.finish(currentWarnings, ScenarioResult.create());
        assertTrue(currentWarnings.stream().noneMatch(w -> w.contains("superseded")),
                "current journal owns the recording and must not warn");
    }
}
