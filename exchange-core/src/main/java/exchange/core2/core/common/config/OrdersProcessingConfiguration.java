package exchange.core2.core.common.config;


import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.ToString;


/**
 * Order processing configuration
 */
@AllArgsConstructor
@Getter
@Builder
@ToString
public final class OrdersProcessingConfiguration {

    /**
     * 是否启用 margin trading 的 JVM 开关。生产默认关闭（沿用原行为），
     * 期货端到端验证 / 集成测试需要在 JVM 启动时显式打开：
     * {@code -Dexchange.marginTrading.enabled=true}
     *
     * <p>注意：本字段在类初始化时一次性求值。若调用方在 OrdersProcessingConfiguration 已被首次加载
     * 之后再 {@code System.setProperty(...)}，DEFAULT 不会变。需要在 JVM 启动参数里设置，
     * 或在任何引用 OrdersProcessingConfiguration 的代码执行之前 setProperty。</p>
     */
    public static final String MARGIN_TRADING_PROPERTY = "exchange.marginTrading.enabled";

    public static OrdersProcessingConfiguration DEFAULT = OrdersProcessingConfiguration.builder()
            .riskProcessingMode(RiskProcessingMode.FULL_PER_CURRENCY)
            .marginTradingMode(Boolean.parseBoolean(System.getProperty(MARGIN_TRADING_PROPERTY, "false"))
                    ? MarginTradingMode.MARGIN_TRADING_ENABLED
                    : MarginTradingMode.MARGIN_TRADING_DISABLED)
            .build();

    private final RiskProcessingMode riskProcessingMode;
    private final MarginTradingMode marginTradingMode;

    public enum RiskProcessingMode {
        // risk processing is on, every currency/asset account is checked independently
        FULL_PER_CURRENCY,

        // risk processing is off, any orders accepted and placed
        NO_RISK_PROCESSING
    }

    public enum MarginTradingMode {
        MARGIN_TRADING_DISABLED,
        MARGIN_TRADING_ENABLED
    }
}
