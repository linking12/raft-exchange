package com.binance.raftexchange.client.spring;

import com.binance.raftexchange.client.ExchangeApi;
import com.netflix.discovery.EurekaClient;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * autoconfig 的 ExchangeApi bean 真正构造会调 ExchangeApi.connect → 起 gRPC sniff， 无法在没真 server 时 boot。所以这里的 case 都让用户配置一个 mock
 * ExchangeApi 让 @ConditionalOnMissingBean 跳过 autoconfig 构造，只验证装配条件 / properties 绑定。
 */
class ExchangeApiAutoConfigurationTest {

    private final ApplicationContextRunner runner =
        new ApplicationContextRunner().withConfiguration(AutoConfigurations.of(ExchangeApiAutoConfiguration.class));

    @Test
    @DisplayName("没有 EurekaClient bean 时不装配 ExchangeApi/Resolver")
    void skipsWithoutEurekaClient() {
        runner.run(ctx -> {
            assertThat(ctx).doesNotHaveBean(EurekaBootstrapResolver.class);
            assertThat(ctx).doesNotHaveBean(ExchangeApi.class);
        });
    }

    @Test
    @DisplayName("有 EurekaClient bean + 用户已提供 ExchangeApi → 只额外装 Resolver")
    void registersResolverWhenEurekaPresent() {
        runner.withUserConfiguration(EurekaWithUserApi.class).run(ctx -> {
            assertThat(ctx).hasSingleBean(EurekaBootstrapResolver.class);
            assertThat(ctx).hasSingleBean(ExchangeApi.class);
        });
    }

    @Test
    @DisplayName("用户自定义 ExchangeApi bean 时不再装配 autoconfig 版本")
    void userExchangeApiWins() {
        runner.withUserConfiguration(EurekaWithUserApi.class).run(ctx -> {
            assertThat(ctx).hasSingleBean(ExchangeApi.class);
            assertThat(ctx.getBean(ExchangeApi.class)).isSameAs(ctx.getBean("userExchangeApi"));
        });
    }

    @Test
    @DisplayName("properties 从 raftexchange.client.* 绑定")
    void bindsProperties() {
        runner.withUserConfiguration(EurekaWithUserApi.class).withPropertyValues("raftexchange.client.app-name=my-app",
            "raftexchange.client.send-timeout=3s", "raftexchange.client.nodes-flush-interval=42s").run(ctx -> {
                ExchangeApiProperties props = ctx.getBean(ExchangeApiProperties.class);
                assertThat(props.getAppName()).isEqualTo("my-app");
                assertThat(props.getSendTimeout()).isEqualTo(Duration.ofSeconds(3));
                assertThat(props.getNodesFlushInterval()).isEqualTo(Duration.ofSeconds(42));
            });
    }

    @Configuration
    static class EurekaWithUserApi {
        @Bean
        EurekaClient eurekaClient() {
            return Mockito.mock(EurekaClient.class);
        }

        @Bean
        ExchangeApi userExchangeApi() {
            return Mockito.mock(ExchangeApi.class);
        }
    }
}
