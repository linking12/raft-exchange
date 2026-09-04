//! ME（Matching Engine）路由：按 symbol 分派到 `OrderBookNaiveImpl`，对应 Java `MatchingEngineRouter` +
//! `IOrderBook.processCommand`（`:182-227`）；R1 门：只有 `ValidForMatchingEngine` 才真正撮合，否则保留 R1 结果不覆盖。
use std::collections::BTreeMap;

use crate::core::common::cmd::order_command::OrderCommand;
use crate::core::common::cmd::command_result_code::CommandResultCode;
use crate::core::common::cmd::order_command_type::OrderCommandType;
use crate::core::common::core_symbol_specification::CoreSymbolSpecification;
use crate::core::orderbook::i_order_book::IOrderBook;
use crate::core::orderbook::order_book_naive_impl::OrderBookNaiveImpl;

/// 对应 Java `MatchingEngineRouter`（现货子集，只保留 symbol→book 路由 + 撮合分派）。
#[derive(Default, serde::Serialize, serde::Deserialize)]
pub struct MatchingEngineRouter {
    books: BTreeMap<i32, OrderBookNaiveImpl>,
}

impl MatchingEngineRouter {
    pub fn new() -> Self {
        MatchingEngineRouter { books: BTreeMap::new() }
    }

    /// 对应 Java `MatchingEngineRouter.addSymbol`（`:276-289`，现货子集，重复 add 幂等忽略）。
    pub fn add_symbol(&mut self, spec: &CoreSymbolSpecification) {
        // 幂等：已存在则保留原簿；用 with_symbol_spec 注入真实 spec 供 move_order 现货 BID 风控用。
        self.books.entry(spec.symbol_id).or_insert_with(|| OrderBookNaiveImpl::with_symbol_spec(spec.clone()));
    }

