package com.binance.raftexchange.server.grpc;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.alipay.sofa.jraft.util.Utils;
import com.binance.raftexchange.server.raft.RaftClusterContainer;

import io.grpc.Server;
import io.grpc.internal.GrpcUtil;
import io.grpc.netty.NettyServerBuilder;
import io.grpc.netty.shaded.io.netty.channel.DefaultEventLoopGroup;
import io.grpc.netty.shaded.io.netty.channel.EventLoopGroup;
import io.netty.channel.ChannelOption;

public class GrpcServerContainer {
    private static final Logger LOGGER = LoggerFactory.getLogger(GrpcServerContainer.class);
    private RaftClusterContainer raftClusterContainer;
    private EventLoopGroup offloadWorker;
    private Server server;

    public void setRaftClusterContainer(RaftClusterContainer raftClusterContainer) {
        this.raftClusterContainer = raftClusterContainer;
        this.offloadWorker = new DefaultEventLoopGroup(Math.max(Utils.cpus() << 3, 32), GrpcUtil.getThreadFactory("grpc-biz-%d", true));
    }

    public void doStart() throws Exception {
        String grpcPort = System.getProperty("grpc.port", "5001");
        // grpc默认会判断epoll可不可用 可用就使用
        // EventLoop线程数为一倍核数 不用调整
        this.server = NettyServerBuilder.forPort(Integer.parseInt(grpcPort)).withChildOption(ChannelOption.TCP_NODELAY, true)//
            .withChildOption(ChannelOption.SO_LINGER, -1)//
            .withChildOption(ChannelOption.SO_KEEPALIVE, Boolean.TRUE)//
            .withChildOption(ChannelOption.TCP_NODELAY, Boolean.FALSE)//
            .withChildOption(ChannelOption.SO_REUSEADDR, Boolean.TRUE)//
            .withOption(ChannelOption.SO_REUSEADDR, true)//
            .withOption(ChannelOption.SO_BACKLOG, 8192).addService(new ApiService(raftClusterContainer, offloadWorker).transform())
            .addService(new SevererNodeService(raftClusterContainer))//
            .addService(new QueryService(raftClusterContainer, offloadWorker))//
            .executor(offloadWorker).build();
        server.start();
        LOGGER.info("grpc server start {}", grpcPort);
    }

    public void doStop() throws Exception {
        server.shutdown();
    }
}
