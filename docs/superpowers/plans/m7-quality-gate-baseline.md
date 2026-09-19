# 第 11 轮基线：`mvnw -Dquality -DskipTests verify` 的 7 类告警（**逐条实测原文**）

> 取证命令（离线、跳过测试，只跑 9 个模块的 `dependency:analyze-only`）：
> `.\mvnw.cmd -o -B "-Dquality" "-DskipTests" verify`

## 基线（**门禁未加任何豁免时**，2026-09-19 第 11 轮）

| # | 模块 | 类别 | 依赖 | 本仓的取舍 |
| --- | --- | --- | --- | --- |
| 1 | protocol / kernel / scenario / components / embedded / examples | **Used undeclared** | `org.junit.jupiter:junit-jupiter-api:5.11.4:test` | **消除**：各模块显式声明（父 POM 已管理版本） |
| 2 | 同上 6 个模块 | **Unused declared** | `org.junit.jupiter:junit-jupiter:5.11.4:test` | **保留 + 显式豁免**：`junit-jupiter` 是空壳聚合件，真正被 import 的是 api/params/engine；硬禁传递会让每模块抄 3 行声明 |
| 3 | protocol | **Used undeclared** | `com.fasterxml.jackson.core:jackson-annotations:2.18.2:compile` | **消除**：显式声明（注解被主代码直接 import） |
| 4 | control | **Used undeclared** | `com.fasterxml.jackson.core:jackson-core:2.18.2:compile` | **消除**：显式声明（流式类型被主代码直接 import） |
| 5 | embedded | **Used undeclared** | `io.fabric8:mockwebserver`、`kubernetes-client-api`、`kubernetes-model-core`、`org.apache.zookeeper:zookeeper`、`org.apache.curator:curator-client` | **保留 + 显式豁免**：这些是 embedded 档所承载的**真实第三方服务本身的传递件**，Duo 代码不 import（`Fabric8K8sMock` 只 new mock server，客户端由被测组件自己创建）。要求 "你承载的服务的全部库都写进你的 POM" 是把上游依赖树抄一遍，上游改版即断 |
| 6 | examples | **Used undeclared** | `ch.qos.logback:logback-core`、`org.slf4j:slf4j-api`、`org.apache.curator:curator-test` | **保留 + 显式豁免**：examples 是**端到端宿主/示例**模块，职责就是"像用户那样把整条链路跑起来"；它声明的 classic/curator-framework 为把整棵树拉齐而存在，测试直接用 `TestingServer` 是示例的正当用法 |
| 7 | embedded / examples | **Non-test scoped test only** | embedded：`io.fabric8:kubernetes-server-mock`（及其件 `mockwebserver`、`kubernetes-model-core`、`kubernetes-client-api`、`kubernetes-model-common`）；examples：全部 compile 依赖 | 同 #5/#6 的理由。embedded 若改成 test scope，embedded 档运行期直接缺件（**静默的降级**，比告警危险得多） |

## 收敛后的门禁配置（`pom.xml` → `quality` profile）

- 生效开关：`-Dquality`（缺省不跑 ⇒ 常规 `mvnw test` 零额外开销，与 `release` 档同一手法）；
- `failOnWarning=true`：本仓当前分析结果已收敛到零告警 ⇒ **新增一条告警即构建失败**；
- `ignoreNonCompileDirectives=true` 在 3.8.1 上是未知参数（实测 `[WARNING] Parameter ... is unknown`），
  已删除——要管的是本仓的 POM，不是上游库的树；
- 5 个直接依赖的消除（#1/#3/#4）是**真修复**；其余 4 类逐条 `ignored*` 显式豁免并附理由注释。

## 预期终态（**已实测通过**）

```text
.\mvnw.cmd -o -B "-Dquality" "-DskipTests" verify
→ 9 个模块：8 × "No dependency problems found" + parent(pom packaging，按设计跳过) + BUILD SUCCESS
```

配套实测：`.\mvnw.cmd -o -B test` → **366 测 / 0 失败 / 0 错误 / 11 skip / BUILD SUCCESS**
（依赖调整后回归无变化）。

## 门禁的"真会失败"验证

门禁配了 `failOnWarning=true`。要确认它**不是纸老虎**，可临时在任一模块加一条没被使用的
声明依赖，例如 `duo-sim-kernel/pom.xml` 加：

```xml
<dependency>
    <groupId>org.yaml</groupId>
    <artifactId>snakeyaml</artifactId>
</dependency>
```

再跑 `.\mvnw.cmd -o -B "-Dquality" "-DskipTests" verify` 应当**构建失败**并指名该依赖
（本轮开发过程中反复见过同形态的失败输出：`Unused declared dependencies found:` +
`Dependency problems found` + `[ERROR] Failed to execute goal ... analyze-only`）。

## 再基线

本条基线对应的"再基线"命令（本轮之后若要重新审视豁免清单）：

```powershell
# 临时去掉 quality profile 的全部豁免：把 pom.xml 中 quality profile 的
# maven-dependency-plugin configuration 整段注释掉，再跑：
.\mvnw.cmd -o -B dependency:analyze
```
