// 对应 Java `ITradeEventsHandler`。Java 用对象池(ArrayDeque borrow/recycle)避免 GC 压力；
// Rust 无此需要，各 Report 直接按值构造/移动，调用方拿到 owned 值自然析构。
use crate::core::common::cmd::order_command::OrderCommand;
use crate::core::common::cmd::order_command_type::OrderCommandType;
use crate::core::common::core_symbol_specification::CoreSymbolSpecification;
use crate::core::common::matcher_trade_event::MatcherTradeEvent;
use crate::core::common::order_action::OrderAction;
use crate::core::common::order_type::OrderType;
use crate::core::common::position_mode::PositionMode;
use crate::core::common::symbol_type::SymbolType;
use crate::core::utils::core_arithmetic_utils::{calculate_amount_bid, calculate_maker_fee, calculate_taker_fee};

/// 对应 Java `ITradeEventsHandler.ExecType`。
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum ExecType {
    New,
    Trade,
    Reduce,
    Cancel,
    Reject,
}

/// 对应 Java `ITradeEventsHandler.OrderStatus`。
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum OrderStatus {
    New,
    PartiallyFilled,
    Filled,
    Canceled,
    Rejected,
}

/// 对应 Java `ITradeEventsHandler.OrderBookRecord`：L2 单档（价位/量/挂单数）。
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct OrderBookRecord {
    pub price: i64,
    pub volume: i64,
    pub orders: i32,
}

/// 对应 Java `ITradeEventsHandler.OrderBook`：附带 scale 自描述的 L2 快照，供下游 PB 直接编码，
/// 不需要消费端另维护 symbol→scale 字典。
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct OrderBook {
    pub symbol: i32,
    pub asks: Vec<OrderBookRecord>,
    pub bids: Vec<OrderBookRecord>,
    pub timestamp: i64,
    pub base_scale_k: i64,
    pub quote_scale_k: i64,
}

/// 对应 Java `ITradeEventsHandler`：非延迟敏感场景的下游事件回调接口，由 `SimpleEventsProcessor` 驱动，
/// 单线程按 execution report → fund events → market data 的固定顺序调用（见 simple_events_processor.rs）。
pub trait TradeEventsHandler {
    fn order_book(&mut self, order_book: OrderBook);
    fn spot_execution_report(&mut self, report: SpotExecutionReport);
    fn futures_execution_report(&mut self, report: FuturesExecutionReport);
}

fn is_budget(order_type: OrderType) -> bool {
    matches!(order_type, OrderType::FokBudget | OrderType::IocBudget)
}

// 对应 Java `SpotExecutionReport.reduceOrder`/`FuturesExecutionReport.reduceOrder` 共用的
// exec_type/order_status 判定逻辑：REDUCE_ORDER 走部分成交/撤销三态，CANCEL_ORDER 恒 Cancel/Canceled。
fn reduce_exec_status(cmd: &OrderCommand, event: &MatcherTradeEvent) -> (ExecType, OrderStatus) {
    if cmd.command == OrderCommandType::ReduceOrder {
        let status = if event.filled == 0 {
            OrderStatus::New
        } else if event.active_order_completed {
            OrderStatus::Canceled
        } else {
            OrderStatus::PartiallyFilled
        };
        (ExecType::Reduce, status)
    } else {
        (ExecType::Cancel, OrderStatus::Canceled)
    }
}

/// 对应 Java `ITradeEventsHandler.SpotExecutionReport`：现货成交/委托生命周期回报。
/// 字段语义对齐 Java 同名字段（`price`/`quote_order_qty` 二选一表达 budget 单预算）。
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct SpotExecutionReport {
    pub execution_id: i64,
    pub execution_type: ExecType,
    pub order_status: OrderStatus,
    pub symbol: i32,
    pub base_scale_k: i64,
    pub quote_scale_k: i64,
    pub account_id: i64,
    pub cl_ord_id: i32,
    pub order_id: i64,
    pub order_type: OrderType,
    pub side: OrderAction,
    pub qty: i64,
    pub price: i64,
    pub quote_order_qty: i64,
    pub order_creation_time: i64,
    pub trade_id: i64,
    pub last_qty: i64,
    pub mark_price: i64,
    pub last_quote_qty: i64,
    pub cumulative_qty: i64,
    pub cumulative_quote_qty: i64,
    pub commission: i64,
    pub commission_asset: i32,
    pub is_maker: bool,
    pub working_indicator: bool,
}

