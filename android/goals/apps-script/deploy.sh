#!/usr/bin/env bash
# Tests the Apps Script, pushes it with clasp and moves the web app deployment to the new version,
# so its /exec URL stays the same. Needs node, clasp (logged in with `clasp login`) and a
# .clasp.json here naming the script (see README.md, Deploy with clasp).
set -euo pipefail
cd "$(dirname "$0")"

[[ -f .clasp.json ]] || { echo "deploy.sh: no .clasp.json; see README.md (Deploy with clasp)" >&2; exit 1; }
node --test tests/*.test.js

# The web app's deployment: the only one besides @HEAD, unless GATE_DEPLOYMENT_ID names it.
id="${GATE_DEPLOYMENT_ID:-}"
if [[ -z "$id" ]]; then
    mapfile -t ids < <(clasp list-deployments --json | node -e '
        const d = JSON.parse(require("fs").readFileSync(0, "utf8"));
        for (const x of d) if (x.versionNumber ?? x.deploymentConfig?.versionNumber) console.log(x.deploymentId);')
    if (( ${#ids[@]} != 1 )); then
        echo "deploy.sh: expected one versioned deployment, found ${#ids[@]}; set GATE_DEPLOYMENT_ID" >&2
        exit 1
    fi
    id="${ids[0]}"
fi

desc="$(git log -1 --format='%h %s' 2>/dev/null || date -u +%F)"
clasp push --force
clasp update-deployment "$id" --description "$desc"
echo "Deployed: $desc"
