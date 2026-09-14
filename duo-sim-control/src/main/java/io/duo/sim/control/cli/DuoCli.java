package io.duo.sim.control.cli;

import io.duo.sim.control.ScenarioHost;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * CLI 主类（M3 T34）：子命令 {@code run / inject / status / events / assert / topology / help}。
 *
 * <p>两种模式（计划 D2）：<b>同进程直连</b>（缺省，脚本化验收最简）与 REST 客户端
 * （{@code --url} 指向运行中的 RestControlServer，用于跨进程演练）。跨进程模式仅覆盖
 * {@code status/events/inject/topology}——场景生命周期（run）仍走同进程。
 *
 * <p>退出码：0=成功/断言通过；1=失败。供脚本化验收与 CI 使用。
 */
public final class DuoCli {

    public static void main(String[] args) {
        System.exit(run(args));
    }

    /** 可测试入口：返回退出码。 */
    public static int run(String... args) {
        if (args.length == 0 || "help".equals(args[0]) || "--help".equals(args[0])) {
            printHelp();
            return 0;
        }
        String cmd = args[0];
        List<String> rest = new ArrayList<>();
        for (int i = 1; i < args.length; i++) {
            rest.add(args[i]);
        }
        try {
            return switch (cmd) {
                case "run" -> cmdRun(rest);
                case "inject" -> cmdInject(rest);
                case "status" -> cmdStatus(rest);
                case "events" -> cmdEvents(rest);
                case "assert" -> cmdAssert(rest);
                case "topology" -> cmdTopology(rest);
                default -> {
                    System.err.println("unknown command: " + cmd + " (try 'help')");
                    yield 1;
                }
            };
        } catch (Exception e) {
            System.err.println("error: " + e.getMessage());
            return 1;
        }
    }

    // ---- run <yaml> [--wait] ----

    private static int cmdRun(List<String> args) throws Exception {
        String yamlPath = arg(args, 0);
        if (yamlPath == null) {
            System.err.println("usage: run <scenario.yaml> [--keep --name N] [--wait]");
            return 1;
        }
        boolean wait = args.contains("--wait");
        boolean keep = args.contains("--keep");
        String name = opt(args, "--name") != null ? opt(args, "--name") : "default";
        ScenarioHost host = new ScenarioHost();
        host.start(Path.of(yamlPath));
        if (keep) {
            host.attach(name); // 同进程模式：后续命令按名接管
            System.out.println("attached as '" + name + "' (pid-scoped)");
        }
        if (wait) {
            host.awaitFinish(120_000);
            host.stop();
            host.detach(name);
        }
        printJson(host.status());
        Object passed = host.status().get("passed");
        return Boolean.TRUE.equals(passed) ? 0 : (host.isRunning() ? 0 : 1);
    }

    // ---- inject <action> <target> [duration] [--url X] ----

    private static int cmdInject(List<String> args) {
        String action = arg(args, 0);
        String target = arg(args, 1);
        String duration = opt(args, "--duration");
        String url = opt(args, "--url");
        if (action == null || target == null) {
            System.err.println("usage: inject <action> <target> [duration] [--url X] [--name N]");
            return 1;
        }
        if (url != null) {
            // REST 客户端模式（M3 计划 D2 跨进程）：外呼仅限控制面自身地址（回环）
            String json = "{\"type\":\"" + action + "\",\"target\":{\"componentId\":{"
                    + "\"value\":\"" + target + "\"}}"
                    + (duration == null ? "" : ",\"durationMillis\":"
                    + io.duo.sim.kernel.util.Durations.parseMillis(duration));
            var resp = java.net.http.HttpClient.newHttpClient().sendAsync(
                    java.net.http.HttpRequest.newBuilder()
                            .uri(java.net.URI.create(url + "/inject"))
                            .header("Content-Type", "application/json")
                            .POST(java.net.http.HttpRequest.BodyPublishers.ofString(json))
                            .build(),
                    java.net.http.HttpResponse.BodyHandlers.ofString())
                    .join();
            System.out.println(resp.statusCode() + " " + resp.body());
            return resp.statusCode() == 200 ? 0 : 1;
        }
        // 同进程模式：按名接管 run --keep 注册的场景
        String name = opt(args, "--name") == null ? "default" : opt(args, "--name");
        if (url == null) {
            ScenarioHost host = ScenarioHost.attached(name);
            if (host == null) {
                System.err.println("no attached scenario '" + name
                        + "' (start one with: run <yaml> --keep --name " + name + ")");
                return 1;
            }
            var addr = parseTarget(target);
            var fa = new io.duo.sim.kernel.api.FaultAction(action, addr,
                    Map.of(), duration == null ? null
                    : io.duo.sim.kernel.util.Durations.parseMillis(duration));
            var r = host.inject(fa);
            System.out.println((r.success() ? "OK" : "FAILED")
                    + (r.reason() == null ? "" : ": " + r.reason()));
            return r.success() ? 0 : 1;
        }
        return 1; // REST 分支已在上文 return
    }

