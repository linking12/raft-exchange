package com.binance.raftexchange.server;

import java.io.IOException;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

import com.binance.raftexchange.server.services.UserServiceImpl;

import io.grpc.Server;
import io.grpc.ServerBuilder;

@SpringBootApplication
public class RaftexchangeApplication {
    public static void main(String[] args) throws Exception {
        Server server = ServerBuilder.forPort(5001).addService(new UserServiceImpl()).build();
        SpringApplication.run(RaftexchangeApplication.class, args);
        try {
            server.start();
            server.awaitTermination();
        } catch (InterruptedException | IOException e) {
            throw new Exception(e.getMessage());
        }
    }

}
