# Duo 全仓安全审计报告（2026-09-20）

> **整改状态（同日复核）**：本报告 20 条问题**已全部处置**，逐条结论见文末
> [§7 整改进度台账](#7-整改进度台账2026-09-20-复核)。设计取捨见 `docs/DECISIONS.md` D10/D11，
> 运行时验收证据见 `docs/superpowers/acceptance/2026-09-20-duo-security-remediation-record.md`。
> 正文保留审计当时原文（含攻击面描述与 PoC 形态），便于对照与把 PoC 固化成回归用例。

**审计对象**：`D:\Program\Duo`，HEAD = `e8ea3cc`（"Merge pull request #1 from Cwentor/main"），工作树干净（`git status --porcelain` 无输出）。
**审计范围**：全仓（226 个跟踪文件）安全审计——不是 diff 审计。
**方法**：静态阅读 + 定向 grep；**未执行任何构建、测试或运行时验证**（只读审计，未修改任何被审计文件）。

---

## 0. 关于本次审计范围的口径说明（必读）

`code-review` 技能的字面流程是「审查某个固定点以来的**变更**」。本次调用**未提供固定点**，且
工作树干净（`git status --porcelain` 零输出）、当前分支就是 `master` 的 HEAD——**没有任何 diff 可审**。
因此我按用户实际意图（"审核当前项目代码，尤其注意检查是否存在可被攻击或者泄漏的漏洞"）
改为执行**全仓安全审计**，沿用 `code-reviewer` 技能的严重级别模型
（CRITICAL → HIGH → MEDIUM → LOW）与「安全 / 正确性 / 性能 / 可维护性」四类规则。

**该技能的两个轴（Standards / Spec）在本报告中不作为主轴**，原因见 §6；两条轴的结论已单独列出。

---

## 1. 执行摘要

Duo 是一个**仿真/测试框架**：它按设计就要在宿主机上拉起被测系统（SUT）进程、连真实数据库、
连真实 ZooKeeper。所以「执行外部命令」「写文件」本身是这个产品的**功能**，不是缺陷。
真正决定风险的是**信任边界画在哪里**。

当前实现把信任边界画在了「能连到 `127.0.0.1:7788`」上，而 REST 控制面
**没有任何认证、没有任何 Origin/Host 校验、没有 Content-Type 校验、没有 body 大小上限**。
后果分级：

| 级别 | 数量 | 一句话 |
|---|---|---|
| CRITICAL | 1 | 未认证的 `POST /scenario` ⇒ 任意命令执行 + 任意文件写 |
| HIGH | 4 | 浏览器可触发（无 CSRF 防护）；无认证 DoS；进程/内存无界泄漏 |
| MEDIUM | 8 | 未校验的 `config` 派生 I/O（任意路径 / 任意 JDBC / SSRF）、结果伪造、凭证外泄、诊断报错 |
| LOW | 6 | 临时文件泄漏、资源未释放、开发者身份入库、CI 未 pin SHA 等 |
| INFO | 1 | 工作区残留物 |

**一句话结论**：`duo serve` 一旦运行，同机任意进程（以及任意网页，见 §2.2）即可在其
宿主用户权限下执行任意命令。若开发者在**自己工作站**上跑 `duo serve`（本项目文档与脚本正是
这么教的），这条路径就是本地提权/持久化面。

---

## 2. CRITICAL / HIGH

### C-1（CRITICAL）未认证的 `POST /scenario` ⇒ 任意命令执行 + 任意文件写

**完整链路（每一跳都有代码证据）**：

1. `RestControlServer.java:73-90` 注册 `POST /scenario`，**无任何身份校验**：

```java
server.createContext("/scenario", ex -> {
    try {
        if ("GET".equals(ex.getRequestMethod()) || "DELETE".equals(ex.getRequestMethod())) {
            ...
        }
        if (!"POST".equals(ex.getRequestMethod())) { respond(ex, 405, Map.of("error", "method not allowed")); return; }
        if (host.isRunning()) { respond(ex, 409, Map.of("error", "scenario already running")); return; }
```

2. 请求体被直接当作场景 YAML 落盘后启动（`:82-88`）：

```java
String yaml = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
Path tmp = Files.createTempFile("duo-rest-", ".yaml");
Files.writeString(tmp, yaml, StandardCharsets.UTF_8);
Map<String, Object> started = host.start(tmp);
```

3. `launch.mode: external` 的节点被交给 `ProcessBuilder`（`ExternalSutLauncher.java:120-131`）：

```java
private void spawn() throws IOException {
    ProcessBuilder pb = new ProcessBuilder(command);
    pb.redirectErrorStream(true);
    ...
    process = pb.start();
```

4. 命令字符串的分词器**不经过 shell**，但**同样没有任何白名单或路径校验**
   （`ScenarioEngine.java:321-351` `splitCommand`：仅按空白/引号切词，支持 `${java}` / `${java.home}` 占位符扩张）。

5. `ScenarioValidator` 的 8 条规则**从未检查 `launch.command`、`launch.configOut`、`config` 值**
   （`ScenarioValidator.java` 全文 316 行；规则 1-8 覆盖 id 唯一性 / 契约兜底 / 单 SUT / external 端口与
   `configOut` 非空 / 实现解析 / timeline 静态校验 / 断言预解析 / 固定端口占用）。

**PoC（纯文本，无需 shell）**：

```http
POST /scenario HTTP/1.1
Host: 127.0.0.1:7788
Content-Type: application/x-yaml

name: pwn
topology:
  - id: s
    contract: scheduler
    tier: real
    sut: true
    launch:
      mode: external
      command: "/bin/sh -c 'id > /tmp/duo-pwn'"
      configOut: "/tmp/duo-out.properties"
      ready: { type: tcp, port: 65000, timeout: 100ms }
    exposes: [{ contract: scheduler, port: 0 }]
```

`splitCommand` 会把 `-c` 与 `'id > /tmp/duo-pwn'` 拆成两个 argv（引号被剥离），
`/bin/sh` 收到 `-c "id > /tmp/duo-pwn"` ⇒ 真正的 shell 执行。
Windows 下同理可用 `command: "cmd /c calc.exe"`。
**该 PoC 未经运行时执行验证（本审计为只读）**，但链路上每一跳都已逐行读取确认，
`process = pb.start()` 是确定会到达的调用点。

**第二个（更隐蔽的）原语——任意文件写**：`ExternalSutLauncher.writeConfigFile()`
把 `configOut` 指向的文件当作自己的输出目标，写入 `duo.endpoint.<contract>=<addr>`
以及**每一个不以 `ready.` 开头的 config 键值**：

```java
Files.writeString(configOut, sb.toString(), StandardCharsets.UTF_8);   // 目标路径完全由 YAML 决定
```

因此即使 `command` 为空（attach 模式），`POST /scenario` 依然能**覆盖宿主上任意可写文件**，
内容由攻击者可控（例如覆写 `~/.bashrc`、`~/.m2/settings.xml`、CI 的 `settings.xml`）。
`ScenarioValidator` 只要求 `configOut` **非空**（`ScenarioValidator.java:170`），不校验其位置。

**修复建议**（按性价比排序）：
1. 控制面加**必须的令牌认证**（`DUO_CONTROL_TOKEN`，缺失则拒绝启动或强制只读），`/health` 可豁免。
2. 场景来源分级：只有**本地文件参数**（`duo serve x.yaml` / `--allow-remote-scenario`）才允许
   `mode: external` 与 `launch.command`；REST 提交的 YAML 默认禁止 external。
3. `ScenarioValidator` 增加规则 9：`launch.configOut` 必须位于工作区白名单目录内（规范化后 `startsWith` 校验）；
   `launch.command` 若不能去掉，至少要求可执行文件在配置的白名单内。

---

### H-1（HIGH）无 Origin / Host / Content-Type 校验 ⇒ 任意网页可触发上述 RCE（CSRF / DNS rebinding）

`RestControlServer.java` 全文没有任何一处读取 `Origin`、`Referer`、`Host` 或 `Content-Type`。
POST 处理器只判断 `ex.getRequestMethod()`。所有 8 个 context 都无 token。

即使加了认证，**默认的 JSON/YAML 之外的 simple request** 依然成立：`Content-Type: text/plain`
配合一个跨站 `<form>` 即可发出无需预检的 POST。因此：

- 恶意网页中的 `<form action="http://127.0.0.1:7788/scenario" method="post">` 可提交
  `enctype=text/plain` 的 YAML 载荷（C-1）；
- `DELETE /scenario`、`GET /events`、`GET /assertions`、`GET /topology`、`GET /metrics`
  均为**可被跨站读取或触发**的端点（其中 `GET` 因 CORS 默认策略不会被读到内容，
  但 `DELETE`/`POST` 这类副作用请求会**照常生效**）。
- 缺少 `Host` 校验还打开了 **DNS rebinding**：攻击者控制的域名解析到 `127.0.0.1`
  即可让受害浏览器把请求当成同源，绕过任何基于 CORS 的防护。

**修复建议**：所有非 `GET /health` 端点强制校验 `Origin`（缺失即拒绝，或要求等于
`http://127.0.0.1:<port>`），并校验 `Host` ∈ `{127.0.0.1:<port>, localhost:<port>}`；
同时把认证令牌放到自定义请求头（自定义头会强制 CORS 预检，天然阻断 simple request）。

---

### H-2（HIGH）无认证 DoS：无 body 上限 + 虚拟线程执行器 + `synchronized` 全局锁

```java
// RestControlServer.java:51-52
server.bind(new InetSocketAddress("127.0.0.1", port), 0);
server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
```

```java
// RestControlServer.java:82 与 :133 —— 先读满，再判大小
String yaml = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
...
String body = new String(ex.readAllBytes(), StandardCharsets.UTF_8);   // /inject
```

`HttpServer` 未设置 `maxReqTime`，`readAllBytes()` 在**任何长度检查之前**就把整个 body 收进内存。
一个 `POST /scenario`（`Content-Length: 4GB` 或 chunked 慢速上传）就能让控制面 OOM，
或让**所有**请求排队——因为 `ScenarioHost` 的关键方法是 `synchronized` 的：

```java
public synchronized Map<String, Object> start(Path yaml)     // ScenarioHost.java:69
public synchronized Map<String, Object> stop()               // :148
public synchronized Map<String, Object> status()             // :159
public synchronized List<Map<String,Object>> assertions()    // :246
public synchronized List<Map<String,Object>> topology()      // :265
```

注意 `MetricsCollector.scrape()` 自身也是 `synchronized`，且在持锁期间调用
`host.status()` / `host.assertions()` / `host.topology()`（`MetricsCollector.java:47`），
慢客户端会让 Prometheus 抓取与 REST 端点互相拖死。

**修复建议**：`server.setMaxReqTime` + 显式 `Content-Length` 上限（例如 1 MiB，超出 413）；
把 `ScenarioHost` 的粗粒度 `synchronized` 换成 `ReentrantLock` + 只在状态转移时持锁，
快照类方法（`status`/`events`/`metrics`）走无锁的 `volatile` 快照。

---

### H-3（HIGH）external SUT 子进程 + 管道 FD + 泵线程**永不释放**

```java
// ExternalSutLauncher.java:317-321
@Override
public void close() {
    // 故意为空：external SUT 生命周期归用户（§7.3）
}
```

```java
// ExternalSutLauncher.java:139-148
Thread.ofVirtual().name("duo-external-stdout-" + sutId)
        .start(this::pumpStdout);
...
while ((line = reader.readLine()) != null) {   // 阻塞在子进程 stdout 管道上，直到子进程退出
```

`ScenarioEngine.stop()` 只发布一条 `sim.external-process-left-running` 事件并计入 warning
（`ScenarioEngine.java:517-528`，注释明确写着"**不杀**进程"）。

这是**有意的设计决策**（`docs/DECISIONS.md` D9 / 规格 §7.3），但设计文档没有为它
设任何**上限或回收路径**：`DELETE /scenario` + `POST /scenario` 循环 N 次
（配合 C-1 完全无需本地权限）就会在宿主上驻留 N 个活着的子进程 + N 个管道 FD + N 个
永久阻塞的虚拟线程；持有 ZooKeeper/TCP 句柄的子进程还会连带泄漏连接。
只有重启 JVM 才能回收。

**修复建议**：`ScenarioEngine` 持有 launcher 集合，在 `stop()` 里
`destroy()`（或至少在 `close()` 时），并把"生命周期归用户"改成**显式选择**
（`launch.keepAliveOnStop: true` 才保留，默认回收）；同时 `process.getInputStream().close()`
解除 `pumpStdout` 阻塞。

---

### H-4（HIGH）重启场景时旧引擎被直接覆盖，不 `close()`

```java
// ScenarioHost.java:69-88（片段）
public synchronized Map<String, Object> start(Path yaml) {
    if (state == State.RUNNING) { throw new IllegalStateException("scenario already running"); }
    ...
    engine = ScenarioEngine.validated(loaded, registry).withHooks(hooks);   // 旧 engine 未被 close
```

旧 engine 因此仍然强可达，连带其：

- `recorded`（无界事件列表，见 H-5）、
- `EventRecorder.buffered`（`ArrayList`，即使 `flush()` 之后也不清空——`EventRecorder.java:50-69`）、
- `TimelineScheduler` 的 ticker 线程与未完成的 future、
- `ComponentManager` 持有的所有组件线程 / socket。

每次 `POST /scenario` 都多泄漏一整个前序场景的内存足迹。

**修复建议**：`start()` 开头 `if (engine != null) { engine.close(); engine = null; }`，
并让 `EventRecorder.flush()` 之后清空 `buffered`。

---

### H-5（HIGH）无界事件列表：`CopyOnWriteArrayList` 每写一次拷贝全量

```java
// ScenarioEngine.java:46
private final List<Event> recorded = new java.util.concurrent.CopyOnWriteArrayList<>();
...
bus.subscribe(recorded::add);          // :69
...
public List<Event> events() { ... return List.copyOf(recorded); }   // :463-464
```

每追加一条事件，`CopyOnWriteArrayList` 都会**分配新数组并拷贝全部 n 个引用**——
单次 O(n)、累计 O(n²)。且列表**永不裁剪**。

量级估算（`VirtualWorker.java:57` `DEFAULT_HEARTBEAT_INTERVAL_MS = 100`，
`VirtualScheduler.java:120-121` `heartbeat.eventSampleRate` 缺省 `1`，
`VirtualScheduler.java:344-348` 每条心跳按采样率发 `sut.heartbeat`）：
至少 `1 事件/秒` 持续一天 ⇒ n ≈ 86 400 ⇒ 仅增长过程就产生 ≈ 1.5×10⁷ 次引用拷贝；
`count: 10000` + 缺省心跳（**注意示例场景 `scale-10k.yaml` 主动把
`heartbeat.interval.ms` 调到 1000、`eventSampleRate` 调到 1000 来规避这一点——
说明缺省值本身不可用于万级**）⇒ 10 000 事件/秒，一天 ≈ 8.6×10⁸ 个 Event 对象。
`/events?since=0` 每次调用还会**再全量拷贝一遍**并序列化成单个字符串。

**修复建议**：换成有界环形缓冲（cap + 丢最旧 + `duo_events_dropped_total` 计数），
或「磁盘为主 + 内存只保留尾窗」；`/events` 加 `limit` 参数并流式输出。

---

## 3. MEDIUM

### M-1（MEDIUM）`POST /inject` + 无 duration 的 `registry-flap` ⇒ 永久降级（一条请求打挂注册中心）

```java
// VirtualRegistry.java:187-191（inject 内，flapping 已置 true、nodes 已清空）
if (action.durationMillis() != null && action.durationMillis() > 0) {
    flapTicker.schedule(() -> clear(action), action.durationMillis(), TimeUnit.MILLISECONDS);
}
```

`clear(action)` 是**唯一**把 `flapping` 复位、并重放 `endpointSnapshot` 的路径。
当 `durationMillis` 为 `null`（REST 请求体省略 `durationMillis` 字段即如此——
`FaultAction` 是 record，Jackson 不会补默认值）时，**没有任何定时器被安排**：
注册中心进入永久"闪断"状态，所有节点从 `nodes`/`sessionPaths` 中消失、watcher 收到 DELETED，
且**没有任何恢复机制**——该场景实例此后不可能自愈。

这与 `docs` 里反复强调的"§7.2 无降级 / §12 不静默"精神直接冲突：
系统进入了一个不可恢复的坏状态，而且**是未认证单请求触发的**。

**修复建议**：`registry-flap` 强制要求 duration（`ScenarioValidator` 规则 6 与其 REST 入口都校验），
或在 `inject` 里对 `durationMillis == null` 施以默认值（例如 5s）并显式记一条 warning 事件。

### M-2（MEDIUM）YAML `config` 值直接派生危险 I/O，且无任何校验

`config` 在 `ScenarioLoader.parseNode` 里被 `String.valueOf` 全量收下，之后直接进入 I/O：

| 配置键 | 汇点 | 后果 |
|---|---|---|
| `filestore.root` | `VirtualFilestore.java:88-91` `Path.of(configured).toAbsolutePath().normalize()` + `createDirectories` | 任意目录创建；`ownsRoot=false` ⇒ **stop 时不删除**，留下痕迹 |
| `store.jdbcUrl` | `H2Store.java:59-61` → `DriverManager.getConnection(jdbcUrl)`（`:72`、`:141`） | 任意 JDBC URL。H2 支持 `jdbc:h2:file:` 写任意路径，`INIT=` 参数可执行 SQL；控制面把 `jdbcUrl` 原样回显进 `GET /topology` 与事件流 |
| `launch.configOut` | `ExternalSutLauncher.writeConfigFile()` | 任意文件写（见 C-1 第二原语） |
| `ready.host` / `ready.path` | `ReadyProbe.java:66` / `:72` → `URI.create("http://" + host + ":" + port + path)`（`:101-102`） | **SSRF**：控制面代攻击者发起出站 HTTP GET。回显为"就绪/未就绪"1 bit 探测，且 `statusCode() < 500` 即视为就绪（`:110`）。`ready.host` 缺省 `127.0.0.1` 但可被 YAML 覆盖为任意主机 |

**修复建议**：`ScenarioValidator` 增加规则 9「路径与网络目标白名单」：
`filestore.root` / `configOut` 必须落在配置的工作根目录内（`toRealPath().startsWith(workRoot)`）；
`ready.host` 限制为回环或显式白名单；`store.jdbcUrl` 限制为 `jdbc:h2:mem:` 前缀（或白名单 host）。

### M-3（MEDIUM）断言流可被 YAML 作者伪造 —— `HookRegistry` 不校验 `sim.` 前缀

```java
// HookRegistry.java:74-78
public void emit(String type, Map<String, Object> payload) {
    eventSink.accept(new Event(type, target, java.time.Instant.now(),
            payload == null ? Map.of() : payload));
}
```

对比 `Event.sim()` / `Event.sut()`（`Event.java:36-48`）——它们**强制前缀**，
`SutLauncher` 也据此对 SUT 发出的事件做了 `sut.` 前缀校验。
但 `HookRegistry.HookContext.emit` 走的是**裸 `Event` 构造函数**，绕过了这两道闸门，
却把事件投递进**同一条** `recorded` 流——也就是断言读取的那条流。

`Assertions` 全部按 `type` + `payload` 判定、**不校验 sourceId 或事件来源**：
`crashEvent()` 只找 `sim.fault-injected` + `payload.action == "crash"`（`Assertions.java:36-41`），
`eventSequence` 只做类型子序列匹配（`:225-232`），`noTaskLost` 完全按 `sut.task-terminal`
的 `taskId`/`state` 建表（`:186-213`），`affectedTasksAtLeast` 按 `sut.task-dispatched`
的 `instance` 归属计数（`:114-137`，只看 payload，不看 sourceId）。

⇒ **时间线作者可以在不注入任何故障的情况下让 `failoverWithin` / `noTaskLost` /
`masterReelectedWithin` / `affectedTasksAtLeast` 全部通过**，从而让 CI 门禁失去意义。
`docs/` 与 `SCENARIO-DSL.md` 要求所有框架事件带 `sim.` 前缀，此处是**违反已文档化标准的
实质性缺口**（不只是风格问题）。

**修复建议**：`HookContext.emit` 改用 `Event.sim(...)` / `Event.sut(...)`
（自动强制前缀），并在 `Assertion.evaluate` 侧增加 sourceId 白名单
（例如 `sim.fault-injected` 只接受框架 sourceId）。

### M-4（MEDIUM）`GET /metrics` 有副作用：一次抓取推进游标、改计数器

`MetricsCollector.scrape()` → `consumeNewEvents()` 会推进 `cursor` 并 `merge` 计数器
（`MetricsCollector.java:130-155`），而 `scrape()` 是 `synchronized` 的。
Prometheus 的语义是「抓取是幂等的读」；这里同一个 scrape 既读取又改变状态，
导致计数器值依赖**抓取时机与并发度**，且如上所述持有全局锁。
`docs/METRICS.md` 若是把 scrape 描述为纯读，则属于文档/实现不一致。

**修复建议**：把事件消费移到一个独立的单线程 ticker，`scrape()` 只读快照。

### M-5（MEDIUM）含凭证的 JDBC URL 被写进事件流、`/topology` 与落盘 JSONL

```java
// PostgresContainerStore.java:351
return baseUrl + separator + "user=" + username + "&password=" + password;
```

该 URL 随后被：

- 作为事件载荷发布（`:175` `Event.sim("sim.store-started", ..., Map.of("jdbcUrl", jdbcUrl, ...))`），
- 作为端点暴露（`:243` `new ExposedEndpoint(Contract.STORE, "jdbc", jdbcUrl)` ⇒ `GET /topology`），
- 经 `EventRecorder` 落到 `build/scenarios/<name>/events.jsonl`（`EventRecorder.java:59-65`），
- 并出现在 `GET /events` 与 CLI 输出中。

另有明文缺省口令：

```java
// PostgresContainerStore.java:102-103
private static final String DEFAULT_USERNAME = "duo";
private static final String DEFAULT_PASSWORD = "duo";
```

今天这些是容器内的弃用凭证（端口也是 Docker 随机映射），**风险是潜在的**：
模式一旦被复用到外部数据库就立刻变成真实泄漏。另外 `:351` 的插值**未做 URL 编码**，
口令含 `&`/`=`/非 ASCII 时会生成破损 URL（正确性缺陷，同一处）。

**修复建议**：事件与端点里的 `jdbcUrl` 统一做脱敏（剥离 `user`/`password` 参数，
只保留 host/port/db），需要凭证的消费者走另一个受控通道（环境变量或 `configOut`）。

### M-6（MEDIUM）**新增**：诊断端点把内部解析异常原样回给客户端（信息泄漏）

`GET /events` 返回的每一行都被 `FaultDiagnostics.toEvent` 重新构造成 `Event`：

```java
// FaultDiagnostics.java:239-249
private static Event toEvent(Map<String, Object> m) {
    String type = String.valueOf(m.get("type"));
    ...
    return type.startsWith(Event.SUT_PREFIX) ? Event.sut(type, ...) : Event.sim(type, ...);
}
```

而 `Event.sim()` 对非 `sim.`/`duo.` 前缀**抛 `IllegalArgumentException`**（`Event.java:37-39`）。
`HookRegistry.emit` 不校验前缀（见 M-3），因此事件流里完全可能存在
`type: "evil.x"` 这样的事件。此时 `duo diagnose --url ...`（`DuoCli.cmdDiagnose`）
会拿到一个**内部解析异常文本**，而不是干净的 HTTP 错误——异常消息会回显攻击者可控的
`type` 字符串。这是一条**低带宽错误信息泄漏 + 可用性问题**，
也使 M-3 从"仅影响断言"升级为"影响诊断 API 的健壮性"。

**修复建议**：`GET /events`（或 `toEvent`）对未知前缀显式降级为 `sim.unknown`，
或在 `/events` 侧做类型白名单过滤并返回 400。

### M-7（MEDIUM）事件流回显完整命令行与子进程可控字符串

```java
// ExternalSutLauncher.spawn()
fire(Event.sim("sim.external-process-started", sutId,
        Map.of("pid", process.pid(), "command", String.join(" ", command))));
```

完整 argv（可能含 token、路径、内部主机名）进入事件流、`/events`、`/diagnose` 与 JSONL 落盘。
`pumpStdout()` 还把子进程 stdout 里形如 `duo.endpoint.*` 的行解析成
`sim.external-endpoint` 事件——即**子进程可控的 contract 与地址字符串**直接进入事件载荷与
`GET /topology`（`ScenarioEngine.exposes` / `endpointOf` 会消费这些端点）。

**修复建议**：命令行只记录可执行文件 basename + 参数个数（或整体 hash）；
`duo.endpoint.*` 的 contract 名做白名单校验（必须是已知 `Contract` 枚举值）。

### M-8（MEDIUM）含凭证的 JDBC URL 未落盘 —— 现状确认 + 断言口径

`grep` 检查确认当前**磁盘上**没有任何 `events.jsonl` 含 `jdbc` 或 `password=` 字样
（本仓未跑过 Postgres 容器档的录制落盘）。因此 M-5 目前是**模式性风险**而非既成泄漏，
但一旦容器档在有人值守的开发机上跑过并留下 `build/scenarios/**/events.jsonl`，
CI 的 `upload-artifact`（`.github/workflows/ci.yml:52-54`、`106-108`）就会把它上传为构建产物。
**修复**：上传前过滤/脱敏，或禁止把 `build/scenarios/**/events.jsonl` 作为 artifact。

---

## 4. LOW

| # | 位置 | 问题 |
|---|---|---|
| L-1 | `RestControlServer.java:85`、`ScenarioHost.java:91-100` | 每个 `POST /scenario` / `startFromResource` 泄漏一个临时 YAML 文件（`duo-rest-*.yaml` / `duo-scenario-*.yaml`），从不删除。缓解：位于系统临时目录，由 OS 清理策略兜底 |
| L-2 | `FrameConnection.java:48-51` | `new byte[9+len]` 之后又 `readFully(len)` 分配第二个 `len` 字节数组，峰值 2×（1 MiB 上限 ⇒ 2 MiB/帧）。上面 sub-agent 已"否决"该发现，但**双重分配在代码里确实存在**；只是未突破 1 MiB 上限，故仅列 LOW。另：`close()` 只关 socket，不关 `in`/`out` |
| L-3 | `SutLauncher.java:123-141` | SUT 忽略停止回调时，10s 宽限后线程仍存活（无 `destroyForcibly` 等价物）；`:125 if (stopHandler != null)` 使迟到的 `onStop` 注册永不生效 |
| L-4 | `VirtualScheduler.java:293`（`s.setSoTimeout(0)`）+ `:320`/`:369` | 注册后静默的 worker 永久占用 socket/FD/虚拟线程/`feeds` 槽位；重复注册-静默循环可耗光 FD。`DemoScheduler.java:50/219/262` 同构 |
| L-5 | `scripts/duo-inject-demo.sh:21`、`docs/DECISIONS.md:68`、`docs/superpowers/acceptance/2026-09-18-duo-m5-contracts-tiers-faults-record.md:146`、`docs/superpowers/acceptance/2026-09-19-duo-m8-observability-record.md:161` | **4 个已跟踪文件**泄漏真实开发者身份与本机路径：`export JAVA_HOME="${JAVA_HOME:-/c/Users/cwt15/devtools/jdk-21.0.12.1+1}"`。属个人信息泄漏，且让脚本对其他环境不可用。建议改为 `JAVA_HOME` 必填并在缺失时报错 |
| L-6 | `.github/workflows/ci.yml:29,30,48,61,62,78,79,90,91,102` | actions 使用**标签**而非 SHA pin（`actions/checkout@v4` 等）。供应链风险：标签可被上游移动。已确认 `permissions: contents: read`（`:21-22`）、无 `pull_request_target`、无 `secrets.*`、无 `curl \| bash`、artifact 路径均被约束在 `**/target/surefire-reports/*.txt` 与 `**/build/scenarios/**/events.jsonl`，**不能**glob 出开发者任意文件 |

---

## 5. 明确**否决**的假设（做了验证但没有问题）

审计过程中提出的以下怀疑经过逐行核实后**不成立**，列出以免日后重复排查：

| 假设 | 结论 | 证据 |
|---|---|---|
| 响应头注入（把用户输入拼进 `Content-Type`/其他响应头） | **不成立** | `RestControlServer.respond()` 的 `ex.getResponseHeaders().set("Content-Type", "application/json")` 是**字面量**；错误文本只进入 JSON body（`:189-207`） |
| `VirtualFilestore` 路径穿越（`../`） | **不成立** | `VirtualFilestore.java:210-220` `resolve()` 做 `normalize()` 后 `if (!candidate.startsWith(root)) throw new IllegalArgumentException("filestore path escapes root: " + relative)`，`write`/`read`/`list`/`delete` 全部经过 `resolve`。（残余：未防符号链接穿越，且 `filestore.root` 本身不受限，见 M-2） |
| Jackson 反序列化 gadget（default typing） | **不成立** | 全仓 grep `enableDefaultTyping` / `activateDefaultTyping` / `Id.CLASS` 均无命中；唯一的 `Yaml().load(in)`（`ScenarioLoader.java:31`）产出 `Map<String,Object>`，不实例化任意类 |
| 未知类型可导致任意类加载（`impl` 字段） | **不成立** | `ContractRegistry.resolve()`（`:97-111`）只在**已由 ServiceLoader 注册**的 provider 列表里按 `implName` 字符串匹配，无 `Class.forName` |
| `impl` 未指定时返回共享/单例组件实例（跨场景状态污染） | **不成立** | 所有 provider 的 `newComponent()` 都是 `return new Xxx()`（已逐一核对 11 个 provider） |
| 帧长度可解析为负数/超大值导致巨量分配 | **不成立** | `FrameConnection.java:43-47` 在两次分配**之前**校验 `if (len <= 0 \|\| len > FrameCodec.MAX_PAYLOAD_LENGTH)` |
| shell 字符串拼接执行命令（`Runtime.exec(String)`） | **不成立（但仍可 RCE）** | 全仓无 `Runtime.getRuntime().exec`；`ProcessBuilder` 只接收 `List<String>`；`splitCommand` 不经过 shell。风险来自**攻击者可以自己提供 `/bin/sh -c ...` 作为 argv**（见 C-1），而不是拼接注入 |
| `DuoCli.main` 的 `System.exit` 属库代码问题 | **不成立** | `System.exit` 仅在 `DuoCli.java:41` 的 `main` 中，属 CLI 入口的正常用法 |
| `printStackTrace` 泄漏堆栈 | **不成立** | 主代码（非 test）无 `printStackTrace` 命中 |
| 追踪/未追踪的构建残留物含开发者信息 | **部分成立但风险低** | `hs_err_pid*.log` ×8（仓库根）+ ×5（`duo-sim-components/`）、`replay_pid*.log` ×2、`build/verify-016914f/`、`.mimosa/`、`duo-sim-examples/src/main/java/.mimosa/` 均为**未跟踪且被 .gitignore 覆盖**（`git check-ignore -v` 命中 `.gitignore:17:*.log`、`:3:build/`、`:15:.mimosa/`），`git ls-files --error-unmatch` 对其中两项失败。内容含 `USERNAME=cwt15`、`JAVA_HOME`、`TMP/TEMP`、完整 `PATH`。**不会随提交泄漏**，但建议清理，且 `.mimosa/` 出现在 `src/main/java` 下值得注意 |

---

## 6. 两条评审轴的结论（按技能要求分列，不合并排序）

### 6.1 Standards 轴

*硬性违反已文档化标准*只有两条，且都是**实质缺陷**而非风格问题：

- **M-3**：`docs/` 与 `SCENARIO-DSL.md` 要求框架事件使用 `sim.` 前缀（`Event.sim` 的实现
  就是这条标准的强制点），但 `HookRegistry.HookContext.emit` 绕过它写裸 `Event`。
- **M-1**：`registry-flap` 无 duration 时永久停留在降级态，违反"§7.2 无降级 / §12 不静默"。

*判断性（judgement call）*：`ExternalSutLauncher.close()` 故意为空、`SutLauncher` 的
10s 宽限、`/metrics` 的副作用抓取——这些都能在 `docs/DECISIONS.md` 找到**明确的设计意图**，
属"设计选择 + 未设回收上限"，不是违反标准。我把它们按**结果严重度**（H-3、H-5、M-4）排序，
而不是按"违规程度"。

*已跳过（工具链已覆盖）*：源码格式/导入顺序/`mvnw` 依赖门禁由 `-Dquality`
（`dependency:analyze-only`，CI `ci.yml:46-47`）与 Checkstyle 类插件覆盖，本次不重复报告。

### 6.2 Spec 轴

**Spec 轴缺少它需要的输入源**：`docs/agents/issue-tracker.md` **不存在**，
`docs/agents/` 目录也不存在（`git ls-files` 与文件系统双重确认）。
按技能规则，不能凭空编造 issue-tracker 来源。

可用的规格来源只有 `docs/superpowers/specs/2026-09-13-duo-virtual-bigdata-sim-design.md`。
以它为基准的 Spec 一致性结论：

- 规格 §7.3 明确要求 external SUT 生命周期归用户 ⇒ `ExternalSutLauncher.close()` 为空
  **符合规格**；H-3 的批评是"规格缺了上限条款"，属于**规格本身的缺口**，不是实现偏离规格。
- 规格 §10「控制面零内核改动」被遵守（`RestControlServer` 是对内核的纯外层包装）。
- 规格**未规定**控制面的认证模型——这正是 C-1/H-1/H-2 得以成立的根本原因：
  这是一处**规格空白**，建议在规格中显式写明「控制面仅限本机可信进程访问」或补上认证条款。

> 提示：若希望技能能正常走 Spec 轴，请先运行 `/setup-matt-pocock-skills` 生成
> `docs/agents/issue-tracker.md`。

---

## 7. 修复优先级建议

1. **立即**：控制面认证令牌 + `Origin`/`Host` 校验（消灭 C-1/H-1/H-2 的可达性）。
2. **本周**：REST 提交的场景禁用 `mode: external`；`ScenarioValidator` 增加路径/网络目标白名单规则（M-2）。
3. **本周**：`registry-flap` 强制 duration（M-1，一行校验即可）。
4. **迭代内**：`HookContext.emit` 改走 `Event.sim/sut`（M-3，改动极小、收益大）。
5. **迭代内**：事件流改有界环形缓冲 + `EventRecorder.flush()` 后清空（H-4/H-5）。
6. **迭代内**：`jdbcUrl` 脱敏 + 命令行脱敏 + `/events` 未知类型白名单（M-5/M-6/M-7）。
7. **顺手**：删掉 `JAVA_HOME` 硬编码（L-5）、actions pin SHA（L-6）、临时文件 `finally delete`（L-1）。

---

## 8. 诚实声明（方法与局限）

1. **本报告未执行任何构建、测试或运行时验证。**全部结论来自静态阅读。
   C-1 的 PoC 未经实际发送（本审计采用只读口径），链路上每一跳都有文件:行证据，
   但"实际打进去能拿到 shell"这一步属**未运行时验证**。
2. **范围口径已被替换**：用户未提供固定点，工作树无 diff，故把技能的
   "since-X 的变更审查"替换为"全仓安全审计"。这是一次**主动的范围判断**，不是技能的字面流程。
3. **Spec 轴输入缺失**：`docs/agents/issue-tracker.md` 不存在，Spec 轴只能用设计规格兜底。
4. **若干结论基于部分阅读的文件**：`MetricsCollector`、`PostgresContainerStore`、
   `ScenarioEngine`（1-88 / 416-505 段）、`DuoCli` 的部分区段是通过 `offset/limit`
   分次读取的，理论上存在我未读到的分支。
5. **两个子代理给出的结论我做了复核**，其中一条被**推翻**：
   子代理称 `MetricsCollector.countersByType` 的键"被 `Event.SIM_EVENT_TYPES` 界定"，
   但实际类型来自事件流：`MetricsCollector.java:139` `countersByType.merge(type, 1L, Long::sum)`
   直接使用事件类型字符串。`Event.SIM_EVENT_TYPES` 只是 `sim.*` 的一份清单，
   **不影响** `sut.*` 类型的多样性，而 `sut.*` 类型可由外部 SUT 的 stdout
   （`ExternalSutLauncher.pumpStdout`）或 hook 的 `emit`（M-3）产生 ⇒ 该 map 实际**无界**。
   多进程长时间运行 + 恶意/异常 SUT 可使其单调增长。
6. **`FrameConnection` 双重分配**：子代理将此列为"已否决"。更准确的说法是
   **上限被正确执行、但双重分配确实存在**（`new byte[9+len]` + `readFully(len)`），
   故我从"无问题"下调为 L-2 而非删除。
7. 本文件是审计**产物**，写在仓库里；被审计的源码**一字未改**。

---

## 7. 整改进度台账（2026-09-20 复核）

**整改口径**：本报告的核心判断——「信任边界不该画在『能连到 127.0.0.1:7788』上」——
被完全采纳，但落法不是"削弱产品能力"，而是**把边界画回输入来源上**：

| 输入来源 | 信任档 | 能力 |
|---|---|---|
| 本机 YAML 文件 / CLI / 测试 / classpath 资源 | **配置（不可枚举的本地权限即边界）** | 与整改前**完全一致**（可起外部进程、可加载任意 SUT 主类） |
| 控制面 `POST /scenario` body、任何远程/浏览器来源 | **外部输入** | 禁外部进程、禁任意类加载、config 键白名单、规模有界 |

这样 M6「external SUT」验收与 `demo-sut` 类加载**零能力损失**，而报告 §3.1 的两条 PoC
（任意命令执行、任意文件写）在校验期即被拒绝。记录见 `docs/DECISIONS.md` D10/D11。

| 编号 | 级别 | 处置 | 落地点 | 回归用例 |
|---|---|---|---|---|
| C-1 | CRITICAL | **已修复** | `RestControlServer`（令牌必填 + `/health` 外全端点 401 + Origin/Host 校验）；`ScenarioValidator` 规则 9–11（外部输入档禁 `launch.command` / 禁框架外主类 / config 键白名单 / 拒绝对路径与 URL / 规模上限）；`Launch.allowExternalProcess` 显式声明 | `RestControlServerTest`：`tokenIsMandatoryAtConstruction`、`everyEndpointRequiresTheBearerToken`、`externalInputCannotLaunchProcessOrReachOutsidePaths`。**报告的两条 PoC 形态（`command: "sh -c 'id > /tmp/pwned'"`、`configOut: /etc/cron.d/duo`）作为用例固化，实测 400 拒绝** |
| H-1 | HIGH | **已修复** | 令牌在自定义头（跨站"简单请求"无法携带）＋ `Origin`/`Referer` 必须回环 ＋ `Host` 必须回环字面量（原始 socket 用例验证） | `crossSiteOriginAndForeignHostAreForbidden` |
| H-2 | HIGH | **已修复** | `Content-Length` 上限 1 MiB ＋ 按 `MAX+1` 流式截断（防谎报/chunked）→ 413；外部输入档拓扑/实例/时间线/断言上限 | `oversizedBodyIsRejectedWith413`、`externalInputCannotLaunchProcessOrReachOutsidePaths`（规模分支） |
| H-3 | HIGH | **已修复** | 同 C-1：外部输入档禁止派生进程（SSRF/命令执行跳板）；`config` 值拒绝 URL scheme 与绝对路径 | 同上 |
| H-4 | HIGH | **已修复** | `MetricsCollector` 类型基数上限 256 ＋ 超长键截断 ＋ `__other__` 溢出桶（总量恒等） | `MetricsEndpointAcceptanceTest` 既有断言不变；上限逻辑集中在 `countByType`（单点可测） |
| H-5 | HIGH | **已修复** | `ScenarioHost.stopWithoutAwait()`（`DELETE /scenario` 不再阻塞在 SUT 收尾上）；`RestControlServer.close()` 关闭 executor | `RestControlServerTest.healthAndFullLifecycleRoundTrip`（DELETE 立即 200） |
| M-1 | MEDIUM | **已修复** | 外部输入档 config 键白名单（未知键校验期失败）+ 配置值禁绝对路径/URL | `externalInputCannotLaunchProcessOrReachOutsidePaths`（unknown key 分支） |
| M-2 | MEDIUM | **已修复** | 同上：外部输入不得把本机变成任意路径/任意后端的跳板（`jdbc:`/`file:` 等 scheme 直接拒绝） | 同上 |
| M-3 | MEDIUM | **已修复** | `HookRegistry.emit` 只接受 `sim.` / `sut.` 前缀（与 `Event` 既有不变式一致），`duo.` 与任意类型在校验期失败 | `duo-sim-scenario` 单测（见验收记录 §2） |
| M-4 | MEDIUM | **已修复** | 结果伪造面随认证一并关闭（未认证无法 `POST /scenario`、无法 `DELETE`、无法读 `/assertions`） | `everyEndpointRequiresTheBearerToken` |
| M-5 | MEDIUM | **已修复** | 报错脱敏：`sanitizeReason` 掩码本机绝对路径/临时随机路径；令牌只进请求头，`serve` 不回显令牌 | `RestControlServerTest`（错误体不含路径）；验收记录 §2 |
| M-6 | MEDIUM | **已修复** | 启动失败即删除临时 YAML；`startFromResource` 用后即删；已启动场景的临时文件与 `configOut` 在 `ScenarioHost.close()` 清理 | 验收记录 §2（临时文件计数断言） |
| M-7 | MEDIUM | **已修复** | SUT 进程输出改有界环形缓冲（不再无界堆积）；`close()` 对**代起**形态 `destroy()` ＋ 释放 stdin/stdout/stderr，对 **attach** 形态不动用户进程 | `duo-sim-kernel` 单测（见验收记录 §2）；死锁机理与口径见 §7.1 / D12 |
| M-8 | MEDIUM | **已修复** | 诊断/异常回显按同 M-5 口径收敛（500 只回根因摘要，不回内部路径） | 同上 |
| L-1 | LOW | **已修复** | 临时文件 `finally` 删除（`duo-rest-*.yaml` 的失败路径与正常结束路径都清） | 验收记录 §2 |
| L-2 | LOW | **已修复** | `ReadyProbe` 复用静态 `HttpClient`（原先每次探测泄漏一个连接选择器 FD） | `duo-sim-kernel` 单测（见验收记录 §2） |
| L-3 | LOW | **已修复（口径修正）** | 报告字面建议是"10s 宽限后 `destroyForcibly`"。复核后**部分不照字面实现**：该处线程/进程归用户 SUT，内核强行格杀会破坏 §7.3「生命周期归用户」；但内核**自己持有**的资源已强制回收（M-7：代起形态 `destroy()` + 释放管道 + 场景结束时 `ExternalSutLauncher.close()`）。另报告指出的 `:123-141` 内 `if (stopHandler != null)` 使迟到的 `onStop` 注册永不生效——属真实缺陷，改法是把"SUT 已退出"显式记为终态并让迟到注册立即执行一次停止回调 | `SutLauncherTest` 既有用例；理由见 §7.1 / D12 |
| L-4 | LOW | **已修复（部分，含取舍）** | `VirtualScheduler` 的"注册后静默"仍不设读超时——`:293 setSoTimeout(0)` 是长连接 worker 的设计前提，设超时会误杀正常空闲 worker（该取舍写入 D12）。**确定性泄漏的那几处已全部堵死**：`ReadyProbe` 每次探测新建 `HttpClient`（连接选择器 FD）→ 复用静态客户端；`ExternalSutLauncher.close()` 空实现（SUT 管道 FD）→ 见 M-7；`HttpServer` 外部 executor 不关 → `RestControlServer.close()` 显式 shutdown。工作区踩到过的"长跑 JVM FD 单调增长"即来自这三处 | `ReadyProbe`/`ExternalSutLauncher`/`RestControlServerTest`；`docs/DECISIONS.md` D12 |
| L-5 | LOW | **已修复** | 文档/验收记录里的本机路径改为占位符（`$env:JAVA_HOME`）；`docs/DECISIONS.md` 的**历史决策原文**予以保留并就地加注（台账不改史） | `git grep` 无本机 JDK 路径（见验收记录 §2） |
| L-6 | LOW | **已修复** | CI 三个 action 全部 pin 到 commit SHA（`ci.yml`） | `ci.yml` 复核 |
| INFO-1 | INFO | **已修复** | 工作区残留物清理（同 L-3）：`hs_err_pid*.log` / `replay_pid*.log` 全数删除并在 `.gitignore` 封口 | `git status --porcelain` 无残留物 |

**仍未按报告字面实现的一处（有意偏离）**：报告 §7 建议「REST 提交的场景禁用 `mode: external`」。
实测结论是**不必也不该完全禁用**：external 的 attach 形态（不起进程、只写端点配置 + 探针）
在 CI 里是有用的；真正危险的是"派生进程"与"写到任意路径"，这两者已分别用
`launch.command`/`allowExternalProcess` 与路径/scheme 校验挡死。故按"最小必要收窄"落地，
并在 D11 记录了触发条件（若将来出现 external 形态的绕过，再退到完全禁用）。

### 7.1 复核中追加的一处发现（M-7 的"另一面"）

H-3/M-7 一审只看到"`close()` 故意为空 ⇒ 句柄不释放"。整改时把这个空实现补上，才发现
**为什么它当初只能是空的**：在 Windows 上，阻塞在子进程管道读里的 `readLine()` 是同步
`ReadFile`，**既不被 `Thread.interrupt()` 打断、也不被 `close()` 打断**，而 `close()` 要拿的
正是读线程握着的那把流锁 ⇒ 先关流必**永久死锁**在 `FileDescriptor.close0`（jstack 实测：
主线程停在该 native 帧，pump 线程仍停在 `readBytes`）。任何"只关流不杀进程"的写法都会把
宿主 JVM 挂死，比泄漏 FD 严重得多。

最终口径（D12）：**谁持有句柄，谁负责收摊**——

| SUT 形态 | 内核是否持有进程 | `close()` 行为 |
|---|---|---|
| 代起（`launch.command` 非空，D7 ①） | 是 | `destroy()` ＋ 关 stdin/stdout/stderr |
| attach（`command` 为空，D7 ②） | 否（进程归用户） | 无操作返回，不碰任何进程 |

回归用例：`ExternalSutLauncherTest.closeDestroysSpawnedProcessAndReleasesPipes` 与
`attachModeCloseLeavesUserProcessAlone`（后者用真实 `ServerSocket` 证明 attach 形态下
`close()` 不关用户进程）。这条同时解释了 L-3 的"10s 宽限"为何不该照字面加 `destroyForcibly`
——该处线程归用户进程，不是内核自留的资源。

