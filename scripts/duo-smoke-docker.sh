#!/usr/bin/env bash
# 容器化调度链路冒烟（评审 2026-10-30）——对运行中的 duo-sim 容器执行 G-W2 等价 API 序列。
# 用法：BASE=http://127.0.0.1:8080 DUO_TOKEN=<T> bash scripts/duo-smoke-docker.sh
set -euo pipefail

BASE="${BASE:-http://127.0.0.1:8080}"
TOKEN="${DUO_TOKEN:?need DUO_TOKEN}"
AUTH=(-H "Authorization: Bearer $TOKEN")
JSON=(-H "Content-Type: application/json")
FORK_ID=smoke-api-review

say() { printf '\n==== %s\n' "$*"; }
expect() { # expect <desc> <want-substr> <actual>
  if [[ "$3" == *"$2"* ]]; then echo "PASS: $1"; else
    echo "FAIL: $1"; echo "  want substring: $2"; echo "  got: ${3:0:400}"; exit 1; fi
}

say "0. /health 免认证活性探针"
R=$(curl -s "$BASE/health"); expect "health ok" '"ok":true' "$R"

say "0b. 未带令牌访问受保护端点必须 401"
CODE=$(curl -s -o /tmp/smoke-401.json -w '%{http_code}' "$BASE/api/scenarios")
expect "401 without token" "401" "$CODE"

say "1. 场景库清单（内置模板可见）"
R=$(curl -s "${AUTH[@]}" "$BASE/api/scenarios")
expect "template worker-crash-failover listed" 'worker-crash-failover' "$R"
expect "template worker-freeze-demo listed" 'worker-freeze-demo' "$R"

say "2. fork 模板 → $FORK_ID"
R=$(curl -s "${AUTH[@]}" "${JSON[@]}" -X POST "$BASE/api/scenarios/worker-crash-failover/fork" \
  -d "{\"id\":\"$FORK_ID\"}")
expect "fork created" "\"id\":\"$FORK_ID\"" "$R"

say "3. 读取 fork 正文并收紧时长（15s→5s，timeline 10s/27s→3s/6s）"
YAML=$(curl -s "${AUTH[@]}" "$BASE/api/scenarios/$FORK_ID" | python -c "import sys,json;print(json.load(sys.stdin)['yaml'])" 2>/dev/null \
  || curl -s "${AUTH[@]}" "$BASE/api/scenarios/$FORK_ID" | sed 's/^{"id":"[^"]*","template":[a-z]*,"yaml":"//; s/"}$//')
YAML=$(printf '%s' "$YAML" | sed 's/duration: 15s/duration: 5s/; s/at: 10s/at: 3s/; s/at: 27s/at: 6s/')

say "3b. 上传前先走 /api/scenarios/validate 全量校验"
R=$(curl -s "${AUTH[@]}" "${JSON[@]}" -X POST "$BASE/api/scenarios/validate" --data-binary "$YAML")
expect "validate returns no errors" '[]' "$R"

say "3c. PUT 保存 fork"
CODE=$(curl -s -o /tmp/smoke-put.json -w '%{http_code}' "${AUTH[@]}" "${JSON[@]}" -X PUT \
  "$BASE/api/scenarios/$FORK_ID" --data-binary "$YAML")
expect "PUT 200" "200" "$CODE"

say "4. POST /scenario 启动调度链路（REST → 校验 → 引擎 → 组件/SUT）"
CODE=$(curl -s -o /tmp/smoke-start.json -w '%{http_code}' "${AUTH[@]}" "${JSON[@]}" -X POST \
  "$BASE/scenario" --data-binary "$YAML")
expect "start 200" "200" "$CODE"

say "5. 轮询状态至 RUNNING"
for i in $(seq 1 30); do
  R=$(curl -s "${AUTH[@]}" "$BASE/scenario/status")
  [[ "$R" == *RUNNING* ]] && break
  sleep 1
done
expect "scenario RUNNING" 'RUNNING' "$R"

say "6. 拓扑可见（3 节点：zk/master/workers）"
R=$(curl -s "${AUTH[@]}" "$BASE/topology")
expect "topology zk" '"zk"' "$R"
expect "topology master" '"master"' "$R"
expect "topology workers" '"workers"' "$R"

