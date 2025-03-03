package com.binance.raftexchange.server.raft;

import static com.binance.platform.common.EurekaConstants.EUREKA_METADATA_FLOWFLAG;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.jgroups.JChannel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.util.StreamUtils;

import com.binance.platform.common.EnvUtil;
import com.netflix.appinfo.ApplicationInfoManager;
import com.netflix.appinfo.InstanceInfo;
import com.netflix.discovery.CacheRefreshedEvent;
import com.netflix.discovery.EurekaClient;
import com.netflix.discovery.EurekaEvent;

public class RaftClusterDiscovery {
    private static final Logger LOGGER = LoggerFactory.getLogger(RaftClusterDiscovery.class);
    private static final String DEFAULT_JGROUPRAFT_CONFIG = "jgroups-raft.xml";
    private static final String RAFT_PORT = "raft.port";
    private static final String GRPC_PORT = "grpc.port";

    private final String localHost;
    private final String jgroupPort;
    private final String jgroupClusterName;
    private final int startupNodes;
    private final EurekaClient eurekaClient;

    private String jgroupResources;
    private String lastAppsHashCode;
    private List<String> lastClusterHostAndPorts;
    private volatile List<String> raftWorkers;

    public RaftClusterDiscovery(EurekaClient eurekaClient) {
        this.jgroupPort = System.getProperty(RAFT_PORT, "7800");
        this.startupNodes = Integer.parseInt(System.getProperty("raft.startupNodes", "3"));
        ApplicationInfoManager applicationInfoManager = eurekaClient.getApplicationInfoManager();

        HashMap<String, String> meta = new HashMap<>();
        meta.put(RAFT_PORT, jgroupPort);
        meta.put(GRPC_PORT, System.getProperty("grpc.port", "5001"));
        applicationInfoManager.registerAppMetadata(meta);

        eurekaClient.registerEventListener(this::onEurekaEvent);
        this.eurekaClient = eurekaClient;
        this.localHost = applicationInfoManager.getInfo().getIPAddr();
        this.jgroupClusterName = applicationInfoManager.getInfo().getAppName();
        try {
            this.jgroupResources = StreamUtils.copyToString(new PathMatchingResourcePatternResolver()
                .getResource("classpath:" + DEFAULT_JGROUPRAFT_CONFIG).getInputStream(), StandardCharsets.UTF_8);
        } catch (Throwable e) {
            // ignore
        }
    }

    public String getJgroupClusterName() {
        return this.jgroupClusterName;
    }

    private void onEurekaEvent(EurekaEvent event) {
        if (event instanceof CacheRefreshedEvent) {
            String appsHashCode = this.eurekaClient.getApplications().getAppsHashCode();
            if (!Objects.equals(this.lastAppsHashCode, appsHashCode)) {
                List<InstanceInfo> clusterInstanceList = eurekaClient.getApplication(jgroupClusterName).getInstances();
                List<String> clusterHostAndPort = clusterInstanceList.stream()
                    .filter(instance -> (instance.getMetadata().containsKey(RAFT_PORT) && StringUtils
                        .equals(instance.getMetadata().get(EUREKA_METADATA_FLOWFLAG), EnvUtil.getFlowFlag())))
                    .map(
                        instance -> String.format("%s[%s]", instance.getIPAddr(), instance.getMetadata().get(RAFT_PORT)))
                    .collect(Collectors.toList());
                this.raftWorkers = clusterInstanceList.stream()
                        .filter(instance -> (instance.getMetadata().containsKey(GRPC_PORT) && StringUtils.equals(instance.getMetadata().get(EUREKA_METADATA_FLOWFLAG), EnvUtil.getFlowFlag())))
                        .map(instance -> String.format("%s[%s]", instance.getIPAddr(), instance.getMetadata().get(GRPC_PORT)))
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

    public String raftMemberCluster() {
        if (lastClusterHostAndPorts == null || lastClusterHostAndPorts.size() < startupNodes) {
            return null;
        }
        return String.join(",", lastClusterHostAndPorts);
    }

    public String raftCurrentMember() {
        return String.format("%s[%s]", this.localHost, this.jgroupPort);
    }

    public List<String> raftWorkers() {
        return Collections.unmodifiableList(raftWorkers);
    }

    public JChannel createJChannel(String raftMemberCluster, String raftCurrentMember) {
        JChannel jChannel = null;
        try {
            if (StringUtils.isNotBlank(raftMemberCluster)) {
                String realJChannelParam = StringUtils.replaceEach(//
                    this.jgroupResources, //
                    new String[] { //
                        "replace_bindlocalhost", //
                        "replace_bindport", //
                        "replace_hostcluster", //
                        "replace_raftmember_cluster", //
                        "replace_raftmember_current"//
                    }, //
                    new String[] { //
                        localHost, //
                        jgroupPort, //
                        raftMemberCluster, //
                        raftMemberCluster, //
                        raftCurrentMember} //
                );
                jChannel = new JChannel(new ByteArrayInputStream(realJChannelParam.getBytes(StandardCharsets.UTF_8)));
                jChannel.setDiscardOwnMessages(true);
                return jChannel;
            }

        } catch (Throwable e) {
            LOGGER.error("init jgroups error", e);
        }
        return jChannel;
    }

}
