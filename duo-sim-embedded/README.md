# duo-sim-embedded

**embedded / container 档适配**：JVM 内真实第三方协议实现，与按需拉起的真容器。

- 依赖：`duo-sim-kernel`、`curator-framework`/`curator-test`、`h2`、`kubernetes-server-mock`（Fabric8）、`testcontainers`、
  `testcontainers-postgresql` + `org.postgresql:postgresql`
- 测试：55 条，其中 **10 条容器档用例在无 Docker 时按设计 skip**（4 条 ZookeeperContainer + 6 条 PostgresContainer；`.\mvnw -o -pl duo-sim-embedded -am test`）；`-Dduo.docker.enabled=false` 可**确定性**关闭容器档（CI regression job 用）
- SPI 注册：`CuratorRegistryProvider`、`H2StoreProvider`、`Fabric8K8sMockProvider`、`ZookeeperContainerProvider`、`PostgresContainerStoreProvider`

## 实现

| 实现 | 契约/档位 | 端点形态 | 说明 |
| --- | --- | --- | --- |
| `CuratorRegistry` | `registry` / `embedded` | `THIRD_PARTY` + `interfaceDirect` | **双面**：`endpoints()` 暴露真实 ZK 端口（SUT 用真实 Curator 客户端）；同进程门面实现 `RegistryContract`（框架组件 direct 消费）。门面持有**独立会话**，闪断时两者都断、各自重连。支持 `registry-flap`（`TestingServer.restart`，**瞬时整服重启**，`duration` 被忽略） |
| `ZkBackedRegistry` | — | — | 双面骨架抽取（供 embedded 与 container 复用） |
| `ZookeeperContainerRegistry` | `registry` / `container` | `THIRD_PARTY` + `interfaceDirect` | Testcontainers ZK。**不支持 `registry-flap`，且 `restart()` 显式抛 `UnsupportedOperationException`**——换宿主端口会让 wire 客户端永久挂起（M4 独立验收 HIGH 整改） |
| `H2Store` | `store` / `embedded` | `THIRD_PARTY` | 真实 H2 内存库：建表/插入/查询/事务提交回滚、连接事件、慢查询事件、自定义 `jdbcUrl`。**无 interface-direct**（SUT 用真实 JDBC 驱动） |
| `PostgresContainerStore` | `store` / `container` | `THIRD_PARTY` | 真实 PostgreSQL（Testcontainers，镜像 `postgres:16-alpine`，JDBC 驱动 `org.postgresql:postgresql:42.7.4`）。**无 interface-direct**；**不支持 `restart()`**（显式抛 `UnsupportedOperationException`：容器 URL 只能来自映射端口，重建即换端口）；**显式拒绝 config `store.jdbcUrl`**（端口由容器映射，不接受外部指定） |
| `Fabric8K8sMock` | `resource` / `embedded` | `THIRD_PARTY` | `KubernetesMockServer` 非 CRUD 模式 + 显式 expect；Pod CRUD 经真实 K8s REST 往返；重启重建 |

## 依赖坑（改动前必读）

- `curator-test`、`kubernetes-server-mock` 都需**排除传递的 `junit-jupiter-api` / `junit-platform-commons`**，
  否则与项目的 JUnit 5.11.4 冲突导致 surefire 测试发现失败。
- `jackson-annotations` 显式钉到 `2.18.2`：Testcontainers → docker-java 会传递 `2.10.3`，
  压过 Fabric8 mock 需要的 `2.17+`（`JsonKey` 缺失 → `NoClassDefFoundError`）。
- **PostgreSQL 容器档的坐标必须与核心 Testcontainers 同代**：本模块核心是
  `org.testcontainers:testcontainers:2.0.5`，故用 `org.testcontainers:testcontainers-postgresql:2.0.5`。
  旧的 1.x 坐标 `org.testcontainers:postgresql` 最高只到 1.21.4，**不可与 2.x 核心混用**（混用即 NoSuchMethod/类缺失）。

## 事件与限制（`PostgresContainerStore`）

- 事件：`sim.store-started`（载荷带 `kind=container`）、`sim.store-stopped`、`sim.store-crashed`、
  `sim.store-connection-opened`、`sim.store-slow-query`。
- **刻意不发** `sim.store-connection-closed`——与既有 `H2Store` 同因：SUT 持有的是裸 JDBC 连接，
  其关闭不可观测（不发明观测不到的事实）。
- 该档 6 条用例（`PostgresContainerStoreTest`）标 Docker 门控，本机无 Docker 时 skip；
  **已在 CI `container` job 上取证为绿**（run 35341365256：`PostgresContainerStoreTest` 6/6、`ZookeeperContainerRegistryTest` 4/4，`skip=0` 门禁通过）。

## 门控

容器档用例标 `@EnabledIf(dockerAvailable)`；同时有 **15 条不标门控**的守卫用例
（`ZookeeperContainerRegistryGuardTest` 5 + `PostgresContainerStoreGuardTest` 10）
离线验证「不支持＝显式拒绝」这一安全属性——**安全属性不能只被门控覆盖**（M4 独立验收教训）。

→ [架构说明 · 双面实现](../docs/ARCHITECTURE.md#53-双面实现third_party--interfacedirecttrue-并存) · [开发指南 · 依赖纪律](../docs/DEVELOPMENT.md#5-模块依赖纪律)
