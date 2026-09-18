/*
 * 零依赖「假第三方 SUT」——M6 验收材料（内核单测与 examples 端到端共用）。
 *
 * 它**刻意不依赖 Duo 的任何工件**：一个真正的第三方进程接不进来的历史缺口（G2），
 * 只有用「不带 Duo classpath 的独立进程」才能证明已经补上。
 *
 * 启动方式（JDK 单文件源码模式，无需编译、无需 classpath）：
 *   java FakeTcpSut.java <port> <mode>
 *
 * mode：
 *   hold        绑定端口 → 常驻接受连接（场景结束由内核验证「不杀进程」）
 *   exit0       ready 后正常退出（exit 0）→ 内核发 sut.exited
 *   crash       ready 后异常退出（非 0）→ 内核发 sut.crashed
 *   exit-now    ready 前正常退出 → 内核按启动失败报真实根因
 *   crash-now   ready 前异常退出 → 同上
 *   never-ready 进程存活但端口不监听 → 探针必然超时
 *
 * 环境变量 duo.config = 内核生成的端点配置文件路径（设计 §7.3 主途径）。
 */
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;

public class FakeTcpSut {

    public static void main(String[] args) throws Exception {
        int port = Integer.parseInt(args[0]);
        String mode = args.length > 1 ? args[1] : "hold";

        reportLearnedConfig();

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
    private static void reportLearnedConfig() throws IOException {
        String path = System.getenv("duo.config");
        if (path == null) {
            System.out.println("config: none");
            return;
        }
        Path file = Path.of(path);
        if (!Files.exists(file)) {
            System.out.println("config: missing " + path);
            return;
        }
        for (String line : Files.readAllLines(file)) {
            int eq = line.indexOf('=');
            if (line.startsWith("duo.endpoint.") && eq > 0) {
                System.out.println("learned endpoint: "
                        + line.substring("duo.endpoint.".length(), eq)
                        + " -> " + line.substring(eq + 1));
            } else if (!line.isBlank() && !line.startsWith("#")) {
                System.out.println("learned config: " + line);
            }
        }
    }
}
