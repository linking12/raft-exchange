package com.binance.raftexchange.server.raft;

import org.jgroups.protocols.raft.RAFT;
import org.jgroups.protocols.raft.Role;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class RaftClusterRoleChangeListener implements RAFT.RoleChange {
	private final Logger LOG = LoggerFactory.getLogger(RaftClusterRoleChangeListener.class);
	private final RaftClusterContainer raftClusterContainer;

	public RaftClusterRoleChangeListener(RaftClusterContainer jgroupsRaftClusterView) {
		this.raftClusterContainer = jgroupsRaftClusterView;
	}

	@Override
	public void roleChanged(Role role) {
		LOG.debug("Role received {}.", role);
		switch (role) {
		case Leader:
			if (!raftClusterContainer.isLeader()) {
				raftClusterContainer.setLeader(true);
			}
			break;
		case Follower:
			if (raftClusterContainer.isLeader()) {
				raftClusterContainer.setLeader(false);
			}
			break;
		default:
			LOG.error("Role {} unknown.", role);
			throw new UnsupportedOperationException("Role " + role + " unknown.");
		}
	}
}
