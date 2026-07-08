package com.binance.raftexchange.server.raft.jraft;

import com.binance.raftexchange.server.exchange.ExchangeRuntime;
import com.binance.raftexchange.server.exchange.events.IEventsHandlerByKafka;
import com.binance.raftexchange.server.grpc.GrpcServerContainer;
import com.binance.raftexchange.server.raft.RaftClusterContainer;
import com.binance.raftexchange.server.raft.RaftClusterDiscovery;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 多节点 raft E2E 子进程入口（每个子 JVM 跑一个 raft 节点 + 一份 ExchangeRuntime）。 由 {@link MultiNodeRaftHarness} fork 拉起，通过 system
 * property 传参，stdout 打 RAFT_NODE_READY marker 通知父进程就绪。
 */
public final class RaftNodeMain {

    private RaftNodeMain() {}

    public static void main(String[] args) throws Exception {
        int raftPort = Integer.parseInt(System.getProperty("raft.port"));
        int grpcPort = Integer.parseInt(System.getProperty("grpc.port"));
        String clusterName = System.getProperty("raft.clusterName");
        String peersStr = System.getProperty("raft.cluster.peers");
        String grpcMapStr = System.getProperty("raft.cluster.grpcMap");
        if (clusterName == null || peersStr == null || grpcMapStr == null) {
            throw new IllegalStateException("missing required system properties");
        }
        System.setProperty("raftexchange.cluster.startupNodes", "3");

        IEventsHandlerByKafka handler = KafkaMockSupport.buildDiscardingHandler();
        ExchangeRuntime exchangeRuntime = new ExchangeRuntime(handler);

        String selfRaft = "127.0.0.1:" + raftPort;
        RaftClusterDiscovery discovery = mock(RaftClusterDiscovery.class);
        when(discovery.getRaftClusterName()).thenReturn(clusterName);
        when(discovery.raftCurrentMember()).thenReturn(selfRaft);
        when(discovery.raftMemberCluster()).thenReturn(peersStr);
        when(discovery.raftToGrpcPeerMap()).thenReturn(parseGrpcMap(grpcMapStr));

        RaftClusterContainer raftContainer = new JraftClusterContainer(discovery, exchangeRuntime);
        raftContainer.addRoleChangeListener(handler::onRoleChange);
        raftContainer.doStart();

        GrpcServerContainer grpcContainer = new GrpcServerContainer();
        grpcContainer.setRaftClusterContainer(raftContainer);
        grpcContainer.doStart();

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                grpcContainer.doStop();
            } catch (Exception ignored) {
            }
            try {
                raftContainer.doStop();
            } catch (Exception ignored) {
            }
            try {
                exchangeRuntime.exchangeCore().shutdown();
            } catch (Exception ignored) {
            }
        }, "raft-node-shutdown"));

        // 父进程靠 stdout 上这行 marker 判定子进程进入工作状态
        System.out.println("RAFT_NODE_READY raftPort=" + raftPort + " grpcPort=" + grpcPort);
        System.out.flush();

        Thread.currentThread().join();
    }

    private static Map<String, String> parseGrpcMap(String s) {
        Map<String, String> map = new LinkedHashMap<>();
        for (String entry : s.split(";")) {
            String[] kv = entry.split("=");
            if (kv.length != 2) {
                throw new IllegalArgumentException("bad grpcMap entry: " + entry);
            }
            map.put(kv[0], kv[1]);
        }
        return map;
    }
}
