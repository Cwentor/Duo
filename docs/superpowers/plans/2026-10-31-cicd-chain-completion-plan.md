# CI/CD 自动化链条补全 实施计划

> 本计划交 dev-executing-plans 逐任务执行；步骤用 `- [ ]` 勾选跟踪。本文件同时充当台账。

**Goal:** 补全 CI/CD 链条断点——镜像「可构建可运行」进 CI 门禁（P1-1）；同步收口触发面盲区（P2-1）、供应链漏洞扫描（P2-2）、冒烟脚本游离（P2-3）。**P1-2（tag→ghcr→GitHub Release 发布链）经用户拍板暂缓**，设计保留见文末「暂缓项」。2026-10-31 二轮复审并入 N1/N4/N5（落在 Task 4/1/7）与设置面手动项（N2/N3）；N6–N10 记台账待议。

**Architecture:** `ci.yml` 新增并行 `image` job（docker build + 冒烟脚本 26 步 API 往返）与 nightly `supply-chain` job（trivy 报告制）；新增 `.github/dependabot.yml` 四生态。不引入任何发布类 workflow。

**Tech Stack:** GitHub Actions（runner pin `ubuntu-24.04`）、既有 Dockerfile 三段构建、trivy-action（唯一新增 action，执行时现场取 SHA pin）、dependabot。

**Spec:** 无独立规格文件；需求来源＝2026-10-31 CI/CD 全链审计结论（P1-1/P2-1/P2-2/P2-3，见会话记录与本文档各任务注释）。计划自含。

## Global Constraints（逐字自仓库既有纪律，所有任务默认隐含）

- 所有 action pin 到 **commit SHA**，注释保留人类可读版本号；升级＝改 SHA＋改注释，一次提交一件事（ci.yml 头注，L-6）。
- runner pin `ubuntu-24.04`，不用 `ubuntu-latest`；换代必须先 dispatch 验证全绿再改 pin（G9、413×2 前科）。
- **skip 必须可见、可解释**（T8）——本计划不新增任何 skip 路径。
- 提交信息按实测数字写，一次提交一件事。
- 本地 Git Bash 验证 mvnw 需 `export JAVA_HOME=<devtools/jdk-21.0.12.1+1>`；`-Dtest` 配 `-am` 须加 `-Dsurefire.failIfNoSpecifiedTests=false`。
- 冒烟脚本令牌必填是镜像安全姿态（C-1）——CI 里用一次性临时令牌，不设免鉴权捷径。

## Review Focus（规格隐含的失效模式 → 期望行为；#N 对应任务内测试）

1. Dockerfile 内 `dependency:go-offline || true` 吞错 → 依赖解析失败必须在后续 `package` 步骤红掉，镜像构建失败必须可见（Task 3 本地预演正常路径；失败路径靠 `mvn package` 自身退出码，不额外造障）。
2. 容器未就绪即冒烟 → `/health` 探活循环先行（60×1s，超时打印 `docker logs` 后 fail）（Task 3 Step 3）。
3. 冒烟 `FORK_ID` 固定 `smoke-api-review` → 容器每次全新启动、/data 匿名卷即弃，fork 必成功；重跑前 `docker rm -f duo || true` 幂等清理（Task 3 Step 2/3）。
4. dependabot 版本 PR → 必须经既有 `pull_request` 触发的 CI 全绿才可合；github-actions 生态应自动改 pin-SHA＋注释（首次 PR 实测确认，Task 4 Step 3）。
5. runner 上 `python` 命令缺失 → 冒烟脚本取 YAML 有 sed fallback 内置（`python -c ... || sed ...`），CI 实跑一次确认降级路径不炸（Task 3 Step 3）。
6. `image.yml` 自身在自身 paths-ignore 内 → 改 workflow 逻辑不自触发，必须手动 `workflow_dispatch` 验证后再提交（Task 3 Step 3 附注，G9 纪律）。
7. 跳过 javadoc 后「注释可编译」失去 CI 兼任门禁 → Task 7 Step 1 先取健康基线（2026-10-27 全仓注释补齐后全仓通过）；防回归依赖本地 package 习惯，如实声明、不装假门禁。

