package com.binance.raftexchange.client.Api;

import io.grpc.NameResolver;

import java.net.URI;

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
        //因为是先刷node触发refreshNodes然后才有这个start
        //这个start被第一次调用进行触发 所以晚于第一次refreshNodes
        //进而要发布一次
        if (lastResult != null) {
            listener2.onResult(lastResult);
        }
    }

    void refreshNodes(ResolutionResult resolutionResult) {
        Listener2 local = listener2;
        lastResult = resolutionResult;
        if(local == null) {
            return;
        }
        local.onResult(resolutionResult);
    }
}
