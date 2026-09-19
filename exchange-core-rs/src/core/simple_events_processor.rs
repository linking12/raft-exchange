use crate::core::common::cmd::command_result_code::CommandResultCode;
use crate::core::common::cmd::order_command::OrderCommand;
use crate::core::common::cmd::order_command_type::OrderCommandType;
use crate::core::common::core_symbol_specification::CoreSymbolSpecification;
use crate::core::common::matcher_event_type::MatcherEventType;
use crate::core::common::position_mode::PositionMode;
use crate::core::common::symbol_type::SymbolType;
use crate::core::fund_events_handler::{FundEventReport, FundEventsHandler};
use crate::core::processors::symbol_specification_provider::SymbolSpecificationProvider;
use crate::core::processors::user_profile_service::UserProfileService;
use crate::core::trade_events_handler::{
    ExecutionIdGenerator, FuturesExecutionReport, OrderBook, OrderBookRecord, SpotExecutionReport, TradeEventsHandler,
};

pub struct SimpleEventsProcessor<T: TradeEventsHandler, F: FundEventsHandler> {
    trade: T,
    fund: F,
}

#[derive(Debug, Default, Clone, Copy)]
pub struct LoggingEventsHandler;

impl TradeEventsHandler for LoggingEventsHandler {
    fn order_book(&mut self, order_book: OrderBook) {
        println!("order book: {:?}", order_book);
    }
    fn spot_execution_report(&mut self, report: SpotExecutionReport) {
        println!("spot execution report: {:?}", report);
    }
    fn futures_execution_report(&mut self, report: FuturesExecutionReport) {
        println!("futures execution report: {:?}", report);
    }
}

impl FundEventsHandler for LoggingEventsHandler {
    fn fund_event_report(&mut self, report: FundEventReport) {
        println!("fund event report: {:?}", report);
    }
}

#[derive(Debug, Default, Clone, Copy)]
pub struct NoopEventsHandler;

impl TradeEventsHandler for NoopEventsHandler {
    fn order_book(&mut self, _order_book: OrderBook) {}
    fn spot_execution_report(&mut self, _report: SpotExecutionReport) {}
    fn futures_execution_report(&mut self, _report: FuturesExecutionReport) {}
}

impl FundEventsHandler for NoopEventsHandler {
    fn fund_event_report(&mut self, _report: FundEventReport) {}
}

impl<T: TradeEventsHandler, F: FundEventsHandler> crate::core::exchange_core::ResultsConsumer for SimpleEventsProcessor<T, F> {
    fn consume(&mut self, cmd: &OrderCommand, seq: i64, ssp: &SymbolSpecificationProvider, ups: &UserProfileService) {
        self.process(cmd, seq, ssp, ups);
    }
}

impl<T: TradeEventsHandler, F: FundEventsHandler> SimpleEventsProcessor<T, F> {
    pub fn new(trade: T, fund: F) -> Self {
        SimpleEventsProcessor { trade, fund }
    }

    pub fn process(
        &mut self,
        cmd: &OrderCommand,
        seq: i64,
        ssp: &SymbolSpecificationProvider,
        ups: &UserProfileService,
    ) {
        self.send_execution_report(cmd, seq, ssp, ups);
        self.send_fund_events(cmd, seq);
        self.send_market_data(cmd, ssp);
    }

    pub fn trade_handler(&self) -> &T {
        &self.trade
    }

    pub fn fund_handler(&self) -> &F {
        &self.fund
    }

    pub fn into_handlers(self) -> (T, F) {
        (self.trade, self.fund)
    }

    fn send_execution_report(
        &mut self,
        cmd: &OrderCommand,
        seq: i64,
        ssp: &SymbolSpecificationProvider,
        ups: &UserProfileService,
    ) {
        if !is_reportable_command(cmd.command) {
            return;
        }
        let Some(spec) = ssp.get_symbol(cmd.symbol) else { return };
        match spec.symbol_type {
            SymbolType::CurrencyExchangePair => self.send_spot_execution_report(cmd, seq, spec),
            SymbolType::FuturesContractPerpetual | SymbolType::FuturesContractDelivery => {
                self.send_futures_execution_report(cmd, seq, spec, ups)
            }
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

    fn send_futures_execution_report(&mut self, cmd: &OrderCommand, seq: i64, spec: &CoreSymbolSpecification, ups: &UserProfileService) {
        let first = cmd.matcher_event.as_deref();
        let taker_side = position_mode_of(ups, cmd.uid);
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
                let maker_side = position_mode_of(ups, ev.matched_order_uid);
                self.trade.futures_execution_report(FuturesExecutionReport::trade_maker(cmd, seq, spec, maker_side, ev, trade_index));
                trade_index += 1;
            }
            cur = ev.next.as_deref();
        }
    }

    fn send_fund_events(&mut self, cmd: &OrderCommand, seq: i64) {
        for (index, fe) in cmd.fund_events.iter().enumerate() {
            let uni_id = ExecutionIdGenerator::build_trade_exec_id(seq, index as i32, false);
            self.fund.fund_event_report(FundEventReport::from_fund_event(fe, uni_id));
        }
    }

