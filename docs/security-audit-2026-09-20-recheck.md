# 审计报告问题复核（2026-09-20）

> **复核对象**：`docs/security-audit-2026-09-20.md` 的全部 20 条发现（C-1、H-1..H-5、M-1..M-8、L-1..L-6、INFO-1）
> 以及该报告 §7「整改进度台账」对该 20 条的处置声明。
> **复核者立场**：独立核验，不做橡皮图章。报告的结论、报告对自己的更正（§5 明确否决的假设 / §8 诚实声明）、
> 以及整改台账的每一条处置声明，都被当作**待证命题**。
> **证据口径**：
> - 审计期代码 ⇒ `git show e8ea3cc:<path>`（审计报告描述的那棵树，HEAD 为 `e8ea3cc`）。
> - 整改后代码 ⇒ 工作树 HEAD `431e58d`。
> - 本文只写我**亲自读到**的代码与行号。凡未读到的，一律记为「无法判定」而非「推定成立」。

---

## 0. 复核结论摘要

| 判定 | 条数 | 编号 |
| --- | --- | --- |
| **确认**（复核成立，原报告描述准确） | 16 | C-1、H-1、H-2、H-3、H-4、H-5、M-1、M-2、M-3、M-5、M-6、M-7、L-1、L-3、L-5、L-6 |
| **部分确认 / 需更正**（结论成立但描述或分级有偏差） | 3 | M-4、M-8、L-2 |
| **无法判定**（证据不足或与代码现状不符） | 1 | L-4 |
| **INFO-1**：确认（但报告自身描述有误，见 §4） | — | INFO-1 |

**整改台账的复核结论（关键）**：§7 台账 20 行中有 **7 行的「落地点」与原始发现的主题不对应**
（H-4、H-5、M-4、M-5、M-6、M-7、L-2 的编号被贴到了另一件事上）。其中部分是**真修复但编错号**，
部分是**用相邻加固顶替原发现**。逐条见 §6。这是本次复核最重要的发现，详见 §6.0。

---

## 1. C-1（CRITICAL）：`POST /scenario` 未认证 → 任意命令执行 + 任意文件写

**判定：确认。** 证据链每一步都在审计期源码里读到了原文。

| 环节 | 审计期证据（`e8ea3cc`） | 复核 |
| --- | --- | --- |
| ① 无认证 | `RestControlServer.java` 的 `start()` 在 `:51` `HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0)`、`:52` `setExecutor(Executors.newVirtualThreadPerTaskExecutor())`；全类无任何 `Authorization`/`Origin`/`Host` 读取 | **确认** |
| ② body → 临时 YAML | 审计期 `POST` 分支把请求体写入 `Files.createTempFile("duo-rest-", ".yaml")` 后交给 `host.start(tmp)` | **确认** |
| ③ 外部进程 | `ExternalSutLauncher.spawn()`：`ProcessBuilder pb = new ProcessBuilder(command).redirectErrorStream(true)`（HEAD `:131`，审计期同形） | **确认** |
| ④ 无命令白名单 | `ScenarioEngine.splitCommand(...)` 是纯分词器（空白/引号，`${java}`/`${java.home}` 展开），**无任何白名单**；结果直接喂 `ProcessBuilder` | **确认** |
| ⑤ 校验器不查 | 审计期 `ScenarioValidator` 共 8 条规则、316 行，**只**要求 `launch.configOut` 非空（`ScenarioValidator.java:170-173`：`"external node " + n.id() + " must declare launch.configOut"`），**从不校验 `launch.command` 的内容，也不校验 `config` 的键与值** | **确认** |
| ⑥ 文件写入口 | `configOut` 交给 `writeConfigFile()` → `Files.newBufferedWriter(configOut, UTF_8)`（HEAD `:114`，审计期同形），路径完全由输入决定 | **确认** |

**结论**：C-1 成立，CRITICAL 分级恰当。攻击者只需「能连上 127.0.0.1:7788」即可让内核以
`/bin/sh -c '<任意命令>'` 起进程并写任意路径文件，无需任何凭证。

---

## 2. HIGH 级（H-1..H-5）

### H-1（HIGH）控制面无 CSRF / DNS-rebinding 防护 —— **确认**

读了审计期 `RestControlServer.java` 全文：`registerRoutes()` 注册的 8 个 context 一律无令牌，
且**没有一处**读 `Origin` / `Referer` / `Host` / `Content-Type`。报告描述的
`<form action="http://127.0.0.1:7788/scenario" method="post">` 简单请求型 CSRF 路径成立
（表单 POST 的 `Content-Type` 默认是三种简单类型之一，不触发预检）。
DNS rebinding 亦成立（服务器不校验 `Host`）。**报告的三条修复建议（Origin 校验 + Host 白名单 + 自定义头令牌）也是标准解法，无异议。**

