package io.duo.sim.examples.acceptance;

import io.duo.sim.scenario.ScenarioEngine;
import io.duo.sim.scenario.ScenarioLoader;
import io.duo.sim.scenario.model.Scenario;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * M9 守卫用例（计划 T-M9-4 明文：「环境缺失→守卫」用例本身**不过门控**，守卫必须永远跑
 * ——M4 复验教训：守卫被门控一起吞掉＝安全属性无人验证）。与 `ZookeeperContainerRegistryGuardTest`
 * 同构：drill 本体（`DsFailoverAcceptanceTest`）被 `-Dduo.ds=true` 门控（真实 DS 环境缺省不存在），
 * 但「物化模板合法 + 拓扑语义正确」是离线可验证属性，由本守卫在常规回归里钉死——
 * 谁改坏场景模板（SUT 标记、external 代起、端点告知、http 探针、flap 先因后果断言），守卫即红。
 *
 * <p>不门控 ⇒ 不依赖 DS 包/JDK11/端口，常规回归零新增依赖、零墙钟（纯文件 + 校验器）。
 * drill 本体的负例取证（断言集可失败，T-M9-3⑥）见
 * `DsFailoverAcceptanceTest` 的 `duo.ds.noflap` 开关与验收记录 §6.1。
 */
class DsFailoverDrillGuardTest {

    @TempDir
    Path tmp;

    @Test
    void materializedTemplatePassesValidatorAndPinsDrillSemantics() throws IOException {
        // 占位符注入任意合法路径（守卫不关心机器真实路径，只关心替换规则与模板语义）
        Path yaml = DsFailoverAcceptanceTest.materializeScenario(tmp,
                "C:/guard/not-a-real-wrapper.ps1", tmp.resolve("duo-ds.properties"));

        // 物化必须替换全部占位符——带着 @token@ 进校验器会把机器相关路径变成假数据
        String text = Files.readString(yaml, StandardCharsets.UTF_8);
        assertFalse(text.contains("@duo.ds.wrapper@"), "wrapper 占位符必须被物化替换");
        assertFalse(text.contains("@duo.ds.configOut@"), "configOut 占位符必须被物化替换");

        // T-M9-1 判据：物化后过 ScenarioValidator 全规则（1–8 与外部输入档 9–11）
        Scenario scenario = ScenarioLoader.load(yaml);
        ScenarioEngine engine = ScenarioEngine.validated(scenario,
                io.duo.sim.kernel.core.ContractRegistry.loadFromServiceLoader());
        assertNotNull(engine, "validated 通过即返回引擎（校验失败会抛，走不到这里）");

        // 钉拓扑语义（守卫的本体——模板结构契约，改动须同步演练注释与验收记录）
        assertTrue(text.contains("contract: registry"), "zk 替身必须是 registry 契约");
        assertTrue(text.contains("tier: embedded"), "zk 替身必须是 embedded 档（JVM 内真 ZK）");
        assertTrue(text.contains("tier: real"), "ds 必须是 real 档（真实第三方系统）");
        assertTrue(text.contains("sut: true"), "ds 必须标记 sut: true（SUT 非注入目标）");
        assertTrue(text.contains("mode: external"), "ds 必须 external 代起（不可改码进程）");
        assertTrue(text.contains("configOut:"), "ds 必须有端点告知文件（内核写 ZK 真实端点）");
        assertTrue(text.contains("type: http"), "就绪探针必须是 http（DS UI/API 路径）");
        assertTrue(text.contains("port: 12345"), "探针端口必须是 DS 固定 API/UI 端口");
        assertTrue(text.contains("wiring:"), "拓扑必须声明 wiring（registry → zk 依赖边）");
        assertTrue(text.contains("eventSequence: [sim.fault-injected, "
                        + "sim.registry-flap-started, sim.registry-flap-cleared]"),
                "断言必须钉 flap 先因后果序列（通道①）");
    }
}
