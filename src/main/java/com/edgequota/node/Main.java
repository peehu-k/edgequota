package com.edgequota.node;

import com.edgequota.cost.CostEstimator;
import com.edgequota.gateway.GatewayServer;
import com.edgequota.gateway.RateLimitHandler;
import com.edgequota.gossip.GossipNode;
import com.edgequota.gossip.NodeId;
import com.edgequota.quota.QuotaAdjustmentAlgorithm;
import com.edgequota.quota.QuotaManager;

import java.net.InetSocketAddress;
import java.util.Map;

/**
 * Process entry point for one EdgeQuota gateway node. Wires together:
 *   GossipNode (membership + failure detection + sketch dissemination)
 *     -> QuotaManager (local admit/reject decisions)
 *       -> RateLimitHandler (per-request cost extraction + decision)
 *         -> GatewayServer (HTTP transport)
 *
 * See docker-compose.yml for how a multi-node cluster is launched, and
 * NodeConfig for the full list of environment variables this reads.
 */
public final class Main {

    public static void main(String[] args) throws Exception {
        NodeConfig config = NodeConfig.fromEnvironment();

        NodeId self = new NodeId(config.nodeId, new InetSocketAddress("0.0.0.0", config.gossipPort));
        // Shared CMS seed across the cluster is required for merges to be meaningful;
        // a fixed constant (rather than random) keeps every node's hash family identical.
        long sharedCmsSeed = 0x45646765517574L; // "EdgeQut" in hex-ish, arbitrary fixed constant

        GossipNode gossipNode = new GossipNode(self, config.gossipPort, config.cmsEpsilon, config.cmsDelta, sharedCmsSeed);
        gossipNode.seedPeers(config.peers);
        gossipNode.start();

        QuotaAdjustmentAlgorithm algorithm = QuotaAdjustmentAlgorithm.defaultAlgorithm(config.clusterSizeHint);
        QuotaManager quotaManager = new QuotaManager(gossipNode, algorithm, config.windowMillis,
                config.safetySlack, config.clusterSizeHint);
        for (Map.Entry<String, Double> tenant : config.tenantQuotas.entrySet()) {
            quotaManager.configureTenant(tenant.getKey(), tenant.getValue());
        }

        CostEstimator costEstimator = CostEstimator.defaultEstimator();
        RateLimitHandler handler = new RateLimitHandler(quotaManager, costEstimator);

        GatewayServer server = new GatewayServer(config.httpPort, handler, 16);
        server.start();

        System.out.println("EdgeQuota node '" + config.nodeId + "' up: http=:" + config.httpPort
                + " gossip=:" + config.gossipPort + " peers=" + config.peers.size()
                + " tenants=" + config.tenantQuotas.keySet());

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            server.close();
            gossipNode.close();
        }));

        Thread.currentThread().join();
    }
}