---

### Task 1: ci.yml 触发面补 dev/review/feat 分支

**Files:**
- Modify: `.github/workflows/ci.yml:15-17`（`on.push` 块）

**Interfaces:**
- Produces: 后续 Task 3/5 的 CI 验证依赖本任务先行——review/feat 分支推送必须触发 run，否则镜像门禁与扫描 job 永远只能事后验证。注意 ci.yml 与 image.yml（Task 3）触发面同族，改一族须两处同步。

- [ ] **Step 1: 修改触发块**

```yaml
on:
  push:
    # 2026-10-31 审计 P2-1：原 [master, main] 使 dev/review 分支推送零 CI——
    # 门禁退化成「squash 合入 master 后的事后验证」。补 dev/review/feat 三族；
    # concurrency cancel-in-progress 已保证旧推送被新推送顶掉，不烧额度。
    branches: [master, main, dev, 'review/**', 'feat/**']
  pull_request:
  workflow_dispatch:
  schedule:
    # nightly 压测（UTC 18:00 = 次日 02:00 CST）
    - cron: '0 18 * * *'
```

- [ ] **Step 2: Commit（触发面一笔）**

```bash
git add .github/workflows/ci.yml
git commit -m "ci: 触发面补 dev/review/feat——门禁从合并后事后跑提前到推送即跑（P2-1）"
```

- [ ] **Step 3: 三 job 加 timeout-minutes（审计 N4；一次提交一件事，单独一笔）**

`regression`/`container` 在各自 `runs-on: ubuntu-24.04` 下一行加 45，`scale` 加 120（压测留余量）：

```yaml
  regression:
    name: regression (no Docker)
    runs-on: ubuntu-24.04
    timeout-minutes: 45   # 缺省 360 分钟挂死面收口（审计 N4）
```

- [ ] **Step 4: Commit（timeout 一笔）**

```bash
git add .github/workflows/ci.yml
git commit -m "ci: 三 job 加 timeout-minutes（regression/container 45、scale 120）——挂死面收口（N4）"
```

- [ ] **Step 5: 语法自检 + 推送观察触发**

Run: `ruby -ryaml -e 'YAML.load_file(".github/workflows/ci.yml"); puts "yaml ok"'`（或任意本地 YAML 解析器）
Expected: `yaml ok`

Run: `git push origin review/code-structure-audit`，然后在 GitHub Actions 页确认本分支出现新 run 且各 job 状态正常（scale 仅 nightly 条件，不出现）。
Expected: run 出现、regression 全绿（container 与本仓 Dockerfile 无关，不受影响）。

---

### Task 2: 冒烟脚本用法注释对齐 + DEVELOPMENT 镜像节补引用

**Files:**
- Modify: `scripts/duo-smoke-docker.sh:4`（头注释 usage 行）
- Modify: `docs/DEVELOPMENT.md`（「镜像」节 `docker run` 代码块之后，约 159-160 行）

**Interfaces:**
- Consumes: 无。
- Produces: 冒烟脚本的权威引用路径 `scripts/duo-smoke-docker.sh`——Task 3 的 CI 步骤与 Task 6 的文档收口都指向它。

- [ ] **Step 1: 修脚本头注释第 4 行**

```bash
# 用法：BASE=http://127.0.0.1:8080 DUO_TOKEN=<T> bash scripts/duo-smoke-docker.sh
```

- [ ] **Step 2: DEVELOPMENT.md 镜像节补冒烟引用**

在 `docker run -d --name duo -p 8080:8080 -e DUO_TOKEN=<T> duo-sim` 代码块之后追加一行（对齐该节行文风格）：

```markdown
   容器就绪后可跑 26 步 API 冒烟（G-W2 等价序列）：
   `BASE=http://127.0.0.1:8080 DUO_TOKEN=<T> bash scripts/duo-smoke-docker.sh`
