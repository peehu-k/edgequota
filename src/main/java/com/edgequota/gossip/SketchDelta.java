package com.edgequota.gossip;

import com.edgequota.sketch.CountMinSketch;

/**
 * The incremental contribution one node makes to the cluster-wide cost
 * estimate for one gossip round: "since I last told anyone, my local
 * sketch changed by this much". Deltas -- not raw request logs, not full
 * sketch snapshots -- are what goes over the wire, which keeps gossip
 * traffic roughly proportional to *new* activity per round rather than to
 * cluster size or total historical traffic. This is also the privacy-ish
 * property called out on the resume bullet: peers learn aggregate cost
 * deltas per tenant bucket, never the underlying per-request logs.
 *
 * Each node tracks, per known peer, the last delta epoch it has already
 * merged (see GossipNode#peerContributions), so a delta that arrives twice
 * (duplicate UDP, or forwarded by more than one intermediate node during
 * epidemic dissemination) can be detected and ignored via the monotonic
 * `epoch` field instead of being double-counted -- this is what keeps the
 * global estimate from drifting upward under retransmission.
 */
public final class SketchDelta {
    public final String originNodeId;
    public final long epoch; // monotonically increasing per-origin sequence number
    public final double[][] cells; // [depth][width], additive contribution for this epoch only
    public final double totalWeightDelta;

    public SketchDelta(String originNodeId, long epoch, double[][] cells, double totalWeightDelta) {
        this.originNodeId = originNodeId;
        this.epoch = epoch;
        this.cells = cells;
        this.totalWeightDelta = totalWeightDelta;
    }

    /** Folds this delta directly into the given accumulator sketch (a node's running view of one peer's contribution). */
    public void applyTo(CountMinSketch accumulator) {
        accumulator.applyDelta(cells, totalWeightDelta);
    }
}
