package com.edgequota.sketch;

/**
 * Count-Min Sketch with "conservative update" (Estan &amp; Varghese, 2002).
 *
 * A vanilla CMS increments *every* row's cell by `weight` on every update,
 * which over-counts: cells other than the row that will ultimately hold the
 * minimum still absorb the full write and inflate future collisions for
 * *other* keys hashed into them. Conservative update instead computes the
 * post-update estimate first (currentMin + weight) and only raises a cell
 * up to that value if it is currently below it:
 *
 *   target = min_over_rows(cell) + weight
 *   for each row: cell[row] = max(cell[row], target)
 *
 * This never decreases accuracy (estimate is still a safe overestimate) but
 * empirically produces a substantially tighter error bound in practice,
 * which matters here because tenants share the sketch and a noisy-neighbour
 * tenant hashing into the same cells as a well-behaved tenant should not be
 * able to inflate the well-behaved tenant's charged cost more than
 * necessary. See docs/BENCHMARKS.md for a head-to-head accuracy comparison
 * against the vanilla {@link CountMinSketch} on a synthetic Zipfian
 * workload.
 *
 * Trade-off: conservative update breaks the simple linear mergeInPlace
 * semantics (it's no longer true that merge(A,B) == replay(A then B) at the
 * cell level, because "conservative" depends on order/state at write time).
 * EdgeQuota therefore uses the vanilla {@link CountMinSketch} as the
 * gossip/merge unit (where linearity is required for correctness) and uses
 * this conservative variant only for the *local*, single-node view a node
 * consults when making an immediate admit/reject decision on the request
 * path -- exactly where the tighter bound is most valuable and no merge is
 * involved.
 */
public class ConservativeCountMinSketch extends CountMinSketch {

    public ConservativeCountMinSketch(double epsilon, double delta, long seed) {
        super(epsilon, delta, seed);
    }

    @Override
    public void update(String key, double weight) {
        if (weight < 0) {
            throw new IllegalArgumentException("weight must be non-negative");
        }
        int[] idx = new HashFamilyAccessor(width, depth, seed).indicesFor(key);
        double currentMin = Double.MAX_VALUE;
        for (int row = 0; row < depth; row++) {
            double v = cell(row, idx[row]);
            if (v < currentMin) {
                currentMin = v;
            }
        }
        double target = currentMin + weight;
        for (int row = 0; row < depth; row++) {
            int col = idx[row];
            if (cell(row, col) < target) {
                setCell(row, col, target);
            }
        }
        addToTotalWeight(weight);
    }

    /**
     * Small package-private shim so this subclass can recompute the same row
     * indices as the parent without re-exposing HashFamily publicly. Keeps
     * HashFamily package-private (an internal detail) while still letting
     * conservative update reuse the identical hashing logic as estimate().
     */
    private static final class HashFamilyAccessor {
        private final HashFamily delegate;

        HashFamilyAccessor(int width, int depth, long seed) {
            this.delegate = new HashFamily(width, depth, seed);
        }

        int[] indicesFor(String key) {
            return delegate.indicesFor(key);
        }
    }
}
