package com.binance.raftexchange.server.raft.jraft;

import com.alipay.sofa.jraft.Status;
import com.binance.raftexchange.server.exchange.ExchangeCalls;
import com.binance.raftexchange.server.raft.RaftNode;
import exchange.core2.core.ExchangeApi;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * 锁定原 RoleChangeEventbusConcurrencyTest 迁移到 ESM 后的并发不变量：
 *
 * <ul>
 * <li>多 listener 同时注册（含 replay）跟 onLeaderStart fanout 不丢消息</li>
 * <li>fanout 顺序 = 注册顺序（CopyOnWriteArrayList 保证）</li>
 * <li>listener 抛异常不应阻塞后续 listener（依赖 forEach 的语义；当前实现不吃异常——本测试同时也作为"如果未来加 try/catch，行为变更"的提醒）</li>
 * </ul>
 */
class JraftExchangeStateMachineRoleListenerConcurrencyTest {

    private static JraftExchangeStateMachine fresh() {
        return new JraftExchangeStateMachine(new ExchangeCalls(mock(ExchangeApi.class)));
    }

    /** 50 个 listener 并发注册：每个都至少收到 1 条 replay；后续 onLeaderStart 时每个都收到 LEADER。 */
    @Test
    void concurrentRegisterListeners_eachListenerSeesReplayThenLeader() throws Exception {
        JraftExchangeStateMachine esm = fresh();
        int listenerCount = 50;
        List<AtomicInteger> followerCounts = new ArrayList<>();
        List<AtomicInteger> leaderCounts = new ArrayList<>();
        for (int i = 0; i < listenerCount; i++) {
            followerCounts.add(new AtomicInteger());
            leaderCounts.add(new AtomicInteger());
        }

        ExecutorService pool = Executors.newFixedThreadPool(8);
        CountDownLatch allRegistered = new CountDownLatch(listenerCount);
        try {
            for (int i = 0; i < listenerCount; i++) {
                final int idx = i;
                pool.submit(() -> {
                    esm.addRoleChangeListener(nt -> {
                        if (nt == RaftNode.NodeType.LEADER)
                            leaderCounts.get(idx).incrementAndGet();
                        else
                            followerCounts.get(idx).incrementAndGet();
                    });
                    allRegistered.countDown();
                });
            }
            assertTrue(allRegistered.await(5, TimeUnit.SECONDS), "所有 listener 应在 5s 内完成注册");
        } finally {
            pool.shutdown();
        }

        // 每个 listener 注册时立刻 replay FOLLOWER
        for (int i = 0; i < listenerCount; i++) {
            assertEquals(1, followerCounts.get(i).get(), "listener[" + i + "] 注册时应收到 1 条 FOLLOWER replay");
            assertEquals(0, leaderCounts.get(i).get(), "listener[" + i + "] 注册时不该收到 LEADER");
        }

        // onLeaderStart fanout，每个都应再收 1 条 LEADER
        esm.onLeaderStart(1L);
        for (int i = 0; i < listenerCount; i++) {
            assertEquals(1, leaderCounts.get(i).get(), "listener[" + i + "] 应收到 1 条 LEADER");
        }
    }

    /** 在 onLeaderStart fanout 期间并发注册的 listener，最终也要看到当前角色（不能丢）。 */
    @Test
    void registerDuringFanout_lateRegisterStillSeesCurrentRole() throws Exception {
        JraftExchangeStateMachine esm = fresh();
        esm.onLeaderStart(1L); // 当前角色 = LEADER

        // 模拟"角色已切到 LEADER 但 listener 装配仍在进行"
        int registerCount = 20;
        ExecutorService pool = Executors.newFixedThreadPool(4);
        CountDownLatch done = new CountDownLatch(registerCount);
        List<RaftNode.NodeType> firstSeen = new ArrayList<>();
        for (int i = 0; i < registerCount; i++)
            firstSeen.add(null);

        try {
            for (int i = 0; i < registerCount; i++) {
                final int idx = i;
                pool.submit(() -> {
                    esm.addRoleChangeListener(nt -> {
                        synchronized (firstSeen) {
                            if (firstSeen.get(idx) == null)
                                firstSeen.set(idx, nt);
                        }
                    });
                    done.countDown();
                });
            }
            assertTrue(done.await(5, TimeUnit.SECONDS), "所有 listener 应在 5s 内完成注册");
        } finally {
            pool.shutdown();
        }

        // 所有 listener 注册时 replay 出来的第一条都应是 LEADER（不能是过期的 FOLLOWER）
        for (int i = 0; i < registerCount; i++) {
            assertNotNull(firstSeen.get(i), "listener[" + i + "] 应至少收到 1 条 replay");
            assertEquals(RaftNode.NodeType.LEADER, firstSeen.get(i), "listener[" + i + "] 注册时 replay 必须反映当前角色 LEADER");
        }
    }

    /** 多次 leader 角色切换：所有 listener 收到的事件顺序应一致（onLeaderStart/Stop 串行）。 */
    @Test
    void multipleRoleTransitions_listenersSeeConsistentOrder() {
        JraftExchangeStateMachine esm = fresh();
        List<RaftNode.NodeType> a = new ArrayList<>();
        List<RaftNode.NodeType> b = new ArrayList<>();
        esm.addRoleChangeListener(a::add);
        esm.addRoleChangeListener(b::add);

        esm.onLeaderStart(1L);
        esm.onLeaderStop(Status.OK());
        esm.onLeaderStart(2L);
        esm.onLeaderStop(Status.OK());

        List<RaftNode.NodeType> expected = List.of(RaftNode.NodeType.FOLLOWER, // initial replay
            RaftNode.NodeType.LEADER, RaftNode.NodeType.FOLLOWER, RaftNode.NodeType.LEADER, RaftNode.NodeType.FOLLOWER);

        assertEquals(expected, a, "listener a 看到的事件序列应跟切换顺序一致");
        assertEquals(expected, b, "listener b 看到的事件序列应跟切换顺序一致");
    }
}
