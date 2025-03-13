package com.binance.raftexchange.client.Api;

import io.grpc.EquivalentAddressGroup;
import io.grpc.NameResolver;

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.net.URI;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

class RaftExchangeNameResolver extends NameResolver {
    //放在这里这样触发对应的类加载
    //scheme需要小写 我看grpc代码是这样的
    static final String SCHEMA = "raftExchange".toLowerCase(Locale.US);

    private final URI uri;

    RaftExchangeNameResolver(URI uri) {
        this.uri = uri;
    }

    @Override
    public String getServiceAuthority() {
        if (uri.getHost() != null) {
            return uri.getHost();
        }
        return "no host";
    }

    @Override
    public void shutdown() {

    }

    @Override
    public void start(Listener2 listener) {
        List<EquivalentAddressGroup> addressGroups = ExchangeClient.nodes.stream()
                .map(s -> new InetSocketAddress(s.getHost(), s.getPort()))
                .map(a -> (SocketAddress) a)
                .map(Collections::singletonList)
                .map(EquivalentAddressGroup::new)//  // every socket address is a single EquivalentAddressGroup, so they can be accessed randomly
                .collect(Collectors.toList());
        listener.onResult(ResolutionResult.newBuilder()
                .setAddresses(addressGroups)
                .build());
    }

}
