package com.binance.raftexchange.server.raft;

import org.jgroups.protocols.raft.RAFT;
import org.jgroups.protocols.raft.Role;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ClusterRoleChangeListener implements RAFT.RoleChange {
    private static final Logger LOG = LoggerFactory.getLogger(ClusterRoleChangeListener.class);
    private final JGroupsRaftClusterView jgroupsRaftClusterView;

    public ClusterRoleChangeListener(JGroupsRaftClusterView jgroupsRaftClusterView) {
        this.jgroupsRaftClusterView = jgroupsRaftClusterView;
    }

    @Override
    public void roleChanged(Role role) {
        LOG.debug("Role received {}.", role);
        switch (role) {
            case Leader:
                jgroupsRaftClusterView.setMaster(true);
                break;
            case Follower:
                jgroupsRaftClusterView.setMaster(false);
                break;
            default:
                LOG.error("Role {} unknown.", role);
                throw new UnsupportedOperationException("Role " + role + " unknown.");
        }
    }
}
