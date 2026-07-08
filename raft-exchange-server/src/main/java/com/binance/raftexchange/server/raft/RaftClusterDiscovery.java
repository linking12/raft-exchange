package com.binance.raftexchange.server.raft;

import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;

import com.netflix.appinfo.ApplicationInfoManager;
import com.netflix.appinfo.InstanceInfo;
import com.netflix.discovery.CacheRefreshedEvent;
import com.netflix.discovery.EurekaClient;
import com.netflix.discovery.EurekaEvent;
import com.netflix.discovery.shared.Application;
import com.netflix.discovery.shared.Applications;

public class RaftClusterDiscovery {
    private static final Logger LOGGER = LoggerFactory.getLogger(RaftClusterDiscovery.class);
    public static final String EUREKA_METADATA_FLOWFLAG = "flowflag";
    public static final String EUREKA_METADATA_FLOWFLAG_ALL = "eureka.instance.metadataMap." + EUREKA_METADATA_FLOWFLAG;

    private static final String RAFT_PORT = "raft.port";
    private static final String GRPC_PORT = "grpc.port";
    private static final String HTTP_PORT = "server.port";
    private static final String MGMT_PORT = "management.server.port";

    private final String localHost;
    private final String raftPort;
    private final String raftClusterName;
    private final int startupNodes;
    private final EurekaClient eurekaClient;
    private final String flowflag;

    private String lastAppsHashCode;
    // raftIP:raftPort -> grpcIP:grpcPort
    private Map<String, String> raftToGrpcPeerMap = Collections.emptyMap();
    // raftIP:raftPort -> httpIP:httpPort (业务 HTTP 端口, server.port, eg. 8080)
    private Map<String, String> raftToHttpPortMap = Collections.emptyMap();
    // raftIP:raftPort -> mgmtIP:mgmtPort (Spring actuator 管理端口, management.server.port, eg. 28081)
    private Map<String, String> raftToMgmtPortMap = Collections.emptyMap();

    public RaftClusterDiscovery(EurekaClient eurekaClient, Environment env) {
        this.startupNodes = Integer.parseInt(System.getProperty("raftexchange.cluster.startupNodes", "3"));
        ApplicationInfoManager applicationInfoManager = eurekaClient.getApplicationInfoManager();
        Map<String, String> meta = new HashMap<String, String>();
        meta.put(RAFT_PORT, env.getProperty(RAFT_PORT, "7800"));
        meta.put(GRPC_PORT, env.getProperty(GRPC_PORT, "5001"));
        meta.put(HTTP_PORT, env.getProperty(HTTP_PORT, "8080"));
        meta.put(MGMT_PORT, env.getProperty(MGMT_PORT, "28081"));
        applicationInfoManager.registerAppMetadata(meta);
        eurekaClient.registerEventListener(this::onEurekaEvent);
        this.eurekaClient = eurekaClient;
        this.flowflag = env.getProperty(EUREKA_METADATA_FLOWFLAG_ALL);;
        this.localHost = applicationInfoManager.getInfo().getIPAddr();
        this.raftPort = meta.get(RAFT_PORT);
        this.raftClusterName = applicationInfoManager.getInfo().getAppName();
    }

    public String getRaftClusterName() {
        return this.raftClusterName;
    }

    private void onEurekaEvent(EurekaEvent event) {
        if (!(event instanceof CacheRefreshedEvent))
            return;
        try {
            refreshCluster();
        } catch (Exception e) {
            LOGGER.warn("Failed to refresh raft cluster from Eureka", e);
        }
    }

    private void refreshCluster() {
        Applications applications = eurekaClient.getApplications();
        if (applications == null)
            return;
        String appsHashCode = applications.getAppsHashCode();
        if (Objects.equals(this.lastAppsHashCode, appsHashCode))
            return;
        Application app = eurekaClient.getApplication(raftClusterName);
        if (app == null)
            return;
        applyClusterSnapshot(app.getInstances());
        this.lastAppsHashCode = appsHashCode;
    }

    private void applyClusterSnapshot(List<InstanceInfo> instances) {
        this.raftToGrpcPeerMap = buildPeerMap(instances, GRPC_PORT, flowflag);
        this.raftToHttpPortMap = buildPeerMap(instances, HTTP_PORT, flowflag);
        this.raftToMgmtPortMap = buildPeerMap(instances, MGMT_PORT, flowflag);
        LOGGER.info("update last clusters to {}", this.raftToGrpcPeerMap.keySet());
    }

    public Map<String, String> raftToGrpcPeerMap() {
        return Collections.unmodifiableMap(raftToGrpcPeerMap);
    }

    public Map<String, String> raftToHttpPortMap() {
        return Collections.unmodifiableMap(raftToHttpPortMap);
    }

    public Map<String, String> raftToMgmtPortMap() {
        return Collections.unmodifiableMap(raftToMgmtPortMap);
    }

    public String raftMemberCluster() {
        if (raftToGrpcPeerMap.size() < startupNodes) {
            return null;
        }
        return String.join(",", raftToGrpcPeerMap.keySet());
    }

    public String raftCurrentMember() {
        return formatPeer(this.localHost, this.raftPort);
    }

    /** grpcPort → 同节点其他端口的反查表，给 actuator 列拓扑用。 */
    public Map<Integer, ExtraPorts> grpcPortToExtraPorts() {
        Map<Integer, ExtraPorts> result = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : raftToGrpcPeerMap.entrySet()) {
            String raftPeer = e.getKey();
            result.put(portFromPeer(e.getValue()), new ExtraPorts(portFromPeer(raftPeer),
                portFromPeer(raftToHttpPortMap.get(raftPeer)), portFromPeer(raftToMgmtPortMap.get(raftPeer))));
        }
        return result;
    }

    private static int portFromPeer(String peer) {
        return peer != null ? Integer.parseInt(peer.split(":")[1]) : -1;
    }

    private Map<String, String> buildPeerMap(List<InstanceInfo> instances, String portKey, String flowflag) {
        return instances.stream()
            .filter(inst -> inst.getMetadata().containsKey(portKey)
                && StringUtils.equals(inst.getMetadata().get(EUREKA_METADATA_FLOWFLAG), flowflag))
            .collect(Collectors.toMap(inst -> formatPeer(inst.getIPAddr(), inst.getMetadata().get(RAFT_PORT)),
                inst -> formatPeer(inst.getIPAddr(), inst.getMetadata().get(portKey)), (v1, v2) -> v1));
    }

    private String formatPeer(String host, String port) {
        return String.format("%s:%s", host, port);
    }

    /** 给定 grpcPort，找回同节点的 raft / http / mgmt 端口。 */
    public record ExtraPorts(int raft, int http, int mgmt) {
        public static final ExtraPorts UNKNOWN = new ExtraPorts(-1, -1, -1);
    }
}
