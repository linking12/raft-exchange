package com.binance.raftexchange.server.raft;

public record AdminResult(boolean success, String message) {

    public static AdminResult ok() {
        return new AdminResult(true, "OK");
    }

    public static AdminResult ok(String message) {
        return new AdminResult(true, message);
    }

    public static AdminResult error(String message) {
        return new AdminResult(false, message);
    }

    public static AdminResult unsupported(String backend) {
        return new AdminResult(false, "operation not supported by backend " + backend);
    }
}
