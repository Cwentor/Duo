#!/usr/bin/env bash
# M3 验收 CLI 演练（计划 T35）——「运行中手动注入故障并观察自愈」（设计文档 §14 M3 行）。
#
# 两个演示：
#   1) 同进程模式：单命令完成「启动 + 到点热注入 + 等待 + 报结果」，退出码即场景结果；
#   2) 跨进程模式：独立进程承载场景与 REST 服务，CLI 经 HTTP 对运行中的场景热注入。
#
# 用法：bash scripts/duo-inject-demo.sh [scenario.yaml]
# 退出码：0 = 两个演示均通过（注入成功、断言通过、无任务丢失）。
#
# 前置：先构建一次（./mvnw.sh -o install -DskipTests）。
set -e

cd "$(dirname "$0")/.."

SCENARIO="${1:-duo-sim-examples/src/main/resources/scenarios/m3-inject-demo.yaml}"
PORT="${DUO_DEMO_PORT:-0}"   # 0 = 由内核分配空闲端口（避免与遗留进程撞端口）

# 与 mvnw.sh 同源的 JDK 21（Git Bash 下 JAVA_HOME 须为 POSIX 路径）
export JAVA_HOME="${JAVA_HOME:-/c/Users/cwt15/devtools/jdk-21.0.12.1+1}"
export PATH="$JAVA_HOME/bin:$PATH"

if ! command -v java >/dev/null 2>&1; then
    echo "错误：找不到 java（请设置 JAVA_HOME）" >&2
    exit 1
fi

# JVM 默认用系统本地编码（中文 Windows 为 GBK）写 stdout；脚本与终端都是 UTF-8，
# 不强制会乱码（尤其重定向到文件时）。故显式钉死 UTF-8。
JAVA_IO_OPTS="-Dfile.encoding=UTF-8 -Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8"

echo "== 构建类路径 =="
# 用 maven 解析第三方依赖（避免手写 jar 路径与版本漂移——原脚本硬编码 zookeeper 3.9.3 而实际为 3.9.2）
./mvnw.sh -o -q -pl duo-sim-examples dependency:build-classpath \
    -Dmdep.outputFile=target/demo-classpath.txt -DincludeScope=runtime >/dev/null
DEPS="$(cat duo-sim-examples/target/demo-classpath.txt)"

# 模块 target/classes 优先于 .m2 里的 jar（保证跑的是当前工作区代码）；
# Windows JVM 的 -cp 分隔符是 ';'（dependency 插件输出亦为 Windows 风格路径）
CP=""
for m in duo-sim-control duo-sim-embedded duo-sim-components duo-sim-scenario duo-sim-kernel duo-sim-protocol duo-sim-examples; do
    CP="$CP$m/target/classes;"
done
CP="$CP$DEPS"

run_duo() { java $JAVA_IO_OPTS -cp "$CP" io.duo.sim.control.cli.DuoCli "$@"; }

echo
echo "======== 演示 1/2：同进程模式（单命令热注入）========"
echo "\$ duo run $SCENARIO --inject-after 3s \"crash workers[2]\" --wait"
run_duo run "$SCENARIO" --inject-after 3s "crash workers[2]" --wait
echo "-> 退出码 0：场景 SUCCESS 且 noTaskLost 通过"

echo
echo "======== 演示 2/2：跨进程模式（独立进程 + REST 热注入）========"
echo "\$ duo serve $SCENARIO --port $PORT &"
LOG="$(mktemp)"
# 直接后台起 java（不经函数包装）——这样 $! 就是 JVM 的 PID，trap 能真正杀掉它；
# 若包装成 run_duo &，$! 只是子 shell，java 会残留并占住端口。
java $JAVA_IO_OPTS -cp "$CP" io.duo.sim.control.cli.DuoCli serve "$SCENARIO" --port "$PORT" >"$LOG" 2>&1 &
SERVE_PID=$!
trap 'kill $SERVE_PID 2>/dev/null || true; wait $SERVE_PID 2>/dev/null || true; rm -f "$LOG"' EXIT

# 等待服务端就绪（打印 "listening on http://127.0.0.1:<port>"）
for _ in $(seq 1 60); do
    grep -q "listening on" "$LOG" && break
    sleep 0.5
done
if ! grep -q "listening on" "$LOG"; then
    echo "错误：serve 未就绪，输出：" >&2
    cat "$LOG" >&2
    exit 1
fi
URL="$(grep -o 'http://127.0.0.1:[0-9]*' "$LOG" | head -1)"
echo "服务端就绪：$URL"

echo
echo "\$ duo status --url $URL"
run_duo status --url "$URL"

echo
echo "\$ duo topology --url $URL"
run_duo topology --url "$URL"

echo
echo "\$ duo inject crash workers[2] --url $URL   # 运行中热注入"
run_duo inject crash "workers[2]" --url "$URL"

echo
echo "\$ duo events --since 0 --url $URL | grep -E 'fault-injected|task-retry' | head -5"
run_duo events --since 0 --url "$URL" | grep -E 'fault-injected|task-retry' | head -5 || true

echo
echo "\$ 轮询 duo status --url $URL 直到 SUT 退出（DAG 终态）"
ST=""
for _ in $(seq 1 120); do
    ST="$(run_duo status --url "$URL")"
    case "$ST" in
        *'"state":"FINISHED"'*|*'"state":"FAILED"'*) break ;;
    esac
    sleep 1
done
echo "$ST"
case "$ST" in
    *'"state":"FINISHED"'*) ;;
    *) echo "错误：场景未在窗口内到达 FINISHED" >&2; exit 1 ;;
esac

echo
echo "\$ duo assert --url $URL                     # 断言结果（退出码即结论）"
run_duo assert --url "$URL"

echo
echo "======== 演练完成：两个模式的注入均成功且断言通过 ========"
