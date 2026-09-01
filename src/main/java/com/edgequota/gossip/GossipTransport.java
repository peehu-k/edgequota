package com.edgequota.gossip;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.DatagramChannel;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.util.ArrayDeque;
import java.util.Queue;
import java.util.function.Consumer;

/**
 * Minimal non-blocking UDP reactor: one {@link Selector}, one
 * {@link DatagramChannel}, one dedicated event-loop thread. This plays the
 * same architectural role a Netty {@code EventLoopGroup} + {@code Bootstrap}
 * would for a UDP transport, just written by hand against raw {@code
 * java.nio} so the whole gossip stack has zero external dependencies and
 * every byte on the wire is something we chose to put there (see
 * {@link GossipCodec}).
 *
 * Threading model: all sends are queued from arbitrary caller threads and
 * flushed from the single reactor thread, so callers never touch the
 * channel directly and no additional locking is needed around the socket
 * itself.
 */
public final class GossipTransport implements AutoCloseable {

    private static final int MAX_DATAGRAM_SIZE = 8192;

    private final DatagramChannel channel;
    private final Selector selector;
    private final Thread loopThread;
    private final Queue<Outbound> sendQueue = new java.util.concurrent.ConcurrentLinkedQueue<>();
    private volatile boolean running = false;
    private final Consumer<Received> onMessage;

    private static final class Outbound {
        final byte[] payload;
        final SocketAddress destination;

        Outbound(byte[] payload, SocketAddress destination) {
            this.payload = payload;
            this.destination = destination;
        }
    }

    public static final class Received {
        public final GossipMessage message;
        public final InetSocketAddress from;

        Received(GossipMessage message, InetSocketAddress from) {
            this.message = message;
            this.from = from;
        }
    }

    public GossipTransport(int port, Consumer<Received> onMessage) throws IOException {
        this.onMessage = onMessage;
        this.channel = DatagramChannel.open();
        this.channel.configureBlocking(false);
        this.channel.bind(new InetSocketAddress(port));
        this.selector = Selector.open();
        this.channel.register(selector, SelectionKey.OP_READ);
        this.loopThread = new Thread(this::runLoop, "edgequota-gossip-io");
        this.loopThread.setDaemon(true);
    }

    public int boundPort() throws IOException {
        return ((InetSocketAddress) channel.getLocalAddress()).getPort();
    }

    public void start() {
        running = true;
        loopThread.start();
    }

    public void send(byte[] payload, SocketAddress destination) {
        sendQueue.add(new Outbound(payload, destination));
        selector.wakeup();
    }

    private void runLoop() {
        ByteBuffer readBuffer = ByteBuffer.allocate(MAX_DATAGRAM_SIZE);
        while (running) {
            try {
                selector.select(100);
                flushSendQueue();
                if (!selector.isOpen()) {
                    break;
                }
                for (SelectionKey key : selector.selectedKeys()) {
                    if (key.isReadable()) {
                        readBuffer.clear();
                        InetSocketAddress from = (InetSocketAddress) channel.receive(readBuffer);
                        if (from != null) {
                            readBuffer.flip();
                            int len = readBuffer.remaining();
                            byte[] data = new byte[len];
                            readBuffer.get(data);
                            try {
                                GossipMessage msg = GossipCodec.decode(data, len);
                                onMessage.accept(new Received(msg, from));
                            } catch (RuntimeException decodeError) {
                                // Malformed/foreign datagram -- drop and keep serving the cluster.
                            }
                        }
                    }
                }
                selector.selectedKeys().clear();
            } catch (IOException e) {
                if (running) {
                    // Transient I/O hiccup; keep the reactor alive rather than tearing down the node.
                }
            }
        }
    }

    private void flushSendQueue() {
        Outbound out;
        while ((out = sendQueue.poll()) != null) {
            try {
                channel.send(ByteBuffer.wrap(out.payload), out.destination);
            } catch (IOException e) {
                // Best-effort UDP send; failures are expected under fault injection
                // (dropped/partitioned peers) and are exactly what the failure
                // detector and gossip retry logic are designed to tolerate.
            }
        }
    }

    @Override
    public void close() {
        running = false;
        selector.wakeup();
        try {
            loopThread.join(1000);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
        try {
            channel.close();
        } catch (IOException ignored) {
        }
        try {
            selector.close();
        } catch (IOException ignored) {
        }
    }
}
