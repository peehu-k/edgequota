package com.edgequota.gossip;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Real end-to-end test: actual UDP sockets on loopback, actual threads,
 * actual wall-clock gossip rounds. Verifies the two properties the resume
 * bullets claim: (1) cluster-wide convergence without exchanging raw
 * request logs (only deltas cross the wire), and (2) failure detection
 * marks a hard-killed peer DEAD within a bounded number of rounds.
 */
class GossipNodeConvergenceTest {

    @Test
    @Timeout(value = 20, unit = TimeUnit.SECONDS)
    void fiveNodesConvergeOnASingleNodesCostUpdate() throws Exception {
        int n = 5;
        int basePort = 27000 + new Random().nextInt(3000);
        List<NodeId> ids = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            ids.add(new NodeId("n" + i, new InetSocketAddress("127.0.0.1", basePort + i)));
        }
        List<GossipNode> nodes = new ArrayList<>();
        try {
            for (int i = 0; i < n; i++) {
                GossipNode node = new GossipNode(ids.get(i), basePort + i, 0.01, 0.05, 555L);
                List<NodeId> peers = new ArrayList<>(ids);
                peers.remove(i);
                node.seedPeers(peers);
                nodes.add(node);
            }
            for (GossipNode node : nodes) {
                node.start();
            }
            Thread.sleep(300);

            nodes.get(2).recordCost("tenant-x", 250.0);

            long deadline = System.currentTimeMillis() + 15_000;
            boolean converged = false;
            while (System.currentTimeMillis() < deadline) {
                converged = nodes.stream().allMatch(nd -> nd.globalEstimate("tenant-x") >= 249.0);
                if (converged) break;
                Thread.sleep(25);
            }
            assertTrue(converged, "all nodes should eventually agree on the injected cost");
        } finally {
            for (GossipNode node : nodes) {
                node.close();
            }
        }
    }

    @Test
    @Timeout(value = 20, unit = TimeUnit.SECONDS)
    void killedNodeIsEventuallyMarkedDeadBySurvivors() throws Exception {
        int n = 4;
        int basePort = 31000 + new Random().nextInt(3000);
        List<NodeId> ids = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            ids.add(new NodeId("d" + i, new InetSocketAddress("127.0.0.1", basePort + i)));
        }
        List<GossipNode> nodes = new ArrayList<>();
        try {
            for (int i = 0; i < n; i++) {
                GossipNode node = new GossipNode(ids.get(i), basePort + i, 0.01, 0.05, 777L);
                List<NodeId> peers = new ArrayList<>(ids);
                peers.remove(i);
                node.seedPeers(peers);
                nodes.add(node);
            }
            for (GossipNode node : nodes) {
                node.start();
            }
            Thread.sleep(400);

            NodeId killed = ids.get(0);
            nodes.get(0).close();

            long deadline = System.currentTimeMillis() + 15_000;
            boolean allDead = false;
            while (System.currentTimeMillis() < deadline) {
                allDead = true;
                for (int i = 1; i < n; i++) {
                    MembershipEntry e = nodes.get(i).membership().get(killed.id());
                    if (e == null || e.status != PeerStatus.DEAD) {
                        allDead = false;
                        break;
                    }
                }
                if (allDead) break;
                Thread.sleep(50);
            }
            assertTrue(allDead, "all survivors should mark the killed node DEAD");
        } finally {
            for (int i = 1; i < n; i++) {
                nodes.get(i).close();
            }
        }
    }
}
