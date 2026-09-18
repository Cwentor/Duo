/*
 * 零依赖「假第三方 SUT」——M6 端到端验收材料（examples 模块；内核单测有同源的精简版
 * duo-sim-kernel/src/test/resources/external/FakeTcpSut.java）。
 *
 * 它**刻意不依赖 Duo 的任何工件**：G2「不可改码的第三方 SUT 接不进来」这个缺口，
 * 只有用「不带 Duo classpath 的独立进程」才能证明已经补上（决策 D1：暂不绑定真实产品）。
 *
 * 启动方式（JDK 单文件源码模式：无需编译、无需 classpath、无需 Duo 依赖）：
 *   java FakeThirdPartySut.java <port> <mode> [reportPath]
 *
 * mode：
 *   hold        绑定端口 → 常驻接受连接（验收「场景结束不杀进程」）
 *   exit0       ready 后正常退出（exit 0）→ 内核发 sut.exited 并终止场景
 *   crash       ready 后异常退出（非 0）→ 内核发 sut.crashed 并终止场景
 *   exit-now    ready 前正常退出 → 内核按启动失败报真实根因
 *   crash-now   ready 前异常退出 → 同上
 *   never-ready 进程存活但端口不监听 → 探针必然超时
 *
 * 环境变量 duo.config = 内核生成的端点配置文件路径（设计 §7.3「内核 → SUT 端点告知」主途径）。
 * reportPath（可选）＝把「从配置文件学到的端点」写到此文件，供测试断言「告知确实到达 SUT」。
 */
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public class FakeThirdPartySut {

    public static void main(String[] args) throws Exception {
        int port = Integer.parseInt(args[0]);
        String mode = args.length > 1 ? args[1] : "hold";
        Path report = args.length > 2 ? Path.of(args[2]) : null;

        List<String> learned = reportLearnedConfig();
        if (report != null) {
            Files.write(report, learned, StandardCharsets.UTF_8);
        }

        if ("exit-now".equals(mode)) {
            System.out.println("exiting before ready");
            return;
        }
        if ("crash-now".equals(mode)) {
            throw new IllegalStateException("fake crash before ready");
        }
        if ("never-ready".equals(mode)) {
            Thread.sleep(120_000);
            return;
        }

        try (ServerSocket server = new ServerSocket()) {
            server.setReuseAddress(true);
            server.bind(new InetSocketAddress("127.0.0.1", port));
            // 端点宣告（§7.3 stdout 兜底途径）：行首即约定前缀
            System.out.println("duo.endpoint.scheduler=127.0.0.1:" + port);
            System.out.flush();

            switch (mode) {
                case "exit0" -> Thread.sleep(1_500);
                case "crash" -> {
                    Thread.sleep(1_500);
                    throw new IllegalStateException("fake crash after ready");
                }
                default -> {
                    while (true) {
                        try (Socket ignored = server.accept()) {
                            // 接受即关：只用于让 ready 探针与用户连接有应答
                        }
                    }
                }
            }
        }
    }

    /**
     * 读内核生成的端点配置文件，证明「内核 → SUT 端点告知」主途径到达。
     * 注意：**不回显** {@code duo.endpoint.*} 原文——回显会被内核按 SUT 自身端点宣告解析。
     */
    private static List<String> reportLearnedConfig() throws IOException {
        List<String> out = new ArrayList<>();
        String path = System.getenv("duo.config");
        if (path == null) {
            out.add("config: none");
            return out;
        }
        Path file = Path.of(path);
        if (!Files.exists(file)) {
            out.add("config: missing " + path);
            return out;
        }
        for (String line : Files.readAllLines(file)) {
            int eq = line.indexOf('=');
            if (line.startsWith("duo.endpoint.") && eq > 0) {
                out.add("learned endpoint: " + line.substring("duo.endpoint.".length(), eq)
                        + " -> " + line.substring(eq + 1));
            } else if (!line.isBlank() && !line.startsWith("#")) {
                out.add("learned config: " + line);
            }
        }
        for (String line : out) {
            System.out.println(line);
        }
        return out;
    }
}