```

- [ ] **Step 3: 验证**

Run: `bash -n scripts/duo-smoke-docker.sh && grep -rn "scripts/duo-smoke-docker.sh" docs/DEVELOPMENT.md`
Expected: `bash -n` 无输出（语法通过）+ grep 命中 DEVELOPMENT.md 一处；且 `grep -rn "build/smoke-docker" scripts/ docs/` 零命中。

- [ ] **Step 4: Commit**

```bash
git add scripts/duo-smoke-docker.sh docs/DEVELOPMENT.md
git commit -m "docs(smoke): 冒烟脚本用法路径对齐 scripts/ + DEVELOPMENT 镜像节补引用（P2-3）"
```

---

### Task 3: 新增 image.yml 独立 workflow——构建 + 冒烟进门禁（P1-1 核心）

**Files:**
- Create: `.github/workflows/image.yml`

**Interfaces:**
- Consumes: `scripts/duo-smoke-docker.sh`（Task 2 已对齐路径，接受 `BASE`/`DUO_TOKEN` 环境变量，26 步断言，任一失败 exit 1）。
- Produces: 每次 push/PR 的「镜像可构建可运行」门禁；Task 6 文档表格引用 workflow 名 `image`。

- [ ] **Step 1: 本地全序列预演（Git Bash，本机 Docker 已有 26/26 基线）**

```bash
docker build -t duo-sim:ci .
docker rm -f duo || true
docker run -d --name duo -p 8080:8080 -e DUO_TOKEN=ci-smoke-token duo-sim:ci
for i in $(seq 1 60); do curl -sf http://127.0.0.1:8080/health | grep -q '"ok":true' && break; sleep 1; done
BASE=http://127.0.0.1:8080 DUO_TOKEN=ci-smoke-token bash scripts/duo-smoke-docker.sh
docker rm -f duo
```

Expected: build 成功；health 循环数秒内 break；冒烟脚本逐条 `PASS` 直至末尾（本机基线 26/26）；清理成功。

- [ ] **Step 2: 写 image.yml（赘余审查 R2 拍板：独立 workflow + paths-ignore）**

```yaml
# 镜像门禁（2026-10-31 审计 P1-1 + 赘余审查 R2）：独立 workflow 而非 ci.yml 内 job——
# .dockerignore 已把 docs/、*.md、architecture-ui/、.github/ 排除在构建上下文外，
# docs-only 提交产出的镜像与上一次逐字节相同，paths-ignore 短路这类空转
# （本仓 docs 类提交占比高，非理论问题）。
# 注意：本文件自身在 paths-ignore 内——改本文件不自触发，按 G9 纪律手动 dispatch 验证。
# nightly 的镜像构建+trivy 扫描归 ci.yml 的 supply-chain job（无双构建，R2 分工）。
name: image

on:
  push:
    # 触发面与 ci.yml（Task 1）同族——两处列表须同步改
    branches: [master, main, dev, 'review/**', 'feat/**']
    paths-ignore:
      - '**.md'
      - 'docs/**'
      - 'architecture-ui/**'   # 不进构建上下文（.dockerignore 对账）
      - '.github/**'
      - 'LICENSE'
  pull_request:
    # GitHub Actions 不支持 YAML 锚点，paths-ignore 两处显式重复
    paths-ignore:
      - '**.md'
      - 'docs/**'
      - 'architecture-ui/**'
      - '.github/**'
      - 'LICENSE'
  workflow_dispatch:

concurrency:
  group: image-${{ github.ref }}
  cancel-in-progress: true

permissions:
  contents: read

