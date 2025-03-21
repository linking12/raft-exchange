package com.binance.raftexchange.client.Api;

import com.binance.raftexchange.stubs.request.ApiCommand;
import com.binance.raftexchange.stubs.response.CommandResult;
import com.binance.raftexchange.stubs.response.CommandResultCode;
import com.binance.raftexchange.stubs.response.ServerNode;
import io.grpc.stub.StreamObserver;

import java.util.concurrent.atomic.AtomicInteger;

//向server进行通讯的streaming
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

    //server对client的streaming
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
                //防止切换主之后 用户的resultStreamObserver错误地接受了旧stream的onCompleted回调
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
            //旧的流关闭
            internalObserver.onCompleted();
        }
        this.internalObserver = observer;
    }

    @Override
    public void close() {
        this.onCompleted();
    }
}
