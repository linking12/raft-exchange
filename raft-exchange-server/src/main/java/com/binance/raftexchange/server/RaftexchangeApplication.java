package com.binance.raftexchange.server;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class RaftexchangeApplication{
    public static void main(String[] args) throws Exception {
        SpringApplication.run(RaftexchangeApplication.class, args);
        // 这里先把Jgroup的raft起起来
        
        // 然后再起暴露GrpcServer
    }
}
