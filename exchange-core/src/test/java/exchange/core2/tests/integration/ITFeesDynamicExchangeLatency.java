package exchange.core2.tests.integration;

import exchange.core2.core.common.config.PerformanceConfiguration;

public class ITFeesDynamicExchangeLatency extends ITFeesDynamicExchange {
    @Override
    public PerformanceConfiguration getPerformanceConfiguration() {
        return PerformanceConfiguration.latencyPerformanceBuilder().build();
    }
}
