package exchange.core2.tests.unit;

import exchange.core2.core.common.MatcherEventType;
import exchange.core2.core.common.MatcherTradeEvent;
import exchange.core2.core.common.PositionDirection;
import exchange.core2.core.common.SymbolPositionRecord;
import exchange.core2.core.common.api.ApiAutoDeleveraging;
import exchange.core2.core.common.api.ApiCommand;
import exchange.core2.core.common.api.ApiIFTakeOver;
import exchange.core2.core.common.cmd.OrderCommand;
import exchange.core2.core.common.cmd.OrderCommandType;
import exchange.core2.core.common.config.ExchangeConfiguration;
import exchange.core2.core.common.config.PerformanceConfiguration;
import exchange.core2.core.processors.liquidation.LiquidationContext;
import exchange.core2.core.processors.liquidation.LiquidationContext.LiquidationState;
import exchange.core2.core.processors.liquidation.LiquidationEngine;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * 锁定 LiquidationEngine 状态机的"cmd type ↔ publish 时点 ctx.state"映射。
 * <p>
 * apply path 的 expected-state gate（见 {@link LiquidationEngine#nextLiquidationState}）依赖这个映射——
 * publish 出去的 cmd type 必须跟 apply 时 ctx.state 对得上，否则 follower 会把合法 cmd 当 duplicate 丢。
 *
 * <p>当前不变量：
 * <ul>
 *   <li>publish FORCE_LIQUIDATION 时 ctx.state == LIQUIDATING（由 {@link LiquidationContext} 构造器保证）</li>
 *   <li>publish IF_TAKEOVER 时 ctx.state == WAIT_IF_EXECUTION（由 onMarketDone REJECT 分支保证）</li>
 *   <li>publish AUTO_DELEVERAGING 时 ctx.state == WAIT_ADL_EXECUTION（由 enterAdlPhase 保证）</li>
 * </ul>
 */
class LiquidationStateMachineMappingTest {

    private static final ExchangeConfiguration TEST_CFG = ExchangeConfiguration.defaultBuilder()
            .performanceCfg(PerformanceConfiguration.baseBuilder().build())
            .build();

    /** Publish FORCE_LIQUIDATION 时 ctx.state 应是 LIQUIDATING（由 ctor 保证）。 */
    @Test
    void contextNewlyCreated_stateIsLiquidating() {
        LiquidationContext ctx = new LiquidationContext(123L, 456L, 0L, 0L);
        assertSame(LiquidationState.LIQUIDATING, ctx.state,
                "新 ctx 默认 state=LIQUIDATING；改了会破坏 FORCE_LIQUIDATION apply 时 expected-state gate");
    }

    /** onMarketDone REJECT → publish IF_TAKEOVER 时 ctx.state 必为 WAIT_IF_EXECUTION。 */
    @Test
    void onMarketDoneReject_publishesIfTakeover_atWaitIfExecutionState() {
        LiquidationEngine le = new LiquidationEngine(null, 0, TEST_CFG);
        List<PublishedEvent> events = new ArrayList<>();
        le.setLiquidationCmdPublisher(captureWithState(events, this::posSupplier));

        SymbolPositionRecord pos = newPositionWithCtx(LiquidationState.LIQUIDATING, 100L, 10L);
        capturedPos = pos;
        OrderCommand cmd = newForceLiquidationCmd(MatcherEventType.REJECT, 4L);

        le.nextLiquidationState(cmd, pos);

        PublishedEvent if_pub = findCmdByType(events, ApiIFTakeOver.class);
        assertSame(LiquidationState.WAIT_IF_EXECUTION, if_pub.stateAtPublish,
                "publish IF_TAKEOVER 时 ctx.state 必为 WAIT_IF_EXECUTION——映射破坏会让 apply 把 IF 当 duplicate skip");
    }

    /** onIFTakeoverDone REJECT → enterAdlPhase → publish AUTO_DELEVERAGING 时 ctx.state 必为 WAIT_ADL_EXECUTION。 */
    @Test
    void onIFTakeoverDoneReject_publishesAdl_atWaitAdlExecutionState() {
        LiquidationEngine le = new LiquidationEngine(null, 0, TEST_CFG);
        List<PublishedEvent> events = new ArrayList<>();
        le.setLiquidationCmdPublisher(captureWithState(events, this::posSupplier));

        SymbolPositionRecord pos = newPositionWithCtx(LiquidationState.WAIT_IF_EXECUTION, 100L, 4L);
        capturedPos = pos;
        OrderCommand cmd = newIfTakeoverCmd(MatcherEventType.REJECT, 2L);

        le.nextLiquidationState(cmd, pos);

        PublishedEvent adl_pub = findCmdByType(events, ApiAutoDeleveraging.class);
        assertSame(LiquidationState.WAIT_ADL_EXECUTION, adl_pub.stateAtPublish,
                "publish AUTO_DELEVERAGING 时 ctx.state 必为 WAIT_ADL_EXECUTION——映射破坏会让 apply 把 ADL 当 duplicate skip");
    }

    // ---------- helpers ----------

    private record PublishedEvent(ApiCommand cmd, LiquidationState stateAtPublish) {}

    private SymbolPositionRecord capturedPos;

    private SymbolPositionRecord posSupplier() {
        return capturedPos;
    }

    private static exchange.core2.core.processors.liquidation.LiquidationCmdPublisher captureWithState(
            List<PublishedEvent> sink, java.util.function.Supplier<SymbolPositionRecord> posSupplier) {
        return (cmd, onApplied) -> {
            sink.add(new PublishedEvent(cmd, posSupplier.get().liquidationCtx.state));
            if (onApplied != null) onApplied.run();
        };
    }

    private static PublishedEvent findCmdByType(List<PublishedEvent> events, Class<?> type) {
        return events.stream().filter(e -> type.isInstance(e.cmd)).findFirst()
                .orElseThrow(() -> new AssertionError("no " + type.getSimpleName() + " published; events=" + events));
    }

    private static SymbolPositionRecord newPositionWithCtx(LiquidationState state, long price, long size) {
        SymbolPositionRecord pos = new SymbolPositionRecord();
        pos.uid = 1L;
        pos.symbol = 100001;
        pos.direction = PositionDirection.LONG;
        pos.openVolume = size;
        LiquidationContext ctx = new LiquidationContext(price, size, 0L, 0L);
        ctx.state = state;
        pos.liquidationCtx = ctx;
        return pos;
    }

    private static OrderCommand newForceLiquidationCmd(MatcherEventType eventType, long firstEventSize) {
        OrderCommand cmd = new OrderCommand(1);
        cmd.command = OrderCommandType.FORCE_LIQUIDATION;
        cmd.price = 100L;
        cmd.size = firstEventSize;
        cmd.matcherEvent = newMatcherEvent(eventType, firstEventSize);
        return cmd;
    }

    private static OrderCommand newIfTakeoverCmd(MatcherEventType eventType, long firstEventSize) {
        OrderCommand cmd = new OrderCommand(1);
        cmd.command = OrderCommandType.IF_TAKEOVER;
        cmd.price = 100L;
        cmd.size = firstEventSize;
        cmd.matcherEvent = newMatcherEvent(eventType, firstEventSize);
        return cmd;
    }

    private static MatcherTradeEvent newMatcherEvent(MatcherEventType eventType, long size) {
        MatcherTradeEvent e = new MatcherTradeEvent();
        e.eventType = eventType;
        e.size = size;
        return e;
    }
}
