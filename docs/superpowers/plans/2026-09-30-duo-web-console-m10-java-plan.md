# Duo Web 控制台 M10 · 计划一：Java 控制面扩展 实施计划

- 日期：2026-09-30（**实施完成 2026-10-01**）
- 状态：**已实施完成（2026-10-01）**——T1–T10 全部落地，终审修复轮 `843f5c7`
  （I-1 双重编码 id 预检 400 / M-1 升 Important 补 `sanitizeReason` / M-6 升 Important 负 `since` 400 /
  M-3 fork 缺省后缀 / M-4 `serve` 打印库路径）；末次验证 Java 整 reactor
  **423 测 / 0 失败 / 0 错误 / 12 skip BUILD SUCCESS**、`-Dquality` 8 模块零告警。
- 台账：`.superpowers/ledger-m10-java/progress.md`（gitignore，不入库——本文状态行即入库留痕）
- 备注：下文步骤保留 `- [ ]` 原样作为**步骤模板**（与 m0–m9 各计划同例：勾选状态不作完成台账，
  完成口径以本状态行 + 提交历史 + 上述实测数字为准）。

> 本计划交 dev-executing-plans 逐任务执行；步骤用 `- [ ]` 勾选跟踪。
> 计划二（console-ui 前端工程）在本计划全部落地后另行编写——两份计划各自独立产出可运行、可测试的软件。

**Goal:** 为 Web 控制台补齐 Java 侧 API 面：场景库 CRUD/fork/校验、能力元数据、手动清除注入、诊断链一等端点、SPA 静态托管、`/events` 游标扩展、CLI `serve` 无场景启动。

**Architecture:** 全部改动收敛在 `duo-sim-control` 模块（含 resources）：`ScenarioHost` 补 `clear`/`droppedEvents` 两条薄通路；`RestControlServer` 以既有 `guard`/`respond` 模式新增 `/api/**` 端点族与静态托管；新类 `ScenarioLibrary` 承载模板+用户库；CLI `cmdServe` 场景参数改为可选。**零内核/场景模块改动**（只用已公开 API）。

**Tech Stack:** Java 21 · JDK `com.sun.net.httpserver`（不引 Web 框架）· Jackson（已在 classpath）· ServiceLoader（能力枚举）· JUnit5（既有分层：契约测试落 `duo-sim-control/src/test`）

**Spec:** [docs/superpowers/specs/2026-09-30-duo-web-console-design.md](../specs/2026-09-30-duo-web-console-design.md)

## 规格修订记录（本计划与规格的唯一偏差，已回写规格文件）

- **§6.2 `GET /topology`**：实施核对发现响应**已含**每节点 `id/contract/tier/sut/count/hosted/healthy/endpoints`（`ScenarioHost.topology()`，`duo-sim-control/src/main/java/io/duo/sim/control/ScenarioHost.java:491`）；实例粒度健康数需新增内核 API，违反零内核改动。**决定：v1 对 `/topology` 零改动**，实例级状态灯由前端从事件流推导。规格 §6.2 与 §4 已同步修订。

## Global Constraints（逐字来自规格，所有任务默认隐含）

- 零内核/场景模块改动：`duo-sim-kernel`、`duo-sim-scenario`、`duo-sim-components`、`duo-sim-examples`、`duo-sim-junit`、`duo-sim-protocol`、`duo-sim-embedded` 的 `src/main` 一律不动（examples 的测试仅 Task 9 允许追加）。
- 零新 Java 运行期依赖；`-Dquality` 依赖门禁必须保持 9 模块零告警（新 import 只能来自已在 pom 声明的工件）。
- 所有非 `/health`、非静态资源端点必须过 `guard(ex, false)`（Bearer + 回环 Host/Origin）；静态资源与 `/health` 同档免令牌但同样过 `guard(ex, true)`。
- 请求体上限 1 MiB 沿用 `readBody`/`rejectTooLarge`；错误响应一律 `sanitizeReason` 掩码，令牌与临时路径不得出现在任何响应。
- 端点只监听 `127.0.0.1`（`start(int)` 现状，不动）。
- §12「不静默」：一切失败必须带机器可读原因，禁止静默吞掉。
- 每任务收尾跑**整 reactor** 回归：`.\mvnw.cmd -o -B test "-Dduo.docker.enabled=false"`（此前教训：不要只跑单模块）。
- 提交信息风格沿用仓库惯例：`type(scope): 中文摘要——关键实测数字`。

## Review Focus（规格隐含、无任务测试会踩到的高危输入；各挂一条测试到所属任务）

1. **库 id 路径遍历**：`../`、绝对路径、空串、含空格/中文/路径分隔符的 id → 一律 400，绝不落盘到 `--library-dir` 之外（Task 3 单测 + Task 4 契约测试）。
2. **模板写/删保护**：对内置模板 id 做 `PUT`/`DELETE` → 405，模板目录只读（Task 3 单测 + Task 4 契约测试）。
3. **外部输入档收窄延伸到库**：`PUT` 一份含 `launch.command` / 越白名单 config 键 / 绝对路径 config 值的 YAML → 400 且 `errors` 数组逐条给出原因（Task 4）。
4. **`/events` 游标一致性**：事件连发时 `next` 恰好等于 `since + events.size()`，二次拉取 `since=next` 不重不漏（Task 2）。
5. **静态资源穿越**：`/console/../` 形态与未知扩展名 → 404；资源解析只走 classpath 字符串拼接前先做白名单校验，绝不触碰 `java.io.File` 路径语义（Task 8）。

---

### Task 1: `ScenarioHost` 补 `clear()` 与 `droppedEvents()` 两条薄通路

**Files:**
- Modify: `duo-sim-control/src/main/java/io/duo/sim/control/ScenarioHost.java`（在 `inject` 方法之后追加两个方法，约 :459 附近）
- Test: Create `duo-sim-control/src/test/java/io/duo/sim/control/ScenarioHostFaultControlTest.java`

**Interfaces:**
- Consumes: `ScenarioEngine.droppedEvents()`（`duo-sim-scenario`，已存在，:137）、`ScenarioEngine.runtime()`（:667）→ `ScenarioRuntime.clear(FaultAction)`（`duo-sim-kernel`，:108）、`ScenarioRuntime.InjectionResult(boolean success, String reason)`。
- Produces: `public ScenarioRuntime.InjectionResult clear(FaultAction action)`、`public long droppedEvents()`（Task 2、Task 6 依赖这两个签名）。

- [ ] **Step 1: 写失败测试**

```java
package io.duo.sim.control;

import io.duo.sim.kernel.api.ComponentId;
import io.duo.sim.kernel.api.FaultAction;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** M10 计划一 Task 1：手动清除注入与事件丢弃计数的控制面通路（薄委托，零内核改动）。 */
class ScenarioHostFaultControlTest {

    private static FaultAction freezeWorkers1() {
        return new FaultAction("freeze",
                FaultAction.ComponentAddress.ofInstance(new ComponentId("workers"), 1),
                Map.of(), null);
    }

    @Test
    void clearFailsLoudlyWhenNoScenarioRunning() {
        try (ScenarioHost host = new ScenarioHost()) {
            var r = host.clear(freezeWorkers1());
            assertFalse(r.success());
            assertEquals("scenario not running", r.reason());
        }
    }

    @Test
    void injectedFreezeCanBeClearedManually() throws Exception {
        String yaml = """
                name: host-clear-smoke
                topology:
                  - id: zk
                    contract: registry
                    tier: virtual
                  - id: master
                    contract: scheduler
                    tier: virtual
                    sut: true
                    launch: { mode: in-process, main: io.duo.sim.control.testfixture.ControlFixtureSut }
                    config: { dag.tasks: "a,b" }
                    exposes: [{ contract: scheduler, port: 0 }]
                    wiring:
                      registry: { node: zk, contract: registry }
                  - id: workers
                    contract: worker
                    tier: virtual
                    count: 2
                    capacity: { slots: 1 }
                    wiring:
                      registry: { node: zk, contract: registry }
                behaviors:
                  profiles:
                    default: { duration: 3s, jitter: 0.0, successRate: 1.0 }
                  bindings:
                    - node: workers
                      profile: default
                timeline: []
                assertions:
                  - noTaskLost: { requireAllSuccess: true }
                """;
        Path yamlFile = Files.createTempFile("host-clear-smoke-", ".yaml");
        Files.writeString(yamlFile, yaml, StandardCharsets.UTF_8);
        try (ScenarioHost host = new ScenarioHost()) {
            host.start(yamlFile);
            long deadline = System.currentTimeMillis() + 10_000;
            while (!host.isRunning() && System.currentTimeMillis() < deadline) {
                Thread.sleep(100);
            }
            assertTrue(host.isRunning(), "scenario should be running within 10s");

            // 组件注册可能晚于 start 返回：对 "unknown target" 类失败做有界重试
            var injected = new ScenarioRuntime.InjectionResult(false, "not attempted");
            for (int i = 0; i < 20 && !injected.success(); i++) {
                injected = host.inject(freezeWorkers1());
                if (!injected.success()) {
                    Thread.sleep(100);
                }
            }
            assertTrue(injected.success(), "inject freeze should succeed: " + injected.reason());

            var cleared = host.clear(freezeWorkers1());
            assertTrue(cleared.success(), "clear should succeed: " + cleared.reason());
            assertEquals(0L, host.droppedEvents(), "no drops under normal operation");
        } finally {
            Files.deleteIfExists(yamlFile);
        }
    }
}
```

（`ScenarioRuntime` 需要 import `io.duo.sim.kernel.core.ScenarioRuntime`。）

- [ ] **Step 2: 跑测试确认失败**

Run: `.\mvnw.cmd -o -B -pl duo-sim-control -am test "-Dtest=ScenarioHostFaultControlTest" "-Dduo.docker.enabled=false"`
Expected: **编译失败**，`cannot find symbol: method clear(FaultAction)` 与 `method droppedEvents()`。

