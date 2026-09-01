package com.edgequota.gossip;

import java.net.InetSocketAddress;
import java.util.Objects;

/** Identity of a gateway node in the cluster: a stable id plus its gossip (UDP) address. */
public final class NodeId {
    private final String id;
    private final InetSocketAddress address;

    public NodeId(String id, InetSocketAddress address) {
        this.id = Objects.requireNonNull(id);
        this.address = Objects.requireNonNull(address);
    }

    public String id() {
        return id;
    }

    public InetSocketAddress address() {
        return address;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof NodeId)) return false;
        NodeId nodeId = (NodeId) o;
        return id.equals(nodeId.id);
    }

    @Override
    public int hashCode() {
        return id.hashCode();
    }

    @Override
    public String toString() {
        return id + "@" + address.getHostString() + ":" + address.getPort();
    }
}
