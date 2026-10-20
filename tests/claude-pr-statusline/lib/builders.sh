# Builders for gh mock fixtures + state-file rows.
#
# gh_fixture_reset                   wipe fixture to {}
# gh_fixture_pr  <ref> <state> <base> <num> [url]
#                                    add a `pr view <ref>` rule
# gh_fixture_pr_url <url> <state> <base> <head> <num>
#                                    add a `pr view <url>` rule (used by
#                                    refresh-core which queries by URL)
# gh_fixture_state <url> <state>     add a `pr view <url> --json state` rule
# gh_fixture_list_head <branch> <pr_url> <head> <base> <num>
#                                    add a single-element `pr list --head` rule
# gh_fixture_list_base <branch> <items...>
#                                    add a multi-element `pr list --base` rule
#                                    where each item is "url|head|base|num"
# gh_fixture_list_empty <head|base> <branch>
#                                    add an empty-array `pr list` rule
# gh_fixture_raw <argv> <stdout> [exit_code]
#                                    low-level: add an arbitrary rule
#
# seed_state_row <repo> <branch> <url> <base> <num>
#                                    append a TSV row to "$STATE_DIR/$SK"
# seed_state_row_into <file> <repo> <branch> <url> <base> <num>
#                                    append a row to an arbitrary file
#
# render_status [cwd] [pct]          run the statusline pipeline, strip ANSI

_fixture_set() {
  # $1 = jq filter, remaining args = jq --arg / --argjson pairs.
  local filter="$1"; shift
  local existing="{}"
  [ -s "$GH_MOCK_FIXTURE" ] && existing=$(cat "$GH_MOCK_FIXTURE")
  jq -nc --argjson e "$existing" "$@" "\$e + ($filter)" > "$GH_MOCK_FIXTURE"
}

gh_fixture_reset() {
  echo '{}' > "$GH_MOCK_FIXTURE"
}

gh_fixture_pr() {
  local ref="$1" state="$2" base="$3" num="$4" url="${5:-https://example.com/pr/$4}"
  local key="pr view $ref --json url,baseRefName,headRefName,number,state"
  _fixture_set \
    '{($k): ({url:$url, baseRefName:$base, headRefName:$head, number:$num, state:$state} | tojson)}' \
    --arg k "$key" --arg url "$url" --arg base "$base" --arg head "$ref" \
    --argjson num "$num" --arg state "$state"
}

gh_fixture_pr_url() {
  local url="$1" state="$2" base="$3" head="$4" num="$5"
  local key="pr view $url --json url,baseRefName,headRefName,number,state"
  _fixture_set \
    '{($k): ({url:$url, baseRefName:$base, headRefName:$head, number:$num, state:$state} | tojson)}' \
    --arg k "$key" --arg url "$url" --arg base "$base" --arg head "$head" \
    --argjson num "$num" --arg state "$state"
}

gh_fixture_state() {
  local url="$1" state="$2"
  local key="pr view $url --json state"
  _fixture_set '{($k): ({state:$state} | tojson)}' --arg k "$key" --arg state "$state"
}

gh_fixture_list_head() {
  local branch="$1" url="$2" head="$3" base="$4" num="$5"
  local key="pr list --head $branch --state open --json url,baseRefName,headRefName,number"
  _fixture_set \
    '{($k): ([{url:$url, baseRefName:$base, headRefName:$head, number:$num}] | tojson)}' \
    --arg k "$key" --arg url "$url" --arg base "$base" --arg head "$head" --argjson num "$num"
}

gh_fixture_list_base() {
  local branch="$1"; shift
  local key="pr list --base $branch --state open --json url,baseRefName,headRefName,number"
  local arr="["
  local first=1
  for item in "$@"; do
    IFS='|' read -r url head base num <<< "$item"
    [ "$first" = 1 ] || arr+=", "
    first=0
    arr+="{\"url\":\"$url\",\"headRefName\":\"$head\",\"baseRefName\":\"$base\",\"number\":$num}"
  done
  arr+="]"
  _fixture_set '{($k): ($v | tojson)}' --arg k "$key" --argjson v "$arr"
}

gh_fixture_list_empty() {
  # gh_fixture_list_empty head|base <branch>
  local kind="$1" branch="$2"
  local key="pr list --$kind $branch --state open --json url,baseRefName,headRefName,number"
  _fixture_set '{($k): "[]"}' --arg k "$key"
}

gh_fixture_raw() {
  # gh_fixture_raw <argv-string> <stdout> [exit_code]
  local key="$1" stdout="$2" exit_code="${3:-0}"
  _fixture_set '{($k): ({stdout:$stdout, exit_code:$ec} )}' \
    --arg k "$key" --arg stdout "$stdout" --argjson ec "$exit_code"
}

seed_state_row() {
  seed_state_row_into "$STATE_DIR/$SK" "$@"
}

seed_state_row_into() {
  local file="$1" repo="$2" branch="$3" url="$4" base="$5" num="$6"
  mkdir -p "$(dirname "$file")"
  printf '%s\t%s\t%s\t%s\t%s\n' "$repo" "$branch" "$url" "$base" "$num" >> "$file"
}

render_status() {
  local cwd="${1:-$REPO}" pct="${2:-42}"
  statusline_input "$cwd" "$TX" "$pct" | bash "$SL" | strip_ansi
}
