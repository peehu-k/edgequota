#!/usr/bin/env bash
# Builds (if needed) and runs the in-process simulation/benchmark harness
# (com.edgequota.sim.SimulationRunner) that produces the numbers quoted in
# docs/BENCHMARKS.md, without needing Docker.
set -euo pipefail
cd "$(dirname "$0")/.."

if [ ! -d target/classes ]; then
  echo "Compiling with Maven..."
  mvn -q -B -DskipTests package
fi

java -cp target/classes com.edgequota.sim.SimulationRunner
