//! 对应 Java: exchange.core2.core.common.cmd.OrderCommand（P1：撮合相关字段 + Task 2：现货路由
//! 所需字段 + P4 Task 1：期货 `leverage`/`marginMode`/reduce-only 字段扩展）。
use std::collections::BTreeMap;

use crate::core::common::adl_user_position::AdlUserPosition;
use crate::core::common::cmd::command_result_code::CommandResultCode;
use crate::core::common::order_action::OrderAction;
use crate::core::common::cmd::order_command_type::OrderCommandType;
use crate::core::common::order_type::OrderType;
use crate::core::common::margin_mode::MarginMode;
use crate::core::common::matcher_trade_event::MatcherTradeEvent;
use crate::core::common::l2_market_data::L2MarketData;

/// 对应 Java `OrderCommand.FLAG_REDUCE_ONLY`：`orderFlags` 位标记，仅在 `PLACE_ORDER` 中表示
/// "只减仓"（不开新敞口）。
pub const FLAG_REDUCE_ONLY: i32 = 1;

#[derive(Debug, Clone, Default)]
pub struct OrderCommand {
    /// 对应 Java `OrderCommand.command`：驱动 R1/R2 路由分派
    /// （`isNonTrading()`/`isLoan()` 门守 + 主 switch），Task 2 之前的 P1 撮合路径未含此字段。
    pub command: OrderCommandType,
    pub order_id: i64,
    pub symbol: i32,
    pub price: i64,
    pub size: i64,
    pub reserve_bid_price: i64,
    pub action: Option<OrderAction>,
    pub order_type: Option<OrderType>,
    pub uid: i64,
    pub timestamp: i64,
    pub order_flags: i32,
    /// 对应 Java `OrderCommand.leverage`（`public int leverage = 1;`）。**Ruling P4-B**：
    /// Rust `#[derive(Default)]` 给出的零值是 `0`，不是 Java 字面初始值 `1`——两者在下游语义上
    /// 等价：`SymbolPositionRecord::update_leverage`/`CoreSymbolSpecification::calculate_init_margin`
    /// 等消费点都把 `leverage==0` 归一为 `1`（同 Java `updateLeverage(int)` 的 `0 -> 1`
    /// 归一规则），因此保留 derive、不手写 `Default` impl，未改变任何既有 `..Default::default()`
    /// 构造点的行为。
    pub leverage: i32,
    /// 对应 Java `OrderCommand.marginMode`（默认 `MarginMode.ISOLATED`，码值 0，与
    /// `MarginMode::default()` 一致，derive 得到的零值恰好是正确默认值）。
    pub margin_mode: MarginMode,
    /// 对应 Java `OrderCommand.userCookie`（`public int userCookie;`）。P5 Task 4 新增：
    /// `LOAN_CREATE` 用其低字节承载 `rateMode`（`(byte) cmd.userCookie ==
    /// IsolatedLoanRecord::RATE_MODE_FLOATING` 则 FLOATING，否则 LOCKED，见
    /// `loan_command_dispatcher::handle_loan_create`）；其余命令不使用该字段，derive 出的零值
    /// 与 Java 默认值一致，不影响任何既有构造点。
    pub user_cookie: i32,
    pub result_code: Option<CommandResultCode>,
    pub matcher_event: Option<Box<MatcherTradeEvent>>,
    pub market_data: Option<L2MarketData>,
    /// P5 Task 8 新增：`REPRICE_LOAN_RATES` 专属的 R1→R2 载体（`(currency, util_bps)`，
    /// currency 升序）。对应 Java 版本靠 `commonByShard[..].amounts`（R1 写）+ `matcherEvent`
    /// 单链表（merge 写、R2 读）横跨 R1/ME/R2 传递数据；本移植的 `MatcherEventType`/
    /// `MatcherTradeEvent` 是撮合专用共享类型（多处对其做穷尽匹配），且 ME 层
    /// （`MatchingEngineRouter`）不持有 `LoanService`，故改用这个命令专属字段承载
    /// `LoanRatePricingProcessor` R1 collect_input + merge build_matcher_events 的合并结果
    /// （详见 `loan_rate_pricing_processor.rs` 模块文档"事件载体的移植偏差"）。其余命令类型
    /// 恒为空 `Vec`，不产生任何影响。
    pub loan_reprice_events: Vec<(i32, i64)>,
    /// P6 Task 3 新增：`INTERNAL_TRANSFER` 专属的 R1→R2 载体，`(to_uid, currency, amount)`。
    /// 对应 Java 版本靠 `MatcherEventType::INTERNAL_TRANSFER_EVENT`（撮合引擎共享事件类型，
    /// `matchedOrderUid`/`price`/`size` 三字段分别承载 `toUid`/`currency`/`amount`）在
    /// R1→ME(merge)→R2 之间传递数据；本移植不扩 `MatcherEventType`（Ruling P6-A，同 P5
    /// `loan_reprice_events` 先例：撮合引擎的共享事件类型不塞与撮合无关的变体），改用这个命令
    /// 专属字段。单 shard 下（Ruling P6-C）R1 collect_input 与 merge build_matcher_events 由
    /// `RiskEngine::internal_transfer_collect` 一次性完成，结果写进这里；R2 由
    /// `RiskEngine::handler_risk_release` 专属分支消费（详见 `internal_transfer_processor.rs`
    /// 模块文档）。其余命令类型恒为 `None`，不产生任何影响。
    pub internal_transfer_event: Option<(i64, i32, i64)>,
    /// P6 Task 4 新增：`SETTLE_FUNDINGFEES` 专属的 R1+merge→R2 载体，
    /// `(payer_amounts, receiver_notionals, shard_recv_amount)`——`payer_amounts`/
    /// `receiver_notionals` 是 R1 [`crate::core::processors::funding_fee_command_processor::
    /// FundingFeeCommandProcessor::collect_input`] 的输出（uid -> fee / uid -> raw notional，
    /// 对应 Java 每 shard 一份的 `FundingPaymentAndRecvNotional`），`shard_recv_amount` 是 merge
    /// [`FundingFeeCommandProcessor::build_matcher_events`] 为本 shard 分配到的截断后金额
    /// （对应 Java `MatcherTradeEvent.price`/`matchedOrderUid` 一对，本移植不扩
    /// `MatcherEventType`——Ruling P6-A，同 `internal_transfer_event`/`loan_reprice_events`
    /// 先例）。三元组均为原语类型（`BTreeMap<i64,i64>`/`i64`），不引入 processor 层类型依赖，
    /// 保持 `common` 层对 `processors` 层零依赖（与 `internal_transfer_event` 用裸元组而非具名
    /// struct 同一理由）。单 shard 下（Ruling P6-C）`RiskEngine::settle_funding_fees_collect`
    /// 一次性完成 R1+merge，写入这里；`None` 表示"本命令没有可结算事件"（`total_pay==0` 或
    /// `total_recv_notional==0`，对应 Java `cmd.matcherEvent=null`）——R2
    /// [`RiskEngine::settle_funding_fees_apply`] 消费，`None` 时早退，其余命令类型恒为 `None`，
    /// 不产生任何影响。
    #[allow(clippy::type_complexity)]
    pub funding_fee_event: Option<(BTreeMap<i64, i64>, BTreeMap<i64, i64>, i64)>,
    /// P6 Task 5 新增：`IF_TAKEOVER` R1 专属载体——对应 Java
    /// `OrderCommand.ifPreviewCoverByShard[shardId]`（按 shard 下标数组）单 shard 塌缩后的标量
    /// 形态（Ruling P6-C）：`RiskEngine::if_takeover_collect` 调用
    /// [`crate::core::processors::liquidation::liquidation_service::LiquidationService
    /// ::reserve_if_notional`] 的返回值原样写入，R2 `RiskEngine::if_takeover_apply` 的 finalize
    /// 阶段读取它调用 `release_reserved_if_notional`（无论接管成功/全拒都要释放，跟 R1 对称，见
    /// `if_command_processor.rs` 模块文档）。其余命令类型恒为 `0`（derive 出的零值，`release`
    /// 释放 0 是 no-op，不产生任何影响）。
    pub if_preview_cover: i64,
    /// P6 Task 5 新增：`IF_TAKEOVER` merge 专属载体——对应 Java
    /// `MatcherEventType::IF_EVENT`/`REJECT`（`matchedOrderUid` 承载 shard id）在 R1→ME(merge)→R2
    /// 之间传递数据；本移植不扩 `MatcherEventType`（Ruling P6-A），改用这个命令专属字段。
    /// `Some(size)` = 接管成功（`size` 恒等于 `cmd.size`，单 shard collapse 下 all-or-nothing 退化
    /// 结果，见 [`crate::core::processors::if_command_processor::IfCommandProcessor
    /// ::build_matcher_event`] 文档）；`None` = 全拒（对应 Java `REJECT` 事件）。`RiskEngine
    /// ::if_takeover_collect`（R1+merge 合并）写入，`RiskEngine::if_takeover_apply`（R2）
    /// 消费——`Some` 时驱动 `accept_if_position` + 关 taker 仓，`None` 时两者都跳过（但下面的
    /// `if_preview_cover` 释放不受影响，始终执行）。其余命令类型恒为 `None`。
    pub if_takeover_size: Option<i64>,
    /// P6 Task 6 新增：`AUTO_DELEVERAGING` R1 专属载体——对应 Java
    /// `OrderCommand.adlUserPositionsByShard[shardId]`（按 shard 下标数组、每项是单链表头指针）
    /// 单 shard 塌缩后的形态（Ruling P6-C）：`Vec<AdlUserPosition>` 取代链表（无对象池，见
    /// `adl_user_position.rs` 模块文档），元素顺序 = R1 选中顺序（按 `risk_score` DESC 排序后
    /// 贪心选取，已经是 merge 阶段需要的全局最优序——单 shard 下"跨 shard best-of-N"退化为对这一
    /// 个列表的顺序消费，见 `adl_command_processor.rs` 模块文档）。
    ///
    /// **这是 R2 finalize 释放 `pending_adl_size` 时必须读取的"原始 R1 表"**——`RiskEngine::adl_apply`
    /// 的 finalize 阶段用 `std::mem::take` 取走它整体消费一次（每个候选都对称释放，不管
    /// [`Self::adl_events`] 里实际消费了多少），不会被 merge 阶段污染（merge 只读它、产出一份
    /// 独立的 `adl_events`，不修改这个字段本身——对应 Java 注释"cursors 必须 clone，不能直接复用
    /// cmd.adlUserPositionsByShard"，本移植用只读迭代 + 独立输出取代克隆，效果等价，见
    /// `adl_command_processor.rs` 模块文档"merge 的克隆-vs-原表"一节）。其余命令类型恒为空
    /// `Vec`，不产生任何影响。
    pub adl_user_positions: Vec<AdlUserPosition>,
    /// P6 Task 6 新增：`AUTO_DELEVERAGING` merge 专属载体，`(uid, exec_volume)`——对应 Java
    /// `MatcherEventType::ADL_EVENT` 单链表（`matchedOrderUid`/`size` 各自承载 uid / 实际消费量）
    /// 在 merge→R2 之间传递数据；本移植不扩 `MatcherEventType`（Ruling P6-A，同
    /// `if_takeover_size`/`internal_transfer_event` 先例），改用这个命令专属字段。空 `Vec` = 全拒
    /// （对应 Java `cmd.matcherEvent.eventType == REJECT`，即"没有候选可减仓"）——R2
    /// `RiskEngine::adl_apply` 用 `is_empty()` 判定是否跳过 taker 自身平仓（对应 Java
    /// `matcherEvent.eventType != REJECT` 门）。`RiskEngine::adl_collect`（R1+merge 合并，单 shard
    /// 下"跨 shard 归并"是恒等操作，同 `if_takeover_collect` 先例）写入，同时把 `cmd.size` 改写为
    /// 实际消费总量（对应 Java `cmd.size -= remaining`）。其余命令类型恒为空 `Vec`。
    pub adl_events: Vec<(i64, i64)>,
}

