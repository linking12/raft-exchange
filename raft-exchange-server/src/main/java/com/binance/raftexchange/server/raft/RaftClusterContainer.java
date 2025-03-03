package com.binance.raftexchange.server.raft;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import org.jgroups.Address;
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
                LOGGER.info("Won HA election, starting raftExchange:{}", raftCurrentMember);
            } else {
                LOGGER.info("Unable to find consensus, stepping down HA leadership:{}", raftCurrentMember);
            }
        });
        this.raftHandle.channel().connect(jgroupsClusterName);
    }

    public void doStop() throws Exception {
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

    public boolean startSuccessed() {
        return raftHandle.channel().view() != null;
    }

    public CompletableFuture<byte[]> requestConsensus(byte[] log) throws Exception {
        return raftHandle.setAsync(log, 0, log.length);
    }

    public boolean isLeader() {
        return raftHandle.isLeader();
    }

    public List<RaftNode> listNodes() {
        List<Address> members = this.raftHandle.channel().view().getMembers();
        Address leader = this.raftHandle.leader();
        ArrayList<RaftNode> list = new ArrayList<>(members.size());
        List<String> raftedWorkers = raftClusterDiscovery.raftWorkers();
        // todo 找不到address转host port的办法 所以先用tostring做demo。。。
        for (Address member : members) {
            list.add(parse(member, leader, raftedWorkers));
        }
        return list;
    }

    public RaftNode leaderNode() {
        Address leader = this.raftHandle.leader();
        List<String> raftedWorkers = raftClusterDiscovery.raftWorkers();
        return parse(leader, leader, raftedWorkers);
    }

    private RaftNode parse(Address member, Address leader, List<String> raftedWorkers) {
        boolean isLeader = member.equals(leader);
        String string = member.toString();
        String[] hostAndPort = string.split("\\[");
        String host = hostAndPort[0];
        int port = raftedWorkers.stream().filter(s -> s.startsWith(host)).map(s -> s.split("\\[")[1])
            .mapToInt(s -> Integer.parseInt(s.substring(0, s.length() - 1))).findFirst().getAsInt();

        return new RaftNode(host, port, isLeader ? RaftNode.NodeType.LEADER : RaftNode.NodeType.FOLLOWER);
    }

}
