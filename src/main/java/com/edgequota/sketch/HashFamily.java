package com.edgequota.sketch;

import com.edgequota.hash.Murmur3;
import java.nio.charset.StandardCharsets;

/**
 * Produces {@code depth} pairwise-independent-enough row indices for a given
 * key using the Kirsch-Mitzenmacher double-hashing trick:
 *
 *   h_i(x) = (h1(x) + i * h2(x)) mod width      for i in [0, depth)
 *
 * This is the standard trick used by production Count-Min Sketch
 * implementations (e.g. Twitter's Algebird, Apache DataSketches variants) to
 * avoid computing `depth` independent hash functions per update -- we only
 * ever compute one 128-bit Murmur3 hash (two 64-bit lanes) per key,
 * regardless of depth. Simulation results (see docs/BENCHMARKS.md) confirm
 * this still gives the expected 1/e collision behaviour Count-Min Sketch
 * relies on.
 */
final class HashFamily {

    private final int width;
    private final int depth;
    private final long seed;

    HashFamily(int width, int depth, long seed) {
        this.width = width;
        this.depth = depth;
        this.seed = seed;
    }

    /** Returns one column index per row (length == depth). */
    int[] indicesFor(String key) {
        Murmur3.Hash128 h = Murmur3.hash128(key.getBytes(StandardCharsets.UTF_8), seed);
        int[] idx = new int[depth];
        long h1 = h.h1;
        long h2 = h.h2 | 1L; // force h2 odd so it's coprime-ish with power-of-two-free widths, reduces clustering
        for (int i = 0; i < depth; i++) {
            long combined = h1 + (long) i * h2;
            int mod = (int) (combined % width);
            idx[i] = mod < 0 ? mod + width : mod;
        }
        return idx;
    }
}
