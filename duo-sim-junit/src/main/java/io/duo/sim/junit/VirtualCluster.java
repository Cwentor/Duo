package io.duo.sim.junit;

import java.lang.annotation.ElementType;
import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 场景化测试注解（M2 T30）：声明一个场景 YAML（classpath 路径）作为测试夹具。
 *
 * <p>扩展在测试类/方法前后管理场景生命周期（加载→校验→启动→注入引擎→停止），
 * 并把 {@link io.duo.sim.scenario.ScenarioEngine} 注入到测试方法参数
 * （经 {@link ScenarioEngineParameterResolver}）。
 *
 * <pre>{@code
 * @VirtualCluster("/scenarios/m2-reelection.yaml")
 * class MyScenarioTest {
 *     @Test
 *     void scenarioRuns(ScenarioEngine engine) {
 *         // engine 已启动；断言用 engine.events() / engine.result()
 *     }
 * }
 * }</pre>
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD})
@Inherited
public @interface VirtualCluster {

    /** 场景 YAML 的 classpath 路径（如 {@code /scenarios/xxx.yaml}）。 */
    String value();

    /** 是否在测试前自动启动 SUT 与组件（缺省 true）。 */
    boolean autoStart() default true;

    /** 是否在测试后执行断言评估并失败（缺省 true）。 */
    boolean assertResult() default true;

    /** SUT 退出等待上限（毫秒）。 */
    long sutExitTimeoutMs() default 90_000;
}
