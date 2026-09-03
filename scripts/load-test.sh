#!/usr/bin/env bash
# Generates concurrent multi-tenant traffic spread across all 5 nodes so you
# can watch the cluster-wide (not per-node) quota get enforced: hammering a
# single node with one tenant's traffic should get that tenant throttled
# cluster-wide once the gossiped global estimate crosses its quota, not just
# throttled on the one node taking the traffic.
#
# Usage: ./scripts/load-test.sh [requests_per_tenant] [concurrency]
set -euo pipefail
REQS="${1:-500}"
CONCURRENCY="${2:-20}"
PORTS=(8081 8082 8083 8084 8085)
TENANTS=(acme globex initech)

request() {
  local tenant=$1
  local port=${PORTS[$((RANDOM % ${#PORTS[@]}))]}
  curl -s -H "X-Tenant-Id: ${tenant}" -d '{"query":"{ user { id name orders { id items { id } } } }"}' \
    "http://localhost:${port}/graphql" | python3 -c "import sys,json; d=json.load(sys.stdin); print(d.get('status'))" 2>/dev/null || echo "error"
}
export -f request
export PORTS TENANTS

for tenant in "${TENANTS[@]}"; do
  echo "== load-testing tenant '${tenant}' with ${REQS} requests (concurrency=${CONCURRENCY}) =="
  seq 1 "$REQS" | xargs -P "$CONCURRENCY" -I{} bash -c "request '$tenant'" | sort | uniq -c
  echo
done
