#!/usr/bin/env bash
# Fault-injection harness for the docker-compose EdgeQuota cluster.
#
# Exercises the two failure modes the resume bullets claim graceful
# degradation for:
#   1. node kill        (docker stop)      -> SWIM failure detector should
#                                              mark it DEAD within ~a few
#                                              GossipNode.SUSPICION_TIMEOUT
#                                              windows, and the remaining
#                                              nodes should keep admitting/
#                                              rejecting traffic correctly
#                                              off the surviving cluster's
#                                              gossiped estimate.
#   2. network partition (docker network disconnect) -> the partitioned
#                                              node should keep serving its
#                                              own local budget (fail
#                                              gracefully, not fail-open to
#                                              unlimited and not fail-closed
#                                              to zero) until it rejoins.
#
# Usage: ./scripts/fault-injection.sh [kill|partition|both]
set -euo pipefail
MODE="${1:-both}"
COMPOSE="docker compose"
TENANT="acme"

hit() {
  local port=$1
  curl -s -o /dev/null -w "%{http_code}" -H "X-Tenant-Id: ${TENANT}" \
    -d '{"probe":"fault-injection"}' "http://localhost:${port}/v1/probe" || echo "000"
}

echo "== baseline: hitting all 5 nodes =="
for p in 8081 8082 8083 8084 8085; do
  echo "node on :${p} -> HTTP $(hit "$p")"
done

if [[ "$MODE" == "kill" || "$MODE" == "both" ]]; then
  echo
  echo "== killing node-1 (docker stop, no graceful leave) =="
  $COMPOSE stop -t 1 node-1
  sleep 3
  echo "-- remaining nodes should still admit/reject normally --"
  for p in 8082 8083 8084 8085; do
    echo "node on :${p} -> HTTP $(hit "$p")"
  done
  echo "restarting node-1..."
  $COMPOSE start node-1
fi

if [[ "$MODE" == "partition" || "$MODE" == "both" ]]; then
  echo
  echo "== partitioning node-2 from the cluster network =="
  NET=$(docker compose ps -q node-2 | xargs docker inspect -f '{{range $k,$v := .NetworkSettings.Networks}}{{$k}}{{end}}')
  docker network disconnect "$NET" "$(docker compose ps -q node-2)" || true
  sleep 2
  echo "-- node-2 should still answer using its own local budget (degraded but alive) --"
  echo "node on :8082 -> HTTP $(hit 8082)"
  echo "-- other nodes should not have node-2's traffic double counted once it heals --"
  docker network connect "$NET" "$(docker compose ps -q node-2)" || true
  sleep 2
  for p in 8081 8082 8083 8084 8085; do
    echo "node on :${p} -> HTTP $(hit "$p")"
  done
fi

echo
echo "Done. Inspect 'docker compose logs -f' for gossip/failure-detector output."