    fn send_market_data(&mut self, cmd: &OrderCommand, ssp: &SymbolSpecificationProvider) {
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
        let (base_scale_k, quote_scale_k) = match ssp.get_symbol(cmd.symbol) {
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

fn position_mode_of(ups: &UserProfileService, uid: i64) -> PositionMode {
    ups.get(uid).map(|u| u.position_mode).unwrap_or_default()
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::core::common::core_currency_specification::CoreCurrencySpecification;
    use crate::core::common::core_symbol_specification::CoreSymbolSpecification;
    use crate::core::common::order_action::OrderAction;
    use crate::core::common::order_type::OrderType;
    use crate::core::exchange_core::ExchangeCore;
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
        let mut c = OrderCommand { command: OrderCommandType::BalanceAdjustment, uid: SELLER, symbol: BASE, price: 1_000, order_id: 1, ..Default::default() };
        run(&mut core, &mut c);
        let mut c = OrderCommand { command: OrderCommandType::BalanceAdjustment, uid: BUYER, symbol: QUOTE, price: 1_000_000, order_id: 2, ..Default::default() };
        run(&mut core, &mut c);

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
        proc.process(&taker, 5, &core.ssp, &core.ups);

        let tr = proc.trade_handler();
        assert_eq!(tr.spot.len(), 3, "spot reports: {:?}", tr.spot);
        assert_eq!(tr.spot[0].execution_type, ExecType::New);
        let trades: Vec<&SpotExecutionReport> = tr.spot.iter().filter(|r| r.execution_type == ExecType::Trade).collect();
        assert_eq!(trades.len(), 2);
        assert!(trades.iter().any(|r| !r.is_maker), "missing taker TRADE");
        assert!(trades.iter().any(|r| r.is_maker), "missing maker TRADE");
        let taker_trade = trades.iter().find(|r| !r.is_maker).unwrap();
        assert_eq!(taker_trade.account_id, BUYER);
        assert_eq!(taker_trade.last_qty, 10);
        assert_eq!(taker_trade.mark_price, 100);
        assert_eq!(taker_trade.cl_ord_id, 77);
        let maker_trade = trades.iter().find(|r| r.is_maker).unwrap();
        assert_eq!(maker_trade.account_id, SELLER);
        assert_eq!(maker_trade.order_id, 100);

        assert!(!proc.fund_handler().fund.is_empty(), "spot trade should produce fund event reports");
    }

    #[test]
    fn wired_as_results_consumer_fires_per_command_via_process_command() {
        use std::cell::RefCell;
        use std::rc::Rc;

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
        let mut c = OrderCommand { command: OrderCommandType::BalanceAdjustment, uid: SELLER, symbol: BASE, price: 1_000, order_id: 1, ..Default::default() };
        run(&mut core, &mut c);
        let mut c = OrderCommand { command: OrderCommandType::BalanceAdjustment, uid: BUYER, symbol: QUOTE, price: 1_000_000, order_id: 2, ..Default::default() };
        run(&mut core, &mut c);
        let mut maker = OrderCommand {
            command: OrderCommandType::PlaceOrder, order_id: 100, uid: SELLER, symbol: SYMBOL,
            price: 100, size: 10, action: Some(OrderAction::Ask), order_type: Some(OrderType::Gtc), ..Default::default()
        };
        run(&mut core, &mut maker);

        let proc = Rc::new(RefCell::new(SimpleEventsProcessor::new(TradeRec::default(), FundRec::default())));
        core.with_results_consumer(Box::new(proc.clone()));

        let mut taker = OrderCommand {
            command: OrderCommandType::PlaceOrder, order_id: 101, uid: BUYER, symbol: SYMBOL,
            price: 100, size: 10, reserve_bid_price: 100, action: Some(OrderAction::Bid),
            order_type: Some(OrderType::Gtc), user_cookie: 77, ..Default::default()
        };
        core.process_command(&mut taker);

        let pr = proc.borrow();
        assert_eq!(pr.trade_handler().spot.len(), 3, "consumer fired for the taker command: NEW + taker/maker TRADE");
        assert!(!pr.fund_handler().fund.is_empty(), "consumer forwarded fund events too");
    }

    #[test]
    fn logging_events_handler_emits_events_through_process_command() {
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
        run(&mut core, &mut OrderCommand { command: OrderCommandType::BalanceAdjustment, uid: SELLER, symbol: BASE, price: 1_000, order_id: 1, ..Default::default() });
        run(&mut core, &mut OrderCommand { command: OrderCommandType::BalanceAdjustment, uid: BUYER, symbol: QUOTE, price: 1_000_000, order_id: 2, ..Default::default() });
        run(&mut core, &mut OrderCommand {
            command: OrderCommandType::PlaceOrder, order_id: 100, uid: SELLER, symbol: SYMBOL,
            price: 100, size: 10, action: Some(OrderAction::Ask), order_type: Some(OrderType::Gtc), ..Default::default()
        });

        let proc = SimpleEventsProcessor::new(LoggingEventsHandler, LoggingEventsHandler);
        core.with_results_consumer(Box::new(proc));

        let mut taker = OrderCommand {
            command: OrderCommandType::PlaceOrder, order_id: 101, uid: BUYER, symbol: SYMBOL,
            price: 100, size: 10, reserve_bid_price: 100, action: Some(OrderAction::Bid),
            order_type: Some(OrderType::Gtc), ..Default::default()
        };
        core.process_command(&mut taker);
        assert_eq!(taker.result_code, Some(CommandResultCode::Success), "trade ran through LoggingEventsHandler-backed results_consumer");
    }

    use crate::core::common::fund_event::{FundEvent, FundEventType};
    use crate::core::common::margin_mode::MarginMode;
    use crate::core::common::matcher_trade_event::MatcherTradeEvent;
    use crate::core::common::order_action::OrderAction as OA;
    use crate::core::common::position_direction::PositionDirection;
    use crate::core::trade_events_handler::OrderStatus;

    fn spot_core() -> ExchangeCore {
        let mut core = ExchangeCore::new();
        core.ssp.add_currency(CoreCurrencySpecification { currency: 1, currency_scale_k: 1000, ..Default::default() });
        core.ssp.add_currency(CoreCurrencySpecification { currency: 2, currency_scale_k: 1000, ..Default::default() });
        let spec = CoreSymbolSpecification {
            symbol_id: 3,
            symbol_type: SymbolType::CurrencyExchangePair,
            base_currency: 1,
            quote_currency: 2,
            base_scale_k: 1000,
            quote_scale_k: 1000,
            ..Default::default()
        };
        assert_eq!(core.ssp.add_symbol(spec), CommandResultCode::Success);
        core
    }

    fn sample_cancel_command() -> OrderCommand {
        OrderCommand {
            command: OrderCommandType::CancelOrder,
            order_id: 123,
            symbol: 3,
            price: 12800,
            size: 3,
            reserve_bid_price: 12800,
            action: Some(OA::Bid),
            order_type: Some(OrderType::Gtc),
            uid: 29851,
            timestamp: 1578930983745201,
            user_cookie: 44188,
            result_code: Some(CommandResultCode::Success),
            ..Default::default()
        }
    }

    fn sample_reduce_command() -> OrderCommand {
        OrderCommand {
            command: OrderCommandType::ReduceOrder,
            order_id: 123,
            symbol: 3,
            price: 52200,
            size: 3200,
            reserve_bid_price: 12800,
            action: Some(OA::Bid),
            order_type: Some(OrderType::Gtc),
            uid: 29851,
            timestamp: 1578930983745201,
            user_cookie: 44188,
            result_code: Some(CommandResultCode::Success),
            ..Default::default()
        }
    }

    fn sample_place_command() -> OrderCommand {
        OrderCommand {
            command: OrderCommandType::PlaceOrder,
            order_id: 123,
            symbol: 3,
            price: 52200,
            size: 3200,
            reserve_bid_price: 12800,
            action: Some(OA::Bid),
            order_type: Some(OrderType::Ioc),
            uid: 29851,
            timestamp: 1578930983745201,
            user_cookie: 44188,
            result_code: Some(CommandResultCode::Success),
            margin_mode: MarginMode::Isolated,
            ..Default::default()
        }
    }

    fn fund_locked_trade() -> FundEvent {
        FundEvent {
            event_type: FundEventType::Locked,
            order_id: 10,
            uid: 100,
            currency: 10,
            currency_scale_k: 1000,
            free: 0,
            locked: 10,
            symbol: 1,
            base_scale_k: 1000,
            quote_scale_k: 1000,
            ..Default::default()
        }
    }

    fn fund_unlocked_trade() -> FundEvent {
        FundEvent {
            event_type: FundEventType::Unlocked,
            order_id: 10,
            uid: 100,
            currency: 10,
            currency_scale_k: 1000,
            free: 0,
            locked: 10,
            symbol: 1,
            base_scale_k: 1000,
            quote_scale_k: 10000,
            direction: PositionDirection::Long,
            open_volume: 1,
            open_init_margin_sum: 2,
            open_price_sum: 3,
            profit: 4,
            pending_sell_size: 5,
            pending_buy_size: 6,
            pending_sell_avg_price: 7,
            pending_buy_avg_price: 8,
            leverage: 9,
            margin_mode: MarginMode::Isolated,
            extra_margin: 10,
            unrealized_profit: 11,
            liquidation_price: 12,
            margin_ratio_scale_k: 13,
            maintenance_margin_scale_k: 0,
            mark_price: 14,
            ..Default::default()
        }
    }

    fn run_proc(core: &ExchangeCore, cmd: &OrderCommand, seq: i64) -> (TradeRec, FundRec) {
        let mut proc = SimpleEventsProcessor::new(TradeRec::default(), FundRec::default());
        proc.process(cmd, seq, &core.ssp, &core.ups);
        proc.into_handlers()
    }

    #[test]
    fn should_handle_simple_command() {
        let core = spot_core();
        let mut cmd = sample_cancel_command();
        cmd.matcher_event = Some(Box::new(MatcherTradeEvent {
            event_type: MatcherEventType::Reduce,
            active_order_completed: true,
            ..Default::default()
        }));
        cmd.fund_events = vec![fund_locked_trade(), fund_unlocked_trade()];

        let (tr, fr) = run_proc(&core, &cmd, 192837);

        assert_eq!(tr.spot.len(), 1);
        assert_eq!(tr.futures.len(), 0);
        assert_eq!(fr.fund.len(), 2);

        let report = &tr.spot[0];
        assert_eq!(report.order_id, 123);
        assert_eq!(report.symbol, 3);
        assert_eq!(report.account_id, 29851);

        assert_eq!(fr.fund[0].event_type, FundEventType::Locked);
        assert_eq!(fr.fund[1].event_type, FundEventType::Unlocked);
    }

    #[test]
    fn should_handle_with_reduce_command() {
        let core = spot_core();
        let mut cmd = sample_reduce_command();
        cmd.matcher_event = Some(Box::new(MatcherTradeEvent {
            event_type: MatcherEventType::Reduce,
            active_order_completed: true,
            maker_order_id: 0,
            maker_order_completed: false,
            filled: 100,
            filled_notional: 10000,
            bidder_hold_price: 20100,
            ..Default::default()
        }));

        let (tr, fr) = run_proc(&core, &cmd, 192837);

        assert_eq!(tr.spot.len(), 1);
        assert_eq!(tr.futures.len(), 0);
        assert_eq!(fr.fund.len(), 0);

        let r = &tr.spot[0];
        assert_eq!(r.execution_type, ExecType::Reduce);
        assert_eq!(r.order_status, OrderStatus::Canceled);
        assert_eq!(r.symbol, 3);
        assert_eq!(r.base_scale_k, 1000);
        assert_eq!(r.quote_scale_k, 1000);
        assert_eq!(r.account_id, 29851);
        assert_eq!(r.cl_ord_id, 44188);
        assert_eq!(r.order_id, 123);
        assert_eq!(r.order_type, OrderType::Gtc);
        assert_eq!(r.side, OA::Bid);
        assert_eq!(r.qty, 3200);
        assert_eq!(r.price, 52200);
        assert_eq!(r.quote_order_qty, 0);
        assert_eq!(r.order_creation_time, 1578930983745201);
        assert_eq!(r.last_qty, 0);
        assert_eq!(r.mark_price, 0);
        assert_eq!(r.cumulative_qty, 100);
        assert_eq!(r.cumulative_quote_qty, 10000);
        assert_eq!(r.commission, 0);
        assert_eq!(r.commission_asset, 2);
        assert!(!r.is_maker);
        assert!(!r.working_indicator);
    }

    #[test]
    fn should_handle_with_single_trade() {
        let core = spot_core();
        let mut cmd = sample_place_command();
        cmd.matcher_event = Some(Box::new(MatcherTradeEvent {
            event_type: MatcherEventType::Trade,
            active_order_completed: false,
            maker_order_id: 276810,
            matched_order_uid: 10332,
            maker_order_completed: true,
            matched_order_command_type: OrderCommandType::PlaceOrder,
            matched_order_filled: 123,
            matched_order_filled_notional: 1000,
            matched_order_type: OrderType::Gtc,
            matched_order_price: 12233,
            matched_order_size: 23,
            matched_user_cookie: 778899,
            matched_order_timestamp: 177777777777,
            price: 20100,
            size: 8272,
            filled: 123,
            filled_notional: 1000,
            bidder_hold_price: 13233,
            ..Default::default()
        }));
        cmd.fund_events = vec![fund_locked_trade(), fund_unlocked_trade()];

        let (tr, fr) = run_proc(&core, &cmd, 192837);

        assert_eq!(tr.spot.len(), 3);
        assert_eq!(tr.futures.len(), 0);
        assert_eq!(fr.fund.len(), 2);

        let new_order = &tr.spot[0];
        assert_eq!(new_order.execution_type, ExecType::New);
        assert_eq!(new_order.order_status, OrderStatus::New);
        assert_eq!(new_order.symbol, 3);
        assert_eq!(new_order.base_scale_k, 1000);
        assert_eq!(new_order.quote_scale_k, 1000);
        assert_eq!(new_order.account_id, 29851);
        assert_eq!(new_order.cl_ord_id, 44188);
        assert_eq!(new_order.order_id, 123);
        assert_eq!(new_order.order_type, OrderType::Ioc);
        assert_eq!(new_order.side, OA::Bid);
        assert_eq!(new_order.qty, 3200);
        assert_eq!(new_order.price, 52200);
        assert_eq!(new_order.quote_order_qty, 0);
        assert_eq!(new_order.order_creation_time, 1578930983745201);
        assert_eq!(new_order.last_qty, 0);
        assert_eq!(new_order.mark_price, 0);
        assert_eq!(new_order.cumulative_qty, 0);
        assert_eq!(new_order.cumulative_quote_qty, 0);
        assert_eq!(new_order.commission, 0);
        assert_eq!(new_order.commission_asset, 2);
        assert!(!new_order.is_maker);
        assert!(!new_order.working_indicator);

        let taker_view = &tr.spot[1];
        assert_eq!(taker_view.execution_type, ExecType::Trade);
        assert_eq!(taker_view.order_status, OrderStatus::PartiallyFilled);
        assert_eq!(taker_view.symbol, 3);
        assert_eq!(taker_view.base_scale_k, 1000);
        assert_eq!(taker_view.quote_scale_k, 1000);
        assert_eq!(taker_view.account_id, 29851);
        assert_eq!(taker_view.cl_ord_id, 44188);
        assert_eq!(taker_view.order_id, 123);
        assert_eq!(taker_view.order_type, OrderType::Ioc);
        assert_eq!(taker_view.side, OA::Bid);
        assert_eq!(taker_view.qty, 3200);
        assert_eq!(taker_view.price, 52200);
        assert_eq!(taker_view.quote_order_qty, 0);
        assert_eq!(taker_view.order_creation_time, 1578930983745201);
        assert_eq!(taker_view.trade_id, 789862400);
        assert_eq!(taker_view.last_qty, 8272);
        assert_eq!(taker_view.mark_price, 20100);
        assert_eq!(taker_view.cumulative_qty, 123);
        assert_eq!(taker_view.cumulative_quote_qty, 1000);
        assert_eq!(taker_view.commission, 0);
        assert_eq!(taker_view.commission_asset, 2);
        assert!(!taker_view.is_maker);
        assert!(!taker_view.working_indicator);

        let maker_view = &tr.spot[2];
        assert_eq!(maker_view.execution_type, ExecType::Trade);
        assert_eq!(maker_view.order_status, OrderStatus::Filled);
        assert_eq!(maker_view.symbol, 3);
        assert_eq!(maker_view.base_scale_k, 1000);
        assert_eq!(maker_view.quote_scale_k, 1000);
        assert_eq!(maker_view.account_id, 10332);
        assert_eq!(maker_view.cl_ord_id, 778899);
        assert_eq!(maker_view.order_id, 276810);
        assert_eq!(maker_view.order_type, OrderType::Gtc);
        assert_eq!(maker_view.side, OA::Ask);
        assert_eq!(maker_view.qty, 23);
        assert_eq!(maker_view.price, 12233);
        assert_eq!(maker_view.quote_order_qty, 0);
        assert_eq!(maker_view.order_creation_time, 177777777777);
        assert_eq!(maker_view.trade_id, 789862400);
        assert_eq!(maker_view.last_qty, 8272);
        assert_eq!(maker_view.mark_price, 20100);
        assert_eq!(maker_view.cumulative_qty, 123);
        assert_eq!(maker_view.cumulative_quote_qty, 1000);
        assert_eq!(maker_view.commission, 0);
        assert_eq!(maker_view.commission_asset, 2);
        assert!(maker_view.is_maker);
        assert!(!maker_view.working_indicator);

        assert_eq!(taker_view.trade_id, maker_view.trade_id);

        assert_eq!(fr.fund[0].event_type, FundEventType::Locked);
        assert_eq!(fr.fund[0].balances.locked, 10);
        assert_eq!(fr.fund[0].balances.free, 0);
        assert_eq!(fr.fund[0].balances.currency, 10);
        assert_eq!(fr.fund[0].balances.currency_scale_k, 1000);

        let pos = &fr.fund[1].positions;
        assert_eq!(fr.fund[1].event_type, FundEventType::Unlocked);
        assert_eq!(pos.symbol_id, 1);
        assert_eq!(pos.base_scale_k, 1000);
        assert_eq!(pos.quote_scale_k, 10000);
        assert_eq!(pos.direction, PositionDirection::Long);
        assert_eq!(pos.quantity, 1);
        assert_eq!(pos.open_price_sum, 3);
        assert_eq!(pos.cum_realized, 4);
        assert!(pos.isolated);
        assert_eq!(pos.isolated_wallet, 10);
        assert_eq!(pos.leverage, 9);
        assert_eq!(pos.open_init_margin_sum, 2);
        assert_eq!(pos.mark_price, 14);
        assert_eq!(pos.unrealized_profit, 11);
        assert_eq!(pos.liquidation_price, 12);
        assert_eq!(pos.margin_ratio_scale_k, 13);
    }

    #[test]
    fn should_handle_with_two_trades() {
        let core = spot_core();
        let mut cmd = sample_place_command();

        let second = MatcherTradeEvent {
            event_type: MatcherEventType::Trade,
            active_order_completed: false,
            maker_order_id: 276811,
            matched_order_uid: 10333,
            maker_order_completed: false,
            matched_order_command_type: OrderCommandType::PlaceOrder,
            matched_order_filled: 223,
            matched_order_filled_notional: 1100,
            matched_order_type: OrderType::Gtc,
            matched_order_price: 12233,
            matched_order_size: 13,
            matched_user_cookie: 778999,
            matched_order_timestamp: 177777777778,
            price: 20101,
            size: 8273,
            filled: 124,
            filled_notional: 10000,
            bidder_hold_price: 13233,
            ..Default::default()
        };
        let first = MatcherTradeEvent {
            event_type: MatcherEventType::Trade,
            active_order_completed: false,
            maker_order_id: 276810,
            matched_order_uid: 10332,
            maker_order_completed: true,
            matched_order_command_type: OrderCommandType::PlaceOrder,
            matched_order_filled: 123,
            matched_order_filled_notional: 1000,
            matched_order_type: OrderType::Gtc,
            matched_order_price: 12233,
            matched_order_size: 23,
            matched_user_cookie: 778899,
            matched_order_timestamp: 177777777777,
            price: 20100,
            size: 8272,
            filled: 123,
            filled_notional: 1000,
            bidder_hold_price: 13233,
            next: Some(Box::new(second)),
            ..Default::default()
        };
        cmd.matcher_event = Some(Box::new(first));
        cmd.fund_events = vec![fund_locked_trade(), fund_unlocked_trade()];

        let (tr, fr) = run_proc(&core, &cmd, 12981721239);

        assert_eq!(tr.spot.len(), 5);
        assert_eq!(tr.futures.len(), 0);
        assert_eq!(fr.fund.len(), 2);

        assert_eq!(tr.spot[0].execution_type, ExecType::New);

        let taker_view = &tr.spot[3];
        assert_eq!(taker_view.execution_type, ExecType::Trade);
        assert_eq!(taker_view.order_status, OrderStatus::PartiallyFilled);
        assert_eq!(taker_view.symbol, 3);
        assert_eq!(taker_view.base_scale_k, 1000);
        assert_eq!(taker_view.quote_scale_k, 1000);
        assert_eq!(taker_view.account_id, 29851);
        assert_eq!(taker_view.cl_ord_id, 44188);
        assert_eq!(taker_view.order_id, 123);
        assert_eq!(taker_view.order_type, OrderType::Ioc);
        assert_eq!(taker_view.side, OA::Bid);
        assert_eq!(taker_view.qty, 3200);
        assert_eq!(taker_view.price, 52200);
        assert_eq!(taker_view.quote_order_qty, 0);
        assert_eq!(taker_view.order_creation_time, 1578930983745201);
        assert_eq!(taker_view.trade_id, 53173130196993);
        assert_eq!(taker_view.last_qty, 8273);
        assert_eq!(taker_view.mark_price, 20101);
        assert_eq!(taker_view.cumulative_qty, 124);
        assert_eq!(taker_view.cumulative_quote_qty, 10000);
        assert_eq!(taker_view.commission, 0);
        assert_eq!(taker_view.commission_asset, 2);
        assert!(!taker_view.is_maker);
        assert!(!taker_view.working_indicator);

        let maker_view = &tr.spot[4];
        assert_eq!(maker_view.execution_type, ExecType::Trade);
        assert_eq!(maker_view.order_status, OrderStatus::PartiallyFilled);
        assert_eq!(maker_view.symbol, 3);
        assert_eq!(maker_view.base_scale_k, 1000);
        assert_eq!(maker_view.quote_scale_k, 1000);
        assert_eq!(maker_view.account_id, 10333);
        assert_eq!(maker_view.cl_ord_id, 778999);
        assert_eq!(maker_view.order_id, 276811);
        assert_eq!(maker_view.order_type, OrderType::Gtc);
        assert_eq!(maker_view.side, OA::Ask);
        assert_eq!(maker_view.qty, 13);
        assert_eq!(maker_view.price, 12233);
        assert_eq!(maker_view.quote_order_qty, 0);
        assert_eq!(maker_view.order_creation_time, 177777777778);
        assert_eq!(maker_view.trade_id, 53173130196993);
        assert_eq!(maker_view.last_qty, 8273);
        assert_eq!(maker_view.mark_price, 20101);
        assert_eq!(maker_view.cumulative_qty, 223);
        assert_eq!(maker_view.cumulative_quote_qty, 1100);
        assert_eq!(maker_view.commission, 0);
        assert_eq!(maker_view.commission_asset, 2);
        assert!(maker_view.is_maker);
        assert!(maker_view.working_indicator);

        assert_eq!(taker_view.trade_id, maker_view.trade_id);

        assert_eq!(fr.fund[0].event_type, FundEventType::Locked);
        assert_eq!(fr.fund[1].event_type, FundEventType::Unlocked);
    }

    #[test]
    fn should_handle_with_two_trades_and_reject() {
        let core = spot_core();
        let mut cmd = sample_place_command();

        let reject = MatcherTradeEvent {
            event_type: MatcherEventType::Reject,
            active_order_completed: true,
            size: 8272,
            ..Default::default()
        };
        let second = MatcherTradeEvent {
            event_type: MatcherEventType::Trade,
            active_order_completed: false,
            maker_order_id: 276811,
            matched_order_uid: 10333,
            maker_order_completed: false,
            matched_order_command_type: OrderCommandType::PlaceOrder,
            matched_order_filled: 223,
            matched_order_filled_notional: 1100,
            matched_order_type: OrderType::Gtc,
            matched_order_price: 12233,
            matched_order_size: 13,
            matched_user_cookie: 778999,
            matched_order_timestamp: 177777777778,
            price: 20101,
            size: 8273,
            filled: 124,
            filled_notional: 10000,
            bidder_hold_price: 13233,
            next: Some(Box::new(reject)),
            ..Default::default()
        };
        let first = MatcherTradeEvent {
            event_type: MatcherEventType::Trade,
            active_order_completed: false,
            maker_order_id: 276810,
            matched_order_uid: 10332,
            maker_order_completed: true,
            matched_order_command_type: OrderCommandType::PlaceOrder,
            matched_order_filled: 123,
            matched_order_filled_notional: 1000,
            matched_order_type: OrderType::Gtc,
            matched_order_price: 12233,
            matched_order_size: 23,
            matched_user_cookie: 778899,
            matched_order_timestamp: 177777777777,
            price: 20100,
            size: 8272,
            filled: 123,
            filled_notional: 1000,
            bidder_hold_price: 13233,
            next: Some(Box::new(second)),
            ..Default::default()
        };
        cmd.matcher_event = Some(Box::new(first));

        let (tr, fr) = run_proc(&core, &cmd, 12981721239);

        assert_eq!(tr.spot.len(), 5);
        assert_eq!(tr.futures.len(), 0);
        assert_eq!(fr.fund.len(), 0);

        assert_eq!(tr.spot[0].execution_type, ExecType::New);

        let taker_view = &tr.spot[3];
        assert_eq!(taker_view.execution_type, ExecType::Trade);
        assert_eq!(taker_view.order_id, 123);
        assert_eq!(taker_view.symbol, 3);
        assert_eq!(taker_view.account_id, 29851);

        let maker_view = &tr.spot[4];
        assert_eq!(maker_view.execution_type, ExecType::Trade);
        assert_eq!(maker_view.order_id, 276811);
        assert_eq!(maker_view.symbol, 3);
        assert_eq!(maker_view.account_id, 10333);
    }

    #[test]
    fn should_handle_with_single_reject() {
        let core = spot_core();
        let mut cmd = sample_place_command();
        cmd.matcher_event = Some(Box::new(MatcherTradeEvent {
            event_type: MatcherEventType::Reject,
            active_order_completed: true,
            size: 8272,
            price: 52201,
            ..Default::default()
        }));

        let (tr, fr) = run_proc(&core, &cmd, 192837);

        assert_eq!(tr.spot.len(), 2);
        assert_eq!(tr.futures.len(), 0);
        assert_eq!(fr.fund.len(), 0);

        let new_order = &tr.spot[0];
        assert_eq!(new_order.execution_type, ExecType::New);
        assert_eq!(new_order.order_status, OrderStatus::New);
        assert_eq!(new_order.symbol, 3);
        assert_eq!(new_order.base_scale_k, 1000);
        assert_eq!(new_order.quote_scale_k, 1000);
        assert_eq!(new_order.account_id, 29851);
        assert_eq!(new_order.cl_ord_id, 44188);
        assert_eq!(new_order.order_id, 123);
        assert_eq!(new_order.order_type, OrderType::Ioc);
        assert_eq!(new_order.side, OA::Bid);
        assert_eq!(new_order.qty, 3200);
        assert_eq!(new_order.price, 52200);
        assert_eq!(new_order.quote_order_qty, 0);
        assert_eq!(new_order.order_creation_time, 1578930983745201);
        assert_eq!(new_order.last_qty, 0);
        assert_eq!(new_order.mark_price, 0);
        assert_eq!(new_order.cumulative_qty, 0);
        assert_eq!(new_order.cumulative_quote_qty, 0);
        assert_eq!(new_order.commission, 0);
        assert_eq!(new_order.commission_asset, 2);
        assert!(!new_order.is_maker);
        assert!(!new_order.working_indicator);

        let reject = &tr.spot[1];
        assert_eq!(reject.execution_type, ExecType::Reject);
        assert_eq!(reject.order_status, OrderStatus::Rejected);
        assert_eq!(reject.symbol, 3);
        assert_eq!(reject.base_scale_k, 1000);
        assert_eq!(reject.quote_scale_k, 1000);
        assert_eq!(reject.account_id, 29851);
        assert_eq!(reject.cl_ord_id, 44188);
        assert_eq!(reject.order_id, 123);
        assert_eq!(reject.order_type, OrderType::Ioc);
        assert_eq!(reject.side, OA::Bid);
        assert_eq!(reject.qty, 3200);
        assert_eq!(reject.price, 52200);
        assert_eq!(reject.quote_order_qty, 0);
        assert_eq!(reject.order_creation_time, 1578930983745201);
        assert_eq!(reject.last_qty, 0);
        assert_eq!(reject.mark_price, 0);
        assert_eq!(reject.cumulative_qty, 0);
        assert_eq!(reject.cumulative_quote_qty, 0);
        assert_eq!(reject.commission, 0);
        assert_eq!(reject.commission_asset, 2);
        assert!(!reject.is_maker);
        assert!(!reject.working_indicator);
    }

    #[test]
    fn should_gen_fund_event_when_balance_change() {
        let core = spot_core();
        let mut cmd = OrderCommand {
            command: OrderCommandType::BalanceAdjustment,
            uid: 301,
            symbol: 30,
            order_id: 13143,
            price: 12800,
            timestamp: 1978930983745201,
            result_code: Some(CommandResultCode::Success),
            ..Default::default()
        };
        cmd.fund_events = vec![FundEvent { uid: 301, symbol: 30, order_id: 13143, currency: 20000, ..Default::default() }];

        let (tr, fr) = run_proc(&core, &cmd, 192837);

        assert_eq!(tr.spot.len(), 0);
        assert_eq!(tr.futures.len(), 0);
        assert_eq!(fr.fund.len(), 1);

        let report = &fr.fund[0];
        assert_eq!(report.event_type, FundEventType::Deposit);
        assert_eq!(report.balances.currency, 20000);
    }

    const FUT_SYM: i32 = 4;
    const FUT_TAKER: i64 = 29851;
    const FUT_MAKER: i64 = 10332;

    fn fut_core() -> ExchangeCore {
        let mut core = ExchangeCore::new();
        core.ssp.add_currency(CoreCurrencySpecification { currency: 1, currency_scale_k: 1000, ..Default::default() });
        core.ssp.add_currency(CoreCurrencySpecification { currency: 2, currency_scale_k: 1000, ..Default::default() });
        let spec = CoreSymbolSpecification {
            symbol_id: FUT_SYM,
            symbol_type: SymbolType::FuturesContractPerpetual,
            base_currency: 1,
            quote_currency: 2,
            base_scale_k: 1000,
            quote_scale_k: 1000,
            ..Default::default()
        };
        assert_eq!(core.ssp.add_symbol(spec), CommandResultCode::Success);
        core.ups.add_empty_user_profile(FUT_TAKER);
        core.ups.add_empty_user_profile(FUT_MAKER);
        core.ups.get_mut(FUT_TAKER).unwrap().position_mode = PositionMode::Hedge;
        core.ups.get_mut(FUT_MAKER).unwrap().position_mode = PositionMode::OneWay;
        core
    }

    fn fut_place_command() -> OrderCommand {
        OrderCommand {
            command: OrderCommandType::PlaceOrder,
            order_id: 123,
            symbol: FUT_SYM,
            price: 52200,
            size: 3200,
            reserve_bid_price: 12800,
            action: Some(OA::Bid),
            order_type: Some(OrderType::Ioc),
            uid: FUT_TAKER,
            timestamp: 1578930983745201,
            user_cookie: 44188,
            result_code: Some(CommandResultCode::Success),
            margin_mode: MarginMode::Isolated,
            ..Default::default()
        }
    }

    #[test]
    fn should_handle_futures_single_trade() {
        let core = fut_core();
        let mut cmd = fut_place_command();
        cmd.matcher_event = Some(Box::new(MatcherTradeEvent {
            event_type: MatcherEventType::Trade,
            active_order_completed: false,
            maker_order_id: 276810,
            matched_order_uid: FUT_MAKER,
            maker_order_completed: true,
            matched_order_command_type: OrderCommandType::PlaceOrder,
            matched_order_filled: 123,
            matched_order_filled_notional: 1000,
            matched_order_type: OrderType::Gtc,
            matched_order_price: 12233,
            matched_order_size: 23,
            matched_user_cookie: 778899,
            matched_order_timestamp: 177777777777,
            price: 20100,
            size: 8272,
            filled: 246,
            filled_notional: 2000,
            bidder_hold_price: 13233,
            ..Default::default()
        }));

        let (tr, fr) = run_proc(&core, &cmd, 192837);

        assert_eq!(tr.spot.len(), 0, "futures symbol must not emit spot reports");
        assert_eq!(tr.futures.len(), 3);
        assert_eq!(fr.fund.len(), 0);

        let new_order = &tr.futures[0];
        assert_eq!(new_order.execution_type, ExecType::New);
        assert_eq!(new_order.order_status, OrderStatus::New);
        assert_eq!(new_order.symbol_id, FUT_SYM);
        assert_eq!(new_order.contract_type, SymbolType::FuturesContractPerpetual);
        assert_eq!(new_order.order_qty_scale, 1000);
        assert_eq!(new_order.price_scale, 1000);
        assert_eq!(new_order.user_id, FUT_TAKER);
        assert_eq!(new_order.cl_order_id, 44188);
        assert_eq!(new_order.order_id, 123);
        assert_eq!(new_order.order_type, OrderType::Ioc);
        assert_eq!(new_order.side, OA::Bid);
        assert_eq!(new_order.counterparty_id, -1);
        assert_eq!(new_order.price, 52200);
        assert_eq!(new_order.order_qty, 3200);
        assert_eq!(new_order.create_time, 1578930983745201);
        assert_eq!(new_order.position_side, PositionMode::Hedge, "taker position_side looked up from taker uid");
        assert_eq!(new_order.last_qty, 0);
        assert_eq!(new_order.avg_px, 0);
        assert_eq!(new_order.fee_asset_id, 2);
        assert!(!new_order.is_maker);

        let taker_view = &tr.futures[1];
        assert_eq!(taker_view.execution_type, ExecType::Trade);
        assert_eq!(taker_view.order_status, OrderStatus::PartiallyFilled);
        assert_eq!(taker_view.user_id, FUT_TAKER);
        assert_eq!(taker_view.counterparty_id, FUT_MAKER);
        assert_eq!(taker_view.position_side, PositionMode::Hedge);
        assert_eq!(taker_view.last_qty, 8272);
        assert_eq!(taker_view.last_px, 20100);
        assert_eq!(taker_view.cum_qty, 246);
        assert_eq!(taker_view.cum_quote_qty, 2000);
        assert_eq!(taker_view.avg_px, 2000 / 246, "taker avg_px = filled_notional / filled");
        assert!(!taker_view.is_maker);

        let maker_view = &tr.futures[2];
        assert_eq!(maker_view.execution_type, ExecType::Trade);
        assert_eq!(maker_view.order_status, OrderStatus::Filled);
        assert_eq!(maker_view.user_id, FUT_MAKER);
        assert_eq!(maker_view.counterparty_id, FUT_TAKER, "maker counterparty is the taker command uid");
        assert_eq!(maker_view.order_id, 276810);
        assert_eq!(maker_view.side, OA::Ask, "maker side is opposite of taker");
        assert_eq!(maker_view.position_side, PositionMode::OneWay, "maker position_side looked up independently from maker uid");
        assert_eq!(maker_view.last_qty, 8272);
        assert_eq!(maker_view.last_px, 20100);
        assert_eq!(maker_view.cum_qty, 123);
        assert_eq!(maker_view.cum_quote_qty, 1000);
        assert_eq!(maker_view.avg_px, 1000 / 123, "maker avg_px = matched_order_filled_notional / matched_order_filled");
        assert!(maker_view.is_maker);

        assert_eq!(taker_view.exec_id, maker_view.exec_id, "taker and maker share the same trade exec id");
    }

    #[test]
    fn should_handle_futures_reduce() {
        let core = fut_core();
        let mut cmd = OrderCommand { command: OrderCommandType::ReduceOrder, ..fut_place_command() };
        cmd.matcher_event = Some(Box::new(MatcherTradeEvent {
            event_type: MatcherEventType::Reduce,
            active_order_completed: true,
            filled: 100,
            filled_notional: 10000,
            ..Default::default()
        }));

        let (tr, fr) = run_proc(&core, &cmd, 192837);

        assert_eq!(tr.spot.len(), 0);
        assert_eq!(tr.futures.len(), 1);
        assert_eq!(fr.fund.len(), 0);

        let r = &tr.futures[0];
        assert_eq!(r.execution_type, ExecType::Reduce);
        assert_eq!(r.order_status, OrderStatus::Canceled);
        assert_eq!(r.symbol_id, FUT_SYM);
        assert_eq!(r.contract_type, SymbolType::FuturesContractPerpetual);
        assert_eq!(r.user_id, FUT_TAKER);
        assert_eq!(r.position_side, PositionMode::Hedge);
        assert_eq!(r.order_id, 123);
        assert!(!r.is_maker);
    }

    #[test]
    fn should_handle_futures_single_reject() {
        let core = fut_core();
        let mut cmd = fut_place_command();
        cmd.matcher_event = Some(Box::new(MatcherTradeEvent {
            event_type: MatcherEventType::Reject,
            active_order_completed: true,
            size: 8272,
            price: 52201,
            ..Default::default()
        }));

        let (tr, fr) = run_proc(&core, &cmd, 192837);

        assert_eq!(tr.spot.len(), 0);
        assert_eq!(tr.futures.len(), 2);
        assert_eq!(fr.fund.len(), 0);

        assert_eq!(tr.futures[0].execution_type, ExecType::New);
        let reject = &tr.futures[1];
        assert_eq!(reject.execution_type, ExecType::Reject);
        assert_eq!(reject.order_status, OrderStatus::Rejected);
        assert_eq!(reject.symbol_id, FUT_SYM);
        assert_eq!(reject.contract_type, SymbolType::FuturesContractPerpetual);
        assert_eq!(reject.user_id, FUT_TAKER);
        assert_eq!(reject.position_side, PositionMode::Hedge);
        assert_eq!(reject.order_id, 123);
        assert!(!reject.is_maker);
    }
}
