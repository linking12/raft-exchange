use std::cell::RefCell;
use std::rc::Rc;

use crate::core::processors::symbol_specification_provider::SymbolSpecificationProvider;
use crate::core::processors::user_profile_service::UserProfileService;
#[cfg(test)]
use crate::core::common::last_price_cache_record::LastPriceCacheRecord;
use crate::core::common::cmd::order_command::OrderCommand;
use crate::core::common::margin_mode::MarginMode;
use crate::core::processors::liquidation::command_submitter::{CommandSubmitter, VecCommandSink};
use crate::core::simple_events_processor::{NoopEventsHandler, SimpleEventsProcessor};
use crate::core::trade_events_handler::TradeEventsHandler;
use crate::core::fund_events_handler::FundEventsHandler;
use crate::core::processors::liquidation::scheduler::LiquidationScheduler;
use crate::core::processors::matching_engine_router::MatchingEngineRouter;
use crate::core::processors::risk_engine::RiskEngine;
use crate::core::snapshot::serialization_processor::{
    InMemorySerializationProcessor, SerializationProcessor, SerializedModuleType,
};

pub trait ResultsConsumer {
    fn consume(&mut self, cmd: &OrderCommand, seq: i64, ssp: &SymbolSpecificationProvider, ups: &UserProfileService);
}

impl<C: ResultsConsumer + ?Sized> ResultsConsumer for Rc<RefCell<C>> {
    fn consume(&mut self, cmd: &OrderCommand, seq: i64, ssp: &SymbolSpecificationProvider, ups: &UserProfileService) {
        self.borrow_mut().consume(cmd, seq, ssp, ups);
    }
}

pub struct ExchangeCore {
    pub risk: RiskEngine,
    pub matching: MatchingEngineRouter,
    pub ups: UserProfileService,
    pub ssp: SymbolSpecificationProvider,
    pending_commands: Rc<RefCell<Vec<OrderCommand>>>,
    ser_proc: Box<dyn SerializationProcessor>,
    results_consumer: Box<dyn ResultsConsumer>,
    results_seq: i64,
    liquidation_scheduler: LiquidationScheduler,
}

impl Default for ExchangeCore {
    fn default() -> Self {
        ExchangeCore::new()
    }
}

impl ExchangeCore {
    pub fn new() -> Self {
        let mut core = ExchangeCore {
            risk: RiskEngine::new(),
            matching: MatchingEngineRouter::new(),
            ups: UserProfileService::new(),
            ssp: SymbolSpecificationProvider::new(),
            pending_commands: Rc::new(RefCell::new(Vec::new())),
            ser_proc: Box::new(InMemorySerializationProcessor::new()),
            results_consumer: Box::new(SimpleEventsProcessor::new(NoopEventsHandler, NoopEventsHandler)),
            results_seq: 0,
            liquidation_scheduler: LiquidationScheduler::new(10, 30, 0),
        };

        let submitter = Rc::new(RefCell::new(VecCommandSink(core.pending_commands.clone())));
        core.with_command_submitter(submitter);
        core
    }

    pub fn with_serialization_processor(&mut self, ser_proc: Box<dyn SerializationProcessor>) {
        self.ser_proc = ser_proc;
    }

    pub fn with_command_submitter(&mut self, submitter: Rc<RefCell<dyn CommandSubmitter>>) {
        self.liquidation_scheduler.set_command_submitter(submitter.clone());
        self.risk.liquidation_engine.set_command_submitter(submitter);
    }

    pub fn with_events_handlers<T, F>(&mut self, trade: T, fund: F)
    where
        T: TradeEventsHandler + 'static,
        F: FundEventsHandler + 'static,
    {
        self.results_consumer = Box::new(SimpleEventsProcessor::new(trade, fund));
    }

    pub fn with_results_consumer(&mut self, consumer: Box<dyn ResultsConsumer>) {
        self.results_consumer = consumer;
    }

    pub fn process_command(&mut self, cmd: &mut OrderCommand) {
        log::trace!(
            "process_command enter: cmd={:?} uid={} symbol={} order_id={}",
            cmd.command, cmd.uid, cmd.symbol, cmd.order_id
        );

        if cmd.command == crate::core::common::cmd::order_command_type::OrderCommandType::Reset {
            self.reset();
            cmd.result_code = Some(crate::core::common::cmd::command_result_code::CommandResultCode::Success);
            log::debug!("process_command: RESET cleared all engine business state");
            return;
        }

        self.apply_one(cmd);

        log::trace!(
            "process_command: R1->ME->R2 done cmd={:?} result={:?}",
            cmd.command, cmd.result_code
        );

        self.drive_pending();
    }

    fn drive_pending(&mut self) {
        loop {
            let batch: Vec<OrderCommand> = std::mem::take(&mut *self.pending_commands.borrow_mut());
            if batch.is_empty() {
                break;
            }
            for mut c in batch {
                self.apply_one(&mut c);
            }
        }
    }

    fn apply_one(&mut self, cmd: &mut OrderCommand) {
        self.risk.pre_process_command(cmd, &mut self.ups, &self.ssp);
        self.matching.process_order(cmd);
        self.risk.handler_risk_release(cmd, &mut self.ups, &self.ssp);
        let seq = self.results_seq;
        self.results_seq += 1;
        self.results_consumer.consume(cmd, seq, &self.ssp, &self.ups);
    }

    pub fn start_liquidation_scheduler(&mut self) {
        self.liquidation_scheduler.is_running = true;
    }

    pub fn stop_liquidation_scheduler(&mut self) {
        self.liquidation_scheduler.is_running = false;
    }

    pub fn tick_liquidation_scheduler(&mut self, now: i64) {
        self.liquidation_scheduler.run_one_iteration(now);
        self.drive_pending();
    }

    fn reset(&mut self) {
        self.risk.reset();
        self.ups.users.clear();
        self.ssp.symbols.clear();
        self.ssp.currencies.clear();
        self.ssp.rebuild_spot_pair_index();
        self.matching.reset();
    }

    pub fn persist(&mut self, snapshot_id: i64, instance_id: i32) -> bool {
        use crate::core::snapshot::marshalling::ChronicleMarshallable;

        let re = crate::core::processors::risk_engine::write_risk_engine_payload(self);
        let mut w = crate::core::snapshot::chronicle_writer::ChronicleWriter::new();
        self.matching.chronicle_write(&mut w);
        let me = w.into_bytes();
        let ok_re = self.ser_proc.store_data(snapshot_id, 0, 0, SerializedModuleType::RiskEngine, instance_id, &re);
        let ok_me =
            self.ser_proc.store_data(snapshot_id, 0, 0, SerializedModuleType::MatchingEngineRouter, instance_id, &me);
        ok_re && ok_me
    }

    pub fn recover(&mut self, snapshot_id: i64, instance_id: i32) {
        use crate::core::snapshot::chronicle_reader::ChronicleReader;
        use crate::core::snapshot::marshalling::ChronicleMarshallable;
        let re = self
            .ser_proc
            .load_data(snapshot_id, SerializedModuleType::RiskEngine, instance_id)
            .expect("RE snapshot module not found");
        crate::core::processors::risk_engine::read_risk_engine_payload(&re, self).expect("RE payload parse failed");
        let me = self
            .ser_proc
            .load_data(snapshot_id, SerializedModuleType::MatchingEngineRouter, instance_id)
            .expect("ME snapshot module not found");
        self.matching =
            MatchingEngineRouter::chronicle_read(&mut ChronicleReader::new(&me)).expect("ME payload parse failed");
        self.restore_non_replicated_state();
    }

