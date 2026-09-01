package com.edgequota.gossip;

import com.edgequota.quota.ClusterCostView;
import com.edgequota.sketch.CountMinSketch;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiConsumer;

/**
 * One cluster member. Owns:
 *  - a SWIM-inspired failure detector (ping / indirect-ping-req / ack,
 *    suspicion timeout) that drives {@link MembershipTable};
 *  - a rumor-mongering epidemic disseminator for both membership rumors and
 *    {@link SketchDelta}s, which is what gives EdgeQuota cluster-wide quota
 *    convergence in O(log n) gossip rounds (see docs/DESIGN.md for the
 *    derivation and docs/BENCHMARKS.md for the measured round counts);
 *  - the merged {@code globalAccumulator} sketch this node currently
 *    believes represents cluster-wide per-tenant cost, which
 *    {@link com.edgequota.quota.QuotaManager} reads to make admit/reject
 *    decisions.
 *
 * All mutable state is only ever touched from the single-threaded
 * `nodeExecutor`, so nothing here needs its own locks even though messages
 * arrive concurrently on the transport's I/O thread.
 */
public final class GossipNode implements AutoCloseable, ClusterCostView {

    public static final long PROTOCOL_PERIOD_MILLIS = 200;
    public static final long PING_TIMEOUT_MILLIS = 150;
    public static final long INDIRECT_TIMEOUT_MILLIS = 300;
    public static final long SUSPICION_TIMEOUT_MILLIS = 1000;

    private final NodeId self;
    private final long cmsWidth;
    private final long cmsDepth;
    private final long cmsSeed;
    private final int cmsWidthInt;
    private final int cmsDepthInt;

