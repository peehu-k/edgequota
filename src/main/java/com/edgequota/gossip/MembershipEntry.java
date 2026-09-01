package com.edgequota.gossip;

/**
 * A single row of the membership table: what we currently believe about one
 * peer, plus the SWIM "incarnation" number used to resolve conflicting
 * rumors (a higher incarnation from the peer itself always wins over a
 * stale SUSPECT/DEAD claim propagating through the cluster).
 */
public final class MembershipEntry {
    public final NodeId node;
    public volatile PeerStatus status;
    public volatile long incarnation;
    public volatile long lastStatusChangeMillis;
    public volatile long suspectSinceMillis;

    public MembershipEntry(NodeId node, PeerStatus status, long incarnation) {
        this.node = node;
        this.status = status;
        this.incarnation = incarnation;
        this.lastStatusChangeMillis = System.currentTimeMillis();
    }
}