jobs:
  build-and-smoke:
    name: build & smoke
    runs-on: ubuntu-24.04
    timeout-minutes: 45   # 缺省 360 分钟挂死面收口（审计 N4）
    # P1-1：Dockerfile 本体此前从未被 CI 构建——SPA 产物路径 / 模块 jar 拷贝 /
    # libs 收敛任何一处被日常改动破坏，CI 全绿但镜像已坏。
    # 构建 + 26 步 API 冒烟（G-W2 等价）；令牌为一次性临时值（C-1 姿态不变）。
    steps:
      - uses: actions/checkout@3d3c42e5aac5ba805825da76410c181273ba90b1 # v7.0.1
      - name: build image
        run: docker build -t duo-sim:ci .
      - name: run container
        run: |
          docker rm -f duo || true
          docker run -d --name duo -p 8080:8080 -e DUO_TOKEN=ci-smoke-token duo-sim:ci
      - name: wait for /health (JVM 就绪，60s 上限)
        run: |
          ok=0
          for i in $(seq 1 60); do
            if curl -sf http://127.0.0.1:8080/health | grep -q '"ok":true'; then ok=1; break; fi
            sleep 1
          done
          if [ "$ok" != "1" ]; then docker logs duo; exit 1; fi
      - name: smoke (26-step API round-trip)
        run: BASE=http://127.0.0.1:8080 DUO_TOKEN=ci-smoke-token bash scripts/duo-smoke-docker.sh
      - name: cleanup
        if: always()
        run: docker rm -f duo || true
```

（checkout 的 SHA 与注释照抄 ci.yml 既有行——同一版本同一纪律。）

- [ ] **Step 3: 推送并验证 run**

Run: `git push`（本提交含 image.yml，不受 paths-ignore 影响，必触发），Actions 页确认 `image / build & smoke` 全绿、smoke 步骤日志逐条 PASS；随后 `cat .dockerignore` 与 paths-ignore 清单逐行对账一遍。
Expected: job 绿；ignore 清单与 .dockerignore 排除面一致（多排=漏门禁、少排=空转）；若 smoke 某步红，按日志修（红＝门禁生效，正是本 job 的价值）。
附注（Review Focus #6）：此后任何对 image.yml 自身的修改不自触发——按 G9 手动 `workflow_dispatch` 验证后再提交。

- [ ] **Step 4: Commit**

```bash
git add .github/workflows/image.yml
git commit -m "ci(image): 独立 image workflow——构建+26 步冒烟进门禁，paths-ignore 短路 docs-only 空转（P1-1、R2）"
```

---

### Task 4: dependabot.yml 五生态接入（P2-2 起步 + 顺带 P3-2 + N1 基础镜像浮动 tag）

**Files:**
- Create: `.github/dependabot.yml`

**Interfaces:**
- Consumes: 既有 `pull_request` 触发的 CI（版本 PR 全绿才可合）。
- Produces: 每周版本/CVE 更新 PR；github-actions 生态自动维护 pin-SHA＋注释版本号。

- [ ] **Step 1: 写文件**

```yaml
# 供应链（2026-10-31 审计 P2-2/P3-2）：版本更新与 CVE 响应自动化。
# github-actions 生态同时解决「pin-SHA 手工升级」——dependabot 会连 SHA 带注释一起 bump，
# PR 走既有 pull_request CI（含 dispatch 纪律对应的验证环节），全绿才可合。
# 注释里的人类可读版本号（如 # v7.0.1）随升级 PR 自动更新。
version: 2
updates:
  - package-ecosystem: maven
    directory: /
    schedule:
      interval: weekly
  - package-ecosystem: npm
    directory: /console-ui
    schedule:
      interval: weekly
  - package-ecosystem: npm
    directory: /architecture-ui
    schedule:
      interval: weekly
  - package-ecosystem: github-actions
    directory: /
    schedule:
      interval: weekly
  # docker 生态（审计 N1）：Dockerfile 三个基础镜像均为浮动 tag（node:22-alpine /
  # maven:3.9-eclipse-temurin-21 / eclipse-temurin:21-jre-jammy），patch 漂移无感知——
  # 以升级 PR 补齐 L-6/A6 pin 纪律的覆盖面；digest 钉死更强但维护成本高，暂不采用。
  - package-ecosystem: docker
    directory: /
    schedule:
      interval: weekly
