package exchange.core2.tests.unit;

import exchange.core2.core.common.MatcherEventType;
import exchange.core2.core.common.MatcherTradeEvent;
import exchange.core2.core.common.OrderAction;
import exchange.core2.core.common.PositionDirection;
import exchange.core2.core.common.SymbolPositionRecord;
import exchange.core2.core.common.api.ApiAutoDeleveraging;
import exchange.core2.core.common.api.ApiCommand;
import exchange.core2.core.common.api.ApiIFTakeOver;
import exchange.core2.core.common.cmd.OrderCommand;
import exchange.core2.core.common.cmd.OrderCommandType;
import exchange.core2.core.common.config.ExchangeConfiguration;
import exchange.core2.core.common.config.PerformanceConfiguration;
import exchange.core2.core.processors.liquidation.LiquidationFlow;
import exchange.core2.core.processors.liquidation.LiquidationFlow.LiquidationState;
import exchange.core2.core.processors.liquidation.LiquidationEngine;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 锁定 LiquidationEngine 状态机的分支转换行为。
 *
 * <p>覆盖 advanceLiquidation 通过 cmd type 分发后进入的三个 onXxxDone：
 * <ul>
 *   <li>onMarketDone（FORCE_LIQUIDATION）：TRADE → flow=null；REJECT+IF → publish IF_TAKEOVER；REJECT+!IF → publish ADL</li>
 *   <li>onIFTakeoverDone（IF_TAKEOVER）：TRADE → flow=null；REJECT → publish ADL</li>
 *   <li>onADLDone（AUTO_DELEVERAGING）：flow=null</li>
 * </ul>
 * 这些 onXxxDone 是 private；通过 advanceLiquidation 公开入口触发。
 */
class LiquidationEngineStateMachineTest {

    private static final ExchangeConfiguration TEST_CFG = ExchangeConfiguration.defaultBuilder()
            .performanceCfg(PerformanceConfiguration.baseBuilder().build())
            .build();

    private static final int SYMBOL = 100001;
    private static final long UID = 9801L;

    private LiquidationEngine le;

    @AfterEach
    void tearDown() {
        if (le != null) {
            le.stop();
        }
    }

    // ============================ onMarketDone ============================

    @Test
    void onMarketDone_tradeEvent_closesCtx_doesNotPublishAnything() {
        LiquidationEngine le = newLE();
        List<ApiCommand> published = new ArrayList<>();
        le.setCommandSubmitter((cmd, onApplied) -> published.add(cmd));

        SymbolPositionRecord pos = newPositionWithCtx(LiquidationState.LIQUIDATING);
        OrderCommand cmd = newForceLiquidationCmd(MatcherEventType.TRADE, 4L);

        le.advanceLiquidation(cmd, pos);

        assertNull(pos.liquidationFlow, "TRADE 后 flow 闭环置 null");
        assertTrue(published.isEmpty(), "市场完全吃单不应再 publish 后续 cmd");
    }

    @Test
    void onMarketDone_rejectWithIfEnabled_publishesIfTakeover_andTransitionsToWaitIf() {
        LiquidationEngine le = newLE();
        List<ApiCommand> published = new ArrayList<>();
        le.setCommandSubmitter((cmd, onApplied) -> published.add(cmd));

        SymbolPositionRecord pos = newPositionWithCtx(LiquidationState.LIQUIDATING);
        OrderCommand cmd = newForceLiquidationCmd(MatcherEventType.REJECT, 3L);

        le.advanceLiquidation(cmd, pos);

        assertSame(LiquidationState.WAIT_IF_EXECUTION, pos.liquidationFlow.state, "REJECT+IF → state=WAIT_IF_EXECUTION");
        assertEquals(3L, pos.liquidationFlow.size, "REJECT 后 flow.size 应改成 firstEvent.size");
        assertEquals(1, published.size(), "应 publish 一条 IF_TAKEOVER");
        ApiIFTakeOver ifCmd = assertInstanceOf(ApiIFTakeOver.class, published.get(0));
        assertEquals(3L, ifCmd.size, "IF_TAKEOVER.size 应跟 flow.size 一致");
    }

    // ============================ onIFTakeoverDone ============================

    @Test
    void onIfTakeoverDone_tradeEvent_closesCtx_doesNotPublishAnything() {
        LiquidationEngine le = newLE();
        List<ApiCommand> published = new ArrayList<>();
        le.setCommandSubmitter((cmd, onApplied) -> published.add(cmd));

        SymbolPositionRecord pos = newPositionWithCtx(LiquidationState.WAIT_IF_EXECUTION);
        OrderCommand cmd = newIfTakeoverCmd(MatcherEventType.TRADE, 3L);

        le.advanceLiquidation(cmd, pos);

        assertNull(pos.liquidationFlow, "IF 接仓成功 → flow 闭环置 null");
        assertTrue(published.isEmpty(), "IF 成交不应再 publish 后续 cmd");
    }

