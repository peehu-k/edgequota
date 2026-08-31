package com.edgequota.sketch;

/**
 * A Count-Min Sketch over *weighted* events (request cost, not just count
 * of 1 per request). Built from scratch: no Guava, no DataSketches, no
 * Algebird -- just the width/depth math and Kirsch-Mitzenmacher hashing.
 *
 * <h2>Why weighted</h2>
 * EdgeQuota doesn't rate-limit "requests per second" -- it rate-limits
 * *cost* per tenant, where cost is a heterogeneous mix of token count,
 * GraphQL query complexity, and request byte size (see
 * {@link com.edgequota.cost.CostEstimator}). A vanilla CMS only supports
 * +1 per event; this one supports update(key, weight) for arbitrary
 * non-negative weight, which is required to track "how much cost has
 * tenant X consumed" rather than "how many requests has tenant X sent".
 *
 * <h2>Error guarantee</h2>
 * For width w = ceil(e / epsilon) and depth d = ceil(ln(1 / delta)):
 *
 *   estimate(x) &gt;= true(x)                                    always
 *   estimate(x) &lt;= true(x) + epsilon * sum(all weights)        w.p. >= 1 - delta
 *
 * This is the standard CMS guarantee (Cormode &amp; Muthukrishnan, 2005),
 * carried over unchanged to the weighted case because the sketch is a
 * linear summary: each update adds `weight` to d cells rather than adding
 * 1, and the proof only relies on linearity + pairwise independence of the
 * hash family, not on unit weights. See docs/DESIGN.md for the full
 * derivation and docs/BENCHMARKS.md for an empirical measurement of the
 * realized error vs. the theoretical bound.
 *
 * <h2>Mergeability</h2>
 * Two CMS instances built with identical (width, depth, seed) are linear in
 * the sense that mergeInPlace(other) is equivalent to having applied every
 * update from `other` directly to `this`. This is exactly the property the
 * gossip layer exploits: nodes exchange sketch *deltas* instead of raw
 * per-request logs, and merging deltas is commutative and idempotent-safe
 * when tracked per-origin (see {@link com.edgequota.gossip.SketchDelta}).
 */
public class CountMinSketch {

    /** Euler's number, used for the standard width formula w = ceil(e/epsilon). */
    private static final double E = Math.E;

    protected final int width;
    protected final int depth;
    protected final long seed;
    protected final double[][] table; // [depth][width]
    private final HashFamily hashFamily;
    private double totalWeight = 0.0;

    public CountMinSketch(double epsilon, double delta, long seed) {
        if (epsilon <= 0 || epsilon >= 1) {
            throw new IllegalArgumentException("epsilon must be in (0,1), got " + epsilon);
        }
        if (delta <= 0 || delta >= 1) {
            throw new IllegalArgumentException("delta must be in (0,1), got " + delta);
        }
        this.width = (int) Math.ceil(E / epsilon);
        this.depth = (int) Math.ceil(Math.log(1.0 / delta));
        this.seed = seed;
        this.table = new double[depth][width];
        this.hashFamily = new HashFamily(width, depth, seed);
    }

    /** Constructor for building an empty sketch with explicit dimensions (used when merging/deserializing). */
    public CountMinSketch(int width, int depth, long seed) {
        this.width = width;
        this.depth = depth;
        this.seed = seed;
        this.table = new double[depth][width];
        this.hashFamily = new HashFamily(width, depth, seed);
    }

    public int width() {
        return width;
    }

    public int depth() {
        return depth;
    }

    public long seed() {
        return seed;
    }

    public double totalWeight() {
        return totalWeight;
    }

    /** Record `weight` units of cost against `key`. */
    public void update(String key, double weight) {
        if (weight < 0) {
            throw new IllegalArgumentException("weight must be non-negative");
        }
        int[] idx = hashFamily.indicesFor(key);
        for (int row = 0; row < depth; row++) {
            table[row][idx[row]] += weight;
        }
        totalWeight += weight;
    }

