//! 对应 Java: `exchange.core2.core.processors.FundingFeeCommandProcessor`（`TwoStepCommandProcessor`
//! 的一个薄实例，全文移植，197 行）。`SETTLE_FUNDINGFEES` 两步处理器：资金费零和结算——
//! payer 池（多付空 / 空付多，由 `cmd.action` 决定）按各自持仓 notional 精确算出应付费，
//! receiver 池按各自持仓 notional pro-rata 分摊应收费，两者恒等（零和，无桶）。
//!
//! 参考文档 §4。字段映射（`ExchangeApi.java:1083-1093`）：`cmd.action` = BID（多付空）/ASK
//! （空付多）、`cmd.price = fundingRate`、`cmd.size = rateScaleK`（定点费率的 scale，
//! `trunc_mul_div` 除以它）。`cmd.symbol` = 期货 symbol。**`SETTLE_FUNDINGFEES` 不是
//! `is_non_trading()`**（Task 1 已定，见 `order_command_type.rs`）——停留在主交易 switch，
//! 与 `PLACE_ORDER`/`CLOSE_POSITION` 同级。
//!
//! 三段式（同 `InternalTransferProcessor`/`LoanRatePricingProcessor` 骨架，Ruling P6-A/P6-C）：
//! - **R1** [`Self::collect_input`]：走全部 `ACTIVE` 用户在 `symbol` 上的持仓，
//!   `notional = open_volume * mark_price`；持仓方向与 `cmd.action` 相同 → payer 侧，精确算出
//!   `fee = trunc_mul_div(notional, cmd.price, cmd.size)`（`fee>0` 才记）；方向相反 → receiver
//!   侧，记**原始 notional**（不在 R1 就算出 fee——按比例分摊要等 merge 阶段知道
//!   `total_pay`/`total_recv_notional` 才能算，见 merge 文档）。
//! - **merge** [`Self::build_matcher_events`]：**两级 pro-rata 的第一级**——`total_pay`
//!   （跨 shard payer 费用求和）按各 shard 的 receiver notional 占比截断分配，用 `trunc_mul_div`
//!   叠加 [`distribute_remainder_by_one`] 做 1-unit 余数分配（shard-id 升序确定性），产出每个
//!   shard 应收到的 `shard_recv_amount`。`total_pay==0 || total_recv_notional==0` → 无事件
//!   （没有可结算的东西）。
//! - **R2** [`Self::apply_event`]：**两级 pro-rata 的第二级**——先无条件精确扣 `payer_amounts`
//!   （R1 已算好精确值，无需再截断），再把 merge 分给本 shard 的 `shard_recv_amount` 按
//!   `receiver_notionals` 占比重新截断分配给具体用户（同一个 [`distribute_remainder_by_one`]
//!   原语，第二次调用，weights 换成 uid -> notional）。两笔都走
//!   [`Self::settle_funding_fee`]：按 `symbol`/`-symbol`（HEDGE 双向持仓）找匹配方向的活仓，
//!   有则直接加进 `position.profit`（无需 scale——position.profit 与 fee 同 sizePrice
//!   scale）；仓已在 R1/R2 之间平掉（ghost）则改用 `size_price_to_currency_scale` 缩放进
//!   `accounts[quote_currency]`——费跟着钱走，不跟着已经不存在的仓走。
//!
//! # 事件载体的移植偏差（同 P5/P6 既有先例，Ruling P6-A/P6-C，需记录）
//! Java 用 `MatcherEventType::FUNDING_EVENT`（撮合引擎共享事件类型，`matchedOrderUid`/`price`
//! 分别承载 shard id / shard 分配额）在 R1→ME(merge)→R2 之间传递数据，`OrderCommand
//! .fundingPaymentAndRecvNotionalByShard[]`（按 shard 下标的数组）承载 R1 输出。本移植不扩
//! `MatcherEventType`（撮合引擎共享类型，多处对其做穷尽 `match`，塞一个和撮合无关的"资金费
//! 事件"变体波及面大），改用 `OrderCommand.funding_fee_event: Option<(BTreeMap<i64,i64>,
//! BTreeMap<i64,i64>, i64)>`（`(payer_amounts, receiver_notionals, shard_recv_amount)`）承载
//! R1+merge 的合并结果。单 shard 下（Ruling P6-C）"跨 shard 归并"是恒等操作（`build_matcher_
//! events` 收到长度为 1 的切片，`shard_recv_amount` 退化为恰好 `total_pay`，第一级的余数分配
//! 理论上恒为 0——但函数本身仍按"多 shard 输入"的形状实现，供未来多 shard 时对齐 Java 真实
//! 归并语义），由 `RiskEngine::settle_funding_fees_collect` 一次性完成 R1
//! [`Self::collect_input`] + merge [`Self::build_matcher_events`]，结果写入
//! `cmd.funding_fee_event`；`RiskEngine::settle_funding_fees_apply` 消费，驱动 R2
//! [`Self::apply_event`]。数值/顺序/语义与 Java 完全一致，只是数据搬运的物理载体不同。
//!
//! # 共享原语：`distribute_remainder_by_one`
//! 本文件是"截断分配 + 1-unit 余数分配"模式的**发源地**（Java 两处几乎逐字相同的循环，见
//! `FundingFeeCommandProcessor.java:85-104`/`:150-161`）——已提取到
//! [`crate::core::utils::core_arithmetic_utils::distribute_remainder_by_one`]（零依赖模型层，
//! 泛型 over `K: Ord + Copy`），Task 5（IF）/Task 6（ADL）复用同一个函数，不再各自重复实现。
//!
//! # checkPositions 钩子（有意未落地，记录偏差）
//! Java `RiskEngine.handlerRiskRelease`（`:977`）在 R2 事件循环结束后追加调用
//! `liquidationEngine.checkPositions(cmd)`——funding 结算完，若有仓因此掉进强平/ADL
//! 资格区间，立刻触发检测。本移植的 `LiquidationEngine`/`checkPositions` 属 Task 7 排期（P6-K
//! 已把 `liquidation_flow` 挪到 Task 7），本 Task 不落地这个钩子——不影响本 Task 负责的账户/
//! 持仓结算正确性（钩子只是"结算后顺带检查"，不改写结算结果本身），Task 7 完成时需回来给
//! `RiskEngine::settle_funding_fees_apply` 补上同一钩子调用（参考文档 §1.1 line 977，进度
//! ledger 已记录这条依赖）。
//!
//! # 未移植：事件总线
//! `sendFundingFeeEvent`/`sendFundingFeeEventForClosedPosition`（`:187`/`:192-193`）未移植——
//! 全 port 无事件总线（Ruling P6-B，同 P1-P5 既定），不影响账户结算正确性。

