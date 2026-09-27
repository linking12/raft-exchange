package com.binance.raftexchange.client;

import com.binance.raftexchange.stubs.CoreSymbolSpecification;
import com.binance.raftexchange.stubs.response.CommandResult;
import com.binance.raftexchange.stubs.response.CommandResultCode;
import com.binance.raftexchange.stubs.response.L2MarketData;
import com.binance.raftexchange.stubs.response.MatcherEventType;
import com.binance.raftexchange.stubs.response.MatcherTradeEvent;
import com.binance.raftexchange.stubs.response.OrderCommand;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * CommandResultView 还原口径单测。覆盖：
 * <ul>
 * <li>resultCode-only / orderCommand-only / 两者皆无三种 oneof 形态</li>
 * <li>MatcherTradeEvent 用 PB 自带的 baseScaleK/quoteScaleK 还原（不查 metadata）</li>
 * <li>MatcherTradeEvent 链表 next_event 顺序与还原</li>
 * <li>L2MarketData 需要 metadata，但 spec 缺失时只让 marketData 降级为 null，整个 view 仍返回</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class CommandResultViewTest {

    private static final int SPOT_SYMBOL = 11;

    @Mock
    private ExchangeMetadataManager metadataManager;

    // ---------- oneof shape ----------

    @Nested
    @DisplayName("CommandResult oneof shape")
    class OneOfShape {

        @Test
        @DisplayName("只有 resultCode：matcherEvent / marketData 都为 null，不查 metadata")
        void resultCodeOnly() {
            CommandResult pb = CommandResult.newBuilder().setResultCode(CommandResultCode.SUCCESS).build();

            CommandResultView view = CommandResultView.build(pb, metadataManager);

            assertEquals(CommandResultCode.SUCCESS, view.getResultCode());
            assertNull(view.getMatcherEvent());
            assertNull(view.getMarketData());
            verify(metadataManager, never()).getSymbolSpec(org.mockito.ArgumentMatchers.anyInt());
        }

        @Test
        @DisplayName("两个 oneof 都没设 → IllegalStateException")
        void noPayloadThrows() {
            CommandResult empty = CommandResult.newBuilder().build();
            assertThrows(IllegalStateException.class, () -> CommandResultView.build(empty, metadataManager));
        }
    }

    // ---------- MatcherTradeEvent ----------

    @Nested
    @DisplayName("MatcherTradeEvent 还原")
    class MatcherEvent {

        @Test
        @DisplayName("使用 PB 自带的 baseScaleK / quoteScaleK 还原，不依赖 metadata")
        void usesEmbeddedScales() {
            MatcherTradeEvent ev =
                MatcherTradeEvent.newBuilder().setEventType(MatcherEventType.TRADE).setActiveOrderCompleted(true)
                    .setMatchedOrderId(7777L).setMatchedOrderUid(42L).setMatchedOrderCompleted(false)
                    // base=10^3, quote=10^5；price 850.2 / size 0.5 / bidderHoldPrice 855
                    .setBaseScaleK(1_000L).setQuoteScaleK(100_000L).setPrice(850_20000L).setSize(500L)
                    .setBidderHoldPrice(855_00000L).build();

            CommandResult pb = CommandResult.newBuilder().setOrderCommand(OrderCommand.newBuilder()
                .setResultCode(CommandResultCode.SUCCESS).setSymbol(SPOT_SYMBOL).setMatcherEvent(ev).build()).build();

            CommandResultView view = CommandResultView.build(pb, metadataManager);
            CommandResultView.MatcherTradeEventView mv = view.getMatcherEvent();

            assertNotNull(mv);
            assertEquals(MatcherEventType.TRADE, mv.getEventType());
            assertEquals(7777L, mv.getMatchedOrderId());
            assertEquals(42L, mv.getMatchedOrderUid());
            assertEqualsBD(new BigDecimal("850.2"), mv.getPrice());
            assertEqualsBD(new BigDecimal("0.5"), mv.getSize());
            assertEqualsBD(new BigDecimal("855"), mv.getBidderHoldPrice());
            assertNull(mv.getNextEvent());

            // matcherEvent 路径不会去查 symbol spec
            verify(metadataManager, never()).getSymbolSpec(org.mockito.ArgumentMatchers.anyInt());
        }

        @Test
        @DisplayName("链表 next_event：3 段事件都被还原，链表顺序保留")
        void chainPreservesOrder() {
            MatcherTradeEvent third = MatcherTradeEvent.newBuilder().setBaseScaleK(1_000L).setQuoteScaleK(100_000L)
                .setPrice(800_00000L).setSize(100L).build();
            MatcherTradeEvent second = MatcherTradeEvent.newBuilder().setBaseScaleK(1_000L).setQuoteScaleK(100_000L)
                .setPrice(810_00000L).setSize(200L).setNextEvent(third).build();
            MatcherTradeEvent head = MatcherTradeEvent.newBuilder().setBaseScaleK(1_000L).setQuoteScaleK(100_000L)
                .setPrice(820_00000L).setSize(300L).setNextEvent(second).build();

            CommandResult pb = CommandResult.newBuilder()
                .setOrderCommand(
                    OrderCommand.newBuilder().setResultCode(CommandResultCode.SUCCESS).setMatcherEvent(head).build())
                .build();

            CommandResultView.MatcherTradeEventView v1 = CommandResultView.build(pb, metadataManager).getMatcherEvent();
            assertEqualsBD(new BigDecimal("820"), v1.getPrice());
            assertEqualsBD(new BigDecimal("0.3"), v1.getSize());

            CommandResultView.MatcherTradeEventView v2 = v1.getNextEvent();
            assertNotNull(v2);
            assertEqualsBD(new BigDecimal("810"), v2.getPrice());
            assertEqualsBD(new BigDecimal("0.2"), v2.getSize());

            CommandResultView.MatcherTradeEventView v3 = v2.getNextEvent();
            assertNotNull(v3);
            assertEqualsBD(new BigDecimal("800"), v3.getPrice());
            assertEqualsBD(new BigDecimal("0.1"), v3.getSize());

            assertNull(v3.getNextEvent());
        }

        @Test
        @DisplayName("baseScaleK / quoteScaleK = 0 → 还原成 ZERO 而不是 NaN")
        void zeroScaleYieldsZero() {
            MatcherTradeEvent ev =
                MatcherTradeEvent.newBuilder().setPrice(123L).setSize(456L).setBidderHoldPrice(789L).build(); // 默认
                                                                                                              // baseScaleK
                                                                                                              // = 0,
                                                                                                              // quoteScaleK
                                                                                                              // = 0
            CommandResult pb = CommandResult.newBuilder()
                .setOrderCommand(
                    OrderCommand.newBuilder().setResultCode(CommandResultCode.SUCCESS).setMatcherEvent(ev).build())
                .build();

            CommandResultView.MatcherTradeEventView mv = CommandResultView.build(pb, metadataManager).getMatcherEvent();

            assertEqualsBD(BigDecimal.ZERO, mv.getPrice());
            assertEqualsBD(BigDecimal.ZERO, mv.getSize());
            assertEqualsBD(BigDecimal.ZERO, mv.getBidderHoldPrice());
        }
    }

    // ---------- L2MarketData ----------

    @Nested
    @DisplayName("L2MarketData 还原")
    class MarketData {

        @Test
        @DisplayName("用 symbol spec 还原 ask/bid prices & volumes")
        void restoresLevels() {
            when(metadataManager.getSymbolSpec(SPOT_SYMBOL)).thenReturn(symbolSpec(SPOT_SYMBOL, 1_000L, 100_000L));

            L2MarketData md = L2MarketData.newBuilder().setAskSizes(2).setBidSizes(2).addAskPrices(850_00000L)
                .addAskPrices(851_00000L) // 850, 851
                .addAskVolumes(500L).addAskVolumes(1_000L) // 0.5, 1.0
                .addAskOrders(3L).addAskOrders(4L).addBidPrices(849_00000L).addBidPrices(848_00000L) // 849, 848
                .addBidVolumes(200L).addBidVolumes(800L) // 0.2, 0.8
                .addBidOrders(1L).addBidOrders(2L).build();

            CommandResult pb = CommandResult.newBuilder().setOrderCommand(OrderCommand.newBuilder()
                .setResultCode(CommandResultCode.SUCCESS).setSymbol(SPOT_SYMBOL).setMarketData(md).build()).build();

            CommandResultView.L2MarketDataView mdv = CommandResultView.build(pb, metadataManager).getMarketData();

            assertNotNull(mdv);
            assertEquals(2, mdv.getAskSize());
            assertEquals(2, mdv.getBidSize());

            List<BigDecimal> askPrices = mdv.getAskPrices();
            assertEqualsBD(new BigDecimal("850"), askPrices.get(0));
            assertEqualsBD(new BigDecimal("851"), askPrices.get(1));

            List<BigDecimal> askVolumes = mdv.getAskVolumes();
            assertEqualsBD(new BigDecimal("0.5"), askVolumes.get(0));
            assertEqualsBD(new BigDecimal("1"), askVolumes.get(1));

            assertEquals(List.of(3L, 4L), mdv.getAskOrders());

            List<BigDecimal> bidPrices = mdv.getBidPrices();
            assertEqualsBD(new BigDecimal("849"), bidPrices.get(0));
            assertEqualsBD(new BigDecimal("848"), bidPrices.get(1));

            List<BigDecimal> bidVolumes = mdv.getBidVolumes();
            assertEqualsBD(new BigDecimal("0.2"), bidVolumes.get(0));
            assertEqualsBD(new BigDecimal("0.8"), bidVolumes.get(1));

            assertEquals(List.of(1L, 2L), mdv.getBidOrders());
        }

        @Test
        @DisplayName("symbol spec 缺失：marketData 降级为 null，matcherEvent 仍正常还原")
        void specMissingDegradesMarketDataOnly() {
            when(metadataManager.getSymbolSpec(SPOT_SYMBOL)).thenThrow(new IllegalArgumentException("unknown symbol"));

            MatcherTradeEvent ev = MatcherTradeEvent.newBuilder().setBaseScaleK(1_000L).setQuoteScaleK(100_000L)
                .setPrice(850_00000L).setSize(500L).build();

            CommandResult pb = CommandResult.newBuilder()
                .setOrderCommand(OrderCommand.newBuilder().setResultCode(CommandResultCode.SUCCESS)
                    .setSymbol(SPOT_SYMBOL).setMatcherEvent(ev)
                    .setMarketData(L2MarketData.newBuilder().setAskSizes(0).setBidSizes(0).build()).build())
                .build();

            CommandResultView view = CommandResultView.build(pb, metadataManager);

            assertEquals(CommandResultCode.SUCCESS, view.getResultCode());
            assertNull(view.getMarketData(), "marketData 应该降级为 null");
            assertNotNull(view.getMatcherEvent(), "matcherEvent 不应受 marketData 失败影响");
            assertEqualsBD(new BigDecimal("850"), view.getMatcherEvent().getPrice());
        }
    }

    // ---------- helpers ----------

    private static CoreSymbolSpecification symbolSpec(int symbolId, long baseScaleK, long quoteScaleK) {
        return CoreSymbolSpecification.newBuilder().setSymbolId(symbolId).setBaseScaleK(baseScaleK)
            .setQuoteScaleK(quoteScaleK).build();
    }

    private static void assertEqualsBD(BigDecimal expected, BigDecimal actual) {
        assertEquals(0, actual.compareTo(expected),
            () -> "expected " + expected.toPlainString() + " but got " + actual.toPlainString());
    }
}
