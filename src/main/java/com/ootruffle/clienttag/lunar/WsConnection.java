package com.ootruffle.clienttag.lunar;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Thin wrapper over the JDK's built-in WebSocket client that reassembles binary frames
 * into whole messages. Until a handler is set, messages are queued so they can be read
 * synchronously with {@link #nextMessage(long)} (used by the authenticator handshake).
 */
final class WsConnection implements WebSocket.Listener {

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    /** Queued in place of a message once the socket closes, compared by identity. */
    private static final byte[] CLOSED = new byte[0];

    private final ByteArrayOutputStream partial = new ByteArrayOutputStream();
    private final BlockingQueue<byte[]> inbox = new LinkedBlockingQueue<>();
    private final CompletableFuture<String> closed = new CompletableFuture<>();
    private Consumer<byte[]> handler;
    private CompletableFuture<WebSocket> sendChain;
    private WebSocket ws;

    private WsConnection() {}

    static WsConnection open(String url, Map<String, String> headers) throws IOException {
        final WsConnection conn = new WsConnection();
        final WebSocket.Builder builder = HTTP.newWebSocketBuilder().connectTimeout(Duration.ofSeconds(10));
        headers.forEach(builder::header);
        try {
            conn.ws = builder.buildAsync(URI.create(url), conn).get(15, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new IOException("couldn't open " + url + ": " + e, e);
        }
        conn.sendChain = CompletableFuture.completedFuture(conn.ws);
        return conn;
    }

    /** JDK WebSockets reject overlapping sends, so each send waits for the previous one. */
    synchronized void send(byte[] message) {
        sendChain = sendChain.thenCompose(w -> w.sendBinary(ByteBuffer.wrap(message), true));
    }

    byte[] nextMessage(long timeoutMs) throws IOException, InterruptedException {
        final byte[] msg = inbox.poll(timeoutMs, TimeUnit.MILLISECONDS);
        if (msg == null) {
            throw new IOException("timed out waiting for a message");
        }
        if (msg == CLOSED) {
            throw new IOException("socket closed: " + closed.getNow("unknown"));
        }
        return msg;
    }

    synchronized void setHandler(Consumer<byte[]> handler) {
        this.handler = handler;
        byte[] msg;
        while ((msg = inbox.poll()) != null) {
            if (msg != CLOSED) {
                handler.accept(msg);
            }
        }
    }

    void onClosed(Consumer<String> callback) {
        closed.thenAccept(callback);
    }

    boolean isOpen() {
        return !closed.isDone();
    }

    void close() {
        if (ws != null && isOpen()) {
            ws.sendClose(WebSocket.NORMAL_CLOSURE, "").whenComplete((w, e) -> ws.abort());
        }
        markClosed("closed locally");
    }

    private synchronized void deliver(byte[] message) {
        if (handler != null) {
            handler.accept(message);
        } else {
            inbox.add(message);
        }
    }

    private void markClosed(String reason) {
        if (closed.complete(reason)) {
            inbox.add(CLOSED);
        }
    }

    @Override
    public CompletionStage<?> onBinary(WebSocket webSocket, ByteBuffer data, boolean last) {
        final byte[] chunk = new byte[data.remaining()];
        data.get(chunk);
        partial.write(chunk, 0, chunk.length);
        if (last) {
            final byte[] message = partial.toByteArray();
            partial.reset();
            deliver(message);
        }
        webSocket.request(1);
        return null;
    }

    @Override
    public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
        webSocket.request(1);
        return null;
    }

    @Override
    public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
        markClosed(statusCode + " " + reason);
        return null;
    }

    @Override
    public void onError(WebSocket webSocket, Throwable error) {
        markClosed(String.valueOf(error));
    }

}