use std::collections::BTreeMap;

use crate::core::common::core_currency_specification::CoreCurrencySpecification;
use crate::core::common::core_symbol_specification::CoreSymbolSpecification;
use crate::core::common::order_action::OrderAction;
use crate::core::common::user_status::UserStatus;
use crate::core::processors::user_profile_service::UserProfileService;
use crate::core::utils::core_arithmetic_utils as arithmetic;
use crate::core::utils::core_arithmetic_utils::distribute_remainder_by_one;

/// 对应 Java `Math.multiplyExact(long, long)`：局部私有重复一份，同 `risk_engine.rs`/
/// `symbol_position_record.rs` 的同名 helper 风格（`CoreArithmeticUtils` 里的 `mulExact` 按
/// Task 1 零依赖 ruling 是私有的，各消费点各自复制一份轻量实现）。
fn mul_exact(a: i64, b: i64) -> i64 {
    i64::try_from(a as i128 * b as i128).unwrap_or_else(|_| panic!("overflow: {a} * {b}"))
}

/// 对应 Java `FundingPaymentAndRecvNotional`（`OrderCommand.fundingPaymentAndRecvNotionalByShard[]`
/// 数组元素类型）：单 shard 一份，`uid -> fee`（payer 侧，R1 已算好精确值）+
/// `uid -> raw notional`（receiver 侧，原始名义价值，费用留到 merge/R2 两级 pro-rata 时再算）。
#[derive(Debug, Clone, Default, PartialEq, Eq)]
pub struct FundingPaymentAndRecvNotional {
    pub payer_amounts: BTreeMap<i64, i64>,
    pub receiver_notionals: BTreeMap<i64, i64>,
}

/// 无状态处理器——所有方法都是关联函数，不持有任何字段（同 `InternalTransferProcessor`/
/// `LoanRatePricingProcessor` 先例：Java 版本持有 `riskEngine`/`eventsHelper` 两个可选引用只是
/// 为了在 R1/R2 实例与 merge 实例之间做运行时门禁校验，本移植单线程同步调用模型不需要这层
/// 门禁）。
pub struct FundingFeeCommandProcessor;

