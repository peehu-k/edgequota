package com.edgequota.gossip;

/** Wire message kinds exchanged over the UDP gossip transport. */
public enum MessageType {
    PING((byte) 1),
    ACK((byte) 2),
    PING_REQ((byte) 3),
    SKETCH_DELTA((byte) 4),
    MEMBER_RUMOR((byte) 5);

    public final byte code;

    MessageType(byte code) {
        this.code = code;
    }

    public static MessageType fromCode(byte code) {
        for (MessageType t : values()) {
            if (t.code == code) {
                return t;
            }
        }
        throw new IllegalArgumentException("unknown gossip message type code: " + code);
    }
}