    /** Point estimate for the cumulative weight recorded against `key`. */
    public double estimate(String key) {
        int[] idx = hashFamily.indicesFor(key);
        double min = Double.MAX_VALUE;
        for (int row = 0; row < depth; row++) {
            double v = table[row][idx[row]];
            if (v < min) {
                min = v;
            }
        }
        return min;
    }

    /** The theoretical additive error bound at the current total weight: epsilon * sum(weights). */
    public double errorBound(double epsilon) {
        return epsilon * totalWeight;
    }

    /**
     * Merge another sketch of identical dimensions into this one, in place.
     * Used both for local roll-up (merging a node's own historical shards)
     * and for applying a decoded gossip delta from a peer.
     */
    public void mergeInPlace(CountMinSketch other) {
        requireSameShape(other);
        for (int row = 0; row < depth; row++) {
            for (int col = 0; col < width; col++) {
                table[row][col] += other.table[row][col];
            }
        }
        totalWeight += other.totalWeight;
    }

    /** Returns a new sketch representing this - baseline, cell by cell. Used to compute gossip deltas. */
    public CountMinSketch diff(CountMinSketch baseline) {
        requireSameShape(baseline);
        CountMinSketch d = new CountMinSketch(width, depth, seed);
        for (int row = 0; row < depth; row++) {
            for (int col = 0; col < width; col++) {
                d.table[row][col] = table[row][col] - baseline.table[row][col];
            }
        }
        d.totalWeight = totalWeight - baseline.totalWeight;
        return d;
    }

    /** Deep copy, used to snapshot "what has been gossiped so far" without aliasing the live table. */
    public CountMinSketch copy() {
        CountMinSketch c = new CountMinSketch(width, depth, seed);
        for (int row = 0; row < depth; row++) {
            System.arraycopy(table[row], 0, c.table[row], 0, width);
        }
        c.totalWeight = totalWeight;
        return c;
    }

    protected void requireSameShape(CountMinSketch other) {
        if (other.width != width || other.depth != depth || other.seed != seed) {
            throw new IllegalArgumentException(
                    "cannot combine sketches with different (width, depth, seed): "
                            + "(" + width + "," + depth + "," + seed + ") vs "
                            + "(" + other.width + "," + other.depth + "," + other.seed + ")");
        }
    }

    /** Direct cell access, package-visible for delta encoding in the gossip layer. */
    double cell(int row, int col) {
        return table[row][col];
    }

    void setCell(int row, int col, double value) {
        table[row][col] = value;
    }

    /** Lets subclasses (e.g. ConservativeCountMinSketch) update the running total-weight counter. */
    protected void addToTotalWeight(double delta) {
        totalWeight += delta;
    }

    /**
     * Applies a raw per-cell additive delta (as produced by diff(), or
     * decoded off the wire as a {@code com.edgequota.gossip.SketchDelta})
     * directly onto this sketch's table, without re-hashing any keys. This
     * is the primitive the gossip layer uses to fold a peer's contribution
     * into a node's cluster-wide estimate.
     */
    public void applyDelta(double[][] deltaCells, double totalWeightDelta) {
        if (deltaCells.length != depth || deltaCells[0].length != width) {
            throw new IllegalArgumentException("delta shape does not match sketch shape");
        }
        for (int row = 0; row < depth; row++) {
            for (int col = 0; col < width; col++) {
                table[row][col] += deltaCells[row][col];
            }
        }
        totalWeight += totalWeightDelta;
    }

    /** Read-only export of the raw table, e.g. for computing a diff to send as a gossip delta. */
    public double[][] exportTable() {
        double[][] copy = new double[depth][width];
        for (int row = 0; row < depth; row++) {
            System.arraycopy(table[row], 0, copy[row], 0, width);
        }
        return copy;
    }
}
