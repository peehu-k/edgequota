package com.edgequota.sim;

import com.edgequota.gossip.GossipNode;
import com.edgequota.gossip.NodeId;
import com.edgequota.gossip.PeerStatus;
import com.edgequota.sketch.CountMinSketch;

import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * A real (loopback UDP, real threads, real wall-clock time) multi-node
 * cluster simulation used to produce the numbers quoted in
 * docs/BENCHMARKS.md: gossip convergence rounds, Count-Min Sketch accuracy
 * under a skewed workload, and behaviour under a simulated node failure.
 *
 * This is not a mock -- it spins up N actual {@link GossipNode} instances,
 * each with its own UDP socket bound on localhost, and lets the real
 * SWIM-inspired failure detector and epidemic gossip dissemination run.
 * It exists because standing up an actual multi-container Docker cluster
 * on every CI run (see docker-compose.yml for the container-based version
 * used for manual fault-injection testing) is slower and flakier for quick,
 * repeatable measurement than an in-process loopback cluster of the same
 * code path.
 *
 * Run with: java -cp target/classes com.edgequota.sim.SimulationRunner
 */
public final class SimulationRunner {

    public static void main(String[] args) throws Exception {
        System.out.println("=== EdgeQuota simulation & benchmark harness ===\n");
        cmsAccuracyBenchmark();
        System.out.println();
        gossipConvergenceBenchmark(8);
        System.out.println();
        faultInjectionBenchmark(6);
    }

    // ---------------------------------------------------------------
    // 1. Count-Min Sketch accuracy under a skewed (Zipfian-ish) workload
    // ---------------------------------------------------------------
    private static void cmsAccuracyBenchmark() {
        System.out.println("-- Count-Min Sketch accuracy benchmark --");
        double epsilon = 0.001;
        double delta = 0.01;
        CountMinSketch sketch = new CountMinSketch(epsilon, delta, 42L);

        int numKeys = 2000;
        Random rnd = new Random(7);
        java.util.Map<String, Double> trueCounts = new java.util.HashMap<>();
        int totalEvents = 500_000;
        double totalWeight = 0;

        for (int i = 0; i < totalEvents; i++) {
            // Zipfian-ish skew: 20% of keys receive 80% of traffic.
            String key;
            if (rnd.nextDouble() < 0.8) {
                key = "hot-" + rnd.nextInt((int) (numKeys * 0.2));
            } else {
                key = "cold-" + rnd.nextInt((int) (numKeys * 0.8));
            }
            double weight = 1 + rnd.nextInt(50); // heterogeneous per-request cost
            sketch.update(key, weight);
            trueCounts.merge(key, weight, Double::sum);
            totalWeight += weight;
        }

        double theoreticalBound = sketch.errorBound(epsilon);
        double maxObservedError = 0;
        double sumAbsError = 0;
        for (var e : trueCounts.entrySet()) {
            double est = sketch.estimate(e.getKey());
            double err = est - e.getValue();
            maxObservedError = Math.max(maxObservedError, err);
            sumAbsError += Math.abs(err);
        }
        double meanAbsError = sumAbsError / trueCounts.size();

        System.out.printf(java.util.Locale.ROOT,
                "width=%d depth=%d totalWeight=%.0f theoreticalBound(eps*W)=%.1f%n",
                sketch.width(), sketch.depth(), totalWeight, theoreticalBound);
        System.out.printf(java.util.Locale.ROOT,
                "distinct keys=%d maxObservedError=%.2f meanAbsError=%.3f withinBound=%s%n",
                trueCounts.size(), maxObservedError, meanAbsError, maxObservedError <= theoreticalBound);
    }

    // ---------------------------------------------------------------
    // 2. Gossip convergence: rounds until all N nodes agree on a
    //    globally-injected cost delta, for increasing cluster sizes.
    // ---------------------------------------------------------------
    private static void gossipConvergenceBenchmark(int maxN) throws Exception {
        System.out.println("-- Gossip convergence benchmark --");
        for (int n = 2; n <= maxN; n *= 2) {
            long rounds = runConvergenceTrial(n);
            System.out.printf(java.util.Locale.ROOT,
                    "n=%2d nodes -> converged in %d protocol rounds (~%d ms), log2(n)=%.1f%n",
                    n, rounds, rounds * GossipNode.PROTOCOL_PERIOD_MILLIS, Math.log(n) / Math.log(2));
        }
    }

