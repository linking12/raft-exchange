package exchange.core2.core.processors;

import exchange.core2.core.common.MatcherEventType;
import exchange.core2.core.common.MatcherTradeEvent;
import exchange.core2.core.common.SymbolPositionRecord;
import exchange.core2.core.common.UserProfile;
import exchange.core2.core.common.CoreCurrencySpecification;
import exchange.core2.core.common.CoreSymbolSpecification;
import exchange.core2.core.common.cmd.CommandResultCode;
import exchange.core2.core.common.cmd.OrderCommand;
import exchange.core2.core.orderbook.OrderBookEventsHelper;

import java.util.Objects;

/**
 * 两步命令处理器：R1（每 shard 收集）→ matcher（单线程合并产 events）→ R2（每 shard 应用 + 释放）。
 * <p>
 * R1 把每个 shard 的局部观察写到 cmd 的 per-shard 槽位；matcher stage 合并；R2 按 events 回到对应 shard 上算账。
 * 三类强平相关命令（IF / ADL / FundingFee）共享这一形态——各自实现 hook 即可，不再散在 RiskEngine 里。
 * <p>
 * 同一个 processor 类 ({@link IFCommandProcessor} / {@link ADLCommandProcessor} /
 * {@link FundingFeeCommandProcessor}) 会存在两份独立实例：
 * <ul>
 *   <li>matcher stage 实例由 {@link MatchingEngineRouter} 持有，{@link #eventsHelper} 非 null、
 *       {@link #riskEngine} 为 null，只走 {@link #process} → {@link #buildMatcherEvents}；
 *   <li>R1/R2 实例由 {@link RiskEngine} 持有（每 shard 一份），{@link #riskEngine} 非 null、
 *       {@link #eventsHelper} 为 null，只走 {@link #collectInput} / {@link #applyEvent} /
 *       {@link #finalizeForCommand}。
 * </ul>
 * 两边调用面不重叠；跨用会在入口 {@code Objects.requireNonNull} 处响亮报错（"matcher-stage only" / "R1/R2 only"），
 * 而非隐式 NPE。
 */
public abstract class TwoStepCommandProcessor {

    protected final OrderBookEventsHelper eventsHelper;
    protected final RiskEngine riskEngine;

    protected TwoStepCommandProcessor(OrderBookEventsHelper eventsHelper, RiskEngine riskEngine) {
        this.eventsHelper = eventsHelper;
        this.riskEngine = riskEngine;
    }

    public final CommandResultCode process(OrderCommand cmd) {
        Objects.requireNonNull(eventsHelper, "matcher-stage only");
        if (cmd.resultCode != CommandResultCode.VALID_FOR_MATCHING_ENGINE) {
            return cmd.resultCode;
        }
        buildMatcherEvents(cmd);
        return CommandResultCode.SUCCESS;
    }

    protected abstract void buildMatcherEvents(OrderCommand cmd);

    /** R1：每 shard RiskEngine 并行回调。 */
    public void collectInput(OrderCommand cmd) {
        Objects.requireNonNull(riskEngine, "R1/R2 only");
    }

    /** R2 per-event：每 shard 遍历 matcherEvent 链时回调。 */
    public void applyEvent(OrderCommand cmd, MatcherTradeEvent ev,
        CoreSymbolSpecification spec, CoreCurrencySpecification currencySpec) {
        Objects.requireNonNull(riskEngine, "R1/R2 only");
    }

    /** R2 finalize：每 shard 在 cmd 收尾回调一次。 */
    public void finalizeForCommand(OrderCommand cmd, UserProfile takerUp,
        SymbolPositionRecord takerSpr, CoreSymbolSpecification spec, CoreCurrencySpecification currencySpec) {
        Objects.requireNonNull(riskEngine, "R1/R2 only");
    }

    protected MatcherTradeEvent buildRejectEvent() {
        MatcherTradeEvent ev = eventsHelper.newMatcherEvent();
        ev.eventType = MatcherEventType.REJECT;
        return ev;
    }
}
