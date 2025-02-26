package com.binance.raftexchange.server;

import com.binance.raftexchange.server.raft.ClusterDiscoveryByEureka;
import com.binance.raftexchange.server.raft.JGroupsRaftClusterView;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

import org.springframework.cloud.netflix.eureka.EnableEurekaClient;

@EnableEurekaClient
@SpringBootApplication
public class RaftExchangeApplication implements CommandLineRunner {

    public static void main(String[] args) throws Exception {
        SpringApplication.run(RaftExchangeApplication.class, args);
    }

    @Value("${spring.application.name}")
    private String jgroupsClusterName;

    @Autowired
    private ClusterDiscoveryByEureka clusterDiscoveryByEureka;

    private JGroupsRaftClusterView jGroupsRaftClusterView;

    @Override
    public void run(String... arg0) throws Exception {
        jGroupsRaftClusterView = new JGroupsRaftClusterView(clusterDiscoveryByEureka, null, jgroupsClusterName);
        jGroupsRaftClusterView.doStart();

        //
    }

}