    @Test
    void onIfTakeoverDone_rejectEvent_publishesAdl_andTransitionsToWaitAdl() {
        LiquidationEngine le = newLE();
        List<ApiCommand> published = new ArrayList<>();
        le.setCommandSubmitter((cmd, onApplied) -> published.add(cmd));

        SymbolPositionRecord pos = newPositionWithCtx(LiquidationState.WAIT_IF_EXECUTION);
        OrderCommand cmd = newIfTakeoverCmd(MatcherEventType.REJECT, 2L);

        le.advanceLiquidation(cmd, pos);

        assertSame(LiquidationState.WAIT_ADL_EXECUTION, pos.liquidationFlow.state,
                "IF REJECT → state=WAIT_ADL_EXECUTION");
        assertEquals(1, published.size(), "应 publish 一条 ADL");
        assertInstanceOf(ApiAutoDeleveraging.class, published.get(0));
    }

    // ============================ onADLDone ============================

    @Test
    void onAdlDone_anyMatcherEvent_closesCtx_doesNotPublishAnything() {
        LiquidationEngine le = newLE();
        List<ApiCommand> published = new ArrayList<>();
        le.setCommandSubmitter((cmd, onApplied) -> published.add(cmd));

        SymbolPositionRecord pos = newPositionWithCtx(LiquidationState.WAIT_ADL_EXECUTION);
        OrderCommand cmd = newCmd(OrderCommandType.AUTO_DELEVERAGING, 100L, 5L);

        le.advanceLiquidation(cmd, pos);

        assertNull(pos.liquidationFlow, "ADL 完成 → flow 闭环置 null");
        assertTrue(published.isEmpty(), "ADL 完成不再 publish");
    }

    // ============================ 已有 flow 的幂等性 ============================

    @Test
    void nextLiquidationState_withExistingCtx_doesNotOverwriteCtx() {
        // 已有 flow 走 expected-state 路径，不重新 bootstrap：bankruptcyPrice 应保留 flow 端值，cmd.price 被忽略。
        // 用 REJECT 让强平流程推进到 WAIT_IF_EXECUTION，flow 留下来供断言（TRADE 会闭环置 null）。
        LiquidationEngine le = newLE();
        le.setCommandSubmitter((cmd, onApplied) -> {});

        SymbolPositionRecord pos = newPositionWithCtx(LiquidationState.LIQUIDATING);
        LiquidationFlow originalCtx = pos.liquidationFlow;
        long originalPrice = originalCtx.bankruptcyPrice;

        // cmd 的 price 跟 flow 不同，验证不会覆盖
        OrderCommand cmd = newForceLiquidationCmd(MatcherEventType.REJECT, 4L);
        cmd.price = 88888L;

        le.advanceLiquidation(cmd, pos);

        assertSame(originalCtx, pos.liquidationFlow, "flow 实例不应被替换");
        assertEquals(originalPrice, pos.liquidationFlow.bankruptcyPrice, "flow.bankruptcyPrice 应保留 flow 端值（cmd.price 被忽略）");
        // flow.size 被 onMarketDone 改成 firstEvent.size（4L）
        assertEquals(4L, pos.liquidationFlow.size, "REJECT 路径下 flow.size = firstEvent.size");
    }

    // ============================ helpers ============================

    private LiquidationEngine newLE() {
        le = new LiquidationEngine(null, 1, TEST_CFG);
        le.start();
        return le;
    }

    private static SymbolPositionRecord newPositionWithCtx(LiquidationState state) {
        SymbolPositionRecord pos = new SymbolPositionRecord();
        pos.uid = UID;
        pos.symbol = SYMBOL;
        pos.direction = PositionDirection.LONG;
        pos.openVolume = 10;
        LiquidationFlow ctx = new LiquidationFlow(100L, 5L, 0L);
        ctx.state = state;
        pos.liquidationFlow = ctx;
        return pos;
    }

    private static OrderCommand newForceLiquidationCmd(MatcherEventType eventType, long firstEventSize) {
        OrderCommand cmd = newCmd(OrderCommandType.FORCE_LIQUIDATION, 100L, firstEventSize);
        cmd.matcherEvent = newMatcherEvent(eventType, firstEventSize);
        return cmd;
    }

    private static OrderCommand newIfTakeoverCmd(MatcherEventType eventType, long firstEventSize) {
        OrderCommand cmd = newCmd(OrderCommandType.IF_TAKEOVER, 100L, firstEventSize);
        cmd.matcherEvent = newMatcherEvent(eventType, firstEventSize);
        return cmd;
    }

    private static OrderCommand newCmd(OrderCommandType type, long price, long size) {
        OrderCommand cmd = new OrderCommand(1);
        cmd.command = type;
        cmd.orderId = 12345L;
        cmd.uid = UID;
        cmd.symbol = SYMBOL;
        cmd.action = OrderAction.ASK;
        cmd.price = price;
        cmd.size = size;
        return cmd;
    }

    private static MatcherTradeEvent newMatcherEvent(MatcherEventType type, long size) {
        MatcherTradeEvent e = new MatcherTradeEvent();
        e.eventType = type;
        e.size = size;
        return e;
    }
}
