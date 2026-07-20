package com.binance.raftexchange.client.grpc;

import java.net.URI;

import io.grpc.NameResolver;

class RaftExchangeNameResolver extends NameResolver {
    private final URI uri;
    private volatile Listener2 listener2;
    private volatile ResolutionResult lastResult;
    private final RaftNameResolverProvider parent;

    RaftExchangeNameResolver(URI uri, RaftNameResolverProvider parent) {
        this.uri = uri;
        this.parent = parent;
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
        parent.removeListener(this);
    }

    @Override
    public void start(Listener2 listener) {
        this.listener2 = listener;
        ResolutionResult current = lastResult;
        if (current != null) {
            listener.onResult(current);
        }
    }

    void refreshNodes(ResolutionResult resolutionResult) {
        Listener2 local = listener2;
        lastResult = resolutionResult;
        if (local == null) {
            return;
        }
        local.onResult(resolutionResult);
    }
}
