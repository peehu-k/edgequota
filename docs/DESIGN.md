# EdgeQuota — Design Notes

This document is the "why", kept separate from the code comments (which are
the "how" at each call site) so the end-to-end argument is readable in one
pass. It's written to double as interview prep: every claim here should be
one you can defend and derive on a whiteboard.

## 1. The problem with a centralized (Redis) rate limiter

A typical multi-region API gateway rate limiter keeps a counter per tenant
in Redis (or an equivalent shared store) and every request does a
check-and-increment RPC against it before being admitted. Two things go
wrong at scale:

- **Latency**: every request pays a synchronous round trip to the limiter
  store, even in the common case where the tenant is nowhere near their
  quota. In a multi-region deployment this either means a single-region
  Redis primary (cross-region RPC latency on every request) or a
  replicated/sharded store (consistency lag, which reintroduces exactly the
  overshoot problem you were trying to avoid).
- **Single point of failure**: if the limiter store is unreachable, the
  gateway has to choose fail-open (unlimited traffic gets through, the
  scenario the limiter exists to prevent) or fail-closed (the limiter
  outage becomes a full outage for every tenant, even well-behaved ones).

EdgeQuota's core bet: move the *authoritative* decision off the request
path entirely. Every admit/reject decision is made locally, using a
sketch-based estimate of cluster-wide usage that's kept approximately
fresh via background gossip, not a synchronous call.

## 2. Count-Min Sketch: why, and the error bound

A tenant's cost budget needs a running total of "cost consumed", but
storing an exact per-tenant, per-resource counter that's cheap to gossip
means either bounding cardinality in advance (fragile: what about a new
resource type, or a burst of unique cache keys?) or accepting approximate
counting with a provable error bound. Count-Min Sketch (Cormode &
Muthukrishnan, 2005) gives the latter.

**Construction**: a `depth × width` table of counters, `depth` independent
hash functions (one per row). `update(key, w)` adds `w` to `table[i][h_i(key)]`
for each row `i`. `estimate(key) = min_i table[i][h_i(key)]`.

**Why width/depth are sized the way they are**:

```
width = ceil(e / epsilon)          (e = Euler's number)
depth = ceil(ln(1 / delta))
```

**Guarantee**: for any key `x`,

```
true(x) <= estimate(x)                                     always
estimate(x) <= true(x) + epsilon * sum(all weights)         w.p. >= 1 - delta
```

*Proof sketch* (why this holds, the version you'd actually say out loud in
an interview): fix key `x` and row `i`. The collision from any other key
`y != x` lands in `x`'s cell with probability `1/width` (pairwise-independent
hashing). The expected extra weight dumped into `x`'s cell from collisions
is therefore `<= sum(weights) / width = sum(weights) * epsilon / e`. By
Markov's inequality, `P(extra weight in row i > epsilon * sum(weights))
<= 1/e`. Since the `depth` rows use independent hash functions, the
probability that *every* row overestimates by more than `epsilon *
sum(weights)` is `<= (1/e)^depth = (1/e)^(ln(1/delta)) = delta`. Taking the
min over rows means `estimate(x)` only exceeds the bound if *all* rows do,
so the overall failure probability is `<= delta`. QED.

**Important nuance actually worth knowing cold**: this `1 - delta`
guarantee is *per query*, not simultaneous across every key in the sketch.
Querying `k` different keys and wanting *all* of them within bound
simultaneously needs a union bound (effective delta of `k * delta`), which
is why the empirical violation rate measured across ~2000 distinct keys in
`docs/BENCHMARKS.md` runs a bit above the nominal per-key `delta=0.01` --
that's expected, not a bug, and it's exactly the kind of question worth
being ready for if this project comes up in an interview.

**Weighted updates**: this isn't the textbook +1-per-event CMS. EdgeQuota's
cost signal (`CostEstimator`) is heterogeneous (tokens, GraphQL complexity,
byte size), so `update(key, weight)` needs arbitrary non-negative weight.
The proof above goes through unchanged because it only relies on linearity
of the table (`update` is always additive) and hash independence, not on
unit weights.

**Conservative update**: `ConservativeCountMinSketch` implements the
Estan & Varghese (2002) variant: instead of unconditionally adding `weight`
to every row, it computes `target = min_over_rows(cell) + weight` first and
only raises a cell up to `target` (never lowers it). This still never
underestimates, and empirically gives a materially tighter bound under
skewed traffic (see `ConservativeCountMinSketchTest` and the benchmark),
because a cell that's already inflated by an unrelated noisy-neighbour key
doesn't get pushed even higher by every subsequent update. Trade-off:
conservative update is not linear/mergeable (order of updates matters), so
it's used only for a node's own local point-in-time view, never as the unit
exchanged over gossip.

## 3. Gossip layer: SWIM-inspired failure detection + epidemic dissemination

Two separate concerns share one transport (`GossipTransport`, a hand-rolled
UDP NIO reactor) and one periodic protocol tick (`GossipNode#tick`):

### 3a. Failure detection (SWIM-inspired, simplified)

