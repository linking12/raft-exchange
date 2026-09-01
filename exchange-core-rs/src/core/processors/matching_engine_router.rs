//! ME（Matching Engine）路由：按 symbol 把命令分派到对应的 `OrderBookNaiveImpl`。
//! 对应 Java: `exchange.core2.core.processors.MatchingEngineRouter`（329 行，`processOrder`/`processMatchingCommand`）
//! + `orderbook/IOrderBook.processCommand`（静态分派，182–227 行）。
//!
//! # 范围裁剪（本移植阶段）
//! Java `processOrder` 还处理 `IF_TAKEOVER`/`AUTO_DELEVERAGING`/`SETTLE_FUNDINGFEES`/
//! `RESET_FEE`/`INTERNAL_TRANSFER`/`REPRICE_LOAN_RATES`/`BINARY_DATA_*`/`RESET`/`NOP`/
//! `PERSIST_STATE_MATCHING`/`RECOVER_STATE_MATCHING` 等非撮合命令，以及跨分片
//! `symbolForThisHandler` 分片过滤——本期单 shard、现货子集，这些命令未移植（`OrderCommandType`
//! 枚举里也没有对应变体），故 `process_order` 只承担 Java `processMatchingCommand` +
//! `IOrderBook.processCommand` 这一段：查 symbol→book、按 `cmd.command` 分派、写 `cmd.result_code`。
//!
//! # R1 门（对照 Java `IOrderBook.processCommand` 192–199 行）
//! `PlaceOrder` 只有在 `cmd.result_code == Some(ValidForMatchingEngine)`（R1 放行）时才真正
//! 调 `book.new_order`；否则原样保留 R1 写下的 `result_code`（Java: `return cmd.resultCode; // no change`），
//! ME 绝不覆盖 R1 的拒绝结果。
use std::collections::BTreeMap;

use crate::core::common::cmd::order_command::OrderCommand;
use crate::core::common::cmd::command_result_code::CommandResultCode;
use crate::core::common::cmd::order_command_type::OrderCommandType;
use crate::core::common::core_symbol_specification::CoreSymbolSpecification;
use crate::core::orderbook::i_order_book::IOrderBook;
use crate::core::orderbook::order_book_naive_impl::OrderBookNaiveImpl;

/// 对应 Java `MatchingEngineRouter`（现货子集：只保留 symbol→book 路由 + 撮合分派，
/// 序列化/对象池/分片/二进制命令等本期不移植）。
#[derive(Default)]
pub struct MatchingEngineRouter {
    books: BTreeMap<i32, OrderBookNaiveImpl>,
}

impl MatchingEngineRouter {
    pub fn new() -> Self {
        MatchingEngineRouter { books: BTreeMap::new() }
    }

    /// 对应 Java `MatchingEngineRouter.addSymbol`（276–289 行，现货子集：略去
    /// `cfgMarginTradingEnabled` 校验与重复添加告警——重复 add 直接忽略，保持幂等）。
    pub fn add_symbol(&mut self, spec: &CoreSymbolSpecification) {
        self.books.entry(spec.symbol_id).or_default();
    }

    /// 对应 Java `MatchingEngineRouter.processMatchingCommand`（291–312 行）
    /// + `IOrderBook.processCommand`（176–227 行）。写 `cmd.result_code` 并返回同一个值。
    ///
    /// # 非交易命令 no-op 守卫（管线结构对齐 Java 之后新增）
    /// `ExchangeCore::process_command` 重构后，所有命令都依次流过 R1→ME→R2（对齐 Java 全命令
    /// 过 disruptor 三阶段的结构），非交易命令（`AddUser`/`BalanceAdjustment` 等）也会流经这里。
    /// 非交易命令的 `cmd.symbol` 语义不是 symbol id（例如 `BalanceAdjustment` 是币种 id），若不
    /// 提前拦截，下面 `self.books.get_mut(&cmd.symbol)` 会用错误的键去查 book——大概率查不到，
    /// 命中 `MATCHING_INVALID_ORDER_BOOK_ID` 分支，**覆盖掉 R1 已经写好的正确 `result_code`**
    /// （即使凑巧命中某个 symbol id 相同的 book，也会做一次无意义甚至有害的撮合尝试）。因此在
    /// 最前面显式短路：不查 book、不碰 `cmd.result_code`，原样保留 R1（`preProcessCommand`）写
    /// 下的结果——对应 Java `MatchingEngineRouter` 里非订单类命令根本不会被路由到这里的效果。
    pub fn process_order(&mut self, cmd: &mut OrderCommand) -> CommandResultCode {
        if cmd.command.is_non_trading() {
            return cmd.result_code.unwrap_or(CommandResultCode::MatchingUnsupportedCommand);
        }

        let Some(book) = self.books.get_mut(&cmd.symbol) else {
            // 对应 Java: `orderBook == null` → `MATCHING_INVALID_ORDER_BOOK_ID`（-3005）。
            let rc = CommandResultCode::MatchingInvalidOrderBookId;
            cmd.result_code = Some(rc);
            return rc;
        };

        let rc = match cmd.command {
            OrderCommandType::MoveOrder => book.move_order(cmd),
            OrderCommandType::CancelOrder => book.cancel_order(cmd),
            OrderCommandType::ReduceOrder => book.reduce_order(cmd),
            OrderCommandType::PlaceOrder => {
                if cmd.result_code == Some(CommandResultCode::ValidForMatchingEngine) {
                    // new_order 内部已经写 cmd.result_code（P1 收尾修复），此处直接透传其返回值。
                    book.new_order(cmd)
                } else {
                    // 对应 Java: `return cmd.resultCode; // no change` —— R1 拒绝的命令，ME 不撮合、
                    // 不覆盖 result_code。
                    return cmd.result_code.unwrap_or(CommandResultCode::MatchingUnsupportedCommand);
                }
            }
            OrderCommandType::OrderBookRequest => {
                // 对应 Java: `int size = (int) cmd.size; ... size >= 0 ? size : Integer.MAX_VALUE`
                // （`(int) cmd.size` 是窄化截断，`as i32` 语义一致）。
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
}
