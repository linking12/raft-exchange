package com.binance.raftexchange.server.raft;

import static com.binance.platform.common.EurekaConstants.EUREKA_METADATA_FLOWFLAG;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.binance.platform.common.EnvUtil;
import com.netflix.appinfo.ApplicationInfoManager;
import com.netflix.appinfo.InstanceInfo;
import com.netflix.discovery.CacheRefreshedEvent;
import com.netflix.discovery.EurekaClient;
import com.netflix.discovery.EurekaEvent;

public class RaftClusterDiscovery {
    private static final Logger LOGGER = LoggerFactory.getLogger(RaftClusterDiscovery.class);
    private static final String RAFT_PORT = "raft.port";
    private static final String GRPC_PORT = "grpc.port";

    private final String localHost;
    private final String raftPort;
    private final String raftClusterName;
    private final int startupNodes;
    private final EurekaClient eurekaClient;

    private String lastAppsHashCode;
    private List<String> lastClusterHostAndPorts;
    private List<String> raftWorkers;

    public RaftClusterDiscovery(EurekaClient eurekaClient) {
        this.raftPort = System.getProperty(RAFT_PORT, "7800");
        this.startupNodes = Integer.parseInt(System.getProperty("raft.startupNodes", "3"));
        ApplicationInfoManager applicationInfoManager = eurekaClient.getApplicationInfoManager();

        Map<String, String> meta = new HashMap<>();
        meta.put(RAFT_PORT, raftPort);
        meta.put(GRPC_PORT, System.getProperty(GRPC_PORT, "5001"));
        applicationInfoManager.registerAppMetadata(meta);

        eurekaClient.registerEventListener(this::onEurekaEvent);
        this.eurekaClient = eurekaClient;
        this.localHost = applicationInfoManager.getInfo().getIPAddr();
        this.raftClusterName = applicationInfoManager.getInfo().getAppName();
    }

    public String getRaftClusterName() {
        return this.raftClusterName;
    }

    private void onEurekaEvent(EurekaEvent event) {
        if (event instanceof CacheRefreshedEvent) {
            String appsHashCode = this.eurekaClient.getApplications().getAppsHashCode();
            if (!Objects.equals(this.lastAppsHashCode, appsHashCode)) {
                List<InstanceInfo> clusterInstanceList = eurekaClient.getApplication(raftClusterName).getInstances();
                List<String> clusterHostAndPort = clusterInstanceList.stream()
                    .filter(instance -> (instance.getMetadata().containsKey(RAFT_PORT) && StringUtils
                        .equals(instance.getMetadata().get(EUREKA_METADATA_FLOWFLAG), EnvUtil.getFlowFlag())))
                    .map(instance -> formatPeer(instance.getIPAddr(), instance.getMetadata().get(RAFT_PORT)))
                    .collect(Collectors.toList());

                this.raftWorkers = clusterInstanceList.stream()
                        .filter(instance -> (instance.getMetadata().containsKey(GRPC_PORT) && StringUtils.equals(instance.getMetadata().get(EUREKA_METADATA_FLOWFLAG), EnvUtil.getFlowFlag())))
                        .map(instance -> formatPeer(instance.getIPAddr(), instance.getMetadata().get(GRPC_PORT)))
                        .collect(Collectors.toList());

                if (this.lastClusterHostAndPorts == null
                    || !CollectionUtils.isEqualCollection(this.lastClusterHostAndPorts, clusterHostAndPort)) {
                    this.lastClusterHostAndPorts = clusterHostAndPort;
                    LOGGER.info("update last clusters to {}", this.lastClusterHostAndPorts);
                    this.lastAppsHashCode = appsHashCode;
                }
            }
        }
    }

    public List<String> raftWorkers() {
        return Collections.unmodifiableList(raftWorkers);
    }

    public String raftMemberCluster() {
        if (lastClusterHostAndPorts == null || lastClusterHostAndPorts.size() < startupNodes) {
            return null;
        }
        return String.join(",", lastClusterHostAndPorts);
    }

    public String raftCurrentMember() {
        return formatPeer(this.localHost, this.raftPort);
    }

    private String formatPeer(String host, String port) {
        return String.format("%s:%s", host, port);
    }
}
