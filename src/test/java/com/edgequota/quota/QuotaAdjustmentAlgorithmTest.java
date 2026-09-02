package com.edgequota.quota;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class QuotaAdjustmentAlgorithmTest {

    @Test
    void shareIsClampedToConfiguredBounds() {
        QuotaAdjustmentAlgorithm algo = new QuotaAdjustmentAlgorithm(0.1, 0.6, 1.0);
        // localEstimate == globalEstimate would naively imply share=1.0, but must clamp to maxShare.
        assertEquals(0.6, algo.computeLocalShare(100, 100, 0.25), 1e-9);
        // localEstimate is a tiny fraction of global => clamp to minShare.
        assertEquals(0.1, algo.computeLocalShare(1, 1000, 0.25), 1e-9);
    }

    @Test
    void fallbackShareUsedWhenGlobalEstimateIsZero() {
        QuotaAdjustmentAlgorithm algo = new QuotaAdjustmentAlgorithm(0.05, 0.8, 1.0);
        assertEquals(0.25, algo.computeLocalShare(0, 0, 0.25), 1e-9);
    }

    @Test
    void nextBudgetNeverExceedsMaxShareOfTotalRegardlessOfAdversarialSkew() {
        QuotaAdjustmentAlgorithm algo = new QuotaAdjustmentAlgorithm(0.05, 0.5, 0.9);
        double totalQuota = 10_000;
        double budget = totalQuota / 4; // arbitrary starting point
        // Simulate an adversarial node claiming it wants ~100% of demand every round.
        for (int round = 0; round < 50; round++) {
            double share = algo.computeLocalShare(1_000_000, 1_000_000, 0.25); // clamps to maxShare=0.5
            budget = algo.computeNextBudget(totalQuota, budget, share);
            assertTrue(budget <= totalQuota * 0.5 + 1e-6,
                    "budget must never exceed maxShare*totalQuota, got " + budget);
        }
    }

    @Test
    void nextBudgetNeverGoesBelowMinShareOfTotal() {
        QuotaAdjustmentAlgorithm algo = new QuotaAdjustmentAlgorithm(0.1, 0.9, 0.9);
        double totalQuota = 5_000;
        double budget = totalQuota / 2;
        for (int round = 0; round < 50; round++) {
            double share = algo.computeLocalShare(0, 1_000_000, 0.25); // this node sees no local demand
            budget = algo.computeNextBudget(totalQuota, budget, share);
            assertTrue(budget >= totalQuota * 0.1 - 1e-6,
                    "budget must never go below minShare*totalQuota, got " + budget);
        }
    }

    @Test
    void smoothingConvergesBudgetTowardTargetOverRounds() {
        QuotaAdjustmentAlgorithm algo = new QuotaAdjustmentAlgorithm(0.0, 1.0, 0.3);
        double totalQuota = 1000;
        double budget = 0;
        double share = 0.5; // fixed fair share every round
        double target = totalQuota * share;
        for (int round = 0; round < 30; round++) {
            budget = algo.computeNextBudget(totalQuota, budget, share);
        }
        assertEquals(target, budget, 1.0, "budget should converge close to target after enough rounds");
    }

    @Test
    void rejectsInvalidConstruction() {
        assertThrows(IllegalArgumentException.class, () -> new QuotaAdjustmentAlgorithm(0.6, 0.4, 0.5));
        assertThrows(IllegalArgumentException.class, () -> new QuotaAdjustmentAlgorithm(-0.1, 0.5, 0.5));
        assertThrows(IllegalArgumentException.class, () -> new QuotaAdjustmentAlgorithm(0.1, 1.5, 0.5));
        assertThrows(IllegalArgumentException.class, () -> new QuotaAdjustmentAlgorithm(0.1, 0.5, 0.0));
        assertThrows(IllegalArgumentException.class, () -> new QuotaAdjustmentAlgorithm(0.1, 0.5, 1.1));
    }
}
