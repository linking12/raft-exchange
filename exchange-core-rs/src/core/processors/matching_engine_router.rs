use std::collections::BTreeMap;

use crate::core::common::cmd::order_command::OrderCommand;
use crate::core::common::cmd::command_result_code::CommandResultCode;
use crate::core::common::cmd::order_command_type::OrderCommandType;
use crate::core::common::core_symbol_specification::CoreSymbolSpecification;
use crate::core::common::order::Order;
use crate::core::orderbook::i_order_book::IOrderBook;
use crate::core::orderbook::order_book_direct_impl::OrderBookDirectImpl;
use crate::core::orderbook::order_book_naive_impl::OrderBookNaiveImpl;
use crate::core::processors::binary_commands_processor::BinaryCommandsProcessor;

#[derive(Default)]
pub struct MatchingEngineRouter {
    pub(crate) books: BTreeMap<i32, OrderBookDirectImpl>,
    pub(crate) binary_cmd: BinaryCommandsProcessor,
}

impl MatchingEngineRouter {

    pub fn new() -> Self {
        MatchingEngineRouter { books: BTreeMap::new(), binary_cmd: BinaryCommandsProcessor::new() }
    }

    pub fn process_order(&mut self, cmd: &mut OrderCommand) -> CommandResultCode {
        if cmd.command.is_non_trading()
            || (cmd.command.is_loan()
                && cmd.command != OrderCommandType::LoanForceLiquidate
                && cmd.command != OrderCommandType::LoanCrossForceLiquidate)
            || matches!(
                cmd.command,
                OrderCommandType::SettleFundingfees
                    | OrderCommandType::IfTakeover
                    | OrderCommandType::AutoDeleveraging
                    | OrderCommandType::LiquidationScan
            )
        {
            return cmd.result_code.unwrap_or(CommandResultCode::MatchingUnsupportedCommand);
        }

        let Some(book) = self.books.get_mut(&cmd.symbol) else {
            let rc = CommandResultCode::MatchingInvalidOrderBookId;
            cmd.result_code = Some(rc);
            return rc;
        };

        let rc = match cmd.command {
            OrderCommandType::MoveOrder => book.move_order(cmd),
            OrderCommandType::CancelOrder => book.cancel_order(cmd),
            OrderCommandType::ReduceOrder => book.reduce_order(cmd),
            OrderCommandType::PlaceOrder
            | OrderCommandType::ClosePosition
            | OrderCommandType::ForceLiquidation
            | OrderCommandType::LoanForceLiquidate
            | OrderCommandType::LoanCrossForceLiquidate => {
                if cmd.result_code == Some(CommandResultCode::ValidForMatchingEngine) {
                    book.new_order(cmd)
                } else {
                    return cmd.result_code.unwrap_or(CommandResultCode::MatchingUnsupportedCommand);
                }
            }
            OrderCommandType::OrderBookRequest => {
                let size = cmd.size as i32;
                let size = if size >= 0 { size } else { i32::MAX };
                cmd.market_data = Some(book.fill_l2(size));
                CommandResultCode::Success
            }
            _ => CommandResultCode::MatchingUnsupportedCommand,
        };

        cmd.result_code = Some(rc);
        rc
    }

    pub fn add_symbol(&mut self, spec: &CoreSymbolSpecification) {
        self.books.entry(spec.symbol_id).or_insert_with(|| OrderBookDirectImpl::with_symbol_spec(spec.clone()));
    }

    pub fn reset(&mut self) {
        self.books.clear();
    }

    pub fn user_orders(&self, uid: i64) -> Vec<(i32, Order)> {
        let mut out = Vec::new();
        for (&sym, book) in &self.books {
            for o in book.find_user_orders(uid) {
                out.push((sym, o));
            }
        }
        out
    }

    pub fn order_books_state_hash(&self) -> i64 {
        let mut h: i64 = 17;
        for (&sym, book) in &self.books {
            h = h.wrapping_mul(31).wrapping_add(sym as i64);
            h = h.wrapping_mul(31).wrapping_add(book.state_hash() as i64);
        }
        h
    }
}

