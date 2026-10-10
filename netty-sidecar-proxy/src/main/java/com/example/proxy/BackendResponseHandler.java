package com.example.proxy;

import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.Channel;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaders;
import java.util.logging.Level;
import java.util.logging.Logger;

public final class BackendResponseHandler
        extends SimpleChannelInboundHandler<FullHttpResponse> {
    private static final Logger LOGGER = Logger.getLogger(BackendResponseHandler.class.getName());

    private final Channel clientChannel;
    private final String requestId;
    private final long deadline;
    private final boolean detectInboundDeadline;
    private final boolean ownsContext;
    private final ProxyMetrics.InboundRequestTracker inboundTracker;
    private final long requestStartNanos;
    private boolean receivedResponse;

    public BackendResponseHandler(Channel clientChannel, String requestId, long deadline,
                                  boolean detectInboundDeadline, boolean ownsContext,
                                  ProxyMetrics.InboundRequestTracker inboundTracker, long requestStartNanos) {
        this.clientChannel = clientChannel;
        this.requestId = requestId;
        this.deadline = deadline;
        this.detectInboundDeadline = detectInboundDeadline;
        this.ownsContext = ownsContext;
        this.inboundTracker = inboundTracker;
        this.requestStartNanos = requestStartNanos;
    }

    @Override
    protected void channelRead0(ChannelHandlerContext backendCtx, FullHttpResponse response) {
        receivedResponse = true;
        final int responseStatus = response.status().code();
        if (inboundTracker != null) {
            ProxyMetrics.recordInboundFailure(responseStatus);
            ProxyMetrics.recordInboundRequestDuration(System.nanoTime() - requestStartNanos);
        }
        LOGGER.info(() -> "Backend response received requestId=" + requestId
                + " status=" + responseStatus
                + " contentBytes=" + response.content().readableBytes()
                + " backend=" + backendCtx.channel().remoteAddress());
        if (!clientChannel.isActive()) {
            LOGGER.warning(() -> "Client disconnected before backend response could be forwarded"
                    + " requestId=" + requestId + " client=" + clientChannel.remoteAddress());
            if (inboundTracker != null) inboundTracker.finish();
            return;
        }

        FullHttpResponse forwarded = response.retainedDuplicate();

        // Example response-side modification.
        forwarded.headers().set("X-Proxy-Processed", "true");
        boolean downstreamExpired = Boolean.parseBoolean(response.headers().get("deadlineExceded"));
        boolean downstreamCancellation = Boolean.parseBoolean(response.headers().get("cancellation_Triggered"));
        if (downstreamExpired || downstreamCancellation) {
            ProxyHandler.markResponseStatus(requestId, downstreamExpired, downstreamCancellation);
            LOGGER.info(() -> "Received cancellation status from downstream for Request_id=" + requestId
                    + " (deadlineExceded=" + downstreamExpired
                    + ", cancellation_Triggered=" + downstreamCancellation + ")");
        }
        io.netty.handler.codec.http.HttpHeaders chainContext = ProxyHandler.requestContext(requestId);
        boolean expired = downstreamExpired
                || (ownsContext && detectInboundDeadline && deadline <= System.currentTimeMillis())
                || (chainContext != null && Boolean.parseBoolean(chainContext.get("deadlineExceded")));
        boolean cancellation = downstreamCancellation || expired || (chainContext != null
                && Boolean.parseBoolean(chainContext.get("cancellation_Triggered")));
        forwarded.headers().set("Request_id", requestId);
        forwarded.headers().set("deadlinevalue", Long.toString(deadline));
        forwarded.headers().set("deadlineExceded", Boolean.toString(expired));
        forwarded.headers().set("cancellation_Triggered", Boolean.toString(cancellation));
        if (cancellation) {
            LOGGER.info(() -> "Forwarding cancellation status upstream for Request_id=" + requestId);
        }
        forwarded.headers().remove(HttpHeaderNames.TRANSFER_ENCODING);
        forwarded.headers().setInt(
                HttpHeaderNames.CONTENT_LENGTH,
                forwarded.content().readableBytes()
        );

        clientChannel.writeAndFlush(forwarded).addListener(writeFuture -> {
            if (writeFuture.isSuccess()) {
                if (inboundTracker != null) {
                    ProxyMetrics.recordInboundSuccessfulRequest(responseStatus);
                }
                LOGGER.info(() -> "Backend response forwarded to client requestId=" + requestId
                        + " status=" + responseStatus
                        + " client=" + clientChannel.remoteAddress());
            } else {
                LOGGER.log(Level.SEVERE, "Forwarding backend response to client failed requestId="
                        + requestId + " client=" + clientChannel.remoteAddress(), writeFuture.cause());
            }
            if (inboundTracker != null) inboundTracker.finish();
        });
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) {
        if (inboundTracker != null) inboundTracker.finish();
        if (!receivedResponse) {
            LOGGER.warning(() -> "Backend connection closed before a response was received"
                    + " requestId=" + requestId + " backend=" + ctx.channel().remoteAddress());
        }
        if (clientChannel.isActive()) {
            clientChannel.close();
        }
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        LOGGER.log(Level.SEVERE, "Backend response pipeline failed requestId=" + requestId
                + " backend=" + ctx.channel().remoteAddress(), cause);
        if (clientChannel.isActive()) {
            clientChannel.close();
        }
        if (inboundTracker != null) inboundTracker.finish();
        ctx.close();
    }
}
