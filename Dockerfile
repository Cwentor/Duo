# Duo 控制面镜像（评审 2026-10-30 交付）。三段构建：
#   ① node:22-alpine 出 console SPA（vite 直写 maven 段的 classpath 资源目录，
#      与 docs/DEVELOPMENT.md §1.4「npm build 先于 mvnw」同序，使 control jar 内嵌控制台）；
#   ② eclipse-temurin:21-jdk 跑 reactor（-DskipTests，测试归 CI regression job），
#      并把全部运行时 jar（8 模块 + 第三方）收敛到 /repo/libs；
#   ③ eclipse-temurin:21-jre 运行时（多段后镜像不含源码与构建链）。
#
# 用法：
#   docker build -t duo-sim .
#   docker run -d --name duo -p 8080:8080 -e DUO_TOKEN=<T> duo-sim
#   （或追加参数传令牌：docker run -d -p 8080:8080 duo-sim --token <T>）
#
# 安全姿态与 serve 一致（审计 C-1）：令牌必填，DUO_TOKEN 与 --token 皆缺时容器
# 拒绝启动并打印原因——镜像不提供免鉴权捷径；场景库落在 VOLUME /data。

# ---- ① 控制台 SPA ----
FROM node:22-alpine AS frontend
WORKDIR /src/console-ui
COPY console-ui/package.json console-ui/package-lock.json ./
RUN npm ci
COPY console-ui/ ./
# outDir 直指 maven 段的 duo-sim-control/target/classes/console（console-ui/vite.config.ts）
RUN npm run build

# ---- ② Maven reactor + 运行时依赖收敛 ----
FROM eclipse-temurin:21-jdk-jammy AS build
WORKDIR /repo
# 先拷 pom 与 wrapper：依赖不变时 Docker 层缓存直接命中
COPY pom.xml mvnw ./
COPY .mvn .mvn
COPY duo-sim-protocol/pom.xml duo-sim-protocol/
COPY duo-sim-kernel/pom.xml duo-sim-kernel/
COPY duo-sim-scenario/pom.xml duo-sim-scenario/
COPY duo-sim-components/pom.xml duo-sim-components/
COPY duo-sim-embedded/pom.xml duo-sim-embedded/
COPY duo-sim-junit/pom.xml duo-sim-junit/
COPY duo-sim-control/pom.xml duo-sim-control/
COPY duo-sim-examples/pom.xml duo-sim-examples/
RUN ./mvnw -B -q dependency:go-offline || true
COPY duo-sim-protocol duo-sim-protocol
COPY duo-sim-kernel duo-sim-kernel
COPY duo-sim-scenario duo-sim-scenario
COPY duo-sim-components duo-sim-components
COPY duo-sim-embedded duo-sim-embedded
COPY duo-sim-junit duo-sim-junit
COPY duo-sim-control duo-sim-control
COPY duo-sim-examples duo-sim-examples
# SPA 产物先进 classpath 目录再打包（maven-resources 不覆盖更新的目标文件，占位页不反冲）
COPY --from=frontend /src/duo-sim-control/target/classes/console duo-sim-control/target/classes/console
# copy-dependencies 全 reactor 汇入同一目录：examples 依赖其余全部模块，模块 jar 由此随依赖拷出。
# 但 copy-dependencies 不含 examples **自身**的 artifact——SCHEDULER/REAL 档的
# DemoSchedulerProvider 只在 examples jar 里（worker-crash-failover 模板依赖它），
# 故再把全部模块 jar 显式拷入（实测缺失时 validate 报 no implementation registered for SCHEDULER/REAL）。
RUN ./mvnw -B -DskipTests package dependency:copy-dependencies -DoutputDirectory=/repo/libs \
    && cp duo-sim-*/target/duo-sim-*.jar /repo/libs/

# ---- ③ 运行时 ----
FROM eclipse-temurin:21-jre-jammy
WORKDIR /app
COPY --from=build /repo/libs libs/
# 非 root 运行（评审 2026-10-30）：控制面是执行面（拉进程/写盘），root 会放大任何注入/校验缺陷的后果。
# /app/build 预建并授权：SUT 端点配置文件按仓库约定写 ./build（ComponentBootstrapper 相对路径），
# 非匿名 root 时缺它则 POST /scenario 直接 500（实测发现）。
RUN useradd --system --uid 10001 --shell /usr/sbin/nologin duo \
    && mkdir -p /data/library /app/build && chown -R duo:duo /data /app/build
USER duo
# 容器内监听 0.0.0.0（隔离边界＝网络命名空间，鉴权仍由 bearer token 承担；
# Host 回环守卫随绑定面联动放开，Origin 守卫与鉴权不变）；
# 场景库（fork/保存的用户场景）落 /data/library，VOLUME 支持挂卷持久化
VOLUME ["/data"]
EXPOSE 8080
ENTRYPOINT ["java", "-cp", "libs/*", "io.duo.sim.control.cli.DuoCli", "serve", \
            "--port", "8080", "--bind", "0.0.0.0", "--library-dir", "/data/library"]