```

- [ ] **Step 2: Commit**

```bash
git add .github/dependabot.yml
git commit -m "chore(deps): dependabot 五生态接入——版本/CVE/基础镜像更新自动化起步（P2-2、P3-2、N1）"
```

- [ ] **Step 3: 合入 master 后实测首个 PR（Review Focus #4）**

dependabot 首轮 PR 到达后：确认其一为 github-actions 生态且 diff 同时改了 SHA 与注释；确认其 PR 上 CI 触发。任何一条不成立→本任务回补（例如 dependabot 对某 pin 格式不识别时的手工升级流程写回 DEVELOPMENT.md）。

---

### Task 5: ci.yml 新增 nightly supply-chain job——trivy 报告制（P2-2 主力）

**Files:**
- Modify: `.github/workflows/ci.yml`（`scale` job 之后追加）

**Interfaces:**
- Consumes: 根 `Dockerfile`。
- Produces: nightly artifact `trivy-report.json`（HIGH/CRITICAL 清单）；收严门禁（改 `exit-code: '1'`＋忽略清单文件）另立任务，基线观察 1–2 周后做。

- [ ] **Step 1: 现场获取 trivy-action 的 pin SHA（本计划唯一新增 action）**

Run:
```bash
git ls-remote https://github.com/aquasecurity/trivy-action 'refs/tags/v0.*' | tail -5
git ls-remote https://github.com/aquasecurity/trivy-action "refs/tags/<所选 tag>^{}"   # 取解引用后的 commit SHA
```
Expected: 得到所选版本的 commit SHA，填入下方 `uses:` 并写版本注释——执行者不得臆造 SHA。

- [ ] **Step 2: 写入新 job（追加在 `scale` job 之后）**

```yaml
  supply-chain:
    name: supply chain (nightly image scan)
    if: github.event_name == 'schedule' || github.event_name == 'workflow_dispatch'
    runs-on: ubuntu-24.04
    timeout-minutes: 45   # 缺省 360 分钟挂死面收口（审计 N4）
    # 2026-10-31 审计 P2-2：依赖门禁（-Dquality）只查 unused/undeclared，不查 CVE。
    # 与 image.yml 分工（赘余审查 R2）：nightly 镜像构建+扫描独家在本 job，无双构建。
    # trivy **报告制**：exit-code 0 只出报告，先取 1–2 周基线
    # 再收严为 HIGH/CRITICAL fail + 忽略清单（收严另立任务，一次提交一件事）。
    steps:
      - uses: actions/checkout@3d3c42e5aac5ba805825da76410c181273ba90b1 # v7.0.1
      - name: build image
        run: docker build -t duo-sim:nightly .
      - name: trivy scan (HIGH/CRITICAL, report-only)
        uses: aquasecurity/trivy-action@<Step 1 实取 SHA> # v0.XX.Y（执行时回填真实版本号）
        with:
          scan-type: image
          image-ref: duo-sim:nightly
          format: json
          output: trivy-report.json
          severity: HIGH,CRITICAL
          ignore-unfixed: true
          exit-code: '0'
      - name: summary (must be visible)
        if: always()
        run: |
          if [ -f trivy-report.json ]; then
            echo "HIGH/CRITICAL findings: $(jq '[.Results[]?.Vulnerabilities[]?] | length' trivy-report.json)"
          else
            echo "::error::trivy-report.json missing — scan did not run"
            exit 1
          fi
      - uses: actions/upload-artifact@043fb46d1a93c77aae656e7c1c64a875d1fc6a0a # v7.0.1
        if: always()
        with:
          name: trivy-report
          path: trivy-report.json
          if-no-files-found: error
```

- [ ] **Step 3: dispatch 验证**

Run: Actions 页 `workflow_dispatch` 触发 CI，确认 `supply-chain` job 绿、summary 打印 findings 数、artifact `trivy-report.json` 可下载且内容为合法 JSON（`jq empty trivy-report.json`）。
Expected: 全绿 + artifact 齐备；首份报告即基线，记入 Task 6 文档。

- [ ] **Step 4: Commit**

```bash
git add .github/workflows/ci.yml
git commit -m "ci(supply-chain): nightly trivy 镜像漏洞扫描（报告制取基线）（P2-2）"
```

---

### Task 6: 文档收口——DEVELOPMENT CI/CD 章节 + CHANGELOG

**Files:**
- Modify: `docs/DEVELOPMENT.md:125-126`（CI job 表——`regression`/`container` 行）
- Modify: `CHANGELOG.md`（`## [未发布]` 下补条目）