- [ ] **Step 3: 最小实现**（在 `ScenarioHost.java` 的 `inject` 方法之后追加）

```java
/**
 * 手动清除一次热注入（M10 W-API-8）。时间线里带 duration 的动作由 {@code TimelineScheduler}
 * 到期自动 clear；本方法是 Web 控制台「撤销注入」的控制面通路：薄委托
 * {@link ScenarioRuntime#clear(FaultAction)}（内核已有，零内核改动）。
 *
 * <p>失败分支记 {@link FaultLog}（§12 不静默）；成功分支不重复记日志——内核在 clear 路径上
 * 会发出 {@code sim.fault-cleared} 事实事件，因果链由事件流承载。
 */
public ScenarioRuntime.InjectionResult clear(FaultAction action) {
    if (engine == null || state != State.RUNNING) {
        return new ScenarioRuntime.InjectionResult(false, "scenario not running");
    }
    return engine.runtime().clear(action);
}

/** 事件缓冲丢弃计数（透传 {@link ScenarioEngine#droppedEvents()}，供 /events 暴露给控制台）。 */
public long droppedEvents() {
    ScenarioEngine eng;
    synchronized (this) {
        eng = engine;
    }
    return eng == null ? 0L : eng.droppedEvents();
}
```

（`engine`/`state` 是本类既有私有字段，`FaultLog`/`ScenarioRuntime`/`ScenarioEngine` 本类已 import。）

- [ ] **Step 4: 跑测试确认通过**

Run: `.\mvnw.cmd -o -B -pl duo-sim-control -am test "-Dtest=ScenarioHostFaultControlTest" "-Dduo.docker.enabled=false"`
Expected: PASS（2 tests）。

- [ ] **Step 5: 整 reactor 回归 + Commit**

```bash
.\mvnw.cmd -o -B test "-Dduo.docker.enabled=false"    # 预期：397+2 全绿，skip 仍为 12
git add duo-sim-control/src/main/java/io/duo/sim/control/ScenarioHost.java \
        duo-sim-control/src/test/java/io/duo/sim/control/ScenarioHostFaultControlTest.java
git commit -m "feat(control): ScenarioHost 补手动 clear 与 droppedEvents 薄通路——M10 计划一 Task 1，+2 测全绿"
```

---

### Task 2: `/events` 响应扩展 `next` 与 `dropped`

**Files:**
- Modify: `duo-sim-control/src/main/java/io/duo/sim/control/rest/RestControlServer.java`（`/events` context，:279 的 `respond` 行）
- Test: Modify `duo-sim-control/src/test/java/io/duo/sim/control/rest/RestControlServerTest.java`（追加用例）

**Interfaces:**
- Consumes: Task 1 的 `host.droppedEvents()`；既有 `host.eventsSince(int)`。
- Produces: 响应体 `{"since": N, "next": M, "dropped": D, "events": [...]}`（`next = since + events.size()`）——计划二前端轮询依赖该契约。

- [ ] **Step 1: 写失败测试**（追加到 `RestControlServerTest`，自包含，不依赖本类既有 helper 字段）

```java
@Test
void eventsResponseCarriesNextCursorAndDropCounter() throws Exception {
    ScenarioHost host = new ScenarioHost();
    RestControlServer server = new RestControlServer(host, RestControlServer.Auth.INSECURE, null);
    try {
        int port = server.start(0);
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        // 启动 FAST_SCENARIO（本类既有常量）并等运行
        var startResp = client.send(HttpRequest.newBuilder(URI.create(
                        "http://127.0.0.1:" + port + "/scenario"))
                        .POST(HttpRequest.BodyPublishers.ofString(FAST_SCENARIO,
                                StandardCharsets.UTF_8)).build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, startResp.statusCode(), startResp.body());
        long deadline = System.currentTimeMillis() + 10_000;
        boolean running = false;
        while (System.currentTimeMillis() < deadline && !running) {
            var st = client.send(HttpRequest.newBuilder(URI.create(
                    "http://127.0.0.1:" + port + "/scenario/status")).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            running = st.statusCode() == 200;
            if (!running) {
                Thread.sleep(100);
            }
        }
        assertTrue(running, "scenario should be running within 10s");

        // 组件注册会产生事件：等到至少 1 条，然后校验游标契约
        deadline = System.currentTimeMillis() + 10_000;
        int next = -1;
        while (System.currentTimeMillis() < deadline) {
            var resp = client.send(HttpRequest.newBuilder(URI.create(
                    "http://127.0.0.1:" + port + "/events?since=0")).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(200, resp.statusCode());
            @SuppressWarnings("unchecked")
            var body = (java.util.Map<String, Object>) new com.fasterxml.jackson.databind.ObjectMapper()
                    .readValue(resp.body(), java.util.Map.class);
            @SuppressWarnings("unchecked")
            var events = (java.util.List<Object>) body.get("events");
            if (!events.isEmpty()) {
                assertEquals(events.size(), ((Number) body.get("next")).intValue(),
                        "next must equal since + events.size()");
                assertTrue(body.containsKey("dropped"), "dropped counter must be present");
                next = ((Number) body.get("next")).intValue();
                break;
            }
            Thread.sleep(100);
        }
        assertTrue(next > 0, "should observe at least one event within 10s");
        // 二次拉取不重不漏：since=next 应为空
        var resp2 = client.send(HttpRequest.newBuilder(URI.create(
                "http://127.0.0.1:" + port + "/events?since=" + next)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        @SuppressWarnings("unchecked")
        var body2 = (java.util.Map<String, Object>) new com.fasterxml.jackson.databind.ObjectMapper()
                .readValue(resp2.body(), java.util.Map.class);
        assertEquals(0, ((java.util.List<?>) body2.get("events")).size(),
                "since=next must yield no repeated events");
    } finally {
        server.close();
        host.close();
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `.\mvnw.cmd -o -B -pl duo-sim-control -am test "-Dtest=RestControlServerTest#eventsResponseCarriesNextCursorAndDropCounter" "-Dduo.docker.enabled=false"`
Expected: FAIL——`next must equal since + events.size(): expected 1 but was null`（旧响应无 `next`）。

- [ ] **Step 3: 最小实现**（`/events` context 的 `respond(ex, 200, Map.of("since", since, "events", events))` 改为）

```java
var body = new java.util.LinkedHashMap<String, Object>();
body.put("since", since);
body.put("next", since + events.size());   // M10 W-API：下一游标（eventsSince 语义 = 下标 >= since）
body.put("dropped", host.droppedEvents()); // M10 W-API：丢弃计数可见（§12 不静默延伸到 UI）
body.put("events", events);
respond(ex, 200, body);
```

- [ ] **Step 4: 跑测试确认通过**（同 Step 2 命令）Expected: PASS。
- [ ] **Step 5: 整 reactor 回归 + Commit**

```bash
.\mvnw.cmd -o -B test "-Dduo.docker.enabled=false"
git add -A duo-sim-control
git commit -m "feat(rest): /events 响应加 next 游标与 dropped 丢弃计数——M10 计划一 Task 2，向后兼容"
```

---

### Task 3: `ScenarioLibrary`——模板（只读）+ 用户库（CRUD + fork + id 白名单）

**Files:**
- Create: `duo-sim-control/src/main/java/io/duo/sim/control/library/ScenarioLibrary.java`
- Create: `duo-sim-control/src/main/resources/console-templates/index.txt`（内容两行：`worker-crash-failover`、`worker-freeze-demo`）
- Create: `duo-sim-control/src/main/resources/console-templates/worker-crash-failover.yaml`（内容见 Step 3）
- Create: `duo-sim-control/src/main/resources/console-templates/worker-freeze-demo.yaml`（内容见 Step 3）
- Test: Create `duo-sim-control/src/test/java/io/duo/sim/control/library/ScenarioLibraryTest.java`

**Interfaces:**
- Consumes: `ScenarioLoader.load(InputStream)`（`duo-sim-scenario` :30）、`ScenarioValidator(ContractRegistry, InputTrust)` 公开构造器 + `validate(Scenario)→Report(errors, warnings)` + `Report.ok()/errors()/warnings()`（:94/:98/:103/:30）、`ContractRegistry.loadFromServiceLoader()`（kernel :36）。
- Produces（Task 4 的 REST 层全依赖）:
  - `ScenarioLibrary(Path userDir)`——构造即 `Files.createDirectories(userDir)`
  - `record Entry(String id, String name, boolean template, boolean valid, int nodeCount, long lastModifiedMillis)`
  - `record Content(String id, boolean template, String yaml)`
  - `List<Entry> list() throws IOException`
  - `Content get(String id)`——坏 id → `IllegalArgumentException`；不存在 → `NoSuchFileException`
  - `void put(String id, String yaml)`——坏 id → `IllegalArgumentException`；模板 → `IllegalStateException`；**只落盘不校验**（校验是 REST 层职责，本类保持纯存储）
  - `void delete(String id)`——坏 id → `IllegalArgumentException`；模板 → `IllegalStateException`；不存在 → `NoSuchFileException`
  - `String fork(String sourceId, String newId)`——返回新 id；源不存在 → `NoSuchFileException`；newId 坏 → `IllegalArgumentException`；newId 已存在（含与模板撞名）→ `IllegalStateException`
  - `Path userDir()`、`static boolean validId(String id)`、`List<String> validateYaml(String yaml)`（外部输入档全量校验，返回错误清单；合法 = 空表）

- [ ] **Step 1: 写失败测试**

```java
package io.duo.sim.control.library;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.NoSuchFileException;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** M10 计划一 Task 3：场景库存储层——id 白名单、模板只读、fork 语义、外部输入档校验。 */
class ScenarioLibraryTest {

