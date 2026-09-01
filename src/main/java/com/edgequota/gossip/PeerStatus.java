package com.edgequota.gossip;

/**
 * SWIM-style membership states. ALIVE and SUSPECT peers are still gossiped
 * to; DEAD peers are excluded from the random peer selection used for both
 * failure detection and sketch-delta dissemination, which is what lets the
 * cluster degrade gracefully (fail node kills stop contributing traffic to
 * quota math within a bounded number of rounds instead of poisoning global
 * estimates forever).
 */
public enum PeerStatus {
    ALIVE,
    SUSPECT,
    DEAD
}
