package io.duo.sim.components.message;

import io.duo.sim.kernel.api.ComponentContext;
import io.duo.sim.kernel.api.ComponentException;
import io.duo.sim.kernel.api.ComponentId;
import io.duo.sim.kernel.api.EndpointShape;
import io.duo.sim.kernel.api.Event;
import io.duo.sim.kernel.api.ExposedEndpoint;
import io.duo.sim.kernel.api.FaultAction;
import io.duo.sim.kernel.api.FaultInjectable;
import io.duo.sim.kernel.api.HealthReport;
import io.duo.sim.kernel.api.StopMode;
import io.duo.sim.kernel.api.VirtualComponent;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * message 契约 virtual 档（M5 交付物 1）：**内存消息队列桩**——主题（topic）+ 发布/订阅 +
 * 按序拉取，供 SUT 演练「消息驱动」路径而无需拉起 Kafka。
 *
 * <p>端点形态 {@code NONE} + {@code interfaceDirect=true}（§7.5 的强制一致性：
 * NONE ⇒ interface-direct）——消费方拿的是同进程门面 {@link #publish}/{@link #drain}/
 * {@link #subscribe}，不存在网络端口。这与设计里「message 契约的 virtual 档是内存桩」一致；
 * 真实 Kafka（embedded/container 档）在出现真实用例前不引入（YAGNI：无真实用例的契约只做
 * virtual 桩，见 M5 交付物 2 的档位补齐策略）。
 *
 * <p>语义（尽量贴近真实 broker 的可观测事实，避免「桩的语义与真品无关」）：
 * <ul>
 *   <li>每主题一条**有序**队列，{@link #drain} 取走并清空（至少一次投递的简化模型）；
 *   <li>{@link #subscribe} 的回调按发布顺序同步触发，且**同时**保留在队列里
 *       （订阅＝观察，drain＝消费，两条通道互不干扰——断言可以只看订阅）；</li>
 *   <li>主题不存在即自动创建（真实 broker 的 auto-create 行为），但 {@link #depth} 等查询
 *       不会因为「没建过」而报错；</li>
 *   <li>未启动时调用即抛 {@link ComponentException}（不假装成功）。</li>
 * </ul>
 *
 * <p>可观测：{@code sim.message-started/-stopped/-crashed/-restarted}（生命周期）、
 * {@code sim.message-published}（每次发布：topic + seq + 累计深度）。
 * 配置项：{@code message.maxDepthPerTopic}（缺省 10_000；超出即**显式拒绝**并抛
 * {@link ComponentException}——队列无界会静默吃内存，§12 不静默）。
 *
 * <p>故障注入（M5 交付物 6，G4 补对）：{@code freeze}——冻结期间 {@code publish}
 * **显式拒绝**（`message broker frozen`），已入队消息不受影响（消息不会凭空消失），
 * 解冻后自动恢复发布。冻结只影响**写**路径：{@code depth}/{@code drain}/{@code subscribe}
 * 仍可读——真实 broker 卡住时消费侧仍能把已有的消息取出去。
 */
public final class VirtualMessageBroker implements VirtualComponent, FaultInjectable {

    private static final int DEFAULT_MAX_DEPTH = 10_000;

    private volatile ComponentId id;
    private volatile ComponentContext ctx;
    private volatile int maxDepthPerTopic = DEFAULT_MAX_DEPTH;
    /** 冻结态（M5 交付物 6）：冻结期间拒绝发布，但不丢已入队消息。 */
    private final AtomicBoolean frozen = new AtomicBoolean(false);

    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicLong seq = new AtomicLong();
    private final Map<String, Deque<String>> topics = new ConcurrentHashMap<>();
    private final Map<String, List<Consumer<String>>> subscribers = new ConcurrentHashMap<>();

    // ---- VirtualComponent ----

    @Override
    public ComponentId id() {
        return id;
    }

    @Override
    public void init(ComponentContext ctx) {
        this.id = ctx.id();
        this.ctx = ctx;
        this.maxDepthPerTopic = Integer.parseInt(ctx.config().getOrDefault(
                "message.maxDepthPerTopic", String.valueOf(DEFAULT_MAX_DEPTH)));
    }

    @Override
    public void start() throws ComponentException {
        if (!running.compareAndSet(false, true)) {
            return;
        }
        fire(Event.sim("sim.message-started", id.value(),
                Map.of("maxDepthPerTopic", maxDepthPerTopic)));
    }

    @Override
    public void stop(StopMode mode) {
        if (!running.compareAndSet(true, false)) {
            return;
        }
        int pending = topics.values().stream().mapToInt(Deque::size).sum();
        topics.clear();
        subscribers.clear();
        fire(Event.sim(mode == StopMode.CRASH ? "sim.message-crashed"
                : "sim.message-stopped", id.value(),
                Map.of("droppedMessages", pending, "mode", mode.name())));
    }

    @Override
    public void restart() {
        // §7.1：身份保留、内部状态清空（队列与订阅者全丢——真实 broker 重启后的语义）
        stop(StopMode.GRACEFUL);
        seq.set(0);
        start();
        fire(Event.sim("sim.message-restarted", id.value(), Map.of()));
    }

    @Override
    public HealthReport health() {
        return running.get() ? HealthReport.ok() : HealthReport.down("message broker not running");
    }

    @Override
    public List<ExposedEndpoint> endpoints() {
        return List.of(); // 端点形态 NONE：同进程门面，无对外端口
    }

    // ---- 同进程门面（interface-direct）----

    /** 发布一条消息；队列超过 {@code message.maxDepthPerTopic} 即显式拒绝。 */
    public long publish(String topic, String payload) {
        requireRunning();
        requireTopic(topic);
        if (frozen.get()) {
            // §12 不静默：冻结期间发布必须显式失败，而不是「发了但队列没动」
            throw new ComponentException("message broker frozen: cannot publish to topic '"
                    + topic + "'");
        }
        Deque<String> queue = topics.computeIfAbsent(topic, k -> new ArrayDeque<>());
        long n;
        synchronized (queue) {
            if (queue.size() >= maxDepthPerTopic) {
                throw new ComponentException("message topic '" + topic
                        + "' exceeded maxDepthPerTopic=" + maxDepthPerTopic
                        + " (message dropped, not silently)");
            }
            queue.addLast(payload);
            n = seq.incrementAndGet();
        }
        fire(Event.sim("sim.message-published", id.value(),
                Map.of("topic", topic, "seq", n, "depth", queue.size())));
        for (Consumer<String> sub : subscribers.getOrDefault(topic, List.of())) {
            sub.accept(payload);
        }
        return n;
    }

    /** 取走并清空主题队列（按发布顺序）。 */
    public List<String> drain(String topic) {
        requireRunning();
        requireTopic(topic);
        Deque<String> queue = topics.get(topic);
        if (queue == null) {
            return List.of();
        }
        synchronized (queue) {
            List<String> out = new ArrayList<>(queue);
            queue.clear();
            return out;
        }
    }

    /** 当前深度（未消费消息数）。 */
    public int depth(String topic) {
        requireRunning();
        requireTopic(topic);
        Deque<String> queue = topics.get(topic);
        if (queue == null) {
            return 0;
        }
        synchronized (queue) {
            return queue.size();
        }
    }

    /** 订阅（回调按发布顺序同步触发；返回取消句柄）。 */
    public AutoCloseable subscribe(String topic, Consumer<String> onMessage) {
        requireRunning();
        requireTopic(topic);
        List<Consumer<String>> list = subscribers.computeIfAbsent(topic,
                k -> new CopyOnWriteArrayList<>());
        list.add(onMessage);
        return () -> list.remove(onMessage);
    }

    /** 已创建的主题名（诊断/测试用）。 */
    public List<String> topics() {
        return new ArrayList<>(topics.keySet());
    }

    /** 冻结态（诊断/测试用）。 */
    public boolean isFrozen() {
        return frozen.get();
    }

    // ---- FaultInjectable（M5 交付物 6：message 契约的故障例）----

    /**
     * 冻结/解冻消息通路。{@code freeze}＝拒收新发布（显式抛错，不静默丢消息），
     * 解冻复用同一注入点（幂等：重复注入不重复发事实）。
     */
    @Override
    public void inject(FaultAction action) {
        if (!FaultAction.FREEZE.equals(action.type())) {
            throw new UnsupportedOperationException("unsupported fault: " + action.type());
        }
        if (!frozen.compareAndSet(false, true)) {
            return; // 已冻结（幂等）
        }
        fire(Event.sim("sim.message-frozen", id.value(),
                Map.of("pendingTopics", topics.size())));
    }

    @Override
    public void clear(FaultAction action) {
        if (!FaultAction.FREEZE.equals(action.type())) {
            throw new UnsupportedOperationException("unsupported fault: " + action.type());
        }
        if (!frozen.compareAndSet(true, false)) {
            return; // 未冻结（幂等）
        }
        fire(Event.sim("sim.message-resumed", id.value(), Map.of()));
    }

    /** 该契约可注入的故障（provider 元数据与实现必须一致，见 ContractRegistry 校验）。 */
    public static Set<String> supportedFaults() {
        return Set.of(FaultAction.FREEZE);
    }

    private void requireTopic(String topic) {
        if (topic == null || topic.isBlank()) {
            throw new IllegalArgumentException("message topic must not be blank");
        }
    }

    private void requireRunning() {
        if (!running.get()) {
            throw new ComponentException("message broker not running: " + id);
        }
    }

    private void fire(Event e) {
        if (ctx != null) {
            ctx.eventBus().publish(e);
        }
    }
}
