package com.binance.raftexchange.server.grpc;

import java.util.concurrent.TimeUnit;

import com.binance.raftexchange.server.raft.RaftClusterContainer;
import com.google.common.util.concurrent.MoreExecutors;
import io.grpc.netty.InternalNettyServerBuilder;
import io.netty.channel.WriteBufferWaterMark;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.grpc.Server;
import io.grpc.netty.NettyServerBuilder;
import io.netty.channel.ChannelOption;

public class GrpcServerContainer {
    private static final Logger LOGGER = LoggerFactory.getLogger(GrpcServerContainer.class);
    private static final long GRACEFUL_SHUTDOWN_TIMEOUT_SEC = 10;
    private static final long FORCED_SHUTDOWN_TIMEOUT_SEC = 2;

    private RaftClusterContainer raftClusterContainer;
    private Server server;

    public void setRaftClusterContainer(RaftClusterContainer raftClusterContainer) {
        this.raftClusterContainer = raftClusterContainer;
    }

    public void doStart() throws Exception {
        String grpcPort = System.getProperty("grpc.port", "5001");
        NettyServerBuilder builder =
            NettyServerBuilder.forPort(Integer.parseInt(grpcPort)).withChildOption(ChannelOption.TCP_NODELAY, true)//
                .withChildOption(ChannelOption.SO_LINGER, -1)//
                .withChildOption(ChannelOption.SO_KEEPALIVE, Boolean.TRUE)//
                .withChildOption(ChannelOption.TCP_NODELAY, Boolean.FALSE)//
                .withChildOption(ChannelOption.SO_REUSEADDR, Boolean.TRUE)//
                .withChildOption(ChannelOption.WRITE_BUFFER_WATER_MARK, WriteBufferWaterMark.DEFAULT)//
                .withOption(ChannelOption.SO_REUSEADDR, true)//
                .withOption(ChannelOption.SO_BACKLOG, 8192)//
                .addService(new ApiService(raftClusterContainer).transform())//
                .addService(new SevererNodeService(raftClusterContainer))//
                .addService(new QueryService(raftClusterContainer))//
                .addService(new SnapshotTransferService(raftClusterContainer::snapshotsRoot))//
                .executor(MoreExecutors.directExecutor());
        InternalNettyServerBuilder.setTracingEnabled(builder, false);
        this.server = builder.build();
        server.start();
        LOGGER.info("grpc server start {}", grpcPort);
    }

    public void doStop() throws Exception {
        server.shutdown();
        if (!server.awaitTermination(GRACEFUL_SHUTDOWN_TIMEOUT_SEC, TimeUnit.SECONDS)) {
            LOGGER.warn("gRPC server did not terminate gracefully in {}s; forcing shutdownNow",
                GRACEFUL_SHUTDOWN_TIMEOUT_SEC);
            server.shutdownNow();
            if (!server.awaitTermination(FORCED_SHUTDOWN_TIMEOUT_SEC, TimeUnit.SECONDS)) {
                LOGGER.error("gRPC server did not terminate after forced shutdown within {}s",
                    FORCED_SHUTDOWN_TIMEOUT_SEC);
            }
        }
    }
}