    private final MembershipTable membership = new MembershipTable();
    private final GossipTransport transport;
    private final ScheduledExecutorService nodeExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "edgequota-gossip-node");
        t.setDaemon(true);
        return t;
    });

    private final AtomicLong incarnation = new AtomicLong(1);
    private final AtomicLong seqGen = new AtomicLong(1);
    private final AtomicLong localEpoch = new AtomicLong(0);

    private CountMinSketch localContribution;
    private CountMinSketch lastGossipedSnapshot;
    private final CountMinSketch globalAccumulator;

    private final Map<String, Long> lastAppliedEpoch = new ConcurrentHashMap<>();

    private final Map<Long, PendingPing> pendingPings = new ConcurrentHashMap<>();
    private final Map<Long, ProxyPing> proxyPings = new ConcurrentHashMap<>();

    private final Deque<RelayItem> relayQueue = new ArrayDeque<>();

    /** Optional observer hook, mainly used by the simulation/benchmark harness to measure convergence. */
    private volatile BiConsumer<String, Long> onDeltaApplied = (origin, epoch) -> {
    };

    private static final class PendingPing {
        final NodeId target;
        final long deadline;
        boolean escalated;

        PendingPing(NodeId target, long deadline) {
            this.target = target;
            this.deadline = deadline;
        }
    }

    private static final class ProxyPing {
        final String requesterId;
        final String requesterHost;
        final int requesterPort;
        final long requesterSeq;

        ProxyPing(String requesterId, String requesterHost, int requesterPort, long requesterSeq) {
            this.requesterId = requesterId;
            this.requesterHost = requesterHost;
            this.requesterPort = requesterPort;
            this.requesterSeq = requesterSeq;
        }
    }

    private static final class RelayItem {
        final GossipMessage message;
        int hopsRemaining;

        RelayItem(GossipMessage message, int hopsRemaining) {
            this.message = message;
            this.hopsRemaining = hopsRemaining;
        }
    }

    public GossipNode(NodeId self, int port, double epsilon, double delta, long cmsSeed) throws IOException {
        this.self = self;
        this.cmsSeed = cmsSeed;
        this.localContribution = new CountMinSketch(epsilon, delta, cmsSeed);
        this.cmsWidthInt = localContribution.width();
        this.cmsDepthInt = localContribution.depth();
        this.cmsWidth = cmsWidthInt;
        this.cmsDepth = cmsDepthInt;
        this.lastGossipedSnapshot = localContribution.copy();
        this.globalAccumulator = new CountMinSketch(cmsWidthInt, cmsDepthInt, cmsSeed);
        this.transport = new GossipTransport(port, this::onWireMessage);
        membership.upsert(self, PeerStatus.ALIVE, incarnation.get());
    }

    public NodeId self() {
        return self;
    }

    public MembershipTable membership() {
        return membership;
    }

    public void setOnDeltaApplied(BiConsumer<String, Long> listener) {
        this.onDeltaApplied = listener;
    }

    /** Seeds the initial peer list (bootstrap contacts). */
    public void seedPeers(List<NodeId> peers) {
        for (NodeId p : peers) {
            if (!p.id().equals(self.id())) {
                membership.upsert(p, PeerStatus.ALIVE, 0);
            }
        }
    }

    public void start() {
        transport.start();
        nodeExecutor.scheduleAtFixedRate(this::tick, 0, PROTOCOL_PERIOD_MILLIS, TimeUnit.MILLISECONDS);
    }

    /** Records `weight` units of cost for `tenantResourceKey` on this node, e.g. "tenant42:tokens". */
    @Override
    public void recordCost(String tenantResourceKey, double weight) {
        nodeExecutor.execute(() -> localContribution.update(tenantResourceKey, weight));
    }

    /** This node's current best estimate of cluster-wide cost for the given key. */
    @Override
    public double globalEstimate(String tenantResourceKey) {
        return globalAccumulator.estimate(tenantResourceKey);
    }

    /** This node's own local (not merged) estimate -- used as the "localRate" input to quota adjustment. */
    @Override
    public double localEstimate(String tenantResourceKey) {
        return localContribution.estimate(tenantResourceKey);
    }

    public double errorBound(double epsilon) {
        return globalAccumulator.errorBound(epsilon);
    }

    // ---- protocol tick: failure detection + gossip dissemination ----

    private void tick() {
        try {
            checkPingTimeouts();
            checkSuspicionTimeouts();
            probeRandomPeer();
            generateLocalDeltaIfAny();
            flushRelayQueue();
        } catch (RuntimeException e) {
            // A single bad tick should never kill the scheduler loop.
        }
    }

    private void probeRandomPeer() {
        NodeId target = membership.randomPeer(self.id());
        if (target == null) {
            return;
        }
        long seq = seqGen.getAndIncrement();
        pendingPings.put(seq, new PendingPing(target, System.currentTimeMillis() + PING_TIMEOUT_MILLIS));
        sendTo(target.address(), GossipMessage.ping(self.id(), self.address().getHostString(), self.address().getPort(), incarnation.get(), seq));
    }

    private void checkPingTimeouts() {
        long now = System.currentTimeMillis();
        for (Map.Entry<Long, PendingPing> e : pendingPings.entrySet()) {
            PendingPing pp = e.getValue();
            if (now < pp.deadline) {
                continue;
            }
            if (!pp.escalated) {
                pp.escalated = true;
                List<NodeId> helpers = membership.randomFanout(self.id(), membership.recommendedFanout());
                for (NodeId helper : helpers) {
                    if (helper.id().equals(pp.target.id())) {
                        continue;
                    }
                    sendTo(helper.address(), GossipMessage.pingReq(
                            self.id(), self.address().getHostString(), self.address().getPort(), incarnation.get(),
                            e.getKey(), pp.target.id(), pp.target.address().getHostString(), pp.target.address().getPort()));
                }
                PendingPing refreshed = new PendingPing(pp.target, now + INDIRECT_TIMEOUT_MILLIS);
                refreshed.escalated = true;
                pendingPings.put(e.getKey(), refreshed);
            } else {
                pendingPings.remove(e.getKey());
                MembershipEntry entry = membership.get(pp.target.id());
                long inc = entry != null ? entry.incarnation : 0;
                boolean changed = membership.upsert(pp.target, PeerStatus.SUSPECT, inc);
                if (changed) {
                    enqueueRumor(pp.target, PeerStatus.SUSPECT, inc);
                }
            }
        }
    }

    private void checkSuspicionTimeouts() {
        long now = System.currentTimeMillis();
        for (MembershipEntry entry : membership.all()) {
            if (entry.status == PeerStatus.SUSPECT && now - entry.suspectSinceMillis > SUSPICION_TIMEOUT_MILLIS) {
                boolean changed = membership.upsert(entry.node, PeerStatus.DEAD, entry.incarnation);
                if (changed) {
                    enqueueRumor(entry.node, PeerStatus.DEAD, entry.incarnation);
                }
            }
        }
    }

    private void generateLocalDeltaIfAny() {
        CountMinSketch current = localContribution;
        CountMinSketch diff = current.diff(lastGossipedSnapshot);
        if (Math.abs(diff.totalWeight()) < 1e-9) {
            return;
        }
        long epoch = localEpoch.incrementAndGet();
        SketchDelta delta = new SketchDelta(self.id(), epoch, diff.exportTable(), diff.totalWeight());
        globalAccumulator.applyDelta(delta.cells, delta.totalWeightDelta);
        lastAppliedEpoch.put(self.id(), epoch);
        lastGossipedSnapshot = current.copy();
        onDeltaApplied.accept(self.id(), epoch);
        GossipMessage msg = GossipMessage.sketchDelta(self.id(), self.address().getHostString(), self.address().getPort(), incarnation.get(), delta);
        relayQueue.addLast(new RelayItem(msg, membership.recommendedFanout()));
    }

    private void enqueueRumor(NodeId node, PeerStatus status, long inc) {
        GossipMessage msg = GossipMessage.rumor(self.id(), self.address().getHostString(), self.address().getPort(), incarnation.get(),
                node.id(), node.address().getHostString(), node.address().getPort(), status, inc);
        relayQueue.addLast(new RelayItem(msg, membership.recommendedFanout()));
    }

    private void flushRelayQueue() {
        int fanout = membership.recommendedFanout();
        int batch = relayQueue.size();
        for (int i = 0; i < batch; i++) {
            RelayItem item = relayQueue.pollFirst();
            if (item == null) {
                break;
            }
            List<NodeId> targets = membership.randomFanout(self.id(), fanout);
            for (NodeId t : targets) {
                sendTo(t.address(), item.message);
            }
            item.hopsRemaining--;
            if (item.hopsRemaining > 0) {
                relayQueue.addLast(item);
            }
        }
    }

    // ---- inbound message handling (marshalled onto nodeExecutor) ----

    private void onWireMessage(GossipTransport.Received received) {
        nodeExecutor.execute(() -> handle(received));
    }

    private void handle(GossipTransport.Received received) {
        GossipMessage m = received.message;
        NodeId sender = new NodeId(m.senderId, new InetSocketAddress(m.senderHost, m.senderPort));
        membership.upsert(sender, PeerStatus.ALIVE, m.senderIncarnation);

        switch (m.type) {
            case PING:
                sendTo(received.from, GossipMessage.ack(self.id(), self.address().getHostString(), self.address().getPort(), incarnation.get(), m.sequenceNumber));
                break;

            case ACK: {
                PendingPing pp = pendingPings.remove(m.sequenceNumber);
                if (pp != null) {
                    MembershipEntry entry = membership.get(pp.target.id());
                    long inc = entry != null ? entry.incarnation : 0;
                    membership.upsert(pp.target, PeerStatus.ALIVE, inc);
                }
                break;
            }

            case PING_REQ: {
                NodeId target = new NodeId(m.indirectTargetId, new InetSocketAddress(m.indirectTargetHost, m.indirectTargetPort));
                long proxySeq = seqGen.getAndIncrement();
                proxyPings.put(proxySeq, new ProxyPing(m.senderId, m.senderHost, m.senderPort, m.sequenceNumber));
                sendTo(target.address(), GossipMessage.ping(self.id(), self.address().getHostString(), self.address().getPort(), incarnation.get(), proxySeq));
                break;
            }

            case MEMBER_RUMOR: {
                NodeId rumorNode = new NodeId(m.rumorNodeId, new InetSocketAddress(m.rumorHost, m.rumorPort));
                boolean changed = membership.upsert(rumorNode, m.rumorStatus, m.rumorIncarnation);
                if (changed) {
                    relayQueue.addLast(new RelayItem(m, membership.recommendedFanout()));
                }
                break;
            }

            case SKETCH_DELTA: {
                SketchDelta delta = m.delta;
                long already = lastAppliedEpoch.getOrDefault(delta.originNodeId, 0L);
                if (delta.epoch > already) {
                    delta.applyTo(globalAccumulator);
                    lastAppliedEpoch.put(delta.originNodeId, delta.epoch);
                    onDeltaApplied.accept(delta.originNodeId, delta.epoch);
                    relayQueue.addLast(new RelayItem(m, membership.recommendedFanout()));
                }
                break;
            }
            default:
                break;
        }

        // Resolve any proxied indirect ping this ACK completes.
        if (m.type == MessageType.ACK) {
            ProxyPing proxy = proxyPings.remove(m.sequenceNumber);
            if (proxy != null) {
                sendTo(new InetSocketAddress(proxy.requesterHost, proxy.requesterPort),
                        GossipMessage.ack(self.id(), self.address().getHostString(), self.address().getPort(), incarnation.get(), proxy.requesterSeq));
            }
        }
    }

    private void sendTo(java.net.SocketAddress address, GossipMessage message) {
        transport.send(GossipCodec.encode(message), address);
    }

    @Override
    public void close() {
        nodeExecutor.shutdownNow();
        transport.close();
    }
}
