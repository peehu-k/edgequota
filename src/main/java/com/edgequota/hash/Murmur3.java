package com.edgequota.hash;

/**
 * Self-contained MurmurHash3 (x64, 128-bit) implementation.
 *
 * EdgeQuota avoids pulling in a hashing library (Guava, etc.) so the sketch
 * package has zero external dependencies and can be reasoned about (and
 * audited) end to end. We only need the 128-bit variant: it gives us two
 * independent 64-bit lanes per input, which feed the Kirsch-Mitzenmacher
 * double-hashing scheme (see {@link com.edgequota.sketch.HashFamily}) to
 * cheaply derive d pairwise-independent-enough hash functions for the
 * Count-Min Sketch without computing d separate hashes from scratch.
 */
public final class Murmur3 {

    private static final long C1 = 0x87c37b91114253d5L;
    private static final long C2 = 0x4cf5ad432745937fL;

    private Murmur3() {
    }

    /** Result of a 128-bit murmur3 hash: two 64-bit lanes. */
    public static final class Hash128 {
        public final long h1;
        public final long h2;

        Hash128(long h1, long h2) {
            this.h1 = h1;
            this.h2 = h2;
        }
    }

    public static Hash128 hash128(byte[] key, long seed) {
        int length = key.length;
        long h1 = seed;
        long h2 = seed;
        int nblocks = length / 16;

        for (int i = 0; i < nblocks; i++) {
            long k1 = getBlockLE(key, i * 16);
            long k2 = getBlockLE(key, i * 16 + 8);

            k1 *= C1;
            k1 = Long.rotateLeft(k1, 31);
            k1 *= C2;
            h1 ^= k1;

            h1 = Long.rotateLeft(h1, 27);
            h1 += h2;
            h1 = h1 * 5 + 0x52dce729;

            k2 *= C2;
            k2 = Long.rotateLeft(k2, 33);
            k2 *= C1;
            h2 ^= k2;

            h2 = Long.rotateLeft(h2, 31);
            h2 += h1;
            h2 = h2 * 5 + 0x38495ab5;
        }

        long k1 = 0;
        long k2 = 0;
        int tailStart = nblocks * 16;
        int tailLen = length - tailStart;

        if (tailLen >= 15) k2 ^= ((long) key[tailStart + 14] & 0xff) << 48;
        if (tailLen >= 14) k2 ^= ((long) key[tailStart + 13] & 0xff) << 40;
        if (tailLen >= 13) k2 ^= ((long) key[tailStart + 12] & 0xff) << 32;
        if (tailLen >= 12) k2 ^= ((long) key[tailStart + 11] & 0xff) << 24;
        if (tailLen >= 11) k2 ^= ((long) key[tailStart + 10] & 0xff) << 16;
        if (tailLen >= 10) k2 ^= ((long) key[tailStart + 9] & 0xff) << 8;
        if (tailLen >= 9) k2 ^= ((long) key[tailStart + 8] & 0xff);
        if (tailLen >= 9) {
            k2 *= C2;
            k2 = Long.rotateLeft(k2, 33);
            k2 *= C1;
            h2 ^= k2;
        }

        if (tailLen >= 8) k1 ^= ((long) key[tailStart + 7] & 0xff) << 56;
        if (tailLen >= 7) k1 ^= ((long) key[tailStart + 6] & 0xff) << 48;
        if (tailLen >= 6) k1 ^= ((long) key[tailStart + 5] & 0xff) << 40;
        if (tailLen >= 5) k1 ^= ((long) key[tailStart + 4] & 0xff) << 32;
        if (tailLen >= 4) k1 ^= ((long) key[tailStart + 3] & 0xff) << 24;
        if (tailLen >= 3) k1 ^= ((long) key[tailStart + 2] & 0xff) << 16;
        if (tailLen >= 2) k1 ^= ((long) key[tailStart + 1] & 0xff) << 8;
        if (tailLen >= 1) k1 ^= ((long) key[tailStart] & 0xff);
        if (tailLen >= 1) {
            k1 *= C1;
            k1 = Long.rotateLeft(k1, 31);
            k1 *= C2;
            h1 ^= k1;
        }

        h1 ^= length;
        h2 ^= length;

        h1 += h2;
        h2 += h1;
        h1 = fmix64(h1);
        h2 = fmix64(h2);
        h1 += h2;
        h2 += h1;

        return new Hash128(h1, h2);
    }

    private static long getBlockLE(byte[] key, int offset) {
        long result = 0;
        for (int i = 0; i < 8; i++) {
            result |= ((long) key[offset + i] & 0xff) << (8 * i);
        }
        return result;
    }

    private static long fmix64(long k) {
        k ^= k >>> 33;
        k *= 0xff51afd7ed558ccdL;
        k ^= k >>> 33;
        k *= 0xc4ceb9fe1a85ec53L;
        k ^= k >>> 33;
        return k;
    }
}
