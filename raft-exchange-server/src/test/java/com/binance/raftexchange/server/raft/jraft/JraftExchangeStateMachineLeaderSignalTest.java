package com.binance.raftexchange.server.raft.jraft;

import com.alipay.sofa.jraft.Status;
import com.binance.raftexchange.server.exchange.ExchangeCalls;
import com.binance.raftexchange.server.raft.RaftNode;
import exchange.core2.core.ExchangeApi;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * JraftExchangeStateMachine 的 onLeaderStart / onLeaderStop 是 jraft 角色切换入口； ESM 必须同时 (1) 更新内部 leaderTerm（驱动 isLeader()）和
 * (2) fanout 给所有 RoleChangeListener。
 */
class JraftExchangeStateMachineLeaderSignalTest {

    private static JraftExchangeStateMachine fresh() {
        return new JraftExchangeStateMachine(new ExchangeCalls(mock(ExchangeApi.class)));
    }

    @Test
    void initial_isFollower() {
        assertFalse(fresh().isLeader(), "构造后未收到 onLeaderStart 之前应为 follower");
    }

    @Test
    void onLeaderStart_setsLeaderAndNotifiesListeners() {
        JraftExchangeStateMachine esm = fresh();
        List<RaftNode.NodeType> received = new ArrayList<>();
        esm.addRoleChangeListener(received::add); // initial replay → FOLLOWER

        esm.onLeaderStart(7L);

        assertTrue(esm.isLeader(), "onLeaderStart 后 isLeader() 应为 true");
        assertEquals(List.of(RaftNode.NodeType.FOLLOWER, RaftNode.NodeType.LEADER), received, "必须 fan-out 给 listener");
    }

    @Test
    void onLeaderStop_clearsLeaderAndNotifiesListeners() {
        JraftExchangeStateMachine esm = fresh();
        esm.onLeaderStart(3L);

        List<RaftNode.NodeType> afterLeader = new ArrayList<>();
        esm.addRoleChangeListener(afterLeader::add); // register 时回放 LEADER

        esm.onLeaderStop(Status.OK());

        assertFalse(esm.isLeader(), "onLeaderStop 后 isLeader() 必须为 false");
        assertEquals(List.of(RaftNode.NodeType.LEADER, RaftNode.NodeType.FOLLOWER), afterLeader,
            "stop 必须发 FOLLOWER 给 listener");
    }

    @Test
    void leaderTermSetBeforeNotify_listenersSeeFreshIsLeader() {
        // listener 收到 LEADER 通知时，esm.isLeader() 必须已是 true（先 set leaderTerm 后 fanout）
        JraftExchangeStateMachine esm = fresh();

        boolean[] esmLeaderAtCallback = {false};
        esm.addRoleChangeListener(role -> {
            if (role == RaftNode.NodeType.LEADER) {
                esmLeaderAtCallback[0] = esm.isLeader();
            }
        });

        esm.onLeaderStart(1L);

        assertTrue(esmLeaderAtCallback[0], "listener 收到 LEADER 时，ESM.isLeader() 必须已经返回 true（先 set 后 fanout）");
    }
}
