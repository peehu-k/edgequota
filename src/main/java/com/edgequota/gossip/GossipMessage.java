package com.edgequota.gossip;

/**
 * A decoded gossip protocol message. Not every field is meaningful for
 * every {@link MessageType}; see {@link GossipCodec} for exactly which
 * fields each message type reads/writes on the wire.
 */
public final class GossipMessage {
    public MessageType type;

    public String senderId;
    public String senderHost;
    public int senderPort;
    public long senderIncarnation;

    // PING / ACK correlation
    public long sequenceNumber;

    // PING_REQ: who the intermediary should probe on the sender's behalf
    public String indirectTargetId;
    public String indirectTargetHost;
    public int indirectTargetPort;

    // MEMBER_RUMOR
    public String rumorNodeId;
    public String rumorHost;
    public int rumorPort;
    public PeerStatus rumorStatus;
    public long rumorIncarnation;

    // SKETCH_DELTA
    public SketchDelta delta;

    public static GossipMessage ping(String senderId, String host, int port, long incarnation, long seq) {
        GossipMessage m = new GossipMessage();
        m.type = MessageType.PING;
        m.senderId = senderId;
        m.senderHost = host;
        m.senderPort = port;
        m.senderIncarnation = incarnation;
        m.sequenceNumber = seq;
        return m;
    }

    public static GossipMessage ack(String senderId, String host, int port, long incarnation, long seq) {
        GossipMessage m = new GossipMessage();
        m.type = MessageType.ACK;
        m.senderId = senderId;
        m.senderHost = host;
        m.senderPort = port;
        m.senderIncarnation = incarnation;
        m.sequenceNumber = seq;
        return m;
    }

    public static GossipMessage pingReq(String senderId, String host, int port, long incarnation, long seq,
                                         String targetId, String targetHost, int targetPort) {
        GossipMessage m = new GossipMessage();
        m.type = MessageType.PING_REQ;
        m.senderId = senderId;
        m.senderHost = host;
        m.senderPort = port;
        m.senderIncarnation = incarnation;
        m.sequenceNumber = seq;
        m.indirectTargetId = targetId;
        m.indirectTargetHost = targetHost;
        m.indirectTargetPort = targetPort;
        return m;
    }

    public static GossipMessage rumor(String senderId, String host, int port, long incarnation,
                                       String rumorNodeId, String rumorHost, int rumorPort,
                                       PeerStatus status, long rumorIncarnation) {
        GossipMessage m = new GossipMessage();
        m.type = MessageType.MEMBER_RUMOR;
        m.senderId = senderId;
        m.senderHost = host;
        m.senderPort = port;
        m.senderIncarnation = incarnation;
        m.rumorNodeId = rumorNodeId;
        m.rumorHost = rumorHost;
        m.rumorPort = rumorPort;
        m.rumorStatus = status;
        m.rumorIncarnation = rumorIncarnation;
        return m;
    }

    public static GossipMessage sketchDelta(String senderId, String host, int port, long incarnation, SketchDelta delta) {
        GossipMessage m = new GossipMessage();
        m.type = MessageType.SKETCH_DELTA;
        m.senderId = senderId;
        m.senderHost = host;
        m.senderPort = port;
        m.senderIncarnation = incarnation;
        m.delta = delta;
        return m;
    }
}
