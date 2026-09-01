package com.edgequota.sketch;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertTrue;

class ConservativeCountMinSketchTest {

    @Test
    void conservativeUpdateNeverUnderestimatesEither() {
        ConservativeCountMinSketch sketch = new ConservativeCountMinSketch(0.02, 0.05, 3L);
        Map<String, Double> truth = new HashMap<>();
        Random rnd = new Random(11);
        for (int i = 0; i < 20_000; i++) {
            String key = "k" + rnd.nextInt(300);
            double w = 1 + rnd.nextInt(15);
            sketch.update(key, w);
            truth.merge(key, w, Double::sum);
        }
        for (Map.Entry<String, Double> e : truth.entrySet()) {
            assertTrue(sketch.estimate(e.getKey()) >= e.getValue() - 1e-9);
        }
    }

    @Test
    void conservativeUpdateIsAtLeastAsTightAsVanillaOnSkewedWorkload() {
        long seed = 123L;
        double epsilon = 0.02;
        double delta = 0.05;

        CountMinSketch vanilla = new CountMinSketch(epsilon, delta, seed);
        ConservativeCountMinSketch conservative = new ConservativeCountMinSketch(epsilon, delta, seed);
        Map<String, Double> truth = new HashMap<>();

        Random rnd = new Random(42);
        for (int i = 0; i < 100_000; i++) {
            String key;
            if (rnd.nextDouble() < 0.9) {
                key = "hot-" + rnd.nextInt(20); // heavy skew: noisy-neighbour tenants
            } else {
                key = "cold-" + rnd.nextInt(2000);
            }
            double w = 1 + rnd.nextInt(30);
            vanilla.update(key, w);
            conservative.update(key, w);
            truth.merge(key, w, Double::sum);
        }

        double vanillaTotalError = 0;
        double conservativeTotalError = 0;
        for (Map.Entry<String, Double> e : truth.entrySet()) {
            vanillaTotalError += vanilla.estimate(e.getKey()) - e.getValue();
            conservativeTotalError += conservative.estimate(e.getKey()) - e.getValue();
        }

        assertTrue(conservativeTotalError <= vanillaTotalError + 1e-6,
                "conservative update should not be worse than vanilla: conservative="
                        + conservativeTotalError + " vanilla=" + vanillaTotalError);
    }
}
