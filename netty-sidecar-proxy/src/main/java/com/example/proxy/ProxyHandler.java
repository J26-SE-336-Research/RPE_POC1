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
import java.net.URI;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

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

    private static void rejectMissingContext(ChannelHandlerContext ctx, FullHttpRequest request, String missing) {
        LOGGER.warning(() -> "Rejecting " + request.method() + " " + requestPath(request.uri())
                + " from " + ctx.channel().remoteAddress() + ": missing " + missing);
        sendError(ctx, HttpResponseStatus.BAD_REQUEST,
                "Missing required propagation header or context: " + missing);
    }

    @Override
    protected void channelRead0(ChannelHandlerContext clientCtx, FullHttpRequest request) {
        if (!(clientCtx.channel().localAddress() instanceof InetSocketAddress localAddress)
                || localAddress.isUnresolved()) {
            sendError(clientCtx, HttpResponseStatus.BAD_GATEWAY,
                    "Could not determine intercepted connection destination");
            return;
        }

        boolean outboundRedirect = localAddress.getPort() == config.listenPort();
        InetSocketAddress destination;
        InetSocketAddress originalDestination = localAddress;
        String uri = request.uri();
        if (outboundRedirect) {
            try {
                OutboundTarget target = outboundTarget(request);
                destination = target.address();
                uri = target.requestUri();
            } catch (IllegalArgumentException invalidTarget) {
                sendError(clientCtx, HttpResponseStatus.BAD_REQUEST, invalidTarget.getMessage());
                return;
            }
        } else {
            // The intercepted inbound destination is this pod's application port.
            // Connecting back to the pod IP can re-enter pod routing and TPROXY,
            // causing the same request to be intercepted repeatedly. Containers in
            // a pod share a network namespace, so loopback reaches the app directly.
            destination = new InetSocketAddress("127.0.0.1", localAddress.getPort());
        }
        String requestId = request.headers().get(REQUEST_ID);
        boolean suppliedRequestId = requestId != null && !requestId.isBlank();
        boolean gatewaySidecar = config.gatewaySidecar();
        if (!suppliedRequestId && !gatewaySidecar) {
            rejectMissingContext(clientCtx, request, REQUEST_ID);
            return;
        }
        if (!suppliedRequestId) requestId = UUID.randomUUID().toString();
        final String activeRequestId = requestId;
        final String logRequestUri = uri;
        LOGGER.info(() -> "Intercepted " + (outboundRedirect ? "outbound" : "inbound")
                + " " + request.method() + " " + requestPath(logRequestUri)
                + " requestId=" + activeRequestId
                + " client=" + clientCtx.channel().remoteAddress()
                + " originalDestination=" + originalDestination
                + " forwardingTo=" + destination
                + " contentBytes=" + request.content().readableBytes());
        HttpHeaders storedContext = REQUEST_CONTEXTS.get(requestId);
        boolean detectThisRequestDeadline = config.detectInboundDeadline() && !gatewaySidecar;
        boolean requestIdOnly = suppliedRequestId
                && !request.headers().contains(DEADLINE)
                && !request.headers().contains(EXCEEDED)
                && !request.headers().contains(CANCELLATION);

        // Outbound service calls carry only Request_id. Their sidecar must find
        // the full chain context saved from that service's inbound request.
        if (requestIdOnly && storedContext == null && !gatewaySidecar) {
            rejectMissingContext(clientCtx, request, "stored context for " + REQUEST_ID);
            return;
        }

        String deadlineValue = request.headers().get(DEADLINE);
        if (requestIdOnly && storedContext != null) deadlineValue = storedContext.get(DEADLINE);
        if ((deadlineValue == null || deadlineValue.isBlank()) && gatewaySidecar) {
            deadlineValue = Long.toString(System.currentTimeMillis() + config.defaultDeadlineMillis());
        }
        if (deadlineValue == null || deadlineValue.isBlank()) {
            rejectMissingContext(clientCtx, request, DEADLINE);
            return;
        }
        long deadline;
        try {
            deadline = Long.parseLong(deadlineValue);
        } catch (NumberFormatException invalidDeadline) {
            sendError(clientCtx, HttpResponseStatus.BAD_REQUEST, "Invalid " + DEADLINE + " header");
            return;
        }

        boolean expired = (storedContext != null && Boolean.parseBoolean(storedContext.get(EXCEEDED)))
                || (detectThisRequestDeadline && deadline <= System.currentTimeMillis())
                || Boolean.parseBoolean(request.headers().get(EXCEEDED));
        if (!requestIdOnly && !gatewaySidecar
                && (!request.headers().contains(EXCEEDED) || !request.headers().contains(CANCELLATION))) {
            rejectMissingContext(clientCtx, request, "deadline and cancellation flags");
            return;
        }
        HttpHeaders contextHeaders = new DefaultHttpHeaders();
        contextHeaders.set(REQUEST_ID, requestId);
        contextHeaders.set(DEADLINE, Long.toString(deadline));
        contextHeaders.set(EXCEEDED, Boolean.toString(expired));
        boolean incomingCancellation = (storedContext != null
                && Boolean.parseBoolean(storedContext.get(CANCELLATION)))
                || Boolean.parseBoolean(request.headers().get(CANCELLATION));
        boolean cancellation = expired || incomingCancellation;
        contextHeaders.set(CANCELLATION, Boolean.toString(cancellation));
        if (expired) {
            LOGGER.info("Inbound deadline already exceeded for Request_id=" + requestId
                    + "; forwarding with cancellation_Triggered=true");
        }
        // If this request belongs to a chain already known by this sidecar,
        // keep the inbound request as the owner of the saved context.
        boolean ownsContext = storedContext == null;
        if (ownsContext) REQUEST_CONTEXTS.put(requestId, contextHeaders.copy());
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

        // SimpleChannelInboundHandler releases the inbound request after this method
        // returns. The forwarded request holds its own retained content reference.

        Bootstrap backendBootstrap = new Bootstrap();
        backendBootstrap.group(clientCtx.channel().eventLoop())
                .channel(clientCtx.channel().getClass())
                .handler(new BackendInitializer(clientCtx.channel(), activeRequestId, activeDeadline,
                        detectThisRequestDeadline, ownsContext))
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 5000);

        LOGGER.info(() -> "Connecting to backend requestId=" + activeRequestId
                + " destination=" + destination);
        backendBootstrap.connect(destination)
                .addListener((ChannelFutureListener) future -> {
                    if (!future.isSuccess()) {
                        if (ownsContext) forgetRequest(activeRequestId);
                        LOGGER.log(Level.SEVERE, "Backend connect failed requestId=" + activeRequestId
                                + " destination=" + destination, future.cause());
                        sendError(clientCtx, HttpResponseStatus.BAD_GATEWAY,
                                "Could not connect to backend: " + future.cause().getMessage());
                        return;
                    }

                    Channel backendChannel = future.channel();
                    LOGGER.info(() -> "Backend connected requestId=" + activeRequestId
                            + " destination=" + backendChannel.remoteAddress());
                    HttpHeaders latestContext = requestContext(activeRequestId);
                    if (latestContext != null) {
                        latestContext.forEach(entry -> forwarded.headers().set(entry.getKey(), entry.getValue()));
                    }
                    boolean activeCancellation = latestContext != null
                            && Boolean.parseBoolean(latestContext.get(CANCELLATION));
                    backendChannel.writeAndFlush(forwarded).addListener(writeFuture -> {
                        if (!writeFuture.isSuccess()) {
                            if (ownsContext) forgetRequest(activeRequestId);
                            LOGGER.log(Level.SEVERE, "Backend request write failed requestId="
                                    + activeRequestId + " destination=" + destination, writeFuture.cause());
                            backendChannel.close();
                            sendError(clientCtx, HttpResponseStatus.BAD_GATEWAY,
                                    "Could not send request to backend");
                        } else {
                            LOGGER.info(() -> "Backend request write succeeded requestId="
                                    + activeRequestId + " destination=" + destination);
                            if (activeCancellation) {
                                LOGGER.info(() -> "Forwarded cancellation_Triggered=true to "
                                        + destination + " for Request_id=" + activeRequestId);
                            }
                        }
                    });
                });
    }

    private static String requestPath(String uri) {
        int query = uri.indexOf('?');
        return query < 0 ? uri : uri.substring(0, query) + "?[query redacted]";
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        LOGGER.log(Level.SEVERE, "Proxy request pipeline failed client="
                + ctx.channel().remoteAddress(), cause);
        ctx.close();
    }

    private static OutboundTarget outboundTarget(FullHttpRequest request) {
        URI uri;
        try {
            uri = URI.create(request.uri());
        } catch (IllegalArgumentException invalidUri) {
            throw new IllegalArgumentException("Invalid outbound HTTP request URI");
        }

        String host;
        int port;
        String backendUri;
        if (uri.isAbsolute()) {
            if (!"http".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null) {
                throw new IllegalArgumentException("Outbound proxy supports absolute http:// request URIs only");
            }
            host = uri.getHost();
            port = uri.getPort() >= 0 ? uri.getPort() : 80;
            backendUri = originForm(uri);
        } else {
            String authority = request.headers().get(HttpHeaderNames.HOST);
            if (authority == null || authority.isBlank()) {
                throw new IllegalArgumentException("Outbound HTTP request is missing the Host header");
            }
            URI hostUri;
            try {
                hostUri = URI.create("http://" + authority);
            } catch (IllegalArgumentException invalidAuthority) {
                throw new IllegalArgumentException("Invalid outbound HTTP Host header");
            }
            if (hostUri.getHost() == null || hostUri.getRawUserInfo() != null) {
                throw new IllegalArgumentException("Invalid outbound HTTP Host header");
            }
            host = hostUri.getHost();
            port = hostUri.getPort() >= 0 ? hostUri.getPort() : 80;
            backendUri = request.uri();
        }

        if (port < 1 || port > 65535) {
            throw new IllegalArgumentException("Invalid outbound HTTP destination port");
        }
        return new OutboundTarget(InetSocketAddress.createUnresolved(host, port), backendUri);
    }

    private static String originForm(URI uri) {
        String path = uri.getRawPath();
        if (path == null || path.isEmpty()) path = "/";
        return uri.getRawQuery() == null ? path : path + "?" + uri.getRawQuery();
    }

    private record OutboundTarget(InetSocketAddress address, String requestUri) {}

    private static void sendError(ChannelHandlerContext ctx, HttpResponseStatus status, String message) {
        if (!ctx.channel().isActive()) return;

        LOGGER.warning(() -> "Sending proxy error status=" + status.code()
                + " client=" + ctx.channel().remoteAddress() + " reason=" + message);

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
