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
import com.netflix.appinfo.ApplicationInfoManager;
import com.netflix.appinfo.InstanceInfo;
import com.netflix.discovery.CacheRefreshedEvent;
import com.netflix.discovery.EurekaClient;
import com.netflix.discovery.EurekaEvent;

public class RaftClusterDiscovery {
	private static final Logger LOGGER = LoggerFactory.getLogger(RaftClusterDiscovery.class);
	private static final String DEFAULT_JGROUPRAFT_CONFIG = "jgroups-tcp.xml";
	private static final String RAFTPORT = "raft.port";

	private final String localHost;
	private final String jgroupClusterName;
	private final EurekaClient eurekaClient;

	private String jgroupPort = "7800";
	private String jgroupResources;
	private String lastAppsHashCode;
	private List<String> lastClusterHostAndPorts;

	public RaftClusterDiscovery(ApplicationInfoManager applicationInfoManager, EurekaClient eurekaClient) {
		applicationInfoManager.registerAppMetadata(Collections.singletonMap(RAFTPORT, jgroupPort));
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
						.filter(instance -> (instance.getMetadata().containsKey(RAFTPORT) && StringUtils
								.equals(instance.getMetadata().get(EUREKA_METADATA_FLOWFLAG), EnvUtil.getFlowFlag())))
						.map(instance -> String.format("%s[%s]", instance.getIPAddr(),
								instance.getMetadata().get(RAFTPORT)))
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
		return this.createJgroupCluster();
	}

	public String raftCurrentMember() {
		return String.format("%s[%s]", this.localHost, this.jgroupPort);
	}

	public JChannel createJChannel(String raftMemberCluster, String raftCurrentMember) {
		JChannel jChannel = null;
		try {
			String jgroupCluster = this.createJgroupCluster();
			if (StringUtils.isNotBlank(jgroupCluster)) {
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
								jgroupCluster, //
								raftMemberCluster, //
								raftCurrentMember } //
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

	private String createJgroupCluster() {
		int clusterSize = this.lastClusterHostAndPorts != null ? this.lastClusterHostAndPorts.size() : 0;
		if (clusterSize == 3) {
			StringBuilder clusterIpSb = new StringBuilder();
			for (int i = 0; i < clusterSize; i++) {
				String ip = this.lastClusterHostAndPorts.get(i);
				if (i == clusterSize - 1) {
					clusterIpSb.append(ip);
				} else {
					clusterIpSb.append(ip + ",");
				}
			}
			return clusterIpSb.toString();
		}
		return null;
	}

}