impl OrderCommand {
    /// 对应 Java `OrderCommand.isReduceOnly()`：`orderFlags & FLAG_REDUCE_ONLY != 0`。
    pub fn is_reduce_only(&self) -> bool {
        (self.order_flags & FLAG_REDUCE_ONLY) != 0
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn default_has_zero_leverage_and_isolated_margin_mode() {
        let cmd = OrderCommand::default();
        assert_eq!(cmd.leverage, 0); // 归一到 1 是消费点职责，见字段文档
        assert_eq!(cmd.margin_mode, MarginMode::Isolated);
        assert!(!cmd.is_reduce_only());
    }

    #[test]
    fn is_reduce_only_reads_flag_bit() {
        let mut cmd = OrderCommand { order_flags: FLAG_REDUCE_ONLY, ..Default::default() };
        assert!(cmd.is_reduce_only());

        cmd.order_flags = 0;
        assert!(!cmd.is_reduce_only());

        // 与其他 flag 位共存时仍应命中（按位与，不要求恰好等于 FLAG_REDUCE_ONLY）。
        cmd.order_flags = FLAG_REDUCE_ONLY | 0b10;
        assert!(cmd.is_reduce_only());
    }

    #[test]
    fn leverage_and_margin_mode_are_settable_via_struct_update_syntax() {
        let cmd = OrderCommand { leverage: 10, margin_mode: MarginMode::Cross, ..Default::default() };
        assert_eq!(cmd.leverage, 10);
        assert_eq!(cmd.margin_mode, MarginMode::Cross);
    }
}