### H-2（HIGH）`readAllBytes()` 先读满再检查 + 同步锁串行化 —— **确认**

- 审计期 `RestControlServer.java:133`：`new String(ex.getRequestBody().readAllBytes(), UTF_8)` —— **无上限读取**，确认。
- 审计期 `:51-52`（`HttpServer.create` + 虚拟线程 executor）与 `:82`、`:133` 两处 read-full-then-check，确认。
- 报告称 `ScenarioHost` 的 `synchronized` 方法把长耗时操作串行化：HEAD 下 `start` `:98`/`:112`、
  `stop` `:231`、`close` `:452` 均为 `synchronized`，与报告描述一致；`MetricsCollector.scrape()` 同为 `synchronized`。
  **确认**（对 `MetricsCollector.java:47` 的精确行号未逐字对齐，但方法级事实无争议）。

### H-3（HIGH）外部进程收尾不杀 + 管道/线程泄漏 —— **确认（但报告自己指出它是规格缺口）**

- 审计期 `ExternalSutLauncher.close()` 确为空实现（注释「故意为空：external SUT 生命周期归用户」）——**确认**。
- `pumpStdout()` 用 `while ((line = reader.readLine()) != null)` 阻塞在管道上——**确认**。
- `ScenarioEngine.stop()` 发 `sim.external-process-left-running` + 警告、**不杀**进程。HEAD 下
  `ScenarioEngine.java:569-580` 仍是「只拆接线，不杀」——**确认，且行为在整改后依旧**。
- 但报告 §6.2 自己也指出：spec §7.3 规定 external SUT 生命周期归用户，故空 `close()` **符合规格**，
  H-3 的批评点在「规格缺口」而非「实现违约」。**这一点报告自陈成立，复核认可。**

### H-4（HIGH）`ScenarioHost.start` 覆写 `engine` 不 `close()`；`EventRecorder.buffered` 永不清空 —— **确认（子命题均已读原文）**

- `EventRecorder.flush()`（HEAD `:72-91`）在 `synchronized (buffered)` 里 `List.copyOf(buffered)`，
  写出后 **`buffered` 一次都没被清理**。报告称「即使 `flush()` 之后也不清空」——**逐字确认**。
  连读 `close()`（`:122-128`）：`if (!closed) { flush(); closed = true; }` —— 也不清。
- `Closeable` 语义下每次 `flush()` 都重写全量，属**明确的重复写 + 内存滞留**缺陷。
- `ScenarioHost.start` 覆写 `engine` 前不 `close()` 旧引擎：HEAD 下 `start`（`:112-142`）依旧直接
  `engine = ScenarioEngine.validated(...)`，无旧的 `engine.close()`；`close()`（`:451-456`）只在宿主
  整体关闭时关一个引擎。**确认**（报告 H-4 的「撤销/重启动泄漏」在整改后仍未修，见 §6）。

### H-5（HIGH）`recorded` 无界 `CopyOnWriteArrayList` + `events()` 全量拷贝 —— **确认**

HEAD（与审计期同形，本次 diff 未触碰这三行）：
- `ScenarioEngine.java:46` `private final List<Event> recorded = new CopyOnWriteArrayList<>();`
- `:71` `bus.subscribe(recorded::add);`
- `:515-517` `public List<Event> events() { return List.copyOf(recorded); }`

`CopyOnWriteArrayList.add` 是 O(n) 拷贝 ⇒ 整场累计 O(n²)。`events()` 每次调用再复制一份。
报告的规模推算（`VirtualWorker.DEFAULT_HEARTBEAT_INTERVAL_MS = 100`、`eventSampleRate` 默认 1）
与「万级 worker 一场景百万级事件」量级一致。**确认**。且在整改后**这条发现完全未被处理**（见 §6）。

---

## 3. MEDIUM 级（M-1..M-8）

### M-1（MEDIUM）无时长 `flap` 永不自愈 —— **确认**