**Interfaces:**
- Consumes: Task 3 的 job 名 `image`、Task 5 的 job 名 `supply-chain` 与首份 trivy 基线数字。

- [ ] **Step 1: CI job 表补两行**（对齐既有表格列格式）

| job | 触发 | 内容 |
| --- | --- | --- |
| `image` | push / PR | 独立 workflow `image.yml`：`docker build` + 起容器跑 `scripts/duo-smoke-docker.sh` 26 步冒烟；paths-ignore 短路 docs-only 提交（镜像可构建可运行进门禁） |
| `supply-chain` | nightly / 手动 | `docker build` + trivy 扫 HIGH/CRITICAL（报告制，artifact `trivy-report.json`） |

- [ ] **Step 2: CHANGELOG 补条目**

在 `## [未发布]` 的「新增」下补（按实测改写，勿照抄）：

```markdown
- **CI/CD 链条补全（2026-10-31）**：`image` workflow（镜像构建+冒烟进门禁，docs-only
  paths-ignore 短路）、nightly `supply-chain` job（trivy 报告制）、dependabot 五生态
  （含 docker 基础镜像）、触发面补 dev/review/feat、job 级 timeout-minutes。
```

- [ ] **Step 3: 对账验证**

Run: `grep -n "image\|supply-chain" docs/DEVELOPMENT.md | head -10`
Expected: 表格两行均可检索；job 名与 ci.yml 实名一致（`grep -n "^  [a-z-]*:" .github/workflows/ci.yml`）。

- [ ] **Step 4: Commit**

```bash
git add docs/DEVELOPMENT.md CHANGELOG.md
git commit -m "docs(develop): CI/CD 章节收口——image/supply-chain/dependabot + CHANGELOG"
```

---

### Task 7: 构建瘦身——CI 门禁与镜像构建跳过 sources/javadoc（审计 N5）

**Files:**
- Modify: `.github/workflows/ci.yml`（regression job 的 dependency gate 步）
- Modify: `Dockerfile`（stage ② 的 `mvn package` 行，约 58 行）
- Modify: `CHANGELOG.md`（`[未发布]` 补一行）

**Interfaces:**
- Consumes: 无（纯命令行参数，零 pom 改动）。
- Produces: 门禁步与镜像构建不再产出 sources/javadoc jar；镜像 `cp duo-sim-*.jar` 通配不再吸入 `-sources/-javadoc` jar，体积同步减负。验证依赖 Task 1（触发面）+ Task 3（image.yml 门禁拦 Dockerfile 回归）。

- [ ] **Step 1: 取 javadoc 健康基线（跳过前确认无 doclint 地雷）**

Run: `./mvnw -B -DskipTests package`（本地 Git Bash，`JAVA_HOME` 指向 JDK 21）
Expected: `BUILD SUCCESS`——2026-10-27「全仓注释补齐」里程碑后 attach-javadocs 全仓通过，基线成立；若失败先修注释再回本任务。

- [ ] **Step 2: ci.yml 门禁步加 skip 参数**

```yaml
      - name: dependency gate (no unused/undeclared deps)
        # N5：sources/javadoc jar 无人消费（IDE attach 由本地构建承担）——门禁只到
        # verify 取 analyze-only，跳过纯打包工时。javadoc 兼任的「注释可编译」门禁
        # 以 Step 1 基线让位，防回归靠本地 package 习惯（如实声明）。
        run: ./mvnw -B -Dduo.docker.enabled=false -Dquality -DskipTests -Dmaven.source.skip=true -Dmaven.javadoc.skip=true verify
```

- [ ] **Step 3: Dockerfile stage ② 同款**

