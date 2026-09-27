package com.binance.raftexchange.server.raft.jraft;

import com.alipay.sofa.jraft.Closure;
import com.alipay.sofa.jraft.Iterator;
import com.alipay.sofa.jraft.JRaftUtils;
import com.alipay.sofa.jraft.Status;
import com.alipay.sofa.jraft.Node;
import com.alipay.sofa.jraft.RaftGroupService;
import com.alipay.sofa.jraft.conf.Configuration;
import com.alipay.sofa.jraft.core.StateMachineAdapter;
import com.alipay.sofa.jraft.entity.PeerId;
import com.alipay.sofa.jraft.entity.Task;
import com.alipay.sofa.jraft.option.NodeOptions;
import com.alipay.sofa.jraft.option.RaftOptions;
import org.apache.commons.io.FileUtils;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.net.ServerSocket;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 跳过 JraftExchangeStateMachine / ExchangeCore 整个栈，直接用一个最小的 StateMachine 在 in-process 起单节点 jraft。 验证 Task.apply →
 * StateMachine.onApply → Closure.run 的核心 raft 契约（applyBatch / disruptor / 数据落盘）。 无 exchange-core / gRPC / Kafka 依赖。
 */
class JRaftSingleNodeApplyTest {

    @Test
    void singleNodeRaft_taskApply_invokesStateMachineOnApplyWithClosure() throws Exception {
        int raftPort = freePort();
        String selfPeer = "127.0.0.1:" + raftPort;
        File dataDir = new File(System.getProperty("user.dir"), "jraft-apply-test-" + System.nanoTime());

        RecordingStateMachine fsm = new RecordingStateMachine();
        RaftGroupService rgs = startRaft(fsm, raftPort, selfPeer, dataDir);

        try {
            Node node = rgs.getRaftNode();
            // 等单节点自选 leader
            assertTrue(waitForLeader(node, 10_000), "10s 内必须选出 leader");

            // 提交 3 个 Task；每个 Closure 在 onApply 触发后 run
            CountDownLatch latch = new CountDownLatch(3);
            byte[][] payloads = {"hello".getBytes(), "world".getBytes(), "raft".getBytes()};
            for (byte[] p : payloads) {
                node.apply(new Task(ByteBuffer.wrap(p), status -> {
                    assertTrue(status.isOk(), "Closure 必须收到 OK status");
                    latch.countDown();
                }));
            }

            boolean fired = latch.await(10, TimeUnit.SECONDS);
            assertTrue(fired, "10s 内 3 个 Task 的 Closure 必须 run；当前 onApply 收到 " + fsm.appliedBytes.size()
                + " 条 / Closure run 次数 = " + (3 - latch.getCount()));
            assertEquals(3, fsm.appliedBytes.size(), "onApply 必须见到 3 条 log 数据");
            for (int i = 0; i < 3; i++) {
                assertArrayEquals(payloads[i], fsm.appliedBytes.get(i), "payload " + i + " 必须原样穿透 raft log 到 onApply");
            }
        } finally {
            rgs.shutdown();
            rgs.join();
            FileUtils.deleteDirectory(dataDir);
        }
    }

    // ---- helpers ----

    private static RaftGroupService startRaft(StateMachineAdapter fsm, int raftPort, String selfPeer, File dataDir)
        throws Exception {
        FileUtils.forceMkdir(dataDir);
        PeerId peerId = JRaftUtils.getPeerId(selfPeer);
        Configuration conf = JRaftUtils.getConfiguration(selfPeer);
        NodeOptions opts = new NodeOptions();
        opts.setFsm(fsm);
        opts.setLogUri(new File(dataDir, "log").getAbsolutePath());
        opts.setSnapshotUri(new File(dataDir, "snapshot").getAbsolutePath());
        opts.setRaftMetaUri(new File(dataDir, "meta").getAbsolutePath());
        opts.setInitialConf(conf);
        opts.setDisableCli(true);
        opts.setSnapshotIntervalSecs(28800); // 不主动 snapshot 干扰测试
        // applyBatch 默认 32 — 3 个 task 会一直攒着不 flush；强制 1 让每个 task 立即 apply
        RaftOptions ro = new RaftOptions();
        ro.setApplyBatch(1);
        ro.setSync(false);
        opts.setRaftOptions(ro);

        String clusterName = "jraft-apply-test-" + System.nanoTime();
        RaftGroupService rgs = new RaftGroupService(clusterName, peerId, opts);
        Node node = rgs.start();
        node.resetPeers(conf);
        return rgs;
    }

    private static boolean waitForLeader(Node node, long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (node.isLeader())
                return true;
            Thread.sleep(50);
        }
        return false;
    }

    private static int freePort() throws Exception {
        try (ServerSocket s = new ServerSocket(0)) {
            return s.getLocalPort();
        }
    }

    /** 最小 StateMachine：把每条 log 的 byte[] 收集起来，Closure.run(OK) 由 jraft 自动调。 */
    private static final class RecordingStateMachine extends StateMachineAdapter {
        final List<byte[]> appliedBytes = new ArrayList<>();

        @Override
        public void onApply(Iterator iter) {
            while (iter.hasNext()) {
                ByteBuffer data = iter.getData();
                byte[] copy = new byte[data.remaining()];
                data.duplicate().get(copy);
                appliedBytes.add(copy);
                Closure done = iter.done();
                if (done != null)
                    done.run(Status.OK()); // jraft 框架不会自动调；FSM 自己负责通知 client
                iter.next();
            }
        }
    }
}