HEAD `VirtualRegistry.inject`：`fire(Event.sim("sim.registry-flap-started", ...))` 之后，
`flapTicker.schedule(() -> clear(action), action.durationMillis(), MILLISECONDS)` **只在
`if (action.durationMillis() != null && action.durationMillis() > 0)` 内**。`FaultAction` 是 record，
Jackson 不会把缺省 `duration` 填成 0/null —— 即 YAML 省略 `duration` ⇒ `clear` 永不排期 ⇒ **永久抖动**。
`TimelineScheduler.java:113-121` 是同一形状（`if (durationMs != null)` 才 schedule）。
**确认**，且叠加缺陷更值得注意：`TimelineScheduler.java:99-105` 对 `crash`/`restart` 这类生命周期动作
会**静默把 `durationMs` 置为 null** 并记一条 warning「nothing to clear」，于是「显式给了 duration 也不排期」。

### M-2（MEDIUM）4 个 I/O 落点由输入直控 —— **确认（且比报告更严重）**

| 键 | 读取点 | 复核 |
| --- | --- | --- |
| `filestore.root` | `VirtualFilestore.java:88` `String configured = ctx.config().get("filestore.root");`（另见 `:133-134`） | **确认** |
| `store.jdbcUrl` | `H2Store.java:59` `ctx.config().get(KEY_JDBC_URL)`，`KEY_JDBC_URL = "store.jdbcUrl"`（`StoreContract.java:43`） | **确认** |
| `launch.configOut` | `ExternalSutLauncher.writeConfigFile()` → `Files.newBufferedWriter(configOut)`（`:114`） | **确认** |
| `ready.host/path` | `ReadyProbe.java:66` `config.getOrDefault("ready.host", DEFAULT_HOST)`、`:72` `config.getOrDefault("ready.path", "/")`、`:110` `statusCode() < 500` 即判就绪 | **确认** |

**复核补充（比报告更强）**：`CapabilityMetadata.trustedConfigKeys` 这条「组件可自行声明额外可信键」的
逃生舱**至今零实现**——全仓仅 7 处引用，除 `ScenarioValidator` 的三处外，全部集中在
`CapabilityMetadata.java` 自己对 record 的定义（`:21`、`:31`、`:45`、`:49`），**没有任何 ComponentProvider
调用 `withTrustedConfigKeys`**。因此白名单实际是封闭的 `TRUSTED_CONFIG_KEYS`（`ScenarioValidator.java:63-78`），
这反而更安全；但文档（D11）宣称的「组件可扩展」目前是**空承诺**。

### M-3（MEDIUM）`HookRegistry.emit` 绕过 `Event.sim()/sut()` —— **确认**

审计期 `HookRegistry.java:76-77` 直接 `new Event(type, target, Instant.now(), payload)`，
绕过 `Event.sim()`/`Event.sut()` 的前缀校验（`Event.java:36-48`）。HEAD 已加：
`throw new IllegalArgumentException("hook emit type must start with 'sim.' or 'sut.': " + type)`。
**报告确认成立，整改也已落地。** 另注：报告的「Standards 轴唯一硬违规」判断我复核后同意。

### M-4（MEDIUM）`/metrics` 的 `scrape()` 有副作用 —— **部分确认（分级偏高）**

事实链读过：`MetricsCollector.scrape()` 是 `synchronized`，并调用 `consumeNewEvents()`
（HEAD `:151-176`），后者消费增量并累加计数器 —— 即**读操作带状态推进**。
报告称「同一份指标连抓两次结果不同」成立。

但**复核者更正**：
1. 该副作用是 **`synchronized` 保护的正常消费语义**，不是竞态；重复 scrape 得到的是**累计量**（单调），
   不会丢数、不会错乱。
2. `HttpServer` 由虚拟线程 executor 驱动，`scrape()` 的锁只与「另一路 metrics 抓取」竞争，
   报告推断的「与 `ScenarioHost` 同步方法互相阻塞」缺证据：`scrape()` 调 `host.status()/assertions()/topology()`
   时会**嵌套拿宿主锁**，但方向单一（`MetricsCollector` → `Host`），**不存在反向加锁路径，故无死锁环**。
3. 因此这更像 **MEDIUM 下限 / LOW** 的可观测性语义问题，而非 HIGH 风险的并发缺陷。
   **判定：事实确认，风险定级应下调，并把「scrape 有副作用」写进 `docs/METRICS.md` 而非当缺陷。**

### M-5（MEDIUM）`PostgresContainerStore` 把口令拼进 jdbcUrl 并对外发布 —— **确认**

