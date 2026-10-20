#!/bin/bash
# Snapshot-based check on the *exact* rendered output for a known stack.

set -e
source "$TEST_ROOT/lib/setup.sh"
test_init snap

seed_state_row "$REPO" feat-a https://ex.com/pr/1 develop 1
seed_state_row "$REPO" feat-b https://ex.com/pr/2 feat-a  2

(cd "$REPO" && git checkout -q -b feat-b)

# Normalize the absolute REPO path so the snapshot is portable across runs.
raw=$(statusline_input "$REPO" "$TX" | bash "$SL")
echo "$(printf '%s' "$raw" | sed "s|$REPO|<REPO>|g" | strip_ansi)" > "$SBX/render.txt"

diff_snapshot "$SBX/render.txt" "fixtures/statusline/two-pr-stack.expected"

echo "snapshot ok"
