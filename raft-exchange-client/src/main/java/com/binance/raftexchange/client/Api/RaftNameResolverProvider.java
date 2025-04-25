package com.binance.raftexchange.client.Api;

import com.binance.raftexchange.stubs.response.NodeType;
import com.binance.raftexchange.stubs.response.ServerNode;
import io.grpc.EquivalentAddressGroup;
import io.grpc.NameResolver;
import io.grpc.NameResolverProvider;
import io.grpc.NameResolverRegistry;
import io.grpc.StatusOr;

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.net.URI;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Collectors;

public class RaftNameResolverProvider extends NameResolverProvider {
    static final String SCHEMA = "raftExchange".toLowerCase(Locale.US);
    private CopyOnWriteArrayList<RaftExchangeNameResolver> resolvers;
    private final ReentrantLock refreshLock;

    public RaftNameResolverProvider() {
        this.resolvers = new CopyOnWriteArrayList<>();
        this.refreshLock = new ReentrantLock();
    }

    @Override
    protected boolean isAvailable() {
        return true;
    }

    @Override
    protected int priority() {
        return 0;
    }

    @Override
    public NameResolver newNameResolver(URI targetUri, NameResolver.Args args) {
        RaftExchangeNameResolver raftExchangeNameResolver = new RaftExchangeNameResolver(targetUri, this);
        resolvers.add(raftExchangeNameResolver);
        return raftExchangeNameResolver;
    }

    @Override
    public String getDefaultScheme() {
        return SCHEMA;
    }

    private void refreshAll(NameResolver.ResolutionResult resolutionResult) {
        for (RaftExchangeNameResolver resolver : resolvers) {
            resolver.refreshNodes(resolutionResult);
        }
    }

    void removeListener(RaftExchangeNameResolver resolver) {
        resolvers.remove(resolver);
    }

    static void refresh(List<ServerNode> nodes) {
        RaftNameResolverProvider provider = (RaftNameResolverProvider)NameResolverRegistry.getDefaultRegistry().getProviderForScheme(SCHEMA);
        ReentrantLock lock = provider.refreshLock;
        if (!lock.tryLock()) {
            return;
        }
        try {
            if (nodes.size() != 1) {
                nodes = nodes.stream().filter(s -> s.getType() != NodeType.LEADER).collect(Collectors.toList());
            }
            List<EquivalentAddressGroup> addressGroups = nodes.stream().map(s -> new InetSocketAddress(s.getHost(), s.getPort())).map(a -> (SocketAddress)a)
                .map(Collections::singletonList).map(EquivalentAddressGroup::new)// // every socket address is a single
                                                                                 // EquivalentAddressGroup, so they can
                                                                                 // be accessed randomly
                .collect(Collectors.toList());
            NameResolver.ResolutionResult resolutionResult =
                NameResolver.ResolutionResult.newBuilder().setAddressesOrError(StatusOr.fromValue(addressGroups)).build();
            provider.refreshAll(resolutionResult);
        } finally {
            lock.unlock();
        }
    }

}