    fn restore_non_replicated_state(&mut self) {
        self.ssp.rebuild_spot_pair_index();
        for up in self.ups.users.values_mut() {
            for pos in up.positions.values_mut() {
                pos.adl_eligibility = if pos.margin_mode == MarginMode::Isolated { 100 } else { 0 };
                pos.pending_adl_size = 0;
                pos.liquidation_flow = None;
            }
        }
        let le = &mut self.risk.liquidation_engine;
        for up in self.ups.users.values() {
            for pos in up.positions.values() {
                if pos.open_volume == 0 {
                    continue;
                }
                if let Some(spec) = self.ssp.get_symbol(pos.symbol) {
                    if spec.symbol_type.is_futures_contract() {
                        le.on_position_opened(up.uid, pos.symbol);
                    }
                }
            }
        }
        le.loan_liquidation_engine.rebuild_indices(&self.ups);
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::core::common::cmd::order_command::OrderCommand;
    use crate::core::common::cmd::command_result_code::CommandResultCode;
    use crate::core::common::order_action::OrderAction;
    use crate::core::common::cmd::order_command_type::OrderCommandType;
    use crate::core::common::order_type::OrderType;
    use crate::core::common::symbol_type::SymbolType;
    use crate::core::common::core_currency_specification::CoreCurrencySpecification;
    use crate::core::common::core_symbol_specification::CoreSymbolSpecification;

    const BASE: i32 = 1;
    const QUOTE: i32 = 2;
    const SYMBOL: i32 = 100;

    fn spot_spec() -> CoreSymbolSpecification {
        CoreSymbolSpecification {
            symbol_id: SYMBOL,
            symbol_type: SymbolType::CurrencyExchangePair,
            base_currency: BASE,
            quote_currency: QUOTE,
            base_scale_k: 1,
            quote_scale_k: 1,
            taker_fee: 0,
            maker_fee: 0,
            fee_scale_k: 0,
            ..Default::default()
        }
    }

    fn seeded_core() -> ExchangeCore {
        let mut core = ExchangeCore::new();
        core.ssp.add_currency(CoreCurrencySpecification { currency: BASE, currency_scale_k: 1, ..Default::default() });
        core.ssp.add_currency(CoreCurrencySpecification { currency: QUOTE, currency_scale_k: 1, ..Default::default() });
        assert_eq!(core.ssp.add_symbol(spot_spec()), CommandResultCode::Success);
        core.matching.add_symbol(&spot_spec());
        core
    }

    #[test]
    fn non_trading_add_user_does_not_touch_matching_router() {
        let mut core = seeded_core();
        let mut cmd =
            OrderCommand { command: OrderCommandType::AddUser, uid: 1, ..Default::default() };

        core.process_command(&mut cmd);

        assert_eq!(cmd.result_code, Some(CommandResultCode::Success));
        assert!(core.ups.get(1).is_some());
        assert!(cmd.matcher_event.is_none());
        assert!(cmd.market_data.is_none());
    }

    #[test]
    fn reset_wipes_all_engine_state() {
        let mut core = seeded_core();
        core.ups.add_empty_user_profile(1);
        core.risk.set_mark_price(spot_spec().symbol_id, 100);
        core.ups.get_mut(1).unwrap().add_to_account(QUOTE, 5_000);
        *core.risk.fees.entry(QUOTE).or_insert(0) += 7;
        let mut place = OrderCommand {
            command: OrderCommandType::PlaceOrder,
            order_id: 10,
            uid: 1,
            symbol: spot_spec().symbol_id,
            price: 100,
            size: 5,
            action: Some(crate::core::common::order_action::OrderAction::Bid),
            order_type: Some(crate::core::common::order_type::OrderType::Gtc),
            reserve_bid_price: 100,
            ..Default::default()
        };
        core.process_command(&mut place);
        assert!(!core.ups.users.is_empty() && !core.ssp.symbols.is_empty());

        let mut reset = OrderCommand { command: OrderCommandType::Reset, ..Default::default() };
        core.process_command(&mut reset);

        assert_eq!(reset.result_code, Some(CommandResultCode::Success));
        assert!(core.ups.users.is_empty(), "users cleared");
        assert!(core.ssp.symbols.is_empty() && core.ssp.currencies.is_empty(), "specs cleared");
        assert!(core.risk.fees.is_empty() && core.risk.adjustments.is_empty() && core.risk.suspends.is_empty(), "fee/adjustment/suspend buckets cleared");
        assert!(core.risk.last_price_cache.is_empty(), "price cache cleared");
        assert_eq!(core.matching.order_books_state_hash(), 17, "order books cleared (empty hash seed 17)");
    }

    #[test]
    fn non_trading_balance_adjustment_credits_account_and_hedges_adjustments() {
        let mut core = seeded_core();
        core.ups.add_empty_user_profile(1);
        let mut cmd = OrderCommand {
            command: OrderCommandType::BalanceAdjustment,
            uid: 1,
            symbol: QUOTE,
            price: 500,
            order_id: 42,
            ..Default::default()
        };

        core.process_command(&mut cmd);

        assert_eq!(cmd.result_code, Some(CommandResultCode::Success));
        assert_eq!(core.ups.get(1).unwrap().account(QUOTE), 500);
        assert_eq!(*core.risk.adjustments.get(&QUOTE).unwrap(), -500);
    }

    #[test]
    fn trading_place_order_risk_rejected_never_reaches_book() {
        let mut core = seeded_core();
        core.ups.add_empty_user_profile(1);
        let mut cmd = OrderCommand {
            command: OrderCommandType::PlaceOrder,
            order_id: 1,
            symbol: SYMBOL,
            price: 50,
            size: 1000,
            reserve_bid_price: 50,
            action: Some(OrderAction::Bid),
            order_type: Some(OrderType::Gtc),
            uid: 1,
            ..Default::default()
        };

        core.process_command(&mut cmd);

        assert_eq!(cmd.result_code, Some(CommandResultCode::RiskNsf));
        assert_eq!(core.ups.get(1).unwrap().locked(QUOTE), 0);

        let mut req = OrderCommand {
            command: OrderCommandType::OrderBookRequest,
            symbol: SYMBOL,
            size: 10,
            ..Default::default()
        };
        core.process_command(&mut req);
        let md = req.market_data.unwrap();
        assert!(md.bid_prices.is_empty());
    }

    #[test]
    fn trading_place_order_valid_reaches_book_and_locks_funds() {
        let mut core = seeded_core();
        core.ups.add_empty_user_profile(1);
        core.ups.get_mut(1).unwrap().add_to_account(QUOTE, 1_000_000);
        let mut cmd = OrderCommand {
            command: OrderCommandType::PlaceOrder,
            order_id: 1,
            symbol: SYMBOL,
            price: 50,
            size: 1000,
            reserve_bid_price: 50,
            action: Some(OrderAction::Bid),
            order_type: Some(OrderType::Gtc),
            uid: 1,
            ..Default::default()
        };

        core.process_command(&mut cmd);

        assert_eq!(cmd.result_code, Some(CommandResultCode::Success));
        assert_eq!(core.ups.get(1).unwrap().locked(QUOTE), 50_000);

        let mut req = OrderCommand {
            command: OrderCommandType::OrderBookRequest,
            symbol: SYMBOL,
            size: 10,
            ..Default::default()
        };
        core.process_command(&mut req);
        let md = req.market_data.unwrap();
        assert_eq!(md.bid_prices, vec![50]);
        assert_eq!(md.bid_volumes, vec![1000]);
    }

    #[test]
    fn cancel_order_is_r1_no_op_and_releases_lock_via_r2() {
        let mut core = seeded_core();
        core.ups.add_empty_user_profile(1);
        core.ups.get_mut(1).unwrap().add_to_account(QUOTE, 1_000_000);
        let mut place = OrderCommand {
            command: OrderCommandType::PlaceOrder,
            order_id: 1,
            symbol: SYMBOL,
            price: 50,
            size: 1000,
            reserve_bid_price: 50,
            action: Some(OrderAction::Bid),
            order_type: Some(OrderType::Gtc),
            uid: 1,
            ..Default::default()
        };
        core.process_command(&mut place);
        assert_eq!(core.ups.get(1).unwrap().locked(QUOTE), 50_000);

        let mut cancel = OrderCommand {
            command: OrderCommandType::CancelOrder,
            order_id: 1,
            symbol: SYMBOL,
            uid: 1,
            ..Default::default()
        };
        core.process_command(&mut cancel);

        assert_eq!(cancel.result_code, Some(CommandResultCode::Success));
        assert_eq!(core.ups.get(1).unwrap().locked(QUOTE), 0, "R2 must release all locked funds");
    }
}

#[cfg(test)]
mod loan_force_liquidate_tests {
    use super::*;
    use crate::core::common::cmd::command_result_code::CommandResultCode;
    use crate::core::common::cmd::order_command_type::OrderCommandType;
    use crate::core::common::core_currency_specification::CoreCurrencySpecification;
    use crate::core::common::core_symbol_specification::CoreSymbolSpecification;
    use crate::core::common::cross_loan_record::CrossLoanRecord;
    use crate::core::common::isolated_loan_record::IsolatedLoanRecord;
    use crate::core::common::order_action::OrderAction;
    use crate::core::common::order_type::OrderType;
    use crate::core::common::symbol_type::SymbolType;

