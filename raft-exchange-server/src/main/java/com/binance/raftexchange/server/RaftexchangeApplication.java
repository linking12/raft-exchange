package com.binance.raftexchange.server;

import java.io.IOException;

import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

import com.binance.raftexchange.server.services.UserServiceImpl;

import io.grpc.Server;
import io.grpc.ServerBuilder;

@SpringBootApplication
public class RaftexchangeApplication implements CommandLineRunner {
    public static void main(String[] args) throws Exception {
        SpringApplication.run(RaftexchangeApplication.class, args);
        // 这里先把Jgroup的raft起起来
        
        
        // 然后再起暴露GrpcServer
    }

    @Override
    public void run(String... arg0) throws Exception {
        Server server = ServerBuilder.forPort(5001).addService(new UserServiceImpl()).build();
        try {
            server.start();
            server.awaitTermination();
        } catch (InterruptedException | IOException e) {
            throw new Exception(e.getMessage());
        }

    }

}