impl SpotExecutionReport {
    /// 对应 Java `SpotExecutionReport.placeOrder`：NEW 回报，`working_indicator` 仅 GTC 单为 true。
    pub fn place_order(cmd: &OrderCommand, seq: i64, spec: &CoreSymbolSpecification) -> Self {
        let mut r = Self::base(cmd, spec, ExecType::New, OrderStatus::New, ExecutionIdGenerator::build_new_exec_id(seq));
        r.working_indicator = cmd.order_type == Some(OrderType::Gtc);
        r
    }

    /// 对应 Java `SpotExecutionReport.rejectOrder`：下单被拒回报。
    pub fn reject_order(cmd: &OrderCommand, seq: i64, spec: &CoreSymbolSpecification) -> Self {
        Self::base(cmd, spec, ExecType::Reject, OrderStatus::Rejected, ExecutionIdGenerator::build_reject_exec_id(seq))
    }

    /// 对应 Java `SpotExecutionReport.reduceOrder`：主动撤单/减量回报。
    pub fn reduce_order(cmd: &OrderCommand, seq: i64, spec: &CoreSymbolSpecification, event: &MatcherTradeEvent) -> Self {
        let (execution_type, order_status) = reduce_exec_status(cmd, event);
        let mut r = Self::base(cmd, spec, execution_type, order_status, ExecutionIdGenerator::build_reduce_exec_id(seq));
        r.cumulative_qty = event.filled;
        r.cumulative_quote_qty = event.filled_notional;
        r
    }

    /// 对应 Java `SpotExecutionReport.tradeTaker`：一笔成交中 taker（当前 cmd 的下单方）一侧的回报。
    pub fn trade_taker(cmd: &OrderCommand, seq: i64, spec: &CoreSymbolSpecification, ev: &MatcherTradeEvent, trade_index: i32) -> Self {
        let status = if ev.active_order_completed { OrderStatus::Filled } else { OrderStatus::PartiallyFilled };
        let mut r = Self::base(cmd, spec, ExecType::Trade, status, ExecutionIdGenerator::build_trade_exec_id(seq, trade_index, false));
        r.trade_id = ExecutionIdGenerator::build_trade_id(seq, trade_index);
        r.last_qty = ev.size;
        r.mark_price = ev.price;
        r.last_quote_qty = calculate_amount_bid(ev.size, ev.price);
        r.cumulative_qty = ev.filled;
        r.cumulative_quote_qty = ev.filled_notional;
        r.commission = calculate_taker_fee(ev.size, ev.price, spec.taker_fee, spec.fee_scale_k);
        r.is_maker = false;
        r.working_indicator = cmd.order_type == Some(OrderType::Gtc) && !ev.active_order_completed;
        r
    }