    const BASE: i32 = 1;
    const QUOTE: i32 = 2;
    const SYMBOL: i32 = 100;
    const BORROWER: i64 = 10;
    const MAKER: i64 = 20;
    const LOAN_ID: i64 = 42;

    fn loan_spot_spec() -> CoreSymbolSpecification {
        CoreSymbolSpecification {
            symbol_id: SYMBOL,
            symbol_type: SymbolType::CurrencyExchangePair,
            base_currency: BASE,
            quote_currency: QUOTE,
            base_scale_k: 1,
            quote_scale_k: 1,
            taker_fee: 0,
            maker_fee: 0,
            fee_scale_k: 0,
            ..Default::default()
        }
    }

    fn seeded_loan_core() -> ExchangeCore {
        let mut core = ExchangeCore::new();
        core.ssp.add_currency(CoreCurrencySpecification { currency: BASE, currency_scale_k: 1, ..Default::default() });
        core.ssp.add_currency(CoreCurrencySpecification { currency: QUOTE, currency_scale_k: 1, ..Default::default() });
        assert_eq!(core.ssp.add_symbol(loan_spot_spec()), CommandResultCode::Success);
        core.matching.add_symbol(&loan_spot_spec());
        core.ups.add_empty_user_profile(BORROWER);
        core.ups.add_empty_user_profile(MAKER);
        core
    }

    fn open_isolated_loan(core: &mut ExchangeCore, loan_id: i64, collateral: i64, principal: i64, rate_bps: i32, opened_at_ts: i64) {
        core.risk.loan_service.add_to_loan_pool_available(QUOTE, 1_000_000);
        {
            let borrower = core.ups.get_mut(BORROWER).unwrap();
            borrower.add_to_account(BASE, collateral);
            let mut loan = IsolatedLoanRecord::new(BORROWER, loan_id, SYMBOL, BASE, QUOTE, rate_bps, opened_at_ts);
            loan.outstanding_principal = principal;
            loan.collateral_amount = collateral;
            borrower.isolated_loans.insert(loan_id, loan);
        }
        let borrower = core.ups.get_mut(BORROWER).unwrap();
        core.risk.loan_service.disburse_loan(borrower, QUOTE, principal);
    }

    fn fund_maker_and_rest_bid(core: &mut ExchangeCore, order_id: i64, price: i64, size: i64) {
        core.ups.get_mut(MAKER).unwrap().add_to_account(QUOTE, 1_000_000_000);
        let mut maker_cmd = OrderCommand {
            command: OrderCommandType::PlaceOrder,
            order_id,
            symbol: SYMBOL,
            price,
            size,
            reserve_bid_price: price,
            action: Some(OrderAction::Bid),
            order_type: Some(OrderType::Gtc),
            uid: MAKER,
            timestamp: 1_000,
            ..Default::default()
        };
        core.process_command(&mut maker_cmd);
        assert_eq!(maker_cmd.result_code, Some(CommandResultCode::Success));
    }

    fn force_liquidate_cmd(order_id: i64, loan_id: i64, price: i64, lots: i64, ts: i64) -> OrderCommand {
        OrderCommand {
            command: OrderCommandType::LoanForceLiquidate,
            order_id,
            symbol: SYMBOL,
            price,
            size: lots,
            reserve_bid_price: loan_id,
            uid: BORROWER,
            timestamp: ts,
            ..Default::default()
        }
    }

    fn conserved_total(core: &ExchangeCore, currency: i32) -> i64 {
        let accounts_sum: i64 = core.ups.users.values().map(|u| u.account(currency)).sum();
        accounts_sum
            + core.risk.loan_service.get_loan_pool_available(currency)
            + core.risk.loan_service.get_interest_revenue(currency)
            + core.risk.loan_service.get_loan_insurance_fund(currency)
            + *core.risk.fees.get(&currency).unwrap_or(&0)
            + *core.risk.adjustments.get(&currency).unwrap_or(&0)
    }

    #[test]
    fn isolated_force_liquidate_full_fill_removes_loan_and_conserves() {
        let mut core = seeded_loan_core();
        open_isolated_loan(&mut core, LOAN_ID, 1_000, 500, 0, 1_000);
        fund_maker_and_rest_bid(&mut core, 1, 1, 2_000);

        let before_base = conserved_total(&core, BASE);
        let before_quote = conserved_total(&core, QUOTE);

        let mut cmd = force_liquidate_cmd(2, LOAN_ID, 1, 1_000, 2_000);
        core.process_command(&mut cmd);

        assert_eq!(cmd.result_code, Some(CommandResultCode::Success));
        let borrower = core.ups.get(BORROWER).unwrap();
        assert!(!borrower.isolated_loans.contains_key(&LOAN_ID), "fully repaid loan removed");
        assert_eq!(borrower.account(BASE), 0);
        assert_eq!(borrower.locked(BASE), 0);
        assert_eq!(borrower.account(QUOTE), 500 + 480);
        assert_eq!(core.risk.loan_service.get_loan_insurance_fund(QUOTE), 20);
        assert_eq!(core.risk.loan_service.get_loan_pool_available(QUOTE), 1_000_000);
        assert_eq!(core.risk.loan_service.get_loan_pool_borrowed(QUOTE), 0);
        assert_eq!(core.risk.loan_service.get_interest_revenue(QUOTE), 0);

        assert_eq!(conserved_total(&core, BASE), before_base);
        assert_eq!(conserved_total(&core, QUOTE), before_quote);
    }

    #[test]
    fn isolated_force_liquidate_partial_fill_keeps_loan_with_updated_snapshot() {
        let mut core = seeded_loan_core();
        open_isolated_loan(&mut core, LOAN_ID, 1_000, 500, 0, 1_000);
        fund_maker_and_rest_bid(&mut core, 1, 1, 400);

        let before_base = conserved_total(&core, BASE);
        let before_quote = conserved_total(&core, QUOTE);

        let mut cmd = force_liquidate_cmd(2, LOAN_ID, 1, 1_000, 2_000);
        core.process_command(&mut cmd);

        assert_eq!(cmd.result_code, Some(CommandResultCode::Success));
        let borrower = core.ups.get(BORROWER).unwrap();
        let loan = borrower.isolated_loans.get(&LOAN_ID).expect("partial fill keeps the loan open");
        assert_eq!(loan.outstanding_principal, 500 - 392);
        assert_eq!(loan.accumulated_interest, 0);
        assert_eq!(loan.collateral_amount, 600);
        assert_eq!(borrower.account(BASE), 600);
        assert_eq!(borrower.locked(BASE), 0);
        assert_eq!(core.risk.loan_service.get_loan_insurance_fund(QUOTE), 8);
        assert_eq!(core.risk.loan_service.get_loan_pool_available(QUOTE), 999_500 + 392);

        assert_eq!(conserved_total(&core, BASE), before_base);
        assert_eq!(conserved_total(&core, QUOTE), before_quote);
    }

