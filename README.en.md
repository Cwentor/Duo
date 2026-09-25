<div align="center">

# Duo

**Composable Virtual Big-Data Simulation System**

[![CI](https://github.com/Cwentor/Duo/actions/workflows/ci.yml/badge.svg)](https://github.com/Cwentor/Duo/actions/workflows/ci.yml)
[![License](https://img.shields.io/badge/License-Apache_2.0-blue.svg)](./LICENSE)
[![Java](https://img.shields.io/badge/Java-21_LTS-ED8B00?logo=openjdk&logoColor=white)](https://openjdk.org/projects/jdk/21/)
[![Maven](https://img.shields.io/badge/Maven-3.9%2B-C71A36?logo=apachemaven&logoColor=white)](https://maven.apache.org/)
[![JUnit](https://img.shields.io/badge/JUnit-5-25A162?logo=junit5&logoColor=white)](https://junit.org/junit5/)

[English](README.en.md) · [简体中文](README.md)

[Quick Start](#5-quick-start) · [Scenario DSL](docs/SCENARIO-DSL.md) · [Architecture](docs/ARCHITECTURE.md) · [Roadmap](docs/ROADMAP.md) · [Docs](#9-documentation-index)

</div>

> With one YAML file, stand up an entire big-data / distributed-systems pipeline — registry, storage,
> resource manager, workers, scheduler — **inside a single JVM**; swap in your real implementation
> (the SUT) for the one node you actually want to test; then, in a second-level feedback loop,
> inject faults into it, watch it self-heal, and let assertions decide the verdict.

## Overview

| Item | Description |
| --- | --- |
| Version | `0.1.0-SNAPSHOT` (`io.duo:duo-sim-parent`) |
| Tech stack | Java 21 (LTS) · Maven multi-module · SnakeYAML · Jackson · Curator/H2/Fabric8/Testcontainers |
| Stage status | **M0–M8 all implemented and accepted; gap list G1–G11 fully closed** (the only registered exception: M8 deliverable 4, "accelerated-clock evaluation", still awaiting its trigger condition); **M9 Phase A complete (2026-09-25): the first real third-party system, DolphinScheduler 3.4.3, ran the registry-flap drill all green**; M9 Phase B (the "session-survivable" fault surface needed for DS re-registration) not started — scheduled for a later round |
| Latest full regression | 2026-09-25 · `.\mvnw.cmd -o -B test` → **397 tests / 0 failures / 0 errors / 12 skips** (per-module breakdown in the [Development Guide §3.2](docs/DEVELOPMENT.md); every skip itemized and explainable: container tier 10 + stress test 1 + M9 real-SUT drill gate 1) |
| Quality gate | `.\mvnw.cmd -o -B "-Dquality" -DskipTests verify` → dependency analysis "zero undeclared / zero unused" (wired into CI) |
| Design basis | [Design document v1.0 (frozen)](docs/superpowers/specs/2026-09-13-duo-virtual-bigdata-sim-design.md) |

**Milestone progress**: `M0` kernel skeleton ✅ · `M1` scenarios & injection ✅ · `M2` embedded
middleware ✅ · `M3` control plane ✅ · `M4` scale & bridging ✅ · `M5` contract & tier completion ✅ ·
`M6` external SUT ✅ · `M7` engineering & CI ✅ · `M8` observability ✅ ·
`M9` real third-party integration (Phase A ✅ · Phase B not started)

## Table of Contents

- [Overview](#overview)
- [1. What Problem It Solves](#1-what-problem-it-solves)
- [2. Four Core Concepts](#2-four-core-concepts)
- [3. Overall Architecture](#3-overall-architecture)
- [4. Module Map](#4-module-map)
- [5. Quick Start](#5-quick-start) — [5.1 Environment](#51-environment) · [5.2 Build & Test](#52-build--test) · [5.3 Run a Scenario (CLI)](#53-run-a-scenario-cli-control-plane) · [5.4 Observability](#54-observability-using-the-three-channels-m8) · [5.5 Write a Scenario Test](#55-write-a-scenario-test-junit-5)
- [6. Contract × Tier](#6-contract--tier-currently-implemented-implementations)
- [7. A Minimal Scenario](#7-a-minimal-scenario)
- [8. Status & Gaps](#8-status--gaps-one-page-overview)
- [9. Documentation Index](#9-documentation-index)
- [10. License](#10-license)

---

## 1. What Problem It Solves

Testing the scheduling and coordination logic of big-data systems (DAG dependencies, retries,
failover, leader election, resource scheduling) traditionally means standing up a full cluster:
startup is measured in tens of minutes, hardware costs are significant, fault drills are not
repeatable, and the feedback loop is measured in hours.

The industry already has four mature precedents, but each stands alone:

| Precedent | What it proved |
| --- | --- |
| KWOK (Kubernetes Without Kubelet) | Fake nodes alone can fool a real scheduler |
| Curator TestingServer | A lightweight ZooKeeper speaking the **real protocol** can run inside the JVM |
| Fabric8 Mock / H2 | Various middleware can be replaced by in-memory doubles |
| Testcontainers | Real-protocol validation can spin up real containers on demand |

Duo supplies the missing layer: **a unified simulation framework with configurable behavior,
injectable faults, and assembly into arbitrary pipeline topologies** — the kernel understands only
"contracts"; it knows nothing about any concrete product.

## 2. Four Core Concepts

| Concept | Meaning |
| --- | --- |
| **Contract** | The role semantics of a component in the pipeline (`registry` / `store` / `worker` / `engine` / `scheduler` / `resource` / `message` / `filestore`). The only abstraction the kernel recognizes |
| **Tier** | How a contract is implemented: `virtual < embedded < container < real`. The same contract slot can switch tiers with **zero changes to test code** |
| **SUT** | System Under Test — the single node marked `sut: true` in the topology, usually a `real`-tier implementation |
| **wiring** | How a node consumes its dependencies: `wire` (over real protocol ports / wire protocol) or `direct` (same-JVM injection of the contract's Java interface), inferred from capability metadata or declared explicitly |

## 3. Overall Architecture

```
                    Scenario YAML
                        │
                        ▼
    ┌───────────────────────────────────┐        ┌──────────────────────────────┐
    │ Scenario Engine                    │        │ Control Plane                 │
    │ validate / orchestrate / timeline  │        │ REST (`duo serve`) + CLI      │
    │ record / hooks                     │        │ hot inject · status · events │
    └────────────────┬──────────────────┘        │ topology                     │
                     │                            └───────────────┬──────────────┘
                     ▼                                            │
    ┌───────────────────────────────────┐                        │
    │ Simulation Kernel                  │◄───────────────────────┘
    │ SPI · contract registry · component manager · event bus · wiring resolution │
    └────────────────┬──────────────────┘
                     │
     ┌──────────────┼──────────────────────────┬─────────────────────┐
     ▼              ▼                          ▼                     ▼
 virtual-tier    embedded-tier               container-tier        real-tier nodes
 component lib   doubles                    doubles
 VirtualWorker   Curator ZK (real proto)    Testcontainers ZK     real implementations (one is the SUT):
 VirtualRegistry H2 (real JDBC)            PostgreSQL 16         kernel-hosted or external
 VirtualScheduler Fabric8 K8s Mock                              e.g. DemoScheduler (master side)
 VirtualEngine   ...                                              RealWorkerSut (worker side)
     │              │                          │                     │
     └──────────────┴──── real protocol / Duo wire protocol / state events ──────┘
                     │
                     ▼
     Observability: event stream (single source of truth) · logs (causal chain) · metrics (/metrics) · assertions
```

## 4. Module Map

Every module has its own `README.md` (responsibilities / dependencies / key classes / tests /
pitfalls). Module READMEs are maintained in Chinese:

| Module | Responsibility | Key classes |
| --- | --- | --- |
| [`duo-sim-protocol`](duo-sim-protocol/README.md) | Duo wire-protocol frame format, codecs, contract messages; third-party adapters depend only on this artifact | `FrameCodec` `DuoCodec` `DuoMessage` |
| [`duo-sim-kernel`](duo-sim-kernel/README.md) | SPI, contract registry & capability metadata, component manager, instance addressing, event bus, wiring resolution, SUT adapter surface, assertion kernel | `VirtualComponent` `ComponentProvider` `ContractRegistry` `WiringResolver` `ScenarioRuntime` `SutLauncher` `Assertions` |
| [`duo-sim-scenario`](duo-sim-scenario/README.md) | YAML parsing & validation (rules 1–8), scenario orchestration, timeline injection, event recording, custom hooks | `ScenarioLoader` `ScenarioValidator` `ScenarioEngine` `TimelineScheduler` `EventRecorder` `HookRegistry` |
| [`duo-sim-components`](duo-sim-components/README.md) | virtual-tier components: `VirtualWorker` (embedded TaskStub behavior model), `VirtualRegistry`, `VirtualScheduler`, `VirtualEngine`, `VirtualFilestore`, `VirtualMessageBroker`, `VirtualResourceManager` | `VirtualWorker` `VirtualRegistry` `VirtualScheduler` `VirtualEngine` `BehaviorProfile` `BehaviorResolver` |
| [`duo-sim-embedded`](duo-sim-embedded/README.md) | embedded/container tiers: Curator TestingServer, H2, Fabric8 K8s Mock, Testcontainers ZK/PostgreSQL | `CuratorRegistry` `H2Store` `PostgresContainerStore` `Fabric8K8sMock` `ZookeeperContainerRegistry` |
| [`duo-sim-junit`](duo-sim-junit/README.md) | JUnit 5 extension `@VirtualCluster` + programmatic assertions `DuoAssertions` | `VirtualCluster` `VirtualClusterExtension` `DuoAssertions` |
| [`duo-sim-control`](duo-sim-control/README.md) | REST + CLI control plane (hot inject, status, events, assertions, topology) | `ScenarioHost` `RestControlServer` `DuoCli` |
| [`duo-sim-examples`](duo-sim-examples/README.md) | reference SUT `demo-scheduler`, `demo real worker`, **worker-side real SUT `RealWorkerSut`** (register/heartbeat/claim/report + disconnect self-healing), gold-standard scenarios and all acceptance tests | `DemoScheduler` `DemoRealWorker` `RealWorkerSut` |

Module dependency direction (**acyclic**):

```
protocol ← components / examples
kernel   ← scenario / junit / control / embedded / components
examples ← aggregates everything (junit / control integration tests live in examples, avoiding module cycles)
```

> **Test-ownership convention**: `duo-sim-control`'s contract tests (`ScenarioHostTest` /
> `RestControlServerTest`) run inside **that module's** `src/test`; the example-coupled orchestration
> case `DuoCliTest` stays in `examples`. Three segments, each clearly owned — no cross-module
> arithmetic needed (see [Development Guide §3.2](docs/DEVELOPMENT.md)).

## 5. Quick Start

### 5.1 Environment

- **JDK 21** (the project uses virtual threads, `record`, text blocks)
- **Maven 3.9+**
- Optional: **Docker** (only the `container` tier needs it; without Docker the relevant tests skip by design)

The repo ships a **standard Maven Wrapper** (`mvnw` / `mvnw.cmd` + `.mvn/wrapper/`, script-only
form). All you need is `JAVA_HOME` pointing at JDK 21 — no preinstalled Maven, no file edits:

```bash
./mvnw -o clean test           # full regression (Git Bash / Linux / macOS)
.\mvnw.cmd -o clean test       # PowerShell / cmd
```

```powershell
$env:JAVA_HOME = 'C:\path\to\jdk-21'   # JAVA_HOME is all you need; Maven 3.9.11 comes with the wrapper
.\mvnw.cmd -o -B clean test
```

> The first run downloads Maven 3.9.11 per `.mvn/wrapper/maven-wrapper.properties` into
> `~/.m2/wrapper/dists/`. The old hand-written `mvnw.sh` shell (which hardcoded the author's
> machine paths) was deleted — see [Decision D6](docs/DECISIONS.md).

### 5.2 Build & Test

```bash
./mvnw -o -B test "-Dduo.docker.enabled=false"       # 397 tests (12 design-gated skips: container 10 + stress 1 + M9 real-SUT drill 1)
./mvnw -o -pl duo-sim-examples -am test -Dtest=ScaleAcceptanceTest "-Dduo.scale=true"
                                                      # thousand/ten-thousand-worker heartbeat stress (≥ 5 min, > 1 GB heap)
./mvnw -o install -DskipTests                        # install into the local repo (required before CLI drills)
```

CI ([`.github/workflows/ci.yml`](.github/workflows/ci.yml)) has three jobs:

| job | trigger | content |
| --- | --- | --- |
| `regression` | push / PR | no-Docker full regression + dependency quality gate (`-Dquality`) + visible skip inventory |
| `container` | push / PR | with Docker: runs the container tier and asserts **skip=0** (real PostgreSQL 6/6 + real ZooKeeper 4/4) |
| `scale` | nightly / manual | thousand/ten-thousand-worker heartbeat stress, artifacts archived (`duo-sim-examples/build/scale/*.json`; a missing artifact fails the job) |

The container tier has **five evidenced green runs on CI**: runs
[35341365256](https://github.com/Cwentor/Duo/actions/runs/35341365256),
[35343279887](https://github.com/Cwentor/Duo/actions/runs/35343279887),
[35417427531](https://github.com/Cwentor/Duo/actions/runs/35417427531),
[35420349133](https://github.com/Cwentor/Duo/actions/runs/35420349133), and
[36115793273](https://github.com/Cwentor/Duo/actions/runs/36115793273) (all three jobs green;
the scale tier's full suite of 413 tests all green).

> **Two things that must not be conflated**: ① this machine has no Docker, so those 10 tests can
> only skip here — "local green" and "container-tier green" are different claims and cannot stand in
> for each other; ② run `36115793273` is also the **first real verification of the G8
> "stress artifacts archived" promise** — `scale-artifacts` (683 bytes) was actually uploaded for
> the first time (previously a path mismatch had left that step silently empty since CI was set up;
> fixed by aligning the path to the module directory and hardening `if-no-files-found` from `warn`
> to `error`). The local `master` and `origin/master` are in sync at `257ca48` (2026-09-25), so the
> evidence runs listed above are current.

### 5.3 Run a Scenario (CLI Control Plane)

```bash
# one command: start → hot-inject "crash workers[2]" after 3s → wait for SUT exit → print result;
# the exit code is the verdict
./mvnw -o install -DskipTests
export JAVA_HOME=/path/to/jdk-21     # the script requires JAVA_HOME (security audit L-5: no fallback to machine-local paths)
bash scripts/duo-inject-demo.sh
```

The two commands inside the drill script are equivalent to the following (`duo` below is shorthand
for `java -cp <classpath> io.duo.sim.control.cli.DuoCli`; the script assembles the classpath — the
repo does **not** ship a `duo` executable):

```bash
duo run duo-sim-examples/src/main/resources/scenarios/m3-inject-demo.yaml \
    --inject-after 3s "crash workers[2]" --wait      # same-process mode

# cross-process mode: serve requires a token (or DUO_TOKEN / --token-file);
# without one it refuses to start — unless explicitly given --insecure-no-auth (local debugging only)
duo serve <scenario.yaml> --port 0 --token dev-secret &
duo status   --url http://127.0.0.1:<port> --token dev-secret
duo topology --url http://127.0.0.1:<port> --token dev-secret
duo inject crash workers[2] --url http://127.0.0.1:<port> --token dev-secret
duo events   --since 0 --url http://127.0.0.1:<port> --token dev-secret
duo assert   --url http://127.0.0.1:<port> --token dev-secret    # exit code reflects assertion pass/fail
duo metrics  --url http://127.0.0.1:<port> --token dev-secret    # Prometheus text format (scrapeable as-is)
duo diagnose --url http://127.0.0.1:<port> --token dev-secret    # full causal chain of one injection; broken chain ⇒ exit code 1
```

> **Control-plane security posture (security audit 2026-09-20)**: the control plane listens only on
> `127.0.0.1`, but "being able to reach the port" is **not** a trust boundary — every same-machine
> process and every web page in a browser can reach it. Therefore: ① all endpoints except `/health`
> require a Bearer token; ② `Host`/`Origin` must be loopback (blocks DNS-rebinding / CSRF);
> ③ request bodies are capped at 1 MiB. Scenarios submitted via `POST /scenario` are validated
> under the **external-input profile**: `launch.command` forbidden, out-of-framework `sut.main`
> forbidden, `config` key whitelist, and values reject absolute paths and URL schemes. Local YAML
> files (CLI/tests) are not restricted. Details in `docs/ARCHITECTURE.md` §12.1 and
> `docs/DECISIONS.md` (D10/D11).

### 5.4 Observability: Using the Three Channels (M8)

The three channels promised by §11 are **complementary** — do not substitute one for another:

| Channel | Audience | Entry point | When to use |
| --- | --- | --- | --- |
| Event stream | machines | `events.jsonl` / `duo events` | assertions and recording (the single "source of truth") |
| Logs | humans | console / `grep FAULT` | reading causal chains: "who did what to whom, and why it was rejected" |
| Metrics | aggregation systems | `GET /metrics` | trends and alerting: "did heartbeat throughput drop?" |

Logs are human-readable on the console by default; third-party loggers
(ZK/Curator/Netty/Testcontainers/H2) are denoised down to WARN. Two switches:

```bash
-Dduo.log.level=DEBUG     # Duo's own DEBUG output (default INFO)
-Dduo.log.json=INFO       # additionally emit structured JSON lines (default off; zero new dependencies)
```

Export the full causal chain of one fault injection (injection → component reactions → SUT facts →
assertions) with a single command:

```bash
duo diagnose --url http://127.0.0.1:<port>
# source: http://127.0.0.1:51422  total events: 608  injections: 1
#
# [1] inject crash → workers-1  (event #6)
#     result: issued   window up to event #598
#     component reactions (5): sim.worker-instance-crashed@workers-1  sim.worker-task-status@workers-2 …
#     SUT facts (586 entries, 8 kinds): sut.heartbeat×564 […]  sut.task-dispatched×4 […] …
#
# assertions: 1, failed: 0
#     PASS noTaskLost: 4 task(s) terminal, all SUCCESS
```

**Broken chains are reported explicitly**: if an injection produces no `sut.*` facts at all ⇒ the
report prints `MISSING` with the reasons and exits non-zero ("the injection was issued but the
subject under test did not react" is precisely the situation that most needs to be seen).
Metric semantics (all 19 metric families + known boundaries) are documented in
[`docs/METRICS.md`](docs/METRICS.md).

### 5.5 Write a Scenario Test (JUnit 5)

```java
@VirtualCluster("/scenarios/junit-extension-smoke.yaml")
class MyScenarioTest {

    @Test
    void dagShouldSurviveWorkerCrash(ScenarioEngine engine) {
        // the engine is already started per the YAML; in the test body you can hot-inject,
        // read events, read assertion results
        engine.inject(new FaultAction("crash",
                FaultAction.ComponentAddress.ofInstance(new ComponentId("workers"), 1),
                Map.of(), null));
        DuoAssertions.assertThat(engine.events())
                .affectedTasksAtLeast(1)
                .noTaskLostAllSuccess();
    }
}
```

## 6. Contract × Tier (currently implemented implementations)

| Contract | Type | virtual | embedded | container | real |
| --- | --- | --- | --- | --- | --- |
| `registry` | standard protocol | `VirtualRegistry` (in-process state machine, endpoint NONE, supports `registry-flap`) | `CuratorRegistry` (real ZK port + same-process facade, supports `registry-flap`) | `ZookeeperContainerRegistry` (Testcontainers ZK, **flap/restart not supported**) | — |
| `worker` | interactive | `VirtualWorker` (Duo-protocol port, instance-level control, supports `task-kill`/`freeze`/`slow`/`resource-exhaust`) | — (interactive contracts have no such form) | — | `DemoRealWorker` (kernel-hosted reference implementation), **`RealWorkerSut` (worker-side SUT: real dialing + register/heartbeat/claim/report + disconnect self-healing)** |
| `scheduler` | interactive | `VirtualScheduler` (Duo-protocol port + registers `/duo/endpoints/scheduler`, supports `freeze`; requires **DIRECT** registry wiring) | — | — | `DemoScheduler` (**reference SUT**) |
| `engine` | interactive | `VirtualEngine` (DUO_PORT server: `task-dispatch` on first frame, then `task-cancel`; plus an in-process `EngineContract.submit`, supports `freeze`/`slow`/`resource-exhaust`) | — | — | — |
| `store` | standard protocol | — | `H2Store` (real in-memory H2 + JDBC URL) | `PostgresContainerStore` (Testcontainers `postgres:16-alpine`, **`restart()` not supported**, explicitly rejects `store.jdbcUrl`) | — |
| `resource` | standard protocol | `VirtualResourceManager` (quota allocator, supports `resource-exhaust`) | `Fabric8K8sMock` (real K8s REST protocol) | — | — |
| `message` | standard protocol | `VirtualMessageBroker` (per-topic FIFO; `message.maxDepthPerTopic` defaults to 10000) | — | — | — |
| `filestore` | standard protocol | `VirtualFilestore` (FS_PATH temp root or `filestore.root`; rejects `../` escapes) | — | — | — |

> Both scheduler tiers share one DAG/retry/failover implementation: `SchedulerStateMachine` and
> `DispatchSelector` live in `duo-sim-components` (moved there from `duo-sim-examples` in M5
> round 4, with 18 tests migrating alongside). Fault-action support matrix: `freeze` = worker +
> engine + scheduler; `slow` = worker + engine; `resource-exhaust` = worker + engine + resource
> (see [Architecture](docs/ARCHITECTURE.md) §5.4).

**Tier-swap matrix** (same pipeline; who plays the SUT can change):

| Combination | Scenario / case | What it covers |
| --- | --- | --- |
| virtual × virtual | `m0-acceptance-virtual-workers.yaml` | SUT = `DemoScheduler` (master side) |
| real × real | `m0-acceptance-real-workers.yaml` | SUT = `DemoScheduler`, workers are `DemoRealWorker` |
| **real-tier worker SUT × virtual-tier master** | `m0-acceptance-real-worker-sut.yaml` + `WorkerSutAcceptanceTest` | **SUT = `RealWorkerSut` (worker side)**: real discovery → dial → register → heartbeat → claim/execute/report → self-healing on disconnect |

Tier gaps and the plan to close them: [Roadmap M5](docs/ROADMAP.md#m5--契约与档位补全广度).

## 7. A Minimal Scenario

```yaml
name: worker-crash-failover
topology:
  - id: zk
    contract: registry
    tier: virtual                    # in-process state machine (no endpoint) → wiring defaults to direct
  - id: master
    contract: scheduler
    tier: real
    sut: true                        # exactly one per scenario
    launch: { mode: in-process, main: io.duo.sim.examples.scheduler.DemoScheduler }
    config: { dag.tasks: "job-a,job-b,job-c,job-d" }
    exposes: [{ contract: scheduler, port: 0 }]      # port 0 = kernel-assigned
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
    target: workers[3]               # instance index starts at 1
  - at: 27s
    action: restart
    target: workers[3]
assertions:
  - affectedTasksAtLeast: { min: 1 } # empty-truth guard
  - failoverWithin: { seconds: 30 }
  - noTaskLost: { requireAllSuccess: true }
  - eventSequence: [sim.fault-injected, sut.task-retry]
```

Field reference, validation rules and assertion semantics:
[`docs/SCENARIO-DSL.md`](docs/SCENARIO-DSL.md).

> **Switching to the worker side**: move `sut: true` from `master` to the `workers` node
> (`tier: real`, `launch.main: io.duo.sim.examples.worker.RealWorkerSut`) and set master back to
> `tier: virtual` — the SUT becomes the scheduled party while the rest of the YAML stays unchanged.
> Full example:
> [`m0-acceptance-real-worker-sut.yaml`](duo-sim-examples/src/main/resources/scenarios/m0-acceptance-real-worker-sut.yaml).

## 8. Status & Gaps (One-Page Overview)

| Original goal (design §2) | Status | Evidence / gap |
| --- | --- | --- |
| 1 Composable (YAML-described topology) | ✅ achieved | `ScenarioLoader` + `WiringResolver` topologically-ordered startup |
| 2 Anything testable (SUT + doubles) | 🟡 partial | in-process and **external (M6: zero-dependency third-party process, end-to-end accepted)** are both closed loops; **all 8 contracts have double implementations**; **the worker-side real SUT has landed** (`RealWorkerSut` + `WorkerSutAcceptanceTest`, three end-to-end cases; master side reuses the kernel's one state machine); remaining gap = the gold-standard scenario set (G4: one positive + one fault case per contract) is still open |
| 3 Replaceable (tier swap, zero code changes) | 🟡 partial | M0 `TierSwapAcceptanceTest` passes; `registry` (three tiers) and `store`/`resource`/`worker`/`scheduler` (two tiers each) have multi-tier implementations; the two `scheduler` tiers share one source (`SchedulerStateMachine`) |
| 4 Controllable behavior (task-stub scripts) | ✅ achieved | `BehaviorProfile` full 8-field set (M1) |
| 5 Faults injectable (timeline + hot inject) | ✅ achieved | `crash`/`restart`/`registry-flap`/`task-kill`/`custom-hook` landed; **M5 round 4** added `freeze` (worker/engine/scheduler), `slow` (worker/engine), `resource-exhaust` (worker/engine/resource) — all idempotent, all declaring `supportedFaults` |
| 6 Real feedback (real protocol ports) | ✅ achieved | the embedded tier exposes real ZK/JDBC/K8s ports; interactive contracts speak the Duo wire protocol (**the worker side is real protocol too**: `RealWorkerSut` registers/heartbeats/claims/reports over `FrameConnection`); the container tier adds real PostgreSQL + real ZooKeeper, **five evidenced green runs on the CI `container` job** (latest: run `36115793273`, all three jobs green; the 10 container cases skip by design when no Docker is present locally) |
| 7 Second-level feedback loop (single JVM, zero Docker) | ✅ achieved | full regression **397 tests / 0 failures / 0 errors / 12 skips** (10 container-tier cases due to no local Docker, 1 stress switch off, 1 `-Dduo.ds=true` real-SUT drill gate off — its always-on guard `DsFailoverDrillGuardTest` runs ungated in the regular regression; `-Dduo.docker.enabled=false` turns "no Docker" into a fact rather than an accident) |
| 8 CI-friendly (JUnit 5 + assertions + scenarios in version control) | ✅ achieved | extension + assertion libraries, standard Wrapper, three CI jobs (M7; remote continuously green), release artifacts (source/javadoc + CHANGELOG), and the **dependency quality gate (`-Dquality`, round 11, wired into CI)** |
| — Observability (design §11, M8) | ✅ achieved | all three channels landed: event-stream recording (pre-existing) + logs (`logback.xml`/`logback-test.xml`, `io.duo.sim.fault` causal chain) + metrics (`/metrics`, 19 metric families); single-command causal-chain export via `duo diagnose` |

**One-sentence summary**: goals 1/4/5/6/7/8 and observability are achieved; what remains for 2/3 is
**breadth**, not capability — the gap list (G1–G11) is fully closed, and the only registered open
item is M8 deliverable 4, "accelerated-clock evaluation" (its trigger — an hour-scale soak scenario
on the virtual tier — has not occurred yet; the `SimClock` interface is reserved, so there is no
rework cost).

Full gap analysis and stage plans: **[docs/ROADMAP.md](docs/ROADMAP.md)**.

## 9. Documentation Index

> The deep-dive docs under `docs/` and the per-module READMEs are currently maintained in Chinese;
> this file is the unified English entry point.

| Document | Content |
| --- | --- |
| [`docs/README.md`](docs/README.md) | Repo-wide doc index and reading paths |
| [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md) | Architecture deep-dive: modules, SPI, contracts & tiers, wiring rules, event bus, SUT adapter surface, control plane, wire protocol |
| [`docs/SCENARIO-DSL.md`](docs/SCENARIO-DSL.md) | Scenario DSL reference (full field set / validation rules 1–8 / assertion semantics / built-in component config keys) |
| [`docs/METRICS.md`](docs/METRICS.md) | `/metrics` metric semantics (all 19 metric families: meaning, refresh timing, known boundaries) |
| [`docs/DEVELOPMENT.md`](docs/DEVELOPMENT.md) | Development guide: environment, build, test layering, extension points, conventions, known engineering debt |
| [`docs/ROADMAP.md`](docs/ROADMAP.md) | Roadmap: goal-achievement inventory, gap list G1–G11, stage plans and acceptance criteria |
| [`docs/DECISIONS.md`](docs/DECISIONS.md) | Decision ledger: rulings (decision / rationale / trigger / landing point) and revision records |
| [`docs/superpowers/specs/`](docs/superpowers/specs) | Design document v1.0 (frozen, the single basis) |
| [`docs/superpowers/plans/`](docs/superpowers/plans) | Implementation plans per stage |
| [`docs/superpowers/acceptance/`](docs/superpowers/acceptance) | Acceptance records, ten-thousand-scale stress reports, independent re-verification records |
| [`CHANGELOG.md`](CHANGELOG.md) | Version policy and unreleased/released changes |

## 10. License

**Apache-2.0** (chosen in design doc §15, following big-data ecosystem convention) — full license
text at the repo root: [`LICENSE`](LICENSE).

---

<div align="center">

**Duo** · One YAML file squeezes big-data pipeline fault drills into a second-level feedback loop

</div>
