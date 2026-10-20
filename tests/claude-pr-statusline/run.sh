#!/bin/bash
# Orchestrator for PR-statusline tests.
#
# Each cases/*.sh runs in a clean subshell with a fresh sandbox. Stdout
# is captured per test and only printed on failure. Exit code is non-zero
# iff any test fails. Honors UPDATE_SNAPSHOTS=1 and KEEP_SANDBOX=1.

set -u

TEST_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
export TEST_ROOT
SCRIPTS_ROOT="$(cd "$TEST_ROOT/../../claude/scripts" && pwd)"
export SCRIPTS_ROOT

cases=("$TEST_ROOT"/cases/*.sh)
if [ "${#cases[@]}" -eq 0 ] || [ ! -f "${cases[0]}" ]; then
  echo "no test cases found in $TEST_ROOT/cases" >&2
  exit 1
fi

# Allow filtering with positional args: ./run.sh 03 statusline
filter() {
  local name="$1"; shift
  if [ "$#" -eq 0 ]; then return 0; fi
  for arg in "$@"; do
    case "$name" in
      *"$arg"*) return 0 ;;
    esac
  done
  return 1
}

pass=0
fail=0
failed_names=()

for case_file in "${cases[@]}"; do
  name=$(basename "$case_file" .sh)
  if ! filter "$name" "$@"; then
    continue
  fi
  out=$(mktemp)
  if bash "$case_file" >"$out" 2>&1; then
    echo "  PASS  $name"
    pass=$((pass+1))
  else
    echo "  FAIL  $name"
    sed 's/^/    /' "$out"
    fail=$((fail+1))
    failed_names+=("$name")
  fi
  rm -f "$out"
done

echo
total=$((pass+fail))
echo "Results: $pass/$total passed"
if [ "$fail" -gt 0 ]; then
  printf 'Failed:\n'
  printf '  %s\n' "${failed_names[@]}"
  exit 1
fi
exit 0