    #[test]
    fn isolated_force_liquidate_all_reject_refunds_collateral_accrues_interest_then_takes_over() {
        let mut core = seeded_loan_core();
        const YEAR_MS: i64 = 365 * 24 * 3600 * 1_000;
        open_isolated_loan(&mut core, LOAN_ID, 1_000, 500, 1_000, 1_000);

        let before_base = conserved_total(&core, BASE);
        let before_quote = conserved_total(&core, QUOTE);

        let mut cmd = force_liquidate_cmd(1, LOAN_ID, 1, 1_000, 1_000 + YEAR_MS);
        core.process_command(&mut cmd);

        assert_eq!(cmd.result_code, Some(CommandResultCode::Success));
        let borrower = core.ups.get(BORROWER).unwrap();
        assert!(!borrower.isolated_loans.contains_key(&LOAN_ID), "taken over -> removed");
        assert_eq!(borrower.locked(BASE), 0);
        assert_eq!(borrower.account(BASE), 0);
        assert_eq!(borrower.account(QUOTE), 500);

        assert_eq!(core.risk.loan_service.get_loan_insurance_fund(QUOTE), -550);
        assert_eq!(core.risk.loan_service.get_loan_insurance_fund(BASE), 1_000);
        assert_eq!(core.risk.loan_service.get_interest_revenue(QUOTE), 50);
        assert_eq!(core.risk.loan_service.get_loan_pool_available(QUOTE), 999_500 + 500);
        assert_eq!(core.risk.loan_service.get_loan_pool_borrowed(QUOTE), 0);

        assert_eq!(conserved_total(&core, BASE), before_base);
        assert_eq!(conserved_total(&core, QUOTE), before_quote);
    }

    #[test]
    fn isolated_force_liquidate_dust_after_partial_debt_coverage_triggers_takeover_via_sellable_lots_zero() {
        let mut core = ExchangeCore::new();
        core.ssp.add_currency(CoreCurrencySpecification { currency: BASE, currency_scale_k: 100, ..Default::default() });
        core.ssp.add_currency(CoreCurrencySpecification { currency: QUOTE, currency_scale_k: 1, ..Default::default() });
        let spec = CoreSymbolSpecification {
            symbol_id: SYMBOL,
            symbol_type: SymbolType::CurrencyExchangePair,
            base_currency: BASE,
            quote_currency: QUOTE,
            base_scale_k: 1,
            quote_scale_k: 1,
            taker_fee: 0,
            maker_fee: 0,
            fee_scale_k: 0,
            ..Default::default()
        };
        assert_eq!(core.ssp.add_symbol(spec.clone()), CommandResultCode::Success);
        core.matching.add_symbol(&spec);
        core.ups.add_empty_user_profile(BORROWER);
        core.ups.add_empty_user_profile(MAKER);

        open_isolated_loan(&mut core, LOAN_ID, 1_050, 2_000, 0, 1_000);
        fund_maker_and_rest_bid(&mut core, 1, 100, 20);

        let before_base = conserved_total(&core, BASE);
        let before_quote = conserved_total(&core, QUOTE);

        let mut cmd = force_liquidate_cmd(2, LOAN_ID, 100, 10, 2_000);
        core.process_command(&mut cmd);

        assert_eq!(cmd.result_code, Some(CommandResultCode::Success));
        let borrower = core.ups.get(BORROWER).unwrap();
        assert!(!borrower.isolated_loans.contains_key(&LOAN_ID), "taken over -> removed");
        assert_eq!(core.risk.loan_service.get_loan_insurance_fund(QUOTE), 20 - 1_020);
        assert_eq!(core.risk.loan_service.get_loan_insurance_fund(BASE), 50);
        assert_eq!(core.risk.loan_service.get_loan_pool_available(QUOTE), 1_000_000 - 2_000 + 980 + 1_020);
        assert_eq!(core.risk.loan_service.get_loan_pool_borrowed(QUOTE), 0);
        assert_eq!(borrower.account(BASE), 0);
        assert_eq!(borrower.locked(BASE), 0);

        assert_eq!(conserved_total(&core, BASE), before_base);
        assert_eq!(conserved_total(&core, QUOTE), before_quote);
    }

    const SELL_CUR: i32 = 3;

    fn cross_seeded_core() -> ExchangeCore {
        let mut core = ExchangeCore::new();
        core.ssp.add_currency(CoreCurrencySpecification { currency: SELL_CUR, currency_scale_k: 1, ..Default::default() });
        core.ssp.add_currency(CoreCurrencySpecification { currency: QUOTE, currency_scale_k: 1, ..Default::default() });
        let spec = CoreSymbolSpecification {
            symbol_id: SYMBOL,
            symbol_type: SymbolType::CurrencyExchangePair,
            base_currency: SELL_CUR,
            quote_currency: QUOTE,
            base_scale_k: 1,
            quote_scale_k: 1,
            taker_fee: 0,
            maker_fee: 0,
            fee_scale_k: 0,
            ..Default::default()
        };
        assert_eq!(core.ssp.add_symbol(spec.clone()), CommandResultCode::Success);
        core.matching.add_symbol(&spec);
        core.ups.add_empty_user_profile(BORROWER);
        core.ups.add_empty_user_profile(MAKER);
        core.risk.loan_service.global_config.numeraire_currency = QUOTE;
        core.risk.last_price_cache.insert(SYMBOL, LastPriceCacheRecord::with_mark(1));
        core
    }

    fn open_cross_loan(core: &mut ExchangeCore, loan_id: i64, collateral: i64, principal: i64) {
        core.risk.loan_service.add_to_loan_pool_available(QUOTE, 1_000_000);
        {
            let borrower = core.ups.get_mut(BORROWER).unwrap();
            borrower.add_to_account(SELL_CUR, collateral);
            borrower.add_to_cross_loan_collateral(SELL_CUR, collateral);
            let mut loan = CrossLoanRecord::new(BORROWER, loan_id, SYMBOL, QUOTE, 0, 1_000);
            loan.outstanding_principal = principal;
            borrower.cross_loans.insert(loan_id, loan);
        }
        let borrower = core.ups.get_mut(BORROWER).unwrap();
        core.risk.loan_service.disburse_loan(borrower, QUOTE, principal);
    }

    fn cross_force_liquidate_cmd(order_id: i64, target_loan_id: i64, price: i64, lots: i64, ts: i64) -> OrderCommand {
        OrderCommand {
            command: OrderCommandType::LoanCrossForceLiquidate,
            order_id,
            symbol: SYMBOL,
            price,
            size: lots,
            reserve_bid_price: target_loan_id,
            uid: BORROWER,
            timestamp: ts,
            ..Default::default()
        }
    }

    #[test]
    fn cross_force_liquidate_structurally_unsellable_triggers_target_takeover() {
        let mut core = cross_seeded_core();
        core.ssp.currencies.get_mut(&SELL_CUR).unwrap().collateral_weight_bps = 0;
        open_cross_loan(&mut core, LOAN_ID, 2_000, 2_000);
        fund_maker_and_rest_bid(&mut core, 1, 1, 2_000);

        let before_quote = conserved_total(&core, QUOTE);
        let before_sell = conserved_total(&core, SELL_CUR);

        let mut cmd = cross_force_liquidate_cmd(2, LOAN_ID, 1, 1_000, 2_000);
        core.process_command(&mut cmd);

        assert_eq!(cmd.result_code, Some(CommandResultCode::Success));
        let borrower = core.ups.get(BORROWER).unwrap();
        assert!(!borrower.cross_loans.contains_key(&LOAN_ID), "taken over -> removed");
        assert_eq!(borrower.cross_loan_collateral(SELL_CUR), 1_000);
        assert_eq!(core.risk.loan_service.get_loan_insurance_fund(QUOTE), 20 - 1_020);
        assert_eq!(core.risk.loan_service.get_loan_insurance_fund(SELL_CUR), 0);
        assert_eq!(core.risk.loan_service.get_loan_pool_borrowed(QUOTE), 0);

        assert_eq!(conserved_total(&core, QUOTE), before_quote);
        assert_eq!(conserved_total(&core, SELL_CUR), before_sell);
    }