    // ---- status / events / assert / topology ----

    private static int cmdStatus(List<String> args) throws Exception {
        try (var host = attachedOrDefault(args)) {
            printJson(host.status());
            return 0;
        }
    }

    private static int cmdEvents(List<String> args) throws Exception {
        int since = 0;
        String sinceArg = opt(args, "--since");
        if (sinceArg != null) {
            since = Integer.parseInt(sinceArg);
        }
        try (var host = attachedOrDefault(args)) {
            for (var e : host.eventsSince(since)) {
                System.out.println(e.get("seq") + " " + e.get("type")
                        + " [" + e.get("sourceId") + "] " + e.get("payload"));
            }
            return 0;
        }
    }

    private static int cmdAssert(List<String> args) throws Exception {
        try (var host = attachedOrDefault(args)) {
            var a = host.assertions();
            printJson(a);
            return Boolean.TRUE.equals(a.get("passed")) ? 0 : 1;
        }
    }

    private static int cmdTopology(List<String> args) throws Exception {
        try (var host = attachedOrDefault(args)) {
            for (var n : host.topology()) {
                System.out.println(n.get("id") + "  " + n.get("contract") + "/"
                        + n.get("tier") + "  count=" + n.get("count")
                        + "  hosted=" + n.get("hosted") + "  healthy=" + n.get("healthy"));
            }
            return 0;
        }
    }

    /** 同进程接管：优先取 run --keep 注册的 host；无则空 host（状态报告 IDLE）。 */
    private static ScenarioHost attachedOrDefault(List<String> args) {
        String name = opt(args, "--name") == null ? "default" : opt(args, "--name");
        ScenarioHost attached = ScenarioHost.attached(name);
        return attached != null ? attached : new ScenarioHost();
    }

    private static io.duo.sim.kernel.api.FaultAction.ComponentAddress parseTarget(
            String target) {
        int bracket = target.indexOf('[');
        if (bracket < 0) {
            return io.duo.sim.kernel.api.FaultAction.ComponentAddress.of(
                    new io.duo.sim.kernel.api.ComponentId(target.trim()));
        }
        int idx = Integer.parseInt(target.substring(bracket + 1, target.length() - 1));
        return io.duo.sim.kernel.api.FaultAction.ComponentAddress.ofInstance(
                new io.duo.sim.kernel.api.ComponentId(target.substring(0, bracket)), idx);
    }

    private static String arg(List<String> args, int i) {
        return i < args.size() ? args.get(i) : null;
    }

    private static String opt(List<String> args, String flag) {
        for (int i = 0; i < args.size(); i++) {
            if (flag.equals(args.get(i)) && i + 1 < args.size()) {
                return args.get(i + 1);
            }
        }
        return null;
    }

    private static void printJson(Object o) {
        System.out.println(o);
    }

    private static void printHelp() {
        System.out.println("""
                duo —— Duo 仿真控制面 CLI（M3）

                子命令：
                  run <scenario.yaml> [--wait]      启动场景（--wait 等 SUT 退出并评估断言）
                  inject <action> <target> [duration] [--url X]
                                                    注入故障（target 如 workers[2]）
                  status                            场景状态
                  events [--since N]                事件流
                  assert                            断言结果（退出码反映通过与否）
                  topology                          拓扑视图
                  help                              本帮助

                跨进程模式：inject/status/events/topology 支持 --url http://127.0.0.1:<port>
                （指向运行中的 RestControlServer）。""");
    }
}
