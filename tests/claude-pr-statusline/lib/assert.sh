# Assertion helpers. Each one prints a diagnostic to stderr and returns
# non-zero on failure so the case's `set -e` aborts.

_fail() {
  TEST_FAILED=1
  echo "  FAIL: $1" >&2
  return 1
}

assert_equal() {
  local a="$1" b="$2" msg="${3:-values differ}"
  if [ "$a" != "$b" ]; then
    _fail "$msg"
    echo "    expected: $b" >&2
    echo "    actual:   $a" >&2
    return 1
  fi
}

assert_contains() {
  local hay="$1" needle="$2" msg="${3:-substring not found}"
  case "$hay" in
    *"$needle"*) return 0 ;;
    *) _fail "$msg"
       echo "    haystack: $hay" >&2
       echo "    needle:   $needle" >&2
       return 1 ;;
  esac
}

assert_not_contains() {
  local hay="$1" needle="$2" msg="${3:-substring unexpectedly found}"
  case "$hay" in
    *"$needle"*) _fail "$msg"
       echo "    haystack: $hay" >&2
       echo "    needle:   $needle" >&2
       return 1 ;;
    *) return 0 ;;
  esac
}

assert_file_exists() {
  [ -f "$1" ] || _fail "file missing: $1"
}

assert_file_missing() {
  [ ! -e "$1" ] || _fail "file unexpectedly present: $1"
}

assert_file_contents() {
  local file="$1" expected="$2" msg="${3:-file contents differ}"
  local actual
  actual=$(cat "$file" 2>/dev/null) || { _fail "cannot read $file"; return 1; }
  if [ "$actual" != "$expected" ]; then
    _fail "$msg"
    echo "    file: $file" >&2
    echo "    expected:" >&2
    printf '      %s\n' "$expected" >&2
    echo "    actual:" >&2
    printf '      %s\n' "$actual" >&2
    return 1
  fi
}

# diff_snapshot <actual_file> <expected_path_relative_to_tests>
# UPDATE_SNAPSHOTS=1 writes the actual to the expected location and passes.
diff_snapshot() {
  local actual="$1" rel="$2"
  local expected="$TEST_ROOT/$rel"
  if [ "${UPDATE_SNAPSHOTS:-0}" = "1" ]; then
    mkdir -p "$(dirname "$expected")"
    cp "$actual" "$expected"
    echo "  [snapshot updated] $rel" >&2
    return 0
  fi
  if [ ! -f "$expected" ]; then
    _fail "snapshot missing: $rel  (run with UPDATE_SNAPSHOTS=1 to create)"
    return 1
  fi
  if ! diff -u "$expected" "$actual" >&2; then
    _fail "snapshot mismatch: $rel"
    return 1
  fi
}
