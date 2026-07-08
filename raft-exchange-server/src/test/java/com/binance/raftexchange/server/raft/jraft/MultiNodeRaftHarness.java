package com.binance.raftexchange.server.raft.jraft;

import com.binance.raftexchange.stubs.api.ServerNodeServiceGrpc;
import com.binance.raftexchange.stubs.request.NodeListCommand;
import com.binance.raftexchange.stubs.response.NodeList;
import com.binance.raftexchange.stubs.response.NodeType;
import com.binance.raftexchange.stubs.response.ServerNode;
import io.grpc.ManagedChannel;
import io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder;
import org.apache.commons.io.FileUtils;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * 多节点 raft E2E harness：fork 出 N 个 {@link RaftNodeMain} 子 JVM 组成真集群（默认 3 节点）。 子进程靠 stdout 的 RAFT_NODE_READY marker
 * 通知就绪，父进程再 ListNodes 轮询 leader 选举。 需要测试 JVM 跟子进程同 classpath（mvn surefire 默认满足）。
 */
public final class MultiNodeRaftHarness implements AutoCloseable {

    /** 默认集群规模 3，能 quorum + 容错 1 节点。 */
    public static final int DEFAULT_CLUSTER_SIZE = 3;

    private final String clusterName;
    private final List<NodeProcess> nodes;
    private final File baseDir;

    public static MultiNodeRaftHarness startCluster(int size) throws Exception {
        return new MultiNodeRaftHarness(size);
    }

    private MultiNodeRaftHarness(int size) throws Exception {
        this.clusterName = "e2e-cluster-" + System.nanoTime();
        this.baseDir = Files.createTempDirectory("raft-e2e-").toFile();

        // 先一次性分配 size × 2 个空闲端口（raft + grpc）
        int[] raftPorts = freePorts(size);
        int[] grpcPorts = freePorts(size);

        // 构造 cluster 字符串（peers 用逗号分隔，grpcMap 用分号分隔）
        String peersStr =
            IntStream.range(0, size).mapToObj(i -> "127.0.0.1:" + raftPorts[i]).collect(Collectors.joining(","));
        String grpcMapStr = IntStream.range(0, size)
            .mapToObj(i -> "127.0.0.1:" + raftPorts[i] + "=127.0.0.1:" + grpcPorts[i]).collect(Collectors.joining(";"));

        // fork 子进程
        this.nodes = new ArrayList<>(size);
        try {
            for (int i = 0; i < size; i++) {
                File nodeDir = new File(baseDir, "node-" + (i + 1));
                if (!nodeDir.mkdirs()) {
                    throw new IllegalStateException("mkdir failed: " + nodeDir);
                }
                NodeProcess node = startOne(i + 1, raftPorts[i], grpcPorts[i], peersStr, grpcMapStr, nodeDir);
                nodes.add(node);
            }
            // 等子进程 READY
            for (NodeProcess node : nodes) {
                node.awaitReady(20_000);
            }
            // 等任一节点能选出 leader
            awaitLeaderElected(30_000);
        } catch (Exception e) {
            close();
            throw e;
        }
    }

    /** 任一节点的 gRPC 端口（客户端 connect 用，sniffLeader 会自动找到真 leader）。 */
    public int seedGrpcPort() {
        return nodes.get(0).grpcPort;
    }

    public int grpcPort(int nodeIndex /* 0-based */) {
        return nodes.get(nodeIndex).grpcPort;
    }

    public int clusterSize() {
        return nodes.size();
    }

    /** 杀死指定节点（0-based 下标）。后续 {@link #close()} 仍能安全收尾。 */
    public void killNode(int nodeIndex) throws Exception {
        nodes.get(nodeIndex).destroy();
    }

    /** 查询当前 leader 所在节点的 0-based 下标；找不到返回 -1。 */
    public int currentLeaderIndex() throws Exception {
        for (int i = 0; i < nodes.size(); i++) {
            NodeProcess node = nodes.get(i);
            if (!node.alive())
                continue;
            ServerNode leader = findLeader("127.0.0.1", node.grpcPort);
            if (leader != null) {
                int leaderPort = leader.getPort();
                for (int j = 0; j < nodes.size(); j++) {
                    // ServerNode 的 port 字段是 grpc port（见 RaftClusterContainer 转换）
                    if (nodes.get(j).grpcPort == leaderPort) {
                        return j;
                    }
                }
                return -1;
            }
        }
        return -1;
    }

    @Override
    public void close() {
        for (NodeProcess node : nodes) {
            try {
                node.destroy();
            } catch (Exception ignored) {
            }
        }
        try {
            if (baseDir != null && baseDir.exists()) {
                FileUtils.deleteDirectory(baseDir);
            }
        } catch (Exception ignored) {
        }
    }

    // ---------------- internals ----------------

