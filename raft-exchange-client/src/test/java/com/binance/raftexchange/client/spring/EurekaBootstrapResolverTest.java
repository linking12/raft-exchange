package com.binance.raftexchange.client.spring;

import com.netflix.appinfo.InstanceInfo;
import com.netflix.discovery.EurekaClient;
import com.netflix.discovery.shared.Application;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class EurekaBootstrapResolverTest {

    private static final String APP = "raft-exchange";

    @Test
    @DisplayName("UP 实例 + grpc.port + flowflag 匹配 → 解析成功")
    void resolvesHealthyInstance() {
        InstanceInfo up =
            instance("10.0.0.1", InstanceInfo.InstanceStatus.UP, Map.of("grpc.port", "5001", "flowflag", "qa"));
        EurekaClient client = mockClient(up);
        MockEnvironment env = new MockEnvironment();
        env.setProperty("eureka.instance.metadataMap.flowflag", "qa");

        EurekaBootstrapResolver.Endpoint ep = new EurekaBootstrapResolver(client, env).resolve(APP);
        assertEquals("10.0.0.1", ep.host());
        assertEquals(5001, ep.port());
    }

    @Test
    @DisplayName("flowflag 不匹配的实例被跳过")
    void skipsMismatchedFlowflag() {
        InstanceInfo wrongFlag =
            instance("10.0.0.1", InstanceInfo.InstanceStatus.UP, Map.of("grpc.port", "5001", "flowflag", "prod"));
        InstanceInfo right =
            instance("10.0.0.2", InstanceInfo.InstanceStatus.UP, Map.of("grpc.port", "5002", "flowflag", "qa"));
        EurekaClient client = mockClient(wrongFlag, right);
        MockEnvironment env = new MockEnvironment();
        env.setProperty("eureka.instance.metadataMap.flowflag", "qa");

        EurekaBootstrapResolver.Endpoint ep = new EurekaBootstrapResolver(client, env).resolve(APP);
        assertEquals("10.0.0.2", ep.host());
        assertEquals(5002, ep.port());
    }

    @Test
    @DisplayName("env 里没设 flowflag 时，只匹配实例 metadata 也无 flowflag 的（对齐服务端 StringUtils.equals 语义）")
    void nullFlowflagOnlyMatchesAbsent() {
        InstanceInfo withFlag =
            instance("10.0.0.1", InstanceInfo.InstanceStatus.UP, Map.of("grpc.port", "5001", "flowflag", "qa"));
        InstanceInfo noFlag = instance("10.0.0.2", InstanceInfo.InstanceStatus.UP, Map.of("grpc.port", "5002"));
        EurekaClient client = mockClient(withFlag, noFlag);

        EurekaBootstrapResolver.Endpoint ep = new EurekaBootstrapResolver(client, new MockEnvironment()).resolve(APP);
        assertEquals("10.0.0.2", ep.host());
        assertEquals(5002, ep.port());
    }

    @Test
    @DisplayName("DOWN 实例被跳过")
    void skipsDownInstance() {
        InstanceInfo down = instance("10.0.0.1", InstanceInfo.InstanceStatus.DOWN, Map.of("grpc.port", "5001"));
        InstanceInfo up = instance("10.0.0.2", InstanceInfo.InstanceStatus.UP, Map.of("grpc.port", "5002"));
        EurekaClient client = mockClient(down, up);

        EurekaBootstrapResolver.Endpoint ep = new EurekaBootstrapResolver(client, new MockEnvironment()).resolve(APP);
        assertEquals("10.0.0.2", ep.host());
    }

    @Test
    @DisplayName("没有 grpc.port 元数据的实例被跳过")
    void skipsInstanceMissingGrpcPort() {
        InstanceInfo noGrpc = instance("10.0.0.1", InstanceInfo.InstanceStatus.UP, Map.of("raft.port", "7800"));
        EurekaClient client = mockClient(noGrpc);

        assertThrows(IllegalStateException.class,
            () -> new EurekaBootstrapResolver(client, new MockEnvironment()).resolve(APP));
    }

    @Test
    @DisplayName("Application 为 null → IllegalStateException")
    void throwsWhenAppMissing() {
        EurekaClient client = mock(EurekaClient.class);
        when(client.getApplication(APP)).thenReturn(null);

        assertThrows(IllegalStateException.class,
            () -> new EurekaBootstrapResolver(client, new MockEnvironment()).resolve(APP));
    }

    @Test
    @DisplayName("实例列表为空 → IllegalStateException")
    void throwsWhenNoInstances() {
        Application app = mock(Application.class);
        when(app.getInstances()).thenReturn(Collections.emptyList());
        EurekaClient client = mock(EurekaClient.class);
        when(client.getApplication(APP)).thenReturn(app);

        assertThrows(IllegalStateException.class,
            () -> new EurekaBootstrapResolver(client, new MockEnvironment()).resolve(APP));
    }

    @Test
    @DisplayName("appName 为空 → IllegalArgumentException")
    void rejectsBlankAppName() {
        assertThrows(IllegalArgumentException.class,
            () -> new EurekaBootstrapResolver(mock(EurekaClient.class), new MockEnvironment()).resolve(" "));
    }

    private static InstanceInfo instance(String ip, InstanceInfo.InstanceStatus status, Map<String, String> meta) {
        InstanceInfo info = mock(InstanceInfo.class);
        when(info.getIPAddr()).thenReturn(ip);
        when(info.getStatus()).thenReturn(status);
        when(info.getMetadata()).thenReturn(new HashMap<>(meta));
        return info;
    }

    private static EurekaClient mockClient(InstanceInfo... instances) {
        Application app = mock(Application.class);
        when(app.getInstances()).thenReturn(Arrays.asList(instances));
        EurekaClient client = mock(EurekaClient.class);
        when(client.getApplication(APP)).thenReturn(app);
        return client;
    }
}
