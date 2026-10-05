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
import java.util.logging.Logger;

import java.net.NetworkInterface;
import java.net.SocketException;
import java.net.InetAddress;
import java.util.Enumeration;

public final class ProxyHandler extends SimpleChannelInboundHandler<FullHttpRequest> {
    private static final Logger LOGGER = Logger.getLogger(ProxyHandler.class.getName());
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
            boolean alreadyExceeded = Boolean.parseBoolean(existing.get(EXCEEDED));
            HttpHeaders updated = existing.copy();
            updated.set(EXCEEDED, "true");
            updated.set(CANCELLATION, "true");
            if (!alreadyExceeded) {
                LOGGER.info(() -> "Deadline exceeded for Request_id=" + requestId
                        + "; cancellation_Triggered=true");
            }
            return updated;
        });
    }

    static void markResponseStatus(String requestId, boolean deadlineExceeded, boolean cancellationTriggered) {
        REQUEST_CONTEXTS.computeIfPresent(requestId, (id, existing) -> {
            HttpHeaders updated = existing.copy();
            boolean alreadyExceeded = Boolean.parseBoolean(existing.get(EXCEEDED));
            boolean alreadyCancelled = Boolean.parseBoolean(existing.get(CANCELLATION));
            updated.set(EXCEEDED, Boolean.toString(alreadyExceeded || deadlineExceeded));
            updated.set(CANCELLATION, Boolean.toString(alreadyCancelled || cancellationTriggered
                    || deadlineExceeded));
            return updated;
        });
    }

    public ProxyHandler(ProxyConfig config) {
        this.config = config;
    }

    private static void rejectMissingContext(ChannelHandlerContext ctx, String missing) {
        sendError(ctx, HttpResponseStatus.BAD_REQUEST,
                "Missing required propagation header or context: " + missing);
    }

    private static boolean isLocalAddress(InetSocketAddress destination) {
        InetAddress address = destination.getAddress();

        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();

            while (interfaces.hasMoreElements()) {
                NetworkInterface networkInterface = interfaces.nextElement();

                Enumeration<InetAddress> addresses = networkInterface.getInetAddresses();

                while (addresses.hasMoreElements()) {
                    if (addresses.nextElement().equals(address)) {
                        return true;
                    }
                }
            }
        } catch (SocketException e) {
            LOGGER.warning("Could not inspect local network interfaces: " + e.getMessage());
        }

        return address.isLoopbackAddress();
    }

    @Override
    protected void channelRead0(ChannelHandlerContext clientCtx, FullHttpRequest request) {
        String uri = request.uri();

        if (!(clientCtx.channel().localAddress() instanceof InetSocketAddress destination)
                || destination.isUnresolved()) {
            sendError(clientCtx, HttpResponseStatus.BAD_GATEWAY,
                    "Could not determine original TPROXY destination");
            return;
        }

        String requestId = request.headers().get(REQUEST_ID);
        boolean suppliedRequestId = requestId != null && !requestId.isBlank();
        boolean gatewaySidecar = config.gatewaySidecar();
        if (!suppliedRequestId && !gatewaySidecar) {
            rejectMissingContext(clientCtx, REQUEST_ID);
            return;
        }
        if (!suppliedRequestId) requestId = UUID.randomUUID().toString();
        HttpHeaders storedContext = REQUEST_CONTEXTS.get(requestId);
        boolean detectThisRequestDeadline = config.detectInboundDeadline() && !gatewaySidecar;
        boolean requestIdOnly = suppliedRequestId
                && !request.headers().contains(DEADLINE)
                && !request.headers().contains(EXCEEDED)
                && !request.headers().contains(CANCELLATION);

        // Outbound service calls carry only Request_id. Their sidecar must find
        // the full chain context saved from that service's inbound request.
        if (requestIdOnly && storedContext == null && !gatewaySidecar) {
            rejectMissingContext(clientCtx, "stored context for " + REQUEST_ID);
            return;
        }

        String deadlineValue = request.headers().get(DEADLINE);
        if (requestIdOnly && storedContext != null) deadlineValue = storedContext.get(DEADLINE);
        if ((deadlineValue == null || deadlineValue.isBlank()) && gatewaySidecar) {
            deadlineValue = Long.toString(System.currentTimeMillis() + config.defaultDeadlineMillis());
        }
        if (deadlineValue == null || deadlineValue.isBlank()) {
            rejectMissingContext(clientCtx, DEADLINE);
            return;
        }
        long deadline;
        try {
            deadline = Long.parseLong(deadlineValue);
        } catch (NumberFormatException invalidDeadline) {
            sendError(clientCtx, HttpResponseStatus.BAD_REQUEST,
                    "Invalid " + DEADLINE + " header");
            return;
        }

        boolean expired = requestIdOnly && storedContext != null
                ? Boolean.parseBoolean(storedContext.get(EXCEEDED))
                : (detectThisRequestDeadline && deadline <= System.currentTimeMillis())
                    || Boolean.parseBoolean(request.headers().get(EXCEEDED));
        if (!requestIdOnly && !gatewaySidecar
                && (!request.headers().contains(EXCEEDED) || !request.headers().contains(CANCELLATION))) {
            rejectMissingContext(clientCtx, "deadline and cancellation flags");
            return;
        }
        HttpHeaders contextHeaders = new DefaultHttpHeaders();
        contextHeaders.set(REQUEST_ID, requestId);
        contextHeaders.set(DEADLINE, Long.toString(deadline));
        contextHeaders.set(EXCEEDED, Boolean.toString(expired));
        boolean incomingCancellation = requestIdOnly && storedContext != null
                ? Boolean.parseBoolean(storedContext.get(CANCELLATION))
                : Boolean.parseBoolean(request.headers().get(CANCELLATION));
        boolean cancellation = expired || incomingCancellation;
        contextHeaders.set(CANCELLATION, Boolean.toString(cancellation));
        if (expired) {
            LOGGER.info("Inbound deadline already exceeded for Request_id=" + requestId
                    + "; forwarding with cancellation_Triggered=true");
        }
        boolean ownsContext = !(requestIdOnly && storedContext != null);
        if (ownsContext) REQUEST_CONTEXTS.put(requestId, contextHeaders.copy());
        final String activeRequestId = requestId;
        final long activeDeadline = deadline;
        if (ownsContext && detectThisRequestDeadline) {
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


        Bootstrap backendBootstrap = new Bootstrap();
        backendBootstrap.group(clientCtx.channel().eventLoop())
                .channel(clientCtx.channel().getClass())
                .handler(new BackendInitializer(clientCtx.channel(), activeRequestId, activeDeadline,
                        detectThisRequestDeadline, ownsContext))
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 5000);

        InetSocketAddress backendDestination = isLocalAddress(destination)
                ? new InetSocketAddress("127.0.0.1", destination.getPort())
                : destination;

        backendBootstrap.connect(backendDestination)
                .addListener((ChannelFutureListener) future -> {
                    if (!future.isSuccess()) {
                        if (ownsContext) forgetRequest(activeRequestId);
                        sendError(clientCtx, HttpResponseStatus.BAD_GATEWAY,
                                "Could not connect to backend: " + future.cause().getMessage());
                        return;
                    }

                    Channel backendChannel = future.channel();
                    HttpHeaders latestContext = requestContext(activeRequestId);
                    if (latestContext != null) {
                        latestContext.forEach(entry -> forwarded.headers().set(entry.getKey(), entry.getValue()));
                    }
                    boolean activeCancellation = latestContext != null
                            && Boolean.parseBoolean(latestContext.get(CANCELLATION));
                    backendChannel.writeAndFlush(forwarded).addListener(writeFuture -> {
                        if (!writeFuture.isSuccess()) {
                            if (ownsContext) forgetRequest(activeRequestId);
                            backendChannel.close();
                            sendError(clientCtx, HttpResponseStatus.BAD_GATEWAY,
                                    "Could not send request to backend");
                        } else if (activeCancellation) {
                            LOGGER.info(() -> "Forwarded cancellation_Triggered=true to "
                                    + destination + " for Request_id=" + activeRequestId);
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
