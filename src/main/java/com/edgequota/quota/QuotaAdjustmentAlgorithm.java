package com.edgequota.quota;

/**
 * Pure, side-effect-free computation of "how much of a tenant's cluster-wide
 * budget should this node grant itself for the next window", given only
 * locally observable and gossip-derived numbers. Kept independent of
 * networking/gossip so it can be exhaustively unit tested (see
 * QuotaAdjustmentAlgorithmTest) against synthetic, adversarial traffic
 * distributions.
 *
 * <h2>Why this needs to be conservative</h2>
 * A node's view of "globalEstimate" always lags reality by up to one gossip
 * round-trip (bounded by the epidemic convergence time, ~O(log n) rounds --
 * see docs/DESIGN.md), and the Count-Min Sketch itself has a bounded but
 * nonzero one-sided error. Both of those need to be priced into how
 * aggressively a node grants itself local budget, or a burst of traffic
 * concentrated on one node right after a quota recalculation could locally
 * admit far more than its fair share before the next gossip round corrects
 * the picture cluster-wide.
 *
 * <h2>Two independent safety mechanisms</h2>
 * 1. {@link #computeLocalShare} -- a *soft*, smoothed estimate of this
 *    node's fair share of total demand, used so well-behaved traffic gets a
 *    locally-decided fast path most of the time instead of hammering the
 *    global check on every request.
 * 2. The *hard* safety valve lives in {@link QuotaManager#tryAdmit}: even a
 *    node whose local budget isn't exhausted will reject once the gossiped
 *    global estimate crosses totalQuota * (1 + safetySlack). That check is
 *    what actually bounds worst-case cluster-wide overshoot -- this class
 *    only controls how *efficient* (vs. purely global-check-bottlenecked)
 *    the common case is.
 */
public final class QuotaAdjustmentAlgorithm {

    private final double minShare;
    private final double maxShare;
    private final double smoothing; // exponential smoothing factor in (0,1]; higher = more reactive, more oscillation-prone

    public QuotaAdjustmentAlgorithm(double minShare, double maxShare, double smoothing) {
        if (minShare < 0 || minShare > maxShare || maxShare > 1.0) {
            throw new IllegalArgumentException("require 0 <= minShare <= maxShare <= 1");
        }
        if (smoothing <= 0 || smoothing > 1) {
            throw new IllegalArgumentException("smoothing must be in (0,1]");
        }
        this.minShare = minShare;
        this.maxShare = maxShare;
        this.smoothing = smoothing;
    }

    public static QuotaAdjustmentAlgorithm defaultAlgorithm(int expectedClusterSize) {
        double fairShare = 1.0 / Math.max(1, expectedClusterSize);
        // Allow a node to claim down to a quarter of a naive fair share
        // (idle nodes shrink fast) and up to 4x a naive fair share (a hot
        // node can grow, but never past maxShare=1 which would mean a
        // single node claims the entire cluster budget).
        double min = Math.max(0.0, fairShare * 0.25);
        double max = Math.min(1.0, fairShare * 4.0);
        return new QuotaAdjustmentAlgorithm(min, max, 0.5);
    }

    /**
     * @param localEstimate  this node's observed demand for the tenant since the last recalculation
     * @param globalEstimate cluster-wide observed demand for the tenant (gossip-merged, possibly stale)
     * @param fallbackShare  share to use when globalEstimate is ~0 (e.g. 1/clusterSize), avoiding div-by-zero at cluster start
     * @return this node's fair share of demand, clamped to [minShare, maxShare]
     */
    public double computeLocalShare(double localEstimate, double globalEstimate, double fallbackShare) {
        double share;
        if (globalEstimate < 1e-9) {
            share = fallbackShare;
        } else {
            share = localEstimate / globalEstimate;
        }
        return clamp(share, minShare, maxShare);
    }

    /**
     * @param totalQuota        the tenant's total cluster-wide budget for this window
     * @param previousLocalBudget the local budget this node granted itself last recalculation
     * @param localShare        output of computeLocalShare
     * @return the smoothed next local budget, always within [minShare, maxShare] * totalQuota
     */
    public double computeNextBudget(double totalQuota, double previousLocalBudget, double localShare) {
        double target = totalQuota * localShare;
        double next = previousLocalBudget + smoothing * (target - previousLocalBudget);
        double lower = totalQuota * minShare;
        double upper = totalQuota * maxShare;
        return clamp(next, lower, upper);
    }

    private static double clamp(double v, double lo, double hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    public double minShare() {
        return minShare;
    }

    public double maxShare() {
        return maxShare;
    }
}
