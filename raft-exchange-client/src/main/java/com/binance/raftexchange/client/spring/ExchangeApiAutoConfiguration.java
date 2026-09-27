package com.binance.raftexchange.client.spring;

import com.binance.raftexchange.client.ExchangeApi;
import com.binance.raftexchange.client.ExchangeApiOptions;
import com.netflix.discovery.EurekaClient;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;

@AutoConfiguration
@ConditionalOnClass({ExchangeApi.class, EurekaClient.class})
@ConditionalOnBean(EurekaClient.class)
@EnableConfigurationProperties(ExchangeApiProperties.class)
public class ExchangeApiAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public EurekaBootstrapResolver eurekaBootstrapResolver(EurekaClient eurekaClient, Environment env) {
        return new EurekaBootstrapResolver(eurekaClient, env);
    }

    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean
    public ExchangeApi exchangeApi(EurekaBootstrapResolver resolver, ExchangeApiProperties props) {
        EurekaBootstrapResolver.Endpoint ep = resolver.resolve(props.getAppName());
        ExchangeApiOptions options = ExchangeApiOptions.builder().sendTimeout(props.getSendTimeout())
            .nodesFlushInterval(props.getNodesFlushInterval()).build();
        return ExchangeApi.connect(ep.host(), ep.port(), options);
    }
}