impl FundingFeeCommandProcessor {
    /// R1：对应 Java `collectInput`（`:34-68`）。**不做** `cmd.size<=0`/mark price 缺失的前置
    /// 门禁——那是 Java `RiskEngine.preProcessCommand` case `SETTLE_FUNDINGFEES` 外层（`spec`
    /// 校验 + `LastPriceCacheRecord` 校验）与 `collectInput` 自身（`cmd.size<=0`）两层校验
    /// 叠加的效果，本移植把这两层校验挪到调用方
    /// [`crate::core::processors::risk_engine::RiskEngine::settle_funding_fees_collect`]
    /// （见其文档"R1 前置门禁顺序"），本函数只负责"给定已验证的 `mark_price`/`rate`/
    /// `rate_scale_k`，扫用户产出 payer/receiver 两个 map"这一步，与 Java `collectInput` 校验
    /// 通过之后的主体逻辑（`:49-67`）一一对应：
    /// ```text
    /// for user in ACTIVE users:
    ///     position = user.positions[symbol]
    ///     if position.open_volume == 0: skip
    ///     notional = open_volume * mark_price
    ///     if position.direction == action:          // payer 侧
    ///         fee = trunc_mul_div(notional, rate, rate_scale_k)
    ///         if fee > 0: payer_amounts[uid] = fee
    ///     else:                                      // receiver 侧
    ///         receiver_notionals[uid] = notional     // 原始 notional，merge/R2 时再算 fee
    /// ```
    pub fn collect_input(
        ups: &UserProfileService,
        symbol: i32,
        mark_price: i64,
        action: OrderAction,
        rate: i64,
        rate_scale_k: i64,
    ) -> FundingPaymentAndRecvNotional {
        let mut shard = FundingPaymentAndRecvNotional::default();
        for user in ups.users.values() {
            if user.user_status != UserStatus::Active {
                continue;
            }
            let Some(position) = user.positions.get(&symbol) else {
                continue;
            };
            if position.open_volume == 0 {
                continue;
            }
            let notional = mul_exact(position.open_volume, mark_price);
            if position.direction.is_same_as_action(action) {
                let fee = arithmetic::trunc_mul_div(notional, rate, rate_scale_k);
                if fee > 0 {
                    shard.payer_amounts.insert(user.uid, fee);
                }
            } else {
                shard.receiver_notionals.insert(user.uid, notional);
            }
        }
        shard
    }

    /// merge：对应 Java `buildMatcherEvents`（`:70-126`）——**两级 pro-rata 的第一级**：
    /// `total_pay`（跨 shard payer 费用求和）按各 shard 的 receiver notional 占比截断分配 +
    /// [`distribute_remainder_by_one`] 1-unit 余数分配（shard-id 升序，确定性，参考文档
    /// §11.2）。`shards_data` 是每个 shard 各自的 [`Self::collect_input`] 输出（本移植单 shard
    /// 场景下调用方传长度为 1 的切片，见 `risk_engine.rs::settle_funding_fees_collect`）。
    ///
    /// `total_pay==0 || total_recv_notional==0` → 返回空 `Vec`（对应 Java `cmd.matcherEvent =
    /// null`：没有可结算的东西，调用方据此判断"本次无事可做"）。
    ///
    /// 每个 shard 是否参与分配（截断额与余数额两个环节共用同一判定）：`receiver_notionals`
    /// 非空。对应 Java 判定条件技术上是两处不同表达（截断循环用 `notional==0` continue、
    /// 余数循环用 `receiverNotionals.isEmpty()`），但在本域内恒等价——参与分配的 notional 恒
    /// `> 0`（由 `open_volume!=0` 且 `mark_price` 必为正的 R1 前置门禁保证），故本函数用统一的
    /// "非空"判定精确对齐 Java 的实际行为。
    ///
    /// 事件产出：`amount<=0 且 payer_amounts 为空` 的 shard 跳过（对应 Java `:111-113`），其余
    /// shard 产出一条 `(shard_id, amount)`（对应 Java `ev.price=amount, ev.matchedOrderUid=
    /// shardId`）。
    pub fn build_matcher_events(shards_data: &[FundingPaymentAndRecvNotional]) -> Vec<(usize, i64)> {
        let total_pay: i64 = shards_data.iter().map(|s| s.payer_amounts.values().sum::<i64>()).sum();
        let total_recv_notional: i64 = shards_data.iter().map(|s| s.receiver_notionals.values().sum::<i64>()).sum();
        if total_pay == 0 || total_recv_notional == 0 {
            return Vec::new();
        }

        let mut weights: BTreeMap<usize, i64> = BTreeMap::new();
        for (shard_id, shard) in shards_data.iter().enumerate() {
            if !shard.receiver_notionals.is_empty() {
                weights.insert(shard_id, shard.receiver_notionals.values().sum());
            }
        }
        let shard_recv_amount = distribute_remainder_by_one(total_pay, &weights);

        let mut events = Vec::new();
        for (shard_id, shard) in shards_data.iter().enumerate() {
            let amount = *shard_recv_amount.get(&shard_id).unwrap_or(&0);
            let has_payers = !shard.payer_amounts.is_empty();
            if amount <= 0 && !has_payers {
                continue;
            }
            events.push((shard_id, amount));
        }
        events
    }

