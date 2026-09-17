//! 对应 Java `MatchingEngineRouter` + `IOrderBook.processCommand`：按 symbol 分派到 `OrderBookDirectImpl`（撮合 O(log N)）。
//! R1 门：只有 `ValidForMatchingEngine` 才真正撮合，否则保留 R1 结果不覆盖。
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

/// 对应 Java `MatchingEngineRouter`（现货子集，只保留 symbol→book 路由 + 撮合分派）。
#[derive(Default)]
pub struct MatchingEngineRouter {
    pub(crate) books: BTreeMap<i32, OrderBookDirectImpl>,
    /// ME 模块的 `binaryCommandsProcessor` 快照分片(仅字节兼容,Rust 恒空,忠实透传 Java 中途快照)。见 [`BinaryCommandsProcessor`]。
    pub(crate) binary_cmd: BinaryCommandsProcessor,
}

impl MatchingEngineRouter {
    // ===== 构造/配置 =====

    pub fn new() -> Self {
        MatchingEngineRouter { books: BTreeMap::new(), binary_cmd: BinaryCommandsProcessor::new() }
    }

    // ===== 核心行为 =====

    /// 对应 Java `MatchingEngineRouter.processMatchingCommand` + `IOrderBook.processCommand`；非交易命令与借贷生命周期命令（两强平码除外）原样短路保留 R1 结果。
    pub fn process_order(&mut self, cmd: &mut OrderCommand) -> CommandResultCode {
        if cmd.command.is_non_trading()
            || (cmd.command.is_loan()
                && cmd.command != OrderCommandType::LoanForceLiquidate
                && cmd.command != OrderCommandType::LoanCrossForceLiquidate)
            // SettleFundingfees/IfTakeover/AutoDeleveraging 的 collect+merge 已折进 R1、结算在 R2；
            // LiquidationScan 在 R1 做 check_positions 扫描（symbol=-1，无订单簿）。这几者 ME 均为 no-op，
            // 须保留 R1 结果码（对齐 Java ME：LIQUIDATION_SCAN 不在撮合分支里、fall-through 不改 resultCode），
            // 否则 symbol=-1 会落到订单簿查找 → MatchingInvalidOrderBookId 覆盖 R1 的 Success。
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

    /// 对应 Java `MatchingEngineRouter.addSymbol`（现货子集，重复 add 幂等忽略）。
    pub fn add_symbol(&mut self, spec: &CoreSymbolSpecification) {
        // 幂等：已存在则保留原簿；用 with_symbol_spec 注入真实 spec 供 move_order 现货 BID 风控用。
        self.books.entry(spec.symbol_id).or_insert_with(|| OrderBookDirectImpl::with_symbol_spec(spec.clone()));
    }

    /// 对应 Java `MatchingEngineRouter.reset()`：清空全部撮合簿（RESET 命令用）。
    pub fn reset(&mut self) {
        self.books.clear();
    }

    // ===== 查询/访问器 =====

    /// 按 uid 反查全部簿的挂单，返回 `(symbol, Order)`（按 symbol、再 order_id 升序，确定性）。报表冷路径用，按需扫簿。
    pub fn user_orders(&self, uid: i64) -> Vec<(i32, Order)> {
        let mut out = Vec::new();
        for (&sym, book) in &self.books {
            for o in book.find_user_orders(uid) {
                out.push((sym, o));
            }
        }
        out
    }

    /// 撮合簿子状态哈希（对应 Java StateHashReport `MATCHING_ORDER_BOOKS`）：按 symbol 升序折入各簿 `state_hash`。
    pub fn order_books_state_hash(&self) -> i64 {
        let mut h: i64 = 17;
        for (&sym, book) in &self.books {
            h = h.wrapping_mul(31).wrapping_add(sym as i64);
            h = h.wrapping_mul(31).wrapping_add(book.state_hash() as i64);
        }
        h
    }
}


// ---- Chronicle 快照读写(对应 Java MatchingEngineRouter.writeMarshallable)----
use crate::core::snapshot::chronicle_reader::{ChronicleError, ChronicleReader};
use crate::core::snapshot::chronicle_writer::ChronicleWriter;
use crate::core::snapshot::marshalling::ChronicleMarshallable;

impl ChronicleMarshallable for MatchingEngineRouter {
    /// Java `MatchingEngineRouter.writeMarshallable`:shardId(int)+shardMask(long)+binaryCommandsProcessor+orderBooks。
    /// 单片塌缩:shardId/shardMask 写常量 0、读丢弃;binaryCommandsProcessor 忠实透传(Rust 恒空)。
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

/// 按订单簿首字节 `implType` 分发读取,统一承载为 `OrderBookDirectImpl`(Rust 撮合热路径恒 Direct):
/// - DIRECT(2):直接读 body;
/// - NAIVE(0):读 body 为 `OrderBookNaiveImpl`,再抽出挂单转成 Direct(混合集群里 Java 若用 Naive 撮合簿也能加载)。
///
/// 代价:读入的 Naive 订单簿在 Rust 内部及后续写出都会变成 Direct(implType=2)。因 implType 自描述,
/// Java 侧读回 Direct 亦可正常建簿;状态哈希是撮合实现无关的逻辑序,故不影响一致性。
fn read_order_book_dispatch(r: &mut ChronicleReader) -> Result<OrderBookDirectImpl, ChronicleError> {
    let impl_type = r.read_u8()?;
    match impl_type {
        2 => OrderBookDirectImpl::chronicle_read_body(r),
        0 => {
            let naive = OrderBookNaiveImpl::chronicle_read_body(r)?;
            let spec = naive.chronicle_symbol_spec().expect("naive 订单簿缺 symbol_spec");
            let (asks, bids) = naive.chronicle_orders();
            Ok(OrderBookDirectImpl::restore_chronicle(spec, asks, bids))
        }
        other => panic!("未知 OrderBookImplType code {other}(仅支持 NAIVE=0 / DIRECT=2)"),
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
        // Java 若用 NAIVE 撮合簿打快照,Rust 读到 implType=0 应能加载(转成 Direct 承载),挂单无损。
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
        // 手工拼 ME payload:shardId/shardMask/binaryCmd(空)/orderBooks(1 项,值=NAIVE 书 implType=0)。
        let mut w = ChronicleWriter::new();
        w.write_i32(0);
        w.write_i64(0);
        w.write_i32(0);
        w.write_i32(1); // orderBooks map size
        w.write_i32(7); // symbol key
        naive.chronicle_write(&mut w);
        let bytes = w.into_bytes();
        let router = MatchingEngineRouter::chronicle_read(&mut ChronicleReader::new(&bytes)).unwrap();
        assert_eq!(router.books.len(), 1);
        let direct = router.books.get(&7).expect("symbol 7 book");
        // 转 Direct 后 L2 与原 Naive 完全一致(价位/量/档序无损)。
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

    // ClosePosition 与 PlaceOrder 共用 newOrder 分支的回归测试。

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

    // ForceLiquidation 与 PlaceOrder/ClosePosition 共用 newOrder 分支的回归测试。

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