- HEAD `:349-351`：`appendCredentials(baseUrl, username, password)` → `return baseUrl + separator + "user=" + username + "&password=" + password;` —— **明文拼接、无 URL 编码**，**确认**。
- `:167` `this.jdbcUrl = jdbcUrlWithCredentials(c);`；`:175` 以 `Map.of("jdbcUrl", jdbcUrl, "kind", KIND_CONTAINER, "image", image)` 发 `sim.store-started`；`:243` `new ExposedEndpoint(Contract.STORE, "jdbc", jdbcUrl)`；`:255-256` 公开 `jdbcUrl()` getter。**全部确认。**
- **复核补充（报告漏掉的一条加重项）**：`H2Store.java:76` 同样把 jdbcUrl 发进事件：
  `fire(Event.sim("sim.store-started", id.value(), Map.of("jdbcUrl", jdbcUrl)))`。H2 档 URL 可被
  `store.jdbcUrl` 覆盖，因此「连接串进事件流」在 **embedded 档也存在**，报告只点了 container 档。
- 另注 `:137-140`：container 档对非空 `store.jdbcUrl` 显式抛
  `ComponentException("store.jdbcUrl is not supported on container tier ...")`，故 container 档的 URL 由容器映射生成，
  口令是测试容器的固定值——**危害范围比"C-1 式攻击者可控"小**，报告未区分这一点。分级可维持 MEDIUM，但理由应改写。

### M-6（MEDIUM）`/events` 回显内部解析异常 —— **部分确认（已修复且手法正确，但报告的利用路径有误）**

报告称 `FaultDiagnostics.toEvent` 对未知前缀抛 `IllegalArgumentException`（引用 `Event.java:37-39`），
使 `duo diagnose` 把攻击者可控的类型串回显。审计期原文（`FaultDiagnostics.java:240-249`）：

```java
private static Event toEvent(Map<String, Object> m) {
    ...
    if (type.startsWith(Event.SUT_PREFIX)) {
        return Event.sut(type, source, payload);
    }
    return Event.sim(type, source, payload);
}
```

**复核更正**：`toEvent` 是**先判前缀再调对应工厂**，只有「不以 `sut.` 开头」才走 `Event.sim(...)`——
即：任意**不以 `sut.` 开头**的类型串（含 `java.lang.Runtime`、`../../etc` 等）都会被当成 `sim.*` 接受并
经 `Event.sim` 的 `SIM_PREFIX` 校验抛异常，**不会**抛「类型不可识别」以外的信息。报告的机制成立
（未识别的 `sut.`-less 串会触发内核异常），但报告引用的「`Event.java:37-39` 对未知前缀抛异常」
与代码实际形状有出入：`Event.sim/sut` 校验的是前缀而非白名单。**结论方向正确、机理描述需更正。**

### M-7（MEDIUM）`sim.external-process-started` 回显完整命令行 —— **确认**

HEAD `ExternalSutLauncher.java:141-142`：

```java
eventSink.accept(Event.sim("sim.external-process-started", sutId,
        Map.of("pid", process.pid(), "command", String.join(" ", command))));
```

`command` 是完整 argv 拼接（含路径、可能的凭据参数），进事件流 → 进 `events.jsonl` → 进 CI artifact。
`pumpStdout` 逐行解析 `duo.endpoint.*` 进 `sim.external-endpoint`，同样由 SUT 侧可控。
**确认**。整改后此事件**未被改动或脱敏**（见 §6）。

### M-8（MEDIUM）事件流/CI 产物泄露 —— **部分确认（"报告不成立"中的一条成立，另一条不成立）**

报告给了两个子命题：
1. **磁盘 `events.jsonl` 里没有 `jdbc`/`password=` 字样** —— 这是报告 §5 的自我否决，我复核后**同意**
   （`EventRecorder.toJsonLine` `:93-99` 只序列化 type/sourceId/timestamp/payload，payload 由各组件决定；
   本地无 container 档运行，故确实抓不到 `password=`）。
2. **CI 会把 `build/scenarios/**/events.jsonl` 上传为 artifact** —— 读 `.github/workflows/ci.yml` 原文：
   - `:50-57`（regression job）：`actions/upload-artifact@...v4.6.2`，`path:` 含
     `**/target/surefire-reports/*.txt` 与 **`**/build/scenarios/**/events.jsonl`** —— **确认**。
   - `:104-111`（scale job）：`scale-artifacts`，`path:` 含 `build/scale/*.json` 与
     **`**/build/scenarios/**/events.jsonl`** —— **确认**。
   （报告引用的 `:52-54` / `:106-108` 是**整改前**的行号；本次读到的 HEAD 已因 L-6 的 SHA pin 注释而位移到
   `:50-57` / `:104-111`。内容与结论不变。）
   故「CI 上传事件流」这条**确实成立**，而报告的 §5 把它当「已否决」处理是**过度自我否决**。

