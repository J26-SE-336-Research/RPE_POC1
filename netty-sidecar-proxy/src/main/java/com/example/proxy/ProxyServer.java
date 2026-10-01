package com.example.proxy;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpServerCodec;

public final class ProxyServer {
    private ProxyServer() {}

    public static void main(String[] args) throws Exception {
        ProxyConfig config = ProxyConfig.fromEnvironment();

        EventLoopGroup boss = new NioEventLoopGroup(1);
        EventLoopGroup workers = new NioEventLoopGroup();

        try {
            ServerBootstrap bootstrap = new ServerBootstrap();
            bootstrap.group(boss, workers)
                    .channel(NioServerSocketChannel.class)
                    .childOption(ChannelOption.SO_KEEPALIVE, true)
                    .childHandler(new ProxyInitializer(config));

            Channel channel = bootstrap.bind(config.listenHost(), config.listenPort()).sync().channel();

            System.out.printf(
                    "Proxy listening on http://%s:%d -> %s:%d%n",
                    config.listenHost(), config.listenPort(),
                    config.backendHost(), config.backendPort()
            );

            channel.closeFuture().sync();
        } finally {
            boss.shutdownGracefully();
            workers.shutdownGracefully();
        }
    }
}
