package io.duo.sim.examples.worker;

import java.util.Map;

/**
 * SUT 侧配置读取（示例共享口径）：把节点 {@code config} 的键**去掉 UTF-8 BOM** 后再查。
 *
 * <p>为什么需要（实测发现）：YAML 场景文件若保存为「UTF-8 with BOM」，BOM 会粘在**首个键名**
 * 上——{@code dag.tasks} 变成 {@code \uFEFFdag.tasks}，于是 {@code config.get("dag.tasks")}
 * 返回 null、实现静默退回缺省值：用户明明写了配置，跑出来的却是另一套拓扑，且**没有任何事实
 * 说明这件事**（违反 §12「不静默」）。
 *
 * <p>消费者：{@code RealWorkerSut}（worker 侧 SUT 的配置读取全走这里）。BOM 口径由
 * {@code WorkerSutAcceptanceTest#bomPrefixedFirstKeyIsStillHonored} 钉住（BOM 钉在首个键上，
 * 断言 SUT 报出的实例数仍是配置值）。它不属于内核 SPI——示例里"怎么读 config"的公共口径就是本类，
 * 避免每个 SUT 各写一份去 BOM 逻辑。
 *
 * <p>公开（{@code public}）而不是包内私有，是因为它被当作**示例层的公共口径**：场景作者照着写自己的
 * SUT 时，读配置这一段应当能直接复用，而不是把去 BOM 的分支再抄一遍——抄错的那一份就是静默失效的
 * 那一份。示例之间（{@code worker} 与 {@code scheduler} 两个包）也共用同一口径。
 */
public final class SutConfigs {

    private static final String BOM = "\uFEFF";

    private SutConfigs() {
    }

    /** 读取配置：缺省键名直查，命中不到再试「去掉 BOM 的键名」；仍无则返回 {@code defaultValue}。 */
    public static String get(Map<String, String> config, String key, String defaultValue) {
        String v = config.get(key);
        if (v != null) {
            return v;
        }
        v = config.get(BOM + key);
        return v == null ? defaultValue : v;
    }

    /**
     * 是否同时存在 BOM 键与同名干净键（用户写了两遍且其中一遍带 BOM＝配置有歧义，**必须报错而不是
     * 挑一个用**）。读取口径取"干净键优先"，所以这种歧义在读的时候是看不见的——只有显式问才有答案，
     * 调用方据此报出 `sut.crashed` 之类的显式失败（§12）。{@code RealWorkerSut} 在启动期问一次。
     */
    public static boolean hasConflict(Map<String, String> config, String key) {
        return config.get(key) != null && config.get(BOM + key) != null;
    }

    /**
     * 首个被 BOM 污染的键名（诊断用：没有则返回 {@code null}）。
     *
     * <p>它的用途是**把"去 BOM"这件事本身变成可观测事实**，而不只是实现里的一段分支：出问题时
     * 能直接看到"哪个键被污染了"，而不是靠猜。{@code RealWorkerSut} 把它报进
     * {@code sut.worker-sut-started} 载荷（BOM 场景下非空，干净场景下为空）。
     */
    public static String firstKeyWithBom(Map<String, String> config) {
        for (String k : config.keySet()) {
            if (k.startsWith(BOM)) {
                return k;
            }
        }
        return null;
    }

    /** 拼进事件载荷的键清单（键名原样，便于排查"配置到底进没进 SUT"）。 */
    public static String keyList(Map<String, String> config) {
        return String.join(",", config.keySet());
    }
}