    #[test]
    fn cross_force_liquidate_all_exhausted_sweeps_remaining_loans_in_ascending_order() {
        let mut core = cross_seeded_core();
        core.ssp.currencies.get_mut(&SELL_CUR).unwrap().collateral_weight_bps = 0;
        open_cross_loan(&mut core, LOAN_ID, 2_000, 2_000);
        {
            let borrower = core.ups.get_mut(BORROWER).unwrap();
            let mut loan90 = CrossLoanRecord::new(BORROWER, 90, SYMBOL, QUOTE, 0, 1_000);
            loan90.outstanding_principal = 700;
            borrower.cross_loans.insert(90, loan90);
            let mut loan50 = CrossLoanRecord::new(BORROWER, 50, SYMBOL, QUOTE, 0, 1_000);
            loan50.outstanding_principal = 300;
            borrower.cross_loans.insert(50, loan50);
        }
        core.risk.loan_service.add_to_loan_pool_borrowed(QUOTE, 700 + 300);
        fund_maker_and_rest_bid(&mut core, 1, 1, 2_000);

        let before_quote = conserved_total(&core, QUOTE);

        let mut cmd = cross_force_liquidate_cmd(2, LOAN_ID, 1, 1_000, 2_000);
        core.process_command(&mut cmd);

        assert_eq!(cmd.result_code, Some(CommandResultCode::Success));
        let borrower = core.ups.get(BORROWER).unwrap();
        assert!(borrower.cross_loans.is_empty(), "target + both remaining loans all swept");

        assert_eq!(core.risk.loan_service.get_loan_insurance_fund(QUOTE), 20 - 1_020 - 300 - 700);
        assert_eq!(core.risk.loan_service.get_loan_pool_borrowed(QUOTE), 0);
        assert_eq!(core.risk.loan_service.get_interest_revenue(QUOTE), 0);

        assert_eq!(conserved_total(&core, QUOTE), before_quote);
    }
}

#[cfg(test)]
mod liquidation_engine_e2e_tests {
    use super::*;
    use crate::core::common::cmd::command_result_code::CommandResultCode;
    use crate::core::common::cmd::order_command_type::OrderCommandType;
    use crate::core::common::core_currency_specification::CoreCurrencySpecification;
    use crate::core::common::core_symbol_specification::CoreSymbolSpecification;
    use crate::core::common::margin_mode::MarginMode;
    use crate::core::common::order_action::OrderAction;
    use crate::core::common::order_type::OrderType;
    use crate::core::common::position_direction::PositionDirection;
    use crate::core::common::symbol_type::SymbolType;
    use std::collections::BTreeMap;

    const BASE: i32 = 1;
    const QUOTE: i32 = 2;
    const FUT: i32 = 400;
    const BORROWER: i64 = 10;
    const M1: i64 = 20;
    const M2: i64 = 30;

    fn fut_spec() -> CoreSymbolSpecification {
        let mut mm = BTreeMap::new();
        mm.insert(i64::MAX, 500);
        CoreSymbolSpecification {
            symbol_id: FUT,
            symbol_type: SymbolType::FuturesContractPerpetual,
            base_currency: BASE,
            quote_currency: QUOTE,
            base_scale_k: 1,
            quote_scale_k: 1,
            taker_fee: 0,
            maker_fee: 0,
            fee_scale_k: 0,
            maintenance_margin: mm,
            maintenance_margin_scale_k: 10_000,
            liquidation_fee: 200,
            ..Default::default()
        }
    }

    fn seeded() -> ExchangeCore {
        let mut core = ExchangeCore::new();
        core.ssp.add_currency(CoreCurrencySpecification { currency: BASE, currency_scale_k: 1, ..Default::default() });
        core.ssp.add_currency(CoreCurrencySpecification { currency: QUOTE, currency_scale_k: 1, ..Default::default() });
        let spec = CoreSymbolSpecification { fee_scale_k: 10_000, ..fut_spec() };
        assert_eq!(core.ssp.add_symbol(spec.clone()), CommandResultCode::Success);
        core.matching.add_symbol(&spec);
        for uid in [BORROWER, M1, M2] {
            core.ups.add_empty_user_profile(uid);
            core.ups.get_mut(uid).unwrap().add_to_account(QUOTE, 10_000_000);
        }
        core.risk.liquidation_engine.is_running = true;
        core
    }

    fn fut_order(order_id: i64, uid: i64, price: i64, size: i64, action: OrderAction, order_type: OrderType, leverage: i32) -> OrderCommand {
        OrderCommand {
            command: OrderCommandType::PlaceOrder,
            order_id,
            uid,
            symbol: FUT,
            price,
            size,
            reserve_bid_price: price,
            action: Some(action),
            order_type: Some(order_type),
            leverage,
            margin_mode: MarginMode::Isolated,
            timestamp: 1_000,
            ..Default::default()
        }
    }

    fn markprice(price: i64, ts: i64) -> OrderCommand {
        OrderCommand { command: OrderCommandType::MarkpriceAdjustment, symbol: FUT, price, timestamp: ts, ..Default::default() }
    }

    fn conserved(core: &ExchangeCore) -> i64 {
        let cur = QUOTE;
        let mark = core.risk.last_price_cache.get(&FUT).map(|r| r.mark_price).unwrap_or(0);
        let mut total: i64 = core.ups.users.values().map(|u| u.account(cur)).sum();
        total += *core.risk.fees.get(&cur).unwrap_or(&0);
        total += *core.risk.adjustments.get(&cur).unwrap_or(&0);
        for u in core.ups.users.values() {
            for p in u.positions.values() {
                if p.currency == cur {
                    total += p.estimate_pnl(mark) + p.extra_margin;
                }
            }
        }
        for n in core.risk.liquidation_service.notionals.values() {
            total += n.available;
        }
        for ifp in core.risk.liquidation_service.positions.values() {
            let sign = ifp.direction.multiplier() as i64;
            total += sign * (mark * ifp.open_volume - ifp.open_price_sum);
        }
        total
    }

    fn open_borrower_long(core: &mut ExchangeCore) {
        let mut m1 = fut_order(1, M1, 100, 10, OrderAction::Ask, OrderType::Gtc, 10);
        core.process_command(&mut m1);
        assert_eq!(m1.result_code, Some(CommandResultCode::Success));
        let mut b = fut_order(2, BORROWER, 100, 10, OrderAction::Bid, OrderType::Gtc, 10);
        core.process_command(&mut b);
        assert_eq!(b.result_code, Some(CommandResultCode::Success));
        assert_eq!(core.ups.get(BORROWER).unwrap().positions[&FUT].direction, PositionDirection::Long);
        assert_eq!(core.ups.get(BORROWER).unwrap().positions[&FUT].open_volume, 10);
    }

    #[test]
    fn on_position_opened_indexes_borrower_and_makers() {
        let mut core = seeded();
        core.process_command(&mut markprice(100, 1_000));
        open_borrower_long(&mut core);
        let holders = core.risk.liquidation_engine.symbol_to_users.get(&FUT).expect("index should have this symbol");
        assert!(holders.contains(&BORROWER));
        assert!(holders.contains(&M1));
    }

    #[test]
    fn markprice_drop_triggers_full_liquidation_collects_fee_to_if_and_conserves() {
        let mut core = seeded();
        core.process_command(&mut markprice(100, 1_000));
        open_borrower_long(&mut core);
        let mut m2 = fut_order(3, M2, 92, 10, OrderAction::Bid, OrderType::Gtc, 10);
        core.process_command(&mut m2);
        assert_eq!(m2.result_code, Some(CommandResultCode::Success));

        let before = conserved(&core);
        core.process_command(&mut markprice(94, 2_000));

        assert!(
            core.pending_commands.borrow().is_empty(),
            "队列必须被排空（生成的 FORCE 已处理）"
        );
        assert!(
            !core.ups.get(BORROWER).unwrap().positions.contains_key(&FUT),
            "借款人 LONG 被 FORCE 全平，仓位移除"
        );
        let if_available: i64 = core.risk.liquidation_service.notionals.values().map(|n| n.available).sum();
        assert!(if_available > 0, "liquidation fee must be credited to IFNotional.available");
        assert_eq!(conserved(&core), before, "globally conserved after force liquidation (incl. liquidation fee transferred to IF)");
    }