Full SWIM (Das, Gupta, Motivala, 2002) includes several refinements this
project doesn't implement in full (e.g. every node round-robins probe
targets rather than picking uniformly at random, and incarnation-based
self-refutation of false suspicion). What's implemented:

- Direct ping/ack with a timeout.
- On timeout, indirect probing: ask `k = ceil(log2(n))` random peers to
  ping the suspect on your behalf (works around a bad direct path when the
  node itself is fine but the direct route is lossy).
- If indirect probing also times out, mark the peer `SUSPECT`; if it stays
  `SUSPECT` past `SUSPICION_TIMEOUT_MILLIS` without a fresher ALIVE claim,
  mark it `DEAD` and stop routing traffic/gossip to it.
- Higher-`incarnation` claims always win conflicting rumors, so a rejoin
  (or a false-positive suspicion) can be corrected as it propagates.

This is the mechanism behind the "graceful degradation without
fail-open/fail-closed behaviour" resume bullet: a genuinely dead node stops
receiving gossip traffic and stops being counted as a probe target within a
bounded number of rounds, but nodes never block waiting on a coordinator to
declare it dead.

### 3b. Epidemic dissemination of sketch deltas

Each node tracks the delta between its current local sketch and the last
snapshot it gossiped (`generateLocalDeltaIfAny`), wraps a nonzero delta in
a monotonically increasing per-origin `epoch`, and pushes it to `fanout =
ceil(log2(clusterSize))` random peers per round. Receivers apply a delta at
most once (`epoch > lastAppliedEpoch[origin]` dedup) and, if it was new
information, relay it onward for a further `fanout` rounds ("rumor
mongering" -- Demers et al., 1987) before it's dropped from that node's
relay queue.

**Why O(log n) rounds**: standard push-gossip epidemic analysis: after
round `r`, the expected number of *un*-informed nodes shrinks roughly by a
factor of `e^-fanout` per round once a meaningful fraction of the cluster
is informed (each informed node "reaches" `fanout` new random targets).
With `fanout = ceil(log2 n)`, full dissemination completes in `O(log n)`
rounds with high probability -- this is the same argument that justifies
epidemic protocols in Amazon Dynamo, Cassandra, and Serf/consul's
membership gossip. `docs/BENCHMARKS.md` includes both a discrete-round
Python validation of this scaling and real wall-clock numbers from the
actual UDP-based `GossipNode` cluster.

**Explicitly not exchanged**: raw per-request logs. The only thing that
crosses the wire is aggregate sketch cell deltas -- a peer can tell "tenant
X's estimated cost went up by roughly Y" but never sees an individual
request.

## 4. Quota adjustment: bounding worst-case overshoot

Two independent mechanisms, deliberately layered (see
`QuotaAdjustmentAlgorithm` and `QuotaManager#tryAdmit`):

1. **Soft, smoothed local budget** (`QuotaAdjustmentAlgorithm`): each node
   periodically recomputes its own share of the tenant's total budget as
   `clamp(localEstimate / globalEstimate, minShare, maxShare)`, smoothed
   exponentially to avoid oscillation, and clamped so no single node can
   claim more than `maxShare` of the total even if it currently accounts
   for 100% of observed demand. This is what makes the common case cheap:
   most requests are decided against a purely local counter.

2. **Hard global safety valve** (`QuotaManager#tryAdmit`): independent of
   the local budget, every request also checks the gossiped
   `globalEstimate` against `totalQuota * (1 + safetySlack)`. This is what
   actually bounds worst-case cluster-wide overshoot, *including* under
   adversarial, maximally-uneven traffic distribution (e.g. an attacker
   deliberately spreading requests across every node right after a quota
   reset, specifically to exploit smoothing lag in mechanism 1): the
   moment enough traffic has landed *anywhere* in the cluster for the
   gossiped estimate to reflect it, every node's hard cap kicks in, so
   total admitted cost across the whole cluster is bounded by
   `totalQuota * (1 + safetySlack)` plus the cost that was in flight during
   the last gossip round's propagation delay. `safetySlack` should be set
   to cover the sum of the CMS's `epsilon * totalWeight` error and the
   expected worst-case gossip propagation delay for the deployed cluster
   size (see `docs/BENCHMARKS.md` for measured convergence times to size
   this in practice).

`QuotaAdjustmentAlgorithmTest` unit-tests mechanism 1's bounds directly
(including a simulated adversarial node that always reports maximal
demand); the gossip convergence tests exercise mechanism 2's dependency
(how stale can `globalEstimate` be) end to end over real sockets.

## 5. Netty vs. hand-rolled reactor

The resume line originally named Netty. This implementation uses
hand-rolled `java.nio` reactors (`GossipTransport` for UDP,
`GatewayServer` for the HTTP accept loop) instead, on purpose: it keeps the
whole project dependency-free (no Maven Central artifact needed to build or
audit any of the networking code) and, more importantly, demonstrates the
same event-loop concepts Netty is built on rather than treating them as a
black box. The `RateLimitHandler` / `HttpRequest` boundary is intentionally
the only coupling point to the transport, so swapping in real Netty (or any
other HTTP stack) is a localized change, not a redesign.
