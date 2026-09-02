package com.edgequota.quota;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class QuotaManagerTest {

    /** In-memory fake so quota logic is tested without any real gossip/networking. */
    static final class FakeClusterCostView implements ClusterCostView {
        double local = 0;
        double global = 0;

        @Override
        public double localEstimate(String key) {
            return local;
        }

        @Override
        public double globalEstimate(String key) {
            return global;
        }

        @Override
        public void recordCost(String key, double weight) {
            local += weight;
            global += weight;
        }
    }

    @Test
    void unconfiguredTenantIsRejected() {
        FakeClusterCostView view = new FakeClusterCostView();
        QuotaManager qm = new QuotaManager(view, QuotaAdjustmentAlgorithm.defaultAlgorithm(3), 1000, 0.1, 3);
        QuotaDecision d = qm.tryAdmit("unknown-tenant", 1.0);
        assertFalse(d.admitted);
    }

    @Test
    void admitsUntilLocalBudgetExhausted() {
        FakeClusterCostView view = new FakeClusterCostView();
        QuotaManager qm = new QuotaManager(view, QuotaAdjustmentAlgorithm.defaultAlgorithm(1), 60_000, 0.5, 1);
        qm.configureTenant("acme", 100.0);

        int admitted = 0;
        for (int i = 0; i < 1000; i++) {
            QuotaDecision d = qm.tryAdmit("acme", 1.0);
            if (d.admitted) {
                admitted++;
            } else {
                break;
            }
        }
        assertTrue(admitted > 0, "should admit at least some requests before exhausting budget");
        assertTrue(admitted <= 100, "should never admit more than the hard cap allows for a single node in this fixture");
    }

    @Test
    void globalSafetyValveRejectsEvenWithLocalBudgetRemaining() {
        FakeClusterCostView view = new FakeClusterCostView();
        // Pretend the rest of the cluster has already consumed almost the entire budget.
        view.global = 95.0;
        QuotaManager qm = new QuotaManager(view, QuotaAdjustmentAlgorithm.defaultAlgorithm(1), 60_000, 0.1, 1);
        qm.configureTenant("acme", 100.0); // hardCap = 110

        QuotaDecision small = qm.tryAdmit("acme", 5.0); // 95+5=100 <= 110, should admit if local budget allows
        // Local budget defaults to totalQuota/clusterSize = 100 initially, so this should admit.
        assertTrue(small.admitted);

        QuotaDecision tooBig = qm.tryAdmit("acme", 50.0); // would push global estimate past hardCap
        assertFalse(tooBig.admitted);
        assertEquals("global-safety-valve: cluster-wide estimate would exceed hard cap", tooBig.reason);
    }
}