    #[test]
    fn cluster_mode_hands_cascade_to_submitter_without_inline_apply() {
        use std::cell::RefCell;
        use std::rc::Rc;
        let mut core = seeded();
        core.process_command(&mut markprice(100, 1_000));
        open_borrower_long(&mut core);
        let mut m2 = fut_order(3, M2, 92, 10, OrderAction::Bid, OrderType::Gtc, 10);
        core.process_command(&mut m2);

        let captured: Rc<RefCell<Vec<OrderCommand>>> = Rc::new(RefCell::new(Vec::new()));
        core.with_command_submitter(Rc::new(RefCell::new(
            crate::core::processors::liquidation::command_submitter::VecCommandSink(captured.clone()),
        )));

        core.process_command(&mut markprice(94, 2_000));

        assert!(
            captured.borrow().iter().any(|c| c.command == OrderCommandType::ForceLiquidation && c.uid == BORROWER),
            "borrower 的 FORCE_LIQUIDATION 应被交给 submitter（去走 raft 共识）"
        );
        assert!(
            core.pending_commands.borrow().is_empty(),
            "pending 已全部交出，引擎内不残留"
        );
        assert!(
            core.ups.get(BORROWER).unwrap().positions.contains_key(&FUT),
            "集群模式下 FORCE 尚未回流 apply，借款人仓位仍在（未就地平仓）"
        );
    }

    #[test]
    fn cluster_mode_cascade_completes_across_rounds_conserves() {
        use std::cell::RefCell;
        use std::collections::VecDeque;
        use std::rc::Rc;
        let mut core = seeded();
        core.process_command(&mut markprice(100, 1_000));
        open_borrower_long(&mut core);
        let mut m2 = fut_order(3, M2, 92, 10, OrderAction::Bid, OrderType::Gtc, 10);
        core.process_command(&mut m2);
        let before = conserved(&core);

        struct RaftQueueSubmitter(Rc<RefCell<VecDeque<OrderCommand>>>);
        impl crate::core::processors::liquidation::command_submitter::CommandSubmitter for RaftQueueSubmitter {
            fn submit(&mut self, cmd: OrderCommand) {
                self.0.borrow_mut().push_back(cmd);
            }
        }
        let queue: Rc<RefCell<VecDeque<OrderCommand>>> = Rc::new(RefCell::new(VecDeque::new()));
        core.with_command_submitter(Rc::new(RefCell::new(RaftQueueSubmitter(queue.clone()))));

        core.process_command(&mut markprice(94, 2_000));

        let mut rounds = 0;
        while let Some(mut cmd) = { let n = queue.borrow_mut().pop_front(); n } {
            core.process_command(&mut cmd);
            rounds += 1;
        }

        assert!(rounds > 0, "至少回流处理了 FORCE");
        assert!(queue.borrow().is_empty());
        assert!(core.pending_commands.borrow().is_empty());
        assert!(
            !core.ups.get(BORROWER).unwrap().positions.contains_key(&FUT),
            "raft 回流 apply 后借款人被强平（与单节点同一终态）"
        );
        assert_eq!(conserved(&core), before, "raft 回流路径全局守恒");
    }

    #[test]
    fn direct_force_size_clamped_by_normalize() {
        let mut core = seeded();
        core.process_command(&mut markprice(100, 1_000));
        open_borrower_long(&mut core);
        let mut m2 = fut_order(3, M2, 90, 100, OrderAction::Bid, OrderType::Gtc, 10);
        core.process_command(&mut m2);

        let before = conserved(&core);
        let mut force = OrderCommand {
            command: OrderCommandType::ForceLiquidation,
            order_id: 42,
            uid: BORROWER,
            symbol: FUT,
            price: 90,
            size: 999,
            action: Some(OrderAction::Ask),
            order_type: Some(OrderType::Ioc),
            timestamp: 3_000,
            ..Default::default()
        };
        core.process_command(&mut force);
        assert_eq!(force.size, 10, "normalize must clamp cmd.size to open_volume=10, must not over-close");
        assert!(!core.ups.get(BORROWER).unwrap().positions.contains_key(&FUT), "position removed after full close of 10 lots");
        assert_eq!(conserved(&core), before, "conserved after normal fill following clamp");
    }

    #[test]
    fn force_with_no_liquidity_cascades_force_if_adl_without_panic_and_conserves() {
        let mut core = seeded();
        core.process_command(&mut markprice(100, 1_000));
        open_borrower_long(&mut core);

        let before = conserved(&core);
        core.process_command(&mut markprice(94, 2_000));

        assert!(core.pending_commands.borrow().is_empty(), "queue drained after FORCE→IF→ADL cascade");
        assert_eq!(conserved(&core), before, "cascade with no fills does not change any balance, conserved");
    }
}

#[cfg(test)]
mod loan_scanner_e2e_tests {
    use super::*;
    use crate::core::common::cmd::command_result_code::CommandResultCode;
    use crate::core::common::cmd::order_command_type::OrderCommandType;
    use crate::core::common::core_currency_specification::CoreCurrencySpecification;
    use crate::core::common::core_symbol_specification::CoreSymbolSpecification;
    use crate::core::common::isolated_loan_record::IsolatedLoanRecord;
    use crate::core::common::order_action::OrderAction;
    use crate::core::common::order_type::OrderType;
    use crate::core::common::symbol_loan_specification::SymbolLoanSpecification;
    use crate::core::common::symbol_type::SymbolType;

    const COLL: i32 = 1;
    const LOANC: i32 = 2;
    const SYMBOL: i32 = 100;
    const BORROWER: i64 = 10;
    const MAKER: i64 = 20;
    const LOAN_ID: i64 = 42;

    fn loan_spot_spec() -> CoreSymbolSpecification {
        CoreSymbolSpecification {
            symbol_id: SYMBOL,
            symbol_type: SymbolType::CurrencyExchangePair,
            base_currency: COLL,
            quote_currency: LOANC,
            base_scale_k: 1,
            quote_scale_k: 1,
            loan_config: SymbolLoanSpecification {
                initial_ltv_bps: 5000,
                liquidation_ltv_bps: 8000,
                margin_call_ltv_bps: 7000,
                max_amount: 0,
                max_term_days: 0,
            },
            ..Default::default()
        }
    }

    fn conserved(core: &ExchangeCore, cur: i32) -> i64 {
        let accounts: i64 = core.ups.users.values().map(|u| u.account(cur)).sum();
        accounts
            + core.risk.loan_service.get_loan_pool_available(cur)
            + core.risk.loan_service.get_interest_revenue(cur)
            + core.risk.loan_service.get_loan_insurance_fund(cur)
            + *core.risk.fees.get(&cur).unwrap_or(&0)
            + *core.risk.adjustments.get(&cur).unwrap_or(&0)
    }

    #[test]
    fn liquidation_scan_triggers_isolated_loan_force_liquidate_and_conserves() {
        let mut core = ExchangeCore::new();
        core.ssp.add_currency(CoreCurrencySpecification { currency: COLL, currency_scale_k: 1, collateral_weight_bps: 8000, ..Default::default() });
        core.ssp.add_currency(CoreCurrencySpecification { currency: LOANC, currency_scale_k: 1, ..Default::default() });
        assert_eq!(core.ssp.add_symbol(loan_spot_spec()), CommandResultCode::Success);
        core.matching.add_symbol(&loan_spot_spec());
        core.ups.add_empty_user_profile(BORROWER);
        core.ups.add_empty_user_profile(MAKER);
        core.risk.last_price_cache.insert(SYMBOL, LastPriceCacheRecord::with_mark(1));
        core.risk.liquidation_engine.is_running = true;

        core.risk.loan_service.add_to_loan_pool_available(LOANC, 1_000_000);
        {
            let b = core.ups.get_mut(BORROWER).unwrap();
            b.add_to_account(COLL, 1_000);
            let mut loan = IsolatedLoanRecord::new(BORROWER, LOAN_ID, SYMBOL, COLL, LOANC, 0, 0);
            loan.outstanding_principal = 900;
            loan.collateral_amount = 1_000;
            b.isolated_loans.insert(LOAN_ID, loan);
        }
        let b = core.ups.get_mut(BORROWER).unwrap();
        core.risk.loan_service.disburse_loan(b, LOANC, 900);

        core.ups.get_mut(MAKER).unwrap().add_to_account(LOANC, 1_000_000_000);
        let mut mk = OrderCommand {
            command: OrderCommandType::PlaceOrder,
            order_id: 1,
            uid: MAKER,
            symbol: SYMBOL,
            price: 1,
            size: 2_000,
            reserve_bid_price: 1,
            action: Some(OrderAction::Bid),
            order_type: Some(OrderType::Gtc),
            timestamp: 1_000,
            ..Default::default()
        };
        core.process_command(&mut mk);
        assert_eq!(mk.result_code, Some(CommandResultCode::Success));

        let before_coll = conserved(&core, COLL);
        let before_loanc = conserved(&core, LOANC);

        let mut scan = OrderCommand {
            command: OrderCommandType::LiquidationScan,
            symbol: -1,
            uid: 0,
            size: 0,
            timestamp: 2_000,
            ..Default::default()
        };
        core.process_command(&mut scan);

        assert!(core.pending_commands.borrow().is_empty(), "force-liquidate generated by scan has been drained and processed");
        assert!(
            !core.ups.get(BORROWER).unwrap().isolated_loans.contains_key(&LOAN_ID),
            "越线 loan 被强平（1000 抵押全卖、900 本金还清、loan 移除）"
        );
        assert_eq!(conserved(&core, COLL), before_coll, "COLL conserved");
        assert_eq!(conserved(&core, LOANC), before_loanc, "LOANC conserved");
    }
}

#[cfg(test)]
mod snapshot_tests {
    use super::*;
    use crate::core::common::cmd::command_result_code::CommandResultCode;
    use crate::core::common::cmd::order_command_type::OrderCommandType;
    use crate::core::common::core_currency_specification::CoreCurrencySpecification;
    use crate::core::common::core_symbol_specification::CoreSymbolSpecification;
    use crate::core::common::isolated_loan_record::IsolatedLoanRecord;
    use crate::core::common::order_action::OrderAction;
    use crate::core::common::order_type::OrderType;
    use crate::core::common::position_direction::PositionDirection;
    use crate::core::common::symbol_loan_specification::SymbolLoanSpecification;
    use crate::core::common::symbol_type::SymbolType;

