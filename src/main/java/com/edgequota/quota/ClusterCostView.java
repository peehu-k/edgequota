package com.edgequota.quota;

/**
 * The minimal read surface {@link QuotaManager} needs from the gossip
 * layer: "what have I personally seen for this key" and "what does the
 * whole cluster look like right now, as far as I currently know". Kept as
 * a narrow interface (rather than depending on {@code GossipNode}
 * directly) so quota logic can be unit-tested with a fake in-memory view
 * instead of standing up real sockets.
 */
public interface ClusterCostView {
    double localEstimate(String key);

    double globalEstimate(String key);

    void recordCost(String key, double weight);
}
