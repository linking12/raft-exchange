package com.binance.raftexchange.server.raft.aeron;

import java.io.File;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.aeron.archive.Archive;
import io.aeron.archive.ArchiveThreadingMode;
import io.aeron.archive.ArchivingMediaDriver;
import io.aeron.archive.client.AeronArchive;
import io.aeron.cluster.ConsensusModule;
import io.aeron.cluster.service.ClusteredService;
import io.aeron.cluster.service.ClusteredServiceContainer;
import io.aeron.driver.MediaDriver;
import io.aeron.driver.MinMulticastFlowControlSupplier;
import io.aeron.driver.ThreadingMode;

/** 单节点 Aeron Cluster 服务侧：{@link ArchivingMediaDriver} + {@link ConsensusModule} + {@link ClusteredServiceContainer}。 */
final class AeronClusterServer implements AutoCloseable {

    private static final Logger LOGGER = LoggerFactory.getLogger(AeronClusterServer.class);

    // basePort..basePort+5：+0 ingress / +1 consensus / +2 log / +3 catchup / +4 archive / +5 replication
    private static final int PORT_INGRESS = 0;
    private static final int PORT_CONSENSUS = 1;
    private static final int PORT_LOG = 2;
    private static final int PORT_CATCHUP = 3;
    private static final int PORT_ARCHIVE = 4;
    private static final int PORT_REPLICATE = 5;

    private final Path clusterDir;
    private final AeronClusterContainer.MemberSpec self;
    private final List<AeronClusterContainer.MemberSpec> members;
    private ClusteredService clusteredService;

    private ArchivingMediaDriver mediaDriver;
    private ConsensusModule consensusModule;
    private ClusteredServiceContainer serviceContainer;

    AeronClusterServer(Path clusterDir, AeronClusterContainer.MemberSpec self,
        List<AeronClusterContainer.MemberSpec> members) {
        this.clusterDir = clusterDir;
        this.self = self;
        this.members = members;
    }

    void setClusteredService(ClusteredService clusteredService) {
        this.clusteredService = clusteredService;
    }

    void start() {
        String aeronDir = aeronDir();
        String clusterMembers = clusterMembers();
        LOGGER.info("Aeron server starting: self={} clusterMembers={}", self, clusterMembers);
        mediaDriver = ArchivingMediaDriver.launch(mediaDriverContext(aeronDir),
            archiveContext(aeronDir, clusterDir.resolve("archive").toFile()));
        launchConsensus(aeronDir, clusterMembers);
    }

    @Override
    public void close() {
        try {
            closeConsensus();
            // 给 EOS 帧时间发到 peers 再杀 mediaDriver
            Thread.sleep(1000L);
            if (mediaDriver != null) {
                mediaDriver.close();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            LOGGER.error("ClusterServer close failed", e);
        }
    }

    void relaunchConsensus(long electionWaitMs) throws InterruptedException {
        if (mediaDriver == null) {
            throw new IllegalStateException("mediaDriver was closed; cannot relaunch consensus");
        }
        closeConsensus();
        Thread.sleep(electionWaitMs);
        launchConsensus(aeronDir(), clusterMembers());
    }

    private void launchConsensus(String aeronDir, String clusterMembers) {
        consensusModule = ConsensusModule
            .launch(consensusContext(aeronDir, clusterDir.resolve("consensus").toFile(), clusterMembers));
        serviceContainer =
            ClusteredServiceContainer.launch(serviceContext(aeronDir, clusterDir.resolve("service").toFile()));
    }

    private void closeConsensus() {
        if (serviceContainer != null) {
            serviceContainer.close();
            serviceContainer = null;
        }
        if (consensusModule != null) {
            consensusModule.close();
            consensusModule = null;
        }
    }

    private String aeronDir() {
        return clusterDir.resolve("aeron").toFile().getAbsolutePath();
    }

    private String clusterMembers() {
        return members.stream().map(this::encodeMember).collect(Collectors.joining("|"));
    }

    private MediaDriver.Context mediaDriverContext(String aeronDir) {
        return new MediaDriver.Context().aeronDirectoryName(aeronDir).threadingMode(ThreadingMode.SHARED)
            .multicastFlowControlSupplier(new MinMulticastFlowControlSupplier())
            .errorHandler(t -> logBackgroundException("MediaDriver", t));
    }

    private Archive.Context archiveContext(String aeronDir, File archiveDir) {
        return new Archive.Context().aeronDirectoryName(aeronDir).archiveDir(archiveDir)
            .controlChannel(udpEndpoint(self.host(), self.aeronBasePort() + PORT_ARCHIVE))
            .localControlChannel("aeron:ipc")
            .replicationChannel(udpEndpoint(self.host(), self.aeronBasePort() + PORT_REPLICATE))
            .recordingEventsEnabled(false).threadingMode(ArchiveThreadingMode.SHARED)
            .errorHandler(t -> logBackgroundException("Archive", t));
    }

    private ConsensusModule.Context consensusContext(String aeronDir, File consensusDir, String clusterMembers) {
        return new ConsensusModule.Context().aeronDirectoryName(aeronDir).clusterDir(consensusDir)
            .clusterMemberId(self.memberId()).clusterMembers(clusterMembers).ingressChannel("aeron:udp?term-length=64k")
            .replicationChannel(udpEndpoint(self.host(), 0)).archiveContext(localArchiveContext())
            .errorHandler(t -> logBackgroundException("ConsensusModule", t));
    }

    private ClusteredServiceContainer.Context serviceContext(String aeronDir, File serviceDir) {
        return new ClusteredServiceContainer.Context().aeronDirectoryName(aeronDir).clusterDir(serviceDir)
            .clusteredService(clusteredService).archiveContext(localArchiveContext())
            .errorHandler(t -> logBackgroundException("ServiceContainer", t));
    }

    private static void logBackgroundException(String component, Throwable t) {
        if (t instanceof io.aeron.cluster.client.ClusterEvent) {
            LOGGER.warn("{} cluster event: {}", component, t.getMessage());
        } else {
            LOGGER.error("{} error", component, t);
        }
    }

    private static AeronArchive.Context localArchiveContext() {
        return new AeronArchive.Context().controlRequestChannel("aeron:ipc").controlResponseChannel("aeron:ipc");
    }

    private String encodeMember(AeronClusterContainer.MemberSpec member) {
        return member.memberId() + "," + hostPort(member, PORT_INGRESS) + "," + hostPort(member, PORT_CONSENSUS) + ","
            + hostPort(member, PORT_LOG) + "," + hostPort(member, PORT_CATCHUP) + "," + hostPort(member, PORT_ARCHIVE);
    }

    private static String hostPort(AeronClusterContainer.MemberSpec member, int portOffset) {
        return member.host() + ":" + (member.aeronBasePort() + portOffset);
    }

    private static String udpEndpoint(String host, int port) {
        return "aeron:udp?endpoint=" + host + ":" + port;
    }
}