**判定：M-8 确认。**报告在 §5 里把它降级为「不成立」是错的（我当时以整改前 `ci.yml` 的同样的
`upload-artifact` 步也能读到相同 `path`，见上）。

---

## 4. LOW 级与 INFO-1（L-1..L-6、INFO-1）

| 编号 | 报告结论 | 复核判定 | 关键证据 |
| --- | --- | --- | --- |
| L-1 | 每次 POST/`startFromResource` 泄漏临时 YAML | **确认** | 审计期 `ScenarioHost.startFromResource` 建 `Files.createTempFile("duo-scenario-", ".yaml")` 后**无删除路径**；`RestControlServer` 的 `duo-rest-*.yaml` 同理。HEAD 已补 `deleteTempNow()`（`:145-156`）与 `cleanupTempArtifacts()`（`:255-278`），**已修复** |
| L-2 | `FrameConnection` 行帧双重分配 | **部分确认（报告结论正确，但理由需修正）** | 审计期 `readFrame()` `:33-53`：**先**校验 `len <= 0 \|\| len > MAX_PAYLOAD_LENGTH`，**再** `new byte[HEADER_LENGTH + len]` 与 `readFully(len)`。上限（`FrameCodec.MAX_PAYLOAD_LENGTH = 1<<20`）确实在两次分配**之前**生效 ⇒ 峰值 2 MiB/连接是**有界常数**。故「存在双重分配」为事实，「突破上限」为**否**。报告 §5 曾自我否决、随后又以 LOW 保留，**保留是对的，但描述应写成"有界内的冗余拷贝"**。`close()`（`:88-91`）只关 socket 不关 `in`/`out`，这一半**确认** |
| L-3 | `SutLauncher` 10s 宽限后不 `destroyForcibly`；迟到的 `onStop` 永不生效 | **确认（前一半＝口径取舍，后一半＝真缺陷且已修）** | 审计期 `:123-141`：`stop()` 在 `stopHandler == null` 时 `runner.interrupt()`，随后 `stopDone.await(STOP_TIMEOUT_MS=10_000)`，**无 `destroyForcibly`** —— 确认。审计期 `:222-225` `onStop` 仅 `owner.stopHandler = handler;`，无补偿 —— 确认。HEAD `:227-239` 已加「若 `stopRequested` 已在途则立即补跑一次」，`:132` 也改为 `else if (stopDone.getCount() > 0 && runner != null)`。**后半段已修复。** |
| L-4 | `setSoTimeout(0)` 后静默连接永久占用 | **确认（且报告未评估同类第三处）** | 读到的原文：`VirtualScheduler.java:291` `setSoTimeout(REGISTER_TIMEOUT_MS=10_000)` → `:293` `s.setSoTimeout(0)`；`VirtualWorker.java:210` 同形 → `:227` `setSoTimeout(0)`。注册后不再有读超时 ⇒ **确认**。**复核补充（报告未提）**：`VirtualEngine.java:357-360` 是**同一形状的第三处**——首帧限时 10s，之后 `s.setSoTimeout(0)`，随后 `while (running.get()) conn.read()`（`:370-378`）。报告只点了 worker 侧，漏了 engine 侧。此后果被 D12 明确接受为已知代价（「长连接 worker 注册后静默会让一个 socket/虚拟线程/feeds 槽位一直被占…不静默」） |
| L-5 | 4 个已跟踪文件泄漏真实身份/本机路径 | **确认** | `git show e8ea3cc:scripts/duo-inject-demo.sh` 第 21 行确为 `export JAVA_HOME="${JAVA_HOME:-/c/Users/cwt15/devtools/jdk-21.0.12.1+1}"`；`git show e8ea3cc:docs/DECISIONS.md` 含「`mvnw.sh` 硬编码 `C:\Users\cwt15\devtools\...`」。HEAD `git grep "cwt15"` 仅命中审计报告自身与验收记录，**源码/脚本已无泄漏**；`scripts/duo-inject-demo.sh:20-29` 已改为「JAVA_HOME 缺失即报错退出」。**确认且已修复** |
| L-6 | CI action 用可移动 tag | **确认** | HEAD `ci.yml` 三个 job 全部 pin 到 40 位 SHA（`:31`/`:32` `actions/checkout@11d5960a... # v4.2.2`、`actions/setup-java@cf277c60... # v4.7.1`、`:50`/`:80`/`:104` `actions/upload-artifact@ea165f8d... # v4.6.2`）。`permissions: contents: read`（`:23-24`）确认；无 `pull_request_target`、无 `secrets.*`。**确认且已修复** |
| INFO-1 | 工作区构建残留含开发者信息 | **确认（但报告正文有误，见下）** | 报告 §5 称 `hs_err_pid*.log`×8 + ×5、`replay_pid*.log`×2 未跟踪且被 `.gitignore` 覆盖（`.gitignore:17 *.log`）。HEAD `.gitignore` 新增 `:19-23` 显式封口 `hs_err_pid*.log` / `replay_pid*.log`。**INFO-1 的处理（清理 + 显式封口）正确**。⚠️ **但报告 §5 把这一行归到了 L-3 名下**（`.gitignore` 注释写「安全审计 2026-09-20 L-3/INFO-1」），而 L-5 才是身份泄漏条 —— 编号串台，属报告笔误 |