    const BASE: i32 = 1;
    const QUOTE: i32 = 2;
    const FUT: i32 = 700;
    const SPOT: i32 = 100;
    const U_LONG: i64 = 10;
    const U_SHORT: i64 = 11;
    const U_MAKER: i64 = 12;
    const BORROWER: i64 = 13;

    fn fut_spec() -> CoreSymbolSpecification {
        let mut mm = std::collections::BTreeMap::new();
        mm.insert(i64::MAX, 500);
        CoreSymbolSpecification {
            symbol_id: FUT,
            symbol_type: SymbolType::FuturesContractPerpetual,
            base_currency: BASE,
            quote_currency: QUOTE,
            base_scale_k: 1,
            quote_scale_k: 1,
            fee_scale_k: 10_000,
            liquidation_fee: 200,
            maintenance_margin: mm,
            maintenance_margin_scale_k: 10_000,
            ..Default::default()
        }
    }

    fn spot_spec() -> CoreSymbolSpecification {
        CoreSymbolSpecification {
            symbol_id: SPOT,
            symbol_type: SymbolType::CurrencyExchangePair,
            base_currency: BASE,
            quote_currency: QUOTE,
            base_scale_k: 1,
            quote_scale_k: 1,
            loan_config: SymbolLoanSpecification {
                initial_ltv_bps: 5000,
                liquidation_ltv_bps: 8000,
                margin_call_ltv_bps: 7000,
                max_amount: 0,
                max_term_days: 0,
            },
            ..Default::default()
        }
    }

    fn build_rich_core(ser_proc: Box<dyn SerializationProcessor>) -> ExchangeCore {
        let mut core = ExchangeCore::new(); core.with_serialization_processor(ser_proc);
        core.ssp.add_currency(CoreCurrencySpecification { currency: BASE, currency_scale_k: 1, collateral_weight_bps: 8000, ..Default::default() });
        core.ssp.add_currency(CoreCurrencySpecification { currency: QUOTE, currency_scale_k: 1, ..Default::default() });
        assert_eq!(core.ssp.add_symbol(fut_spec()), CommandResultCode::Success);
        assert_eq!(core.ssp.add_symbol(spot_spec()), CommandResultCode::Success);
        core.matching.add_symbol(&fut_spec());
        core.matching.add_symbol(&spot_spec());
        for uid in [U_LONG, U_SHORT, U_MAKER, BORROWER] {
            core.ups.add_empty_user_profile(uid);
            core.ups.get_mut(uid).unwrap().add_to_account(QUOTE, 10_000_000);
        }
        core.risk.liquidation_engine.is_running = true;

        let mut mp = OrderCommand { command: OrderCommandType::MarkpriceAdjustment, symbol: FUT, price: 100, timestamp: 1_000, ..Default::default() };
        core.process_command(&mut mp);
        let mut a = fut_order(1, U_SHORT, 100, 10, false, 10);
        core.process_command(&mut a);
        let mut b = fut_order(2, U_LONG, 100, 10, true, 10);
        core.process_command(&mut b);
        let mut resting = fut_order(3, U_MAKER, 80, 5, true, 10);
        core.process_command(&mut resting);

        core.risk.loan_service.add_to_loan_pool_available(QUOTE, 1_000_000);
        {
            let bp = core.ups.get_mut(BORROWER).unwrap();
            bp.add_to_account(BASE, 1_000);
            let mut loan = IsolatedLoanRecord::new(BORROWER, 99, SPOT, BASE, QUOTE, 0, 0);
            loan.outstanding_principal = 300;
            loan.collateral_amount = 1_000;
            bp.isolated_loans.insert(99, loan);
        }
        let bp = core.ups.get_mut(BORROWER).unwrap();
        core.risk.loan_service.disburse_loan(bp, QUOTE, 300);
        core.risk.liquidation_engine.loan_liquidation_engine.on_isolated_loan_opened(BORROWER, SPOT);

        core.risk.liquidation_service.credit_liquidation_fee(FUT, 500);
        core
    }

    fn fut_order(order_id: i64, uid: i64, price: i64, size: i64, bid: bool, leverage: i32) -> OrderCommand {
        OrderCommand {
            command: OrderCommandType::PlaceOrder,
            order_id,
            uid,
            symbol: FUT,
            price,
            size,
            reserve_bid_price: price,
            action: Some(if bid { OrderAction::Bid } else { OrderAction::Ask }),
            order_type: Some(OrderType::Gtc),
            leverage,
            margin_mode: crate::core::common::margin_mode::MarginMode::Isolated,
            timestamp: 1_000,
            ..Default::default()
        }
    }

    #[test]
    fn snapshot_roundtrip_preserves_replicated_state_and_rebuilds_non_replicated() {

        let shared = InMemorySerializationProcessor::new();
        let mut core = build_rich_core(Box::new(shared.clone()));
        assert!(core.persist(1, 0));
        let mut restored = ExchangeCore::new(); restored.with_serialization_processor(Box::new(shared.clone()));
        restored.recover(1, 0);

        assert!(restored.persist(2, 0));
        let m_re = SerializedModuleType::RiskEngine;
        let m_me = SerializedModuleType::MatchingEngineRouter;
        assert_eq!(shared.load_data(2, m_re, 0), shared.load_data(1, m_re, 0), "RE module snapshot round-trip must be byte-equal");
        assert_eq!(shared.load_data(2, m_me, 0), shared.load_data(1, m_me, 0), "ME module snapshot round-trip must be byte-equal");

        assert_eq!(restored.ups.get(U_LONG).unwrap().positions[&FUT].open_volume, 10);
        assert_eq!(restored.ups.get(U_LONG).unwrap().positions[&FUT].direction, PositionDirection::Long);
        assert_eq!(restored.ups.get(BORROWER).unwrap().isolated_loans[&99].outstanding_principal, 300);
        assert_eq!(restored.risk.loan_service.get_loan_pool_available(QUOTE), 1_000_000 - 300);
        assert_eq!(restored.risk.liquidation_service.notionals[&FUT].available, 500);
        let mut ob = OrderCommand { command: OrderCommandType::OrderBookRequest, symbol: FUT, size: 10, ..Default::default() };
        let mut restored2 = ExchangeCore::new(); restored2.with_serialization_processor(Box::new(shared.clone()));
        restored2.recover(1, 0);
        restored2.process_command(&mut ob);
        let md = ob.market_data.unwrap();
        assert!(md.bid_prices.contains(&80), "resting order book state must be restored with the snapshot");

        assert_eq!(restored.ups.get(U_LONG).unwrap().positions[&FUT].adl_eligibility, 100, "ISOLATED position adl_eligibility restored to 100");
        assert!(restored.ups.get(U_LONG).unwrap().positions[&FUT].liquidation_flow.is_none());
        assert_eq!(restored.ups.get(U_LONG).unwrap().positions[&FUT].pending_adl_size, 0);

        let holders = restored.risk.liquidation_engine.symbol_to_users.get(&FUT).expect("futures index rebuilt");
        assert!(holders.contains(&U_LONG) && holders.contains(&U_SHORT));
        assert!(!holders.contains(&U_MAKER), "users who only rest orders without opening a position are filtered by open_volume>0 and excluded from the rebuilt index (aligned with Java)");
        assert!(
            restored.risk.liquidation_engine.loan_liquidation_engine.isolated_loan_symbol_to_users.get(&SPOT).unwrap().contains(&BORROWER),
            "loan 索引重建"
        );
        assert!(!restored.risk.liquidation_engine.is_running);
    }

