package com.example.proxy;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.Channel;
import io.netty.channel.socket.SocketChannel;
import io.netty.handler.codec.http.HttpClientCodec;
import io.netty.handler.codec.http.HttpObjectAggregator;

public final class BackendInitializer extends ChannelInitializer<SocketChannel> {
    private final Channel clientChannel;
    private final String requestId;
    private final long deadline;
    private final boolean detectInboundDeadline;
    private final boolean ownsContext;
    private final ProxyMetrics.InboundRequestTracker inboundTracker;
    private final long requestStartNanos;

    public BackendInitializer(Channel clientChannel, String requestId, long deadline,
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
    protected void initChannel(SocketChannel channel) {
        channel.pipeline()
                .addLast("httpClientCodec", new HttpClientCodec())
                .addLast("aggregator", new HttpObjectAggregator(10 * 1024 * 1024))
                .addLast("backendResponseHandler", new BackendResponseHandler(
                        clientChannel, requestId, deadline, detectInboundDeadline, ownsContext,
                        inboundTracker, requestStartNanos));
    }
}