say "7. 直接 API 注入 crash workers[1]（实例级寻址）"
R=$(curl -s "${AUTH[@]}" "${JSON[@]}" -X POST "$BASE/inject" \
  -d '{"type":"crash","target":{"componentId":{"value":"workers"},"instanceIndex":1},"params":{}}')
expect "inject accepted" '"success"' "$R"

say "8. 事件流出现注入事实"
for i in $(seq 1 30); do
  R=$(curl -s "${AUTH[@]}" "$BASE/events?since=0")
  [[ "$R" == *fault-injected* ]] && break
  sleep 1
done
expect "sim.fault-injected in events" 'fault-injected' "$R"
expect "worker crash fact in events" 'worker-instance-crashed' "$R"

say "9. 注入清除端点（/api/inject/clear）——只走正路：freeze 整组注入→清除（组件级故障、事件可见反应）。"
say "   负向语义（实测记录于评审报告）：实例级组件故障被显式拒绝（带指引语）；clear 幂等；"
say "   生命周期动作不可清除；注入失败流先 fault-injected 后 fault-inject-failed（诊断链不配对，见报告 F1）"
R=$(curl -s "${AUTH[@]}" "${JSON[@]}" -X POST "$BASE/inject" \
  -d '{"type":"freeze","target":{"componentId":{"value":"workers"}},"params":{}}')
expect "group freeze accepted" '"success":true' "$R"
R=$(curl -s "${AUTH[@]}" "${JSON[@]}" -X POST "$BASE/api/inject/clear" \
  -d '{"type":"freeze","target":{"componentId":{"value":"workers"}},"params":{}}')
expect "clear freeze accepted" '"success":true' "$R"

say "10. 等待场景收口（≤60s），断言可读"
PASSED=""
for i in $(seq 1 60); do
  R=$(curl -s "${AUTH[@]}" "$BASE/scenario/status")
  [[ "$R" == *FINISHED* || "$R" == *FAILED* ]] && break
  sleep 1
done
if [[ "$R" == *FINISHED* || "$R" == *FAILED* ]]; then echo "PASS: terminal state reached"; else
  echo "FAIL: no terminal state within 60s"; echo "  got: ${R:0:400}"; exit 1; fi
R=$(curl -s "${AUTH[@]}" "$BASE/assertions")
expect "assertions present" '"assertions"' "$R"
printf '  assertions: %.400s\n' "$R"

say "11. 诊断链完整（注入链/SUT 事实/gaps）"
R=$(curl -s "${AUTH[@]}" "$BASE/diagnose")
expect "diagnose chains" '"chains"' "$R"
if [[ "$R" == *'"gaps":[]'* ]]; then echo "PASS: gaps empty"; else
  echo "FAIL: gaps non-empty: $(printf '%s' "$R" | grep -o '"gaps":\[[^]]*\]')"; exit 1; fi

say "12. 指标端点（Prometheus 文本）"
R=$(curl -s "${AUTH[@]}" "$BASE/metrics")
expect "metrics scrape" 'duo_' "$R"

say "13. 停止 + 删除 fork 收尾"
curl -s "${AUTH[@]}" -X DELETE "$BASE/scenario" >/dev/null
CODE=$(curl -s -o /dev/null -w '%{http_code}' "${AUTH[@]}" -X DELETE "$BASE/api/scenarios/$FORK_ID")
expect "delete fork 200" "200" "$CODE"

say "14. SPA 静态托管（/ 与 /console/**）"
R=$(curl -s "$BASE/"); expect "index.html served" '<!DOCTYPE html>' "$R"
CODE=$(curl -s -o /dev/null -w '%{http_code}' "$BASE/console/")
expect "console/ 200" "200" "$CODE"
CODE=$(curl -s -o /dev/null -w '%{http_code}' --path-as-is "$BASE/console/../scenario")
if [[ "$CODE" != "200" ]]; then echo "PASS: traversal rejected ($CODE)"; else
  echo "FAIL: traversal returned 200"; exit 1; fi

say "SMOKE ALL DONE"
