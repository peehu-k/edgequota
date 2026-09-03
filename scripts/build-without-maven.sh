#!/usr/bin/env bash
# Fallback build for environments without Maven: plain javac against the
# standard library only (the main sources have zero external dependencies).
# Test sources are skipped here since they depend on JUnit5; use `mvn test`
# for the full test suite.
set -euo pipefail
cd "$(dirname "$0")/.."
rm -rf out
mkdir -p out
find src/main/java -name '*.java' > /tmp/edgequota-sources.txt
javac -d out -encoding UTF-8 @/tmp/edgequota-sources.txt
echo "Compiled to ./out"
echo "Run a node with, e.g.:"
echo "  NODE_ID=node-1 GOSSIP_PORT=7946 HTTP_PORT=8080 java -cp out com.edgequota.node.Main"
echo "Run the simulation harness with:"
echo "  java -cp out com.edgequota.sim.SimulationRunner"