    /// R2：对应 Java `applyEvent`（`:128-167`）——只处理 `ev.matchedOrderUid == shardId` 的事件
    /// （本移植单 shard 恒匹配，调用方已只传本 shard 的数据，无需再比对 shard id）。**两级
    /// pro-rata 的第二级**：先无条件精确扣 `payer_amounts`（R1 已算好精确值，逐用户
    /// [`Self::settle_funding_fee`]，`is_payer=true`），再把 `shard_recv_amount`（merge 分给本
    /// shard 的截断后金额）按 `receiver_notionals` 占比重新截断分配给具体用户（同一个
    /// [`distribute_remainder_by_one`] 原语第二次调用，weights 换成 uid -> notional），
    /// `fee==0` 的用户跳过（对应 Java `:163-164`）。
    ///
    /// `shard_recv_amount<=0 || receiver_notionals.is_empty()` → 直接返回（对应 Java
    /// `:147-149`：本 shard 没有分到钱或没有 receiver，跳过第二级分配，但 payer 一侧已在上面
    /// 无条件处理过）。
    #[allow(clippy::too_many_arguments)]
    pub fn apply_event(
        ups: &mut UserProfileService,
        symbol: i32,
        action: OrderAction,
        payer_amounts: &BTreeMap<i64, i64>,
        receiver_notionals: &BTreeMap<i64, i64>,
        shard_recv_amount: i64,
        spec: &CoreSymbolSpecification,
        currency_spec: &CoreCurrencySpecification,
    ) {
        for (&uid, &fee) in payer_amounts {
            Self::settle_funding_fee(ups, symbol, action, uid, fee, true, spec, currency_spec);
        }

        if shard_recv_amount <= 0 || receiver_notionals.is_empty() {
            return;
        }
        let receiver_fees = distribute_remainder_by_one(shard_recv_amount, receiver_notionals);
        for (&uid, &fee) in &receiver_fees {
            if fee == 0 {
                continue;
            }
            Self::settle_funding_fee(ups, symbol, action, uid, fee, false, spec, currency_spec);
        }
    }

