#!/usr/bin/env bash
# surefire skip 汇总（T8：skip 必须可见、可解释；M7 交付物 2）
#
# 用法：
#   bash .github/scripts/skip-summary.sh                  # 只打印汇总（skip 可见）
#   bash .github/scripts/skip-summary.sh --fail-on-skip   # skip>0 即失败（有 Docker 的容器档门禁）
#
# 数据源：各模块 target/surefire-reports/*.xml 的 <testsuite> 属性与 <skipped/> 子元素。
set -uo pipefail

FAIL_ON_SKIP=0
if [ "${1:-}" = "--fail-on-skip" ]; then
  FAIL_ON_SKIP=1
fi

shopt -s nullglob globstar
reports=( **/target/surefire-reports/*.xml )

if [ ${#reports[@]} -eq 0 ]; then
  echo "::error::no surefire reports found — tests did not run"
  exit 1
fi

total_tests=0
total_failures=0
total_errors=0
total_skipped=0
skipped_cases=""

attr() { # attr <xml> <name>
  printf '%s' "$1" | grep -o "$2=\"[0-9]*\"" | head -1 | grep -o '[0-9]*'
}

printf '%-62s %7s %6s %6s %6s\n' "surefire report" "tests" "fail" "error" "skip"
printf '%s\n' "------------------------------------------------------------------------------"

for f in "${reports[@]}"; do
  suite=$(grep -m1 -o '<testsuite [^>]*>' "$f" || true)
  if [ -z "$suite" ]; then
    continue
  fi
  t=$(attr "$suite" tests); f_=$(attr "$suite" failures)
  e=$(attr "$suite" errors); s=$(attr "$suite" skipped)
  t=${t:-0}; f_=${f_:-0}; e=${e:-0}; s=${s:-0}
  total_tests=$((total_tests + t))
  total_failures=$((total_failures + f_))
  total_errors=$((total_errors + e))
  total_skipped=$((total_skipped + s))
  name=$(basename "$f" .xml)
  printf '%-62s %7s %6s %6s %6s\n' "$name" "$t" "$f_" "$e" "$s"
  if [ "$s" != "0" ]; then
    # 列出被跳过的用例名（逐个 <testcase> 块内出现 <skipped/> 才算）
    while IFS= read -r line; do
      [ -n "$line" ] && skipped_cases="${skipped_cases}  - ${line}  [${name}]"$'\n'
    done < <(awk '
      /<testcase / { tc = $0; inskip = 0 }
      /<skipped/   { inskip = 1 }
      /<\/testcase>/ {
        if (inskip && match(tc, /name="[^"]*"/)) {
          print substr(tc, RSTART + 6, RLENGTH - 7)
        }
      }
    ' "$f" | sort -u)
  fi
done

printf '%s\n' "------------------------------------------------------------------------------"
printf '%-62s %7s %6s %6s %6s\n' "TOTAL" "$total_tests" "$total_failures" "$total_errors" "$total_skipped"

if [ -n "$skipped_cases" ]; then
  echo
  echo "skipped cases (${total_skipped}):"
  printf '%s' "$skipped_cases"
else
  echo
  echo "skipped cases: none"
fi

if [ "$total_skipped" != "0" ] && [ "$FAIL_ON_SKIP" = "1" ]; then
  echo "::error::${total_skipped} test(s) skipped while Docker is available — skips must be explainable (T8)"
  exit 1
fi

exit 0
