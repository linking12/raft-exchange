package com.binance.raftexchange.client.Api;

import java.net.URI;

import io.grpc.NameResolver;

class RaftExchangeNameResolver extends NameResolver {
    private final URI uri;
    private volatile Listener2 listener2;
    private ResolutionResult lastResult;
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
        if (lastResult != null) {
            listener2.onResult(lastResult);
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
