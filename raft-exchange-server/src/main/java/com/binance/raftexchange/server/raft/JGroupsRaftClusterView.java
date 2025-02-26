package com.binance.raftexchange.server.raft;

import com.binance.platform.common.shutdown.GracefulShutdownHook;
import org.jgroups.JChannel;
import org.jgroups.raft.RaftHandle;
import org.jgroups.raft.StateMachine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.TimeUnit;

public class JGroupsRaftClusterView implements GracefulShutdownHook {
    private static final Logger LOG = LoggerFactory.getLogger(JGroupsRaftClusterView.class);
    private final ClusterDiscoveryByEureka discovery;
    private final StateMachine stateMachine;
    private final String jgroupsClusterName;
    private final String raftId;
    private volatile boolean isMaster;
    private RaftHandle raftHandle;

    public JGroupsRaftClusterView(ClusterDiscoveryByEureka discovery, StateMachine stateMachine, String jgroupsClusterName) {
        this.discovery = discovery;
        this.stateMachine = stateMachine;
        this.jgroupsClusterName = jgroupsClusterName;
        this.raftId = discovery.raftCurrentMember();
    }

    public void doStart() throws Exception {
        JChannel jChannel;
        do {
            String raftMemberCluster = discovery.raftMemberCluster();
            jChannel = discovery.createJChannel(raftMemberCluster, raftId);
            TimeUnit.SECONDS.sleep(5);
            LOG.info("Starting jgroup: {}", raftId);
        } while (jChannel == null);
        this.raftHandle = new RaftHandle(jChannel, null).raftId(raftId);
        raftHandle.addRoleListener(new ClusterRoleChangeListener(this));
        this.raftHandle.channel().connect(jgroupsClusterName);
        LOG.info("Joined and connected to {} with raft id: {}", jgroupsClusterName, raftId);
    }

    @Override
    public void shutdown() {
        try {
            doShutdown();
        } catch (Exception e) {
            LOG.error("Error shutting down JGroupsRaftClusterView", e);
        }
    }

    public void doShutdown() throws Exception {
        isMaster = false;
        if (raftHandle != null) {
            if (raftHandle.channel() != null) {
                raftHandle.channel().close();
                LOG.info("Closed JGroupsRaft Channel for JGroupsRaftClusterView with Id {}", raftId);
            }
            if (raftHandle.log() != null) {
                raftHandle.log().close();
                LOG.info("Closed Log for JGroupsRaftClusterView with Id {}", raftId);
            }
            raftHandle = null;
        }
    }

    public boolean isMaster() {
        return isMaster;
    }

    public void setMaster(boolean master) {
        isMaster = master;
    }

}
