package com.binance.raftexchange.client;

import com.binance.raftexchange.stubs.request.ApiCommand;
import com.binance.raftexchange.stubs.response.CommandResult;
import com.binance.raftexchange.stubs.response.CommandResultCode;
import com.binance.raftexchange.stubs.response.ServerNode;
import io.grpc.stub.StreamObserver;

//向server进行通讯的streaming
public class ApiStream implements StreamObserver<ApiCommand> {

    private volatile StreamObserver<ApiCommand> internalObserver;

    private final ExchangeClient root;

    private final StreamObserver<CommandResult> resultStreamObserver;

    public ApiStream(ExchangeClient root, StreamObserver<CommandResult> resultStreamObserver) {
        this.root = root;
        this.resultStreamObserver = resultStreamObserver;
    }

    //server对client的streaming
    StreamObserver<CommandResult> toUserObserver() {
        return new StreamObserver<CommandResult>() {
            @Override
            public void onNext(CommandResult commandResult) {
                if (commandResult.getResultCode() != CommandResultCode.NEED_MOVE) {
                    resultStreamObserver.onNext(commandResult);
                    return;
                }
                ServerNode leaderNode = commandResult.getLeaderNode();
                root.reportLeaderFresh(leaderNode);
            }

            @Override
            public void onError(Throwable throwable) {
                resultStreamObserver.onError(throwable);
            }

            @Override
            public void onCompleted() {
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
}
