package com.edgequota.cost;

/** The heterogeneous cost components extracted for a single request, plus a blended scalar weight. */
public final class RequestCost {
    public final double tokenCount;
    public final double graphQlComplexity;
    public final double requestSizeBytes;
    public final double weightedCost;

    public RequestCost(double tokenCount, double graphQlComplexity, double requestSizeBytes, double weightedCost) {
        this.tokenCount = tokenCount;
        this.graphQlComplexity = graphQlComplexity;
        this.requestSizeBytes = requestSizeBytes;
        this.weightedCost = weightedCost;
    }
}