    #[test]
    fn restored_core_liquidation_works_via_rebuilt_index() {
        let shared = InMemorySerializationProcessor::new();
        let mut core = build_rich_core(Box::new(shared.clone()));
        assert!(core.persist(1, 0));
        let mut restored = ExchangeCore::new(); restored.with_serialization_processor(Box::new(shared.clone()));
        restored.recover(1, 0);
        restored.risk.liquidation_engine.is_running = true;

        let mut mk = fut_order(50, U_MAKER, 92, 10, true, 10);
        restored.process_command(&mut mk);

        let mut mp = OrderCommand { command: OrderCommandType::MarkpriceAdjustment, symbol: FUT, price: 94, timestamp: 5_000, ..Default::default() };
        restored.process_command(&mut mp);

        assert!(restored.pending_commands.borrow().is_empty(), "force-liquidation cascade drains normally after recovery");
        assert!(
            !restored.ups.get(U_LONG).unwrap().positions.contains_key(&FUT),
            "恢复后 targeted 索引生效，U_LONG 被强平平仓"
        );
    }
}

#[cfg(test)]
mod settle_pnl_tests {
    use super::*;
    use crate::core::common::cmd::command_result_code::CommandResultCode;
    use crate::core::common::cmd::order_command_type::OrderCommandType;
    use crate::core::common::core_currency_specification::CoreCurrencySpecification;
    use crate::core::common::core_symbol_specification::CoreSymbolSpecification;
    use crate::core::common::margin_mode::MarginMode;
    use crate::core::common::order_action::OrderAction;
    use crate::core::common::order_type::OrderType;
    use crate::core::common::symbol_type::SymbolType;

    const BASE: i32 = 1;
    const QUOTE: i32 = 2;
    const DELIV: i32 = 800;
    const PERP: i32 = 801;
    const U_LONG: i64 = 10;
    const U_SHORT: i64 = 11;

    fn deliv_spec() -> CoreSymbolSpecification {
        let mut mm = std::collections::BTreeMap::new();
        mm.insert(i64::MAX, 500);
        CoreSymbolSpecification {
            symbol_id: DELIV,
            symbol_type: SymbolType::FuturesContractDelivery,
            base_currency: BASE,
            quote_currency: QUOTE,
            base_scale_k: 1,
            quote_scale_k: 1,
            maintenance_margin: mm,
            maintenance_margin_scale_k: 10_000,
            ..Default::default()
        }
    }

    fn conserved(core: &ExchangeCore) -> i64 {
        let cur = QUOTE;
        let mark = core.risk.last_price_cache.get(&DELIV).map(|r| r.mark_price).unwrap_or(0);
        let mut total: i64 = core.ups.users.values().map(|u| u.account(cur)).sum();
        total += *core.risk.fees.get(&cur).unwrap_or(&0);
        total += *core.risk.adjustments.get(&cur).unwrap_or(&0);
        for u in core.ups.users.values() {
            for p in u.positions.values() {
                if p.currency == cur {
                    total += p.estimate_pnl(mark) + p.extra_margin;
                }
            }
        }
        total
    }

    fn order(oid: i64, uid: i64, symbol: i32, price: i64, size: i64, bid: bool) -> OrderCommand {
        OrderCommand {
            command: OrderCommandType::PlaceOrder,
            order_id: oid,
            uid,
            symbol,
            price,
            size,
            reserve_bid_price: price,
            action: Some(if bid { OrderAction::Bid } else { OrderAction::Ask }),
            order_type: Some(OrderType::Gtc),
            leverage: 10,
            margin_mode: MarginMode::Isolated,
            timestamp: 1_000,
            ..Default::default()
        }
    }

    fn seeded() -> ExchangeCore {
        let mut core = ExchangeCore::new();
        core.ssp.add_currency(CoreCurrencySpecification { currency: BASE, currency_scale_k: 1, ..Default::default() });
        core.ssp.add_currency(CoreCurrencySpecification { currency: QUOTE, currency_scale_k: 1, ..Default::default() });
        assert_eq!(core.ssp.add_symbol(deliv_spec()), CommandResultCode::Success);
        core.matching.add_symbol(&deliv_spec());
        for uid in [U_LONG, U_SHORT] {
            core.ups.add_empty_user_profile(uid);
            core.ups.get_mut(uid).unwrap().add_to_account(QUOTE, 1_000_000);
        }
        core
    }

    #[test]
    fn settle_pnl_closes_all_positions_at_delivery_price_and_conserves() {
        let mut core = seeded();
        core.process_command(&mut OrderCommand { command: OrderCommandType::MarkpriceAdjustment, symbol: DELIV, price: 100, timestamp: 1_000, ..Default::default() });
        core.process_command(&mut order(1, U_SHORT, DELIV, 100, 10, false));
        core.process_command(&mut order(2, U_LONG, DELIV, 100, 10, true));
        assert_eq!(core.ups.get(U_LONG).unwrap().positions[&DELIV].open_volume, 10);
        assert_eq!(core.ups.get(U_SHORT).unwrap().positions[&DELIV].open_volume, 10);

        let long_acct0 = core.ups.get(U_LONG).unwrap().account(QUOTE);
        let short_acct0 = core.ups.get(U_SHORT).unwrap().account(QUOTE);
        let before = conserved(&core);

        let mut settle = OrderCommand { command: OrderCommandType::SettlePnl, symbol: DELIV, price: 105, timestamp: 2_000, ..Default::default() };
        core.process_command(&mut settle);
        assert_eq!(settle.result_code, Some(CommandResultCode::Success));

        assert!(!core.ups.get(U_LONG).unwrap().positions.contains_key(&DELIV), "LONG delivery close removes position");
        assert!(!core.ups.get(U_SHORT).unwrap().positions.contains_key(&DELIV), "SHORT delivery close removes position");
        assert_eq!(core.ups.get(U_LONG).unwrap().account(QUOTE) - long_acct0, 50, "LONG delivery profit (105-100)*10=+50");
        assert_eq!(core.ups.get(U_SHORT).unwrap().account(QUOTE) - short_acct0, -50, "SHORT delivery loss (100-105)*10=-50");
        assert_eq!(conserved(&core), before, "globally conserved after delivery settlement");
    }

    #[test]
    fn settle_pnl_on_non_delivery_symbol_is_invalid() {
        let mut core = seeded();
        let perp = CoreSymbolSpecification { symbol_id: PERP, symbol_type: SymbolType::FuturesContractPerpetual, ..deliv_spec() };
        assert_eq!(core.ssp.add_symbol(perp.clone()), CommandResultCode::Success);
        core.matching.add_symbol(&perp);

        let mut settle = OrderCommand { command: OrderCommandType::SettlePnl, symbol: PERP, price: 105, timestamp: 2_000, ..Default::default() };
        core.process_command(&mut settle);
        assert_eq!(settle.result_code, Some(CommandResultCode::InvalidSymbol), "SETTLE_PNL is only valid for delivery contracts");
    }
}
