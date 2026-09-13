package io.duo.sim.kernel.sut;

import io.duo.sim.kernel.api.ComponentException;
import io.duo.sim.kernel.api.Event;
import io.duo.sim.kernel.api.SutMain;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SutLauncherTest {

    private final List<Event> events = new ArrayList<>();

    @Test
    void normalExitEmitsSutExited() throws Exception {
        SutMain main = ctx -> {
            ctx.ready();
            ctx.events().publish("sut.task-terminal", Map.of("taskId", "t-1"));
            // run() 正常返回
        };
        var launcher = new SutLauncher("m", main, Map.of(), Map.of(),
                Map.of("scheduler", "127.0.0.1:9"), events::add, null);
        launcher.start();
        assertTrue(launcher.stop());
        // 轮询退出态
        for (int i = 0; i < 50; i++) {
            if (launcher.exitState() != null) {
                break;
            }
            Thread.sleep(20);
        }
        assertEquals(true, launcher.exitState().normal());
        assertTrue(events.stream().anyMatch(e -> e.type().equals("sut.exited")));
        assertTrue(events.stream().anyMatch(e -> e.type().equals("sut.task-terminal")));
    }

    @Test
    void crashEmitsSutCrashed() {
        SutMain main = ctx -> {
            ctx.ready();
            throw new IllegalStateException("boom");
        };
        var launcher = new SutLauncher("m", main, Map.of(), Map.of(),
                Map.of(), events::add, null);
        launcher.start();
        // run() 抛异常 → sut.crashed；退出态 normal=false
        for (int i = 0; i < 50 && launcher.exitState() == null; i++) {
            try {
                Thread.sleep(20);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        assertFalse(launcher.exitState().normal());
        assertTrue(events.stream().anyMatch(e -> e.type().equals("sut.crashed")));
    }

    @Test
    void readyTimeoutThrowsStartFailure() {
        SutMain main = ctx -> {
            // 永不 ready，阻塞
            try {
                Thread.sleep(5000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        };
        var launcher = new SutLauncher("m", main, Map.of(), Map.of(), Map.of(),
                events::add, null, 200); // ready 超时 200ms
        var e = assertThrows(ComponentException.class, launcher::start);
        assertTrue(e.getMessage().contains("ready timeout"));
    }

    @Test
    void cooperativeStopRunsHandler() throws Exception {
        var stopped = new java.util.concurrent.atomic.AtomicBoolean();
        SutMain main = ctx -> {
            ctx.onStop(() -> stopped.set(true)); // 协作式停止
            ctx.ready();
            while (!stopped.get()) {
                Thread.sleep(10);
            }
        };
        var launcher = new SutLauncher("m", main, Map.of(), Map.of(), Map.of(),
                events::add, null);
        launcher.start();
        assertTrue(launcher.stop());
        assertTrue(stopped.get());
        assertTrue(launcher.exitState().normal());
    }

    @Test
    void missingStopHandlerFallsBackToInterrupt() {
        SutMain main = ctx -> {
            ctx.ready();
            try {
                Thread.sleep(30_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                // 正常响应中断退出 → exited
            }
        };
        var launcher = new SutLauncher("m", main, Map.of(), Map.of(), Map.of(),
                events::add, null);
        launcher.start();
        assertTrue(launcher.stop()); // 未注册 handler → interrupt 兜底
        assertTrue(launcher.exitState().normal()); // run() 响应中断，返回＝exited
    }

    @Test
    void sutPublisherEnforcesPrefix() {
        SutMain main = ctx -> {
            ctx.ready();
            assertThrows(IllegalArgumentException.class,
                    () -> ctx.events().publish("bogus-event", Map.of()));
        };
        var launcher = new SutLauncher("m", main, Map.of(), Map.of(), Map.of(),
                events::add, null);
        launcher.start();
        assertTrue(launcher.stop());
    }
}
