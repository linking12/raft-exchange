package exchange.core2.tests.integration;

import exchange.core2.core.common.config.PerformanceConfiguration;

public final class ITFeesDynamicMarginBasic extends ITFeesDynamicMargin {
    @Override
    public PerformanceConfiguration getPerformanceConfiguration() {
        return PerformanceConfiguration.DEFAULT;
    }
}
