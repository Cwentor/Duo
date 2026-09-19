package io.duo.sim.components.filestore;

import io.duo.sim.kernel.api.ComponentContext;
import io.duo.sim.kernel.api.ComponentException;
import io.duo.sim.kernel.api.ComponentId;
import io.duo.sim.kernel.api.Contract;
import io.duo.sim.kernel.api.EndpointShape;
import io.duo.sim.kernel.api.Event;
import io.duo.sim.kernel.api.ExposedEndpoint;
import io.duo.sim.kernel.api.FaultAction;
import io.duo.sim.kernel.api.FaultInjectable;
import io.duo.sim.kernel.api.HealthReport;
import io.duo.sim.kernel.api.StopMode;
import io.duo.sim.kernel.api.VirtualComponent;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;

/**
 * filestore 契约 virtual 档（M5 交付物 1）：**本地文件系统桩**——把「共享存储/分布式文件系统」
 * 这一契约角色替身成进程内可控的临时目录，供 SUT 读写真实文件（真 IO、真路径），
 * 但不引入 HDFS/S3 之类的重依赖（YAGNI：无真实用例前只做 virtual 桩）。
 *
 * <p>端点形态 {@code FS_PATH}（§7.5 能力元数据）——这是四个端点形态里唯一表达
 * 「非网络、非进程内对象，而是一条文件系统路径」的形态，{@code endpoints()} 暴露
 * {@code ExposedEndpoint.fs(...)}；同时 {@code interfaceDirect=true}（同进程门面
 * {@link #root()}/{@link #write}/{@link #read}/{@link #list}/{@link #delete}），
 * 因为路径语义天然是同进程可用的。
 *
 * <p>安全与诚实（§12 不静默）：
 * <ul>
 *   <li>所有相对路径都在 {@link #resolve} 里做**越界校验**——{@code ../} 逃逸根目录即抛
 *       {@link IllegalArgumentException}，绝不静默写/读到根目录之外；</li>
 *   <li>读不存在的文件抛 {@link UncheckedIOException}（cause 为 {@code NoSuchFileException}），
 *       不返回 null/空串；删除不存在的文件抛 {@code NoSuchFileException}；</li>
 *   <li>未启动时调用读写抛 {@link ComponentException}（不假装成功）。</li>
 * </ul>
 *
 * <p>生命周期语义：{@code start} 创建根目录（config {@code filestore.root} 指定，缺省为
 * 系统临时目录下的随机目录，**由本组件拥有并在 stop 时删除**）；{@code restart} 保留同一
 * 根路径（§7.1「端点与身份保留」）但**清空内容**（内部状态视为全新实例）——这两点都写成测试。
 *
 * <p>故障注入（M5 交付物 6，G4 补对）：{@code crash}——挂载丢失。注入后所有读写**显式失败**
 * （{@link ComponentException}，带原因），**已落盘的数据保持原样**（模拟"存储暂时不可达"，
 * 而不是"数据被抹掉"）；{@code clear} 后自动恢复读写，数据仍在。
 * 只声明这一个动作：filestore 的其它候选动作（{@code freeze}/{@code slow}）语义上与 crash
 * 重复或无法观测，**不静默接受**——未声明的动作一律显式拒绝。
 */
public final class VirtualFilestore implements VirtualComponent, FaultInjectable {

    private volatile ComponentId id;
    private volatile ComponentContext ctx;
    private volatile Path root;
    /** 根目录是否由本组件创建（决定 stop 时是否删除）。 */
    private volatile boolean ownsRoot;
    private final AtomicBoolean running = new AtomicBoolean(false);
    /** 挂载丢失态（M5 交付物 6）：注入后拒绝一切读写，但不丢已落盘数据。 */
    private final AtomicBoolean mountLost = new AtomicBoolean(false);

    // ---- VirtualComponent ----

    @Override
    public ComponentId id() {
        return id;
    }

    @Override
    public void init(ComponentContext ctx) {
        this.id = ctx.id();
        this.ctx = ctx;
    }

    @Override
    public void start() throws ComponentException {
        if (!running.compareAndSet(false, true)) {
            return;
        }
        String configured = ctx.config().get("filestore.root");
        try {
            if (configured == null || configured.isBlank()) {
                root = Files.createTempDirectory("duo-filestore-");
                ownsRoot = true;
            } else {
                root = Path.of(configured).toAbsolutePath().normalize();
                Files.createDirectories(root);
                ownsRoot = false; // 用户指定的目录：只创建、不删除
            }
        } catch (IOException e) {
            running.set(false);
            throw new ComponentException("cannot prepare filestore root: " + e.getMessage(), e);
        }
        fire(Event.sim("sim.filestore-started", id.value(),
                Map.of("root", root.toString(), "owned", ownsRoot)));
    }

    @Override
    public void stop(StopMode mode) {
        if (!running.compareAndSet(true, false)) {
            return;
        }
        String rootPath = root == null ? null : root.toString();
        if (ownsRoot && root != null) {
            deleteRecursively(root);
        }
        fire(Event.sim(mode == StopMode.CRASH ? "sim.filestore-crashed"
                : "sim.filestore-stopped", id.value(),
                Map.of("root", String.valueOf(rootPath))));
    }