    /// 对应 Java `MatchingEngineRouter.processMatchingCommand`（`:291-312`）+ `IOrderBook.processCommand`
    /// （`:176-227`）；非交易命令与借贷生命周期命令（两强平码除外）原样短路保留 R1 结果（Java allowlist `:204-212`）。
    pub fn process_order(&mut self, cmd: &mut OrderCommand) -> CommandResultCode {
        if cmd.command.is_non_trading()
            || (cmd.command.is_loan()
                && cmd.command != OrderCommandType::LoanForceLiquidate
                && cmd.command != OrderCommandType::LoanCrossForceLiquidate)
        {
            return cmd.result_code.unwrap_or(CommandResultCode::MatchingUnsupportedCommand);
        }

        let Some(book) = self.books.get_mut(&cmd.symbol) else {
            // 对应 Java `orderBook == null` → MATCHING_INVALID_ORDER_BOOK_ID（-3005）。
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
                // PlaceOrder/ClosePosition/两强平码/ForceLiquidation 共用 new_order 分支（Java allowlist `:206-214`）。
                if cmd.result_code == Some(CommandResultCode::ValidForMatchingEngine) {
                    // new_order 内部已写 cmd.result_code，此处透传其返回值。
                    book.new_order(cmd)
                } else {
                    // 对应 Java `return cmd.resultCode; // no change`：R1 拒绝的命令不撮合、不覆盖。
                    return cmd.result_code.unwrap_or(CommandResultCode::MatchingUnsupportedCommand);
                }
            }
            OrderCommandType::OrderBookRequest => {
                // 对应 Java `(int) cmd.size` 窄化截断，`size >= 0 ? size : Integer.MAX_VALUE`。
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
    fn place_order_valid_for_matching_engine_routes_and_rests_on_book() {
        let mut router = MatchingEngineRouter::new();
        router.add_symbol(&spec(1));

        let mut place = place_cmd(1, 1, CommandResultCode::ValidForMatchingEngine);
        let rc = router.process_order(&mut place);
        assert_eq!(rc, CommandResultCode::Success);
        assert_eq!(place.result_code, Some(CommandResultCode::Success));

        // 通过 OrderBookRequest 回读 L2，验证挂单确实落在该 symbol 的 book 上。
        let mut req = OrderCommand {
            command: OrderCommandType::OrderBookRequest,
            symbol: 1,
            size: 10,
            ..Default::default()
        };
        let rc2 = router.process_order(&mut req);
        assert_eq!(rc2, CommandResultCode::Success);
        let md = req.market_data.expect("应回填 L2 数据");
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
        let md = req.market_data.expect("应回填 L2 数据");
        assert_eq!(md.ask_prices, vec![100]);
        assert_eq!(md.ask_volumes, vec![10]);
    }

    #[test]
    fn unknown_symbol_reports_error_and_does_not_panic() {
        let mut router = MatchingEngineRouter::new();
        // 未 add_symbol(99)。
        let mut cmd = place_cmd(1, 99, CommandResultCode::ValidForMatchingEngine);
        let rc = router.process_order(&mut cmd);
        assert_eq!(rc, CommandResultCode::MatchingInvalidOrderBookId);
        assert_eq!(cmd.result_code, Some(CommandResultCode::MatchingInvalidOrderBookId));
    }

    #[test]
    fn place_order_not_valid_for_matching_engine_is_not_placed() {
        let mut router = MatchingEngineRouter::new();
        router.add_symbol(&spec(1));

        // R1 拒绝（RiskNsf）：ME 不应撮合/挂簿。
        let mut place = place_cmd(1, 1, CommandResultCode::RiskNsf);
        let rc = router.process_order(&mut place);
        assert_eq!(rc, CommandResultCode::RiskNsf);
        assert_eq!(place.result_code, Some(CommandResultCode::RiskNsf));

        // 簿仍为空：OrderBookRequest 应该没有任何挂单。
        let mut req = OrderCommand {
            command: OrderCommandType::OrderBookRequest,
            symbol: 1,
            size: 10,
            ..Default::default()
        };
        router.process_order(&mut req);
        let md = req.market_data.expect("应回填 L2 数据");
        assert!(md.bid_prices.is_empty());
        assert!(md.ask_prices.is_empty());
    }

    #[test]
    fn add_symbol_is_idempotent_for_duplicate_registration() {
        let mut router = MatchingEngineRouter::new();
        router.add_symbol(&spec(1));
        // 先挂一笔单，再重复 add_symbol：不应清空已有 book。
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
        let md = req.market_data.expect("应回填 L2 数据");
        assert_eq!(md.bid_volumes, vec![10]);
    }

    // ClosePosition 与 PlaceOrder 共用 newOrder 分支的回归测试（对应 Java `IOrderBook.processCommand:191-199`）。

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
        let md = req.market_data.expect("应回填 L2 数据");
        assert_eq!(md.ask_prices, vec![100]);
        assert_eq!(md.ask_volumes, vec![10]);
    }

    #[test]
    fn close_position_not_valid_for_matching_engine_is_not_placed_and_result_code_preserved() {
        let mut router = MatchingEngineRouter::new();
        router.add_symbol(&spec(1));

        // R1 拒绝（如 UnsupportedSymbolType）：ME 不应撮合/挂簿，也不应覆盖成 MatchingUnsupportedCommand。
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
        let md = req.market_data.expect("应回填 L2 数据");
        assert!(md.ask_prices.is_empty());
    }

    // ForceLiquidation 与 PlaceOrder/ClosePosition 共用 newOrder 分支的回归测试（Java `MatchingEngineRouter.java:206-214`）。

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
        let md = req.market_data.expect("应回填 L2 数据");
        assert_eq!(md.ask_prices, vec![100]);
        assert_eq!(md.ask_volumes, vec![10]);
    }

    #[test]
    fn force_liquidation_not_valid_for_matching_engine_is_not_placed_and_result_code_preserved() {
        let mut router = MatchingEngineRouter::new();
        router.add_symbol(&spec(1));

        // R1 拒绝：ME 不应撮合/挂簿，也不应把结果覆盖成 MatchingUnsupportedCommand。
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
        let md = req.market_data.expect("应回填 L2 数据");
        assert!(md.ask_prices.is_empty());
    }
}
