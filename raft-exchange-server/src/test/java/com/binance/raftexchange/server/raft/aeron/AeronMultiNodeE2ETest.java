package com.binance.raftexchange.server.raft.aeron;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import com.binance.raftexchange.server.raft.RaftResponse;
import com.binance.raftexchange.stubs.request.ApiAddUser;
import com.binance.raftexchange.stubs.request.ApiCommand;
import com.binance.raftexchange.stubs.request.ApiNop;
import com.binance.raftexchange.stubs.response.CommandResult;
import com.binance.raftexchange.stubs.response.CommandResultCode;

/**
 * 3 节点 in-process Aeron 集群 E2E。慢测试（~20s）：3 个 MediaDriver + Archive + ConsensusModule + ServiceContainer + 3 个
 * ExchangeRuntime 同 JVM 起。占 18 个连续 UDP 端口。
 *
 * 默认 disabled，手动跑： mvn -pl raft-exchange-server test -Dtest=AeronMultiNodeE2ETest \
 * -Djunit.jupiter.conditions.deactivate='org.junit.*DisabledCondition'
 *
 * 注：受 AeronClusterContainer.doStart 的 clusterDir 计算限制，多节点同 JVM 在 non-macOS 上会用同一份 dataDir。 本 harness 通过给每节点不同
 * clusterName 绕过这个限制；生产部署每节点一个 JVM，无此问题。
 */
@Disabled("Slow e2e (~20s): 3 节点 in-process Aeron 集群。手动回归用。")
class AeronMultiNodeE2ETest {

    private static final long CMD_TIMEOUT_MS = 15_000;

    @Test
    void clusterElectsExactlyOneLeader() throws Exception {
        try (AeronMultiNodeHarness h = AeronMultiNodeHarness.startCluster(3)) {
            int leader = h.currentLeaderIndex();
            assertNotEquals(-1, leader, "应选出 leader");

            int leaderCount = 0;
            for (int i = 0; i < h.clusterSize(); i++) {
                if (h.container(i).isLeader())
                    leaderCount++;
            }
            assertEquals(1, leaderCount, "必须恰好 1 个 leader");
        }
    }

    @Test
    void nopCommandViaLeader_succeeds() throws Exception {
        try (AeronMultiNodeHarness h = AeronMultiNodeHarness.startCluster(3)) {
            int leader = h.currentLeaderIndex();
            ApiCommand cmd = ApiCommand.newBuilder().setTimestamp(System.currentTimeMillis())
                .setNop(ApiNop.getDefaultInstance()).build();

            RaftResponse resp = h.submitAndAwait(leader, cmd.toByteArray(), CMD_TIMEOUT_MS);
            CommandResult result = CommandResult.parseFrom(resp.serializer().get());
            assertEquals(CommandResultCode.SUCCESS, result.getResultCode());
        }
    }

    @Test
    void addUserViaFollower_sdkRedirectsToLeader() throws Exception {
        try (AeronMultiNodeHarness h = AeronMultiNodeHarness.startCluster(3)) {
            int leader = h.currentLeaderIndex();
            int follower = (leader + 1) % h.clusterSize();

            ApiCommand cmd = ApiCommand.newBuilder().setTimestamp(System.currentTimeMillis())
                .setAddUser(ApiAddUser.newBuilder().setUid(20001L)).build();

            // 通过 follower 的 container 提交：AeronCluster SDK 应自动 redirect 到 leader
            RaftResponse resp = h.submitAndAwait(follower, cmd.toByteArray(), CMD_TIMEOUT_MS);
            CommandResult result = CommandResult.parseFrom(resp.serializer().get());
            assertEquals(CommandResultCode.SUCCESS, result.getOrderCommand().getResultCode(),
                "经 follower 入口，SDK 自动转 leader 后仍应 SUCCESS");
        }
    }
}