    @Override
    public void restart() {
        // §7.1：端点（根路径）与身份保留，内部状态清空＝清空目录内容
        Path kept = root;
        stop(StopMode.GRACEFUL);
        if (kept != null) {
            try {
                Files.createDirectories(kept);
            } catch (IOException e) {
                throw new ComponentException("cannot recreate filestore root: "
                        + e.getMessage(), e);
            }
            root = kept;
            ownsRoot = ctx.config().get("filestore.root") == null
                    || ctx.config().get("filestore.root").isBlank();
            running.set(true);
            fire(Event.sim("sim.filestore-restarted", id.value(),
                    Map.of("root", kept.toString())));
        } else {
            start();
        }
    }

    @Override
    public HealthReport health() {
        if (mountLost.get()) {
            return HealthReport.down("filestore mount lost (injected fault)");
        }
        return running.get() && root != null && Files.isDirectory(root)
                ? HealthReport.ok() : HealthReport.down("filestore not running");
    }

    @Override
    public List<ExposedEndpoint> endpoints() {
        return running.get() && root != null
                ? List.of(ExposedEndpoint.fs(Contract.FILESTORE, root.toString()))
                : List.of();
    }

    // ---- FaultInjectable（M5 交付物 6：filestore 契约的故障例）----

    /**
     * 挂载丢失。注入后所有读写**显式失败**（不假装成功），但**不删数据**——
     * 真实存储不可达时数据仍在盘上，场景要能区分「暂时不可达」与「数据没了」。
     */
    @Override
    public void inject(FaultAction action) {
        if (!FaultAction.CRASH.equals(action.type())) {
            throw new UnsupportedOperationException("unsupported fault: " + action.type());
        }
        if (!mountLost.compareAndSet(false, true)) {
            return; // 已丢失（幂等）
        }
        fire(Event.sim("sim.filestore-mount-lost", id.value(),
                Map.of("root", String.valueOf(root))));
    }

    /** 恢复挂载（幂等；未丢失时重复清除不产生额外事实）。 */
    @Override
    public void clear(FaultAction action) {
        if (!FaultAction.CRASH.equals(action.type())) {
            throw new UnsupportedOperationException("unsupported fault: " + action.type());
        }
        if (!mountLost.compareAndSet(true, false)) {
            return;
        }
        fire(Event.sim("sim.filestore-mount-restored", id.value(), Map.of()));
    }

    /** 挂载是否处于丢失态（诊断/测试用）。 */
    public boolean isMountLost() {
        return mountLost.get();
    }

    /** 该契约可注入的故障（provider 元数据与实现必须一致，见 ContractRegistry 校验）。 */
    public static Set<String> supportedFaults() {
        return Set.of(FaultAction.CRASH);
    }

    // ---- 同进程门面（interface-direct）----

    /** 根目录（未启动返回 null）。 */
    public Path root() {
        return root;
    }

    /**
     * 解析相对路径并做**越界校验**：解析结果必须仍在根目录内，否则显式拒绝
     * （{@code ../} 逃逸是真实缺陷来源，不静默放行）。
     */
    public Path resolve(String relative) {
        requireRunning();
        if (relative == null || relative.isBlank()) {
            throw new IllegalArgumentException("filestore path must not be blank");
        }
        Path candidate = root.resolve(relative).normalize();
        if (!candidate.startsWith(root)) {
            throw new IllegalArgumentException("filestore path escapes root: " + relative);
        }
        return candidate;
    }

    /** 写入（自动建父目录）；返回写入字节数。 */
    public int write(String relative, String content) {
        Path target = resolve(relative);
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        try {
            if (target.getParent() != null) {
                Files.createDirectories(target.getParent());
            }
            Files.write(target, bytes);
        } catch (IOException e) {
            throw new UncheckedIOException("filestore write failed: " + relative, e);
        }
        fire(Event.sim("sim.filestore-written", id.value(),
                Map.of("path", relative, "bytes", bytes.length)));
        return bytes.length;
    }

    /** 读取（文件不存在即抛 UncheckedIOException，cause 为 NoSuchFileException，不返回 null/空串）。 */
    public String read(String relative) {
        Path target = resolve(relative);
        try {
            return Files.readString(target, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("filestore read failed: " + relative, e);
        }
    }

    /** 列出根目录下全部**文件**的相对路径（排序，目录不递归下钻）。 */
    public List<String> list() {
        requireRunning();
        try (Stream<Path> walk = Files.walk(root)) {
            List<String> out = new ArrayList<>();
            walk.filter(Files::isRegularFile)
                    .forEach(p -> out.add(root.relativize(p).toString().replace('\\', '/')));
            out.sort(Comparator.naturalOrder());
            return out;
        } catch (IOException e) {
            throw new UncheckedIOException("filestore list failed", e);
        }
    }

    /** 删除文件（不存在＝显式失败，避免「删了但其实没有」的假成功）。 */
    public void delete(String relative) {
        Path target = resolve(relative);
        try {
            if (!Files.deleteIfExists(target)) {
                throw new java.nio.file.NoSuchFileException(target.toString());
            }
        } catch (IOException e) {
            throw new UncheckedIOException("filestore delete failed: " + relative, e);
        }
    }

    private void requireRunning() {
        if (!running.get() || root == null) {
            throw new ComponentException("filestore not running: " + id);
        }
        if (mountLost.get()) {
            // §12 不静默：挂载丢失期间读写必须显式失败，而不是"看起来写成功了"
            throw new ComponentException("filestore mount lost (crash injected): " + id);
        }
    }

    private static void deleteRecursively(Path dir) {
        try (Stream<Path> walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                    // 尽力而为：临时目录清理失败不应让 stop 失败
                }
            });
        } catch (IOException ignored) {
            // 同上
        }
    }

    private void fire(Event e) {
        if (ctx != null) {
            ctx.eventBus().publish(e);
        }
    }
}
