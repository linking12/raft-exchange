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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;
import org.springframework.util.StreamUtils;

import com.binance.platform.common.EnvUtil;
import com.netflix.appinfo.ApplicationInfoManager;
import com.netflix.appinfo.InstanceInfo;
import com.netflix.discovery.CacheRefreshedEvent;
import com.netflix.discovery.EurekaClient;
import com.netflix.discovery.EurekaEvent;

import javax.annotation.PostConstruct;

@Component
public class ClusterDiscoveryByEureka {
    private static final Logger LOGGER = LoggerFactory.getLogger(ClusterDiscoveryByEureka.class);

    private static final String RAFTPORT = "raft.port";

    private String jgroupResources;

    @Autowired
    private ApplicationInfoManager applicationInfoManager;

    @Autowired
    private EurekaClient eurekaClient;

    @Value("${spring.application.name}")
    private String appName;

    @Value("${spring.cloud.client.ip-address}")
    private String localHost;

    @Value("${raft.port:7800}")
    private String jgroupPort;

    @Value("${raft.init-nodes:1}")
    private Integer startupNodes;

    private List<String> lastClusterHostAndPorts;

    private volatile String lastAppsHashCode;

    @PostConstruct
    public void init() {
        applicationInfoManager.registerAppMetadata(Collections.singletonMap(RAFTPORT, jgroupPort));
        this.eurekaClient.registerEventListener(this::onEurekaEvent);
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
