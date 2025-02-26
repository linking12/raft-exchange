package com.binance.raftexchange.server.raft;

import static com.binance.platform.common.EurekaConstants.EUREKA_METADATA_FLOWFLAG;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
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
import com.netflix.appinfo.InstanceInfo;
import com.netflix.discovery.CacheRefreshedEvent;
import com.netflix.discovery.EurekaClient;
import com.netflix.discovery.EurekaEvent;

public class ClusterDiscoveryByEureka {
    private static final Logger LOGGER = LoggerFactory.getLogger(ClusterDiscoveryByEureka.class);

    private static final String RAFTPORT = "raft.port";

    private String jgroupResources;
    private final EurekaClient eurekaClient;
    private final String appName;
    private final String localHost;
    private final String jgroupPort;
    private final int startupNodes;

    private List<String> lastClusterHostAndPorts;

    private volatile String lastAppsHashCode;

    public ClusterDiscoveryByEureka(EurekaClient eurekaClient, String appName, String localHost, String jgroupPort, int startupNodes) {
        this.eurekaClient = eurekaClient;
        this.appName = appName;
        this.localHost = localHost;
        this.jgroupPort = jgroupPort;
        this.startupNodes = startupNodes;
        this.eurekaClient.registerEventListener(this::onEurekaEvent);
        this.eurekaClient.getApplicationInfoManager().registerAppMetadata(Collections.singletonMap(RAFTPORT, jgroupPort));
        try {
            this.jgroupResources = StreamUtils.copyToString(new PathMatchingResourcePatternResolver()
                    .getResource("classpath:jgroups-raft.xml").getInputStream(), StandardCharsets.UTF_8);
        } catch (Throwable e) {
            // ignore
        }
    }

    private void onEurekaEvent(EurekaEvent event) {
        if (event instanceof CacheRefreshedEvent) {
            String appsHashCode = this.eurekaClient.getApplications().getAppsHashCode();
            if (!Objects.equals(this.lastAppsHashCode, appsHashCode)) {
                List<InstanceInfo> clusterInstanceList = this.eurekaClient.getApplication(this.appName).getInstances();
                List<String> clusterHostAndPort = clusterInstanceList.stream()
                    .filter(instance -> (instance.getMetadata().containsKey(RAFTPORT) && StringUtils.equals(instance.getMetadata().get(EUREKA_METADATA_FLOWFLAG), EnvUtil.getFlowFlag())))
                    .map(instance -> this.jgroupHostAndPort(instance.getIPAddr(), instance.getMetadata().get(RAFTPORT))).collect(Collectors.toList());
                if (this.lastClusterHostAndPorts == null || !CollectionUtils.isEqualCollection(this.lastClusterHostAndPorts, clusterHostAndPort)) {
                    this.lastClusterHostAndPorts = clusterHostAndPort;
                    LOGGER.info("update last clusters to {}", this.lastClusterHostAndPorts);
                    this.lastAppsHashCode = appsHashCode;
                }
            }
        }
    }

    private String jgroupHostAndPort(String host, String port) {
        return host + "[" + port + "]";
    }

    public String raftMemberCluster() {
        if (lastClusterHostAndPorts == null || lastClusterHostAndPorts.size() < startupNodes) {
            return null;
        }
        return String.join(",", lastClusterHostAndPorts);
    }

    public String raftCurrentMember() {
        return jgroupHostAndPort(this.localHost, this.jgroupPort);
    }

    public JChannel createJChannel(String raftMemberCluster, String raftCurrentMember) {
        JChannel jChannel = null;
        try {
            if (StringUtils.isNotBlank(raftMemberCluster)) {
                String realJChannelParam = StringUtils.replaceEach(//
                    this.jgroupResources, //
                    new String[] {//
                        "replace_bindlocalhost", //
                        "replace_bindport", //
                        "replace_hostcluster", //
                        "replace_raftmember_cluster", //
                        "replace_raftmember_current"//
                    }, //
                    new String[] {//
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
