//! 对应 Java `exchange.core2.core.SimpleEventsProcessor`：把命令上的撮合事件链 + 资金事件 + L2 快照
//! 翻译成对外执行回报，回调 `TradeEventsHandler` / `FundEventsHandler`。
//!
//! 移植取向：单节点无 disruptor，`seq` 由调用方给；`process_command` 一次跑完 R1+ME+R2，故不设 Java 的负 seq
//! （R2-only 资金事件）分支——执行回报 + 资金事件 + 行情一次下发。
use crate::core::common::cmd::command_result_code::CommandResultCode;
use crate::core::common::cmd::order_command::OrderCommand;
use crate::core::common::cmd::order_command_type::OrderCommandType;
use crate::core::common::core_symbol_specification::CoreSymbolSpecification;
use crate::core::common::matcher_event_type::MatcherEventType;
use crate::core::common::position_mode::PositionMode;
use crate::core::common::symbol_type::SymbolType;
use crate::core::fund_events_handler::{FundEventReport, FundEventsHandler};
use crate::core::trade_events_handler::{
    ExecutionIdGenerator, FuturesExecutionReport, OrderBook, OrderBookRecord, SpotExecutionReport, TradeEventsHandler,
};
use crate::core::exchange_core::ExchangeCore;

/// 对应 Java `SimpleEventsProcessor`。持有两个 handler；`process` 需 `&mut self`（handler 回调 `&mut`，本移植不引入内部可变性）。
pub struct SimpleEventsProcessor<T: TradeEventsHandler, F: FundEventsHandler> {
    trade: T,
    fund: F,
}

impl<T: TradeEventsHandler, F: FundEventsHandler> SimpleEventsProcessor<T, F> {
    // ===== 构造 =====
    pub fn new(trade: T, fund: F) -> Self {
        SimpleEventsProcessor { trade, fund }
    }

    // ===== 核心行为 =====
    /// 对应 Java `SimpleEventsProcessor.accept`（正 seq 分支）。
    pub fn process(&mut self, core: &ExchangeCore, cmd: &OrderCommand, seq: i64) {
        self.send_execution_report(core, cmd, seq);
        self.send_fund_events(cmd, seq);
        self.send_market_data(core, cmd);
    }

    // ===== 访问器 =====
    pub fn trade_handler(&self) -> &T {
        &self.trade
    }

    pub fn fund_handler(&self) -> &F {
        &self.fund
    }

    pub fn into_handlers(self) -> (T, F) {
        (self.trade, self.fund)
    }

    // ===== 内部 helper =====
    fn send_execution_report(&mut self, core: &ExchangeCore, cmd: &OrderCommand, seq: i64) {
        if !is_reportable_command(cmd.command) {
            return;
        }
        let Some(spec) = core.ssp.get_symbol(cmd.symbol) else { return };
        match spec.symbol_type {
            SymbolType::CurrencyExchangePair => self.send_spot_execution_report(cmd, seq, spec),
            SymbolType::FuturesContractPerpetual | SymbolType::FuturesContractDelivery => {
                self.send_futures_execution_report(core, cmd, seq, spec)
            }
            // 对齐 Java switch：其余类型无执行回报。
            SymbolType::Option => {}
        }
    }

    fn send_spot_execution_report(&mut self, cmd: &OrderCommand, seq: i64, spec: &CoreSymbolSpecification) {
        let first = cmd.matcher_event.as_deref();
        if cmd.command == OrderCommandType::PlaceOrder && cmd.result_code == Some(CommandResultCode::Success) {
            self.trade.spot_execution_report(SpotExecutionReport::place_order(cmd, seq, spec));
        }
        if let Some(ev) = first {
            if ev.event_type == MatcherEventType::Reject {
                self.trade.spot_execution_report(SpotExecutionReport::reject_order(cmd, seq, spec));
            }
        }
        if (cmd.command == OrderCommandType::CancelOrder || cmd.command == OrderCommandType::ReduceOrder)
            && cmd.result_code == Some(CommandResultCode::Success)
        {
            if let Some(ev) = first {
                if ev.event_type == MatcherEventType::Reduce {
                    self.trade.spot_execution_report(SpotExecutionReport::reduce_order(cmd, seq, spec, ev));
                    return;
                }
            }
        }
        let mut trade_index = 0;
        let mut cur = first;
        while let Some(ev) = cur {
            if ev.event_type == MatcherEventType::Trade {
                self.trade.spot_execution_report(SpotExecutionReport::trade_taker(cmd, seq, spec, ev, trade_index));
                self.trade.spot_execution_report(SpotExecutionReport::trade_maker(cmd, seq, spec, ev, trade_index));
                trade_index += 1;
            }
            cur = ev.next.as_deref();
        }
    }

