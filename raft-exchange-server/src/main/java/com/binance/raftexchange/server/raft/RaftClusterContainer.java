package com.binance.raftexchange.server.raft;

import java.util.concurrent.TimeUnit;

import org.jgroups.JChannel;
import org.jgroups.protocols.raft.Role;
import org.jgroups.raft.RaftHandle;
import org.jgroups.raft.StateMachine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class RaftClusterContainer {
	private static final Logger LOGGER = LoggerFactory.getLogger(RaftClusterContainer.class);
	private final StateMachine stateMachine;
	private final String jgroupsClusterName;
	private final RaftClusterDiscovery raftClusterDiscovery;
	private volatile boolean isLeader;
	private RaftHandle raftHandle;
	private String raftCurrentMember;

	public RaftClusterContainer(RaftClusterDiscovery raftClusterDiscovery) {
		this.jgroupsClusterName = raftClusterDiscovery.getJgroupClusterName();
		this.raftClusterDiscovery = raftClusterDiscovery;
		this.stateMachine = new ExchangeStateMachine();
	}

	public void doStart() throws Exception {
		String raftCurrentMember = raftClusterDiscovery.raftCurrentMember();
		JChannel jChannel = null;
		do {
			String raftMemberCluster = raftClusterDiscovery.raftMemberCluster();
			jChannel = this.raftClusterDiscovery.createJChannel(raftMemberCluster, raftCurrentMember);
			TimeUnit.SECONDS.sleep(5);
			LOGGER.info("Starting jgroup: {}", raftCurrentMember);
		} while (jChannel == null);
		this.raftCurrentMember = raftCurrentMember;
		this.raftHandle = new RaftHandle(jChannel, stateMachine).raftId(raftCurrentMember).addRoleListener(role -> {
			if (role == Role.Leader) {
				isLeader = true;
				LOGGER.info("Won HA election, starting raftExchange:{}", raftCurrentMember);
			} else {
				isLeader = false;
				LOGGER.info("Unable to find consensus, stepping down HA leadership:{}", raftCurrentMember);
			}
		});
		this.raftHandle.channel().connect(jgroupsClusterName);
	}

	public void doStop() throws Exception {
		isLeader = false;
		LOGGER.info("Disconnecting JGroupsraft Channel for JGroupsRaftClusterView with Id {}", raftCurrentMember);
		raftHandle.channel().disconnect();
		if (raftHandle != null && raftHandle.log() != null) {
			raftHandle.log().close();
			LOGGER.info("Closed Log for JGroupsRaftClusterView with Id {}", raftCurrentMember);
		}
	}

	public void doShutdown() throws Exception {
		isLeader = false;
		if (raftHandle != null) {
			if (raftHandle.channel() != null) {
				LOGGER.info("Closing JGroupsraft Channel for JGroupsRaftClusterView with Id {}", raftCurrentMember);
				raftHandle.channel().close();
				LOGGER.info("Closed JGroupsraft Channel Channel for JGroupsRaftClusterView with Id {}",
						raftCurrentMember);
			}
			if (raftHandle.log() != null) {
				LOGGER.info("Closing Log for JGroupsRaftClusterView with Id {}", raftCurrentMember);
				raftHandle.log().close();
				LOGGER.info("Closed Log for JGroupsRaftClusterView with Id {}", raftCurrentMember);
			}
			raftHandle = null;
		}
	}

	public boolean isLeader() {
		return this.isLeader;
	}

	public RaftHandle raftHandle() {
		return this.raftHandle;
	}

}
