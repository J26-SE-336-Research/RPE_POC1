package com.example.proxy;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.epoll.Epoll;
import io.netty.channel.epoll.EpollServerSocketChannel;
import io.netty.channel.epoll.EpollChannelOption;
import io.netty.channel.epoll.EpollEventLoopGroup;

public final class ProxyServer {
    private ProxyServer() {}

    public static void main(String[] args) throws Exception {
        ProxyConfig config = ProxyConfig.fromEnvironment();

        if (!Epoll.isAvailable()) {
            throw new IllegalStateException("This TPROXY sidecar requires Netty native epoll on Linux",
                    Epoll.unavailabilityCause());
        }
        EventLoopGroup boss = new EpollEventLoopGroup(1);
        EventLoopGroup workers = new EpollEventLoopGroup();

        try {
            ServerBootstrap bootstrap = new ServerBootstrap();
            bootstrap.group(boss, workers);
            bootstrap.channel(EpollServerSocketChannel.class)
                    .option(EpollChannelOption.IP_TRANSPARENT, true)
                    .childOption(ChannelOption.SO_KEEPALIVE, true)
                    .childHandler(new ProxyInitializer(config));

            Channel channel = bootstrap.bind(config.listenHost(), config.listenPort()).sync().channel();

            System.out.printf("TPROXY sidecar listening on %s:%d; forwarding to each connection's original destination%n",
                    config.listenHost(), config.listenPort());

            channel.closeFuture().sync();
        } finally {
            boss.shutdownGracefully();
            workers.shutdownGracefully();
        }
    }
}
