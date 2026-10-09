package com.example.proxy;

import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.util.CharsetUtil;

public final class MetricsEndpointHandler extends SimpleChannelInboundHandler<FullHttpRequest> {
    private static final byte[] EMPTY_METRICS = "# No metrics registered\n".getBytes(CharsetUtil.UTF_8);

    @Override
    protected void channelRead0(ChannelHandlerContext context, FullHttpRequest request) {
        HttpResponseStatus status;
        byte[] body;

        if (!request.method().name().equals("GET")) {
            status = HttpResponseStatus.METHOD_NOT_ALLOWED;
            body = new byte[0];
        } else if (!request.uri().equals("/metrics")) {
            status = HttpResponseStatus.NOT_FOUND;
            body = new byte[0];
        } else {
            status = HttpResponseStatus.OK;
            body = EMPTY_METRICS;
        }

        DefaultFullHttpResponse response = new DefaultFullHttpResponse(
                HttpVersion.HTTP_1_1,
                status,
                Unpooled.wrappedBuffer(body));
        response.headers().set(HttpHeaderNames.CONTENT_TYPE, "text/plain; version=0.0.4; charset=utf-8");
        response.headers().setInt(HttpHeaderNames.CONTENT_LENGTH, body.length);

        boolean keepAlive = io.netty.handler.codec.http.HttpUtil.isKeepAlive(request);
        if (keepAlive) {
            response.headers().set(HttpHeaderNames.CONNECTION, "keep-alive");
            context.writeAndFlush(response);
        } else {
            context.writeAndFlush(response).addListener(ChannelFutureListener.CLOSE);
        }
    }
}