    private NodeProcess startOne(int label, int raftPort, int grpcPort, String peersStr, String grpcMapStr,
        File workingDir) throws Exception {
        List<String> cmd = new ArrayList<>();
        cmd.add(javaBinary());
        cmd.add("-Draft.port=" + raftPort);
        cmd.add("-Dgrpc.port=" + grpcPort);
        cmd.add("-Draft.clusterName=" + clusterName);
        cmd.add("-Draft.cluster.peers=" + peersStr);
        cmd.add("-Draft.cluster.grpcMap=" + grpcMapStr);
        // 跟父进程同一份 classpath；ProcessBuilder 不会自动继承
        cmd.add("-cp");
        cmd.add(System.getProperty("java.class.path"));
        // 关掉若干 JVM warning，避免污染 stdout 上的 READY 行
        cmd.add("--add-opens=java.base/java.lang=ALL-UNNAMED");
        cmd.add("--add-opens=java.base/java.nio=ALL-UNNAMED");
        cmd.add("--add-opens=java.base/jdk.internal.misc=ALL-UNNAMED");
        cmd.add(RaftNodeMain.class.getName());

        ProcessBuilder pb = new ProcessBuilder(cmd).directory(workingDir).redirectErrorStream(true);
        Process process = pb.start();
        NodeProcess node = new NodeProcess("node-" + label, raftPort, grpcPort, process);
        node.startStdoutPump();
        return node;
    }

    private static String javaBinary() {
        String javaHome = System.getProperty("java.home");
        String osName = System.getProperty("os.name", "").toLowerCase();
        String exe = osName.contains("win") ? "java.exe" : "java";
        return javaHome + File.separator + "bin" + File.separator + exe;
    }

    private static int[] freePorts(int n) throws Exception {
        int[] ports = new int[n];
        List<ServerSocket> sockets = new ArrayList<>(n);
        try {
            for (int i = 0; i < n; i++) {
                ServerSocket s = new ServerSocket(0);
                sockets.add(s);
                ports[i] = s.getLocalPort();
            }
        } finally {
            for (ServerSocket s : sockets) {
                try {
                    s.close();
                } catch (Exception ignored) {
                }
            }
        }
        return ports;
    }

    private void awaitLeaderElected(long timeoutMs) throws Exception {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            for (NodeProcess node : nodes) {
                if (!node.alive())
                    continue;
                ServerNode leader = findLeader("127.0.0.1", node.grpcPort);
                if (leader != null) {
                    System.out.println("[harness] leader elected: " + leader.getHost() + ":" + leader.getPort());
                    return;
                }
            }
            Thread.sleep(200);
        }
        throw new IllegalStateException("no leader elected within " + timeoutMs + "ms");
    }

    private static ServerNode findLeader(String host, int grpcPort) {
        ManagedChannel ch = NettyChannelBuilder.forAddress(host, grpcPort).usePlaintext().build();
        try {
            NodeList list = ServerNodeServiceGrpc.newBlockingStub(ch).withDeadlineAfter(2, TimeUnit.SECONDS)
                .listNodes(NodeListCommand.getDefaultInstance());
            return list.getNodesList().stream().filter(n -> n.getType() == NodeType.LEADER).findFirst().orElse(null);
        } catch (Exception ignored) {
            return null;
        } finally {
            ch.shutdownNow();
        }
    }

    /** 一个子进程的句柄 + stdout 读取线程。 */
    private static final class NodeProcess {
        private final String label;
        final int raftPort;
        final int grpcPort;
        final Process process;
        private final AtomicReference<Boolean> ready = new AtomicReference<>(null);

        NodeProcess(String label, int raftPort, int grpcPort, Process process) {
            this.label = label;
            this.raftPort = raftPort;
            this.grpcPort = grpcPort;
            this.process = process;
        }

        /** 启一个守护线程把子进程 stdout / stderr 拉到父进程，并寻找 READY marker。 */
        void startStdoutPump() {
            Thread t = new Thread(() -> {
                try (BufferedReader br = new BufferedReader(
                    new InputStreamReader(Objects.requireNonNull(process.getInputStream()), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = br.readLine()) != null) {
                        System.out.println("[" + label + "] " + line);
                        if (line.startsWith("RAFT_NODE_READY")) {
                            ready.set(Boolean.TRUE);
                        }
                    }
                } catch (Exception ignored) {
                }
                ready.compareAndSet(null, Boolean.FALSE);
            }, "stdout-" + label);
            t.setDaemon(true);
            t.start();
        }

        void awaitReady(long timeoutMs) throws InterruptedException {
            long deadline = System.currentTimeMillis() + timeoutMs;
            while (System.currentTimeMillis() < deadline) {
                Boolean v = ready.get();
                if (Boolean.TRUE.equals(v))
                    return;
                if (Boolean.FALSE.equals(v) && !process.isAlive()) {
                    throw new IllegalStateException(label + " exited before READY marker");
                }
                Thread.sleep(100);
            }
            throw new IllegalStateException(label + " did not emit RAFT_NODE_READY within " + timeoutMs + "ms");
        }

        boolean alive() {
            return process.isAlive();
        }

        void destroy() throws InterruptedException {
            if (!process.isAlive())
                return;
            process.destroy();
            if (!process.waitFor(5, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                process.waitFor(2, TimeUnit.SECONDS);
            }
        }

        @SuppressWarnings("unused")
        InputStream errStream() {
            return process.getErrorStream();
        }
    }
}