---

## 5. 报告「明确否决的假设」（§5）复核

抽查了 4 条最关键的自我否决，**全部同意**：

| 被否决的假设 | 复核 |
| --- | --- |
| `VirtualFilestore` 路径穿越 | **同意**。`resolve()` 做 `normalize()` + `startsWith(root)` 校验，否则抛 `"filestore path escapes root: "`，防护在代码里 |
| Jackson 默认类型 gadget | **同意**。全仓无 `enableDefaultTyping` / `activateDefaultTyping` / `Id.CLASS`；`ScenarioLoader.java:31` 仅 `new Yaml().load(in)` |
| `impl` 字段任意类加载 | **同意**。`ContractRegistry.resolve()` 只在 `ServiceLoader` 已注册的 provider 里按 `implName` 匹配，无 `Class.forName` |
| 帧长度负数/超大 | **同意**。`FrameConnection.readFrame()` 在校验后才分配（见 L-2），`FrameCodec.decode` 另有 `payload too large` / `bad payload length` 两道 |

---

## 6. 整改台账（报告 §7 + 验收记录）复核

### 6.0 首要发现：台账把 7 条编号贴错了对象

报告 §7 与 `docs/superpowers/acceptance/2026-09-20-duo-security-remediation-record.md` §1 的
「落地点」列，与报告 §2–§4 里该编号的**主题**不一致：

| 编号 | 报告 §2–§4 里的主题 | 台账/验收记录写的落地点 | 复核 |
| --- | --- | --- | --- |
| H-4 | `ScenarioHost.start` 覆写 `engine` 不 `close()`；`EventRecorder.buffered` 不清空 | 「`MetricsCollector` 事件类型基数上限 256 + `__other__`」 | ❌ **串台**：`MetricsCollector` 的基数上限是**报告 §8 自承被推翻的那条子代理结论**，不是 H-4 |
| H-5 | `ScenarioEngine.recorded` 无界 `CopyOnWriteArrayList`（O(n²)） | 「`EventRecorder` 有界缓冲 + `stopWithoutAwait()` + `close()` 关 executor」 | ❌ **串台**：`recorded` 至今**一字未改**（`ScenarioEngine.java:46`/`:71`/`:516` 三行与 `e8ea3cc` 完全一致） |
| M-4 | `/metrics` 的 `scrape()` 有副作用 | 「结果伪造面随认证关闭」 | ⚠️ **顶替**：`scrape()` 的副作用**未改**，只是「未认证不能读」降低了暴露面 |
| M-5 | `PostgresContainerStore` 明文口令进 jdbcUrl | 「`sanitizeReason`：绝对路径掩码」 | ❌ **串台**：那是对 HTTP 错误消息的脱敏；`appendCredentials` `:351` 一字未改 |
| M-6 | `/events` 回显内部解析异常 | 「启动失败即删临时 YAML」 | ❌ **串台**：那是 L-1 的内容，M-6 的主题**未处理** |
| M-7 | `sim.external-process-started` 回显完整命令行 | 「`close()` 释放 stdin/stdout」 | ❌ **串台**：`Map.of("pid", ..., "command", String.join(" ", command))` 至今原样（`ExternalSutLauncher.java:141-142`） |
| M-8 | 事件流/CI artifact 泄露 | 「500 只回根因摘要」 | ❌ **串台**：那是 M-6 的主题；CI `upload-artifact` 的 `**/build/scenarios/**/events.jsonl` **仍在**（`ci.yml:56`/`:110`） |
| L-2 | `FrameConnection` 双重分配 | 「`FrameConnection` 见下未改；`ReadyProbe` 复用静态 `HttpClient`」 | ✅ **诚实**：前半段明说未做（理由成立），后半段是**另一个真问题**（`ReadyProbe` 每次探测 new `HttpClient`）被顺带修掉 |

