package com.binance.raftexchange.client.grpc;

import java.util.concurrent.atomic.AtomicInteger;

import com.binance.raftexchange.stubs.request.ApiCommand;
import com.binance.raftexchange.stubs.response.CommandResult;
import com.binance.raftexchange.stubs.response.CommandResultCode;
import com.binance.raftexchange.stubs.response.ServerNode;

import io.grpc.stub.StreamObserver;

public class ApiStream implements StreamObserver<ApiCommand>, AutoCloseable {

    private volatile StreamObserver<ApiCommand> internalObserver;

    private final ExchangeClient root;

    private final StreamObserver<CommandResult> resultStreamObserver;

    private final AtomicInteger version;

    public ApiStream(ExchangeClient root, StreamObserver<CommandResult> resultStreamObserver) {
        this.root = root;
        this.resultStreamObserver = resultStreamObserver;
        this.version = new AtomicInteger(0);
    }

    StreamObserver<CommandResult> toUserObserver() {
        int currentVersion = version.incrementAndGet();
        return new StreamObserver<CommandResult>() {
            @Override
            public void onNext(CommandResult commandResult) {
                if (commandResult.getResultCode() == CommandResultCode.NEED_MOVE) {
                    ServerNode leaderNode = commandResult.getLeaderNode();
                    root.reportLeaderFresh(leaderNode);
                }

                resultStreamObserver.onNext(commandResult);
            }

            @Override
            public void onError(Throwable throwable) {
                resultStreamObserver.onError(throwable);
            }

            @Override
            public void onCompleted() {
                if (currentVersion != version.get()) {
                    return;
                }
                resultStreamObserver.onCompleted();
            }
        };
    }

    @Override
    public void onNext(ApiCommand apiCommand) {
        internalObserver.onNext(apiCommand);
    }

    @Override
    public void onError(Throwable throwable) {
        internalObserver.onError(throwable);
    }

    @Override
    public void onCompleted() {
        internalObserver.onCompleted();
        root.endStream(this);
    }

    void replaceInternalObserver(StreamObserver<ApiCommand> observer) {
        if (this.internalObserver != null) {
            internalObserver.onCompleted();
        }
        this.internalObserver = observer;
    }

    @Override
    public void close() {
        this.onCompleted();
    }
}