    /// 对应 Java `settleFundingFee`（`:169-195`）：payer/receiver 共用的落账逻辑。
    ///
    /// `position_side`：payer 用 `action` 本身（付款方持仓方向 == cmd.action，与 R1 payer 侧
    /// 判定条件一致），receiver 用 `action.opposite()`（收款方持仓方向与 payer 相反）。
    /// `signed_fee`：payer 为负（扣），receiver 为正（加）。
    ///
    /// **HEDGE 双向持仓查找**：先查 `symbol`，若不存在或方向与 `position_side` 不符，退而查
    /// `-symbol`（HEDGE 模式下正负 symbol 分别承载多/空两条独立持仓记录，对应 Java
    /// `user.positions.get(symbol)` 失败后 `.get(-symbol)` 的两步查找）。
    ///
    /// **活仓命中**（最终解析到的持仓 `open_volume>0` 且方向与 `position_side` 一致）：直接
    /// `position.profit += signed_fee`——不缩放，`profit` 与 `fee` 同 sizePrice scale。
    ///
    /// **ghost 回落**（两次查找都未命中活仓——仓已在 R1/R2 之间被平掉，或用户从未在此 symbol
    /// 开过仓）：用 `size_price_to_currency_scale` 把 `signed_fee` 缩放进
    /// `accounts[spec.quote_currency]`——费跟着钱走，不跟着已经不存在的仓走（参考文档 §4.3
    /// 明确措辞）。
    ///
    /// 用户缺失或非 `ACTIVE`：直接跳过（对应 Java `:172-174`，费"消失"——不影响零和总账，
    /// 因为触发这一路径要求用户在 R1 阶段就已经不是这个 payer/receiver 的来源；本移植单命令
    /// 内 R1→merge→R2 同步执行，这条分支在真实调用链路上不可达，只有测试直接调用
    /// `apply_event` 且刻意在 R1/R2 之间改变用户状态时才会触发——同 ghost-position 场景的
    /// 测试方法论）。
    #[allow(clippy::too_many_arguments)]
    fn settle_funding_fee(
        ups: &mut UserProfileService,
        symbol: i32,
        action: OrderAction,
        uid: i64,
        fee: i64,
        is_payer: bool,
        spec: &CoreSymbolSpecification,
        currency_spec: &CoreCurrencySpecification,
    ) {
        let Some(user) = ups.get_mut(uid) else {
            return;
        };
        if user.user_status != UserStatus::Active {
            return;
        }

        let position_side = if is_payer { action } else { action.opposite() };
        let signed_fee = if is_payer { -fee } else { fee };

        let primary_matches =
            user.positions.get(&symbol).is_some_and(|p| p.direction.is_same_as_action(position_side));
        let lookup_symbol = if primary_matches { symbol } else { -symbol };

        let has_active_position = user
            .positions
            .get(&lookup_symbol)
            .is_some_and(|p| p.open_volume > 0 && p.direction.is_same_as_action(position_side));

        if has_active_position {
            user.positions.get_mut(&lookup_symbol).expect("checked present above").profit += signed_fee;
        } else {
            let scaled_fee = arithmetic::size_price_to_currency_scale(
                signed_fee,
                spec.base_scale_k,
                spec.quote_scale_k,
                currency_spec.currency_scale_k,
            );
            user.add_to_account(spec.quote_currency, scaled_fee);
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::core::common::cmd::command_result_code::CommandResultCode;
    use crate::core::common::core_currency_specification::CoreCurrencySpecification;
    use crate::core::common::core_symbol_specification::CoreSymbolSpecification;
    use crate::core::common::margin_mode::MarginMode;
    use crate::core::common::position_direction::PositionDirection;
    use crate::core::common::symbol_position_record::SymbolPositionRecord;
    use crate::core::common::symbol_type::SymbolType;

    const SYMBOL: i32 = 500;
    const BASE: i32 = 10;
    const QUOTE: i32 = 20;

    fn spec() -> CoreSymbolSpecification {
        CoreSymbolSpecification {
            symbol_id: SYMBOL,
            symbol_type: SymbolType::FuturesContractPerpetual,
            base_currency: BASE,
            quote_currency: QUOTE,
            base_scale_k: 1,
            quote_scale_k: 1,
            ..Default::default()
        }
    }

    fn currency_spec() -> CoreCurrencySpecification {
        CoreCurrencySpecification { currency: QUOTE, currency_scale_k: 1, ..Default::default() }
    }

    fn ups_with_user(uid: i64) -> UserProfileService {
        let mut ups = UserProfileService::new();
        assert_eq!(ups.add_empty_user_profile(uid), CommandResultCode::Success);
        ups
    }

    fn position(uid: i64, direction: PositionDirection, open_volume: i64) -> SymbolPositionRecord {
        SymbolPositionRecord {
            direction,
            open_volume,
            ..SymbolPositionRecord::new(uid, SYMBOL, QUOTE, MarginMode::Isolated, 1)
        }
    }

    // ---- R1 collect_input ----

    #[test]
    fn collect_input_payer_side_computes_exact_fee_and_skips_zero_fee() {
        let mut ups = ups_with_user(1);
        ups.get_mut(1).unwrap().positions.insert(SYMBOL, position(1, PositionDirection::Long, 100));
        // action=Bid（多付空）: LONG 持仓与 action 同向 -> payer。notional=100*10=1000,
        // rate=5, rate_scale_k=1000 -> fee = trunc(1000*5/1000) = 5.
        let shard = FundingFeeCommandProcessor::collect_input(&ups, SYMBOL, 10, OrderAction::Bid, 5, 1000);
        assert_eq!(shard.payer_amounts.get(&1), Some(&5));
        assert!(shard.receiver_notionals.is_empty());
    }

    #[test]
    fn collect_input_payer_side_skips_when_computed_fee_not_positive() {
        let mut ups = ups_with_user(1);
        ups.get_mut(1).unwrap().positions.insert(SYMBOL, position(1, PositionDirection::Long, 100));
        // notional=1000, rate=0 -> fee=0，不记入 payer_amounts（对应 Java `if (fundingFee > 0)`）。
        let shard = FundingFeeCommandProcessor::collect_input(&ups, SYMBOL, 10, OrderAction::Bid, 0, 1000);
        assert!(shard.payer_amounts.is_empty());
    }

    #[test]
    fn collect_input_receiver_side_records_raw_notional_not_fee() {
        let mut ups = ups_with_user(2);
        ups.get_mut(2).unwrap().positions.insert(SYMBOL, position(2, PositionDirection::Short, 100));
        // action=Bid: SHORT 持仓与 action 反向 -> receiver。记原始 notional=100*10=1000（不是 fee）。
        let shard = FundingFeeCommandProcessor::collect_input(&ups, SYMBOL, 10, OrderAction::Bid, 5, 1000);
        assert_eq!(shard.receiver_notionals.get(&2), Some(&1000));
        assert!(shard.payer_amounts.is_empty());
    }

    #[test]
    fn collect_input_skips_flat_and_missing_positions_and_inactive_users() {
        let mut ups = ups_with_user(1);
        ups.get_mut(1).unwrap().positions.insert(SYMBOL, position(1, PositionDirection::Empty, 0));
        assert_eq!(ups.add_empty_user_profile(2), CommandResultCode::Success); // no position on SYMBOL at all
        assert_eq!(ups.add_empty_user_profile(3), CommandResultCode::Success);
        ups.get_mut(3).unwrap().positions.insert(SYMBOL, position(3, PositionDirection::Long, 100));
        ups.get_mut(3).unwrap().user_status = crate::core::common::user_status::UserStatus::Suspended;

        let shard = FundingFeeCommandProcessor::collect_input(&ups, SYMBOL, 10, OrderAction::Bid, 5, 1000);
        assert!(shard.payer_amounts.is_empty());
        assert!(shard.receiver_notionals.is_empty());
    }

    // ---- merge build_matcher_events ----

    #[test]
    fn build_matcher_events_single_shard_full_pay_goes_to_single_shard_no_remainder() {
        let mut shard = FundingPaymentAndRecvNotional::default();
        shard.payer_amounts.insert(1, 100);
        shard.receiver_notionals.insert(2, 500);
        let events = FundingFeeCommandProcessor::build_matcher_events(&[shard]);
        assert_eq!(events, vec![(0, 100)], "单 shard: 自己的 recv notional 占比恒 100%，无截断损失");
    }

    #[test]
    fn build_matcher_events_no_event_when_either_pool_empty() {
        let mut only_payers = FundingPaymentAndRecvNotional::default();
        only_payers.payer_amounts.insert(1, 100);
        assert!(FundingFeeCommandProcessor::build_matcher_events(&[only_payers]).is_empty(), "receiver 池为空 -> 无事件");

        let mut only_receivers = FundingPaymentAndRecvNotional::default();
        only_receivers.receiver_notionals.insert(2, 500);
        assert!(
            FundingFeeCommandProcessor::build_matcher_events(&[only_receivers]).is_empty(),
            "payer 池为空 -> 无事件"
        );

        assert!(FundingFeeCommandProcessor::build_matcher_events(&[]).is_empty());
    }

    #[test]
    fn build_matcher_events_multi_shard_pro_rata_by_receiver_notional_with_deterministic_remainder() {
        // shard 0: payer 100; receiver notional sum = 30
        // shard 1: payer 0;   receiver notional sum = 70
        // total_pay=100, total_recv_notional=100 -> shard0 应得 trunc(100*30/100)=30,
        // shard1 应得 trunc(100*70/100)=70，恰好整除，无余数。
        let mut shard0 = FundingPaymentAndRecvNotional::default();
        shard0.payer_amounts.insert(1, 100);
        shard0.receiver_notionals.insert(10, 30);
        let mut shard1 = FundingPaymentAndRecvNotional::default();
        shard1.receiver_notionals.insert(20, 70);
        let events = FundingFeeCommandProcessor::build_matcher_events(&[shard0, shard1]);
        assert_eq!(events, vec![(0, 30), (1, 70)]);
    }

    #[test]
    fn build_matcher_events_multi_shard_remainder_goes_to_lowest_shard_id() {
        // shard 0: payer 10; receiver notional 1
        // shard 1: payer 0;  receiver notional 1
        // shard 2: payer 0;  receiver notional 1
        // total_pay=10, total_recv_notional=3 -> trunc(10*1/3)=3 每个 shard，distributed=9,
        // remainder=1 -> shard 0（升序最小）拿到多的 1。
        let mut shard0 = FundingPaymentAndRecvNotional::default();
        shard0.payer_amounts.insert(1, 10);
        shard0.receiver_notionals.insert(10, 1);
        let mut shard1 = FundingPaymentAndRecvNotional::default();
        shard1.receiver_notionals.insert(20, 1);
        let mut shard2 = FundingPaymentAndRecvNotional::default();
        shard2.receiver_notionals.insert(30, 1);
        let events = FundingFeeCommandProcessor::build_matcher_events(&[shard0, shard1, shard2]);
        assert_eq!(events, vec![(0, 4), (1, 3), (2, 3)], "余数 1 单位必须分给 shard_id 升序最小的 shard 0");
    }

    // ---- R2 apply_event / settle_funding_fee ----

    #[test]
    fn apply_event_one_payer_one_receiver_full_transfer_is_zero_sum() {
        let mut ups = ups_with_user(1);
        assert_eq!(ups.add_empty_user_profile(2), CommandResultCode::Success);
        ups.get_mut(1).unwrap().positions.insert(SYMBOL, position(1, PositionDirection::Long, 100)); // payer
        ups.get_mut(2).unwrap().positions.insert(SYMBOL, position(2, PositionDirection::Short, 100)); // receiver

        let payer_amounts = BTreeMap::from([(1i64, 50i64)]);
        let receiver_notionals = BTreeMap::from([(2i64, 1000i64)]);
        FundingFeeCommandProcessor::apply_event(
            &mut ups,
            SYMBOL,
            OrderAction::Bid,
            &payer_amounts,
            &receiver_notionals,
            50, // shard_recv_amount == total_pay (single receiver gets it all)
            &spec(),
            &currency_spec(),
        );

        let payer_profit = ups.get(1).unwrap().positions.get(&SYMBOL).unwrap().profit;
        let receiver_profit = ups.get(2).unwrap().positions.get(&SYMBOL).unwrap().profit;
        assert_eq!(payer_profit, -50, "payer 精确扣 50");
        assert_eq!(receiver_profit, 50, "receiver 全额收到 50");
        assert_eq!(payer_profit + receiver_profit, 0, "零和：payer 损失 == receiver 收益");
    }

    #[test]
    fn apply_event_multiple_receivers_pro_rata_with_deterministic_remainder_uid() {
        let mut ups = ups_with_user(1); // payer
        for uid in [10i64, 20, 30] {
            assert_eq!(ups.add_empty_user_profile(uid), CommandResultCode::Success);
        }
        ups.get_mut(1).unwrap().positions.insert(SYMBOL, position(1, PositionDirection::Long, 100));
        ups.get_mut(10).unwrap().positions.insert(SYMBOL, position(10, PositionDirection::Short, 100));
        ups.get_mut(20).unwrap().positions.insert(SYMBOL, position(20, PositionDirection::Short, 100));
        ups.get_mut(30).unwrap().positions.insert(SYMBOL, position(30, PositionDirection::Short, 100));

        let payer_amounts = BTreeMap::from([(1i64, 10i64)]);
        // notionals 1:1:1 -> trunc(10*1/3)=3 each, distributed=9, remainder=1 -> uid=10 (升序最小) 拿到 +1.
        let receiver_notionals = BTreeMap::from([(10i64, 1i64), (20i64, 1i64), (30i64, 1i64)]);
        FundingFeeCommandProcessor::apply_event(
            &mut ups,
            SYMBOL,
            OrderAction::Bid,
            &payer_amounts,
            &receiver_notionals,
            10,
            &spec(),
            &currency_spec(),
        );

        let p10 = ups.get(10).unwrap().positions.get(&SYMBOL).unwrap().profit;
        let p20 = ups.get(20).unwrap().positions.get(&SYMBOL).unwrap().profit;
        let p30 = ups.get(30).unwrap().positions.get(&SYMBOL).unwrap().profit;
        assert_eq!((p10, p20, p30), (4, 3, 3), "余数 1 单位必须分给 uid 升序最小的 10，确定性可预测");
        assert_eq!(p10 + p20 + p30, 10, "receiver 总收益必须等于 shard_recv_amount");
        let payer_profit = ups.get(1).unwrap().positions.get(&SYMBOL).unwrap().profit;
        assert_eq!(payer_profit + p10 + p20 + p30, 0, "零和守恒");
    }

    #[test]
    fn apply_event_payer_or_receiver_pool_empty_produces_no_settlement() {
        let mut ups = ups_with_user(1);
        ups.get_mut(1).unwrap().positions.insert(SYMBOL, position(1, PositionDirection::Long, 100));
        let empty: BTreeMap<i64, i64> = BTreeMap::new();
        let receivers = BTreeMap::from([(2i64, 1000i64)]);

        // shard_recv_amount<=0 -> 第二级分配完全跳过（即使 receiver_notionals 非空）。
        FundingFeeCommandProcessor::apply_event(
            &mut ups,
            SYMBOL,
            OrderAction::Bid,
            &empty,
            &receivers,
            0,
            &spec(),
            &currency_spec(),
        );
        assert_eq!(ups.get(1).unwrap().positions.get(&SYMBOL).unwrap().profit, 0, "无 payer_amounts -> 无扣款");
    }

    #[test]
    fn apply_event_position_closed_between_r1_and_r2_routes_fee_to_accounts_not_ghost_position() {
        let mut ups = ups_with_user(1);
        // R1 时刻：用户在 SYMBOL 上有仓（模拟 collect_input 观察到的状态）。
        ups.get_mut(1).unwrap().positions.insert(SYMBOL, position(1, PositionDirection::Long, 100));
        // 模拟 R1/R2 之间该仓被平掉（open_volume 归零，direction 归 Empty，同真实平仓后的状态）。
        let p = ups.get_mut(1).unwrap().positions.get_mut(&SYMBOL).unwrap();
        p.open_volume = 0;
        p.direction = PositionDirection::Empty;

        let payer_amounts = BTreeMap::from([(1i64, 42i64)]);
        let empty: BTreeMap<i64, i64> = BTreeMap::new();
        FundingFeeCommandProcessor::apply_event(
            &mut ups, SYMBOL, OrderAction::Bid, &payer_amounts, &empty, 0, &spec(), &currency_spec(),
        );

        let up = ups.get(1).unwrap();
        assert_eq!(up.positions.get(&SYMBOL).unwrap().profit, 0, "ghost 仓不应被写入");
        assert_eq!(up.account(QUOTE), -42, "费跟着钱走：直接扣进 accounts[quote_currency]");
    }

    #[test]
    fn apply_event_receiver_position_closed_between_r1_and_r2_credits_accounts() {
        let mut ups = ups_with_user(1); // payer
        assert_eq!(ups.add_empty_user_profile(2), CommandResultCode::Success); // receiver, ghost by R2
        ups.get_mut(1).unwrap().positions.insert(SYMBOL, position(1, PositionDirection::Long, 100));
        // uid=2 从未在 SYMBOL 开过仓（等价于"已平仓"——两次查找 symbol/-symbol 都不命中活仓）。

        let payer_amounts = BTreeMap::from([(1i64, 50i64)]);
        let receiver_notionals = BTreeMap::from([(2i64, 1000i64)]);
        FundingFeeCommandProcessor::apply_event(
            &mut ups, SYMBOL, OrderAction::Bid, &payer_amounts, &receiver_notionals, 50, &spec(), &currency_spec(),
        );

        let receiver = ups.get(2).unwrap();
        assert!(receiver.positions.get(&SYMBOL).is_none(), "不应凭空建仓");
        assert_eq!(receiver.account(QUOTE), 50, "ghost receiver 的收益直接入账户");
    }

    #[test]
    fn apply_event_hedge_dual_direction_lookup_falls_back_to_negative_symbol() {
        // HEDGE：payer 在 -SYMBOL（空头腿）上持有匹配方向的仓，SYMBOL（多头腿）不存在或方向不符。
        let mut ups = ups_with_user(1);
        // action=Ask（空付多，payer 方向应为 Short）。SYMBOL 上没有仓，只在 -SYMBOL 上有 SHORT 仓。
        let neg_symbol = -SYMBOL;
        ups.get_mut(1).unwrap().positions.insert(
            neg_symbol,
            SymbolPositionRecord {
                direction: PositionDirection::Short,
                open_volume: 100,
                ..SymbolPositionRecord::new(1, neg_symbol, QUOTE, MarginMode::Isolated, 1)
            },
        );

        let payer_amounts = BTreeMap::from([(1i64, 30i64)]);
        let empty: BTreeMap<i64, i64> = BTreeMap::new();
        FundingFeeCommandProcessor::apply_event(
            &mut ups, SYMBOL, OrderAction::Ask, &payer_amounts, &empty, 0, &spec(), &currency_spec(),
        );

        assert_eq!(
            ups.get(1).unwrap().positions.get(&neg_symbol).unwrap().profit,
            -30,
            "HEDGE: SYMBOL 未命中活仓，回落到 -SYMBOL 命中并入账"
        );
        assert_eq!(ups.get(1).unwrap().account(QUOTE), 0, "命中活仓时不应改动 accounts");
    }
}