```dockerfile
RUN mvn -B -DskipTests -Dmaven.source.skip=true -Dmaven.javadoc.skip=true package dependency:copy-dependencies -DoutputDirectory=/repo/libs \
    && cp duo-sim-*/target/duo-sim-*.jar /repo/libs/
```

（顺带收益：`cp duo-sim-*.jar` 通配此前会把 8 模块的 `-sources/-javadoc` jar 一并吸进镜像 `libs/`，跳过后自然消失。）

- [ ] **Step 4: CHANGELOG 补行 + Commit**

`[未发布]` 下补：

```markdown
- **构建瘦身（2026-10-31）**：CI 门禁与镜像构建跳过 sources/javadoc jar——
  门禁工时与镜像 `libs/` 同步减负（审计 N5）。
```

```bash
git add .github/workflows/ci.yml Dockerfile CHANGELOG.md
git commit -m "build: CI 门禁与镜像构建跳过 sources/javadoc——工时与镜像体积双收（N5）"
```

- [ ] **Step 5: 推送验证**

Run: `git push` → regression job 绿（gate 步时长可见缩短）+ image.yml 绿（镜像构建通过、冒烟 26/26）。
Expected: 双绿；image.yml 红则按日志修（门禁生效即价值）。

---

## 红线自查记录

- **占位符扫描**：`<Step 1 实取 SHA>` 为执行时现场取值的显式指令（附取值命令），非 TBD；其余 action SHA 全部照抄 ci.yml 已 pin 值，无臆造。✓
- **接口一致性**：workflow 名 `image` 在 Task 3→6、job 名 `supply-chain` 在 Task 5→6、`scripts/duo-smoke-docker.sh` 在 Task 2→3/6 拼写一致；image.yml 与 ci.yml 触发面列表同族；`-Dmaven.source.skip=true -Dmaven.javadoc.skip=true` 在 Task 7 两处拼写一致。✓
- **paths-ignore 对账**：与 .dockerignore 排除面一致（docs/、*.md、architecture-ui、.github、LICENSE）；`scripts/` **不在** ignore 内——冒烟脚本变更必须重跑门禁。✓
- **Review Focus 落实**：#2/#3/#5 由 Task 3 步骤直接踩到；#1 为既有 Dockerfile 行为（失败路径由 `mvn package` 退出码保证）；#4 由 Task 4 Step 3 踩到；#6 由 Task 3 Step 3 附注踩到；#7 由 Task 7 Step 1 踩到。✓
- **依赖顺序**：Task 1 →（推送触发能力）→ Task 3/5 的 CI 验证；Task 2 → Task 3 的脚本路径；Task 4 合 master 后 Step 3 收尾；Task 7 的验证依赖 Task 1（触发面）+ Task 3（image.yml 拦 Dockerfile 回归）。执行按序号进行。✓

## 执行方式

任务间接口依赖少、全为配置与文档、每任务验证成本低——推荐**本会话逐任务**执行（自己按序跑完，最后整分支评审一次）。收尾两件均在合入 master 后：Task 4 Step 3（dependabot 首轮 PR 实测）与「设置面手动项」两开关。

---

## 设置面手动项（不入 workflow，零代码；合入 master 后执行）

- [ ] **N2：开启 Dependabot alerts 与 security updates**（Settings → Code security and analysis）——当前两者仓库级 disabled（API 实证 403 + `disabled`）；不开则 Task 4 装了 dependabot.yml 也只出版本 PR，CVE 告警与自动安全 PR 恒为零。
- [ ] **N3 决策记录（按 a 执行）**：维持 master 无分支保护（API 实证 404）——Task 1 后全分支推送有门禁，master 门禁事后跑属已知取舍；协作面扩大再议 required checks + PR 强制。

## 台账待议项（二轮复审 P3，不排期）

