package com.binance.raftexchange.client.spring;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "raftexchange.client")
public class ExchangeApiProperties {

    /** Eureka 中目标服务的 appName，对齐服务端 spring.application.name=raft-exchange。 */
    private String appName = "raft-exchange";

    /** 默认 send 超时。 */
    private Duration sendTimeout = Duration.ofSeconds(2);

    /** ExchangeClient 内部刷 leader 的间隔。 */
    private Duration nodesFlushInterval = Duration.ofMinutes(1);

    public String getAppName() {
        return appName;
    }

    public void setAppName(String appName) {
        this.appName = appName;
    }

    public Duration getSendTimeout() {
        return sendTimeout;
    }

    public void setSendTimeout(Duration sendTimeout) {
        this.sendTimeout = sendTimeout;
    }

    public Duration getNodesFlushInterval() {
        return nodesFlushInterval;
    }

    public void setNodesFlushInterval(Duration nodesFlushInterval) {
        this.nodesFlushInterval = nodesFlushInterval;
    }
}