    fn send_futures_execution_report(&mut self, core: &ExchangeCore, cmd: &OrderCommand, seq: i64, spec: &CoreSymbolSpecification) {
        let first = cmd.matcher_event.as_deref();
        let taker_side = position_mode_of(core, cmd.uid);
        if cmd.command == OrderCommandType::PlaceOrder && cmd.result_code == Some(CommandResultCode::Success) {
            self.trade.futures_execution_report(FuturesExecutionReport::place_order(cmd, seq, spec, taker_side));
        }
        if let Some(ev) = first {
            if ev.event_type == MatcherEventType::Reject {
                self.trade.futures_execution_report(FuturesExecutionReport::reject_order(cmd, seq, spec, taker_side));
            }
        }
        if (cmd.command == OrderCommandType::CancelOrder || cmd.command == OrderCommandType::ReduceOrder)
            && cmd.result_code == Some(CommandResultCode::Success)
        {
            if let Some(ev) = first {
                if ev.event_type == MatcherEventType::Reduce {
                    self.trade.futures_execution_report(FuturesExecutionReport::reduce_order(cmd, seq, spec, taker_side, ev));
                    return;
                }
            }
        }
        let mut trade_index = 0;
        let mut cur = first;
        while let Some(ev) = cur {
            if ev.event_type == MatcherEventType::Trade {
                self.trade.futures_execution_report(FuturesExecutionReport::trade_taker(cmd, seq, spec, taker_side, ev, trade_index));
                let maker_side = position_mode_of(core, ev.matched_order_uid);
                self.trade.futures_execution_report(FuturesExecutionReport::trade_maker(cmd, seq, spec, maker_side, ev, trade_index));
                trade_index += 1;
            }
            cur = ev.next.as_deref();
        }
    }

    /// 对应 Java `SimpleEventsProcessor.sendFundEvents`：本移植 `cmd.fund_events` 已是单一有序流，逐条编号下发。
    fn send_fund_events(&mut self, cmd: &OrderCommand, seq: i64) {
        for (index, fe) in cmd.fund_events.iter().enumerate() {
            let uni_id = ExecutionIdGenerator::build_trade_exec_id(seq, index as i32, false);
            self.fund.fund_event_report(FundEventReport::from_fund_event(fe, uni_id));
        }
    }

    /// 对应 Java `SimpleEventsProcessor.sendMarketData`。
    fn send_market_data(&mut self, core: &ExchangeCore, cmd: &OrderCommand) {
        let Some(md) = &cmd.market_data else { return };
        let asks = md
            .ask_prices
            .iter()
            .zip(&md.ask_volumes)
            .zip(&md.ask_orders)
            .map(|((&price, &volume), &orders)| OrderBookRecord { price, volume, orders: orders as i32 })
            .collect();
        let bids = md
            .bid_prices
            .iter()
            .zip(&md.bid_volumes)
            .zip(&md.bid_orders)
            .map(|((&price, &volume), &orders)| OrderBookRecord { price, volume, orders: orders as i32 })
            .collect();
        let (base_scale_k, quote_scale_k) = match core.ssp.get_symbol(cmd.symbol) {
            Some(spec) => (spec.base_scale_k, spec.quote_scale_k),
            None => (0, 0),
        };
        self.trade.order_book(OrderBook { symbol: cmd.symbol, asks, bids, timestamp: cmd.timestamp, base_scale_k, quote_scale_k });
    }
}

fn is_reportable_command(command: OrderCommandType) -> bool {
    matches!(
        command,
        OrderCommandType::PlaceOrder
            | OrderCommandType::CancelOrder
            | OrderCommandType::MoveOrder
            | OrderCommandType::ReduceOrder
            | OrderCommandType::ClosePosition
            | OrderCommandType::ForceLiquidation
    )
}

