package exchange.core2.tests.integration;

import exchange.core2.core.common.config.PerformanceConfiguration;

public final class ITFeesDynamicMarginLatency extends ITFeesDynamicMargin {
    @Override
    public PerformanceConfiguration getPerformanceConfiguration() {
        return PerformanceConfiguration.latencyPerformanceBuilder().build();
    }
}
