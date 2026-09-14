package io.duo.sim.kernel.sut;

import io.duo.sim.kernel.api.ComponentException;
import io.duo.sim.kernel.api.Event;
import io.duo.sim.kernel.api.SutContext;
import io.duo.sim.kernel.api.SutMain;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * in-process SUT 启动器（§7.3，计划 T12）：
 * 独立线程调阻塞 {@code run(SutContext)}；ready 回调默认 60s 超时归启动失败；
 * 退出探测区分 {@code sut.exited} / {@code sut.crashed}；
 * 协作停止＝触发 onStop handler + 带超时等待 run() 返回（未注册则 interrupt）。
 */
public final class SutLauncher implements AutoCloseable {

    /** 启动结果。 */
    public record ExitState(boolean exited, boolean normal, String error) {
    }

    private static final long DEFAULT_READY_TIMEOUT_MS = 60_000;
    private static final long STOP_TIMEOUT_MS = 10_000;

    private final String sutId;
    private final SutMain main;
    private final SutContextImpl ctx;
    private final Consumer<Event> eventSink;
    private final Path configOut;
    private final Map<String, String> endpoints;
    private final long readyTimeoutMs;
    private volatile Thread runner;
    private final AtomicBoolean stopRequested = new AtomicBoolean(false);
    private volatile Runnable stopHandler;
    private final CountDownLatch stopDone = new CountDownLatch(1);
    /**
     * ready 真实到达标志（区分 {@code ctx.ready()} 与 finally 放行）。
     * 唯一写入点＝{@link SutContextImpl#ready()}；{@code start()} 只读本字段判断启动失败。
     */
    private volatile boolean readyConfirmed;
    private volatile ExitState exitState;

    public SutLauncher(String sutId, SutMain main, Map<String, Object> directBindings,
                       Map<String, String> config, Map<String, String> endpoints,
                       Consumer<Event> eventSink, Path configOut) {
        this(sutId, main, directBindings, config, endpoints, eventSink, configOut,
                DEFAULT_READY_TIMEOUT_MS);
    }

    public SutLauncher(String sutId, SutMain main, Map<String, Object> directBindings,
                       Map<String, String> config, Map<String, String> endpoints,
                       Consumer<Event> eventSink, Path configOut, long readyTimeoutMs) {
        this.sutId = sutId;
        this.main = main;
        this.eventSink = eventSink;
        this.configOut = configOut;
        this.endpoints = Map.copyOf(endpoints);
        this.readyTimeoutMs = readyTimeoutMs;
        this.ctx = new SutContextImpl(this, directBindings, config, endpoints, eventSink);
    }