- **N6** architecture-pages 无 `pull_request` 校验——架构图改动合入 master 才首验（PR 期 ci.yml 也不构建 architecture-ui）；修法：PR 触发 build-only + deploy 加 ref 门。
- **N7** `.dockerignore` 漏 `.superpowers/`（88K 上下文膨胀，非安全）。
- **N8** 无 maven-enforcer——Java 21/Maven 3.9 无构建期强制，靠文档+CI 环境自觉。
- **N9** Dockerfile 无 HEALTHCHECK、无 OCI labels——P1-2 解封时顺手。
- **N10** `copy-dependencies` 缺省 scope=test——镜像 `libs/` 含 junit/testcontainers/mock 等测试件；`-DincludeScope=runtime` 收紧需核对 provided 例外。

微观察（不动作）：nightly 进行中 master push 会取消在途 nightly（concurrency 同组；02:00 CST 实际无冲突，run 历史四连绿）；surefire artifact 只传 `.txt`（skip-summary 在 job 内自读 XML，不受影响）；冒烟口径「26 项检查」= 15 小节内 23 处 `expect` + 3 处内联判定（run 41133647233 佐证 nightly 三档全跑）。

---

## 暂缓项（2026-10-31 用户拍板）

**决策：暂不引入 tag→版本发布链（P1-2 整体顺延）。** 解封时机由用户后续确定；解封时按下述保留设计直接落地，并同步补 DEVELOPMENT「发布」小节与 CHANGELOG。

### 保留设计一：release.yml（tag `v*` → ghcr 镜像 + GitHub Release）

```yaml
# 发布流水线（审计 P1-2）：tag v* 触发，一条龙 = 镜像推 ghcr + GitHub Release。
# 供应链决策：全程 **shell 直调 docker/gh，零新增 action**——延续 pin-SHA 纪律（L-6）
# 的最省路径：不为 login/build-push/metadata 引入三个待 pin 的新依赖面。
# 镜像名 = GITHUB_REPOSITORY 全小写（bash `${VAR,,}` 展开；ghcr 强制小写）。
# 镜像 tag = 引用 tag 去 v 前缀（v0.9.0 → 0.9.0）+ latest；语义化版本见 CHANGELOG 版本策略。
name: release

on:
  push:
    tags: ['v*']

permissions:
  contents: write   # gh release create
  packages: write   # ghcr 推送

# 发布不可被后续事件中途取消
concurrency:
  group: release-${{ github.ref }}
  cancel-in-progress: false

jobs:
  image:
    name: build & push image + release
    runs-on: ubuntu-24.04
    steps:
      - uses: actions/checkout@3d3c42e5aac5ba805825da76410c181273ba90b1 # v7.0.1
      - name: login ghcr
        run: echo "${{ secrets.GITHUB_TOKEN }}" | docker login ghcr.io -u "${{ github.actor }}" --password-stdin
      - name: build & push
        run: |
          IMAGE="ghcr.io/${GITHUB_REPOSITORY,,}"
          VERSION="${GITHUB_REF_NAME#v}"
          echo "IMAGE=$IMAGE VERSION=$VERSION"
          docker build -t "$IMAGE:$VERSION" -t "$IMAGE:latest" .
          docker push "$IMAGE:$VERSION"
          docker push "$IMAGE:latest"
      - name: create GitHub Release
        run: gh release create "$GITHUB_REF_NAME" --verify-tag --generate-notes --title "$GITHUB_REF_NAME"
        env:
          GH_TOKEN: ${{ secrets.GITHUB_TOKEN }}
```

### 保留设计二：发布演练清单（解封后执行一次）

1. 版本按 CHANGELOG 版本策略定版（首个 tag 版本号届时拍板）；
2. `git tag vX.Y.Z && git push origin vX.Y.Z` → Actions release run 全绿；
3. `docker pull ghcr.io/cwentor/duo:<去v版本>` 成功、GitHub Release 页 notes 存在；
4. 拉下的镜像本地起容器复跑 `scripts/duo-smoke-docker.sh` → 26/26 PASS；
5. 实测日期与 run 号回填 DEVELOPMENT 发布小节。

### 暂缓相关约束（解封时一并恢复生效）

- ghcr 镜像路径必须**全小写**（`${GITHUB_REPOSITORY,,}`）；
- Review Focus 补一条：`GITHUB_REPOSITORY` 含大写 → push 前打印镜像名肉眼可查。
