package com.edgequota.gossip;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Thread-safe view of "who is in the cluster and what do we believe about
 * them right now". Backs both the SWIM failure detector (random probe
 * target selection, suspicion timeouts) and the gossip fanout used to
 * disseminate Count-Min Sketch deltas (random-peer push gossip).
 */
public final class MembershipTable {

    private final Map<String, MembershipEntry> peers = new ConcurrentHashMap<>();
    private final Random random = new Random();

    /** Returns true iff this call actually changed the recorded state for the node (new node, or a higher-precedence claim). */
    public boolean upsert(NodeId node, PeerStatus status, long incarnation) {
        boolean[] changed = {false};
        peers.compute(node.id(), (id, existing) -> {
            if (existing == null) {
                changed[0] = true;
                return new MembershipEntry(node, status, incarnation);
            }
            // SWIM ordering rule: higher incarnation always wins; on equal
            // incarnation, ALIVE < SUSPECT < DEAD in "severity" so a more
            // severe claim at the same incarnation overrides a milder one.
            if (incarnation > existing.incarnation
                    || (incarnation == existing.incarnation && severity(status) > severity(existing.status))) {
                existing.status = status;
                existing.incarnation = incarnation;
                existing.lastStatusChangeMillis = System.currentTimeMillis();
                if (status == PeerStatus.SUSPECT) {
                    existing.suspectSinceMillis = System.currentTimeMillis();
                }
                changed[0] = true;
            }
            return existing;
        });
        return changed[0];
    }

    private static int severity(PeerStatus s) {
        switch (s) {
            case ALIVE: return 0;
            case SUSPECT: return 1;
            case DEAD: return 2;
            default: return -1;
        }
    }

    public MembershipEntry get(String id) {
        return peers.get(id);
    }

    public Collection<MembershipEntry> all() {
        return peers.values();
    }

    public List<NodeId> aliveOrSuspectPeers(String excludingId) {
        return peers.values().stream()
                .filter(e -> !e.node.id().equals(excludingId))
                .filter(e -> e.status != PeerStatus.DEAD)
                .map(e -> e.node)
                .collect(Collectors.toList());
    }

    /** Picks up to `fanout` random distinct peers, excluding self and DEAD peers. Core of epidemic dissemination. */
    public List<NodeId> randomFanout(String excludingId, int fanout) {
        List<NodeId> candidates = aliveOrSuspectPeers(excludingId);
        java.util.Collections.shuffle(candidates, random);
        return candidates.subList(0, Math.min(fanout, candidates.size()));
    }

    public NodeId randomPeer(String excludingId) {
        List<NodeId> candidates = aliveOrSuspectPeers(excludingId);
        if (candidates.isEmpty()) {
            return null;
        }
        return candidates.get(random.nextInt(candidates.size()));
    }

    public int clusterSize() {
        return peers.size();
    }

    /** Recommended fanout for O(log n) round-based epidemic convergence. */
    public int recommendedFanout() {
        int n = Math.max(1, clusterSize());
        return Math.max(1, (int) Math.ceil(Math.log(n) / Math.log(2)));
    }
}
