package com.edgequota.quota;

/** Outcome of a single admit/reject check, with enough detail for logging/metrics/tests. */
public final class QuotaDecision {
    public final boolean admitted;
    public final String reason;
    public final double localBudgetRemaining;
    public final double globalEstimate;
    public final double hardCap;

    private QuotaDecision(boolean admitted, String reason, double localBudgetRemaining, double globalEstimate, double hardCap) {
        this.admitted = admitted;
        this.reason = reason;
        this.localBudgetRemaining = localBudgetRemaining;
        this.globalEstimate = globalEstimate;
        this.hardCap = hardCap;
    }

    public static QuotaDecision admit(double localBudgetRemaining, double globalEstimate, double hardCap) {
        return new QuotaDecision(true, "admitted", localBudgetRemaining, globalEstimate, hardCap);
    }

    public static QuotaDecision rejectGlobalCap(double globalEstimate, double hardCap) {
        return new QuotaDecision(false, "global-safety-valve: cluster-wide estimate would exceed hard cap", 0, globalEstimate, hardCap);
    }

    public static QuotaDecision rejectLocalBudget(double localBudgetRemaining, double globalEstimate, double hardCap) {
        return new QuotaDecision(false, "local-budget-exhausted", localBudgetRemaining, globalEstimate, hardCap);
    }
}
