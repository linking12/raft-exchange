package com.binance.raftexchange.server.raft.aeron;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import com.binance.raftexchange.client.CommandResultView;
import com.binance.raftexchange.client.ExchangeApi;
import com.binance.raftexchange.stubs.response.CommandResultCode;

/**
 * Smoke test 对接外部已起的 3 节点 aeron 集群（默认 127.0.0.1:5001/5002/5003）。 通过 leader 走真 gRPC 提交 addCurrency / addUser /
 * adjustBalance，验证 raft + 撮合链路通；用 unique uid (System.nanoTime()) 避开重复账户冲突。
 *
 * 跑： mvn -pl raft-exchange-server test -Dtest=AeronLiveClusterSmokeTest \
 * -Djunit.jupiter.conditions.deactivate='org.junit.*DisabledCondition' \ [-Dlive.host=127.0.0.1] [-Dlive.grpc=5001]
 *
 * 跑完去 curl http://127.0.0.1:28081/raft/cluster 看 state_hash_submodules 是否依然 3 节点 converged 且数字变化。
 */
@Disabled("Hits an externally-running aeron cluster — manual smoke only.")
class AeronLiveClusterSmokeTest {

    private static final String HOST = System.getProperty("live.host", "127.0.0.1");
    private static final int GRPC_PORT = Integer.parseInt(System.getProperty("live.grpc", "5001"));

    private static final int USDT_ID = 2;
    private static final int USDT_DIGIT = 6;

    private static final long WRITE_TIMEOUT_SEC = 10;

    @Test
    void smokeAddCurrencyAddUserAdjustBalance() throws Exception {
        try (ExchangeApi client = ExchangeApi.connect(HOST, GRPC_PORT)) {
            // 1. AddCurrency USDT —— 幂等命令，重复跑会返 already exists（不算硬错），所以只检查不抛
            CommandResultView addCurr =
                client.addCurrencyAsync(USDT_ID, "USDT", USDT_DIGIT).get(WRITE_TIMEOUT_SEC, TimeUnit.SECONDS);
            System.out.println("[smoke] addCurrency → " + addCurr.getResultCode());

            // 2. AddUser —— uid 用 nanoTime 派生避开历史 uid，确保首次创建必 SUCCESS
            long uid = System.nanoTime();
            CommandResultView addUser = client.addUserAsync(uid).get(WRITE_TIMEOUT_SEC, TimeUnit.SECONDS);
            System.out.println("[smoke] addUser uid=" + uid + " → " + addUser.getResultCode());
            assertEquals(CommandResultCode.SUCCESS, addUser.getResultCode());

            // 3. AdjustBalance —— 给该 user 充值 1000 USDT
            long txId = System.nanoTime();
            CommandResultView adjust =
                client.adjustUserBalanceAsync(uid, txId, USDT_ID, 1000.0).get(WRITE_TIMEOUT_SEC, TimeUnit.SECONDS);
            System.out.println("[smoke] adjustBalance uid=" + uid + " +1000 USDT → " + adjust.getResultCode());
            assertEquals(CommandResultCode.SUCCESS, adjust.getResultCode());
        }
    }
}
