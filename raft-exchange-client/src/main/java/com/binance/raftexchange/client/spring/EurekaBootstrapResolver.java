package com.binance.raftexchange.client.spring;

import com.netflix.appinfo.InstanceInfo;
import com.netflix.discovery.EurekaClient;
import com.netflix.discovery.shared.Application;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;

import java.util.List;
import java.util.Objects;

/**
 * 客户端启动时通过 Eureka 找一个 raft-exchange 实例作为 bootstrap： - 按 app-name 取实例列表 - 按 metadata.flowflag 过滤（和服务端
 * RaftClusterDiscovery 对齐） - 实例必须暴露 metadata.grpc.port Leader 跟踪仍走 ExchangeClient 的 ServerNodeService.listNodes()
 * 机制，这里只解决 bootstrap。
 */
public class EurekaBootstrapResolver {

    private static final Logger LOGGER = LoggerFactory.getLogger(EurekaBootstrapResolver.class);

    static final String METADATA_FLOWFLAG = "flowflag";
    static final String METADATA_GRPC_PORT = "grpc.port";
    static final String FLOWFLAG_ENV_KEY = "eureka.instance.metadataMap." + METADATA_FLOWFLAG;

    private final EurekaClient eurekaClient;
    private final Environment env;

    public EurekaBootstrapResolver(EurekaClient eurekaClient, Environment env) {
        this.eurekaClient = Objects.requireNonNull(eurekaClient, "eurekaClient");
        this.env = Objects.requireNonNull(env, "env");
    }

    public Endpoint resolve(String appName) {
        if (StringUtils.isBlank(appName)) {
            throw new IllegalArgumentException("appName must not be blank");
        }
        Application app = eurekaClient.getApplication(appName);
        if (app == null) {
            throw new IllegalStateException("No application found in Eureka for app=" + appName);
        }
        List<InstanceInfo> instances = app.getInstances();
        if (instances == null || instances.isEmpty()) {
            throw new IllegalStateException("No instances found in Eureka for app=" + appName);
        }
        String expectedFlowflag = env.getProperty(FLOWFLAG_ENV_KEY);
        for (InstanceInfo inst : instances) {
            if (inst.getStatus() != InstanceInfo.InstanceStatus.UP) {
                continue;
            }
            String grpcPort = inst.getMetadata().get(METADATA_GRPC_PORT);
            if (StringUtils.isBlank(grpcPort)) {
                continue;
            }
            String instFlowflag = inst.getMetadata().get(METADATA_FLOWFLAG);
            if (!StringUtils.equals(instFlowflag, expectedFlowflag)) {
                continue;
            }
            Endpoint ep = new Endpoint(inst.getIPAddr(), Integer.parseInt(grpcPort));
            LOGGER.info("Eureka bootstrap picked app={} ip={} grpcPort={} flowflag={}", appName, ep.host(), ep.port(),
                instFlowflag);
            return ep;
        }
        throw new IllegalStateException("No usable Eureka instance for app=" + appName
            + " (need status=UP + metadata.grpc.port + flowflag=" + expectedFlowflag + ")");
    }

    public static final class Endpoint {
        private final String host;
        private final int port;

        public Endpoint(String host, int port) {
            this.host = host;
            this.port = port;
        }

        public String host() {
            return host;
        }

        public int port() {
            return port;
        }
    }
}
