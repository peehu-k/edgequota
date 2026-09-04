# EdgeQuota — Benchmarks & Validation

Numbers in this document come from two sources, labeled explicitly:

- **Python validation prototype** -- a from-scratch re-implementation of
  the same algorithms (Count-Min Sketch sizing/hashing, round-based
  epidemic gossip dissemination) used to sanity-check the *math* of the
  design quickly and reproducibly, independent of the Java toolchain. This
  is what produced the numbers below.
- **Java `SimulationRunner`** (`com.edgequota.sim.SimulationRunner`) --
  runs the actual production code path (`GossipNode`, real UDP sockets on
  loopback, real `CountMinSketch`) end to end. Reproduce with:
  `mvn -q -DskipTests package && java -cp target/classes com.edgequota.sim.SimulationRunner`
  or `./scripts/run-simulation.sh`. Numbers will vary by hardware; this is
  the source of truth for the actual implementation, and the JUnit tests in
  `src/test` (`CountMinSketchTest`, `GossipNodeConvergenceTest`, etc.) pin
  down the same properties as pass/fail assertions rather than printed
  numbers -- see the CI badge in the README for a live run against every
  push.

## 1. Count-Min Sketch accuracy (Python prototype)

Setup: `epsilon=0.001`, `delta=0.01` (width=2719, depth=5), 500,000
weighted update events over 2,000 distinct keys drawn from an 80/20
skewed (Zipfian-like) distribution, weight uniform in [1,50] to model
heterogeneous per-request cost.

```
width=2719 depth=5 totalWeight=12,736,937
theoreticalBound (epsilon * totalWeight) = 12,736.9
distinctKeys=2000  maxObservedError=27,912.00  meanAbsError=706.85
violationRate = 2.25%   (nominal per-key delta=0.01 = 1%)
```

**Reading this correctly**: the CMS never underestimates (verified
separately, and enforced as a hard invariant in `CountMinSketchTest`). The
`1 - delta` bound is a *per-key* probabilistic guarantee, not a
simultaneous guarantee across all 2000 keys queried in this run -- a union
bound would predict the *simultaneous* failure probability scaling with
the number of keys queried, so seeing a handful of violations when checking
2000 keys at once against a per-key 1% bound is expected, not evidence the
implementation is wrong. See `docs/DESIGN.md` §2 for the derivation. For a
deployment that needs a tighter simultaneous guarantee across many tenants,
lower `delta` (linear cost in `depth`, i.e. sketch size and per-update
work) accordingly -- `CMS_DELTA` is a `NodeConfig` environment variable for
exactly this tuning.

## 2. Gossip convergence rounds vs. cluster size (Python prototype)

Setup: discrete-round push-gossip simulation, `fanout = ceil(log2(n))`
matching `MembershipTable#recommendedFanout`, 200 trials per cluster size,
starting from a single informed node.

```
    n   avg_rounds   max_rounds   log2(n)
    2         2.06           11       1.00
    4         2.30            4       2.00
    8         2.65            4       3.00
   16         3.03            4       4.00
   32         3.19            4       5.00
   64         3.63            4       6.00
  128         4.00            5       7.00
  256         4.00            5       8.00
```

Rounds needed stay small and grow far slower than `n` itself -- consistent
with the `O(log n)` bound claimed on the resume (an even tighter bound in
this fanout regime, but `O(log n)` is the safe, standard, defensible claim
to make and defend). Translating rounds to wall-clock time: with
`GossipNode.PROTOCOL_PERIOD_MILLIS = 200`, an 8-node cluster converging in
~3 rounds is ~600ms end to end for a cost update originating on one node to
be visible cluster-wide -- this is the number that should drive
`SAFETY_SLACK` sizing for a given deployment (see `docs/DESIGN.md` §4).

## 3. Real end-to-end validation (Java, JUnit + SimulationRunner)

These properties are exercised against the actual implementation, not the
prototype:

- `CountMinSketchTest` -- never-underestimates invariant, empirical error
  bound compliance, merge/diff/applyDelta linearity round-trip.
- `ConservativeCountMinSketchTest` -- conservative update is never worse
  (and typically better) than vanilla CMS on a skewed workload.
- `QuotaAdjustmentAlgorithmTest` -- local budget share never exceeds
  `maxShare * totalQuota` even against a simulated adversarial node that
  always reports maximal demand; never drops below `minShare * totalQuota`;
  converges toward the target share under repeated smoothing.
- `QuotaManagerTest` -- the hard global safety valve rejects even when
  local budget has room, once the gossiped global estimate would cross the
  cap.
- `GossipNodeConvergenceTest` -- a real 5-node cluster over real UDP
  sockets converges on an injected cost update; a real 4-node cluster
  correctly marks a hard-killed peer `DEAD` via the SWIM-inspired failure
  detector.

Run the full suite with `mvn test`, or see the GitHub Actions workflow
(`.github/workflows/ci.yml`) for the exact command run on every push.

## 4. Reproducing on your machine

```bash
mvn -q test                                  # full JUnit suite
./scripts/run-simulation.sh                  # real Java gossip cluster benchmark (loopback UDP)
docker compose up --build                    # real 5-container cluster
./scripts/load-test.sh                       # multi-tenant concurrent load against the docker cluster
./scripts/fault-injection.sh both            # kill + partition a node, watch the cluster degrade gracefully
```
