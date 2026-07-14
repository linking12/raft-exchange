/*
 * Copyright 2019 Maksim Zheravin
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package exchange.core2.tests.unit;

import exchange.core2.core.common.MatcherEventType;
import exchange.core2.core.common.MatcherTradeEvent;
import exchange.core2.core.common.cmd.CommandResultCode;
import exchange.core2.core.common.cmd.OrderCommand;
import exchange.core2.core.orderbook.OrderBookEventsHelper;
import exchange.core2.core.processors.LoanRatePricingProcessor;
import exchange.core2.core.processors.RiskEngine;
import exchange.core2.core.processors.loan.LoanService;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * REPRICE_LOAN_RATES 两步处理器（loan.md §13.2）：R1 key 编码收池子 → merge 跨 shard 求全局利用率 → R2 过曲线写利率。
 * 默认曲线 base=200/kink=8000/slope1=400/slope2=6000。
 */
class LoanRatePricingProcessorTest {

    private static final int USDT = 2;

    private static OrderBookEventsHelper freshEventsHelper() {
        OrderBookEventsHelper eh = mock(OrderBookEventsHelper.class);
        when(eh.newMatcherEvent()).thenAnswer(i -> new MatcherTradeEvent());
        return eh;
    }

    @Test
    void collectInput_packsBorrowedAtCcy_availableAtNotCcy() {
        LoanService svc = new LoanService();
        svc.getLoanPoolBorrowed().put(USDT, 300L);
        svc.getLoanPoolAvailable().put(USDT, 700L);
        RiskEngine engine = mock(RiskEngine.class);
        when(engine.getLoanService()).thenReturn(svc);
        when(engine.getShardId()).thenReturn(0);

        OrderCommand cmd = new OrderCommand(2);
        new LoanRatePricingProcessor(engine).collectInput(cmd);

        assertEquals(300L, cmd.commonByShard[0].amounts.get(USDT), "borrowed → key=currency");
        assertEquals(700L, cmd.commonByShard[0].amounts.get(~USDT), "available → key=~currency");
    }

    @Test
    void merge_sumsAcrossShards_thenR2AppliesCurve() {
        // shard0: borrowed 300 / available 700 ; shard1: borrowed 100 / available 900
        // 全局 borrowed=400 available=1600 → util = 400×10000/2000 = 2000
        OrderCommand cmd = new OrderCommand(2);
        cmd.commonByShard[0].amounts.put(USDT, 300L);
        cmd.commonByShard[0].amounts.put(~USDT, 700L);
        cmd.commonByShard[1].amounts.put(USDT, 100L);
        cmd.commonByShard[1].amounts.put(~USDT, 900L);

        cmd.resultCode = CommandResultCode.VALID_FOR_MATCHING_ENGINE;
        new LoanRatePricingProcessor(freshEventsHelper()).process(cmd);

        MatcherTradeEvent ev = cmd.matcherEvent;
        assertEquals(MatcherEventType.LOAN_REPRICE_EVENT, ev.eventType);
        assertEquals(USDT, ev.matchedOrderUid, "载 currency");
        assertEquals(2000L, ev.size, "载全局利用率 20%");
        assertNull(ev.nextEvent, "单币种一条");

        // R2：util 2000 (<kink) → 曲线 200 + 400×2000/8000 = 300
        LoanService svc = new LoanService();
        RiskEngine engine = mock(RiskEngine.class);
        when(engine.getLoanService()).thenReturn(svc);
        new LoanRatePricingProcessor(engine).applyEvent(cmd, ev, null, null);
        assertEquals(300L, svc.getFloatingRate().getCurrentRateBps().get(USDT));
    }

    @Test
    void merge_availableOnlyCurrency_utilZero_pricesAtBase() {
        // 池子有钱没人借：util=0 → 曲线 = base = 200
        OrderCommand cmd = new OrderCommand(1);
        cmd.commonByShard[0].amounts.put(~USDT, 1000L);
        cmd.resultCode = CommandResultCode.VALID_FOR_MATCHING_ENGINE;
        new LoanRatePricingProcessor(freshEventsHelper()).process(cmd);

        assertEquals(0L, cmd.matcherEvent.size, "util=0");
        LoanService svc = new LoanService();
        RiskEngine engine = mock(RiskEngine.class);
        when(engine.getLoanService()).thenReturn(svc);
        new LoanRatePricingProcessor(engine).applyEvent(cmd, cmd.matcherEvent, null, null);
        assertEquals(200L, svc.getFloatingRate().getCurrentRateBps().get(USDT), "空借出 → 基础利率");
    }

    @Test
    void merge_emptyPool_noEvents() {
        OrderCommand cmd = new OrderCommand(2);
        cmd.resultCode = CommandResultCode.VALID_FOR_MATCHING_ENGINE;
        new LoanRatePricingProcessor(freshEventsHelper()).process(cmd);
        assertNull(cmd.matcherEvent, "无池子 → 无 reprice 事件");
    }

    @Test
    void applyEvent_ignoresNonRepriceEvent() {
        LoanService svc = new LoanService();
        RiskEngine engine = mock(RiskEngine.class);
        when(engine.getLoanService()).thenReturn(svc);
        MatcherTradeEvent ev = new MatcherTradeEvent();
        ev.eventType = MatcherEventType.TRADE;
        new LoanRatePricingProcessor(engine).applyEvent(new OrderCommand(1), ev, null, null);
        assertEquals(0L, svc.getFloatingRate().getCurrentRateBps().get(USDT), "非 reprice 事件不写利率");
    }
}
