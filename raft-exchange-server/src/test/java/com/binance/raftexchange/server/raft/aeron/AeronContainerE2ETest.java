package com.binance.raftexchange.server.raft.aeron;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import com.binance.raftexchange.server.raft.RaftNode;
import com.binance.raftexchange.server.raft.RaftResponse;
import com.binance.raftexchange.stubs.request.ApiAddUser;
import com.binance.raftexchange.stubs.request.ApiCommand;
import com.binance.raftexchange.stubs.request.ApiNop;
import com.binance.raftexchange.stubs.response.CommandResult;
import com.binance.raftexchange.stubs.response.CommandResultCode;

/**
 * AeronClusterContainer + ExchangeRuntime 全链路： requestConsensus(byte[] log) → AeronClient.send → cluster ingress →
 * AeronExchangeStateMachine.onSessionMessage → CommandRegistry.apply(cmd) → exchange-core (或 NOP 直接 SUCCESS) → egress 回
 * callback
 *
 * 慢测试（~6s）：要起完整 Aeron cluster + exchange-core。默认 disabled，手动跑： mvn -pl raft-exchange-server test
 * -Dtest=AeronContainerE2ETest \ -Djunit.jupiter.conditions.deactivate='org.junit.*DisabledCondition'
 */
@Disabled("Slow e2e (~6s): starts full Aeron cluster + ExchangeCore in-process. 手动回归用。")
class AeronContainerE2ETest {

    private static final long CMD_TIMEOUT_MS = 10_000;

    @Test
    void singleNodeAeronElectsLeaderAndListsItself() throws Exception {
        try (AeronSingleNodeHarness h = AeronSingleNodeHarness.start()) {
            assertTrue(h.container().isLeader(), "单节点必须立即成为 leader");
            RaftNode leader = h.leaderNode();
            assertNotNull(leader, "leaderNode 不能为 null");
            assertEquals(RaftNode.NodeType.LEADER, leader.nodeType());

            List<RaftNode> nodes = h.container().listNodes();
            assertEquals(1, nodes.size(), "单节点 cluster 应当报 1 个 node");
            assertEquals(RaftNode.NodeType.LEADER, nodes.get(0).nodeType());
        }
    }

    @Test
    void nopCommand_roundTripsToSuccessResponse() throws Exception {
        try (AeronSingleNodeHarness h = AeronSingleNodeHarness.start()) {
            ApiCommand cmd = ApiCommand.newBuilder().setTimestamp(System.currentTimeMillis())
                .setNop(ApiNop.getDefaultInstance()).build();

            RaftResponse resp = h.submitAndAwait(cmd.toByteArray(), CMD_TIMEOUT_MS);
            CommandResult result = CommandResult.parseFrom(resp.serializer().get());
            assertEquals(CommandResultCode.SUCCESS, result.getResultCode(), "NOP 必须返 SUCCESS（handleNoOp 路径）");
        }
    }

    @Test
    void addUser_roundTripsThroughExchangeCore() throws Exception {
        try (AeronSingleNodeHarness h = AeronSingleNodeHarness.start()) {
            ApiCommand cmd = ApiCommand.newBuilder().setTimestamp(System.currentTimeMillis())
                .setAddUser(ApiAddUser.newBuilder().setUid(10001L)).build();

            RaftResponse resp = h.submitAndAwait(cmd.toByteArray(), CMD_TIMEOUT_MS);
            CommandResult result = CommandResult.parseFrom(resp.serializer().get());
            // exchange-core 命令的 resultCode 在 orderCommand 子消息里（非 NOP 路径），跟 NOP 不同
            assertEquals(CommandResultCode.SUCCESS, result.getOrderCommand().getResultCode(),
                "AddUser 必须返 SUCCESS；exchange-core 路径打通");
            assertEquals(10001L, result.getOrderCommand().getUid());
        }
    }
}
