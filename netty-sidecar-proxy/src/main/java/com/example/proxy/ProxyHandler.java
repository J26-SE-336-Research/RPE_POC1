package com.example.proxy;

import io.netty.bootstrap.Bootstrap;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelOption;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.Channel;
import io.netty.handler.codec.http.*;
import java.util.UUID;
import java.net.InetSocketAddress;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

public final class ProxyHandler extends SimpleChannelInboundHandler<FullHttpRequest> {
    private static final String DEADLINE = "deadlinevalue";
    private static final String EXCEEDED = "deadlineExceded";
    private static final String CANCELLATION = "cancellation_Triggered";
    private static final String REQUEST_ID = "Request_id";
    private static final ConcurrentHashMap<String, HttpHeaders> REQUEST_CONTEXTS = new ConcurrentHashMap<>();
    private final ProxyConfig config;

    static void forgetRequest(String requestId) {
        REQUEST_CONTEXTS.remove(requestId);
    }

    static HttpHeaders requestContext(String requestId) {
        HttpHeaders headers = REQUEST_CONTEXTS.get(requestId);
        return headers == null ? null : headers.copy();
    }

    private static void markDeadlineExceeded(String requestId) {
        REQUEST_CONTEXTS.computeIfPresent(requestId, (id, existing) -> {
            HttpHeaders updated = existing.copy();
            updated.set(EXCEEDED, "true");
            updated.set(CANCELLATION, "true");
            return updated;
        });
    }

    static void markCancellation(String requestId) {
        REQUEST_CONTEXTS.computeIfPresent(requestId, (id, existing) -> {
            HttpHeaders updated = existing.copy();
            updated.set(EXCEEDED, "true");
            updated.set(CANCELLATION, "true");
            return updated;
        });
    }

    public ProxyHandler(ProxyConfig config) {
        this.config = config;
    }

