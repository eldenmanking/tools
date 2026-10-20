#!/bin/bash
# `shorten()` must not match arbitrary paths when HOME is empty.

set -e
source "$TEST_ROOT/lib/setup.sh"
test_init

# Extract shorten() from the live statusline script.
shorten_src=$(awk '/^shorten\(\) \{/,/^\}/' "$SL")
[ -n "$shorten_src" ] || _fail "couldn't extract shorten() source"
eval "$shorten_src"

HOME_BACKUP=$HOME

HOME=/home/user
assert_equal "$(shorten /home/user/proj)" "~/proj" "shorten with matching prefix"

assert_equal "$(shorten /var/lib/foo)" "/var/lib/foo" "shorten with no prefix match"

HOME=""
assert_equal "$(shorten /etc/passwd)" "/etc/passwd" "shorten with empty HOME passes through"

HOME=$HOME_BACKUP
echo "shorten() empty-HOME guard ok"
