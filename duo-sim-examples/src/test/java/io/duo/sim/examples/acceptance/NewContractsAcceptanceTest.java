package io.duo.sim.examples.acceptance;

import io.duo.sim.components.engine.VirtualEngine;
import io.duo.sim.components.filestore.VirtualFilestore;
import io.duo.sim.components.message.VirtualMessageBroker;
import io.duo.sim.components.resource.VirtualResourceManager;
import io.duo.sim.components.scheduler.VirtualScheduler;
import io.duo.sim.kernel.api.Contract;
import io.duo.sim.kernel.api.EndpointShape;
import io.duo.sim.kernel.api.Event;
import io.duo.sim.kernel.core.ContractRegistry;
import io.duo.sim.scenario.ScenarioEngine;
import io.duo.sim.scenario.ScenarioLoader;
import io.duo.sim.scenario.model.Scenario;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * M5 交付物 1/2/3 验收：**四个此前无实现的契约 + scheduler 的 virtual 档**在一条 YAML 场景里
 * 同时起齐并完成一次真实调度闭环。
 *
 * <p>验收要点（对应 ROADMAP M5 的达成判定）：
 * <ol>
 *   <li>8 个契约全部至少有一个可运行实现——本用例覆盖 engine/filestore/message/resource 四个新增，
 *       并顺带覆盖 scheduler 的 virtual 档（real 档早已存在）；</li>
 *   <li>**SUT 可落在 worker 侧**：调度侧完全是框架替身（{@code scheduler/virtual}），
 *       场景里没有任何调度器代码；</li>
 *   <li>M5-3 三个故障动作（resource-exhaust / freeze / slow）都能从 YAML 时间线注入，
 *       且各自产生可观测事实、不丢任务（{@code noTaskLost} 断言通过）。</li>
 * </ol>
 */
class NewContractsAcceptanceTest {