**结论**：台账不是「20 条逐条闭环」，而是「15 条真修复 + 5 条相邻加固顶替 + 若干编号错位」。
其中 M-5/M-6/M-7/H-4/H-5 这 5 条的**原始问题在代码里仍然存在**，被记成「已修复」是台账的实质缺陷。

### 6.1 真正落地、我逐字验证过的整改

| 项 | 证据 |
| --- | --- |
| **令牌必填** | `RestControlServer` 构造函数 `:101-106`：`Auth.TOKEN` 下 `token == null \|\| token.isEmpty()` 即抛 `IllegalArgumentException`；`TestTokens` 断言 `new RestControlServer(host)` / `(host, TOKEN, "")` / `(host, INSECURE, "x")` 三者都抛 |
| **8 个端点全需令牌** | `guard(...)` `:320-345`：① `Host` 必须回环（`:322`，`LOOPBACK_HOSTS` 含 `127.0.0.1`/`localhost`/`[::1]` 等）② `Origin`/`Referer` 存在则必须回环（`:327-332`）③ `Bearer` 常量时间比较（`:337-343`，`MessageDigest.isEqual`）；`/health` 免认证（`:333`） |
| **请求体上限** | `MAX_BODY_BYTES = 1<<20`；`readBody()` `:404-417` 先看 `Content-Length` 早失败，再 `readAtMost`（`:419-430`）按 `max+1` 真实截断 → `BodyTooLargeException` → 413（报告 H-2 的修复**到位**） |
| **外部输入档收窄** | `ScenarioValidator.InputTrust{CONFIG,EXTERNAL}`、`ScenarioEngine.InputPolicy{CONFIG,EXTERNAL_INPUT}`、`ScenarioHost.Trust{CONFIG,EXTERNAL_INPUT}` 三级贯通（`ScenarioHost.java:123-126` → `ScenarioEngine.validated` `:110-125` → `ScenarioValidator` `:348`） |
| **规则 9/10/11** | `:392-424`：`isExternal && (allowExternalProcess \|\| command 非空)` ⇒ 拒（**C-1 的 RCE 路径在校验期关闭**）；`main` 必须命中 `io.duo.sim.` / `com.duo.`（`:406-411`）；`config`/`capacity` 键走 `TRUSTED_CONFIG_KEYS` 白名单（`:414-423`）；`checkConfigPath` `:438-449` 拒绝对路径与 `file:/jdbc:/jar:/classpath:` 等 scheme；规模上限 `MAX_NODES=256`/`MAX_INSTANCES=4096`/`MAX_INSTANCES_PER_NODE=1024`/`MAX_TIMELINE=1000`/`MAX_ASSERTIONS=200` |
| **纵深防御** | `ScenarioEngine.startExternalSut` `:308-311` 在 EXTERNAL_INPUT 下直接抛异常（即使有人绕过 `validated`）；`assertMainAllowed` `:348-358` 在 `Class.forName`（`:229`）**之前**调用（`:227`），顺序正确 |
| **`ExternalSutLauncher.close()`** | `:337-357`：代起形态 `p.destroy()` **先于**关流（注释记录了 Windows `readLine()` 同步 `ReadFile` 不可中断、先关流会卡死在 `FileDescriptor.close0` 的实测机理），随后 `closeQuietly` 三条管道；attach 形态（`command.isEmpty()`）直接 return，**不碰用户进程**。`pumpStdout` 也改掉了 try-with-resources（`:151-156`），实现「one owner 负责释放」 |
| **`SutLauncher.onStop` 迟到注册** | `:227-239`：`owner.stopHandler = handler;` 之后若 `owner.stopRequested.get()` 则立即补跑一次并捕获 `RuntimeException` 发 `sut.stop-handler-error` |
| **`HookRegistry.emit` 前缀校验** | 已加，见 M-3 |
| **`ReadyProbe` 静态 `HttpClient`** | `:116-118` `SHARED_HTTP`，替代原先每次探测 `HttpClient.newBuilder()...build()` |
| **`RestControlServer.close()` 关 executor** | `:487-498` `server.stop(0)` + `executor.shutdown()` |
| **CI SHA pin / `.gitignore` 封口 / 脚本 JAVA_HOME** | 见 §4 的 L-6、INFO-1、L-5 |
| **新增回归用例** | `SecurityRemediationAcceptanceTest`（2 个 `@Test`：C-1 PoC 落盘副作用断言 + 临时文件清理）、`RestControlServerTest`（`tokenIsMandatoryAtConstruction`/`everyEndpointRequiresTheBearerToken`/`crossSiteOriginAndForeignHostAreForbidden`/`oversizedBodyIsRejectedWith413`/`externalInputCannotLaunchProcessOrReachOutsidePaths`）、`ExternalSutLauncherTest`（`closeDestroysSpawnedProcessAndReleasesPipes`/`attachModeCloseLeavesUserProcessAlone`）。**用例内容与断言我逐条读过，写法扎实**（如 PoC 用例断言的是「marker 文件不存在」这一**副作用**而非状态码） |