    private static final String HELLO_YAML = """
            name: my-drill
            topology:
              - id: zk
                contract: registry
                tier: virtual
              - id: master
                contract: scheduler
                tier: virtual
                sut: true
                launch: { mode: in-process, main: io.duo.sim.examples.scheduler.DemoScheduler }
                config: { dag.tasks: "a" }
                exposes: [{ contract: scheduler, port: 0 }]
                wiring:
                  registry: { node: zk, contract: registry }
              - id: workers
                contract: worker
                tier: virtual
                count: 1
                capacity: { slots: 1 }
                wiring:
                  registry: { node: zk, contract: registry }
            behaviors:
              profiles:
                default: { duration: 3s, jitter: 0.0, successRate: 1.0 }
              bindings:
                - node: workers
                  profile: default
            timeline: []
            assertions:
              - noTaskLost: { requireAllSuccess: true }
            """;

    @Test
    void validIdRejectsTraversalAndFancyCharacters(@TempDir Path dir) {
        assertFalse(ScenarioLibrary.validId("../etc/passwd"));
        assertFalse(ScenarioLibrary.validId("a/b"));
        assertFalse(ScenarioLibrary.validId("a\\b"));
        assertFalse(ScenarioLibrary.validId(""));
        assertFalse(ScenarioLibrary.validId("a b"));
        assertFalse(ScenarioLibrary.validId("场景一"));
        assertTrue(ScenarioLibrary.validId("my-drill-01"));
        assertTrue(ScenarioLibrary.validId("m3_inject.demo"));
    }

    @Test
    void templatesAreReadOnlyAndListed(@TempDir Path dir) {
        var lib = new ScenarioLibrary(dir);
        var entries = assertDoesNotThrow(lib::list);
        assertTrue(entries.stream().anyMatch(e -> e.template()
                && e.id().equals("worker-crash-failover")), "template from index.txt must be listed");
        assertThrows(IllegalStateException.class,
                () -> lib.put("worker-crash-failover", HELLO_YAML), "template is read-only");
        assertThrows(IllegalStateException.class,
                () -> lib.delete("worker-crash-failover"), "template cannot be deleted");
    }

    @Test
    void putGetDeleteRoundTrip(@TempDir Path dir) throws Exception {
        var lib = new ScenarioLibrary(dir);
        lib.put("my-drill-01", HELLO_YAML);
        var c = lib.get("my-drill-01");
        assertFalse(c.template());
        assertEquals(HELLO_YAML, c.yaml());
        lib.delete("my-drill-01");
        assertThrows(NoSuchFileException.class, () -> lib.get("my-drill-01"));
    }

    @Test
    void forkCopiesIntoUserNamespace(@TempDir Path dir) throws Exception {
        var lib = new ScenarioLibrary(dir);
        String newId = lib.fork("worker-crash-failover", "my-fork");
        assertEquals("my-fork", newId);
        assertTrue(lib.get("my-fork").yaml().contains("name:"));
        assertThrows(IllegalStateException.class, () -> lib.fork("worker-crash-failover", "my-fork"),
                "fork onto existing id must fail loudly");
    }

    @Test
    void validateYamlCatchesSchemaErrorsAndExternalInputViolations(@TempDir Path dir) {
        var lib = new ScenarioLibrary(dir);
        assertTrue(lib.validateYaml(HELLO_YAML).isEmpty(), "well-formed scenario must validate");
        assertFalse(lib.validateYaml("name: [broken").isEmpty(), "parse error must be reported");
        assertFalse(lib.validateYaml("""
                name: bad
                topology:
                  - id: m
                    contract: scheduler
                    tier: real
                    sut: true
                    launch: { mode: external, command: "calc.exe" }
                timeline: []
                assertions: []
                """).isEmpty(), "external-input profile must reject launch.command");
    }
}
```

（`assertDoesNotThrow` 需要 `import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;`。）

- [ ] **Step 2: 跑测试确认失败**

Run: `.\mvnw.cmd -o -B -pl duo-sim-control -am test "-Dtest=ScenarioLibraryTest" "-Dduo.docker.enabled=false"`
Expected: **编译失败**，`cannot find symbol: class ScenarioLibrary`。

- [ ] **Step 3: 最小实现**

`ScenarioLibrary.java`：

```java
package io.duo.sim.control.library;

