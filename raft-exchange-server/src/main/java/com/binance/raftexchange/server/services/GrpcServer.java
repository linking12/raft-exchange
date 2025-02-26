package com.binance.raftexchange.server.services;

import io.grpc.Server;
import io.grpc.ServerBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class GrpcServer implements InitializingBean, DisposableBean {

    static final Logger LOGGER = LoggerFactory.getLogger(GrpcServer.class);

    @Autowired
    private UserServiceImpl userService;

    @Autowired
    private ApiService apiService;

    Server server;

    @Override
    public void afterPropertiesSet() throws Exception {
        server = ServerBuilder.forPort(5001)
                .addService(userService)
                .addService(apiService)
                .build();
        Server localServer = server.start();
        LOGGER.info("grpc server start {}", localServer.getPort());
    }


    @Override
    public void destroy() throws Exception {
        server.shutdown();
    }
}
