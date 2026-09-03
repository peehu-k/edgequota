package com.edgequota.gateway;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The HTTP-facing side of a gateway node: a small hand-rolled
 * accept-reactor over {@code java.nio}, in the same spirit as the
 * {@link com.edgequota.gossip.GossipTransport} UDP reactor.
 *
 * Design choice: accepting connections is non-blocking (one {@link Selector}
 * thread), but once a connection is accepted it's handed to a bounded
 * worker pool that reads/parses/responds using ordinary blocking I/O on
 * that connection. Pure end-to-end non-blocking HTTP (manual chunked
 * parsing, partial-read state machines per connection) buys real headroom
 * only at connection counts far beyond what a rate-limiter demo needs to
 * prove out; accept-reactor + blocking-worker is the same pattern used
 * happily in production by plenty of real systems and keeps the HTTP
 * parsing code (see {@link HttpRequestParser}) simple enough to read in one
 * sitting and trust.
 */
public final class GatewayServer implements AutoCloseable {

    private final ServerSocketChannel serverChannel;
    private final Selector selector;
    private final ExecutorService workers;
    private final RateLimitHandler handler;
    private final Thread acceptThread;
    private volatile boolean running = false;

    public GatewayServer(int port, RateLimitHandler handler, int workerThreads) throws IOException {
        this.handler = handler;
        this.serverChannel = ServerSocketChannel.open();
        this.serverChannel.configureBlocking(false);
        this.serverChannel.bind(new InetSocketAddress(port));
        this.selector = Selector.open();
        this.serverChannel.register(selector, SelectionKey.OP_ACCEPT);
        this.workers = Executors.newFixedThreadPool(workerThreads, r -> {
            Thread t = new Thread(r, "edgequota-gateway-worker");
            t.setDaemon(true);
            return t;
        });
        this.acceptThread = new Thread(this::acceptLoop, "edgequota-gateway-accept");
        this.acceptThread.setDaemon(true);
    }

    public int boundPort() throws IOException {
        return ((InetSocketAddress) serverChannel.getLocalAddress()).getPort();
    }

    public void start() {
        running = true;
        acceptThread.start();
    }

    private void acceptLoop() {
        while (running) {
            try {
                selector.select(200);
                if (!selector.isOpen()) {
                    break;
                }
                for (SelectionKey key : selector.selectedKeys()) {
                    if (key.isAcceptable()) {
                        SocketChannel client = serverChannel.accept();
                        if (client != null) {
                            client.configureBlocking(true);
                            workers.submit(() -> serveConnection(client));
                        }
                    }
                }
                selector.selectedKeys().clear();
            } catch (IOException e) {
                if (running) {
                    // transient accept-loop error; keep serving
                }
            }
        }
    }

    private void serveConnection(SocketChannel client) {
        try (SocketChannel c = client) {
            HttpRequest request = HttpRequestParser.parse(java.nio.channels.Channels.newInputStream(c));
            if (request == null) {
                return;
            }
            handler.handle(request, java.nio.channels.Channels.newOutputStream(c));
        } catch (IOException e) {
            // Client disconnects mid-request are routine; nothing to do.
        }
    }

    @Override
    public void close() {
        running = false;
        selector.wakeup();
        try {
            acceptThread.join(1000);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
        workers.shutdownNow();
        try {
            serverChannel.close();
        } catch (IOException ignored) {
        }
        try {
            selector.close();
        } catch (IOException ignored) {
        }
    }
}