    /// 对应 Java `SpotExecutionReport.tradeMaker`：同一笔成交中 maker（被吃单方，来自 `ev.matched_order_*`）一侧的回报。
    pub fn trade_maker(cmd: &OrderCommand, seq: i64, spec: &CoreSymbolSpecification, ev: &MatcherTradeEvent, trade_index: i32) -> Self {
        let budget = is_budget(ev.matched_order_type);
        let status = if ev.maker_order_completed { OrderStatus::Filled } else { OrderStatus::PartiallyFilled };
        SpotExecutionReport {
            execution_id: ExecutionIdGenerator::build_trade_exec_id(seq, trade_index, true),
            execution_type: ExecType::Trade,
            order_status: status,
            symbol: cmd.symbol,
            base_scale_k: spec.base_scale_k,
            quote_scale_k: spec.quote_scale_k,
            account_id: ev.matched_order_uid,
            cl_ord_id: ev.matched_user_cookie,
            order_id: ev.maker_order_id,
            order_type: ev.matched_order_type,
            side: cmd.action.unwrap_or(OrderAction::Ask).opposite(),
            qty: ev.matched_order_size,
            price: if budget { 0 } else { ev.matched_order_price },
            quote_order_qty: if budget { ev.matched_order_price } else { 0 },
            order_creation_time: ev.matched_order_timestamp,
            trade_id: ExecutionIdGenerator::build_trade_id(seq, trade_index),
            last_qty: ev.size,
            mark_price: ev.price,
            last_quote_qty: calculate_amount_bid(ev.size, ev.price),
            cumulative_qty: ev.matched_order_filled,
            cumulative_quote_qty: ev.matched_order_filled_notional,
            commission: calculate_maker_fee(ev.size, ev.price, spec.maker_fee, spec.fee_scale_k),
            commission_asset: spec.quote_currency,
            is_maker: true,
            working_indicator: ev.matched_order_type == OrderType::Gtc && !ev.maker_order_completed,
        }
    }

    // 对应 Java 四个工厂方法(placeOrder/rejectOrder/reduceOrder/tradeTaker)公共的字段初始化部分。
    fn base(cmd: &OrderCommand, spec: &CoreSymbolSpecification, execution_type: ExecType, order_status: OrderStatus, execution_id: i64) -> Self {
        let order_type = cmd.order_type.unwrap_or(OrderType::Gtc);
        let budget = is_budget(order_type);
        SpotExecutionReport {
            execution_id,
            execution_type,
            order_status,
            symbol: cmd.symbol,
            base_scale_k: spec.base_scale_k,
            quote_scale_k: spec.quote_scale_k,
            account_id: cmd.uid,
            cl_ord_id: cmd.user_cookie,
            order_id: cmd.order_id,
            order_type,
            side: cmd.action.unwrap_or(OrderAction::Ask),
            qty: cmd.size,
            price: if budget { 0 } else { cmd.price },
            quote_order_qty: if budget { cmd.price } else { 0 },
            order_creation_time: cmd.timestamp,
            trade_id: -1,
            last_qty: 0,
            mark_price: 0,
            last_quote_qty: 0,
            cumulative_qty: 0,
            cumulative_quote_qty: 0,
            commission: 0,
            commission_asset: spec.quote_currency,
            is_maker: false,
            working_indicator: false,
        }
    }
}

/// 对应 Java `ITradeEventsHandler.FuturesExecutionReport`：期货成交/委托生命周期回报，比现货多带
/// `contract_type`（永续/交割）与 `position_side`（单向/双向持仓模式）。
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct FuturesExecutionReport {
    pub uni_id: i64,
    pub execution_type: ExecType,
    pub order_status: OrderStatus,
    pub symbol_id: i32,
    pub order_qty_scale: i64,
    pub price_scale: i64,
    pub user_id: i64,
    pub cl_order_id: i32,
    pub order_id: i64,
    pub order_type: OrderType,
    pub side: OrderAction,
    pub counterparty_id: i64,
    pub price: i64,
    pub order_qty: i64,
    pub create_time: i64,
    pub exec_id: i64,
    pub contract_type: SymbolType,
    pub position_side: PositionMode,
    pub last_qty: i64,
    pub last_px: i64,
    pub cum_qty: i64,
    pub cum_quote_qty: i64,
    pub avg_px: i64,
    pub fee: i64,
    pub fee_asset_id: i32,
    pub is_maker: bool,
}

impl FuturesExecutionReport {
    /// 对应 Java `FuturesExecutionReport.placeOrder`。
    pub fn place_order(cmd: &OrderCommand, seq: i64, spec: &CoreSymbolSpecification, position_side: PositionMode) -> Self {
        Self::base(cmd, spec, position_side, ExecType::New, OrderStatus::New, ExecutionIdGenerator::build_new_exec_id(seq))
    }

