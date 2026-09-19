package io.duo.sim.examples.acceptance;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * M8 交付物 3（ROADMAP）：**一次故障注入的完整因果链可被单命令导出**。
 *
 * <p>设计 §11 的三条观测通道分工不同：事件流录制是**机器**的事实源（断言与 /metrics 都读它），
 * 日志是**人**的因果链。本用例把「日志真的存在、且故障确实被记录」变成可失败断言——
 * 而不是"配置了一个 logback.xml 就算完成"（那正是 G6 登记的做法）。
 *
 * <p>断言口径：
 * <ol>
 *   <li>生产档 `logback.xml` 与测试档 `logback-test.xml` 都在类路径上且被 logback 选中
 *       （测试档优先，因此全量回归不会被日志淹没）；</li>
 *   <li>手工执行一次注入时，`io.duo.sim.fault` 记录器**实际收到一条事件**且消息里带目标与动作
 *       ——这条把"故障注入有日志"钉死，避免日后有人删掉日志点而无人发现。</li>
 * </ol>
 */
class FaultCausalChainLoggingTest {

    @Test
    void testProfileKeepsNoiseDownButFaultChainVisible() throws Exception {
        var cl = getClass().getClassLoader();
        assertTrue(cl.getResource("logback-test.xml") != null,
                "测试档 logback-test.xml 必须在 test classpath 上（否则全量回归刷屏）");
        assertTrue(cl.getResource("logback.xml") != null,
                "生产档 logback.xml 必须在 main classpath 上（CLI/REST 默认可读日志）");
        String ctxName = org.slf4j.LoggerFactory.getILoggerFactory().getClass().getName();
        assertTrue(ctxName.contains("logback"), () -> "SLF4J 后端应是 logback，实际: " + ctxName);

        // 测试档里两个显式 logger 级别必须被应用——这是「测试档真的生效」的可失败证据
        // （不断言根级别：surefire 自身会把 root 调成 DEBUG，那是工具行为不是我们的配置）。
        var fault = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory
                .getLogger("io.duo.sim.fault");
        assertEquals(ch.qos.logback.classic.Level.INFO, fault.getLevel(),
                "io.duo.sim.fault 必须保持 INFO：降噪不能把故障因果链一起静默（§12）");
        var zk = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory
                .getLogger("org.apache.zookeeper");
        assertEquals(ch.qos.logback.classic.Level.ERROR, zk.getLevel(),
                "测试档应把 ZK 降到 ERROR（ZK INFO 会淹没 Duo 的因果链）");
        assertFalse(fault.isDebugEnabled() && fault.getLevel() == null,
                "fault logger 不得被清空级别");

        // 配置文件内容自证：生产档必须显式列出第三方降噪与 JSON 开关说明
        String main = Files.readString(
                Path.of(cl.getResource("logback.xml").toURI()), StandardCharsets.UTF_8);
        assertTrue(main.contains("duo.log.json"),
                "生产档应支持 -Dduo.log.json=INFO 的结构化输出开关");
        for (String noisy : new String[]{"org.apache.zookeeper", "io.netty", "org.testcontainers"}) {
            assertTrue(main.contains(noisy),
                    () -> "生产档应显式给 " + noisy + " 降噪，否则 INFO 视野被第三方淹没");
        }
        assertFalse(main.contains("root level=\"DEBUG\""),
                "生产档默认不得是 DEBUG（日志量与敏感信息都不可控）");
        // 回归护栏：logback 1.5.16 的 <if>/<else> 在本项目配置形态下会让 LoggerContext
        // 初始化失败（EmptyStackException），配置不得再引入条件块。
        assertFalse(main.contains("<if condition=") || main.contains("<else>"),
                "logback.xml 不得使用 <if>/<else>（1.5.16 下抛 EmptyStackException，日志后端直接不可用）");
        String test = Files.readString(
                Path.of(cl.getResource("logback-test.xml").toURI()), StandardCharsets.UTF_8);
        assertFalse(test.contains("<if condition=") || test.contains("<else>"),
                "logback-test.xml 不得使用 <if>/<else>（同上）");
    }

    @Test
    void injectionIsRecordedOnTheFaultLogger() throws Exception {
        ch.qos.logback.classic.Logger fault =
                (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory
                        .getLogger("io.duo.sim.fault");
        var appender = new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
        appender.start();
        fault.addAppender(appender);
        try {
            io.duo.sim.control.FaultLog.injected("workers", 2, "crash");
            io.duo.sim.control.FaultLog.failed("workers", "freeze", "action not declared");
            assertEquals(2, appender.list.size(),
                    "注入成功/失败都应各留一条日志（不静默）");
            String ok = appender.list.get(0).getFormattedMessage();
            assertTrue(ok.contains("workers") && ok.contains("crash"),
                    () -> "成功日志须含目标与动作: " + ok);
            String bad = appender.list.get(1).getFormattedMessage();
            assertTrue(bad.contains("freeze") && bad.contains("not declared"),
                    () -> "失败日志须含动作与原因: " + bad);
            assertEquals(ch.qos.logback.classic.Level.WARN, appender.list.get(1).getLevel(),
                    "失败必须是 WARN（成功是 INFO）——级别本身就是可筛的语义");
        } finally {
            fault.detachAppender(appender);
        }
    }
}
