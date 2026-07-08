package com.binance.raftexchange.server.raft.jraft;

import com.binance.raftexchange.client.CommandResultView;
import com.binance.raftexchange.client.ExchangeApi;
import com.binance.raftexchange.client.SingleUserReportResultView;
import com.binance.raftexchange.stubs.response.CommandResultCode;

import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;

import java.math.BigDecimal;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * 三节点 raft 集群 E2E：fork 3 个子 JVM 跑真集群，客户端走真 gRPC 进去。 验证 leader 选举 + 写读链路 + kill follower 后 quorum (2/3) 仍工作。 手动跑：mvn -pl
 * raft-exchange-server test -Dtest=RaftClusterReadWriteE2ETest
 * -Djunit.jupiter.conditions.deactivate='org.junit.*DisabledCondition'
 */
@Disabled("Slow E2E (~20s): fork 3 JVMs + leader election. 手动跑回归用。")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class RaftClusterReadWriteE2ETest {

    private static final int USDT_ID = 2;
    private static final int USDT_DIGIT = 6;
    private static final long UID_1 = 10_001L;
    private static final long UID_2 = 10_002L;

    /** 写命令在 3 节点首次提交会有 raft 复制 + 状态稳定时间，预算 15s 远超过客户端默认的 2s。 */
    private static final long WRITE_TIMEOUT_SEC = 15;
    private static final long READ_TIMEOUT_SEC = 5;

    private MultiNodeRaftHarness harness;
    private ExchangeApi api;

    @BeforeAll
    void startCluster() throws Exception {
        harness = MultiNodeRaftHarness.startCluster(MultiNodeRaftHarness.DEFAULT_CLUSTER_SIZE);
        // 客户端连任一节点的 gRPC 端口；bootstrapLeaderChannel 会自动找到真 leader
        api = ExchangeApi.connect("127.0.0.1", harness.seedGrpcPort());
        // 让 leader 选举后的 gRPC 服务 / state machine 稳定一秒再做第一笔写
        Thread.sleep(1000);
    }

    private CommandResultCode await(CompletableFuture<CommandResultView> f) throws Exception {
        return f.get(WRITE_TIMEOUT_SEC, TimeUnit.SECONDS).getResultCode();
    }

    @AfterAll
    void stopCluster() throws Exception {
        try {
            if (api != null)
                api.close();
        } catch (Exception ignored) {
        }
        if (harness != null)
            harness.close();
    }

    @Test
    @Order(1)
    @DisplayName("3 节点集群选出 leader")
    void clusterElectsLeader() throws Exception {
        int leader = harness.currentLeaderIndex();
        assertNotEquals(-1, leader, "应该已经选出 leader");
        System.out.println("[test] leader index = " + leader);
    }

    @Test
    @Order(2)
    @DisplayName("写命令通过 leader 提交：addCurrency / addUser / adjustBalance")
    void writesGoThroughLeader() throws Exception {
        assertEquals(CommandResultCode.SUCCESS, await(api.addCurrencyAsync(USDT_ID, "USDT", USDT_DIGIT)));
        assertEquals(CommandResultCode.SUCCESS, await(api.addUserAsync(UID_1)));
        assertEquals(CommandResultCode.SUCCESS, await(api.addUserAsync(UID_2)));
        assertEquals(CommandResultCode.SUCCESS,
            await(api.adjustUserBalanceAsync(UID_1, /* txId */ 1L, USDT_ID, 5000.0)));
    }

    @Test
    @Order(3)
    @DisplayName("读查询走只读路径：queryUserReport 返回写入的余额")
    void readsReturnLatestState() throws Exception {
        SingleUserReportResultView view = api.queryUserReport(UID_1).get(READ_TIMEOUT_SEC, TimeUnit.SECONDS);
        assertNotNull(view);
        BigDecimal usdtBalance = view.getAccounts().get(USDT_ID);
        assertNotNull(usdtBalance, "UID_1 应有 USDT 余额");
        assertEquals(0, usdtBalance.compareTo(new BigDecimal("5000")), "余额 = 5000");
    }

    @Test
    @Order(4)
    @DisplayName("kill 1 个非 leader follower：quorum (2/3) 仍工作，写 + 读继续 OK")
    void quorumStillWorksAfterFollowerKilled() throws Exception {
        int leader = harness.currentLeaderIndex();
        int followerToKill = (leader + 1) % harness.clusterSize(); // 挑一个非 leader

        System.out.println("[test] killing follower index = " + followerToKill);
        harness.killNode(followerToKill);
        // 给集群一点时间 detect peer 离线
        Thread.sleep(2000);

        // 还能写：再充值一次
        assertEquals(CommandResultCode.SUCCESS,
            await(api.adjustUserBalanceAsync(UID_1, /* txId */ 2L, USDT_ID, 1000.0)));

        // 还能读：余额变成 6000
        SingleUserReportResultView view = api.queryUserReport(UID_1).get(READ_TIMEOUT_SEC, TimeUnit.SECONDS);
        BigDecimal usdtBalance = view.getAccounts().get(USDT_ID);
        assertEquals(0, usdtBalance.compareTo(new BigDecimal("6000")), "余额 = 5000 + 1000");
    }
}
