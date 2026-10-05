package com.example.proxy;

import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.Channel;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaders;
import java.util.logging.Logger;

public final class BackendResponseHandler
        extends SimpleChannelInboundHandler<FullHttpResponse> {
    private static final Logger LOGGER = Logger.getLogger(BackendResponseHandler.class.getName());

    private final Channel clientChannel;
    private final String requestId;
    private final long deadline;
    private final boolean detectInboundDeadline;
    private final boolean ownsContext;

    public BackendResponseHandler(Channel clientChannel, String requestId, long deadline,
                                  boolean detectInboundDeadline, boolean ownsContext) {
        this.clientChannel = clientChannel;
        this.requestId = requestId;
        this.deadline = deadline;
        this.detectInboundDeadline = detectInboundDeadline;
        this.ownsContext = ownsContext;
    }

    @Override
    protected void channelRead0(ChannelHandlerContext backendCtx, FullHttpResponse response) {
        if (!clientChannel.isActive()) {
            if (ownsContext) ProxyHandler.forgetRequest(requestId);
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

        clientChannel.writeAndFlush(forwarded);
        if (ownsContext) ProxyHandler.forgetRequest(requestId);
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) {
        if (clientChannel.isActive()) {
            clientChannel.close();
        }
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        cause.printStackTrace();
        if (clientChannel.isActive()) {
            clientChannel.close();
        }
        ctx.close();
    }
}