use crate::core::snapshot::chronicle_reader::{ChronicleError, ChronicleReader};
use crate::core::snapshot::chronicle_writer::ChronicleWriter;
use crate::core::snapshot::marshalling::ChronicleMarshallable;

impl ChronicleMarshallable for MatchingEngineRouter {
    fn chronicle_write(&self, w: &mut ChronicleWriter) {
        w.write_i32(0);
        w.write_i64(0);
        self.binary_cmd.chronicle_write(w);
        w.write_int_keyed_map(
            &self.books,
            |vw, v| v.chronicle_write(vw),
        );
    }
    fn chronicle_read(r: &mut ChronicleReader) -> Result<Self, ChronicleError> {
        let _shard_id = r.read_i32()?;
        let _shard_mask = r.read_i64()?;
        let binary_cmd = BinaryCommandsProcessor::chronicle_read(r)?;
        let books = crate::core::snapshot::marshalling::to_btree_i32(
            r.read_int_keyed_map(read_order_book_dispatch)?,
        );
        Ok(MatchingEngineRouter { books, binary_cmd })
    }
}

fn read_order_book_dispatch(r: &mut ChronicleReader) -> Result<OrderBookDirectImpl, ChronicleError> {
    let impl_type = r.read_u8()?;
    match impl_type {
        2 => OrderBookDirectImpl::chronicle_read_body(r),
        0 => {
            let naive = OrderBookNaiveImpl::chronicle_read_body(r)?;
            let spec = naive.chronicle_symbol_spec().expect("naive order book missing symbol_spec");
            let (asks, bids) = naive.chronicle_orders();
            Ok(OrderBookDirectImpl::restore_chronicle(spec, asks, bids))
        }
        other => panic!("unknown OrderBookImplType code {other} (only NAIVE=0 / DIRECT=2 supported)"),
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::core::common::order_action::OrderAction;
    use crate::core::common::order_type::OrderType;
    use crate::core::common::symbol_type::SymbolType;

    fn spec(symbol_id: i32) -> CoreSymbolSpecification {
        CoreSymbolSpecification {
            symbol_id,
            symbol_type: SymbolType::CurrencyExchangePair,
            base_currency: 1,
            quote_currency: 2,
            base_scale_k: 1,
            quote_scale_k: 1,
            taker_fee: 0,
            maker_fee: 0,
            fee_scale_k: 0,
            ..Default::default()
        }
    }

    fn place_cmd(order_id: i64, symbol: i32, result_code: CommandResultCode) -> OrderCommand {
        OrderCommand {
            command: OrderCommandType::PlaceOrder,
            order_id,
            symbol,
            price: 100,
            size: 10,
            action: Some(OrderAction::Bid),
            order_type: Some(OrderType::Gtc),
            uid: order_id,
            result_code: Some(result_code),
            ..Default::default()
        }
    }

    #[test]
    fn chronicle_read_dispatches_naive_book_to_direct() {
        let mut naive = OrderBookNaiveImpl::with_symbol_spec(spec(7));
        for (id, act, price, size) in [
            (1i64, OrderAction::Ask, 110i64, 5i64),
            (2, OrderAction::Ask, 120, 7),
            (3, OrderAction::Bid, 100, 4),
            (4, OrderAction::Bid, 90, 6),
        ] {
            let mut cmd = OrderCommand { order_id: id, symbol: 7, price, size,
                action: Some(act), order_type: Some(OrderType::Gtc), uid: id, ..Default::default() };
            naive.new_order(&mut cmd);
        }
        let mut w = ChronicleWriter::new();
        w.write_i32(0);
        w.write_i64(0);
        w.write_i32(0);
        w.write_i32(1);
        w.write_i32(7);
        naive.chronicle_write(&mut w);
        let bytes = w.into_bytes();
        let router = MatchingEngineRouter::chronicle_read(&mut ChronicleReader::new(&bytes)).unwrap();
        assert_eq!(router.books.len(), 1);
        let direct = router.books.get(&7).expect("symbol 7 book");
        assert_eq!(direct.fill_l2(100).ask_prices, naive.fill_l2(100).ask_prices);
        assert_eq!(direct.fill_l2(100).ask_volumes, naive.fill_l2(100).ask_volumes);
        assert_eq!(direct.fill_l2(100).bid_prices, naive.fill_l2(100).bid_prices);
        assert_eq!(direct.fill_l2(100).bid_volumes, naive.fill_l2(100).bid_volumes);
    }

    #[test]
    fn place_order_valid_for_matching_engine_routes_and_rests_on_book() {
        let mut router = MatchingEngineRouter::new();
        router.add_symbol(&spec(1));

        let mut place = place_cmd(1, 1, CommandResultCode::ValidForMatchingEngine);
        let rc = router.process_order(&mut place);
        assert_eq!(rc, CommandResultCode::Success);
        assert_eq!(place.result_code, Some(CommandResultCode::Success));

        let mut req = OrderCommand {
            command: OrderCommandType::OrderBookRequest,
            symbol: 1,
            size: 10,
            ..Default::default()
        };
        let rc2 = router.process_order(&mut req);
        assert_eq!(rc2, CommandResultCode::Success);
        let md = req.market_data.expect("L2 market data should have been filled");
        assert_eq!(md.bid_prices, vec![100]);
        assert_eq!(md.bid_volumes, vec![10]);
    }

    #[test]
    fn order_book_request_returns_l2_for_known_symbol() {
        let mut router = MatchingEngineRouter::new();
        router.add_symbol(&spec(7));

        let mut ask = place_cmd(1, 7, CommandResultCode::ValidForMatchingEngine);
        ask.action = Some(OrderAction::Ask);
        router.process_order(&mut ask);

        let mut req = OrderCommand {
            command: OrderCommandType::OrderBookRequest,
            symbol: 7,
            size: 5,
            ..Default::default()
        };
        router.process_order(&mut req);
        let md = req.market_data.expect("L2 market data should have been filled");
        assert_eq!(md.ask_prices, vec![100]);
        assert_eq!(md.ask_volumes, vec![10]);
    }

    #[test]
    fn unknown_symbol_reports_error_and_does_not_panic() {
        let mut router = MatchingEngineRouter::new();
        let mut cmd = place_cmd(1, 99, CommandResultCode::ValidForMatchingEngine);
        let rc = router.process_order(&mut cmd);
        assert_eq!(rc, CommandResultCode::MatchingInvalidOrderBookId);
        assert_eq!(cmd.result_code, Some(CommandResultCode::MatchingInvalidOrderBookId));
    }

    #[test]
    fn place_order_not_valid_for_matching_engine_is_not_placed() {
        let mut router = MatchingEngineRouter::new();
        router.add_symbol(&spec(1));

        let mut place = place_cmd(1, 1, CommandResultCode::RiskNsf);
        let rc = router.process_order(&mut place);
        assert_eq!(rc, CommandResultCode::RiskNsf);
        assert_eq!(place.result_code, Some(CommandResultCode::RiskNsf));

        let mut req = OrderCommand {
            command: OrderCommandType::OrderBookRequest,
            symbol: 1,
            size: 10,
            ..Default::default()
        };
        router.process_order(&mut req);
        let md = req.market_data.expect("L2 market data should have been filled");
        assert!(md.bid_prices.is_empty());
        assert!(md.ask_prices.is_empty());
    }

    #[test]
    fn add_symbol_is_idempotent_for_duplicate_registration() {
        let mut router = MatchingEngineRouter::new();
        router.add_symbol(&spec(1));
        let mut place = place_cmd(1, 1, CommandResultCode::ValidForMatchingEngine);
        router.process_order(&mut place);
        router.add_symbol(&spec(1));

        let mut req = OrderCommand {
            command: OrderCommandType::OrderBookRequest,
            symbol: 1,
            size: 10,
            ..Default::default()
        };
        router.process_order(&mut req);
        let md = req.market_data.expect("L2 market data should have been filled");
        assert_eq!(md.bid_volumes, vec![10]);
    }

    fn close_position_cmd(order_id: i64, symbol: i32, result_code: CommandResultCode) -> OrderCommand {
        OrderCommand {
            command: OrderCommandType::ClosePosition,
            order_id,
            symbol,
            price: 100,
            size: 10,
            action: Some(OrderAction::Ask),
            order_type: Some(OrderType::Gtc),
            uid: order_id,
            result_code: Some(result_code),
            ..Default::default()
        }
    }

    #[test]
    fn close_position_valid_for_matching_engine_routes_and_rests_on_book() {
        let mut router = MatchingEngineRouter::new();
        router.add_symbol(&spec(1));

        let mut close = close_position_cmd(1, 1, CommandResultCode::ValidForMatchingEngine);
        let rc = router.process_order(&mut close);
        assert_eq!(rc, CommandResultCode::Success);
        assert_eq!(close.result_code, Some(CommandResultCode::Success));

        let mut req = OrderCommand {
            command: OrderCommandType::OrderBookRequest,
            symbol: 1,
            size: 10,
            ..Default::default()
        };
        router.process_order(&mut req);
        let md = req.market_data.expect("L2 market data should have been filled");
        assert_eq!(md.ask_prices, vec![100]);
        assert_eq!(md.ask_volumes, vec![10]);
    }

    #[test]
    fn close_position_not_valid_for_matching_engine_is_not_placed_and_result_code_preserved() {
        let mut router = MatchingEngineRouter::new();
        router.add_symbol(&spec(1));

        let mut close = close_position_cmd(1, 1, CommandResultCode::UnsupportedSymbolType);
        let rc = router.process_order(&mut close);
        assert_eq!(rc, CommandResultCode::UnsupportedSymbolType);
        assert_eq!(close.result_code, Some(CommandResultCode::UnsupportedSymbolType));

        let mut req = OrderCommand {
            command: OrderCommandType::OrderBookRequest,
            symbol: 1,
            size: 10,
            ..Default::default()
        };
        router.process_order(&mut req);
        let md = req.market_data.expect("L2 market data should have been filled");
        assert!(md.ask_prices.is_empty());
    }

    fn force_liquidation_cmd(order_id: i64, symbol: i32, result_code: CommandResultCode) -> OrderCommand {
        OrderCommand {
            command: OrderCommandType::ForceLiquidation,
            order_id,
            symbol,
            price: 100,
            size: 10,
            action: Some(OrderAction::Ask),
            order_type: Some(OrderType::Gtc),
            uid: order_id,
            result_code: Some(result_code),
            ..Default::default()
        }
    }

    #[test]
    fn force_liquidation_valid_for_matching_engine_routes_and_rests_on_book() {
        let mut router = MatchingEngineRouter::new();
        router.add_symbol(&spec(1));

        let mut force = force_liquidation_cmd(1, 1, CommandResultCode::ValidForMatchingEngine);
        let rc = router.process_order(&mut force);
        assert_eq!(rc, CommandResultCode::Success);
        assert_eq!(force.result_code, Some(CommandResultCode::Success));

        let mut req = OrderCommand {
            command: OrderCommandType::OrderBookRequest,
            symbol: 1,
            size: 10,
            ..Default::default()
        };
        router.process_order(&mut req);
        let md = req.market_data.expect("L2 market data should have been filled");
        assert_eq!(md.ask_prices, vec![100]);
        assert_eq!(md.ask_volumes, vec![10]);
    }

    #[test]
    fn force_liquidation_not_valid_for_matching_engine_is_not_placed_and_result_code_preserved() {
        let mut router = MatchingEngineRouter::new();
        router.add_symbol(&spec(1));

        let mut force = force_liquidation_cmd(1, 1, CommandResultCode::UnsupportedSymbolType);
        let rc = router.process_order(&mut force);
        assert_eq!(rc, CommandResultCode::UnsupportedSymbolType);
        assert_eq!(force.result_code, Some(CommandResultCode::UnsupportedSymbolType));

        let mut req = OrderCommand {
            command: OrderCommandType::OrderBookRequest,
            symbol: 1,
            size: 10,
            ..Default::default()
        };
        router.process_order(&mut req);
        let md = req.market_data.expect("L2 market data should have been filled");
        assert!(md.ask_prices.is_empty());
    }
}