    /**
     * 启动 SUT 并等待 ready。
     *
     * @throws ComponentException ready 超时（§12 启动失败路径）
     */
    public void start() {
        writeEndpointsConfig();
        runner = Thread.ofVirtual().name("duo-sut-" + sutId).start(this::runAndWatch);
        try {
            if (!ctx.readyLatch.await(readyTimeoutMs, TimeUnit.MILLISECONDS)) {
                throw new ComponentException("SUT ready timeout ("
                        + readyTimeoutMs + "ms): " + sutId);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ComponentException("SUT ready interrupted: " + sutId, e);
        }
        // ready 未确认而 SUT 已退出＝启动失败（快速失败，§12，报真实根因而非 ready 超时）
        // 注意：ready 已确认后的退出（正常结束如 demo-scheduler 跑完 DAG，或崩溃）不属于启动失败，
        // 由 exitState/awaitSutExit 传达
        if (!readyConfirmed && exitState != null) {
            throw new ComponentException("SUT exited before ready: " + sutId
                    + (exitState.normal() ? "" : " (crashed: " + exitState.error() + ")"),
                    exitState.normal() ? null : new IllegalStateException(exitState.error()));
        }
    }

    private void runAndWatch() {
        try {
            main.run(ctx);
            exitState = new ExitState(true, true, null);
            eventSink.accept(Event.sut("sut.exited", sutId, Map.of()));
        } catch (Throwable t) {
            exitState = new ExitState(true, false, String.valueOf(t));
            eventSink.accept(Event.sut("sut.crashed", sutId,
                    Map.of("error", String.valueOf(t))));
        } finally {
            // 无条件放行：run() 在 ready 前退出（正常返回或抛异常）也要唤醒 start()，
            // 使其据 exitState 报真实根因，而不是把根因拖成 ready 超时（§12）。
            // 是否属启动失败由 readyConfirmed 判定（见 start()），与放行动作解耦。
            ctx.readyLatch.countDown();
            stopDone.countDown();
        }
    }

    /**
     * 协作式停止（§7.3）：触发 handler 请求退出 + 带超时等待 {@code run()} 返回；
     * 未注册 handler 时 interrupt 线程。返回是否成功停止（超时＝false，记停止失败由调用方处理）。
     */
    public boolean stop() {
        stopRequested.set(true);
        if (stopHandler != null) {
            try {
                stopHandler.run();
            } catch (RuntimeException e) {
                eventSink.accept(Event.sut("sut.stop-handler-error", sutId,
                        Map.of("error", String.valueOf(e))));
            }
        } else if (runner != null) {
            runner.interrupt();
        }
        try {
            return stopDone.await(STOP_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    public ExitState exitState() {
        return exitState;
    }

    public boolean isStopRequested() {
        return stopRequested.get();
    }

    /** 端点配置文件（§7.3 主途径；{@code duo.endpoint.<contract>=<endpoint>}）。 */
    private void writeEndpointsConfig() {
        if (configOut == null) {
            return;
        }
        try {
            if (configOut.getParent() != null) {
                Files.createDirectories(configOut.getParent());
            }
            try (PrintWriter pw = new PrintWriter(Files.newBufferedWriter(configOut,
                    StandardCharsets.UTF_8))) {
                endpoints.forEach((contract, addr) ->
                        pw.println("duo.endpoint." + contract + "=" + addr));
            }
        } catch (IOException e) {
            throw new ComponentException("cannot write endpoint config: " + configOut, e);
        }
    }

    @Override
    public void close() {
        stop();
    }

    // ---- SutContext 实现 ----

    private static final class SutContextImpl implements SutContext {

        private final SutLauncher owner;
        private final Map<String, Object> directBindings;
        private final Map<String, String> config;
        private final Map<String, String> endpoints;
        private final Consumer<Event> eventSink;
        final CountDownLatch readyLatch = new CountDownLatch(1);

        SutContextImpl(SutLauncher owner, Map<String, Object> directBindings,
                       Map<String, String> config, Map<String, String> endpoints,
                       Consumer<Event> eventSink) {
            this.owner = owner;
            this.directBindings = new ConcurrentHashMap<>(directBindings);
            this.config = Map.copyOf(config);
            this.endpoints = Map.copyOf(endpoints);
            this.eventSink = eventSink;
        }

        @Override
        public Map<String, String> endpointByContract() {
            return endpoints;
        }

        @Override
        @SuppressWarnings("unchecked")
        public Map<String, Object> directBindings() {
            return (Map<String, Object>) Map.copyOf(directBindings);
        }

        @Override
        public io.duo.sim.kernel.api.SutEventPublisher events() {
            return (type, payload) -> {
                if (!type.startsWith(Event.SUT_PREFIX)) {
                    throw new IllegalArgumentException("SUT facts must use sut. prefix: " + type);
                }
                eventSink.accept(Event.sut(type, "sut", payload));
            };
        }

        @Override
        public Map<String, String> config() {
            return config;
        }

        @Override
        public void onStop(Runnable handler) {
            owner.stopHandler = handler;
        }

        @Override
        public void ready() {
            // 写 owner 字段（本类不得再声明同名字段——曾因字段遮蔽使该写入落到内层副本，
            // 导致 start() 的启动失败判定恒真、修复失效）
            owner.readyConfirmed = true;
            readyLatch.countDown();
        }
    }
}
