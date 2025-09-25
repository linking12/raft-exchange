package com.binance.raftexchange.server.grpc;

import com.google.common.util.concurrent.MoreExecutors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.binance.raftexchange.server.raft.RaftClusterContainer;

import io.grpc.Server;
import io.grpc.netty.NettyServerBuilder;
import io.netty.channel.ChannelOption;

public class GrpcServerContainer {
    private static final Logger LOGGER = LoggerFactory.getLogger(GrpcServerContainer.class);
    private RaftClusterContainer raftClusterContainer;
    private Server server;

    public void setRaftClusterContainer(RaftClusterContainer raftClusterContainer) {
        this.raftClusterContainer = raftClusterContainer;
    }

    public void doStart() throws Exception {
        String grpcPort = System.getProperty("grpc.port", "5001");
        this.server = NettyServerBuilder.forPort(Integer.parseInt(grpcPort)).withChildOption(ChannelOption.TCP_NODELAY, true)//
            .withChildOption(ChannelOption.SO_LINGER, -1)//
            .withChildOption(ChannelOption.SO_KEEPALIVE, Boolean.TRUE)//
            .withChildOption(ChannelOption.TCP_NODELAY, Boolean.FALSE)//
            .withChildOption(ChannelOption.SO_REUSEADDR, Boolean.TRUE)//
            .withOption(ChannelOption.SO_REUSEADDR, true)//
            .withOption(ChannelOption.SO_BACKLOG, 8192).addService(new ApiService(raftClusterContainer).transform())
            .addService(new SevererNodeService(raftClusterContainer))//
            .addService(new QueryService(raftClusterContainer))//
            .executor(MoreExecutors.directExecutor()).build();
        server.start();
        LOGGER.info("grpc server start {}", grpcPort);
    }

    public void doStop() throws Exception {
        server.shutdown();
    }
}