fn position_mode_of(core: &ExchangeCore, uid: i64) -> PositionMode {
    core.ups.get(uid).map(|u| u.position_mode).unwrap_or_default()
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::core::common::core_currency_specification::CoreCurrencySpecification;
    use crate::core::common::core_symbol_specification::CoreSymbolSpecification;
    use crate::core::common::order_action::OrderAction;
    use crate::core::common::order_type::OrderType;
    use crate::core::fund_events_handler::FundEventReport;
    use crate::core::trade_events_handler::{ExecType, FuturesExecutionReport, OrderBook, SpotExecutionReport};

    const BASE: i32 = 10;
    const QUOTE: i32 = 20;
    const SYMBOL: i32 = 1;
    const SELLER: i64 = 1;
    const BUYER: i64 = 2;

    #[derive(Default)]
    struct TradeRec {
        spot: Vec<SpotExecutionReport>,
        futures: Vec<FuturesExecutionReport>,
        books: Vec<OrderBook>,
    }
    impl TradeEventsHandler for TradeRec {
        fn order_book(&mut self, ob: OrderBook) {
            self.books.push(ob);
        }
        fn spot_execution_report(&mut self, r: SpotExecutionReport) {
            self.spot.push(r);
        }
        fn futures_execution_report(&mut self, r: FuturesExecutionReport) {
            self.futures.push(r);
        }
    }

    #[derive(Default)]
    struct FundRec {
        fund: Vec<FundEventReport>,
    }
    impl FundEventsHandler for FundRec {
        fn fund_event_report(&mut self, r: FundEventReport) {
            self.fund.push(r);
        }
    }

    fn run(core: &mut ExchangeCore, cmd: &mut OrderCommand) {
        core.process_command(cmd);
    }

    #[test]
    fn spot_trade_emits_new_taker_maker_reports_and_fund_events() {
        let mut core = ExchangeCore::new();
        core.ssp.add_currency(CoreCurrencySpecification { currency: BASE, currency_scale_k: 1, ..Default::default() });
        core.ssp.add_currency(CoreCurrencySpecification { currency: QUOTE, currency_scale_k: 1, ..Default::default() });
        let spec = CoreSymbolSpecification {
            symbol_id: SYMBOL,
            symbol_type: SymbolType::CurrencyExchangePair,
            base_currency: BASE,
            quote_currency: QUOTE,
            base_scale_k: 1,
            quote_scale_k: 1,
            ..Default::default()
        };
        assert_eq!(core.ssp.add_symbol(spec.clone()), CommandResultCode::Success);
        core.matching.add_symbol(&spec);

        for uid in [SELLER, BUYER] {
            let mut c = OrderCommand { command: OrderCommandType::AddUser, uid, ..Default::default() };
            run(&mut core, &mut c);
        }
        // 卖方备 BASE，买方备 QUOTE。
        let mut c = OrderCommand { command: OrderCommandType::BalanceAdjustment, uid: SELLER, symbol: BASE, price: 1_000, order_id: 1, ..Default::default() };
        run(&mut core, &mut c);
        let mut c = OrderCommand { command: OrderCommandType::BalanceAdjustment, uid: BUYER, symbol: QUOTE, price: 1_000_000, order_id: 2, ..Default::default() };
        run(&mut core, &mut c);

        // maker：卖 @100 size 10。
        let mut maker = OrderCommand {
            command: OrderCommandType::PlaceOrder,
            order_id: 100,
            uid: SELLER,
            symbol: SYMBOL,
            price: 100,
            size: 10,
            action: Some(OrderAction::Ask),
            order_type: Some(OrderType::Gtc),
            ..Default::default()
        };
        run(&mut core, &mut maker);

        // taker：买 @100 size 10（全量吃满 maker）。
        let mut taker = OrderCommand {
            command: OrderCommandType::PlaceOrder,
            order_id: 101,
            uid: BUYER,
            symbol: SYMBOL,
            price: 100,
            size: 10,
            reserve_bid_price: 100,
            action: Some(OrderAction::Bid),
            order_type: Some(OrderType::Gtc),
            user_cookie: 77,
            ..Default::default()
        };
        run(&mut core, &mut taker);

        assert_eq!(taker.result_code, Some(CommandResultCode::Success));

        let mut proc = SimpleEventsProcessor::new(TradeRec::default(), FundRec::default());
        proc.process(&core, &taker, 5);

        let tr = proc.trade_handler();
        // NEW + taker TRADE + maker TRADE。
        assert_eq!(tr.spot.len(), 3, "spot reports: {:?}", tr.spot);
        assert_eq!(tr.spot[0].execution_type, ExecType::New);
        let trades: Vec<&SpotExecutionReport> = tr.spot.iter().filter(|r| r.execution_type == ExecType::Trade).collect();
        assert_eq!(trades.len(), 2);
        assert!(trades.iter().any(|r| !r.is_maker), "缺 taker TRADE");
        assert!(trades.iter().any(|r| r.is_maker), "缺 maker TRADE");
        // taker TRADE 视角：买方 uid、last_qty=10、last_price=100。
        let taker_trade = trades.iter().find(|r| !r.is_maker).unwrap();
        assert_eq!(taker_trade.account_id, BUYER);
        assert_eq!(taker_trade.last_qty, 10);
        assert_eq!(taker_trade.mark_price, 100);
        assert_eq!(taker_trade.cl_ord_id, 77);
        // maker TRADE 视角：卖方 uid。
        let maker_trade = trades.iter().find(|r| r.is_maker).unwrap();
        assert_eq!(maker_trade.account_id, SELLER);
        assert_eq!(maker_trade.order_id, 100);

        assert!(!proc.fund_handler().fund.is_empty(), "现货成交应产生资金事件回报");
    }
}