    private static long runConvergenceTrial(int n) throws Exception {
        List<GossipNode> nodes = new ArrayList<>();
        List<NodeId> allIds = new ArrayList<>();
        int basePort = 17000 + new Random().nextInt(2000);

        for (int i = 0; i < n; i++) {
            NodeId id = new NodeId("sim-" + i, new InetSocketAddress("127.0.0.1", basePort + i));
            allIds.add(id);
        }
        for (int i = 0; i < n; i++) {
            GossipNode node = new GossipNode(allIds.get(i), basePort + i, 0.01, 0.05, 999L);
            List<NodeId> peers = new ArrayList<>(allIds);
            peers.remove(i);
            node.seedPeers(peers);
            nodes.add(node);
        }
        for (GossipNode node : nodes) {
            node.start();
        }

        // let membership/failure-detector warm up briefly
        Thread.sleep(300);

        String key = "convergence-test-key";
        nodes.get(0).recordCost(key, 1000.0);

        long start = System.currentTimeMillis();
        long deadline = start + 15_000;
        boolean converged = false;
        long rounds = -1;
        while (System.currentTimeMillis() < deadline) {
            boolean allSee = true;
            for (GossipNode node : nodes) {
                if (node.globalEstimate(key) < 999.0) {
                    allSee = false;
                    break;
                }
            }
            if (allSee) {
                converged = true;
                rounds = (System.currentTimeMillis() - start) / GossipNode.PROTOCOL_PERIOD_MILLIS + 1;
                break;
            }
            Thread.sleep(20);
        }

        for (GossipNode node : nodes) {
            node.close();
        }
        if (!converged) {
            return -1;
        }
        return rounds;
    }

    // ---------------------------------------------------------------
    // 3. Fault injection: kill a node mid-cluster and confirm the rest
    //    mark it DEAD within the suspicion timeout and keep serving.
    // ---------------------------------------------------------------
    private static void faultInjectionBenchmark(int n) throws Exception {
        System.out.println("-- Fault injection benchmark (node kill) --");
        List<GossipNode> nodes = new ArrayList<>();
        List<NodeId> allIds = new ArrayList<>();
        int basePort = 19500 + new Random().nextInt(2000);

        for (int i = 0; i < n; i++) {
            allIds.add(new NodeId("fault-" + i, new InetSocketAddress("127.0.0.1", basePort + i)));
        }
        for (int i = 0; i < n; i++) {
            GossipNode node = new GossipNode(allIds.get(i), basePort + i, 0.01, 0.05, 999L);
            List<NodeId> peers = new ArrayList<>(allIds);
            peers.remove(i);
            node.seedPeers(peers);
            nodes.add(node);
        }
        for (GossipNode node : nodes) {
            node.start();
        }

        Thread.sleep(500);

        // Kill node 0 without a graceful leave -- simulates a hard crash / network partition.
        NodeId killed = nodes.get(0).self();
        nodes.get(0).close();

        long start = System.currentTimeMillis();
        long deadline = start + 10_000;
        boolean allDetected = false;
        while (System.currentTimeMillis() < deadline) {
            boolean all = true;
            for (int i = 1; i < n; i++) {
                var entry = nodes.get(i).membership().get(killed.id());
                if (entry == null || entry.status != PeerStatus.DEAD) {
                    all = false;
                    break;
                }
            }
            if (all) {
                allDetected = true;
                break;
            }
            Thread.sleep(50);
        }
        long detectMillis = System.currentTimeMillis() - start;

        System.out.printf(java.util.Locale.ROOT,
                "killed node detected DEAD by all %d survivors: %s (%d ms, suspicion timeout=%d ms)%n",
                n - 1, allDetected, detectMillis, GossipNode.SUSPICION_TIMEOUT_MILLIS);

        // Confirm survivors still admit new gossip traffic after the kill (graceful degradation, no fail-open/fail-closed lockup).
        String key = "post-failure-key";
        nodes.get(1).recordCost(key, 42.0);
        Thread.sleep(1000);
        boolean stillWorks = nodes.get(n - 1).globalEstimate(key) >= 41.0;
        System.out.println("cluster still converges after node loss: " + stillWorks);

        for (int i = 1; i < n; i++) {
            nodes.get(i).close();
        }
    }
}