    @Override
    protected void channelRead0(ChannelHandlerContext clientCtx, FullHttpRequest request) {
        String uri = request.uri();
        if (!(clientCtx.channel().localAddress() instanceof InetSocketAddress destination)
                || destination.isUnresolved()) {
            request.release();
            sendError(clientCtx, HttpResponseStatus.BAD_GATEWAY,
                    "Could not determine original TPROXY destination");
            return;
        }

        String requestId = request.headers().get(REQUEST_ID);
        boolean suppliedRequestId = requestId != null && !requestId.isBlank();
        if (requestId == null || requestId.isBlank()) requestId = UUID.randomUUID().toString();
        HttpHeaders storedContext = REQUEST_CONTEXTS.get(requestId);
        boolean hasChainMetadata = request.headers().contains(DEADLINE)
                || request.headers().contains(EXCEEDED) || request.headers().contains(CANCELLATION);
        // A request carrying a full context starts/continues the inbound side
        // of this process. A request carrying only Request_id is an outbound
        // hop: restore its metadata from the inbound request's stored context.
        boolean ownsContext = !suppliedRequestId || hasChainMetadata || storedContext == null;
        String deadlineValue = request.headers().get(DEADLINE);
        if (storedContext != null && !hasChainMetadata) deadlineValue = storedContext.get(DEADLINE);
        long deadline;
        try {
            deadline = deadlineValue == null ? System.currentTimeMillis() + config.defaultDeadlineMillis()
                    : Long.parseLong(deadlineValue);
        } catch (NumberFormatException ignored) {
            deadline = System.currentTimeMillis() + config.defaultDeadlineMillis();
        }
        boolean expired = storedContext != null && !hasChainMetadata
                ? Boolean.parseBoolean(storedContext.get(EXCEEDED))
                : (config.detectInboundDeadline() && deadline <= System.currentTimeMillis())
                    || Boolean.parseBoolean(request.headers().get(EXCEEDED));
        HttpHeaders contextHeaders = new DefaultHttpHeaders();
        contextHeaders.set(REQUEST_ID, requestId);
        contextHeaders.set(DEADLINE, Long.toString(deadline));
        contextHeaders.set(EXCEEDED, Boolean.toString(expired));
        boolean cancellation = expired || (storedContext != null && !hasChainMetadata
                ? Boolean.parseBoolean(storedContext.get(CANCELLATION))
                : Boolean.parseBoolean(request.headers().get(CANCELLATION)));
        contextHeaders.set(CANCELLATION, Boolean.toString(cancellation));
        if (ownsContext) REQUEST_CONTEXTS.put(requestId, contextHeaders.copy());
        final String activeRequestId = requestId;
        final long activeDeadline = deadline;
        if (ownsContext && config.detectInboundDeadline()) {
            long delayMillis = Math.max(0, deadline - System.currentTimeMillis());
            clientCtx.executor().schedule(
                    () -> markDeadlineExceeded(activeRequestId), delayMillis, TimeUnit.MILLISECONDS);
        }

        FullHttpRequest forwarded = new DefaultFullHttpRequest(
                request.protocolVersion(),
                request.method(),
                uri,
                request.content().retainedDuplicate(),
                request.headers().copy(),
                request.trailingHeaders().copy()
        );

        // Example proxy-side modification.
        forwarded.headers().set("X-My-Proxy", "Netty-Sidecar-Proxy");
        forwarded.headers().set(REQUEST_ID, requestId);
        forwarded.headers().set(DEADLINE, Long.toString(deadline));
        forwarded.headers().set(EXCEEDED, Boolean.toString(expired));
        forwarded.headers().set(CANCELLATION, contextHeaders.get(CANCELLATION));
        // A matching request id lets calls passing through this proxy reuse the
        // original chain's metadata instead of creating a new deadline.
        if (storedContext != null) {
            storedContext.forEach(entry -> forwarded.headers().set(entry.getKey(), entry.getValue()));
        }
        contextHeaders.forEach(entry -> forwarded.headers().set(entry.getKey(), entry.getValue()));

        // The client connection owns the request content, so release the original
        // after retaining/copying what the backend request needs.
        request.release();

        Bootstrap backendBootstrap = new Bootstrap();
        backendBootstrap.group(clientCtx.channel().eventLoop())
                .channel(clientCtx.channel().getClass())
                .handler(new BackendInitializer(clientCtx.channel(), activeRequestId, activeDeadline,
                        config.detectInboundDeadline(), ownsContext))
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 5000);

        backendBootstrap.connect(destination)
                .addListener((ChannelFutureListener) future -> {
                    if (!future.isSuccess()) {
                        if (ownsContext) forgetRequest(activeRequestId);
                        sendError(clientCtx, HttpResponseStatus.BAD_GATEWAY,
                                "Could not connect to backend: " + future.cause().getMessage());
                        return;
                    }

                    Channel backendChannel = future.channel();
                    backendChannel.writeAndFlush(forwarded).addListener(writeFuture -> {
                        if (!writeFuture.isSuccess()) {
                            if (ownsContext) forgetRequest(activeRequestId);
                            backendChannel.close();
                            sendError(clientCtx, HttpResponseStatus.BAD_GATEWAY,
                                    "Could not send request to backend");
                        }
                    });
                });
    }

    private static void sendError(ChannelHandlerContext ctx, HttpResponseStatus status, String message) {
        if (!ctx.channel().isActive()) return;

        FullHttpResponse response = new DefaultFullHttpResponse(
                HttpVersion.HTTP_1_1,
                status,
                ctx.alloc().buffer().writeBytes(message.getBytes(java.nio.charset.StandardCharsets.UTF_8))
        );
        response.headers().set(HttpHeaderNames.CONTENT_TYPE, "text/plain; charset=UTF-8");
        response.headers().setInt(HttpHeaderNames.CONTENT_LENGTH, response.content().readableBytes());
        response.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.CLOSE);

        ctx.writeAndFlush(response).addListener(ChannelFutureListener.CLOSE);
    }
}