    private static Scenario load(String resource) {
        try (InputStream in = NewContractsAcceptanceTest.class.getResourceAsStream(resource)) {
            assertNotNull(in, resource + " must exist");
            return ScenarioLoader.load(in);
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void allNewContractsAndFaultActionsRunInOneScenario() throws Exception {
        var scenario = load("/scenarios/m5-new-contracts-acceptance.yaml");
        // SPI 发现全部 provider：能通过校验即证明 (contract, tier) 已注册且元数据自洽
        var registry = ContractRegistry.loadFromServiceLoader();

        try (ScenarioEngine engine = ScenarioEngine.validated(scenario, registry)) {
            // SUT（real 档调度器）必须先起：worker 靠 registry 发现调度器端点，
            // 端点缺失会让 worker 启动失败（§12 快速失败，不静默降级）
            engine.startSut();
            engine.startComponents();

            // 新增契约的实例类型与端点形态（校验期只读元数据，这里是运行时的实证）
            assertTrue(engine.components().get("engines") instanceof VirtualEngine);
            assertTrue(engine.components().get("files") instanceof VirtualFilestore);
            assertTrue(engine.components().get("mq") instanceof VirtualMessageBroker);
            assertTrue(engine.components().get("resources") instanceof VirtualResourceManager);

            var files = (VirtualFilestore) engine.components().get("files");
            assertEquals(Contract.FILESTORE, files.endpoints().get(0).contract());
            assertEquals(EndpointShape.FS_PATH, new io.duo.sim.components.provider
                    .VirtualFilestoreProvider().metadata().endpointShape());
            assertEquals("fs", files.endpoints().get(0).kind(), "FS_PATH 端点用 fs 形态");

            // 等 DAG 收敛（SUT 调度器派发给 virtual worker；slow 会把时长×2）
            long deadline = System.currentTimeMillis() + 20_000;
            while (System.currentTimeMillis() < deadline
                    && engine.events().stream().noneMatch(e ->
                            "sut.dag-terminal".equals(e.type()))) {
                Thread.sleep(50);
            }
            // 再等带 duration 的 freeze 自然到期（TimelineScheduler 的定时 clear）：
            // 该动作在 400ms 注入、300ms 后到期，而 stop 会取消 pending 的定时任务——
            // 不等就会得到"解冻事实永不出现"的假红（本轮实测踩到并修正）。
            long clearDeadline = System.currentTimeMillis() + 5_000;
            while (System.currentTimeMillis() < clearDeadline
                    && engine.events().stream().noneMatch(e ->
                            "sim.engine-resumed".equals(e.type()))) {
                Thread.sleep(20);
            }
            engine.stop();

            List<Event> events = engine.events();
            // ---- 三个故障动作的事实都可观测（顺序＝注入次序）----
            for (String type : List.of("sim.resource-exhausted", "sim.engine-frozen",
                    "sim.worker-slowed")) {
                assertTrue(events.stream().anyMatch(e -> type.equals(e.type())),
                        type + " must be observable in the event stream");
            }
            // ---- 调度/派发事实面（SUT 侧调度器 + 新契约替身共同参与）----
            assertTrue(events.stream().anyMatch(e -> "sut.scheduler-started".equals(e.type())));
            assertTrue(events.stream().anyMatch(e -> "sut.worker-registered".equals(e.type())));
            assertTrue(events.stream().anyMatch(e -> "sut.task-dispatched".equals(e.type())));
            assertTrue(events.stream().anyMatch(e -> "sut.dag-terminal".equals(e.type())));

            var snapshot = engine.result().snapshot();
            assertTrue(snapshot.injectionFailures().isEmpty(),
                    "三个动作都必须被接受，实际失败：" + snapshot.injectionFailures());
            // 断言条数与 YAML 一一对应（3 条：故障顺序 + 自动解冻顺序 + DAG 收敛）
            assertEquals(3, snapshot.assertions().size(),
                    "YAML 断言条数：" + snapshot.assertions());
            snapshot.assertions().forEach(a -> assertTrue(a.passed(),
                    "断言 '" + a.name() + "' 失败：" + a.detail()));
            // ---- 故障**期间**的语义（不是"故障发生过"这种事后可签的空事实）----
            int frozen = indexOf(events, "sim.engine-frozen");
            int resumed = indexOf(events, "sim.engine-resumed");
            assertTrue(frozen >= 0 && resumed > frozen,
                    "解冻事实必须出现在冻结之后：" + types(events));
            // 冻结窗口内不得补报终态（终态挂起到 clear）：engine 的 3s 任务在 400~700ms 的
            // 冻结窗口里还没结束，窗口内出现的状态报告只能是非终态
            for (int i = frozen; i < resumed; i++) {
                Event e = events.get(i);
                if ("sim.engine-task-status".equals(e.type())) {
                    String state = String.valueOf(e.payload().get("state"));
                    assertTrue(!state.startsWith("SUCCEEDED") && !state.startsWith("FAILED")
                                    && !state.startsWith("CANCELLED"),
                            "冻结期间不得补报终态，实测第 " + i + " 条：" + state);
                }
            }
        }
    }

    private static int indexOf(List<Event> events, String type) {
        for (int i = 0; i < events.size(); i++) {
            if (type.equals(events.get(i).type())) {
                return i;
            }
        }
        return -1;
    }

    private static String types(List<Event> events) {
        return events.stream().map(Event::type).distinct()
                .reduce((a, b) -> a + "," + b).orElse("(none)");
    }

    @Test
    void slowWithIllegalFactorIsRejectedAtInjectionTime() throws Exception {
        // §12 不静默：params.factor ≤ 1.0 视为无效注入（而不是「注入了但没变慢」）
        var scenario = load("/scenarios/m5-new-contracts-acceptance.yaml");
        var registry = ContractRegistry.loadFromServiceLoader();
        try (ScenarioEngine engine = ScenarioEngine.validated(scenario, registry)) {
            engine.startSut();
            engine.startComponents();
            var bad = new io.duo.sim.kernel.api.FaultAction(
                    io.duo.sim.kernel.api.FaultAction.SLOW,
                    io.duo.sim.kernel.api.FaultAction.ComponentAddress.of(
                            new io.duo.sim.kernel.api.ComponentId("workers")),
                    java.util.Map.of("factor", "0.5"), null);
            var result = engine.inject(bad);
            // 注入被拒绝：要么抛错要么记 injectionFailure，但绝不静默成功
            assertTrue(!result.success(), "非法 slow 倍数必须显式失败：" + result);
            assertTrue(String.valueOf(result.reason()).contains("slow factor"),
                    "拒绝原因必须点明倍数非法：" + result.reason());
            engine.stop();
        }
    }
}
