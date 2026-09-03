package com.edgequota.node;

import com.edgequota.gossip.NodeId;

import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Environment-variable-driven configuration, deliberately simple (no YAML/
 * config-lib dependency) since every value here is either a single
 * primitive or a small delimited list, and this is exactly what Docker /
 * docker-compose passes to containers naturally via `environment:`.
 *
 * Recognized variables (see docker-compose.yml for a full multi-node example):
 *   NODE_ID        - stable id for this node, e.g. "node-1"
 *   GOSSIP_PORT     - UDP port for the gossip transport (default 7946)
 *   HTTP_PORT       - TCP port for the HTTP gateway (default 8080)
 *   PEERS           - comma-separated bootstrap peers as id=host:gossipPort, e.g. "node-2=node-2:7946,node-3=node-3:7946"
 *   TENANTS         - comma-separated tenantId=totalQuotaPerWindow, e.g. "acme=5000,globex=2000"
 *   WINDOW_MILLIS    - quota recalculation window (default 5000)
 *   SAFETY_SLACK     - fractional slack on the hard global cap (default 0.15)
 *   CLUSTER_SIZE_HINT - expected cluster size used for the initial fair-share fallback (default 1)
 *   CMS_EPSILON / CMS_DELTA - Count-Min Sketch error/confidence parameters (defaults 0.001 / 0.01)
 */
public final class NodeConfig {
    public final String nodeId;
    public final int gossipPort;
    public final int httpPort;
    public final List<NodeId> peers;
    public final Map<String, Double> tenantQuotas;
    public final long windowMillis;
    public final double safetySlack;
    public final int clusterSizeHint;
    public final double cmsEpsilon;
    public final double cmsDelta;

    private NodeConfig(String nodeId, int gossipPort, int httpPort, List<NodeId> peers,
                        Map<String, Double> tenantQuotas, long windowMillis, double safetySlack,
                        int clusterSizeHint, double cmsEpsilon, double cmsDelta) {
        this.nodeId = nodeId;
        this.gossipPort = gossipPort;
        this.httpPort = httpPort;
        this.peers = peers;
        this.tenantQuotas = tenantQuotas;
        this.windowMillis = windowMillis;
        this.safetySlack = safetySlack;
        this.clusterSizeHint = clusterSizeHint;
        this.cmsEpsilon = cmsEpsilon;
        this.cmsDelta = cmsDelta;
    }

    public static NodeConfig fromEnvironment() {
        String nodeId = env("NODE_ID", "node-1");
        int gossipPort = Integer.parseInt(env("GOSSIP_PORT", "7946"));
        int httpPort = Integer.parseInt(env("HTTP_PORT", "8080"));

        List<NodeId> peers = new ArrayList<>();
        String peersRaw = env("PEERS", "");
        if (!peersRaw.isEmpty()) {
            for (String entry : peersRaw.split(",")) {
                String[] kv = entry.split("=");
                String id = kv[0].trim();
                String[] hostPort = kv[1].trim().split(":");
                peers.add(new NodeId(id, new InetSocketAddress(hostPort[0], Integer.parseInt(hostPort[1]))));
            }
        }

        Map<String, Double> tenantQuotas = new LinkedHashMap<>();
        String tenantsRaw = env("TENANTS", "demo-tenant=1000");
        for (String entry : tenantsRaw.split(",")) {
            if (entry.trim().isEmpty()) continue;
            String[] kv = entry.split("=");
            tenantQuotas.put(kv[0].trim(), Double.parseDouble(kv[1].trim()));
        }

        long windowMillis = Long.parseLong(env("WINDOW_MILLIS", "5000"));
        double safetySlack = Double.parseDouble(env("SAFETY_SLACK", "0.15"));
        int clusterSizeHint = Integer.parseInt(env("CLUSTER_SIZE_HINT", String.valueOf(Math.max(1, peers.size() + 1))));
        double cmsEpsilon = Double.parseDouble(env("CMS_EPSILON", "0.001"));
        double cmsDelta = Double.parseDouble(env("CMS_DELTA", "0.01"));

        return new NodeConfig(nodeId, gossipPort, httpPort, peers, tenantQuotas, windowMillis,
                safetySlack, clusterSizeHint, cmsEpsilon, cmsDelta);
    }

    private static String env(String key, String def) {
        String v = System.getenv(key);
        return v == null || v.isEmpty() ? def : v;
    }
}
