package com.binance.raftexchange.server;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.actuate.autoconfigure.availability.AvailabilityProbesAutoConfiguration;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.netflix.eureka.EnableEurekaClient;

import com.binance.master.constant.Constant;
import com.binance.master.utils.IPUtils;

@EnableEurekaClient
@SpringBootApplication(exclude = {AvailabilityProbesAutoConfiguration.class})
public class RaftexchangeApplication {
    public static void main(String[] args) {
        System.setProperty(Constant.LOCAL_IP, IPUtils.getIp());
        SpringApplication.run(RaftexchangeApplication.class, args);
    }

}
