package io.duo.sim.junit;

import io.duo.sim.kernel.core.ContractRegistry;
import io.duo.sim.scenario.ScenarioEngine;
import io.duo.sim.scenario.ScenarioLoader;
import io.duo.sim.scenario.model.Scenario;
import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.ParameterContext;
import org.junit.jupiter.api.extension.ParameterResolver;

import java.io.InputStream;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * {@link VirtualCluster} 的 JUnit5 扩展（M2 T30）：场景生命周期管理 + 引擎注入。
 *
 * <p>生命周期（每测试方法）：加载 YAML → 校验 → 启动 SUT 与组件 → 可选等 SUT 退出 →
 * 测试体执行（可注入 {@link ScenarioEngine}）→ 断言评估（失败即测试失败）→ 停止清理。
 * 失败时输出录制文件路径（§11 审查材料）。
 */
public final class VirtualClusterExtension
        implements BeforeEachCallback, AfterEachCallback, ParameterResolver {

    private static final ExtensionContext.Namespace NS =
            ExtensionContext.Namespace.create(VirtualClusterExtension.class);

    @Override
    public void beforeEach(ExtensionContext context) throws Exception {
        VirtualCluster cfg = findAnnotation(context);
        if (cfg == null) {
            return;
        }
        Scenario scenario;
        try (InputStream in = VirtualClusterExtension.class.getResourceAsStream(cfg.value())) {
            if (in == null) {
                throw new IllegalStateException("scenario resource not found on classpath: "
                        + cfg.value());
            }
            scenario = ScenarioLoader.load(in);
        }
        var registry = ContractRegistry.loadFromServiceLoader();
        ScenarioEngine engine = ScenarioEngine.validated(scenario, registry);
        if (cfg.autoStart()) {
            engine.startSut();
            engine.startComponents();
            engine.awaitSutExit(cfg.sutExitTimeoutMs());
        }
        context.getStore(NS).put(engineKey(context), engine);
    }

    @Override
    public void afterEach(ExtensionContext context) {
        ScenarioEngine engine = context.getStore(NS).remove(engineKey(context),
                ScenarioEngine.class);
        if (engine == null) {
            return;
        }
        VirtualCluster cfg = findAnnotation(context);
        try {
            engine.stop(); // 触发统一清理 + 断言评估 + 录制落盘
            if (cfg != null && cfg.assertResult()) {
                var result = engine.result();
                if (!result.passed()) {
                    var failures = result.snapshot().assertions().stream()
                            .filter(a -> !a.passed())
                            .map(a -> a.name() + ": " + a.detail())
                            .toList();
                    throw new AssertionError("scenario assertions failed: " + failures
                            + " (recording: " + engine.recordingPath() + ")");
                }
            }
        } finally {
            // 确保资源释放（stop 幂等）
            engine.close();
        }
    }

    @Override
    public boolean supportsParameter(ParameterContext pc, ExtensionContext ec) {
        return pc.getParameter().getType() == ScenarioEngine.class
                && ec.getStore(NS).get(engineKey(ec)) != null;
    }

    @Override
    public Object resolveParameter(ParameterContext pc, ExtensionContext ec) {
        return ec.getStore(NS).get(engineKey(ec), ScenarioEngine.class);
    }

    private static Object engineKey(ExtensionContext context) {
        return context.getUniqueId();
    }

    private static VirtualCluster findAnnotation(ExtensionContext context) {
        return context.getTestMethod()
                .flatMap(m -> java.util.Optional.ofNullable(
                        m.getAnnotation(VirtualCluster.class)))
                .or(() -> context.getTestClass()
                        .flatMap(c -> java.util.Optional.ofNullable(
                                c.getAnnotation(VirtualCluster.class))))
                .orElse(null);
    }
}