    /// 对应 Java `FuturesExecutionReport.rejectOrder`。
    pub fn reject_order(cmd: &OrderCommand, seq: i64, spec: &CoreSymbolSpecification, position_side: PositionMode) -> Self {
        Self::base(cmd, spec, position_side, ExecType::Reject, OrderStatus::Rejected, ExecutionIdGenerator::build_reject_exec_id(seq))
    }

    /// 对应 Java `FuturesExecutionReport.reduceOrder`。
    pub fn reduce_order(cmd: &OrderCommand, seq: i64, spec: &CoreSymbolSpecification, position_side: PositionMode, event: &MatcherTradeEvent) -> Self {
        let (execution_type, order_status) = reduce_exec_status(cmd, event);
        Self::base(cmd, spec, position_side, execution_type, order_status, ExecutionIdGenerator::build_reduce_exec_id(seq))
    }

    /// 对应 Java `FuturesExecutionReport.tradeTaker`：taker 一侧回报；手续费按 `ev.size` 全量以 taker 费率
    /// 计（对齐 RiskEngine 在 closed_size/size_to_open 两段分别扣费后的合计，非引擎唯一真理源的重复计算）。
    pub fn trade_taker(cmd: &OrderCommand, seq: i64, spec: &CoreSymbolSpecification, position_side: PositionMode, ev: &MatcherTradeEvent, trade_index: i32) -> Self {
        let status = if ev.active_order_completed { OrderStatus::Filled } else { OrderStatus::PartiallyFilled };
        let mut r = Self::base(cmd, spec, position_side, ExecType::Trade, status, ExecutionIdGenerator::build_trade_exec_id(seq, trade_index, false));
        r.counterparty_id = ev.matched_order_uid;
        r.exec_id = ExecutionIdGenerator::build_trade_id(seq, trade_index);
        r.last_qty = ev.size;
        r.last_px = ev.price;
        r.cum_qty = ev.filled;
        r.cum_quote_qty = ev.filled_notional;
        r.avg_px = if ev.filled == 0 { 0 } else { ev.filled_notional / ev.filled };
        r.fee = calculate_taker_fee(ev.size, ev.price, spec.taker_fee, spec.fee_scale_k);
        r.is_maker = false;
        r
    }

    /// 对应 Java `FuturesExecutionReport.tradeMaker`：maker 一侧回报，`maker_position_side` 取自被吃单方
    /// 自己的持仓模式（不同于 taker，见 simple_events_processor 中按 `ev.matched_order_uid` 单独查询）。
    pub fn trade_maker(cmd: &OrderCommand, seq: i64, spec: &CoreSymbolSpecification, maker_position_side: PositionMode, ev: &MatcherTradeEvent, trade_index: i32) -> Self {
        let budget = is_budget(ev.matched_order_type);
        let status = if ev.maker_order_completed { OrderStatus::Filled } else { OrderStatus::PartiallyFilled };
        FuturesExecutionReport {
            uni_id: ExecutionIdGenerator::build_trade_exec_id(seq, trade_index, true),
            execution_type: ExecType::Trade,
            order_status: status,
            symbol_id: cmd.symbol,
            order_qty_scale: spec.base_scale_k,
            price_scale: spec.quote_scale_k,
            user_id: ev.matched_order_uid,
            cl_order_id: ev.matched_user_cookie,
            order_id: ev.maker_order_id,
            order_type: ev.matched_order_type,
            side: cmd.action.unwrap_or(OrderAction::Ask).opposite(),
            counterparty_id: cmd.uid,
            price: if budget { 0 } else { ev.matched_order_price },
            order_qty: if budget { ev.matched_order_price } else { ev.matched_order_size },
            create_time: ev.matched_order_timestamp,
            exec_id: ExecutionIdGenerator::build_trade_id(seq, trade_index),
            contract_type: spec.symbol_type,
            position_side: maker_position_side,
            last_qty: ev.size,
            last_px: ev.price,
            cum_qty: ev.matched_order_filled,
            cum_quote_qty: ev.matched_order_filled_notional,
            avg_px: if ev.matched_order_filled == 0 { 0 } else { ev.matched_order_filled_notional / ev.matched_order_filled },
            fee: calculate_maker_fee(ev.size, ev.price, spec.maker_fee, spec.fee_scale_k),
            fee_asset_id: spec.quote_currency,
            is_maker: true,
        }
    }

