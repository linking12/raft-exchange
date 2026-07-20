package com.binance.raftexchange.client.grpc;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import com.binance.raftexchange.stubs.request.ApiCommand;
import com.binance.raftexchange.stubs.response.CommandResult;
import com.binance.raftexchange.stubs.response.CommandResultCode;
import com.binance.raftexchange.stubs.response.ServerNode;

import io.grpc.stub.StreamObserver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ApiStream implements StreamObserver<ApiCommand>, AutoCloseable {
    private static final Logger LOGGER = LoggerFactory.getLogger(ApiStream.class);
    private final ReentrantReadWriteLock observerLock = new ReentrantReadWriteLock();
    private StreamObserver<ApiCommand> internalObserver;

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
                if (currentVersion != version.get()) {
                    return;
                }
                if (commandResult.getResultCode() == CommandResultCode.NEED_MOVE) {
                    ServerNode leaderNode = commandResult.getLeaderNode();
                    LOGGER.info("NEED_MOVE received from server, redirecting to {}:{} (streamVersion={})",
                        leaderNode.getHost(), leaderNode.getPort(), currentVersion);
                    root.switchToNewLeader(leaderNode);
                }
                resultStreamObserver.onNext(commandResult);
            }

            @Override
            public void onError(Throwable throwable) {
                if (currentVersion != version.get()) {
                    return;
                }
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
        observerLock.readLock().lock();
        try {
            requireInitialized().onNext(apiCommand);
        } finally {
            observerLock.readLock().unlock();
        }
    }

    @Override
    public void onError(Throwable throwable) {
        observerLock.readLock().lock();
        try {
            requireInitialized().onError(throwable);
        } finally {
            observerLock.readLock().unlock();
        }
    }

    @Override
    public void onCompleted() {
        observerLock.readLock().lock();
        try {
            requireInitialized().onCompleted();
        } finally {
            observerLock.readLock().unlock();
        }
        root.endStream(this);
    }

    void replaceInternalObserver(StreamObserver<ApiCommand> newObserver) {
        observerLock.writeLock().lock();
        try {
            StreamObserver<ApiCommand> oldObserver = this.internalObserver;
            this.internalObserver = newObserver;
            if (oldObserver != null) {
                oldObserver.onCompleted();
            }
        } finally {
            observerLock.writeLock().unlock();
        }
    }

    @Override
    public void close() {
        this.onCompleted();
    }

    private StreamObserver<ApiCommand> requireInitialized() {
        StreamObserver<ApiCommand> obs = this.internalObserver;
        if (obs == null) {
            throw new IllegalStateException("ApiStream internalObserver not initialized");
        }
        return obs;
    }
}
