package com.binance.raftexchange.server.raft;

import org.jgroups.JChannel;
import org.jgroups.raft.RaftHandle;
import org.jgroups.raft.StateMachine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class JGroupsRaftClusterView {
    private static final transient Logger LOG = LoggerFactory.getLogger(JGroupsRaftClusterView.class);
    private StateMachine stateMachine;
    private String jgroupsConfig;
    private String jgroupsClusterName;
    private RaftHandle raftHandle;
    private String raftId;
    private volatile boolean isMaster;

    public JGroupsRaftClusterView(String jgroupsConfig, String jgroupsClusterName, RaftHandle raftHandle, String raftId) {
        this.jgroupsConfig = jgroupsConfig;
        this.jgroupsClusterName = jgroupsClusterName;
        this.raftHandle = raftHandle;
        this.raftId = raftId;
        this.stateMachine = new ExchangeStateMachine();
    }

    public void doStart() throws Exception {
        if (raftHandle == null && jgroupsConfig != null && !jgroupsConfig.isEmpty()) {
            raftHandle = new RaftHandle(new JChannel(jgroupsConfig), stateMachine).raftId(raftId);
        }
        Exception cause = null;
        for (int i = 1; i < 11; i++) {
            LOG.debug("Attempt #{} for raft {} to join {}", i, raftId, jgroupsClusterName);
            try {
                raftHandle.addRoleListener(new ClusterRoleChangeListener(this));
                raftHandle.channel().connect(jgroupsClusterName);
                LOG.debug("Joined and connected to {} with raft id: {}", jgroupsClusterName, raftId);
                cause = null;
                break;
            } catch (Exception e) {
                cause = e;
            }
            // wait for next attempt
            Thread.sleep(5000);
        }
        if (cause != null) {
            throw cause;
        }
    }

    public void doStop() throws Exception {
        isMaster = false;
        LOG.info("Disconnecting JGroupsraft Channel for JGroupsRaftClusterView with Id {}", raftId);
        raftHandle.channel().disconnect();
        if (raftHandle != null && raftHandle.log() != null) {
            raftHandle.log().close();
            LOG.info("Closed Log for JGroupsRaftClusterView with Id {}", raftId);
        }
    }

    public void doShutdown() throws Exception {
        isMaster = false;
        if (raftHandle != null) {
            if (raftHandle.channel() != null) {
                LOG.info("Closing JGroupsraft Channel for JGroupsRaftClusterView with Id {}", raftId);
                raftHandle.channel().close();
                LOG.info("Closed JGroupsraft Channel Channel for JGroupsRaftClusterView with Id {}", raftId);
            }
            if (raftHandle.log() != null) {
                LOG.info("Closing Log for JGroupsRaftClusterView with Id {}", raftId);
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