    // 对应 Java 四个工厂方法公共的字段初始化部分。
    #[allow(clippy::too_many_arguments)]
    fn base(cmd: &OrderCommand, spec: &CoreSymbolSpecification, position_side: PositionMode, execution_type: ExecType, order_status: OrderStatus, uni_id: i64) -> Self {
        let order_type = cmd.order_type.unwrap_or(OrderType::Gtc);
        let budget = is_budget(order_type);
        FuturesExecutionReport {
            uni_id,
            execution_type,
            order_status,
            symbol_id: cmd.symbol,
            order_qty_scale: spec.base_scale_k,
            price_scale: spec.quote_scale_k,
            user_id: cmd.uid,
            cl_order_id: cmd.user_cookie,
            order_id: cmd.order_id,
            order_type,
            side: cmd.action.unwrap_or(OrderAction::Ask),
            counterparty_id: -1,
            price: if budget { 0 } else { cmd.price },
            order_qty: if budget { cmd.price } else { cmd.size },
            create_time: cmd.timestamp,
            exec_id: -1,
            contract_type: spec.symbol_type,
            position_side,
            last_qty: 0,
            last_px: 0,
            cum_qty: 0,
            cum_quote_qty: 0,
            avg_px: 0,
            fee: 0,
            fee_asset_id: spec.quote_currency,
            is_maker: false,
        }
    }
}

/// 对应 Java `ITradeEventsHandler.ExecutionIdGenerator`：把全局命令序号 `seq` 与命令内子序号编码进一个
/// i64（低 12 位留给子序号，见 `SHIFT_BITS`）；trade 类子序号从 `IDX_TRADE_BASE` 起按
/// `(trade_index << 1) | is_maker` 排布，保证同一笔成交 taker/maker 两条相邻。
pub struct ExecutionIdGenerator;

impl ExecutionIdGenerator {
    const SHIFT_BITS: i64 = 12;
    const IDX_NEW: i64 = 0;
    const IDX_REJECT: i64 = 1;
    const IDX_CANCEL: i64 = 2;
    const IDX_TRADE_BASE: i64 = 3;

    /// 对应 Java `ExecutionIdGenerator.buildNewExecId`。
    pub fn build_new_exec_id(seq: i64) -> i64 {
        (seq << Self::SHIFT_BITS) | Self::IDX_NEW
    }

    /// 对应 Java `ExecutionIdGenerator.buildRejectExecId`。
    pub fn build_reject_exec_id(seq: i64) -> i64 {
        (seq << Self::SHIFT_BITS) | Self::IDX_REJECT
    }

    /// 对应 Java `ExecutionIdGenerator.buildReduceExecId`（CANCEL 与 REDUCE 共用该子序号）。
    pub fn build_reduce_exec_id(seq: i64) -> i64 {
        (seq << Self::SHIFT_BITS) | Self::IDX_CANCEL
    }

    /// 对应 Java `ExecutionIdGenerator.buildTradeExecId`：`is_maker` 决定同一 `trade_index` 内取
    /// taker/maker 哪一条。
    pub fn build_trade_exec_id(seq: i64, trade_index: i32, is_maker: bool) -> i64 {
        let local_idx = Self::IDX_TRADE_BASE + ((trade_index as i64) << 1) + if is_maker { 1 } else { 0 };
        (seq << Self::SHIFT_BITS) | local_idx
    }

    /// 对应 Java `ExecutionIdGenerator.buildTradeId`：taker/maker 共用同一 trade_id，与上面按位区分的
    /// exec_id 不同（子序号从高位半程起编，避免与 NEW/REJECT/CANCEL/TRADE 子序号撞号）。
    pub fn build_trade_id(seq: i64, trade_index: i32) -> i64 {
        let local_idx = (1i64 << (Self::SHIFT_BITS - 1)) + trade_index as i64;
        (seq << Self::SHIFT_BITS) | local_idx
    }
}
