package com.edgequota.cost;

/**
 * Turns a request into a single scalar "cost" that the rest of EdgeQuota
 * rate-limits against, instead of a flat 1-request-per-slot count. Real API
 * gateways sit in front of wildly heterogeneous workloads -- a 4-token
 * health check and a 12,000-token LLM completion, or a trivial GraphQL
 * lookup vs. a deeply nested query -- and charging both the same "1
 * request" either starves cheap tenants under a low global limit or lets
 * expensive tenants blow the backend's real capacity under a high one.
 *
 * The three signals combined here are deliberately cheap to compute on the
 * request path (no backend round-trip):
 *
 *  1. token count      -- for LLM-proxying gateways, an approximate token
 *                          count (chars/4 heuristic, consistent with the
 *                          rule of thumb used by most tokenizer-agnostic
 *                          estimators) as a stand-in for real inference
 *                          cost when the exact tokenizer isn't available
 *                          at the gateway.
 *  2. GraphQL complexity -- a static structural complexity score (field
 *                          count weighted by nesting depth), the same
 *                          family of technique used by graphql-cost-analysis
 *                          / graphql-query-complexity libraries, computed
 *                          by a tiny hand-rolled scanner (no GraphQL
 *                          parser dependency -- see {@link GraphQlComplexityScanner}).
 *  3. request size       -- raw byte size, a cheap universal fallback that
 *                          also matters for non-LLM, non-GraphQL REST/gRPC
 *                          traffic (large payload uploads cost more to
 *                          proxy/buffer regardless of semantic content).
 *
 * The three are combined with configurable weights into one scalar so a
 * single Count-Min Sketch can track "cost" per tenant without needing a
 * separate sketch per cost dimension.
 */
public final class CostEstimator {

    private final double tokenWeight;
    private final double complexityWeight;
    private final double byteWeight;
    private final double approxCharsPerToken;

    public CostEstimator(double tokenWeight, double complexityWeight, double byteWeight, double approxCharsPerToken) {
        this.tokenWeight = tokenWeight;
        this.complexityWeight = complexityWeight;
        this.byteWeight = byteWeight;
        this.approxCharsPerToken = approxCharsPerToken;
    }

    public static CostEstimator defaultEstimator() {
        // Byte weight is tiny relative to token/complexity weight because a
        // typical body is thousands of bytes but should not dominate cost
        // versus a handful of expensive tokens; tune per deployment.
        return new CostEstimator(1.0, 4.0, 0.001, 4.0);
    }

    public RequestCost estimate(String bodyText, boolean isGraphQl) {
        double bytes = bodyText == null ? 0 : bodyText.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
        double tokens = bodyText == null ? 0 : Math.ceil(bodyText.length() / approxCharsPerToken);
        double complexity = 0;
        if (isGraphQl && bodyText != null) {
            complexity = GraphQlComplexityScanner.score(bodyText);
        }
        double weighted = tokens * tokenWeight + complexity * complexityWeight + bytes * byteWeight;
        // Every request costs at least a small epsilon so the sketch still
        // reflects "a request happened" even for zero-body GETs.
        weighted = Math.max(weighted, 1.0);
        return new RequestCost(tokens, complexity, bytes, weighted);
    }
}