### 6.2 台账声明中**未获证据支持**的部分

- **「378 测 / 0 失败 / 0 错误 / 11 skip / BUILD SUCCESS」**：本次复核**未运行**构建或测试，
  因此这条运行时结论**无法判定**，只能记录为「验收记录 `:150-157` 如此声称」。
  同一份记录 `:158-159` 也承认「本记录只写可复现的证据」，故这条应当被复现（见 §7）。
- **`ExternalSutAcceptanceTest` 的收尾**：该用例（`e8ea3cc` 与 HEAD **字节相同**）在
  `killProcess()` 里只做 `p.destroyForcibly()`（`:254-263`），**没有** `engine.stop()` / `externalSut().close()`。
  也就是说：**整改后的「内核自己收摊」这条新口径，在既有 external 验收用例里没有任何覆盖**；
  它被覆盖的只有新加的 `ExternalSutLauncherTest.closeDestroysSpawnedProcessAndReleasesPipes`
  （直接对 launcher 调 `close()`）。这一层覆盖缺口应在验收记录里如实标注。
- **`docs/METRICS.md` 与 `MetricsCollector` 的一致性**：报告 M-4 提到可能的文档不一致；
  整改记录称已在 `docs/METRICS.md` §1/§4 补「认证行 + 类型基数上限」。**行号级比对未做 ⇒ 无法判定。**

---

## 7. 复现建议（把「声称」变成「证据」）

为让本复核的运行时部分闭环，建议执行（本机无 Docker，故容器档必然 skip）：

```powershell
cd D:\Program\Duo
.\mvnw.cmd -B -o -Dduo.docker.enabled=false `
  "-Dtest=SecurityRemediationAcceptanceTest,RestControlServerTest,ExternalSutLauncherTest,ScenarioValidatorTest,EventRecorderTest,HookRegistryTest" test
```

要验的三件事：
1. `SecurityRemediationAcceptanceTest.c1PocIsRefusedAtValidationAndLeavesNoTraceOnDisk` 真的把
   审计 §3.1 的 PoC 发出去且 marker 未落盘（＝C-1 的**能力**已关闭，而不只是返回 400）；
2. `ExternalSutLauncherTest.closeDestroysSpawnedProcessAndReleasesPipes` 在 **Windows** 上不挂
   （即 D12 的「先 destroy 再关流」顺序确实避开了 `FileDescriptor.close0` 死锁）；
3. `EventRecorderTest`/`ScenarioValidatorTest` 的条数与报告称的 5 / 20 一致。

---

## 8. 复核者诚实声明

1. 本次复核**只做静态阅读**，未执行构建、测试或运行时验证；§6.2 的「378 测」无法判定即源于此。
2. 证据边界：C-1 六级链、H-1..H-5、M-1/M-2/M-3/M-5/M-6/M-7、L-1/L-2/L-3/L-4/L-5/L-6、INFO-1、
   §5 全部 4 条抽查、§6 全部整改落地点，均有本文引用的原文与行号。
3. **未逐字核对的**：M-4 中 `MetricsCollector.java:47` 的精确行号、`docs/METRICS.md` 的整改后内容、
   `docs/ARCHITECTURE.md` §12.1 / `docs/SCENARIO-DSL.md` §4 的文档同步、`CHANGELOG.md`。
   这些记为「无法判定」，未写成结论。
4. 我**推翻/更正**了报告的两处判断：§5 对 M-8（CI artifact）的过度自我否决（实际成立）、
   以及 M-4 的风险定级（事实成立但应下调）。
5. 我**加重**了两处：`H2Store.java:76` 也把 jdbcUrl 发进事件（M-5 的 embedded 档同源）；
   `VirtualEngine.java:357-360` 是 `setSoTimeout(0)` 的第三处（L-4 漏点）。
6. 最重要的一条见 §6.0：**整改台账有 7 行编号与主题不对应，其中 H-4 / H-5 / M-5 / M-6 / M-7
   的原始问题在整改后代码里依然存在**，却被记为「已修复」。这不影响 C-1/H-1/H-2/H-3 等
   控制面整改的真实性与质量，但「20 条全部闭环」这个印象是不成立的。
