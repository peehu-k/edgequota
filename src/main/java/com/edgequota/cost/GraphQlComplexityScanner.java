package com.edgequota.cost;

/**
 * A dependency-free, heuristic GraphQL query complexity scorer.
 *
 * This is intentionally not a spec-compliant GraphQL parser (no schema
 * awareness, no directive handling, no fragment expansion) -- pulling in a
 * real GraphQL parser is exactly the kind of heavyweight dependency the
 * gateway's request path should avoid. Instead it does a single linear
 * character scan, tracking brace depth and summing `max(1, depth)` for
 * every identifier-like token encountered, on the same intuition that
 * drives real complexity analyzers (graphql-cost-analysis and friends):
 * fields nested deeper inside a selection set are exponentially more
 * expensive to resolve than shallow ones, so they should cost more.
 *
 * It deliberately errs toward over-counting (arguments and literals get
 * swept in alongside field names) rather than under-counting, which is the
 * safer failure mode for a cost signal feeding a rate limiter: an
 * over-estimate makes a tenant pay slightly more of their budget for a
 * complex-looking query; an under-estimate would let genuinely expensive
 * queries slip through under-charged.
 */
final class GraphQlComplexityScanner {

    private GraphQlComplexityScanner() {
    }

    static double score(String query) {
        int depth = 0;
        double complexity = 0;
        int tokenLen = 0;
        boolean inString = false;

        for (int i = 0; i < query.length(); i++) {
            char c = query.charAt(i);
            if (c == '"') {
                inString = !inString;
                continue;
            }
            if (inString) {
                continue;
            }
            if (Character.isLetterOrDigit(c) || c == '_') {
                tokenLen++;
                continue;
            }
            if (tokenLen > 0) {
                complexity += Math.max(1, depth);
                tokenLen = 0;
            }
            if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth = Math.max(0, depth - 1);
            }
        }
        if (tokenLen > 0) {
            complexity += Math.max(1, depth);
        }
        return complexity;
    }
}
