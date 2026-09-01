package com.edgequota.gossip;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;

/**
 * Hand-rolled binary wire format for {@link GossipMessage}. No Protobuf/
 * Avro/Kryo: gossip datagrams need to stay small (they ride on UDP, which
 * on most networks means keeping well under the ~1200-byte practical MTU
 * ceiling to avoid IP fragmentation), and a plain DataOutputStream framing
 * gives full control over exactly how many bytes each field costs without
 * a schema-registry dependency for a project this size.
 *
 * Layout: [1 byte type][UTF senderId][UTF senderHost][int senderPort]
 * [long senderIncarnation][type-specific fields...]
 */
public final class GossipCodec {

    private GossipCodec() {
    }

    public static byte[] encode(GossipMessage m) {
        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(baos);
            out.writeByte(m.type.code);
            out.writeUTF(m.senderId);
            out.writeUTF(m.senderHost);
            out.writeInt(m.senderPort);
            out.writeLong(m.senderIncarnation);

            switch (m.type) {
                case PING:
                case ACK:
                    out.writeLong(m.sequenceNumber);
                    break;
                case PING_REQ:
                    out.writeLong(m.sequenceNumber);
                    out.writeUTF(m.indirectTargetId);
                    out.writeUTF(m.indirectTargetHost);
                    out.writeInt(m.indirectTargetPort);
                    break;
                case MEMBER_RUMOR:
                    out.writeUTF(m.rumorNodeId);
                    out.writeUTF(m.rumorHost);
                    out.writeInt(m.rumorPort);
                    out.writeByte(statusCode(m.rumorStatus));
                    out.writeLong(m.rumorIncarnation);
                    break;
                case SKETCH_DELTA:
                    out.writeUTF(m.delta.originNodeId);
                    out.writeLong(m.delta.epoch);
                    out.writeDouble(m.delta.totalWeightDelta);
                    int depth = m.delta.cells.length;
                    int width = depth == 0 ? 0 : m.delta.cells[0].length;
                    out.writeInt(depth);
                    out.writeInt(width);
                    for (int row = 0; row < depth; row++) {
                        for (int col = 0; col < width; col++) {
                            out.writeDouble(m.delta.cells[row][col]);
                        }
                    }
                    break;
                default:
                    throw new IllegalStateException("unhandled type " + m.type);
            }
            out.flush();
            return baos.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static GossipMessage decode(byte[] bytes, int length) {
        try {
            DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes, 0, length));
            GossipMessage m = new GossipMessage();
            m.type = MessageType.fromCode(in.readByte());
            m.senderId = in.readUTF();
            m.senderHost = in.readUTF();
            m.senderPort = in.readInt();
            m.senderIncarnation = in.readLong();

            switch (m.type) {
                case PING:
                case ACK:
                    m.sequenceNumber = in.readLong();
                    break;
                case PING_REQ:
                    m.sequenceNumber = in.readLong();
                    m.indirectTargetId = in.readUTF();
                    m.indirectTargetHost = in.readUTF();
                    m.indirectTargetPort = in.readInt();
                    break;
                case MEMBER_RUMOR:
                    m.rumorNodeId = in.readUTF();
                    m.rumorHost = in.readUTF();
                    m.rumorPort = in.readInt();
                    m.rumorStatus = statusFromCode(in.readByte());
                    m.rumorIncarnation = in.readLong();
                    break;
                case SKETCH_DELTA: {
                    String originNodeId = in.readUTF();
                    long epoch = in.readLong();
                    double totalWeightDelta = in.readDouble();
                    int depth = in.readInt();
                    int width = in.readInt();
                    double[][] cells = new double[depth][width];
                    for (int row = 0; row < depth; row++) {
                        for (int col = 0; col < width; col++) {
                            cells[row][col] = in.readDouble();
                        }
                    }
                    m.delta = new SketchDelta(originNodeId, epoch, cells, totalWeightDelta);
                    break;
                }
                default:
                    throw new IllegalStateException("unhandled type " + m.type);
            }
            return m;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static byte statusCode(PeerStatus s) {
        switch (s) {
            case ALIVE: return 0;
            case SUSPECT: return 1;
            case DEAD: return 2;
            default: throw new IllegalStateException();
        }
    }

    private static PeerStatus statusFromCode(byte b) {
        switch (b) {
            case 0: return PeerStatus.ALIVE;
            case 1: return PeerStatus.SUSPECT;
            case 2: return PeerStatus.DEAD;
            default: throw new IllegalArgumentException("bad status code " + b);
        }
    }
}
