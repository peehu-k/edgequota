package com.edgequota.quota;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Per-tenant, per-node admission control. This is what
 * {@link com.edgequota.gateway.RateLimitHandler} calls on the request path.
 *
 * Every admit/reject decision is made *entirely locally* -- no blocking RPC
 * to a coordinator, no synchronous cross-node call -- using only:
 *   (a) this node's locally-smoothed budget for the tenant (efficient,
 *       usually sufficient on its own), and
 *   (b) the gossiped global estimate compared against a hard cap (the
 *       safety valve that actually bounds cluster-wide overshoot).
 *
 * See {@link QuotaAdjustmentAlgorithm} for the budget-smoothing math.
 */
public final class QuotaManager {

    private final ClusterCostView view;
    private final QuotaAdjustmentAlgorithm algorithm;
    private final long windowMillis;
    private final double safetySlack;
    private final int expectedClusterSize;

    private final ConcurrentMap<String, Double> tenantTotalQuota = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, TenantWindowState> tenantState = new ConcurrentHashMap<>();

    private static final class TenantWindowState {
        volatile double localBudget;
        volatile double localUsed;
        volatile long windowStartMillis;

        TenantWindowState(double localBudget, long windowStartMillis) {
            this.localBudget = localBudget;
            this.windowStartMillis = windowStartMillis;
        }
    }

    public QuotaManager(ClusterCostView view, QuotaAdjustmentAlgorithm algorithm, long windowMillis,
                         double safetySlack, int expectedClusterSize) {
        this.view = view;
        this.algorithm = algorithm;
        this.windowMillis = windowMillis;
        this.safetySlack = safetySlack;
        this.expectedClusterSize = expectedClusterSize;
    }

    /** Registers (or updates) a tenant's total cluster-wide cost budget per window. */
    public void configureTenant(String tenantId, double totalQuotaPerWindow) {
        tenantTotalQuota.put(tenantId, totalQuotaPerWindow);
        tenantState.computeIfAbsent(tenantId, id ->
                new TenantWindowState(totalQuotaPerWindow / Math.max(1, expectedClusterSize), System.currentTimeMillis()));
    }

    public synchronized QuotaDecision tryAdmit(String tenantId, double cost) {
        Double totalQuota = tenantTotalQuota.get(tenantId);
        if (totalQuota == null) {
            // Unconfigured tenants get no budget by default -- fail closed rather than silently unlimited.
            return QuotaDecision.rejectLocalBudget(0, 0, 0);
        }

        TenantWindowState state = tenantState.computeIfAbsent(tenantId, id ->
                new TenantWindowState(totalQuota / Math.max(1, expectedClusterSize), System.currentTimeMillis()));

        maybeRecalculate(tenantId, totalQuota, state);

        double globalEstimate = view.globalEstimate(tenantId);
        double hardCap = totalQuota * (1.0 + safetySlack);

        if (globalEstimate + cost > hardCap) {
            return QuotaDecision.rejectGlobalCap(globalEstimate, hardCap);
        }

        if (state.localUsed + cost > state.localBudget) {
            return QuotaDecision.rejectLocalBudget(state.localBudget - state.localUsed, globalEstimate, hardCap);
        }

        state.localUsed += cost;
        view.recordCost(tenantId, cost);
        return QuotaDecision.admit(state.localBudget - state.localUsed, globalEstimate, hardCap);
    }

    private void maybeRecalculate(String tenantId, double totalQuota, TenantWindowState state) {
        long now = System.currentTimeMillis();
        if (now - state.windowStartMillis < windowMillis) {
            return;
        }
        double localEstimate = view.localEstimate(tenantId);
        double globalEstimate = view.globalEstimate(tenantId);
        double fallbackShare = 1.0 / Math.max(1, expectedClusterSize);
        double share = algorithm.computeLocalShare(localEstimate, globalEstimate, fallbackShare);
        double nextBudget = algorithm.computeNextBudget(totalQuota, state.localBudget, share);
        state.localBudget = nextBudget;
        state.localUsed = 0;
        state.windowStartMillis = now;
    }

    /** Test/metrics hook: current local budget snapshot for a tenant, or -1 if unconfigured. */
    public double currentLocalBudget(String tenantId) {
        TenantWindowState s = tenantState.get(tenantId);
        return s == null ? -1 : s.localBudget;
    }
}
