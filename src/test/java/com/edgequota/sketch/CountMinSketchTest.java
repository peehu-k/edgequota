package com.edgequota.sketch;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

class CountMinSketchTest {

    @Test
    void estimateIsNeverBelowTrueCount() {
        CountMinSketch sketch = new CountMinSketch(0.01, 0.05, 1L);
        Map<String, Double> truth = new HashMap<>();
        Random rnd = new Random(1);
        for (int i = 0; i < 10_000; i++) {
            String key = "k" + rnd.nextInt(200);
            double w = 1 + rnd.nextInt(10);
            sketch.update(key, w);
            truth.merge(key, w, Double::sum);
        }
        for (Map.Entry<String, Double> e : truth.entrySet()) {
            assertTrue(sketch.estimate(e.getKey()) >= e.getValue() - 1e-9,
                    "CMS must never underestimate: " + e.getKey());
        }
    }

    @Test
    void estimateRespectsTheoreticalErrorBoundWithHighProbability() {
        double epsilon = 0.01;
        CountMinSketch sketch = new CountMinSketch(epsilon, 0.01, 2L);
        Map<String, Double> truth = new HashMap<>();
        Random rnd = new Random(2);
        double total = 0;
        for (int i = 0; i < 50_000; i++) {
            String key = "k" + rnd.nextInt(500);
            double w = 1 + rnd.nextInt(20);
            sketch.update(key, w);
            truth.merge(key, w, Double::sum);
            total += w;
        }
        double bound = epsilon * total;
        int violations = 0;
        for (Map.Entry<String, Double> e : truth.entrySet()) {
            double err = sketch.estimate(e.getKey()) - e.getValue();
            if (err > bound) {
                violations++;
            }
        }
        // delta=0.01 permits failure w.p. <=1%; assert well under a generous margin for test stability.
        double violationRate = violations / (double) truth.size();
        assertTrue(violationRate < 0.05, "violation rate too high: " + violationRate);
    }

    @Test
    void mergeInPlaceIsEquivalentToReplayingBothUpdateSequences() {
        CountMinSketch a = new CountMinSketch(0.02, 0.05, 5L);
        CountMinSketch b = new CountMinSketch(0.02, 0.05, 5L);
        CountMinSketch replayed = new CountMinSketch(0.02, 0.05, 5L);

        a.update("x", 3);
        a.update("y", 7);
        b.update("x", 4);
        b.update("z", 2);

        replayed.update("x", 3);
        replayed.update("y", 7);
        replayed.update("x", 4);
        replayed.update("z", 2);

        a.mergeInPlace(b);

        assertEquals(replayed.estimate("x"), a.estimate("x"), 1e-9);
        assertEquals(replayed.estimate("y"), a.estimate("y"), 1e-9);
        assertEquals(replayed.estimate("z"), a.estimate("z"), 1e-9);
        assertEquals(replayed.totalWeight(), a.totalWeight(), 1e-9);
    }

    @Test
    void diffThenApplyDeltaRoundTripsExactly() {
        CountMinSketch base = new CountMinSketch(0.02, 0.05, 9L);
        base.update("a", 10);
        CountMinSketch snapshot = base.copy();

        base.update("a", 5);
        base.update("b", 2);

        CountMinSketch delta = base.diff(snapshot);
        CountMinSketch reconstructed = snapshot.copy();
        reconstructed.applyDelta(delta.exportTable(), delta.totalWeight());

        assertEquals(base.estimate("a"), reconstructed.estimate("a"), 1e-9);
        assertEquals(base.estimate("b"), reconstructed.estimate("b"), 1e-9);
        assertEquals(base.totalWeight(), reconstructed.totalWeight(), 1e-9);
    }

    @Test
    void rejectsInvalidEpsilonAndDelta() {
        assertThrows(IllegalArgumentException.class, () -> new CountMinSketch(0.0, 0.05, 1L));
        assertThrows(IllegalArgumentException.class, () -> new CountMinSketch(1.5, 0.05, 1L));
        assertThrows(IllegalArgumentException.class, () -> new CountMinSketch(0.01, 0.0, 1L));
        assertThrows(IllegalArgumentException.class, () -> new CountMinSketch(0.01, 1.0, 1L));
    }

    @Test
    void rejectsNegativeWeight() {
        CountMinSketch sketch = new CountMinSketch(0.01, 0.05, 1L);
        assertThrows(IllegalArgumentException.class, () -> sketch.update("k", -1));
    }

    @Test
    void mergeRejectsMismatchedShapes() {
        CountMinSketch a = new CountMinSketch(0.01, 0.05, 1L);
        CountMinSketch b = new CountMinSketch(0.02, 0.05, 1L);
        assertThrows(IllegalArgumentException.class, () -> a.mergeInPlace(b));
    }
}