import io.duo.sim.kernel.core.ContractRegistry;
import io.duo.sim.scenario.ScenarioLoader;
import io.duo.sim.scenario.ScenarioValidator;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 场景库（M10 计划一 Task 3）：内置模板（classpath {@code /console-templates/}，只读）
 * + 用户库（{@code --library-dir} 目录，可写）。
 *
 * <p>安全口径（规格 §7）：id 白名单 {@code [A-Za-z0-9._-]+}——路径分隔符、{@code ..}、
 * 空串一律 {@link IllegalArgumentException}，写入严格限定在 userDir 内；模板 id 对
 * 写/删一律 {@link IllegalStateException}（REST 层映射 405）。
 *
 * <p>{@link #validateYaml} 是**外部输入档**全量校验（规则 1–8 + 9–11）：
 * {@link ScenarioValidator} 以 {@code InputTrust.EXTERNAL} 构造，与 {@code POST /scenario}
 * 同档——经 Web 上传的 YAML 不因「存成了文件」而升档（规格 §7.4）。
 */
public final class ScenarioLibrary {

    private static final String TEMPLATES_ROOT = "/console-templates";
    private static final Pattern ID = Pattern.compile("[A-Za-z0-9._-]+");

    private final Path userDir;

    public ScenarioLibrary(Path userDir) throws IOException {
        this.userDir = userDir.toAbsolutePath().normalize();
        Files.createDirectories(this.userDir);
    }

    public Path userDir() {
        return userDir;
    }

    public static boolean validId(String id) {
        return id != null && !id.isBlank() && ID.matcher(id).matches();
    }

    /** 场景条目。{@code valid=false} 表示文件存在但解析/校验不过（列表不得因此 500）。 */
    public record Entry(String id, String name, boolean template, boolean valid,
                        int nodeCount, long lastModifiedMillis) {
    }

    public record Content(String id, boolean template, String yaml) {
    }

    private void requireValidId(String id) {
        if (!validId(id)) {
            throw new IllegalArgumentException(
                    "invalid scenario id: only [A-Za-z0-9._-] are allowed");
        }
    }

    private boolean isTemplate(String id) {
        try (var in = ScenarioLibrary.class.getResourceAsStream(
                TEMPLATES_ROOT + "/index.txt")) {
            if (in == null) {
                return false;
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8).lines()
                    .map(String::trim).anyMatch(id::equals);
        } catch (IOException e) {
            return false;
        }
    }

    private String readTemplate(String id) throws IOException {
        try (var in = ScenarioLibrary.class.getResourceAsStream(TEMPLATES_ROOT + "/" + id + ".yaml")) {
            if (in == null) {
                throw new NoSuchFileException(id);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    public List<Entry> list() throws IOException {
        List<Entry> out = new ArrayList<>();
        try (var in = ScenarioLibrary.class.getResourceAsStream(TEMPLATES_ROOT + "/index.txt")) {
            if (in != null) {
                for (String line : new String(in.readAllBytes(), StandardCharsets.UTF_8).lines()
                        .map(String::trim).filter(s -> !s.isEmpty()).toList()) {
                    out.add(describe(line, true, readTemplate(line), 0L));
                }
            }
        }
        if (Files.isDirectory(userDir)) {
            try (var files = Files.list(userDir)) {
                for (Path p : files.filter(f -> f.getFileName().toString().endsWith(".yaml"))
                        .sorted().toList()) {
                    String id = p.getFileName().toString().replaceFirst("\\.yaml$", "");
                    out.add(describe(id, false, Files.readString(p, StandardCharsets.UTF_8),
                            Files.getLastModifiedTime(p).toMillis()));
                }
            }
        }
        return out;
    }

    private Entry describe(String id, boolean template, String yaml, long lastModified) {
        try {
            var scenario = ScenarioLoader.load(
                    new ByteArrayInputStream(yaml.getBytes(StandardCharsets.UTF_8)));
            return new Entry(id, scenario.name(), template, true,
                    scenario.nodes().size(), lastModified);
        } catch (RuntimeException | IOException e) {
            return new Entry(id, id, template, false, 0, lastModified);
        }
    }

    public Content get(String id) throws IOException {
        requireValidId(id);
        if (isTemplate(id)) {
            return new Content(id, true, readTemplate(id));
        }
        Path p = userDir.resolve(id + ".yaml");
        if (!Files.exists(p)) {
            throw new NoSuchFileException(id);
        }
        return new Content(id, false, Files.readString(p, StandardCharsets.UTF_8));
    }

    public void put(String id, String yaml) throws IOException {
        requireValidId(id);
        if (isTemplate(id)) {
            throw new IllegalStateException("template scenarios are read-only: " + id);
        }
        Files.writeString(userDir.resolve(id + ".yaml"), yaml, StandardCharsets.UTF_8);
    }

    public void delete(String id) throws IOException {
        requireValidId(id);
        if (isTemplate(id)) {
            throw new IllegalStateException("template scenarios are read-only: " + id);
        }
        Path p = userDir.resolve(id + ".yaml");
        if (!Files.exists(p)) {
            throw new NoSuchFileException(id);
        }
        Files.delete(p);
    }

    public String fork(String sourceId, String newId) throws IOException {
        String yaml = get(sourceId).yaml(); // 源可以是模板或用户场景；get 自带 id 校验
        put(newId, yaml);                   // put 自带 newId 校验与模板冲突拒绝
        return newId;
    }

    /**
     * 外部输入档全量校验（规则 1–8 + 9–11）。返回**逐条**错误清单；合法 = 空表。
     * 解析失败（YAML 语法/结构）作为单条错误返回。
     */
    public List<String> validateYaml(String yaml) {
        try {
            var scenario = ScenarioLoader.load(
                    new ByteArrayInputStream(yaml.getBytes(StandardCharsets.UTF_8)));
            var report = new ScenarioValidator(ContractRegistry.loadFromServiceLoader(),
                    ScenarioValidator.InputTrust.EXTERNAL).validate(scenario);
            return report.ok() ? List.of() : List.copyOf(report.errors());
        } catch (IOException e) {
            return List.of(String.valueOf(e.getMessage()));
        } catch (RuntimeException e) {
            return List.of(String.valueOf(e.getMessage()));
        }
    }
}
```

模板 1 `worker-crash-failover.yaml`（与 README §7 最小场景同构；SUT 为 examples 的参考实现，`duo serve` 的运行类路径含 examples）：

```yaml
name: worker-crash-failover
topology:
  - id: zk
    contract: registry
    tier: virtual
  - id: master
    contract: scheduler
    tier: real
    sut: true
    launch: { mode: in-process, main: io.duo.sim.examples.scheduler.DemoScheduler }
    config: { dag.tasks: "job-a,job-b,job-c,job-d" }
    exposes: [{ contract: scheduler, port: 0 }]
    wiring:
      registry: { node: zk, contract: registry }
  - id: workers
    contract: worker
    tier: virtual
    count: 4
    capacity: { slots: 1 }
    wiring:
      registry: { node: zk, contract: registry }
behaviors:
  profiles:
    default: { duration: 15s, jitter: 0.1, successRate: 1.0 }
  bindings:
    - node: workers
      profile: default
timeline:
  - at: 10s
    action: crash
    target: workers[3]
  - at: 27s
    action: restart
    target: workers[3]
assertions:
  - affectedTasksAtLeast: { min: 1 }
  - failoverWithin: { seconds: 30 }
  - noTaskLost: { requireAllSuccess: true }
```

模板 2 `worker-freeze-demo.yaml`：同上拓扑与 behaviors，仅 timeline/assertions 改为——

```yaml
timeline:
  - at: 10s
    action: freeze
    target: workers[2]
    duration: 5s
assertions:
  - affectedTasksAtLeast: { min: 1 }
  - noTaskLost: { requireAllSuccess: true }
  - eventSequence: [sim.fault-injected, sim.fault-cleared]
```

（`name:` 字段相应改为 `worker-freeze-demo`。）

- [ ] **Step 4: 跑测试确认通过**（同 Step 2 命令）Expected: PASS（5 tests）。
- [ ] **Step 5: 整 reactor 回归 + Commit**

```bash
.\mvnw.cmd -o -B test "-Dduo.docker.enabled=false"
git add duo-sim-control
git commit -m "feat(control): ScenarioLibrary 模板+用户库（id 白名单/模板只读/fork/外部输入档校验）——M10 计划一 Task 3，+5 测全绿"
```

---

### Task 4: `/api/scenarios` 端点族（list/get/put/delete/fork/validate）

**Files:**
- Modify: `duo-sim-control/src/main/java/io/duo/sim/control/rest/RestControlServer.java`
  - 新字段 `private final ScenarioLibrary library;`、新构造器、`registerRoutes()` 末尾追加 `registerLibraryRoutes();`
- Test: Create `duo-sim-control/src/test/java/io/duo/sim/control/rest/RestConsoleApiTest.java`

**Interfaces:**
- Consumes: Task 3 的 `ScenarioLibrary` 全部公开签名。
- Produces（计划二前端依赖的 HTTP 契约）:
  - `GET /api/scenarios` → `200 {"templates":[Entry...],"user":[Entry...]}`
  - `GET /api/scenarios/{id}` → `200 {"id":…,"template":bool,"yaml":…}`；坏 id 400；不存在 404
  - `PUT /api/scenarios/{id}`（body=YAML 文本）→ `200 {"id":…,"saved":true}`；校验不过 `400 {"errors":[...]}`；模板 405
  - `DELETE /api/scenarios/{id}` → `200 {"id":…,"deleted":true}`；模板 405；不存在 404
  - `POST /api/scenarios/{id}/fork`（body=`{"id":"new-id"}`）→ `200 {"source":…,"id":"new-id"}`
  - `POST /api/scenarios/validate`（body=YAML 文本）→ `200 {"errors":[...],"ok":bool}`
  - 未配置库（旧构造器）→ `503 {"error":"scenario library not configured (pass --library-dir to serve)"}`
  - 异常映射沿用既有口径：`IllegalArgumentException`→400、`IllegalStateException`→405、`NoSuchFileException`→404。

- [ ] **Step 1: 写失败测试**

```java
package io.duo.sim.control.rest;

import io.duo.sim.control.ScenarioHost;
import io.duo.sim.control.library.ScenarioLibrary;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** M10 计划一 Task 4：场景库端点契约（list/get/put/delete/fork/validate + 外部输入档 + 503）。 */
class RestConsoleApiTest {

    @TempDir
    Path libDir;

    private ScenarioHost host;
    private RestControlServer server;
    private HttpClient client;
    private int port;

    @BeforeEach
    void setUp() throws Exception {
        host = new ScenarioHost();
        server = new RestControlServer(host, RestControlServer.Auth.INSECURE, null,
                new ScenarioLibrary(libDir));
        port = server.start(0);
        client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    }

    @AfterEach
    void tearDown() {
        server.close();
        host.close();
    }

    private HttpResponse<String> send(String method, String path, String body) throws Exception {
        var req = switch (method) {
            case "GET" -> HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                    .GET().build();
            case "DELETE" -> HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                    .DELETE().build();
            case "PUT" -> HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                    .PUT(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8)).build();
            default -> HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                    .POST(HttpRequest.BodyPublishers.ofString(body == null ? "" : body,
                            StandardCharsets.UTF_8)).build();
        };
        return client.send(req, HttpResponse.BodyHandlers.ofString());
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> json(HttpResponse<String> resp) throws Exception {
        return new com.fasterxml.jackson.databind.ObjectMapper()
                .readValue(resp.body(), Map.class);
    }

    @Test
    void listSplitsTemplatesFromUserScenarios() throws Exception {
        send("PUT", "/api/scenarios/mine-01", VALID_YAML);
        var resp = send("GET", "/api/scenarios", null);
        assertEquals(200, resp.statusCode());
        var body = json(resp);
        var templates = (List<Map<String, Object>>) body.get("templates");
        var user = (List<Map<String, Object>>) body.get("user");
        assertTrue(templates.stream().anyMatch(t -> "worker-crash-failover".equals(t.get("id"))));
        assertTrue(user.stream().anyMatch(t -> "mine-01".equals(t.get("id"))));
    }

    @Test
    void putRejectsInvalidYamlWithPerIssueErrors() throws Exception {
        var resp = send("PUT", "/api/scenarios/bad-01", "name: [broken");
        assertEquals(400, resp.statusCode());
        assertTrue(((List<?>) json(resp).get("errors")).size() >= 1);
        // 外部输入档：launch.command 必须被拒
        var resp2 = send("PUT", "/api/scenarios/bad-02", """
                name: bad
                topology:
                  - id: m
                    contract: scheduler
                    tier: real
                    sut: true
                    launch: { mode: external, command: "calc.exe" }
                timeline: []
                assertions: []
                """);
        assertEquals(400, resp2.statusCode());
    }

    @Test
    void putToTemplateIs405AndDeleteTemplateIs405() throws Exception {
        assertEquals(405, send("PUT", "/api/scenarios/worker-crash-failover", VALID_YAML).statusCode());
        assertEquals(405, send("DELETE", "/api/scenarios/worker-crash-failover").statusCode());
    }

    @Test
    void traversalIdsAre400NeverTouchDisk() throws Exception {
        assertEquals(400, send("GET", "/api/scenarios/..%2F..%2Fsecret", null).statusCode());
        assertEquals(400, send("GET", "/api/scenarios/a%20b", null).statusCode());
        assertEquals(400, send("PUT", "/api/scenarios/..", VALID_YAML).statusCode());
    }

    @Test
    void forkCreatesUserCopyFromTemplate() throws Exception {
        var resp = send("POST", "/api/scenarios/worker-crash-failover/fork",
                "{\"id\":\"my-fork\"}");
        assertEquals(200, resp.statusCode());
        var got = send("GET", "/api/scenarios/my-fork", null);
        assertEquals(200, got.statusCode());
        assertTrue(((String) json(got).get("yaml")).contains("name:"));
    }

    @Test
    void validateEndpointReturnsOkFlag() throws Exception {
        var good = json(send("POST", "/api/scenarios/validate", VALID_YAML));
        assertEquals(Boolean.TRUE, good.get("ok"));
        var bad = json(send("POST", "/api/scenarios/validate", "name: [broken"));
        assertEquals(Boolean.FALSE, bad.get("ok"));
    }

    @Test
    void libraryNotConfiguredIs503() throws Exception {
        try (ScenarioHost h2 = new ScenarioHost();
             RestControlServer s2 = new RestControlServer(h2,
                     RestControlServer.Auth.INSECURE, null)) {
            int p2 = s2.start(0);
            var resp = HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create(
                    "http://127.0.0.1:" + p2 + "/api/scenarios")).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(503, resp.statusCode());
        }
    }

    private static final String VALID_YAML = """
            name: api-smoke
            topology:
              - id: zk
                contract: registry
                tier: virtual
              - id: master
                contract: scheduler
                tier: virtual
                sut: true
                launch: { mode: in-process, main: io.duo.sim.examples.scheduler.DemoScheduler }
                config: { dag.tasks: "a" }
                exposes: [{ contract: scheduler, port: 0 }]
                wiring:
                  registry: { node: zk, contract: registry }
              - id: workers
                contract: worker
                tier: virtual
                count: 1
                capacity: { slots: 1 }
                wiring:
                  registry: { node: zk, contract: registry }
            behaviors:
              profiles:
                default: { duration: 3s, jitter: 0.0, successRate: 1.0 }
              bindings:
                - node: workers
                  profile: default
            timeline: []
            assertions:
              - noTaskLost: { requireAllSuccess: true }
            """;
}
```

（注：`send` helper 里的首个 `if (body != null)` 占位分支是**写作失误**，执行者直接以其中的 `switch (method)` 版本为准——即 `send` 只含 `var req = switch (method) {...}` 与 `return client.send(...)` 两段。）

- [ ] **Step 2: 跑测试确认失败**

Run: `.\mvnw.cmd -o -B -pl duo-sim-control -am test "-Dtest=RestConsoleApiTest" "-Dduo.docker.enabled=false"`
Expected: **编译失败**，`cannot find symbol: constructor RestControlServer(..., ScenarioLibrary)`（以及 404 on /api/scenarios——编译过后）。

- [ ] **Step 3: 最小实现**

`RestControlServer.java` 三处改动：

① 字段与构造器（既有两参构造器委托新构造器，`library = null`）：

```java
private final ScenarioLibrary library;

/** M10：控制台场景库。{@code null} = 未配置（/api/scenarios 返回 503）。 */
public RestControlServer(ScenarioHost host, Auth auth, String token, ScenarioLibrary library) {
    this(host, auth, token);
    this.library = library;
}
```

（既有 `RestControlServer(ScenarioHost, Auth, String)` 不动；`this.library = library` 在该委托链下可行，因 `this(...)` 先行。字段初始化补 `this.library = null;` 于既有构造器，或将字段声明为非 final 均可——执行者以可编译为准，优先在既有构造器里补 `this.library = null;` 并把字段改为非 final。）

② `registerRoutes()` 末尾追加 `registerLibraryRoutes();`：

```java
/** M10 W-API：场景库端点族（/api/ 前缀——控制台专属面，不触碰被 CLI 依赖的核心端点）。 */
private void registerLibraryRoutes() {
    server.createContext("/api/scenarios/validate", ex -> {
        if (!guard(ex, false)) {
            return;
        }
        if (!"POST".equals(ex.getRequestMethod())) {
            respond(ex, 405, Map.of("error", "method not allowed"));
            return;
        }
        if (library == null) {
            respond(ex, 503, Map.of("error", LIBRARY_NOT_CONFIGURED));
            return;
        }
        try {
            String yaml = readBody(ex);
            List<String> errors = library.validateYaml(yaml);
            respond(ex, 200, Map.of("ok", errors.isEmpty(), "errors", errors));
        } catch (BodyTooLargeException e) {
            rejectTooLarge(ex, e);
        } catch (IOException e) {
            // 客户端断开
        }
    });
    server.createContext("/api/scenarios", ex -> {
        if (!guard(ex, false)) {
            return;
        }
        if (library == null) {
            respond(ex, 503, Map.of("error", LIBRARY_NOT_CONFIGURED));
            return;
        }
        String path = ex.getRequestURI().getPath(); // /api/scenarios[/{id}[/fork]]
        String rest = path.length() > "/api/scenarios".length()
                ? path.substring("/api/scenarios".length() + 1) : "";
        String[] parts = rest.isEmpty() ? new String[0] : rest.split("/");
        try {
            switch (ex.getRequestMethod()) {
                case "GET" -> {
                    if (parts.length == 0) {
                        var all = library.list();
                        respond(ex, 200, Map.of(
                                "templates", all.stream().filter(ScenarioLibrary.Entry::template).toList(),
                                "user", all.stream().filter(e -> !e.template()).toList()));
                    } else if (parts.length == 1) {
                        var c = library.get(java.net.URLDecoder.decode(parts[0], StandardCharsets.UTF_8));
                        respond(ex, 200, Map.of("id", c.id(), "template", c.template(), "yaml", c.yaml()));
                    } else {
                        respond(ex, 404, Map.of("error", "not found"));
                    }
                }
                case "PUT" -> {
                    if (parts.length != 1) {
                        respond(ex, 404, Map.of("error", "not found"));
                        return;
                    }
                    String id = java.net.URLDecoder.decode(parts[0], StandardCharsets.UTF_8);
                    if (!ScenarioLibrary.validId(id)) {
                        respond(ex, 400, Map.of("error", "invalid scenario id: only [A-Za-z0-9._-]"));
                        return;
                    }
                    String yaml = readBody(ex);
                    List<String> errors = library.validateYaml(yaml);
                    if (!errors.isEmpty()) {
                        respond(ex, 400, Map.of("errors", errors));
                        return;
                    }
                    library.put(id, yaml);
                    respond(ex, 200, Map.of("id", id, "saved", true));
                }
                case "DELETE" -> {
                    if (parts.length != 1) {
                        respond(ex, 404, Map.of("error", "not found"));
                        return;
                    }
                    String id = java.net.URLDecoder.decode(parts[0], StandardCharsets.UTF_8);
                    if (!ScenarioLibrary.validId(id)) {
                        respond(ex, 400, Map.of("error", "invalid scenario id: only [A-Za-z0-9._-]"));
                        return;
                    }
                    library.delete(id);
                    respond(ex, 200, Map.of("id", id, "deleted", true));
                }
                case "POST" -> {
                    if (parts.length == 2 && "fork".equals(parts[1])) {
                        String source = java.net.URLDecoder.decode(parts[0], StandardCharsets.UTF_8);
                        String body = readBody(ex);
                        var req = mapper.readValue(body, java.util.Map.class);
                        Object newIdObj = req.get("id");
                        if (!(newIdObj instanceof String newId) || !ScenarioLibrary.validId(newId)) {
                            respond(ex, 400, Map.of("error",
                                    "body must be {\"id\":\"<valid-id>\"}"));
                            return;
                        }
                        String created = library.fork(source, newId);
                        respond(ex, 200, Map.of("source", source, "id", created));
                    } else {
                        respond(ex, 404, Map.of("error", "not found"));
                    }
                }
                default -> respond(ex, 405, Map.of("error", "method not allowed"));
            }
        } catch (BodyTooLargeException e) {
            rejectTooLarge(ex, e);
        } catch (NoSuchFileException e) {
            respond(ex, 404, Map.of("error", "scenario not found: " + e.getMessage()));
        } catch (IllegalArgumentException e) {
            respond(ex, 400, Map.of("error", sanitizeReason(e.getMessage())));
        } catch (IllegalStateException e) {
            // 模板只读等：资源存在但方法不被允许 → 405
            respond(ex, 405, Map.of("error", sanitizeReason(e.getMessage())));
        } catch (com.fasterxml.jackson.core.JacksonException e) {
            respond(ex, 400, Map.of("error", sanitizeReason(e.getMessage())));
        } catch (IOException e) {
            respond(ex, 500, Map.of("error", sanitizeReason(e.getMessage())));
        }
    });
}

private static final String LIBRARY_NOT_CONFIGURED =
        "scenario library not configured (pass --library-dir to serve)";
```

（`NoSuchFileException` 需要 `import java.nio.file.NoSuchFileException;`；`ScenarioLibrary` 需要 `import io.duo.sim.control.library.ScenarioLibrary;`。）

- [ ] **Step 4: 跑测试确认通过**（同 Step 2 命令）Expected: PASS（7 tests）。
- [ ] **Step 5: 整 reactor 回归 + Commit**

```bash
.\mvnw.cmd -o -B test "-Dduo.docker.enabled=false"
git add duo-sim-control
git commit -m "feat(rest): /api/scenarios 六端点（list/get/put/delete/fork/validate）——外部输入档收窄延伸到库，+7 测全绿"
```

---

### Task 5: `/api/capabilities`（能力元数据）与 `/api/meta`（serve 自述）

**Files:**
- Modify: `duo-sim-control/src/main/java/io/duo/sim/control/rest/RestControlServer.java`（`registerRoutes()` 追加两个 context）
- Test: Modify `duo-sim-control/src/test/java/io/duo/sim/control/rest/RestConsoleApiTest.java`（追加用例）

**Interfaces:**
- Consumes: `ServiceLoader.load(ComponentProvider.class)`（`io.duo.sim.kernel.spi.ComponentProvider`：`contract()/tier()/implName()/isDefault()/metadata()`）；`CapabilityMetadata` record 六字段；`ScenarioLibrary.userDir()`；`EventRecorder.MAX_BUFFERED_EVENTS`（`duo-sim-scenario` :34，public）。
- Produces（计划二注入面板数据源）: `GET /api/capabilities` → `200 {"providers":[{"contract":…,"tier":…,"impl":…,"default":bool,"supportedFaults":[…],"endpointShape":…,"interfaceDirect":bool,"instanceControl":bool}]}`；`GET /api/meta` → `200 {"version":…,"auth":"TOKEN"|"INSECURE","libraryDir":…,"eventBufferMax":500000}`。

- [ ] **Step 1: 写失败测试**（追加到 `RestConsoleApiTest`）

```java
@Test
void capabilitiesListsProvidersWithSupportedFaults() throws Exception {
    var resp = send("GET", "/api/capabilities", null);
    assertEquals(200, resp.statusCode());
    var body = json(resp);
    var providers = (List<Map<String, Object>>) body.get("providers");
    // control 测试类路径上有 components 模块 ⇒ ServiceLoader 必然发现 virtual registry
    assertTrue(providers.stream().anyMatch(p ->
            "registry".equals(p.get("contract")) && "virtual".equals(p.get("tier"))));
    assertTrue(providers.stream().anyMatch(p ->
            p.get("supportedFaults") != null), "supportedFaults must be present");
}

@Test
void metaDescribesServeEnvironment() throws Exception {
    var resp = send("GET", "/api/meta", null);
    assertEquals(200, resp.statusCode());
    var body = json(resp);
    assertEquals("INSECURE", body.get("auth"));
    assertTrue(body.containsKey("version"));
    assertTrue(new java.io.File((String) body.get("libraryDir")).isAbsolute(),
            "libraryDir must be an absolute path");
}
```

（`libraryDir` 断言基于 `ScenarioLibrary` 构造器 `toAbsolutePath().normalize()`，必为绝对路径。）

- [ ] **Step 2: 跑测试确认失败**

Run: `.\mvnw.cmd -o -B -pl duo-sim-control -am test "-Dtest=RestConsoleApiTest#capabilitiesListsProvidersWithSupportedFaults+metaDescribesServeEnvironment" "-Dduo.docker.enabled=false"`
Expected: FAIL（404 on 两个端点）。

- [ ] **Step 3: 最小实现**（`registerRoutes()` 追加）

```java
// M10 W-API-7：能力元数据（注入面板「动作×契约」下拉的唯一数据源，前端不写死）。
// 直接枚举 ServiceLoader provider——不实例化 ContractRegistry，IDLE 态同样可用。
server.createContext("/api/capabilities", ex -> {
    if (!guard(ex, false)) {
        return;
    }
    List<Map<String, Object>> providers = new ArrayList<>();
    for (var p : java.util.ServiceLoader.load(io.duo.sim.kernel.spi.ComponentProvider.class)) {
        var m = p.metadata();
        var row = new java.util.LinkedHashMap<String, Object>();
        row.put("contract", p.contract().name());
        row.put("tier", p.tier().name());
        row.put("impl", p.implName());
        row.put("default", p.isDefault());
        row.put("supportedFaults", m.supportedFaults());
        row.put("endpointShape", m.endpointShape().name());
        row.put("interfaceDirect", m.interfaceDirect());
        row.put("instanceControl", m.instanceControl());
        providers.add(row);
    }
    respond(ex, 200, Map.of("providers", providers));
});
// M10 W-API-9：serve 自述（关于页 + 前端能力探测）。不回显令牌本体——只回认证模式。
server.createContext("/api/meta", ex -> {
    if (!guard(ex, false)) {
        return;
    }
    String version = RestControlServer.class.getPackage().getImplementationVersion();
    respond(ex, 200, Map.of(
            "version", version == null ? "0.1.0-SNAPSHOT" : version,
            "auth", auth.name(),
            "libraryDir", library == null ? "" : library.userDir().toString(),
            "eventBufferMax", io.duo.sim.scenario.EventRecorder.MAX_BUFFERED_EVENTS));
});
```

（需要 `import java.util.ArrayList;`（若未有）；`EventRecorder` 所在包以实际为准——若不在 `io.duo.sim.scenario`，用全限定名修正 import。）

- [ ] **Step 4: 跑测试确认通过**（同 Step 2 命令）Expected: PASS。
- [ ] **Step 5: 整 reactor 回归 + Commit**

```bash
.\mvnw.cmd -o -B test "-Dduo.docker.enabled=false"
git add duo-sim-control
git commit -m "feat(rest): /api/capabilities 能力元数据 + /api/meta serve 自述——M10 计划一 Task 5"
```

---

### Task 6: `/api/inject/clear`——手动清除注入

**Files:**
- Modify: `duo-sim-control/src/main/java/io/duo/sim/control/rest/RestControlServer.java`（`registerRoutes()` 追加）
- Test: Modify `duo-sim-control/src/test/java/io/duo/sim/control/rest/RestConsoleApiTest.java`（追加用例）

**Interfaces:**
- Consumes: Task 1 的 `host.clear(FaultAction)`；既有 `/inject` 的 body 解析与错误映射模式。
- Produces: `POST /api/inject/clear`（body=FaultAction JSON，与 `/inject` 同构）→ `200 {"success":bool,"reason":…}`；未运行 409；坏 body/NPE 400。

- [ ] **Step 1: 写失败测试**（追加到 `RestConsoleApiTest`；需要先启动场景——沿用本测试类的 `host`/`server` 夹具，POST `FAST_SCENARIO` 等运行后操作；`FAST_SCENARIO` 从 `RestControlServerTest` 复制为私有常量，或引用 `RestControlServerTest.FAST_SCENARIO` 若执行者确认其为 package 可见）

```java
@Test
void clearInjectionAfterInjecting() throws Exception {
    // 启动场景（自包含：不依赖其它测试类的可见性——这里直接内联一份最小场景）
    var startResp = send("POST", "/scenario", CLEAR_SCENARIO);
    assertEquals(200, startResp.statusCode(), startResp.body());
    long deadline = System.currentTimeMillis() + 10_000;
    while (System.currentTimeMillis() < deadline
            && send("GET", "/scenario/status", null).statusCode() != 200) {
        Thread.sleep(100);
    }
    assertEquals(200, send("GET", "/scenario/status", null).statusCode());

    var faultBody = """
            {"type":"freeze",
             "target":{"componentId":{"value":"workers"},"instanceIndex":1},
             "params":{}}
            """;
    // 注入 + 清除（注入在组件注册完成前可能 unknown target：有界重试）
    boolean injected = false;
    for (int i = 0; i < 20 && !injected; i++) {
        var r = json(send("POST", "/inject", faultBody));
        injected = Boolean.TRUE.equals(r.get("success"));
        if (!injected) {
            Thread.sleep(100);
        }
    }
    assertTrue(injected, "inject freeze should succeed");
    var cleared = json(send("POST", "/api/inject/clear", faultBody));
    assertEquals(Boolean.TRUE, cleared.get("success"),
            "clear should succeed: " + cleared.get("reason"));
}

private static final String CLEAR_SCENARIO = """
        name: clear-smoke
        topology:
          - id: zk
            contract: registry
            tier: virtual
          - id: master
            contract: scheduler
            tier: virtual
            sut: true
            launch: { mode: in-process, main: io.duo.sim.control.testfixture.ControlFixtureSut }
            config: { dag.tasks: "a,b" }
            exposes: [{ contract: scheduler, port: 0 }]
            wiring:
              registry: { node: zk, contract: registry }
          - id: workers
            contract: worker
            tier: virtual
            count: 2
            capacity: { slots: 1 }
            wiring:
              registry: { node: zk, contract: registry }
        behaviors:
          profiles:
            default: { duration: 3s, jitter: 0.0, successRate: 1.0 }
          bindings:
            - node: workers
              profile: default
        timeline: []
        assertions:
          - noTaskLost: { requireAllSuccess: true }
        """;
```

（`FaultAction` JSON 的 `target` 字段名以内核 `FaultAction` record 为准：执行者若发现反序列化报缺字段，读 `duo-sim-kernel/src/main/java/io/duo/sim/kernel/api/FaultAction.java` 对齐字段名——`/inject` 既有用例（`RestControlServerTest` 内）里有可直接照抄的请求体。）

- [ ] **Step 2: 跑测试确认失败**

Run: `.\mvnw.cmd -o -B -pl duo-sim-control -am test "-Dtest=RestConsoleApiTest#clearInjectionAfterInjecting" "-Dduo.docker.enabled=false"`
Expected: FAIL（404 on /api/inject/clear）。

- [ ] **Step 3: 最小实现**（`registerRoutes()` 追加，错误映射与 `/inject` 逐行同构）

```java
// M10 W-API-8：手动清除注入（与 /inject 同构的 body 与错误映射；薄委托 host.clear）。
server.createContext("/api/inject/clear", ex -> {
    if (!guard(ex, false)) {
        return;
    }
    if (!"POST".equals(ex.getRequestMethod())) {
        respond(ex, 405, Map.of("error", "method not allowed"));
        return;
    }
    if (!host.isRunning()) {
        respond(ex, 409, Map.of("error", "scenario not running"));
        return;
    }
    try {
        String body = readBody(ex);
        var action = mapper.readValue(body, io.duo.sim.kernel.api.FaultAction.class);
        var result = host.clear(action);
        respond(ex, 200, Map.of("success", result.success(),
                "reason", result.reason() == null ? "" : result.reason()));
    } catch (BodyTooLargeException e) {
        rejectTooLarge(ex, e);
    } catch (com.fasterxml.jackson.core.JacksonException | IllegalArgumentException e) {
        respond(ex, 400, Map.of("error", String.valueOf(e.getMessage())));
    } catch (NullPointerException e) {
        respond(ex, 400, Map.of("error",
                "fault action is missing a required field: " + sanitizeReason(e.getMessage())));
    } catch (Exception e) {
        respond(ex, 500, Map.of("error", sanitizeReason(e.getMessage())));
    }
});
```

- [ ] **Step 4: 跑测试确认通过**（同 Step 2 命令）Expected: PASS。
- [ ] **Step 5: 整 reactor 回归 + Commit**

```bash
.\mvnw.cmd -o -B test "-Dduo.docker.enabled=false"
git add duo-sim-control
git commit -m "feat(rest): /api/inject/clear 手动清除注入——M10 计划一 Task 6，错误映射与 /inject 同构"
```

---

### Task 7: `GET /diagnose`——诊断链服务端一等化

**Files:**
- Modify: `duo-sim-control/src/main/java/io/duo/sim/control/rest/RestControlServer.java`（`registerRoutes()` 追加）
- Test: Modify `duo-sim-control/src/test/java/io/duo/sim/control/rest/RestConsoleApiTest.java`（追加用例）

**Interfaces:**
- Consumes: `FaultDiagnostics.analyze(List<Map<String,Object>> rawEvents, List<Map<String,Object>> assertionDetails) → Report`（`duo-sim-control` :139，Report 为 record 可被 Jackson 直序）；`host.eventsSince(0)`、`host.assertions()`。
- Produces: `GET /diagnose` → `200 Report JSON`（四段：chains/assertions/eventsTotal/…）；未启动 409；断链段由 `FaultDiagnostics` 既有 gaps 语义呈现 `MISSING`（零新逻辑）。

- [ ] **Step 1: 写失败测试**（追加到 `RestConsoleApiTest`）

```java
@Test
void diagnoseReturnsChainsAfterInjection() throws Exception {
    var startResp = send("POST", "/scenario", CLEAR_SCENARIO);
    assertEquals(200, startResp.statusCode());
    long deadline = System.currentTimeMillis() + 10_000;
    while (System.currentTimeMillis() < deadline
            && send("GET", "/scenario/status", null).statusCode() != 200) {
        Thread.sleep(100);
    }
    String faultBody = """
            {"type":"freeze",
             "target":{"componentId":{"value":"workers"},"instanceIndex":1},
             "params":{}}
            """;
    for (int i = 0; i < 20; i++) {
        if (Boolean.TRUE.equals(json(send("POST", "/inject", faultBody)).get("success"))) {
            break;
        }
        Thread.sleep(100);
    }
    // 给事件流一点时间落盘
    Thread.sleep(500);
    var resp = send("GET", "/diagnose", null);
    assertEquals(200, resp.statusCode());
    var body = json(resp);
    assertTrue(body.containsKey("chains"), "report must expose chains");
    assertTrue(((Number) body.get("eventsTotal")).intValue() >= 1);
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `.\mvnw.cmd -o -B -pl duo-sim-control -am test "-Dtest=RestConsoleApiTest#diagnoseReturnsChainsAfterInjection" "-Dduo.docker.enabled=false"`
Expected: FAIL（404）。

- [ ] **Step 3: 最小实现**（`registerRoutes()` 追加）

```java
// M10 W-API-8/10：诊断链服务端一等化——复用 CLI `duo diagnose` 同一套 FaultDiagnostics，
// 前端直接渲染四段报告（注入 → 组件反应 → SUT 事实 → 断言），断链段沿用 gaps→MISSING 语义。
server.createContext("/diagnose", ex -> {
    if (!guard(ex, false)) {
        return;
    }
    if (!"GET".equals(ex.getRequestMethod())) {
        respond(ex, 405, Map.of("error", "method not allowed"));
        return;
    }
    if (!host.hasResult() && !host.isRunning()) {
        respond(ex, 409, Map.of("error", "scenario not started"));
        return;
    }
    try {
        respond(ex, 200, io.duo.sim.control.FaultDiagnostics.analyze(
                host.eventsSince(0), host.assertions()));
    } catch (RuntimeException e) {
        respond(ex, 500, Map.of("error", "cannot analyze event stream: "
                + sanitizeReason(e.getMessage())));
    }
});
```

- [ ] **Step 4: 跑测试确认通过**（同 Step 2 命令）Expected: PASS。
- [ ] **Step 5: 整 reactor 回归 + Commit**

```bash
.\mvnw.cmd -o -B test "-Dduo.docker.enabled=false"
git add duo-sim-control
git commit -m "feat(rest): GET /diagnose 诊断链一等端点——复用 FaultDiagnostics，M10 计划一 Task 7"
```

---

### Task 8: SPA 静态资源托管 + CSP 纵深防御

**Files:**
- Create: `duo-sim-control/src/main/resources/console/index.html`（占位页；计划二以构建产物覆盖）
- Modify: `duo-sim-control/src/main/java/io/duo/sim/control/rest/RestControlServer.java`（`registerRoutes()` 追加静态托管）
- Test: Modify `duo-sim-control/src/test/java/io/duo/sim/control/rest/RestConsoleApiTest.java`（追加用例）

**Interfaces:**
- Consumes: classpath 资源 `/console/**`；既有 `guard(ex, true)` 免令牌通道。
- Produces（计划二前端构建产物落点契约）: `GET /` 与 `GET /console/**` → 静态文件（MIME：html/js/css/svg/json/png/ico/map/woff2）；无扩展名路径 404 时回退 `index.html`（SPA 路由 fallback）；全部静态响应带 `Content-Security-Policy: default-src 'self'` 与 `X-Content-Type-Options: nosniff`。

- [ ] **Step 1: 写占位页**（`duo-sim-control/src/main/resources/console/index.html`）

```html
<!DOCTYPE html>
<html lang="zh-CN">
<head>
  <meta charset="UTF-8" />
  <title>Duo Console</title>
</head>
<body>
  <h1>Duo Console</h1>
  <p>控制台前端尚未构建（M10 计划二交付）。当前 serve 的 REST API 已可用。</p>
</body>
</html>
```

- [ ] **Step 2: 写失败测试**（追加到 `RestConsoleApiTest`）

```java
@Test
void staticAssetsServedWithCspAndTraversalRejected() throws Exception {
    var index = client.send(HttpRequest.newBuilder(URI.create(
            "http://127.0.0.1:" + port + "/console/index.html")).GET().build(),
            HttpResponse.BodyHandlers.ofString());
    assertEquals(200, index.statusCode());
    assertEquals("text/html", index.headers().firstValue("Content-Type").orElse("")
            .split(";")[0].trim());
    assertEquals("default-src 'self'",
            index.headers().firstValue("Content-Security-Policy").orElse("").trim());
    assertTrue(index.body().contains("Duo Console"));

    // SPA fallback：无扩展名路径回退 index.html
    var route = client.send(HttpRequest.newBuilder(URI.create(
            "http://127.0.0.1:" + port + "/console/library/my-drill")).GET().build(),
            HttpResponse.BodyHandlers.ofString());
    assertEquals(200, route.statusCode());

    // 穿越：.. 必须拒绝，绝不触碰文件路径语义
    var trav = client.send(HttpRequest.newBuilder(URI.create(
            "http://127.0.0.1:" + port + "/console/../rest/RestControlServer.class")).GET().build(),
            HttpResponse.BodyHandlers.ofString());
    assertTrue(trav.statusCode() == 400 || trav.statusCode() == 404);

    // 根路径也出控制台
    var root = client.send(HttpRequest.newBuilder(URI.create(
            "http://127.0.0.1:" + port + "/")).GET().build(),
            HttpResponse.BodyHandlers.ofString());
    assertEquals(200, root.statusCode());
}
```

- [ ] **Step 3: 跑测试确认失败**

Run: `.\mvnw.cmd -o -B -pl duo-sim-control -am test "-Dtest=RestConsoleApiTest#staticAssetsServedWithCspAndTraversalRejected" "-Dduo.docker.enabled=false"`
Expected: FAIL（`/console/index.html` 404——无托管）。

- [ ] **Step 4: 最小实现**（`registerRoutes()` 追加）

```java
// M10 W-API-11：SPA 静态托管。静态资源是构建产物、不含机密 → 与 /health 同档免令牌
//（guard(ex, true) 仍做回环 Host/Origin 校验）；API 依旧全令牌。纵深防御：CSP + nosniff。
server.createContext("/", ex -> {
    if (!guard(ex, true)) {
        return;
    }
    if (!"GET".equals(ex.getRequestMethod())) {
        respond(ex, 405, Map.of("error", "method not allowed"));
        return;
    }
    serveConsoleAsset(ex, ex.getRequestURI().getPath());
});
```

```java
private static final java.util.Map<String, String> MIME = java.util.Map.ofEntries(
        java.util.Map.entry("html", "text/html; charset=utf-8"),
        java.util.Map.entry("js", "text/javascript; charset=utf-8"),
        java.util.Map.entry("css", "text/css; charset=utf-8"),
        java.util.Map.entry("svg", "image/svg+xml"),
        java.util.Map.entry("json", "application/json"),
        java.util.Map.entry("png", "image/png"),
        java.util.Map.entry("ico", "image/x-icon"),
        java.util.Map.entry("map", "application/json"),
        java.util.Map.entry("woff2", "font/woff2"));

private void serveConsoleAsset(HttpExchange ex, String path) throws java.io.IOException {
    String rel = path.equals("/") ? "index.html" : path;
    if (rel.startsWith("/console/")) {
        rel = rel.substring("/console/".length());
    } else if (rel.startsWith("/")) {
        rel = rel.substring(1);
    }
    // 穿越：拒绝 .. 与绝对化形态；其余按 classpath 资源解析（绝不触碰 java.io.File）
    if (rel.contains("..") || rel.contains("\\") || rel.startsWith("/")) {
        respond(ex, 400, Map.of("error", "invalid asset path"));
        return;
    }
    if (rel.isBlank()) {
        rel = "index.html";
    }
    String ext = rel.contains(".") ? rel.substring(rel.lastIndexOf('.') + 1) : "";
    byte[] bytes = null;
    try (var in = RestControlServer.class.getResourceAsStream("/console/" + rel)) {
        if (in != null) {
            bytes = in.readAllBytes();
        }
    }
    if (bytes == null && ext.isEmpty()) {
        // SPA 路由 fallback：无扩展名路径回 index.html
        try (var in = RestControlServer.class.getResourceAsStream("/console/index.html")) {
            if (in != null) {
                bytes = in.readAllBytes();
            }
        }
        ext = "html";
    }
    if (bytes == null) {
        respond(ex, 404, Map.of("error", "asset not found"));
        return;
    }
    ex.getResponseHeaders().set("Content-Type",
            MIME.getOrDefault(ext, "application/octet-stream"));
    ex.getResponseHeaders().set("Content-Security-Policy", "default-src 'self'");
    ex.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
    ex.sendResponseHeaders(200, bytes.length);
    try (var os = ex.getResponseBody()) {
        os.write(bytes);
    } catch (IOException e) {
        // 客户端断开
    }
}
```

- [ ] **Step 5: 跑测试确认通过**（同 Step 2 命令）Expected: PASS。
- [ ] **Step 6: 整 reactor 回归 + Commit**

```bash
.\mvnw.cmd -o -B test "-Dduo.docker.enabled=false"
git add duo-sim-control
git commit -m "feat(rest): SPA 静态托管（classpath /console + SPA fallback）+ CSP/nosniff——M10 计划一 Task 8"
```

---

### Task 9: CLI `serve` 场景参数可选 + `--library-dir`

**Files:**
- Modify: `duo-sim-control/src/main/java/io/duo/sim/control/cli/DuoCli.java`（`cmdServe` :146-201、help 文本 :531）
- Test: Modify `duo-sim-examples/src/test/java/io/duo/sim/control/cli/DuoCliTest.java`（追加用例——本计划唯一触碰 examples 的点，且只在 `src/test`）

**Interfaces:**
- Consumes: Task 3/4 的 `ScenarioLibrary(Path)` 与 `RestControlServer(host, auth, token, library)`。
- Produces: `serve [scenario.yaml] [--port N] [--token T | --token-file F | --insecure-no-auth] [--library-dir D]`——无场景参数时以 IDLE 态启动（`ScenarioHost.State.IDLE`），控制台为主要入口；带参数用法原样保留。`--library-dir` 缺省 `./duo-console-library`。

- [ ] **Step 1: 写失败测试**（追加到 `DuoCliTest`）

```java
@Test
void serveWithoutScenarioStillRequiresToken() {
    // 无场景参数同样必须显式提供认证材料（C-1 口径不因「空载启动」而松动）
    assertEquals(1, DuoCli.run("serve", "--port", "0"),
            "serve without yaml still refuses to start without a token");
}

@Test
void helpListsOptionalScenarioArgument() {
    // help 输出应反映 [scenario.yaml] 可选与 --library-dir
    String help = captureStdout(() -> DuoCli.run("help"));
    assertTrue(help.contains("serve [scenario.yaml]"), help);
    assertTrue(help.contains("--library-dir"), help);
}

private static String captureStdout(java.util.function.Supplier<Integer> action) {
    var original = System.out;
    var buffer = new java.io.ByteArrayOutputStream();
    System.setOut(new java.io.PrintStream(buffer, true, java.nio.charset.StandardCharsets.UTF_8));
    try {
        action.get();
    } finally {
        System.setOut(original);
    }
    return buffer.toString(java.nio.charset.StandardCharsets.UTF_8);
}
```

（`help` 输出若走 `System.err`，把 capture 目标换成 `System.err`——以 `DuoCli` 现有 help 实现为准；执行者先读 :531 附近确认输出流。）

- [ ] **Step 2: 跑测试确认失败**

Run: `.\mvnw.cmd -o -B -pl duo-sim-examples -am test "-Dtest=DuoCliTest" "-Dduo.docker.enabled=false"`
Expected: FAIL——`serve` 无参数时当前直接 `return 1`（`usage` 分支），`helpListsOptionalScenarioArgument` 断言 help 文本不含新形态。

- [ ] **Step 3: 最小实现**（`cmdServe` 改造）

```java
// ---- serve [yaml] [--port N] [--token T | --token-file F | --insecure-no-auth] [--library-dir D] ----

private static int cmdServe(List<String> args) throws Exception {
    String yamlPath = arg(args, 0); // M10：可选——缺省 IDLE 态启动，控制台为主要入口
    if (yamlPath != null && yamlPath.startsWith("--")) {
        System.err.println("usage: serve [scenario.yaml] [--port N] [--token T | --token-file F"
                + " | --insecure-no-auth] [--library-dir D]");
        return 1;
    }
    String portArg = opt(args, "--port");
    int port = portArg == null ? 7788 : Integer.parseInt(portArg);
    String libraryDir = opt(args, "--library-dir");
    Path libraryPath = Path.of(libraryDir == null ? "./duo-console-library" : libraryDir);

    // （认证材料解析部分：与现状逐行相同——令牌必填/DUO_TOKEN/--token-file/--insecure-no-auth，不动）

    ScenarioHost host = new ScenarioHost();
    ScenarioLibrary library = new ScenarioLibrary(libraryPath);
    RestControlServer server = new RestControlServer(host,
            insecure ? RestControlServer.Auth.INSECURE : RestControlServer.Auth.TOKEN, token,
            library);
    if (yamlPath != null) {
        host.start(Path.of(yamlPath));
        // SUT 自行退出时自动固化结果（现状行为保留）
        host.awaitFinishInBackground(30 * 60_000L);
    }
    int actual = server.start(port);
    // 脚本据此发现端口（--port 0 时为实际分配值）；令牌**不**回显（现状口径）。
    System.out.println("listening on http://127.0.0.1:" + actual
            + (insecure ? " (auth: none)" : " (auth: bearer token)")
            + (yamlPath == null
                ? " (idle: no scenario loaded; console at http://127.0.0.1:" + actual + "/)"
                : ""));
    System.out.flush();

    Runtime.getRuntime().addShutdownHook(new Thread(() -> {
        server.close();
        host.close();
    }));
    new CountDownLatch(1).await(); // 阻塞至 SIGINT/SIGTERM
    return 0;
}
```

（注释处「与现状逐行相同」的认证解析段，执行者**原样保留** `cmdServe` 里 :155-176 的既有代码，不重写。help 文本 :531 行同步改为 `serve [scenario.yaml] [--port N] [--token T | --token-file F | --insecure-no-auth] [--library-dir D]`。需要 `import io.duo.sim.control.library.ScenarioLibrary;`。）

- [ ] **Step 4: 跑测试确认通过**（同 Step 2 命令）Expected: PASS（既有用例零回归 + 2 条新用例）。
- [ ] **Step 5: 整 reactor 回归 + Commit**

```bash
.\mvnw.cmd -o -B test "-Dduo.docker.enabled=false"
git add duo-sim-control duo-sim-examples
git commit -m "feat(cli): serve 场景参数可选（IDLE 态 + 控制台入口）+ --library-dir——M10 计划一 Task 9"
```

---

### Task 10: 全量回归 + 质量门禁 + 口径文档

**Files:**
- Modify: `CHANGELOG.md`（未发布段）
- Modify: `docs/DECISIONS.md`（D16 登记）
- Modify: `README.md` §5.3 与 `README.en.md` 对应行（控制台入口一句）

- [ ] **Step 1: 全量回归 + 依赖门禁**

```bash
.\mvnw.cmd -o -B test "-Dduo.docker.enabled=false"
# 预期：397+（2+1+5+7+2+1+1+1+2）≈ 419 测 / 0 失败 / 0 错误 / 12 skip（实测数字以输出为准，提交信息按实测写）
.\mvnw.cmd -o -B "-Dquality" "-DskipTests" verify
# 预期：9 × No dependency problems found + BUILD SUCCESS
```

- [ ] **Step 2: 核对 `/scenario/status` 含 `warnings`（规格 §6.2 收尾项）**

读 `ScenarioHost.status()`（`duo-sim-control/.../ScenarioHost.java` :385）的返回 Map：

- 若已含 `warnings` 键（来源 `warnings()`）→ **零改动**，在 `RestConsoleApiTest` 追加一条断言用例钉住：

```java
@Test
void statusExposesWarnings() throws Exception {
    send("POST", "/scenario", VALID_YAML);
    long deadline = System.currentTimeMillis() + 10_000;
    while (System.currentTimeMillis() < deadline
            && send("GET", "/scenario/status", null).statusCode() != 200) {
        Thread.sleep(100);
    }
    var body = json(send("GET", "/scenario/status", null));
    assertTrue(body.containsKey("warnings"), "status must expose warnings for the console");
}
```

- 若**不含** → 在 `status()` 的返回 Map 加一行 `m.put("warnings", warnings());`（warnings 语义：外部进程遗留等警告，规格 §6.2），再跑上述用例。

- [ ] **Step 3: 口径文档**（三处，内容如下）

`CHANGELOG.md` 未发布段追加：

```markdown
### Added — M10 计划一：Web 控制台 Java 控制面
- `/api/scenarios` 场景库端点族（模板+用户库、fork、外部输入档校验）与 `ScenarioLibrary`
- `/api/capabilities` 能力元数据、`/api/meta` serve 自述、`/api/inject/clear` 手动清除、`GET /diagnose` 一等端点
- `/events` 响应扩展 `next`/`dropped`（向后兼容）；SPA 静态托管（`/console/**`）+ CSP/nosniff
- `duo serve [scenario.yaml]` 场景参数可选（IDLE 态，控制台入口）+ `--library-dir`
- 设计文档：`docs/superpowers/specs/2026-09-30-duo-web-console-design.md`；决策 D16
```

`docs/DECISIONS.md` 追加 D16（按该文件既有行格式）：

```markdown
## D16 — Web 控制台立项（推翻「精美 Web 控制台」非目标）

- **决定**：M10 立项 Web 控制台（console-ui 前端 + 控制面 API 扩展）。推翻 ROADMAP §7
  「精美 Web 控制台——仅薄层 CLI/REST」这条**非目标**。
- **理由**：CLI/配置文件操作体验是采用门槛；控制台不改变能力边界——非目标清单其余各项
  （网络模拟、分布式内核、性能承诺等）继续有效。技术路线＝方案二：前端独立工程（Node 仅构建期），
  构建产物内嵌 `duo-sim-control`，运行期仍是一条 `java` 命令、零新 Java 依赖。
- **触发条件**：无（主动立项）。
- **落点**：设计文档 `docs/superpowers/specs/2026-09-30-duo-web-console-design.md`；
  实施计划 `docs/superpowers/plans/2026-09-30-duo-web-console-m10-java-plan.md`（计划一）
  与计划二（console-ui 前端，待计划一落地后编写）。
```

`README.md` §5.3 标题下方补一句（英文版同步）：

```markdown
> **M10 起**：`duo serve` 支持[无场景参数](docs/superpowers/specs/2026-09-30-duo-web-console-design.md)以 IDLE 态启动，浏览器打开 `http://127.0.0.1:<port>/` 即 Web 控制台（场景库/画布编辑/注入/观测）。CLI 与 REST 原用法不变。
```

- [ ] **Step 4: Commit**

```bash
git add CHANGELOG.md docs/DECISIONS.md README.md README.en.md
git commit -m "docs(m10): 计划一口径回填——D16 立项登记 + CHANGELOG + README 控制台入口（测试数按实测回填）"
```

---

## 移交与执行方式

- 任务顺序严格串行：T1→T2（host 通路先行）→T3→T4（库先行）→T5→T6→T7→T8→T9→T10。
- 每任务自带 TDD 循环 + 整 reactor 回归 + 独立提交；任务间只通过「Interfaces」声明的签名耦合。
- 推荐执行方式：**本会话逐任务**（任务间接口依赖多、串行强、单模块改动，拆子代理的上下文成本高于收益；出错代价低——每任务独立提交可回退）。
- 计划二（console-ui 前端）在本计划 T10 验收后编写。
