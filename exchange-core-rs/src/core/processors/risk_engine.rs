//! 对应 Java: `exchange.core2.core.processors.RiskEngine`（现货子集：
//! `placeOrderRiskCheck`/`placeOrder`/`placeExchangeOrder`，行 399–685，现货分支 633–685；
//! P4 Task 3 新增期货分支：`placeOrder` 期货路径/`canPlaceMarginOrder`/`closePositionRiskCheck`/
//! `maxClosableSize`，行 436–503/533–623/823–875）。
//! 权威参考：`docs/superpowers/specs/2026-08-31-p3-spot-risk-reference.md` §2（现货）、
//! `docs/superpowers/specs/2026-09-01-p4-futures-risk-reference.md` §3（期货 R1）。
//!
//! # Ruling P3-B（borrow 设计）
//! `RiskEngine` 不持有 `UserProfileService`/`SymbolSpecificationProvider` 的所有权——方法按需
//! 借用调用方传入的 `&mut`/`&` 引用。本期单 shard、单线程，不做 Java `uidForThisHandler` 分片，
//! 视所有 uid 为本 shard（Task 10 引擎持有 ups/ssp 字段，逐命令借出）。

use std::collections::BTreeMap;

use crate::core::common::user_profile::UserProfile;
use crate::core::processors::symbol_specification_provider::SymbolSpecificationProvider;
use crate::core::processors::user_profile_service::UserProfileService;
use crate::core::common::cmd::order_command::OrderCommand;
use crate::core::common::cmd::command_result_code::CommandResultCode;
use crate::core::common::margin_mode::MarginMode;
use crate::core::common::matcher_event_type::MatcherEventType;
use crate::core::common::order_action::OrderAction;
use crate::core::common::cmd::order_command_type::OrderCommandType;
use crate::core::common::order_type::OrderType;
use crate::core::common::position_direction::PositionDirection;
use crate::core::common::position_mode::PositionMode;
use crate::core::common::symbol_position_record::SymbolPositionRecord;
use crate::core::common::symbol_type::SymbolType;
use crate::core::common::matcher_trade_event::MatcherTradeEvent;
use crate::core::common::core_currency_specification::CoreCurrencySpecification;
use crate::core::common::core_symbol_specification::CoreSymbolSpecification;
use crate::core::utils::core_arithmetic_utils as arithmetic;

/// 对应 Java `Math.multiplyExact(long, long)`：局部私有重复一份（`CoreArithmeticUtils` 里的
/// 同名函数按 Task 1 零依赖 ruling 是私有的），风格对齐 `symbol_position_record.rs` 同款 helper。
fn mul_exact(a: i64, b: i64) -> i64 {
    i64::try_from(a as i128 * b as i128).unwrap_or_else(|_| panic!("overflow: {a} * {b}"))
}

/// 对应 Java `Math.subtractExact(long, long)`。
fn sub_exact(a: i64, b: i64) -> i64 {
    i64::try_from(a as i128 - b as i128).unwrap_or_else(|_| panic!("overflow: {a} - {b}"))
}

/// 对应 Java `RiskEngine`（现货子集 + P4 期货 R1 子集）。shard 全局守恒桶（参考文档 §5/§6）：
/// - `adjustments`：`BALANCE_ADJUSTMENT`（充提）的对冲桶——`accounts[cur] += amountDiff` 的同时
///   `adjustments[cur] -= amountDiff`，使 `Σ accounts[cur] + adjustments[cur]` 恒定。
/// - `fees`：现货成交平台手续费累计桶（Task 6/7 `handle_matcher_events_exchange_sell/buy` 写入）。
/// - `last_price_cache`：对应 Java `lastPriceCache`（`IntObjectHashMap<LastPriceCacheRecord>`）
///   ——P4 Task 3 简化子集：只存 `markPrice`（`i64`），完整 `LastPriceCacheRecord`
///   （含 askPrice/bidPrice）延后到有消费者（清算/ADL，P6）时再补字段。symbol -> markPrice；
///   缺失或 `0` 都表示"尚无有效标记价"（对应 Java `priceRecord == null || priceRecord.markPrice
///   == 0` 两种情况，见 [`Self::mark_price`]）。
/// - `cfg_margin_trading_enabled`：对应 Java `cfgMarginTradingEnabled` 配置开关。
///
/// Java 还有 `suspends`（`BalanceAdjustmentType::Suspend` 型对冲桶）——本任务按 brief 明确延后
/// （SUSPEND_USER 命令未移植），故不建该字段，避免无命令路径写入的死字段。
#[derive(Debug, Default)]
pub struct RiskEngine {
    pub adjustments: BTreeMap<i32, i64>,
    pub fees: BTreeMap<i32, i64>,
    pub last_price_cache: BTreeMap<i32, i64>,
    pub cfg_margin_trading_enabled: bool,
}

impl RiskEngine {
    /// **Ruling P4-B**：`cfg_margin_trading_enabled` 默认 `true`（P4 期货测试路径默认可用）——
    /// `#[derive(Default)]` 会给出 `false`，但全仓库唯一的构造入口是 `RiskEngine::new()`
    /// （无调用点用 `RiskEngine::default()`，见任务报告核对），故这里手写默认值不影响
    /// derive 出的 `Default` 对既有测试的行为。新增两个字段均以空/零值起步，不改变现货路径
    /// （`place_exchange_order`）的既有行为——它从不读这两个字段。
    pub fn new() -> Self {
        RiskEngine {
            adjustments: BTreeMap::new(),
            fees: BTreeMap::new(),
            last_price_cache: BTreeMap::new(),
            cfg_margin_trading_enabled: true,
        }
    }

    /// 对应 Java `lastPriceCache.get(symbol)` 之后取 `.markPrice`（简化子集，见结构体字段
    /// 文档）：`None`（未缓存）与 `Some(0)`（缓存了但标记价为 0）统一折叠成 `None`——对应 Java
    /// `priceRecord == null || priceRecord.markPrice == 0` 两个 null/零值分支合并成一个"不可用"
    /// 结果，调用方（`place_order`/`can_place_margin_order`）不用重复判两次。
    pub fn mark_price(&self, symbol: i32) -> Option<i64> {
        match self.last_price_cache.get(&symbol) {
            Some(&p) if p != 0 => Some(p),
            _ => None,
        }
    }

    /// 对应 Java `RiskEngine.preProcessCommand`（管线结构对齐重构新增）：R1 入口，按
    /// `cmd.command` 路由，镜像 Java `preProcessCommand` 的主 switch 结构：
    /// - 非交易命令（`is_non_trading()`）：整块委托（原 `ExchangeCore::dispatch_non_trading`
    ///   的逻辑搬迁至此）——`AddUser`→[`Self::add_user`]、`BalanceAdjustment`→
    ///   [`Self::balance_adjustment`]，其余（本移植子集里只有 `BinaryDataCommand`）→
    ///   `MatchingUnsupportedCommand`（未移植该命令的处理器，不 panic）。
    /// - `PlaceOrder`：调用 [`Self::place_order_risk_check`] 做风控冻结（R1 hold，现货/期货
    ///   两分支，见该方法文档）。
    /// - `ClosePosition`（P4 Task 3 新增）：调用 [`Self::close_position_risk_check`]——期货纯
    ///   减仓命令，无 NSF 检查（不开新敞口）。
    /// - `CancelOrder`/`MoveOrder`/`ReduceOrder`/`OrderBookRequest`（以及本移植子集里的
    ///   `Reset`/`Nop`）：R1 无动作，直落 ME，`cmd.result_code` 留给 ME/R2 决定——对应 Java
    ///   `preProcessCommand` 里这些分支没有风控前置校验。
    ///
    /// Java `preProcessCommand` 返回 `boolean`（disruptor 批次控制信号：是否结束当前 grouping
    /// batch），本移植单线程、无 grouping/批边界概念，该返回值无单线程语义，故此处返回 `()`。
    pub fn pre_process_command(
        &mut self,
        cmd: &mut OrderCommand,
        ups: &mut UserProfileService,
        ssp: &SymbolSpecificationProvider,
    ) {
        if cmd.command.is_non_trading() {
            let rc = match cmd.command {
                OrderCommandType::AddUser => self.add_user(cmd, ups),
                OrderCommandType::BalanceAdjustment => self.balance_adjustment(cmd, ups),
                _ => CommandResultCode::MatchingUnsupportedCommand,
            };
            cmd.result_code = Some(rc);
            return;
        }

        if cmd.command == OrderCommandType::PlaceOrder {
            cmd.result_code = Some(self.place_order_risk_check(cmd, ups, ssp));
        } else if cmd.command == OrderCommandType::ClosePosition {
            cmd.result_code = Some(self.close_position_risk_check(cmd, ups, ssp));
        }
        // CancelOrder/MoveOrder/ReduceOrder/OrderBookRequest/Reset/Nop：R1 无动作。
    }

    /// 对应 Java `placeOrderRiskCheck`（399–420）：加载 user profile（缺失 → `AuthInvalidUser`）、
    /// 加载 symbol spec（缺失 → `InvalidSymbol`），转 [`Self::place_order`] 做现货/期货分派。
    ///
    /// 省略 Java 的 `cfgIgnoreRiskProcessing` 开关——P3 未移植该配置项，恒走风控路径。
    pub fn place_order_risk_check(
        &mut self,
        cmd: &mut OrderCommand,
        ups: &mut UserProfileService,
        ssp: &SymbolSpecificationProvider,
    ) -> CommandResultCode {
        let user_profile = match ups.get_mut(cmd.uid) {
            Some(u) => u,
            None => return CommandResultCode::AuthInvalidUser,
        };
        let spec = match ssp.get_symbol(cmd.symbol) {
            Some(s) => s,
            None => return CommandResultCode::InvalidSymbol,
        };
        self.place_order(cmd, user_profile, spec, ssp)
    }

    /// 对应 Java `placeOrder`（432–503）：现货 → [`Self::place_exchange_order`]；期货
    /// （`isFuturesContract`）→ 校验（margin trading 开关/mark price/marginMode+leverage
    /// 跨腿一致）→ 解析/分配 `SymbolPositionRecord`（NSF 通过前不插入 `positions`）→ ONEWAY
    /// reduce-only 夹 → `isValidLeverage` → [`Self::can_place_margin_order`] NSF →
    /// 成功后 `pendingHold[Budget]` + 提交仓入 map。非 perp/非 spot（期权等）`unimplemented!`
    /// （P5/P6 范围）。参考文档 §3 "placeOrder 期货分支检查序"。
    ///
    /// # Rust 对齐 Java 对象池"NSF 前不插入"的做法
    /// Java 用对象池 new/put 一个可能被丢弃的 `SymbolPositionRecord`；Rust 无对象池，改为：
    /// 从 `positions` map **clone** 出已有记录（或新建一个本地默认值）到局部变量 `position`，
    /// 全程只读/只改这份本地副本，NSF 通过后再整体 `insert` 回 map（覆盖旧值或新建条目）——
    /// 既不会在 NSF 失败时污染 map，也不需要"归还池"这一步。
    fn place_order(
        &mut self,
        cmd: &mut OrderCommand,
        user_profile: &mut UserProfile,
        spec: &CoreSymbolSpecification,
        ssp: &SymbolSpecificationProvider,
    ) -> CommandResultCode {
        if spec.symbol_type == SymbolType::CurrencyExchangePair {
            let currency = if matches!(cmd.action, Some(OrderAction::Bid)) {
                spec.quote_currency
            } else {
                spec.base_currency
            };
            let currency_spec = ssp
                .get_currency(currency)
                .unwrap_or_else(|| panic!("currency spec missing for currency {currency}"));
            return self.place_exchange_order(cmd, user_profile, spec, currency_spec);
        }
        if !spec.symbol_type.is_futures_contract() {
            unimplemented!("P5/P6: option symbol type");
        }
        if !self.cfg_margin_trading_enabled {
            return CommandResultCode::RiskMarginTradingDisabled;
        }
        let mark_price = match self.mark_price(cmd.symbol) {
            Some(p) => p,
            None => return CommandResultCode::RiskMarkpriceNotAvailable,
        };
        let action = cmd.action.expect("PLACE_ORDER requires action");

        // 同 symbol 下所有仓位（ONEWAY 至多 1 条，HEDGE 至多 2 条）必须 marginMode + leverage 一致。
        if user_profile.count_position_record(spec.symbol_id, |pos| pos.margin_mode != cmd.margin_mode) > 0 {
            return CommandResultCode::RiskMarginModeMismatch;
        }
        if user_profile.count_position_record(spec.symbol_id, |pos| !pos.is_same_leverage(cmd.leverage)) > 0 {
            return CommandResultCode::RiskLeverageMismatch;
        }

        let position_key = user_profile.create_positions_key(spec.symbol_id, action, cmd.command);
        let mut position = match user_profile.positions.get(&position_key) {
            Some(existing) => existing.clone(),
            None => {
                let mut p = SymbolPositionRecord::default();
                p.initialize(user_profile.uid, spec.symbol_id, spec.quote_currency, action, cmd.leverage, cmd.margin_mode);
                p
            }
        };

        // ONEWAY + reduce-only：裁剪 size 到 ≤ 当前反向可平量；同向/无仓直接 SUCCESS no-op
        // （不开新敞口，也不必"归还"——position 只是本地副本，从未插入 map）。
        if user_profile.position_mode == PositionMode::OneWay && cmd.is_reduce_only() {
            cmd.size = Self::max_closable_size(&position, action, cmd.size);
            if cmd.size <= 0 {
                return CommandResultCode::Success;
            }
        }

        let currency_spec = ssp
            .get_currency(position.currency)
            .unwrap_or_else(|| panic!("currency spec missing for currency {}", position.currency));

        let notional = position.estimate_notional_for_order(action, cmd.size, mark_price);
        if !spec.is_valid_leverage(notional, cmd.leverage) {
            return CommandResultCode::RiskInvalidLeverage;
        }

        if !self.can_place_margin_order(cmd, user_profile, spec, &position, position_key, currency_spec, ssp) {
            return CommandResultCode::RiskNsf;
        }

        // 校验全过：pendingHold 占用（BUDGET 单 cmd.price 是总预算，用 pendingHoldBudget；
        // 普通限价 cmd.price 是单价，用 pendingHold），再 commit 本地副本回 map。
        if matches!(cmd.order_type, Some(OrderType::FokBudget) | Some(OrderType::IocBudget)) {
            position.pending_hold_budget(action, cmd.size, cmd.price);
        } else {
            position.pending_hold(action, cmd.size, cmd.price);
        }
        user_profile.positions.insert(position_key, position);

        CommandResultCode::ValidForMatchingEngine
    }

    /// 对应 Java `canPlaceMarginOrder`（`:533-623`）：期货下单 NSF 校验——五项加总（scale 到
    /// currency 后比较）与账户可支配额度比较，`true` 表示可下单。参考文档 §3.3。
    ///
    /// `position_key` 替代 Java 的对象引用 `==` 恒等比较（`posRecord == position`）：
    /// Rust 这里的 `position` 是从 `positions` map clone 出的独立副本而非同一对象，但 NSF 检查
    /// 发生在把它插回 map 之前——此时 map 里对应 `position_key` 的记录（若存在）与本地副本状态
    /// 完全一致，故用 key 相等判定"本仓"与 Java 的对象恒等语义等价。
    #[allow(clippy::too_many_arguments)] // 逐字对齐 Java canPlaceMarginOrder 的参数集，拆分反而失真
    fn can_place_margin_order(
        &self,
        cmd: &OrderCommand,
        user_profile: &UserProfile,
        spec: &CoreSymbolSpecification,
        position: &SymbolPositionRecord,
        position_key: i32,
        currency_spec: &CoreCurrencySpecification,
        ssp: &SymbolSpecificationProvider,
    ) -> bool {
        let action = cmd.action.expect("PLACE_ORDER requires action");
        let is_budget_order = matches!(cmd.order_type, Some(OrderType::FokBudget) | Some(OrderType::IocBudget));

        // ────────────────────────────────────────────────────────────
        // ① positionMargin：本仓含新挂单后的总保证金。BUDGET 单 cmd.price 已是总预算 notional；
        //   LIMIT 单需 × size。-1 哨兵（新单不扩最坏敞口）→ 回退到含现有 pending 的
        //   calculateRequiredMarginForFutures。
        // ────────────────────────────────────────────────────────────
        let order_notional = if is_budget_order { cmd.price } else { mul_exact(cmd.size, cmd.price) };
        let new_order_margin = position.calculate_required_margin_for_order(spec, action, order_notional);
        let position_margin = if new_order_margin == -1 {
            position.calculate_required_margin_for_futures(spec)
        } else {
            new_order_margin
        };

        // ────────────────────────────────────────────────────────────
        // ② crossFreeMargin（currency scale，可负）：遍历账户所有仓位。
        //   - 本仓（key == position_key）：仅 CROSS 才加自身浮盈（用本单 spec 缩放；margin 已含
        //     在 positionMargin 里，不重复减）。
        //   - 其它同 quoteCurrency 仓（含 HEDGE 对侧腿）：CROSS 加其浮盈（用其自身 otherSpec
        //     缩放），且**无论 marginMode**都减其 calculateRequiredMarginForFutures——任何仓的
        //     锁定保证金都要从可支配里剥离，只有 CROSS 的浮盈能加回来抵扣。
        // ────────────────────────────────────────────────────────────
        let mut cross_free_margin: i64 = 0;
        for (&key, pos_record) in user_profile.positions.iter() {
            if key == position_key {
                if pos_record.margin_mode == MarginMode::Cross {
                    let mark = self.mark_price(pos_record.symbol).unwrap_or_else(|| {
                        panic!("mark price missing for open position symbol {}", pos_record.symbol)
                    });
                    cross_free_margin += arithmetic::size_price_to_currency_scale(
                        pos_record.estimate_pnl(mark),
                        spec.base_scale_k,
                        spec.quote_scale_k,
                        currency_spec.currency_scale_k,
                    );
                }
            } else if pos_record.currency == spec.quote_currency {
                let other_spec = ssp
                    .get_symbol(pos_record.symbol)
                    .unwrap_or_else(|| panic!("symbol spec missing for symbol {}", pos_record.symbol));
                if pos_record.margin_mode == MarginMode::Cross {
                    let mark = self.mark_price(pos_record.symbol).unwrap_or_else(|| {
                        panic!("mark price missing for open position symbol {}", pos_record.symbol)
                    });
                    cross_free_margin += arithmetic::size_price_to_currency_scale(
                        pos_record.estimate_pnl(mark),
                        other_spec.base_scale_k,
                        other_spec.quote_scale_k,
                        currency_spec.currency_scale_k,
                    );
                }
                cross_free_margin -= arithmetic::size_price_to_currency_scale(
                    pos_record.calculate_required_margin_for_futures(other_spec),
                    other_spec.base_scale_k,
                    other_spec.quote_scale_k,
                    currency_spec.currency_scale_k,
                );
            }
        }

        // ────────────────────────────────────────────────────────────
        // ③ pendingFee：本单成交按 taker rate 预扣估算（NSF 预检用，不实收）。
        // ────────────────────────────────────────────────────────────
        let pending_fee = if is_budget_order {
            position.calculate_pending_fee_for_order_budget(spec, action, cmd.size, cmd.price)
        } else {
            position.calculate_pending_fee_for_order(spec, action, cmd.size, cmd.price)
        };

        // ────────────────────────────────────────────────────────────
        // ④ openLoss：开仓瞬间浮亏预留（防"开仓即爆仓"）。BUDGET 单跳过（成交价由撮合决定）。
        //   openingSize：ONEWAY 且反向于现仓时先抵掉 openVolume，剩余部分才是真正新开敞口；
        //   否则（同向/无仓/HEDGE）openingSize = 全部 cmd.size。
        // ────────────────────────────────────────────────────────────
        let mut open_loss: i64 = 0;
        if !is_budget_order {
            let opposite_to_pos = user_profile.position_mode == PositionMode::OneWay
                && position.open_volume > 0
                && ((action == OrderAction::Bid && position.direction == PositionDirection::Short)
                    || (action == OrderAction::Ask && position.direction == PositionDirection::Long));
            let opening_size =
                if opposite_to_pos { 0i64.max(sub_exact(cmd.size, position.open_volume)) } else { cmd.size };
            if opening_size > 0 {
                let mark_price = self
                    .mark_price(cmd.symbol)
                    .unwrap_or_else(|| panic!("mark price missing for symbol {} (checked by caller)", cmd.symbol));
                let order_cost = mul_exact(opening_size, cmd.price);
                let mark_cost = mul_exact(opening_size, mark_price);
                open_loss = match action {
                    OrderAction::Bid => 0i64.max(sub_exact(order_cost, mark_cost)),
                    OrderAction::Ask => 0i64.max(sub_exact(mark_cost, order_cost)),
                };
            }
        }

        // ────────────────────────────────────────────────────────────
        // ⑤ 比较：可支配 = accounts − 现货冻结 − 借贷抵押（loanCollateralLocked，P5 前恒 0）；
        //   需求 = scale(positionMargin + pendingFee + openLoss) − crossFreeMargin。
        // ────────────────────────────────────────────────────────────
        let currency = position.currency;
        let spendable = user_profile.account(currency) - user_profile.locked(currency)
            - self.loan_collateral_locked(user_profile, currency);
        let required = arithmetic::size_price_to_currency_scale(
            position_margin + pending_fee + open_loss,
            spec.base_scale_k,
            spec.quote_scale_k,
            currency_spec.currency_scale_k,
        ) - cross_free_margin;
        required <= spendable
    }

    /// 对应 Java `loanCollateralLocked`（`:1063-1072`）：借贷抵押锁定额，P5 移植前的 stub——
    /// 恒返回 `0`（不抵扣任何可支配额度）。占位保留调用点与签名，供 P5 落地时就地替换实现，
    /// 无需改调用方（`can_place_margin_order`/未来 `withdrawableBalance` 等）。
    fn loan_collateral_locked(&self, _user_profile: &UserProfile, _currency: i32) -> i64 {
        0
    }

    /// 对应 Java `closePositionRiskCheck`（`:823-865`）：`CLOSE_POSITION` R1——纯减仓、无新敞口
    /// math。同 `place_order` 期货分支的守卫序（symbol/futures type/margin trading 开关），
    /// 缺仓或 `maxClosableSize<=0` 一律 `SUCCESS` no-op（不下单也不报错）；有效减仓则收敛
    /// `cmd.size` 到可平量，`cmd.leverage`/`cmd.marginMode` 强制随仓（防止调用方传入与仓位不一致
    /// 的值污染 ME 事件），`pendingHold` 占用挂单量。
    pub fn close_position_risk_check(
        &mut self,
        cmd: &mut OrderCommand,
        ups: &mut UserProfileService,
        ssp: &SymbolSpecificationProvider,
    ) -> CommandResultCode {
        let user_profile = match ups.get_mut(cmd.uid) {
            Some(u) => u,
            None => return CommandResultCode::AuthInvalidUser,
        };
        let spec = match ssp.get_symbol(cmd.symbol) {
            Some(s) => s,
            None => return CommandResultCode::InvalidSymbol,
        };
        if !spec.symbol_type.is_futures_contract() {
            return CommandResultCode::UnsupportedSymbolType;
        }
        if !self.cfg_margin_trading_enabled {
            return CommandResultCode::RiskMarginTradingDisabled;
        }

        let action = cmd.action.expect("CLOSE_POSITION requires action");
        let position_key = user_profile.create_positions_key(spec.symbol_id, action, cmd.command);
        let position = match user_profile.positions.get_mut(&position_key) {
            Some(p) => p,
            None => return CommandResultCode::Success,
        };
        let close_size = Self::max_closable_size(position, action, cmd.size);
        if close_size <= 0 {
            return CommandResultCode::Success;
        }
        cmd.size = close_size;
        cmd.leverage = position.leverage;
        cmd.margin_mode = position.margin_mode;

        position.pending_hold(action, cmd.size, cmd.price);
        CommandResultCode::ValidForMatchingEngine
    }

    /// 对应 Java `maxClosableSize`（`:870-875`）：可平量——只有 `position.direction` 与
    /// `action` 反向时才有意义（`isOppositeToAction`）；`EMPTY`/同向 直接返回 `0`，让上游
    /// no-op，防止 reduce-only/CLOSE_POSITION 被误用成开新敞口。
    fn max_closable_size(pos: &SymbolPositionRecord, action: OrderAction, requested_size: i64) -> i64 {
        if !pos.direction.is_opposite_to_action(action) {
            return 0;
        }
        requested_size.min(pos.open_volume)
    }

    /// 对应 Java `placeExchangeOrder`（633–685）：现货下单冻结。BID 锁 quote，ASK 锁 base；
    /// 成功只累加 `exchange_locked`，`accounts` 不动。逐行对照参考文档 §2：
    /// - BID：reserve 价校验（BUDGET 要求 `reserve==price`，普通限价要求 `reserve>=price`，
    ///   否则 `RiskInvalidReserveBidPrice`）→ BUDGET 用
    ///   `calculate_amount_bid_taker_fee_for_budget(size, cmd.price, ...)`（`cmd.price` 是总预算），
    ///   普通限价用 `calculate_amount_bid_taker_fee(size, cmd.reserve_bid_price, ...)`（保守价，
    ///   非 `cmd.price`）→ `size_price_to_currency_scale` 缩放到 quote currency scale。
    /// - ASK：`is_ask_price_too_low` 守卫（→ `RiskAskPriceLowerThanFee`）→
    ///   `calculate_amount_ask(size) = size` → `symbol_to_currency_scale` 缩放到 base currency scale
    ///   （ASK 侧不预留 fee，从卖出 quote 收益里扣，属 R2）。
    /// - NSF：`accounts[currency] - exchange_locked[currency] - order_lock_amount < 0` →
    ///   `RiskNsf`（现货子集：无期货净保证金、无借贷抵押扣减，二者属 P4/Task 8+ 范围）。
    /// - 成功：`user_profile.add_to_locked(currency, +order_lock_amount)`，返回
    ///   `ValidForMatchingEngine`。
    fn place_exchange_order(
        &mut self,
        cmd: &OrderCommand,
        user_profile: &mut UserProfile,
        spec: &CoreSymbolSpecification,
        currency_spec: &CoreCurrencySpecification,
    ) -> CommandResultCode {
        let is_bid = matches!(cmd.action, Some(OrderAction::Bid));
        let currency = if is_bid { spec.quote_currency } else { spec.base_currency };
        let size = cmd.size;

        let order_lock_amount = if is_bid {
            let is_budget =
                matches!(cmd.order_type, Some(OrderType::FokBudget) | Some(OrderType::IocBudget));
            let raw = if is_budget {
                if cmd.reserve_bid_price != cmd.price {
                    return CommandResultCode::RiskInvalidReserveBidPrice;
                }
                arithmetic::calculate_amount_bid_taker_fee_for_budget(
                    size,
                    cmd.price,
                    spec.taker_fee,
                    spec.fee_scale_k,
                )
            } else {
                if cmd.reserve_bid_price < cmd.price {
                    return CommandResultCode::RiskInvalidReserveBidPrice;
                }
                arithmetic::calculate_amount_bid_taker_fee(
                    size,
                    cmd.reserve_bid_price,
                    spec.taker_fee,
                    spec.fee_scale_k,
                )
            };
            arithmetic::size_price_to_currency_scale(
                raw,
                spec.base_scale_k,
                spec.quote_scale_k,
                currency_spec.currency_scale_k,
            )
        } else {
            if arithmetic::is_ask_price_too_low(cmd.price, spec.taker_fee, spec.fee_scale_k) {
                return CommandResultCode::RiskAskPriceLowerThanFee;
            }
            let raw = arithmetic::calculate_amount_ask(size);
            arithmetic::symbol_to_currency_scale(raw, spec.base_scale_k, currency_spec.currency_scale_k)
        };

        let balance = user_profile.account(currency);
        let existing_locked = user_profile.locked(currency);
        // 现货子集：无 freeFuturesMargin（期货净保证金抵扣）、无 loanLocked（借贷抵押扣减）——
        // 二者分别属 P4（margin）与 Task 8+（loan）范围，参考文档 §2 已明确"spot: no futures
        // margin, no loan term"。
        if balance - existing_locked - order_lock_amount < 0 {
            return CommandResultCode::RiskNsf;
        }
        user_profile.add_to_locked(currency, order_lock_amount);
        CommandResultCode::ValidForMatchingEngine
    }

    /// 对应 Java `handlerRiskRelease`（885–1023，现货分支 922–945）：R2 撮合后置分派骨架。
    /// 参考文档 §3。链头 REJECT/REDUCE 释放（Task 5）+ TRADE 链 sell 结算（Task 6，
    /// `handle_matcher_events_exchange_sell`）已落地；buy 结算留给 Task 7（`// TODO` 占位）。
    ///
    /// # 非交易命令 no-op 守卫（管线结构对齐 Java 之后新增）
    /// `ExchangeCore::process_command` 重构后，**所有**命令（含 `AddUser`/`BalanceAdjustment` 等
    /// 非交易命令）都会依次流过 R1→ME→R2 三段（对齐 Java 全命令过 disruptor 三阶段的结构）。
    /// 非交易命令没有 symbol/matcher_event 语义——`cmd.symbol` 对 `BalanceAdjustment` 是**币种 id**
    /// 而非 symbol id，若不提前拦截，下面 `ssp.get_symbol(cmd.symbol)` 会用币种 id 当 symbol id
    /// 查（大概率 panic 或查到无关 symbol）。因此在最前面显式短路：非交易命令在 R1
    /// （`pre_process_command`）已经把结果写进 `cmd.result_code`，R2 在这里直接返回、不做任何
    /// 改动。（备注：即使没有这条显式 guard，非交易命令因为从未被设置过 `matcher_event`，也会被
    /// 紧随其后的 `cmd.matcher_event.take() == None` 分支挡住——这条 guard 是双保险 + 把"非交易
    /// 命令在 R2 无意义"这件事写进代码里，而非依赖一个巧合成立的空值检查。）
    ///
    /// 借用设计（vs. Java `takerUp = getUserProfileOrAddSuspended(cmd.uid)` 提前一次性取）：本期
    /// 单 shard 恒 `uidForThisHandler == true`，但如果在这里先 `ups.get_or_add_suspended(cmd.uid)`
    /// 拿到 `&mut UserProfile` 再传给下游 helper，sell 结算里额外借出 maker profile 时会与这个
    /// 长生命周期借用冲突。故改为把 `&mut UserProfileService` 传进 helper，helper 内部按 uid
    /// 现取，且只在各自需要的短作用域内持有——taker 与 maker（甚至自成交时同一 uid）都不会
    /// 出现两个 `&mut` 同时借用同一 `UserProfile` 的冲突。
    ///
    /// `fees` 对应 Java 的平台手续费累计桶（`RiskEngine.fees`），Task 8 落地为本结构体字段
    /// （之前以 `&mut` 参数形式由调用方持有）。`handle_matcher_events_exchange_sell/buy` 仍是
    /// 关联函数、以 `&mut BTreeMap<i32,i64>` 参数接收 fees（Task 8 brief 的推荐做法）——这里
    /// 用 `&mut self.fees` 分裂借用后传入，与 `ups`/`ssp`（调用方另行传入、非 `self` 字段）互不冲突，
    /// 避免 `&mut self` 方法内部再借 `&mut UserProfileService` 时产生自借用冲突。
    pub fn handler_risk_release(
        &mut self,
        cmd: &mut OrderCommand,
        ups: &mut UserProfileService,
        ssp: &SymbolSpecificationProvider,
    ) {
        if cmd.command.is_non_trading() {
            return;
        }
        let fees = &mut self.fees;
        let mut mte = match cmd.matcher_event.take() {
            Some(m) => m,
            None => return,
        };
        if mte.event_type == MatcherEventType::BinaryEvent {
            cmd.matcher_event = Some(mte);
            return;
        }
        let spec = ssp
            .get_symbol(cmd.symbol)
            .unwrap_or_else(|| panic!("symbol spec missing for symbol {}", cmd.symbol))
            .clone();
        if spec.symbol_type != SymbolType::CurrencyExchangePair {
            unimplemented!("P4: futures R2");
        }
        let taker_sell = matches!(cmd.action, Some(OrderAction::Ask));

        // REJECT 总在链头；REDUCE 单独成事件，同样只可能出现在链头。
        let next: Option<Box<MatcherTradeEvent>> =
            if mte.event_type == MatcherEventType::Reduce
                || mte.event_type == MatcherEventType::Reject
            {
                let currency = if taker_sell { spec.base_currency } else { spec.quote_currency };
                let currency_spec = ssp
                    .get_currency(currency)
                    .unwrap_or_else(|| panic!("currency spec missing for currency {currency}"))
                    .clone();
                let taker_up = ups.get_or_add_suspended(cmd.uid);
                Self::handle_matcher_reject_reduce_event_exchange(
                    cmd,
                    &mte,
                    &spec,
                    &currency_spec,
                    taker_sell,
                    taker_up,
                );
                mte.next.take()
            } else {
                Some(mte)
            };

        if let Some(remaining) = next {
            if taker_sell {
                let base_currency_spec = ssp
                    .get_currency(spec.base_currency)
                    .unwrap_or_else(|| {
                        panic!("currency spec missing for currency {}", spec.base_currency)
                    })
                    .clone();
                let quote_currency_spec = ssp
                    .get_currency(spec.quote_currency)
                    .unwrap_or_else(|| {
                        panic!("currency spec missing for currency {}", spec.quote_currency)
                    })
                    .clone();
                Self::handle_matcher_events_exchange_sell(
                    cmd,
                    remaining,
                    &spec,
                    &base_currency_spec,
                    &quote_currency_spec,
                    ups,
                    fees,
                );
                // TRADE 链已完全结算消费，不回填 cmd.matcher_event（对齐 REJECT/REDUCE 消费后清空的模式）。
            } else {
                let base_currency_spec = ssp
                    .get_currency(spec.base_currency)
                    .unwrap_or_else(|| {
                        panic!("currency spec missing for currency {}", spec.base_currency)
                    })
                    .clone();
                let quote_currency_spec = ssp
                    .get_currency(spec.quote_currency)
                    .unwrap_or_else(|| {
                        panic!("currency spec missing for currency {}", spec.quote_currency)
                    })
                    .clone();
                Self::handle_matcher_events_exchange_buy(
                    cmd,
                    remaining,
                    &spec,
                    &base_currency_spec,
                    &quote_currency_spec,
                    ups,
                    fees,
                );
                // TRADE 链已完全结算消费，不回填 cmd.matcher_event（对齐 sell 分支）。
            }
        }
    }

    /// 对应 Java `handleMatcherRejectReduceEventExchange`（1094–1125）：撤单/拒单释放冻结，
    /// 只涉及单方（active 单的 owner），accounts 不动。参考文档 §3a：
    /// - `currency = taker_sell ? base : quote`。
    /// - ASK：`release = symbol_to_currency_scale(calculate_amount_ask(mte.size), ...)` = mte.size
    ///   缩放（残量按 base 数量直退，下单时也是按 size 直冻，无 fee 预留）。
    /// - BID 按订单类型：
    ///   - `PLACE_ORDER` + `FOK_BUDGET`：`calculate_amount_bid_taker_fee_for_budget(mte.size, mte.price, ...)`
    ///     （FOK 只可能整单成交或整单拒绝，`mte.price` 就是原始预算）。
    ///   - `IOC_BUDGET` 且 `mte.next.is_none()`（全拒，无前置成交）：
    ///     `calculate_amount_bid_taker_fee_for_budget(cmd.size, cmd.price, ...)`（释放整份预算）。
    ///   - `IOC_BUDGET` 有前置 TRADE（部分成交后剩余的 REDUCE）：`release_sp = 0`——BUY 结算
    ///     （Task 7）已经把整份预算的锁定释放过一次，这里再释放就是双重释放，破坏守恒。
    ///   - 普通限价：`calculate_amount_bid_taker_fee(mte.size, mte.bidder_hold_price, ...)`
    ///     （用下单时实际冻结所用的保守价 `bidder_hold_price`，而非 `cmd.price`）。
    ///   - 缩放：`size_price_to_currency_scale(release_sp, ...)`。
    /// - 应用：`user_profile.add_to_locked(currency, -release)`；accounts 不动。
    ///   （Java 侧 `release>0` 才发 `sendUnLockEvent`——P3 未移植事件总线，此处不发事件，
    ///   纯记账副作用。）
    fn handle_matcher_reject_reduce_event_exchange(
        cmd: &OrderCommand,
        mte: &MatcherTradeEvent,
        spec: &CoreSymbolSpecification,
        currency_spec: &CoreCurrencySpecification,
        taker_sell: bool,
        taker_up: &mut UserProfile,
    ) {
        let currency = if taker_sell { spec.base_currency } else { spec.quote_currency };

        let release = if taker_sell {
            let raw = arithmetic::calculate_amount_ask(mte.size);
            arithmetic::symbol_to_currency_scale(raw, spec.base_scale_k, currency_spec.currency_scale_k)
        } else {
            let is_fok_budget = cmd.command == OrderCommandType::PlaceOrder
                && matches!(cmd.order_type, Some(OrderType::FokBudget));
            let is_ioc_budget = matches!(cmd.order_type, Some(OrderType::IocBudget));
            let release_sp = if is_fok_budget {
                arithmetic::calculate_amount_bid_taker_fee_for_budget(
                    mte.size,
                    mte.price,
                    spec.taker_fee,
                    spec.fee_scale_k,
                )
            } else if is_ioc_budget && mte.next.is_none() {
                arithmetic::calculate_amount_bid_taker_fee_for_budget(
                    cmd.size,
                    cmd.price,
                    spec.taker_fee,
                    spec.fee_scale_k,
                )
            } else if is_ioc_budget {
                0
            } else {
                arithmetic::calculate_amount_bid_taker_fee(
                    mte.size,
                    mte.bidder_hold_price,
                    spec.taker_fee,
                    spec.fee_scale_k,
                )
            };
            arithmetic::size_price_to_currency_scale(
                release_sp,
                spec.base_scale_k,
                spec.quote_scale_k,
                currency_spec.currency_scale_k,
            )
        };

        taker_up.add_to_locked(currency, -release);
    }

    /// 对应 Java `handleMatcherEventsExchangeSell`（1134–1227）：taker 卖(ASK)、maker 买(BID) 的
    /// 现货成交结算。参考文档 §3b。两阶段：
    /// 1. 逐 TRADE 事件（`next` 链）结算本 shard maker（释放 quote 冻结 + 扣 quote 实付 + 入 base），
    ///    同时把 `size`/`size*price`（`i128`，防 `size*price` 累加溢出 `i64`——单笔 `mul_exact`
    ///    仍在 Task 1 里做 `i64` 溢出检查，这里只是多笔累加的容器更宽）累加进 taker/maker 的
    ///    notional/size 局部变量。
    /// 2. 循环结束后一次性结算 taker（释放 base 冻结 + 扣 base 实付 + 入 quote = notional −
    ///    taker 费，用聚合 `avg_taker_price` 重算一次 taker 费，非逐笔求和）+ 用
    ///    `avg_maker_price` 重算一次平台费入账（Java 1218-1220 注释：逐笔 ceil 会比合并算多产生
    ///    dust，均价单次结算是刻意的不对称，dust 沉在 `exchange_locked`，不在本任务处理）。
    ///
    /// 借用设计：不预先长期持有任何 `UserProfile` 的 `&mut`——每个 maker、以及最后的 taker，
    /// 都各自只在自己的一段短作用域里 `ups.get_or_add_suspended(uid)`；循环体内只把结果累加成
    /// `i64`/`i128` 原语存到局部变量。这样即使 taker 与某个 maker 恰好同 uid（自成交），也不会
    /// 出现两个 `&mut` 同时借用同一 `UserProfile` 的编译错误——它们是先后发生、不重叠的借用。
    fn handle_matcher_events_exchange_sell(
        cmd: &OrderCommand,
        first_trade_mte: Box<MatcherTradeEvent>,
        spec: &CoreSymbolSpecification,
        base_currency_spec: &CoreCurrencySpecification,
        quote_currency_spec: &CoreCurrencySpecification,
        ups: &mut UserProfileService,
        fees: &mut BTreeMap<i32, i64>,
    ) {
        let base_currency = spec.base_currency;
        let quote_currency = spec.quote_currency;

        let mut taker_notional: i128 = 0;
        let mut taker_size: i64 = 0;
        let mut maker_notional: i128 = 0;
        let mut maker_size: i64 = 0;

        let mut node = Some(first_trade_mte);
        while let Some(ev) = node {
            debug_assert_eq!(ev.event_type, MatcherEventType::Trade);

            // taker 恒本 shard（单 shard 简化，对应 Java 的 `takerUp != null` 恒真）。
            taker_notional += ev.size as i128 * ev.price as i128;
            taker_size += ev.size;

            // maker 恒本 shard（单 shard 简化，对应 Java 的 `uidForThisHandler` 恒真）。
            {
                let maker_up = ups.get_or_add_suspended(ev.matched_order_uid);

                // maker 挂 BID 时按 taker 费率 + 保守价（bidder_hold_price）冻结的原始 quote。
                let hold_quote_raw = arithmetic::calculate_amount_bid_taker_fee(
                    ev.size,
                    ev.bidder_hold_price,
                    spec.taker_fee,
                    spec.fee_scale_k,
                );
                let hold_quote = arithmetic::size_price_to_currency_scale(
                    hold_quote_raw,
                    spec.base_scale_k,
                    spec.quote_scale_k,
                    quote_currency_spec.currency_scale_k,
                );

                // 价格改善 + taker→maker 费率差退款。
                let quote_refund_raw = arithmetic::calculate_amount_bid_release_corr_maker(
                    ev.size,
                    ev.bidder_hold_price,
                    ev.price,
                    spec.taker_fee,
                    spec.maker_fee,
                    spec.fee_scale_k,
                );
                let quote_refund = arithmetic::size_price_to_currency_scale(
                    quote_refund_raw,
                    spec.base_scale_k,
                    spec.quote_scale_k,
                    quote_currency_spec.currency_scale_k,
                );

                maker_up.add_to_locked(quote_currency, -hold_quote);
                // 净 quote 变动 = quote_refund − hold_quote = −(size·price + makerFee)。
                maker_up.add_to_account(quote_currency, quote_refund - hold_quote);

                // calculateAmountAsk(size) = size：ASK 侧不收费（费用走 quote 侧），maker 收到的
                // base 数量就是成交量本身。
                let base_gained = arithmetic::symbol_to_currency_scale(
                    arithmetic::calculate_amount_ask(ev.size),
                    spec.base_scale_k,
                    base_currency_spec.currency_scale_k,
                );
                maker_up.add_to_account(base_currency, base_gained);
            }

            maker_notional += ev.size as i128 * ev.price as i128;
            maker_size += ev.size;

            node = ev.next;
        }

        // hoist：taker_fee 在 taker 结算块和下面 fees 池都要用，避免重复算一次 ceil。
        let avg_taker_price = if taker_size > 0 {
            i64::try_from(taker_notional / taker_size as i128)
                .unwrap_or_else(|_| panic!("overflow narrowing avg_taker_price"))
        } else {
            0
        };
        let taker_fee = arithmetic::calculate_taker_fee(
            taker_size,
            avg_taker_price,
            spec.taker_fee,
            spec.fee_scale_k,
        );

        {
            let taker_up = ups.get_or_add_suspended(cmd.uid);

            // taker 是卖方：释放 base 冻结、实际扣 base；加 quote = notional − takerFee。
            let base_paid = arithmetic::symbol_to_currency_scale(
                arithmetic::calculate_amount_ask(taker_size),
                spec.base_scale_k,
                base_currency_spec.currency_scale_k,
            );
            taker_up.add_to_locked(base_currency, -base_paid);
            taker_up.add_to_account(base_currency, -base_paid);

            let net_notional_raw = i64::try_from(taker_notional - taker_fee as i128)
                .unwrap_or_else(|_| panic!("overflow narrowing taker net notional"));
            let to_be_added = arithmetic::size_price_to_currency_scale(
                net_notional_raw,
                spec.base_scale_k,
                spec.quote_scale_k,
                quote_currency_spec.currency_scale_k,
            );
            taker_up.add_to_account(quote_currency, to_be_added);
        }

        if taker_size != 0 || maker_size != 0 {
            // fees 池入账用 avg-price 重算 takerFee+makerFee 后做单次 sizePriceToCurrencyScale，
            // 避免 per-event ceil + 多次 scale 转换累积 dust（与 maker 块的 per-event 截断不对称是
            // 有意的：单笔 dust 沉积在 exchangeLocked，SUSPEND 时 sweep 到 fees，全局守恒——
            // sweep 路径属 Task 8+，本任务不处理）。
            let avg_maker_price = if maker_size > 0 {
                i64::try_from(maker_notional / maker_size as i128)
                    .unwrap_or_else(|_| panic!("overflow narrowing avg_maker_price"))
            } else {
                0
            };
            let maker_fee = arithmetic::calculate_maker_fee(
                maker_size,
                avg_maker_price,
                spec.maker_fee,
                spec.fee_scale_k,
            );

            let fee_sum = taker_fee
                .checked_add(maker_fee)
                .unwrap_or_else(|| panic!("overflow: taker_fee + maker_fee"));
            let fee_scaled = arithmetic::size_price_to_currency_scale(
                fee_sum,
                spec.base_scale_k,
                spec.quote_scale_k,
                quote_currency_spec.currency_scale_k,
            );
            *fees.entry(quote_currency).or_insert(0) += fee_scaled;
        }
    }

    /// 对应 Java `handleMatcherEventsExchangeBuy`（1238–1343）：taker 买(BID)、maker 卖(ASK) 的
    /// 现货成交结算——`handle_matcher_events_exchange_sell` 的镜像。参考文档 §3c。两阶段：
    /// 1. 逐 TRADE 事件结算本 shard maker（释放 base 冻结 + 扣 base 实付 + 入 quote = 名义 −
    ///    maker 费），同时把 `size`/`size*price`/`size*bidder_hold_price` 累加进 taker/maker 的
    ///    notional/size 局部变量（`i128`，理由同 sell：防多笔累加溢出 `i64`，单笔算术仍是
    ///    `i64` `mul_exact`）。
    /// 2. 循环结束后一次性结算 taker（BUDGET 与普通限价两条子路径分别算 `leftover`/`hold_quote`，
    ///    见下）+ 用 `avg_maker_price` 重算一次平台费入账（与 sell 对称：dust 沉 exchange_locked，
    ///    不在本任务处理）。
    ///
    /// taker 两条子路径（Java 1310–1325）：
    /// - BUDGET（`PLACE_ORDER` + `FOK_BUDGET`/`IOC_BUDGET`）：下单时冻结的是预算上限
    ///   `held_total = calculate_amount_bid_taker_fee_for_budget(cmd.size, cmd.price, ...)`
    ///   （`cmd.price` 是总预算，非单价）——本次 `hold_quote` 就是这个 `held_total` 的原样缩放，
    ///   即**释放当初锁定的全部预算**（无论本次是否全部成交）。`leftover = held_total −
    ///   (taker_notional + taker_fee)` 是"预算 − 实际花费"的剩余部分；同时把
    ///   `taker_hold_notional` 重赋值为 `taker_notional`（BUDGET 单没有逐笔 `bidder_hold_price`
    ///   概念，退款公式统一走 `hold_notional − notional + leftover`）。
    /// - 普通限价：冻结是"逐笔 `bidder_hold_price`"的聚合（`taker_hold_notional`），按
    ///   `taker_hold_notional/taker_size` 均价重算一次 `fee_held`，`leftover = fee_held −
    ///   taker_fee` 是"冻结费 − 实收费"的差额，`hold_quote = scale(taker_hold_notional +
    ///   fee_held)`。
    ///
    /// 两条路径统一收尾：`quote_refund = scale(taker_hold_notional − taker_notional +
    /// leftover)`（价差 + leftover）；`exchange_locked[quote] −= hold_quote`；
    /// `accounts[quote] += quote_refund − hold_quote`（净 = `−(notional + taker_fee)`）；
    /// `accounts[base] += scale(taker_size)`（taker 买方得 base，base 腿无费）。
    ///
    /// **Task 5/7 一致性**（`handle_matcher_reject_reduce_event_exchange` 的 IOC_BUDGET
    /// "有前置 TRADE → release_sp=0" 分支依赖的前提）：上面 BUDGET 子路径的 `hold_quote` 恒等于
    /// `scale(held_total)`——即 Task 4 下单时锁定的**整份预算**，与本次是否部分成交无关。因此
    /// IOC_BUDGET 部分成交后，这里已经把整份预算的锁定全额释放（连同价内差额一起退回
    /// `quote_refund`），Task 5 链头 REDUCE 若再释放非零金额就是双重释放——`release_sp=0` 与此处
    /// 严格对齐，见下方 `bid_ioc_budget_partial_fill_full_budget_release_matches_task5_zero_release`
    /// 测试的显式验证。
    fn handle_matcher_events_exchange_buy(
        cmd: &OrderCommand,
        first_trade_mte: Box<MatcherTradeEvent>,
        spec: &CoreSymbolSpecification,
        base_currency_spec: &CoreCurrencySpecification,
        quote_currency_spec: &CoreCurrencySpecification,
        ups: &mut UserProfileService,
        fees: &mut BTreeMap<i32, i64>,
    ) {
        let base_currency = spec.base_currency;
        let quote_currency = spec.quote_currency;

        let mut taker_notional: i128 = 0;
        let mut taker_hold_notional: i128 = 0;
        let mut taker_size: i64 = 0;
        let mut maker_notional: i128 = 0;
        let mut maker_size: i64 = 0;

        let mut node = Some(first_trade_mte);
        while let Some(ev) = node {
            debug_assert_eq!(ev.event_type, MatcherEventType::Trade);

            // taker 恒本 shard（单 shard 简化，对应 Java 的 `takerUp != null` 恒真）。
            taker_notional += ev.size as i128 * ev.price as i128;
            taker_hold_notional += ev.size as i128 * ev.bidder_hold_price as i128;
            taker_size += ev.size;

            // maker 恒本 shard（单 shard 简化，对应 Java 的 `uidForThisHandler` 恒真）。
            {
                let maker_up = ups.get_or_add_suspended(ev.matched_order_uid);

                // calculateAmountBid(size, price) = size × price：原始成交对价（未扣 maker fee）。
                let quote_gained = arithmetic::calculate_amount_bid(ev.size, ev.price);

                // maker 是卖方，下单时按 base 数量直接冻结（calculateAmountAsk(size) = size），
                // 这里全释放并实际扣 base。
                let base_paid = arithmetic::symbol_to_currency_scale(
                    arithmetic::calculate_amount_ask(ev.size),
                    spec.base_scale_k,
                    base_currency_spec.currency_scale_k,
                );
                maker_up.add_to_locked(base_currency, -base_paid);
                maker_up.add_to_account(base_currency, -base_paid);

                // maker 收到 quote = 成交对价 − maker fee。
                let fee = arithmetic::calculate_maker_fee(
                    ev.size,
                    ev.price,
                    spec.maker_fee,
                    spec.fee_scale_k,
                );
                let to_be_added = arithmetic::size_price_to_currency_scale(
                    quote_gained - fee,
                    spec.base_scale_k,
                    spec.quote_scale_k,
                    quote_currency_spec.currency_scale_k,
                );
                maker_up.add_to_account(quote_currency, to_be_added);
            }

            maker_notional += ev.size as i128 * ev.price as i128;
            maker_size += ev.size;

            node = ev.next;
        }

        // hoist：taker_fee 在 taker 结算块和下面 fees 池都要用，避免重复算一次 ceil。
        let avg_taker_price = if taker_size > 0 {
            i64::try_from(taker_notional / taker_size as i128)
                .unwrap_or_else(|_| panic!("overflow narrowing avg_taker_price"))
        } else {
            0
        };
        let taker_fee = arithmetic::calculate_taker_fee(
            taker_size,
            avg_taker_price,
            spec.taker_fee,
            spec.fee_scale_k,
        );

        {
            let taker_notional_i64 = i64::try_from(taker_notional)
                .unwrap_or_else(|_| panic!("overflow narrowing taker_notional"));

            let is_budget = cmd.command == OrderCommandType::PlaceOrder
                && matches!(cmd.order_type, Some(OrderType::FokBudget) | Some(OrderType::IocBudget));

            // effective_hold_notional：BUDGET 路径重赋值为 taker_notional（见下），普通路径保持
            // 逐笔 bidder_hold_price 聚合——两条路径收尾用同一条 quote_refund 公式。
            let (leftover, hold_quote, effective_hold_notional) = if is_budget {
                // FOK_BUDGET/IOC_BUDGET：冻结的是预算上限 heldTotal，未匹配部分 leftover 原样退。
                let held_total = arithmetic::calculate_amount_bid_taker_fee_for_budget(
                    cmd.size,
                    cmd.price,
                    spec.taker_fee,
                    spec.fee_scale_k,
                );
                let leftover = held_total - (taker_notional_i64 + taker_fee);
                let hold_quote = arithmetic::size_price_to_currency_scale(
                    held_total,
                    spec.base_scale_k,
                    spec.quote_scale_k,
                    quote_currency_spec.currency_scale_k,
                );
                (leftover, hold_quote, taker_notional_i64)
            } else {
                // 普通单：feeHeld 按 bidderHoldPrice 均价冻、takerFee 按实际成交均价收，差额
                // leftover 退给用户。
                let taker_hold_notional_i64 = i64::try_from(taker_hold_notional)
                    .unwrap_or_else(|_| panic!("overflow narrowing taker_hold_notional"));
                let avg_hold_price = taker_hold_notional_i64 / taker_size;
                let fee_held = arithmetic::calculate_taker_fee(
                    taker_size,
                    avg_hold_price,
                    spec.taker_fee,
                    spec.fee_scale_k,
                );
                let leftover = fee_held - taker_fee;
                let hold_quote = arithmetic::size_price_to_currency_scale(
                    taker_hold_notional_i64 + fee_held,
                    spec.base_scale_k,
                    spec.quote_scale_k,
                    quote_currency_spec.currency_scale_k,
                );
                (leftover, hold_quote, taker_hold_notional_i64)
            };

            // 价差(holdNotional − notional) + leftover = 应退 quote。
            let quote_refund = arithmetic::size_price_to_currency_scale(
                effective_hold_notional - taker_notional_i64 + leftover,
                spec.base_scale_k,
                spec.quote_scale_k,
                quote_currency_spec.currency_scale_k,
            );

            let taker_up = ups.get_or_add_suspended(cmd.uid);

            taker_up.add_to_locked(quote_currency, -hold_quote);
            // 净 +(refund − hold) = −实付 = −(notional + takerFee)。
            taker_up.add_to_account(quote_currency, quote_refund - hold_quote);

            let to_be_added = arithmetic::symbol_to_currency_scale(
                taker_size,
                spec.base_scale_k,
                base_currency_spec.currency_scale_k,
            );
            taker_up.add_to_account(base_currency, to_be_added);
        }

        if taker_size != 0 || maker_size != 0 {
            let avg_maker_price = if maker_size > 0 {
                i64::try_from(maker_notional / maker_size as i128)
                    .unwrap_or_else(|_| panic!("overflow narrowing avg_maker_price"))
            } else {
                0
            };
            let maker_fee = arithmetic::calculate_maker_fee(
                maker_size,
                avg_maker_price,
                spec.maker_fee,
                spec.fee_scale_k,
            );

            let fee_sum = taker_fee
                .checked_add(maker_fee)
                .unwrap_or_else(|| panic!("overflow: taker_fee + maker_fee"));
            let fee_scaled = arithmetic::size_price_to_currency_scale(
                fee_sum,
                spec.base_scale_k,
                spec.quote_scale_k,
                quote_currency_spec.currency_scale_k,
            );
            *fees.entry(quote_currency).or_insert(0) += fee_scaled;
        }
    }

    /// 对应 Java `RiskEngineCommandDispatcher.addUser`（177–181）：`UserProfileService
    /// .addEmptyUserProfile` 建空 `UserProfile(uid, ACTIVE)`；已存在 → `UserMgmtUserAlreadyExists`。
    /// `uidForThisHandler` 分片门本期未移植（单 shard、恒真），由调用方（Task 10 引擎）决定是否
    /// 派发到这里。
    pub fn add_user(&mut self, cmd: &OrderCommand, ups: &mut UserProfileService) -> CommandResultCode {
        ups.add_empty_user_profile(cmd.uid)
    }

    /// 对应 Java `RiskEngineCommandDispatcher.adjustBalance`（183–211）+ `UserProfileService
    /// .balanceAdjustment`（71–89）两层校验叠加；参考文档 §5：
    /// - `currency = cmd.symbol`、`amount_diff = cmd.price`；用户不存在 → `AuthInvalidUser`。
    /// - **外层（dispatcher）现货 NSF**：`withdrawable = account(cur) - locked(cur)`；提现
    ///   （`amount_diff < 0`）时 `withdrawable + amount_diff < 0` → `RiskNsf`。
    /// - **内层（`UserProfileService.balanceAdjustment`）NSF**：`amount_diff < 0 &&
    ///   account(cur) + amount_diff < 0` → `UserMgmtAccountBalanceAdjustmentNsf`。给定外层已通过，
    ///   此内层对 `BALANCE_ADJUSTMENT` 恒不可达（`locked >= 0` ⟹ `account + amount_diff =
    ///   account - withdrawal_amount >= locked >= 0`）——Java 两层都在（内层是 `UserProfileService`
    ///   的通用防线，供 `MARGIN_ADJUSTMENT`/`INTERNAL_TRANSFER` 等未经过外层现货 NSF 的调用方共用，
    ///   本移植未含那些调用方），逐字复刻两层顺序而非合并，为未来接线这些调用方留出正确的插入点。
    /// - **幂等**：`cmd.order_id` 在 `UserProfile.processed_tx_ids` 里 claim；已存在 →
    ///   `UserMgmtAccountBalanceAdjustmentAlreadyAppliedSame`。**两条 NSF 路径都在 claim 之前
    ///   return，不 claim id**——NSF 后调用方用修正过的金额重试同一 `order_id` 必须放行（Java
    ///   注释："NSF 时不 claim id，避免污染后续修正重试"）。
    /// - **成功**：`account(cur) += amount_diff`；随后（对应 dispatcher `applyBalanceAdjustment`
    ///   的 `ADJUSTMENT` 分支）`adjustments[cur] -= amount_diff`——`Σ account[cur] + adjustments[cur]`
    ///   恒定。`BalanceAdjustmentType::Suspend` 对冲桶延后（`RiskEngine` 无 `suspends` 字段，
    ///   见结构体注释），本任务恒按 `Adjustment` 型处理。
    pub fn balance_adjustment(
        &mut self,
        cmd: &OrderCommand,
        ups: &mut UserProfileService,
    ) -> CommandResultCode {
        let currency = cmd.symbol;
        let amount_diff = cmd.price;

        let user_profile = match ups.get_mut(cmd.uid) {
            Some(u) => u,
            None => return CommandResultCode::AuthInvalidUser,
        };

        if amount_diff < 0 {
            let withdrawal_amount = -amount_diff;
            let withdrawable = user_profile.account(currency) - user_profile.locked(currency);
            if withdrawable - withdrawal_amount < 0 {
                return CommandResultCode::RiskNsf;
            }
        }

        if amount_diff < 0 && user_profile.account(currency) + amount_diff < 0 {
            return CommandResultCode::UserMgmtAccountBalanceAdjustmentNsf;
        }

        if !user_profile.try_claim_tx(cmd.order_id) {
            return CommandResultCode::UserMgmtAccountBalanceAdjustmentAlreadyAppliedSame;
        }

        user_profile.add_to_account(currency, amount_diff);

        *self.adjustments.entry(currency).or_insert(0) -= amount_diff;

        CommandResultCode::Success
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::core::common::cmd::order_command::OrderCommand;
    use crate::core::common::cmd::order_command_type::OrderCommandType;
    use crate::core::common::symbol_type::SymbolType;

    const BASE: i32 = 1;
    const QUOTE: i32 = 2;
    const SYMBOL: i32 = 100;
    const UID: i64 = 7;

    fn spec_with_fee(taker_fee: i64, fee_scale_k: i64) -> CoreSymbolSpecification {
        CoreSymbolSpecification {
            symbol_id: SYMBOL,
            symbol_type: SymbolType::CurrencyExchangePair,
            base_currency: BASE,
            quote_currency: QUOTE,
            base_scale_k: 100,
            quote_scale_k: 1_000_000,
            taker_fee,
            maker_fee: 0,
            fee_scale_k,
            ..Default::default()
        }
    }

    /// 搭建：一个现货 symbol（BASE/QUOTE，base_scale_k=100/quote_scale_k=1_000_000，
    /// 均非平凡缩放）+ 两种 currency spec（base currency_scale_k=100 对齐 base_scale_k，
    /// ASK 侧缩放天然恒等；quote currency_scale_k=1_000_000 < 乘积 scale 1e8，BID 侧缩放非恒等）
    /// + 一个已建档、按需充值的用户。
    fn setup(
        taker_fee: i64,
        fee_scale_k: i64,
        quote_balance: i64,
        base_balance: i64,
    ) -> (UserProfileService, SymbolSpecificationProvider) {
        let mut ssp = SymbolSpecificationProvider::new();
        assert_eq!(
            ssp.add_symbol(spec_with_fee(taker_fee, fee_scale_k)),
            CommandResultCode::Success
        );
        ssp.add_currency(CoreCurrencySpecification { currency: BASE, currency_scale_k: 100 });
        ssp.add_currency(CoreCurrencySpecification {
            currency: QUOTE,
            currency_scale_k: 1_000_000,
        });

        let mut ups = UserProfileService::new();
        assert_eq!(ups.add_empty_user_profile(UID), CommandResultCode::Success);
        let p = ups.get_mut(UID).unwrap();
        p.add_to_account(QUOTE, quote_balance);
        p.add_to_account(BASE, base_balance);
        (ups, ssp)
    }

    fn bid_cmd(size: i64, price: i64, reserve_bid_price: i64, order_type: OrderType) -> OrderCommand {
        OrderCommand {
            command: OrderCommandType::PlaceOrder,
            order_id: 1,
            symbol: SYMBOL,
            price,
            size,
            reserve_bid_price,
            action: Some(OrderAction::Bid),
            order_type: Some(order_type),
            uid: UID,
            ..Default::default()
        }
    }

    fn ask_cmd(size: i64, price: i64) -> OrderCommand {
        OrderCommand {
            command: OrderCommandType::PlaceOrder,
            order_id: 1,
            symbol: SYMBOL,
            price,
            size,
            reserve_bid_price: 0,
            action: Some(OrderAction::Ask),
            order_type: Some(OrderType::Gtc),
            uid: UID,
            ..Default::default()
        }
    }

    // ------------------------------------------------------------------
    // BID — 固定费
    // ------------------------------------------------------------------

    #[test]
    fn bid_limit_order_sufficient_balance_locks_notional_plus_fixed_fee() {
        let (mut ups, ssp) = setup(2, 0, 1_000_000, 0);
        let mut engine = RiskEngine::new();
        let mut cmd = bid_cmd(1000, 50, 50, OrderType::Gtc);

        let result = engine.place_order_risk_check(&mut cmd, &mut ups, &ssp);

        let raw = arithmetic::calculate_amount_bid_taker_fee(1000, 50, 2, 0);
        let expected = arithmetic::size_price_to_currency_scale(raw, 100, 1_000_000, 1_000_000);
        assert_eq!(result, CommandResultCode::ValidForMatchingEngine);
        let p = ups.get(UID).unwrap();
        assert_eq!(p.locked(QUOTE), expected);
        assert_eq!(p.account(QUOTE), 1_000_000, "accounts 不动");
    }

    #[test]
    fn bid_limit_order_insufficient_balance_returns_nsf_and_locks_nothing() {
        let (mut ups, ssp) = setup(2, 0, 100, 0);
        let mut engine = RiskEngine::new();
        let mut cmd = bid_cmd(1000, 50, 50, OrderType::Gtc);

        let result = engine.place_order_risk_check(&mut cmd, &mut ups, &ssp);

        assert_eq!(result, CommandResultCode::RiskNsf);
        let p = ups.get(UID).unwrap();
        assert_eq!(p.locked(QUOTE), 0);
        assert_eq!(p.account(QUOTE), 100);
    }

    #[test]
    fn bid_limit_order_reserve_less_than_price_returns_invalid_reserve_price() {
        let (mut ups, ssp) = setup(2, 0, 1_000_000, 0);
        let mut engine = RiskEngine::new();
        let mut cmd = bid_cmd(1000, 50, 49, OrderType::Gtc); // reserve(49) < price(50)

        let result = engine.place_order_risk_check(&mut cmd, &mut ups, &ssp);

        assert_eq!(result, CommandResultCode::RiskInvalidReserveBidPrice);
        assert_eq!(ups.get(UID).unwrap().locked(QUOTE), 0);
    }

    // ------------------------------------------------------------------
    // BID — 比例费
    // ------------------------------------------------------------------

    #[test]
    fn bid_limit_order_proportional_fee_locks_notional_plus_ceil_fee() {
        let (mut ups, ssp) = setup(500, 1_000_000, 1_000_000_000, 0);
        let mut engine = RiskEngine::new();
        let mut cmd = bid_cmd(1000, 50, 60, OrderType::Gtc); // reserve(60) > price(50)：保守价冻结

        let result = engine.place_order_risk_check(&mut cmd, &mut ups, &ssp);

        // 用保守价 reserve_bid_price=60（非 cmd.price=50）计算，逐字对照 §2。
        let raw = arithmetic::calculate_amount_bid_taker_fee(1000, 60, 500, 1_000_000);
        let expected = arithmetic::size_price_to_currency_scale(raw, 100, 1_000_000, 1_000_000);
        assert_eq!(result, CommandResultCode::ValidForMatchingEngine);
        assert_eq!(ups.get(UID).unwrap().locked(QUOTE), expected);
    }

    #[test]
    fn bid_budget_order_reserve_equals_price_locks_budget_plus_fee() {
        let (mut ups, ssp) = setup(500, 1_000_000, 1_000_000_000, 0);
        let mut engine = RiskEngine::new();
        // BUDGET 单：cmd.price 是总预算而非单价，reserve 必须严格等于 price。
        let mut cmd = bid_cmd(1000, 60_000, 60_000, OrderType::FokBudget);

        let result = engine.place_order_risk_check(&mut cmd, &mut ups, &ssp);

        let raw = arithmetic::calculate_amount_bid_taker_fee_for_budget(1000, 60_000, 500, 1_000_000);
        let expected = arithmetic::size_price_to_currency_scale(raw, 100, 1_000_000, 1_000_000);
        assert_eq!(result, CommandResultCode::ValidForMatchingEngine);
        assert_eq!(ups.get(UID).unwrap().locked(QUOTE), expected);
    }

    #[test]
    fn bid_budget_order_reserve_mismatch_returns_invalid_reserve_price() {
        let (mut ups, ssp) = setup(500, 1_000_000, 1_000_000_000, 0);
        let mut engine = RiskEngine::new();
        let mut cmd = bid_cmd(1000, 60_000, 60_001, OrderType::IocBudget); // reserve != price

        let result = engine.place_order_risk_check(&mut cmd, &mut ups, &ssp);

        assert_eq!(result, CommandResultCode::RiskInvalidReserveBidPrice);
        assert_eq!(ups.get(UID).unwrap().locked(QUOTE), 0);
    }

    // ------------------------------------------------------------------
    // ASK — 固定费 / 比例费
    // ------------------------------------------------------------------

    #[test]
    fn ask_order_locks_base_size_scaled_fixed_fee() {
        let (mut ups, ssp) = setup(2, 0, 0, 1_000_000);
        let mut engine = RiskEngine::new();
        let mut cmd = ask_cmd(1000, 50); // price(50) >= taker_fee(2)：不触发 too-low

        let result = engine.place_order_risk_check(&mut cmd, &mut ups, &ssp);

        let raw = arithmetic::calculate_amount_ask(1000);
        let expected = arithmetic::symbol_to_currency_scale(raw, 100, 100);
        assert_eq!(result, CommandResultCode::ValidForMatchingEngine);
        let p = ups.get(UID).unwrap();
        assert_eq!(p.locked(BASE), expected);
        assert_eq!(p.account(BASE), 1_000_000, "accounts 不动");
    }

    #[test]
    fn ask_order_locks_base_size_scaled_proportional_fee() {
        let (mut ups, ssp) = setup(500, 1_000_000, 0, 1_000_000);
        let mut engine = RiskEngine::new();
        let mut cmd = ask_cmd(1000, 50); // ceil_divide(1_000_000, 500)=2000，price(50) < 2000 会 too-low！

        // 用足够高的价格避免 too-low：ceil(fee_scale_k/taker_fee)=2000。
        cmd.price = 2000;
        let result = engine.place_order_risk_check(&mut cmd, &mut ups, &ssp);

        let raw = arithmetic::calculate_amount_ask(1000);
        let expected = arithmetic::symbol_to_currency_scale(raw, 100, 100);
        assert_eq!(result, CommandResultCode::ValidForMatchingEngine);
        assert_eq!(ups.get(UID).unwrap().locked(BASE), expected);
    }

    #[test]
    fn ask_price_too_low_fixed_fee_returns_error() {
        let (mut ups, ssp) = setup(5, 0, 0, 1_000_000);
        let mut engine = RiskEngine::new();
        let mut cmd = ask_cmd(1000, 1); // price(1) < taker_fee(5)

        let result = engine.place_order_risk_check(&mut cmd, &mut ups, &ssp);

        assert_eq!(result, CommandResultCode::RiskAskPriceLowerThanFee);
        assert_eq!(ups.get(UID).unwrap().locked(BASE), 0);
    }

    #[test]
    fn ask_price_too_low_proportional_fee_returns_error() {
        let (mut ups, ssp) = setup(500, 1_000_000, 0, 1_000_000);
        let mut engine = RiskEngine::new();
        // ceil_divide(1_000_000, 500) = 2000；price(1999) < 2000 触发 too-low。
        let mut cmd = ask_cmd(1000, 1999);

        let result = engine.place_order_risk_check(&mut cmd, &mut ups, &ssp);

        assert_eq!(result, CommandResultCode::RiskAskPriceLowerThanFee);
        assert_eq!(ups.get(UID).unwrap().locked(BASE), 0);
    }

    #[test]
    fn ask_order_insufficient_base_balance_returns_nsf() {
        let (mut ups, ssp) = setup(2, 0, 0, 10);
        let mut engine = RiskEngine::new();
        let mut cmd = ask_cmd(1000, 50);

        let result = engine.place_order_risk_check(&mut cmd, &mut ups, &ssp);

        assert_eq!(result, CommandResultCode::RiskNsf);
        let p = ups.get(UID).unwrap();
        assert_eq!(p.locked(BASE), 0);
        assert_eq!(p.account(BASE), 10);
    }

    // ------------------------------------------------------------------
    // 用户 / symbol 缺失
    // ------------------------------------------------------------------

    #[test]
    fn auth_invalid_user_when_profile_missing() {
        let (mut ups, ssp) = setup(2, 0, 1_000_000, 0);
        let mut engine = RiskEngine::new();
        let mut cmd = bid_cmd(1000, 50, 50, OrderType::Gtc);
        cmd.uid = 999; // 未建档用户

        let result = engine.place_order_risk_check(&mut cmd, &mut ups, &ssp);

        assert_eq!(result, CommandResultCode::AuthInvalidUser);
    }

    #[test]
    fn invalid_symbol_when_spec_missing() {
        let (mut ups, ssp) = setup(2, 0, 1_000_000, 0);
        let mut engine = RiskEngine::new();
        let mut cmd = bid_cmd(1000, 50, 50, OrderType::Gtc);
        cmd.symbol = 999; // 未注册 symbol

        let result = engine.place_order_risk_check(&mut cmd, &mut ups, &ssp);

        assert_eq!(result, CommandResultCode::InvalidSymbol);
    }

    // ------------------------------------------------------------------
    // R2 — handler_risk_release：reject/reduce 释放冻结
    // ------------------------------------------------------------------

    fn reject_or_reduce_event(
        event_type: MatcherEventType,
        size: i64,
        price: i64,
        bidder_hold_price: i64,
        next: Option<Box<MatcherTradeEvent>>,
    ) -> Box<MatcherTradeEvent> {
        Box::new(MatcherTradeEvent {
            event_type,
            active_order_completed: false,
            maker_order_id: 0,
            maker_order_completed: false,
            price,
            size,
            bid_gt_ask: false,
            bidder_hold_price,
            matched_order_uid: 0,
            next,
        })
    }

    #[test]
    fn bid_plain_limit_pure_reject_releases_full_lock_and_leaves_accounts_untouched() {
        let (mut ups, ssp) = setup(2, 0, 1_000_000, 0);
        let mut engine = RiskEngine::new();
        // 先下单，Task 4 冻结 quote（保守价 reserve_bid_price=50=price）。
        let mut cmd = bid_cmd(1000, 50, 50, OrderType::Gtc);
        let result = engine.place_order_risk_check(&mut cmd, &mut ups, &ssp);
        assert_eq!(result, CommandResultCode::ValidForMatchingEngine);
        let locked_after_place = ups.get(UID).unwrap().locked(QUOTE);
        assert!(locked_after_place > 0, "前置条件：下单必须产生非零冻结");

        // 撮合产生纯 REJECT：整单未成交，size = 订单原始 size，bidder_hold_price = 下单时的保守价。
        cmd.matcher_event =
            Some(reject_or_reduce_event(MatcherEventType::Reject, 1000, 50, 50, None));

        engine.handler_risk_release(&mut cmd, &mut ups, &ssp);

        let p = ups.get(UID).unwrap();
        assert_eq!(p.locked(QUOTE), 0, "纯 REJECT 应把冻结全额释放回 0");
        assert_eq!(p.account(QUOTE), 1_000_000, "accounts 不动");
        assert_eq!(p.account(BASE), 0, "accounts 不动");
        assert!(cmd.matcher_event.is_none(), "REJECT 是唯一事件，链头消费后应清空");
    }

    #[test]
    fn ask_order_reduce_remainder_releases_partial_lock_and_leaves_accounts_untouched() {
        let (mut ups, ssp) = setup(2, 0, 0, 1_000_000);
        let mut engine = RiskEngine::new();
        // 下单：ASK size=1000，锁 base（base_scale_k=100=currency_scale_k(BASE)，缩放恒等）。
        let mut cmd = ask_cmd(1000, 50);
        let result = engine.place_order_risk_check(&mut cmd, &mut ups, &ssp);
        assert_eq!(result, CommandResultCode::ValidForMatchingEngine);
        let locked_after_place = ups.get(UID).unwrap().locked(BASE);
        assert_eq!(locked_after_place, 1000);

        // 部分成交后剩余 300 未成交，REDUCE 释放对应 base 冻结。
        cmd.matcher_event =
            Some(reject_or_reduce_event(MatcherEventType::Reduce, 300, 50, 0, None));

        engine.handler_risk_release(&mut cmd, &mut ups, &ssp);

        let p = ups.get(UID).unwrap();
        assert_eq!(p.locked(BASE), locked_after_place - 300, "REDUCE 只释放剩余量对应的锁定");
        assert_eq!(p.account(BASE), 1_000_000, "accounts 不动");
        assert_eq!(p.account(QUOTE), 0, "accounts 不动");
        assert!(cmd.matcher_event.is_none(), "REDUCE 是唯一事件，链头消费后应清空");
    }

    #[test]
    fn bid_ioc_budget_full_reject_no_prior_trade_releases_full_budget() {
        let (mut ups, ssp) = setup(500, 1_000_000, 1_000_000_000, 0);
        let mut engine = RiskEngine::new();
        let mut cmd = bid_cmd(1000, 60_000, 60_000, OrderType::IocBudget);
        let result = engine.place_order_risk_check(&mut cmd, &mut ups, &ssp);
        assert_eq!(result, CommandResultCode::ValidForMatchingEngine);
        let locked_after_place = ups.get(UID).unwrap().locked(QUOTE);
        assert!(locked_after_place > 0);

        // 全拒：REJECT 是链上唯一事件（mte.next == None）→ 释放整份预算（用 cmd.size/cmd.price）。
        cmd.matcher_event =
            Some(reject_or_reduce_event(MatcherEventType::Reject, 1000, 60_000, 0, None));

        engine.handler_risk_release(&mut cmd, &mut ups, &ssp);

        assert_eq!(ups.get(UID).unwrap().locked(QUOTE), 0, "全拒应释放整份预算冻结");
    }

    #[test]
    fn bid_ioc_budget_partial_fill_then_reduce_releases_zero_to_avoid_double_release() {
        let (mut ups, ssp) = setup(500, 1_000_000, 1_000_000_000, 0);
        let mut engine = RiskEngine::new();
        let mut cmd = bid_cmd(1000, 60_000, 60_000, OrderType::IocBudget);
        let result = engine.place_order_risk_check(&mut cmd, &mut ups, &ssp);
        assert_eq!(result, CommandResultCode::ValidForMatchingEngine);
        let locked_after_place = ups.get(UID).unwrap().locked(QUOTE);
        assert!(locked_after_place > 0);

        // 部分成交：REJECT/REDUCE 链头后面挂着一个 TRADE（400/1000 成交，matched_order_uid=0——
        // 本测试不关心 maker 侧记账，只关心 taker 侧 quote 冻结的完整生命周期）。
        let trailing_trade =
            reject_or_reduce_event(MatcherEventType::Trade, 400, 55, 60_000, None);
        cmd.matcher_event = Some(reject_or_reduce_event(
            MatcherEventType::Reduce,
            600,
            60_000,
            0,
            Some(trailing_trade),
        ));

        engine.handler_risk_release(&mut cmd, &mut ups, &ssp);

        // 端到端一致性：REDUCE 释放 0（不重复释放）+ 紧接着 buy 结算（Task 7）把整份
        // held_total(60030) 全额释放 → taker quote 冻结最终清零。Task 5 的 release_sp=0
        // 正是建立在"Task 7 会把整份预算释放干净"这个前提上，这里端到端验证该前提成立。
        assert_eq!(
            ups.get(UID).unwrap().locked(QUOTE),
            0,
            "REDUCE 不重复释放 + buy 结算全额释放 held_total，taker quote 冻结应归零"
        );
        // 剩余 TRADE 链已被 buy 结算（Task 7）完全消费。
        assert!(cmd.matcher_event.is_none(), "TRADE 链应被 buy 结算完全消费");
    }

    // ------------------------------------------------------------------
    // R2 — handler_risk_release / handle_matcher_events_exchange_sell：
    // taker 卖(ASK)结算，逐 maker + 聚合 taker + 平台费，全程守恒断言。
    // ------------------------------------------------------------------

    const BUYER1: i64 = 8;
    const BUYER2: i64 = 9;

    /// 与 [`spec_with_fee`] 的区别：额外暴露 `maker_fee`；quote currency 的 `currency_scale_k`
    /// 取 `base_scale_k * quote_scale_k`（乘积单位本身），让 `size_price_to_currency_scale`
    /// 在这组测试里恒等（`from_k == to_k` 直接返回，见 `convert_scale`）——这样才能把 §3b 公式的
    /// 每一步都断言到精确整数，而不必额外操心"乘积 scale 换算到 quote currency scale"这次独立
    /// 取整引入的 dust；ceil 手续费本身的取整路径（`ceil_mul_mul_div`/`ceil_mul_div`）仍完整走到。
    fn spec_with_fees(taker_fee: i64, maker_fee: i64, fee_scale_k: i64) -> CoreSymbolSpecification {
        CoreSymbolSpecification {
            symbol_id: SYMBOL,
            symbol_type: SymbolType::CurrencyExchangePair,
            base_currency: BASE,
            quote_currency: QUOTE,
            base_scale_k: 100,
            quote_scale_k: 1_000_000,
            taker_fee,
            maker_fee,
            fee_scale_k,
            ..Default::default()
        }
    }

    /// 搭建：一个 seller（taker，已建档 + base 余额）+ 若干 buyer（maker，已建档 + quote 余额）。
    fn setup_sell(
        taker_fee: i64,
        maker_fee: i64,
        fee_scale_k: i64,
        seller_base_balance: i64,
        buyers_quote_balance: &[(i64, i64)],
    ) -> (UserProfileService, SymbolSpecificationProvider) {
        let mut ssp = SymbolSpecificationProvider::new();
        assert_eq!(
            ssp.add_symbol(spec_with_fees(taker_fee, maker_fee, fee_scale_k)),
            CommandResultCode::Success
        );
        ssp.add_currency(CoreCurrencySpecification { currency: BASE, currency_scale_k: 100 });
        ssp.add_currency(CoreCurrencySpecification {
            currency: QUOTE,
            currency_scale_k: 100 * 1_000_000,
        });

        let mut ups = UserProfileService::new();
        assert_eq!(ups.add_empty_user_profile(UID), CommandResultCode::Success);
        ups.get_mut(UID).unwrap().add_to_account(BASE, seller_base_balance);
        for &(uid, quote_balance) in buyers_quote_balance {
            assert_eq!(ups.add_empty_user_profile(uid), CommandResultCode::Success);
            ups.get_mut(uid).unwrap().add_to_account(QUOTE, quote_balance);
        }
        (ups, ssp)
    }

    fn trade_event(
        size: i64,
        price: i64,
        bidder_hold_price: i64,
        matched_order_uid: i64,
        next: Option<Box<MatcherTradeEvent>>,
    ) -> Box<MatcherTradeEvent> {
        Box::new(MatcherTradeEvent {
            event_type: MatcherEventType::Trade,
            active_order_completed: false,
            maker_order_id: 0,
            maker_order_completed: false,
            price,
            size,
            bid_gt_ask: false,
            bidder_hold_price,
            matched_order_uid,
            next,
        })
    }

    /// 断言这笔结算的全局守恒不变式（参考文档 §6）：base 腿逐笔精确守恒（无 fee）；
    /// quote 腿守恒 modulo `fees[quote]`（`Σ accounts[quote] delta + fees[quote] delta == 0`）。
    fn assert_conserved(
        base_deltas: &[i64],
        quote_deltas: &[i64],
        fees_quote_delta: i64,
    ) {
        let base_sum: i64 = base_deltas.iter().sum();
        assert_eq!(base_sum, 0, "base 腿必须逐笔精确守恒（无 fee）");
        let quote_sum: i64 = quote_deltas.iter().sum();
        assert_eq!(quote_sum + fees_quote_delta, 0, "quote 腿守恒 modulo fees[quote]");
    }

    #[test]
    fn sell_single_maker_fixed_fee_price_improvement_refund_and_conservation() {
        // taker_fee=3（固定，每手 3）、maker_fee=1（固定，每手 1）。
        let (mut ups, ssp) = setup_sell(3, 1, 0, 1_000_000, &[(BUYER1, 1_000_000)]);
        let mut engine = RiskEngine::new();

        // seller：ASK size=1000。
        let mut seller_cmd = ask_cmd(1000, 50);
        assert_eq!(
            engine.place_order_risk_check(&mut seller_cmd, &mut ups, &ssp),
            CommandResultCode::ValidForMatchingEngine
        );
        assert_eq!(ups.get(UID).unwrap().locked(BASE), 1000);

        // buyer：BID size=1000 @ 55（挂单保守价 55，成交价改善到 50）。
        let mut buyer_cmd = bid_cmd(1000, 55, 55, OrderType::Gtc);
        buyer_cmd.uid = BUYER1;
        assert_eq!(
            engine.place_order_risk_check(&mut buyer_cmd, &mut ups, &ssp),
            CommandResultCode::ValidForMatchingEngine
        );
        let hold_quote = ups.get(BUYER1).unwrap().locked(QUOTE);
        assert_eq!(hold_quote, 58_000, "58000 = size*holdPrice(55000) + size*takerFee(3000)");

        let seller_quote_before = ups.get(UID).unwrap().account(QUOTE);
        let seller_base_before = ups.get(UID).unwrap().account(BASE);
        let buyer_quote_before = ups.get(BUYER1).unwrap().account(QUOTE);
        let buyer_base_before = ups.get(BUYER1).unwrap().account(BASE);

        // 唯一一笔 TRADE：size=1000 @ 50，maker(BUYER1) 的保守价 55。
        seller_cmd.matcher_event = Some(trade_event(1000, 50, 55, BUYER1, None));
        engine.handler_risk_release(&mut seller_cmd, &mut ups, &ssp);

        // maker：quote 净变动 = quote_refund(7000) - hold_quote(58000) = -51000；base += 1000；锁定清零。
        let buyer = ups.get(BUYER1).unwrap();
        assert_eq!(buyer.locked(QUOTE), 0, "maker quote 冻结应全额释放");
        assert_eq!(buyer.account(QUOTE) - buyer_quote_before, -51_000);
        assert_eq!(buyer.account(BASE) - buyer_base_before, 1000);

        // taker：base released/spent = size(1000)；quote += notional(50000) - takerFee(3000) = 47000。
        let seller = ups.get(UID).unwrap();
        assert_eq!(seller.locked(BASE), 0, "taker base 冻结应全额释放");
        assert_eq!(seller.account(BASE) - seller_base_before, -1000);
        assert_eq!(seller.account(QUOTE) - seller_quote_before, 47_000);

        // fees[quote] = takerFee(3000) + makerFee(1000) = 4000。
        assert_eq!(*engine.fees.get(&QUOTE).unwrap(), 4000);

        assert!(seller_cmd.matcher_event.is_none(), "TRADE 链结算后应清空");

        assert_conserved(&[-1000, 1000], &[47_000, -51_000], 4000);
    }

    #[test]
    fn sell_single_maker_proportional_fee_and_conservation() {
        // taker_fee=100/10000=1%，maker_fee=20/10000=0.2%。
        let (mut ups, ssp) = setup_sell(100, 20, 10_000, 1_000_000, &[(BUYER1, 1_000_000_000)]);
        let mut engine = RiskEngine::new();

        let mut seller_cmd = ask_cmd(1000, 2000); // price(2000) >= ceil(10000/100)=100，不触发 too-low
        assert_eq!(
            engine.place_order_risk_check(&mut seller_cmd, &mut ups, &ssp),
            CommandResultCode::ValidForMatchingEngine
        );

        let mut buyer_cmd = bid_cmd(1000, 60, 60, OrderType::Gtc);
        buyer_cmd.uid = BUYER1;
        assert_eq!(
            engine.place_order_risk_check(&mut buyer_cmd, &mut ups, &ssp),
            CommandResultCode::ValidForMatchingEngine
        );
        // holdQuote = size*price(60000) + ceil(size*price*takerFee/scale)=ceil(6_000_000/10000)=600 → 60600。
        assert_eq!(ups.get(BUYER1).unwrap().locked(QUOTE), 60_600);

        let seller_quote_before = ups.get(UID).unwrap().account(QUOTE);
        let seller_base_before = ups.get(UID).unwrap().account(BASE);
        let buyer_quote_before = ups.get(BUYER1).unwrap().account(QUOTE);
        let buyer_base_before = ups.get(BUYER1).unwrap().account(BASE);

        // 成交价改善到 50（< holdPrice 60）。
        seller_cmd.matcher_event = Some(trade_event(1000, 50, 60, BUYER1, None));
        engine.handler_risk_release(&mut seller_cmd, &mut ups, &ssp);

        // quoteRefund = tradeAmountDiff(10000) + feeDiff(ceil(1000*(60*100-50*20)/10000)=ceil(5_000_000/10000)=500) = 10500。
        // maker quote 净变动 = 10500 - 60600 = -50100。
        let buyer = ups.get(BUYER1).unwrap();
        assert_eq!(buyer.locked(QUOTE), 0);
        assert_eq!(buyer.account(QUOTE) - buyer_quote_before, -50_100);
        assert_eq!(buyer.account(BASE) - buyer_base_before, 1000);

        // takerFee = ceil(1000*50*100/10000) = 500；quote += 50000-500 = 49500。
        let seller = ups.get(UID).unwrap();
        assert_eq!(seller.locked(BASE), 0);
        assert_eq!(seller.account(BASE) - seller_base_before, -1000);
        assert_eq!(seller.account(QUOTE) - seller_quote_before, 49_500);

        // makerFee(avg price=50) = ceil(1000*50*20/10000) = 100；fees[quote] = 500+100 = 600。
        assert_eq!(*engine.fees.get(&QUOTE).unwrap(), 600);

        assert_conserved(&[-1000, 1000], &[49_500, -50_100], 600);
    }

    #[test]
    fn sell_two_makers_fixed_fee_avg_price_platform_fee_and_conservation() {
        let (mut ups, ssp) = setup_sell(
            3,
            1,
            0,
            1_000_000,
            &[(BUYER1, 1_000_000), (BUYER2, 1_000_000)],
        );
        let mut engine = RiskEngine::new();

        let mut seller_cmd = ask_cmd(2000, 50);
        assert_eq!(
            engine.place_order_risk_check(&mut seller_cmd, &mut ups, &ssp),
            CommandResultCode::ValidForMatchingEngine
        );

        let mut buyer1_cmd = bid_cmd(1000, 55, 55, OrderType::Gtc);
        buyer1_cmd.uid = BUYER1;
        assert_eq!(
            engine.place_order_risk_check(&mut buyer1_cmd, &mut ups, &ssp),
            CommandResultCode::ValidForMatchingEngine
        );
        let mut buyer2_cmd = bid_cmd(1000, 60, 60, OrderType::Gtc);
        buyer2_cmd.uid = BUYER2;
        assert_eq!(
            engine.place_order_risk_check(&mut buyer2_cmd, &mut ups, &ssp),
            CommandResultCode::ValidForMatchingEngine
        );

        let seller_base_before = ups.get(UID).unwrap().account(BASE);
        let seller_quote_before = ups.get(UID).unwrap().account(QUOTE);
        let buyer1_quote_before = ups.get(BUYER1).unwrap().account(QUOTE);
        let buyer1_base_before = ups.get(BUYER1).unwrap().account(BASE);
        let buyer2_quote_before = ups.get(BUYER2).unwrap().account(QUOTE);
        let buyer2_base_before = ups.get(BUYER2).unwrap().account(BASE);

        // 两笔 TRADE：event1 对 BUYER1（size1000@50，holdPrice55，价格改善）；
        // event2 对 BUYER2（size1000@60，holdPrice60，无改善）。
        let event2 = trade_event(1000, 60, 60, BUYER2, None);
        seller_cmd.matcher_event = Some(trade_event(1000, 50, 55, BUYER1, Some(event2)));
        engine.handler_risk_release(&mut seller_cmd, &mut ups, &ssp);

        let buyer1 = ups.get(BUYER1).unwrap();
        assert_eq!(buyer1.locked(QUOTE), 0);
        let buyer1_quote_delta = buyer1.account(QUOTE) - buyer1_quote_before;
        assert_eq!(buyer1_quote_delta, -51_000); // 同单 maker fixed 用例
        let buyer1_base_delta = buyer1.account(BASE) - buyer1_base_before;
        assert_eq!(buyer1_base_delta, 1000);

        let buyer2 = ups.get(BUYER2).unwrap();
        assert_eq!(buyer2.locked(QUOTE), 0);
        // holdQuote=1000*60+1000*3=63000；quoteRefund=1000*(60-60)+1000*(3-1)=2000；净=2000-63000=-61000。
        let buyer2_quote_delta = buyer2.account(QUOTE) - buyer2_quote_before;
        assert_eq!(buyer2_quote_delta, -61_000);
        let buyer2_base_delta = buyer2.account(BASE) - buyer2_base_before;
        assert_eq!(buyer2_base_delta, 1000);

        // taker：avgTakerPrice=(1000*50+1000*60)/2000=55；takerFee=2000*3=6000（固定费与价格无关）。
        // quote += (110000-6000)=104000。
        let seller = ups.get(UID).unwrap();
        assert_eq!(seller.locked(BASE), 0);
        let seller_base_delta = seller.account(BASE) - seller_base_before;
        assert_eq!(seller_base_delta, -2000);
        let seller_quote_delta = seller.account(QUOTE) - seller_quote_before;
        assert_eq!(seller_quote_delta, 104_000);

        // avgMakerPrice=55；makerFee=2000*1=2000；fees[quote]=6000+2000=8000。
        let fees_delta = *engine.fees.get(&QUOTE).unwrap();
        assert_eq!(fees_delta, 8000);

        assert_conserved(
            &[seller_base_delta, buyer1_base_delta, buyer2_base_delta],
            &[seller_quote_delta, buyer1_quote_delta, buyer2_quote_delta],
            fees_delta,
        );
    }

    #[test]
    fn sell_two_makers_proportional_fee_avg_price_platform_fee_and_conservation() {
        let (mut ups, ssp) = setup_sell(
            100,
            20,
            10_000,
            1_000_000,
            &[(BUYER1, 1_000_000_000), (BUYER2, 1_000_000_000)],
        );
        let mut engine = RiskEngine::new();

        let mut seller_cmd = ask_cmd(2000, 2000);
        assert_eq!(
            engine.place_order_risk_check(&mut seller_cmd, &mut ups, &ssp),
            CommandResultCode::ValidForMatchingEngine
        );

        // 两个 maker 都没有价格改善（holdPrice == 成交价），聚焦"均价重算平台费"这条路径。
        let mut buyer1_cmd = bid_cmd(1000, 40, 40, OrderType::Gtc);
        buyer1_cmd.uid = BUYER1;
        assert_eq!(
            engine.place_order_risk_check(&mut buyer1_cmd, &mut ups, &ssp),
            CommandResultCode::ValidForMatchingEngine
        );
        let mut buyer2_cmd = bid_cmd(1000, 60, 60, OrderType::Gtc);
        buyer2_cmd.uid = BUYER2;
        assert_eq!(
            engine.place_order_risk_check(&mut buyer2_cmd, &mut ups, &ssp),
            CommandResultCode::ValidForMatchingEngine
        );

        let seller_base_before = ups.get(UID).unwrap().account(BASE);
        let seller_quote_before = ups.get(UID).unwrap().account(QUOTE);
        let buyer1_quote_before = ups.get(BUYER1).unwrap().account(QUOTE);
        let buyer1_base_before = ups.get(BUYER1).unwrap().account(BASE);
        let buyer2_quote_before = ups.get(BUYER2).unwrap().account(QUOTE);
        let buyer2_base_before = ups.get(BUYER2).unwrap().account(BASE);

        let event2 = trade_event(1000, 60, 60, BUYER2, None);
        seller_cmd.matcher_event = Some(trade_event(1000, 40, 40, BUYER1, Some(event2)));
        engine.handler_risk_release(&mut seller_cmd, &mut ups, &ssp);

        let buyer1 = ups.get(BUYER1).unwrap();
        assert_eq!(buyer1.locked(QUOTE), 0);
        let buyer1_quote_delta = buyer1.account(QUOTE) - buyer1_quote_before;
        // holdQuote=1000*40+ceil(1000*40*100/10000)=40000+400=40400；
        // quoteRefund=0+ceil(1000*(40*100-40*20)/10000)=ceil(3_200_000/10000)=320；净=320-40400=-40080。
        assert_eq!(buyer1_quote_delta, -40_080);
        let buyer1_base_delta = buyer1.account(BASE) - buyer1_base_before;
        assert_eq!(buyer1_base_delta, 1000);

        let buyer2 = ups.get(BUYER2).unwrap();
        assert_eq!(buyer2.locked(QUOTE), 0);
        // holdQuote=1000*60+ceil(1000*60*100/10000)=60000+600=60600；
        // quoteRefund=0+ceil(1000*(60*100-60*20)/10000)=ceil(4_800_000/10000)=480；净=480-60600=-60120。
        let buyer2_quote_delta = buyer2.account(QUOTE) - buyer2_quote_before;
        assert_eq!(buyer2_quote_delta, -60_120);
        let buyer2_base_delta = buyer2.account(BASE) - buyer2_base_before;
        assert_eq!(buyer2_base_delta, 1000);

        // avgTakerPrice=(1000*40+1000*60)/2000=50；takerFee=ceil(2000*50*100/10000)=1000。
        // quote += (100000-1000)=99000。
        let seller = ups.get(UID).unwrap();
        assert_eq!(seller.locked(BASE), 0);
        let seller_base_delta = seller.account(BASE) - seller_base_before;
        assert_eq!(seller_base_delta, -2000);
        let seller_quote_delta = seller.account(QUOTE) - seller_quote_before;
        assert_eq!(seller_quote_delta, 99_000);

        // avgMakerPrice=50；makerFee=ceil(2000*50*20/10000)=200；fees[quote]=1000+200=1200。
        let fees_delta = *engine.fees.get(&QUOTE).unwrap();
        assert_eq!(fees_delta, 1200);

        assert_conserved(
            &[seller_base_delta, buyer1_base_delta, buyer2_base_delta],
            &[seller_quote_delta, buyer1_quote_delta, buyer2_quote_delta],
            fees_delta,
        );
    }

    // ------------------------------------------------------------------
    // R2 — handler_risk_release / handle_matcher_events_exchange_buy：
    // taker 买(BID)结算，逐 maker(卖方) + 聚合 taker + 平台费，全程守恒断言。
    // 与上面 sell 测试组镜像（角色对调：taker=买方锁 quote，maker=卖方锁 base）。
    // ------------------------------------------------------------------

    const SELLER1: i64 = 10;
    const SELLER2: i64 = 11;

    /// 搭建：一个 buyer（taker，已建档 + quote 余额）+ 若干 seller（maker，已建档 + base 余额）。
    fn setup_buy(
        taker_fee: i64,
        maker_fee: i64,
        fee_scale_k: i64,
        taker_quote_balance: i64,
        sellers_base_balance: &[(i64, i64)],
    ) -> (UserProfileService, SymbolSpecificationProvider) {
        let mut ssp = SymbolSpecificationProvider::new();
        assert_eq!(
            ssp.add_symbol(spec_with_fees(taker_fee, maker_fee, fee_scale_k)),
            CommandResultCode::Success
        );
        ssp.add_currency(CoreCurrencySpecification { currency: BASE, currency_scale_k: 100 });
        ssp.add_currency(CoreCurrencySpecification {
            currency: QUOTE,
            currency_scale_k: 100 * 1_000_000,
        });

        let mut ups = UserProfileService::new();
        assert_eq!(ups.add_empty_user_profile(UID), CommandResultCode::Success);
        ups.get_mut(UID).unwrap().add_to_account(QUOTE, taker_quote_balance);
        for &(uid, base_balance) in sellers_base_balance {
            assert_eq!(ups.add_empty_user_profile(uid), CommandResultCode::Success);
            ups.get_mut(uid).unwrap().add_to_account(BASE, base_balance);
        }
        (ups, ssp)
    }

    #[test]
    fn buy_single_maker_fixed_fee_price_improvement_refund_and_conservation() {
        // taker_fee=3（固定）、maker_fee=1（固定）。
        let (mut ups, ssp) = setup_buy(3, 1, 0, 1_000_000, &[(SELLER1, 1_000_000)]);
        let mut engine = RiskEngine::new();

        // maker：seller ASK size=1000。
        let mut seller_cmd = ask_cmd(1000, 50);
        seller_cmd.uid = SELLER1;
        assert_eq!(
            engine.place_order_risk_check(&mut seller_cmd, &mut ups, &ssp),
            CommandResultCode::ValidForMatchingEngine
        );
        assert_eq!(ups.get(SELLER1).unwrap().locked(BASE), 1000);

        // taker：buyer BID size=1000 @ 55（own 保守价 55，成交价改善到 50）。
        let mut buyer_cmd = bid_cmd(1000, 55, 55, OrderType::Gtc);
        assert_eq!(
            engine.place_order_risk_check(&mut buyer_cmd, &mut ups, &ssp),
            CommandResultCode::ValidForMatchingEngine
        );
        let hold_quote = ups.get(UID).unwrap().locked(QUOTE);
        assert_eq!(hold_quote, 58_000, "58000 = size*holdPrice(55000) + size*takerFee(3000)");

        let buyer_quote_before = ups.get(UID).unwrap().account(QUOTE);
        let buyer_base_before = ups.get(UID).unwrap().account(BASE);
        let seller_quote_before = ups.get(SELLER1).unwrap().account(QUOTE);
        let seller_base_before = ups.get(SELLER1).unwrap().account(BASE);

        // 唯一一笔 TRADE：size=1000 @ 50，taker(买方) 的保守价 55。
        buyer_cmd.matcher_event = Some(trade_event(1000, 50, 55, SELLER1, None));
        engine.handler_risk_release(&mut buyer_cmd, &mut ups, &ssp);

        // maker(seller)：base 释放/实付 = size(1000)；quote += notional(50000) - makerFee(1000) = 49000。
        let seller = ups.get(SELLER1).unwrap();
        assert_eq!(seller.locked(BASE), 0, "maker base 冻结应全额释放");
        assert_eq!(seller.account(BASE) - seller_base_before, -1000);
        assert_eq!(seller.account(QUOTE) - seller_quote_before, 49_000);

        // taker(buyer)：quote 净变动 = quoteRefund(5000) - holdQuote(58000) = -53000；base += 1000；锁定清零。
        let buyer = ups.get(UID).unwrap();
        assert_eq!(buyer.locked(QUOTE), 0, "taker quote 冻结应全额释放");
        assert_eq!(buyer.account(QUOTE) - buyer_quote_before, -53_000);
        assert_eq!(buyer.account(BASE) - buyer_base_before, 1000);

        // fees[quote] = takerFee(3000) + makerFee(1000) = 4000。
        assert_eq!(*engine.fees.get(&QUOTE).unwrap(), 4000);

        assert!(buyer_cmd.matcher_event.is_none(), "TRADE 链结算后应清空");

        assert_conserved(&[1000, -1000], &[-53_000, 49_000], 4000);
    }

    #[test]
    fn buy_single_maker_proportional_fee_and_conservation() {
        // taker_fee=100/10000=1%，maker_fee=20/10000=0.2%。
        let (mut ups, ssp) = setup_buy(100, 20, 10_000, 1_000_000_000, &[(SELLER1, 1_000_000)]);
        let mut engine = RiskEngine::new();

        let mut seller_cmd = ask_cmd(1000, 2000); // price(2000) >= ceil(10000/100)=100，不触发 too-low
        seller_cmd.uid = SELLER1;
        assert_eq!(
            engine.place_order_risk_check(&mut seller_cmd, &mut ups, &ssp),
            CommandResultCode::ValidForMatchingEngine
        );

        let mut buyer_cmd = bid_cmd(1000, 60, 60, OrderType::Gtc);
        assert_eq!(
            engine.place_order_risk_check(&mut buyer_cmd, &mut ups, &ssp),
            CommandResultCode::ValidForMatchingEngine
        );
        // holdQuote = size*price(60000) + ceil(size*price*takerFee/scale)=ceil(6_000_000/10000)=600 → 60600。
        assert_eq!(ups.get(UID).unwrap().locked(QUOTE), 60_600);

        let buyer_quote_before = ups.get(UID).unwrap().account(QUOTE);
        let buyer_base_before = ups.get(UID).unwrap().account(BASE);
        let seller_quote_before = ups.get(SELLER1).unwrap().account(QUOTE);
        let seller_base_before = ups.get(SELLER1).unwrap().account(BASE);

        // 成交价改善到 50（< holdPrice 60）。
        buyer_cmd.matcher_event = Some(trade_event(1000, 50, 60, SELLER1, None));
        engine.handler_risk_release(&mut buyer_cmd, &mut ups, &ssp);

        // makerFee(price=50) = ceil(1000*50*20/10000) = 100；maker quote += 50000-100 = 49900。
        let seller = ups.get(SELLER1).unwrap();
        assert_eq!(seller.locked(BASE), 0);
        assert_eq!(seller.account(BASE) - seller_base_before, -1000);
        assert_eq!(seller.account(QUOTE) - seller_quote_before, 49_900);

        // takerFee(avgPrice=50) = ceil(1000*50*100/10000) = 500；feeHeld(holdPrice=60) =
        // ceil(1000*60*100/10000) = 600；leftover = 600-500 = 100；
        // quoteRefund = (60000-50000+100) = 10100；净 = 10100-60600 = -50500。
        let buyer = ups.get(UID).unwrap();
        assert_eq!(buyer.locked(QUOTE), 0);
        assert_eq!(buyer.account(QUOTE) - buyer_quote_before, -50_500);
        assert_eq!(buyer.account(BASE) - buyer_base_before, 1000);

        // fees[quote] = takerFee(500) + makerFee(avg=50→100) = 600。
        assert_eq!(*engine.fees.get(&QUOTE).unwrap(), 600);

        assert_conserved(&[1000, -1000], &[-50_500, 49_900], 600);
    }

    #[test]
    fn buy_two_makers_fixed_fee_avg_price_platform_fee_and_conservation() {
        let (mut ups, ssp) = setup_buy(
            3,
            1,
            0,
            1_000_000,
            &[(SELLER1, 1_000_000), (SELLER2, 1_000_000)],
        );
        let mut engine = RiskEngine::new();

        let mut seller1_cmd = ask_cmd(1000, 50);
        seller1_cmd.uid = SELLER1;
        assert_eq!(
            engine.place_order_risk_check(&mut seller1_cmd, &mut ups, &ssp),
            CommandResultCode::ValidForMatchingEngine
        );
        let mut seller2_cmd = ask_cmd(1000, 60);
        seller2_cmd.uid = SELLER2;
        assert_eq!(
            engine.place_order_risk_check(&mut seller2_cmd, &mut ups, &ssp),
            CommandResultCode::ValidForMatchingEngine
        );

        // taker：单笔 BID size=2000 @ 60（own 保守价 60），走两个 maker。
        let mut buyer_cmd = bid_cmd(2000, 60, 60, OrderType::Gtc);
        assert_eq!(
            engine.place_order_risk_check(&mut buyer_cmd, &mut ups, &ssp),
            CommandResultCode::ValidForMatchingEngine
        );

        let buyer_quote_before = ups.get(UID).unwrap().account(QUOTE);
        let buyer_base_before = ups.get(UID).unwrap().account(BASE);
        let seller1_quote_before = ups.get(SELLER1).unwrap().account(QUOTE);
        let seller1_base_before = ups.get(SELLER1).unwrap().account(BASE);
        let seller2_quote_before = ups.get(SELLER2).unwrap().account(QUOTE);
        let seller2_base_before = ups.get(SELLER2).unwrap().account(BASE);

        // 两笔 TRADE：event1 对 SELLER1（size1000@50，价格改善）；event2 对 SELLER2（size1000@60，无改善）。
        let event2 = trade_event(1000, 60, 60, SELLER2, None);
        buyer_cmd.matcher_event = Some(trade_event(1000, 50, 60, SELLER1, Some(event2)));
        engine.handler_risk_release(&mut buyer_cmd, &mut ups, &ssp);

        let seller1 = ups.get(SELLER1).unwrap();
        assert_eq!(seller1.locked(BASE), 0);
        let seller1_quote_delta = seller1.account(QUOTE) - seller1_quote_before;
        assert_eq!(seller1_quote_delta, 49_000); // 同单 maker fixed 用例
        let seller1_base_delta = seller1.account(BASE) - seller1_base_before;
        assert_eq!(seller1_base_delta, -1000);

        let seller2 = ups.get(SELLER2).unwrap();
        assert_eq!(seller2.locked(BASE), 0);
        // quoteGained=1000*60=60000；makerFee(固定)=1000；quote += 60000-1000=59000。
        let seller2_quote_delta = seller2.account(QUOTE) - seller2_quote_before;
        assert_eq!(seller2_quote_delta, 59_000);
        let seller2_base_delta = seller2.account(BASE) - seller2_base_before;
        assert_eq!(seller2_base_delta, -1000);

        // taker：avgTakerPrice=(1000*50+1000*60)/2000=55；takerFee=2000*3=6000（固定与价格无关）；
        // holdPrice 恒60（own 保守价）：feeHeld=2000*3=6000；leftover=0；holdQuote=126000（匹配下单锁定）；
        // quoteRefund=(120000-110000+0)=10000；净=10000-126000=-116000。
        let buyer = ups.get(UID).unwrap();
        assert_eq!(buyer.locked(QUOTE), 0);
        let buyer_quote_delta = buyer.account(QUOTE) - buyer_quote_before;
        assert_eq!(buyer_quote_delta, -116_000);
        let buyer_base_delta = buyer.account(BASE) - buyer_base_before;
        assert_eq!(buyer_base_delta, 2000);

        // avgMakerPrice=55；makerFee=2000*1=2000；fees[quote]=6000+2000=8000。
        let fees_delta = *engine.fees.get(&QUOTE).unwrap();
        assert_eq!(fees_delta, 8000);

        assert_conserved(
            &[buyer_base_delta, seller1_base_delta, seller2_base_delta],
            &[buyer_quote_delta, seller1_quote_delta, seller2_quote_delta],
            fees_delta,
        );
    }

    #[test]
    fn buy_two_makers_proportional_fee_avg_price_platform_fee_and_conservation() {
        let (mut ups, ssp) = setup_buy(
            100,
            20,
            10_000,
            1_000_000_000,
            &[(SELLER1, 1_000_000), (SELLER2, 1_000_000)],
        );
        let mut engine = RiskEngine::new();

        let mut seller1_cmd = ask_cmd(1000, 2000);
        seller1_cmd.uid = SELLER1;
        assert_eq!(
            engine.place_order_risk_check(&mut seller1_cmd, &mut ups, &ssp),
            CommandResultCode::ValidForMatchingEngine
        );
        let mut seller2_cmd = ask_cmd(1000, 2000);
        seller2_cmd.uid = SELLER2;
        assert_eq!(
            engine.place_order_risk_check(&mut seller2_cmd, &mut ups, &ssp),
            CommandResultCode::ValidForMatchingEngine
        );

        let mut buyer_cmd = bid_cmd(2000, 60, 60, OrderType::Gtc);
        assert_eq!(
            engine.place_order_risk_check(&mut buyer_cmd, &mut ups, &ssp),
            CommandResultCode::ValidForMatchingEngine
        );

        let buyer_quote_before = ups.get(UID).unwrap().account(QUOTE);
        let buyer_base_before = ups.get(UID).unwrap().account(BASE);
        let seller1_quote_before = ups.get(SELLER1).unwrap().account(QUOTE);
        let seller1_base_before = ups.get(SELLER1).unwrap().account(BASE);
        let seller2_quote_before = ups.get(SELLER2).unwrap().account(QUOTE);
        let seller2_base_before = ups.get(SELLER2).unwrap().account(BASE);

        // event1 大幅价格改善（40 vs holdPrice 60），event2 无改善（60 vs holdPrice 60）。
        let event2 = trade_event(1000, 60, 60, SELLER2, None);
        buyer_cmd.matcher_event = Some(trade_event(1000, 40, 60, SELLER1, Some(event2)));
        engine.handler_risk_release(&mut buyer_cmd, &mut ups, &ssp);

        let seller1 = ups.get(SELLER1).unwrap();
        assert_eq!(seller1.locked(BASE), 0);
        // makerFee(price=40)=ceil(1000*40*20/10000)=80；quote += 40000-80=39920。
        let seller1_quote_delta = seller1.account(QUOTE) - seller1_quote_before;
        assert_eq!(seller1_quote_delta, 39_920);
        let seller1_base_delta = seller1.account(BASE) - seller1_base_before;
        assert_eq!(seller1_base_delta, -1000);

        let seller2 = ups.get(SELLER2).unwrap();
        assert_eq!(seller2.locked(BASE), 0);
        // makerFee(price=60)=ceil(1000*60*20/10000)=120；quote += 60000-120=59880。
        let seller2_quote_delta = seller2.account(QUOTE) - seller2_quote_before;
        assert_eq!(seller2_quote_delta, 59_880);
        let seller2_base_delta = seller2.account(BASE) - seller2_base_before;
        assert_eq!(seller2_base_delta, -1000);

        // avgTakerPrice=(1000*40+1000*60)/2000=50；takerFee=ceil(2000*50*100/10000)=1000；
        // holdPrice 恒60：feeHeld=ceil(2000*60*100/10000)=1200；leftover=200；holdQuote=121200
        // （匹配下单锁定）；quoteRefund=(120000-100000+200)=20200；净=20200-121200=-101000。
        let buyer = ups.get(UID).unwrap();
        assert_eq!(buyer.locked(QUOTE), 0);
        let buyer_quote_delta = buyer.account(QUOTE) - buyer_quote_before;
        assert_eq!(buyer_quote_delta, -101_000);
        let buyer_base_delta = buyer.account(BASE) - buyer_base_before;
        assert_eq!(buyer_base_delta, 2000);

        // avgMakerPrice=50；makerFee=ceil(2000*50*20/10000)=200；fees[quote]=1000+200=1200。
        let fees_delta = *engine.fees.get(&QUOTE).unwrap();
        assert_eq!(fees_delta, 1200);

        assert_conserved(
            &[buyer_base_delta, seller1_base_delta, seller2_base_delta],
            &[buyer_quote_delta, seller1_quote_delta, seller2_quote_delta],
            fees_delta,
        );
    }

    /// **Task 5/7 一致性证明**：IOC_BUDGET 部分成交时，本函数（Task 7）必须释放**整份**
    /// `held_total`（用 `cmd.size`/`cmd.price` 算出的原始预算，而非本次成交量），因为 Task 5 的
    /// `handle_matcher_reject_reduce_event_exchange` 在"IOC_BUDGET 有前置 TRADE"分支上releases 0
    /// （见 `bid_ioc_budget_partial_fill_then_reduce_releases_zero_to_avoid_double_release`），
    /// 假设这里已经把整份预算释放干净。本测试显式断言 `hold_quote`（本函数内部释放的量）等于
    /// 下单时 Task 4 锁定的 `locked_after_place`，即使只成交了原始 size 的 40%。
    #[test]
    fn buy_ioc_budget_partial_fill_releases_full_held_total_matching_task5_assumption() {
        // taker_fee=500/1_000_000=0.05%，maker_fee=100/1_000_000=0.01%。
        let (mut ups, ssp) = setup_buy(500, 100, 1_000_000, 1_000_000_000, &[(SELLER1, 1_000_000)]);
        let mut engine = RiskEngine::new();

        // maker：seller ASK size=400（本次成交量，等于 maker 挂单全部数量）。
        // price(2000) >= ceil(1_000_000/500)=2000，不触发 too-low。
        let mut seller_cmd = ask_cmd(400, 2000);
        seller_cmd.uid = SELLER1;
        assert_eq!(
            engine.place_order_risk_check(&mut seller_cmd, &mut ups, &ssp),
            CommandResultCode::ValidForMatchingEngine
        );

        // taker：IOC_BUDGET，cmd.size=1000（上限）、cmd.price=60000（预算总额，非单价）。
        let mut buyer_cmd = bid_cmd(1000, 60_000, 60_000, OrderType::IocBudget);
        assert_eq!(
            engine.place_order_risk_check(&mut buyer_cmd, &mut ups, &ssp),
            CommandResultCode::ValidForMatchingEngine
        );
        let held_total = arithmetic::calculate_amount_bid_taker_fee_for_budget(1000, 60_000, 500, 1_000_000);
        assert_eq!(held_total, 60_030);
        let locked_after_place = ups.get(UID).unwrap().locked(QUOTE);
        assert_eq!(locked_after_place, held_total, "下单锁定 = held_total（乘积 scale 恒等）");

        let buyer_quote_before = ups.get(UID).unwrap().account(QUOTE);
        let buyer_base_before = ups.get(UID).unwrap().account(BASE);
        let seller_quote_before = ups.get(SELLER1).unwrap().account(QUOTE);
        let seller_base_before = ups.get(SELLER1).unwrap().account(BASE);

        // 只成交 400（原始 1000 的 40%）；bidder_hold_price 对 BUDGET 分支无意义（被
        // taker_hold_notional=taker_notional 覆盖），此处填 60000 仅表示"字段存在但未被读取"。
        buyer_cmd.matcher_event = Some(trade_event(400, 50, 60_000, SELLER1, None));
        engine.handler_risk_release(&mut buyer_cmd, &mut ups, &ssp);

        // 核心断言：taker quote 冻结应清零——即本函数释放的 hold_quote 恰好等于 held_total 全额，
        // 与部分成交量(400)无关。这正是 Task 5 IOC_BUDGET+prior-trade 分支 release_sp=0 的前提。
        let buyer = ups.get(UID).unwrap();
        assert_eq!(
            buyer.locked(QUOTE),
            0,
            "IOC_BUDGET 部分成交后 taker quote 冻结必须清零（全额释放 held_total，Task 5 依赖此前提）"
        );

        // maker：quoteGained=400*50=20000；makerFee=ceil(400*50*100/1_000_000)=2；quote+=19998。
        let seller = ups.get(SELLER1).unwrap();
        assert_eq!(seller.locked(BASE), 0);
        let seller_base_delta = seller.account(BASE) - seller_base_before;
        assert_eq!(seller_base_delta, -400);
        let seller_quote_delta = seller.account(QUOTE) - seller_quote_before;
        assert_eq!(seller_quote_delta, 19_998);

        // taker：takerFee(avg=50)=ceil(400*50*500/1_000_000)=10；leftover=heldTotal-(20000+10)=40020；
        // quoteRefund=(20000-20000+40020)=40020；净=40020-60030=-20010；base+=400。
        let buyer_quote_delta = buyer.account(QUOTE) - buyer_quote_before;
        assert_eq!(buyer_quote_delta, -20_010);
        let buyer_base_delta = buyer.account(BASE) - buyer_base_before;
        assert_eq!(buyer_base_delta, 400);

        // fees[quote] = takerFee(10) + makerFee(2) = 12。
        let fees_delta = *engine.fees.get(&QUOTE).unwrap();
        assert_eq!(fees_delta, 12);

        assert_conserved(&[buyer_base_delta, seller_base_delta], &[buyer_quote_delta, seller_quote_delta], fees_delta);
    }

    #[test]
    fn buy_fok_budget_full_fill_proportional_fee_releases_held_total_and_conservation() {
        let (mut ups, ssp) = setup_buy(500, 100, 1_000_000, 1_000_000_000, &[(SELLER1, 1_000_000)]);
        let mut engine = RiskEngine::new();

        // price(2000) >= ceil(1_000_000/500)=2000，不触发 too-low。
        let mut seller_cmd = ask_cmd(1000, 2000);
        seller_cmd.uid = SELLER1;
        assert_eq!(
            engine.place_order_risk_check(&mut seller_cmd, &mut ups, &ssp),
            CommandResultCode::ValidForMatchingEngine
        );

        let mut buyer_cmd = bid_cmd(1000, 60_000, 60_000, OrderType::FokBudget);
        assert_eq!(
            engine.place_order_risk_check(&mut buyer_cmd, &mut ups, &ssp),
            CommandResultCode::ValidForMatchingEngine
        );
        let held_total = arithmetic::calculate_amount_bid_taker_fee_for_budget(1000, 60_000, 500, 1_000_000);
        assert_eq!(held_total, 60_030);
        assert_eq!(ups.get(UID).unwrap().locked(QUOTE), held_total);

        let buyer_quote_before = ups.get(UID).unwrap().account(QUOTE);
        let buyer_base_before = ups.get(UID).unwrap().account(BASE);
        let seller_quote_before = ups.get(SELLER1).unwrap().account(QUOTE);
        let seller_base_before = ups.get(SELLER1).unwrap().account(BASE);

        // 全额成交：size=1000（原始 size 全部），价格 55。
        buyer_cmd.matcher_event = Some(trade_event(1000, 55, 60_000, SELLER1, None));
        engine.handler_risk_release(&mut buyer_cmd, &mut ups, &ssp);

        // maker：makerFee=ceil(1000*55*100/1_000_000)=6；quote += 55000-6=54994。
        let seller = ups.get(SELLER1).unwrap();
        assert_eq!(seller.locked(BASE), 0);
        let seller_base_delta = seller.account(BASE) - seller_base_before;
        assert_eq!(seller_base_delta, -1000);
        let seller_quote_delta = seller.account(QUOTE) - seller_quote_before;
        assert_eq!(seller_quote_delta, 54_994);

        // taker：即使全额成交，holdQuote 仍是 held_total 原样缩放（60030），非按实际成交价重算。
        let buyer = ups.get(UID).unwrap();
        assert_eq!(buyer.locked(QUOTE), 0, "全额成交也应把整份预算冻结全部释放");
        let buyer_quote_delta = buyer.account(QUOTE) - buyer_quote_before;
        assert_eq!(buyer_quote_delta, -55_028);
        let buyer_base_delta = buyer.account(BASE) - buyer_base_before;
        assert_eq!(buyer_base_delta, 1000);

        // fees[quote] = takerFee(28) + makerFee(6) = 34。
        let fees_delta = *engine.fees.get(&QUOTE).unwrap();
        assert_eq!(fees_delta, 34);

        assert_conserved(&[buyer_base_delta, seller_base_delta], &[buyer_quote_delta, seller_quote_delta], fees_delta);
    }

    #[test]
    fn buy_fok_budget_full_fill_fixed_fee_releases_held_total_and_conservation() {
        let (mut ups, ssp) = setup_buy(3, 1, 0, 1_000_000, &[(SELLER1, 1_000_000)]);
        let mut engine = RiskEngine::new();

        let mut seller_cmd = ask_cmd(1000, 50);
        seller_cmd.uid = SELLER1;
        assert_eq!(
            engine.place_order_risk_check(&mut seller_cmd, &mut ups, &ssp),
            CommandResultCode::ValidForMatchingEngine
        );

        // 固定费预算路径：fee = size*taker_fee(与 budget 无关) = 1000*3 = 3000；held_total=63000。
        let mut buyer_cmd = bid_cmd(1000, 60_000, 60_000, OrderType::FokBudget);
        assert_eq!(
            engine.place_order_risk_check(&mut buyer_cmd, &mut ups, &ssp),
            CommandResultCode::ValidForMatchingEngine
        );
        let held_total = arithmetic::calculate_amount_bid_taker_fee_for_budget(1000, 60_000, 3, 0);
        assert_eq!(held_total, 63_000);
        assert_eq!(ups.get(UID).unwrap().locked(QUOTE), held_total);

        let buyer_quote_before = ups.get(UID).unwrap().account(QUOTE);
        let buyer_base_before = ups.get(UID).unwrap().account(BASE);
        let seller_quote_before = ups.get(SELLER1).unwrap().account(QUOTE);
        let seller_base_before = ups.get(SELLER1).unwrap().account(BASE);

        buyer_cmd.matcher_event = Some(trade_event(1000, 55, 60_000, SELLER1, None));
        engine.handler_risk_release(&mut buyer_cmd, &mut ups, &ssp);

        // maker：makerFee(固定)=1000*1=1000；quote += 55000-1000=54000。
        let seller = ups.get(SELLER1).unwrap();
        assert_eq!(seller.locked(BASE), 0);
        let seller_base_delta = seller.account(BASE) - seller_base_before;
        assert_eq!(seller_base_delta, -1000);
        let seller_quote_delta = seller.account(QUOTE) - seller_quote_before;
        assert_eq!(seller_quote_delta, 54_000);

        // taker：takerFee=1000*3=3000；leftover=63000-(55000+3000)=5000；quoteRefund=5000；
        // 净=5000-63000=-58000。
        let buyer = ups.get(UID).unwrap();
        assert_eq!(buyer.locked(QUOTE), 0);
        let buyer_quote_delta = buyer.account(QUOTE) - buyer_quote_before;
        assert_eq!(buyer_quote_delta, -58_000);
        let buyer_base_delta = buyer.account(BASE) - buyer_base_before;
        assert_eq!(buyer_base_delta, 1000);

        // fees[quote] = takerFee(3000) + makerFee(1000) = 4000。
        let fees_delta = *engine.fees.get(&QUOTE).unwrap();
        assert_eq!(fees_delta, 4000);

        assert_conserved(&[buyer_base_delta, seller_base_delta], &[buyer_quote_delta, seller_quote_delta], fees_delta);
    }

    // ========================================================================================
    // Task 8 — ADD_USER / BALANCE_ADJUSTMENT + adjustments 守恒桶（参考文档 §5）
    // ========================================================================================

    fn add_user_cmd(uid: i64) -> OrderCommand {
        OrderCommand { command: OrderCommandType::AddUser, uid, ..Default::default() }
    }

    fn balance_adjustment_cmd(uid: i64, currency: i32, amount: i64, order_id: i64) -> OrderCommand {
        OrderCommand {
            command: OrderCommandType::BalanceAdjustment,
            uid,
            symbol: currency,
            price: amount,
            order_id,
            ..Default::default()
        }
    }

    #[test]
    fn add_user_creates_empty_account() {
        let mut ups = UserProfileService::new();
        let mut engine = RiskEngine::new();
        let cmd = add_user_cmd(UID);
        assert_eq!(engine.add_user(&cmd, &mut ups), CommandResultCode::Success);
        let profile = ups.get(UID).unwrap();
        assert_eq!(profile.account(QUOTE), 0);
        assert_eq!(profile.locked(QUOTE), 0);
    }

    #[test]
    fn add_user_rejects_duplicate_uid() {
        let mut ups = UserProfileService::new();
        let mut engine = RiskEngine::new();
        assert_eq!(engine.add_user(&add_user_cmd(UID), &mut ups), CommandResultCode::Success);
        assert_eq!(
            engine.add_user(&add_user_cmd(UID), &mut ups),
            CommandResultCode::UserMgmtUserAlreadyExists
        );
    }

    #[test]
    fn balance_adjustment_deposit_increases_account_and_conserves_globally() {
        let mut ups = UserProfileService::new();
        let mut engine = RiskEngine::new();
        engine.add_user(&add_user_cmd(UID), &mut ups);

        let cmd = balance_adjustment_cmd(UID, QUOTE, 1000, 1);
        assert_eq!(engine.balance_adjustment(&cmd, &mut ups), CommandResultCode::Success);

        assert_eq!(ups.get(UID).unwrap().account(QUOTE), 1000);
        assert_eq!(*engine.adjustments.get(&QUOTE).unwrap(), -1000);
        // Σ account[cur] + adjustments[cur] 从空账户起恒为 0。
        assert_eq!(ups.get(UID).unwrap().account(QUOTE) + engine.adjustments.get(&QUOTE).unwrap(), 0);
    }

    #[test]
    fn balance_adjustment_withdrawal_exceeding_withdrawable_is_nsf_and_noop() {
        let mut ups = UserProfileService::new();
        let mut engine = RiskEngine::new();
        engine.add_user(&add_user_cmd(UID), &mut ups);
        engine.balance_adjustment(&balance_adjustment_cmd(UID, QUOTE, 1000, 1), &mut ups);
        // 500 冻结在挂单里，可提余额只剩 500。
        ups.get_mut(UID).unwrap().add_to_locked(QUOTE, 500);

        let before_account = ups.get(UID).unwrap().account(QUOTE);
        let before_adjustments = *engine.adjustments.get(&QUOTE).unwrap_or(&0);

        let withdraw_cmd = balance_adjustment_cmd(UID, QUOTE, -600, 2);
        assert_eq!(engine.balance_adjustment(&withdraw_cmd, &mut ups), CommandResultCode::RiskNsf);

        // NSF：账户与守恒桶都不应变化。
        assert_eq!(ups.get(UID).unwrap().account(QUOTE), before_account);
        assert_eq!(*engine.adjustments.get(&QUOTE).unwrap_or(&0), before_adjustments);
    }

    #[test]
    fn balance_adjustment_withdrawal_within_withdrawable_succeeds() {
        let mut ups = UserProfileService::new();
        let mut engine = RiskEngine::new();
        engine.add_user(&add_user_cmd(UID), &mut ups);
        engine.balance_adjustment(&balance_adjustment_cmd(UID, QUOTE, 1000, 1), &mut ups);

        let withdraw_cmd = balance_adjustment_cmd(UID, QUOTE, -400, 2);
        assert_eq!(engine.balance_adjustment(&withdraw_cmd, &mut ups), CommandResultCode::Success);
        assert_eq!(ups.get(UID).unwrap().account(QUOTE), 600);
        assert_eq!(*engine.adjustments.get(&QUOTE).unwrap(), -600);
    }

    #[test]
    fn balance_adjustment_duplicate_order_id_is_already_applied_same_noop() {
        let mut ups = UserProfileService::new();
        let mut engine = RiskEngine::new();
        engine.add_user(&add_user_cmd(UID), &mut ups);

        let cmd = balance_adjustment_cmd(UID, QUOTE, 1000, 42);
        assert_eq!(engine.balance_adjustment(&cmd, &mut ups), CommandResultCode::Success);
        assert_eq!(ups.get(UID).unwrap().account(QUOTE), 1000);

        // 同 order_id 重复 → AlreadyAppliedSame，账户/adjustments 均不再变化（no-op）。
        let repeat = balance_adjustment_cmd(UID, QUOTE, 1000, 42);
        assert_eq!(
            engine.balance_adjustment(&repeat, &mut ups),
            CommandResultCode::UserMgmtAccountBalanceAdjustmentAlreadyAppliedSame
        );
        assert_eq!(ups.get(UID).unwrap().account(QUOTE), 1000);
        assert_eq!(*engine.adjustments.get(&QUOTE).unwrap(), -1000);

        // 不同 order_id 正常再次生效。
        let different_id = balance_adjustment_cmd(UID, QUOTE, 500, 43);
        assert_eq!(engine.balance_adjustment(&different_id, &mut ups), CommandResultCode::Success);
        assert_eq!(ups.get(UID).unwrap().account(QUOTE), 1500);
        assert_eq!(*engine.adjustments.get(&QUOTE).unwrap(), -1500);
    }

    #[test]
    fn balance_adjustment_nsf_does_not_claim_id_so_same_id_retry_after_funding_succeeds() {
        let mut ups = UserProfileService::new();
        let mut engine = RiskEngine::new();
        engine.add_user(&add_user_cmd(UID), &mut ups);
        engine.balance_adjustment(&balance_adjustment_cmd(UID, QUOTE, 500, 1), &mut ups);

        // 提现 600 超过可提余额 500 → NSF，order_id=99 未被 claim。
        let nsf_attempt = balance_adjustment_cmd(UID, QUOTE, -600, 99);
        assert_eq!(engine.balance_adjustment(&nsf_attempt, &mut ups), CommandResultCode::RiskNsf);

        // 补充资金后用同一 order_id=99 重试，必须放行（NSF 路径未 claim id）。
        engine.balance_adjustment(&balance_adjustment_cmd(UID, QUOTE, 1000, 2), &mut ups);
        let retry = balance_adjustment_cmd(UID, QUOTE, -600, 99);
        assert_eq!(engine.balance_adjustment(&retry, &mut ups), CommandResultCode::Success);
        assert_eq!(ups.get(UID).unwrap().account(QUOTE), 900); // 500 + 1000 - 600
    }

    #[test]
    fn balance_adjustment_unknown_user_is_auth_invalid_user() {
        let mut ups = UserProfileService::new();
        let mut engine = RiskEngine::new();
        let cmd = balance_adjustment_cmd(999, QUOTE, 100, 1);
        assert_eq!(engine.balance_adjustment(&cmd, &mut ups), CommandResultCode::AuthInvalidUser);
    }

    // ================================================================================
    // P4 Task 3：期货 R1 — place_order 期货分支 / can_place_margin_order NSF / CLOSE_POSITION
    // 参考文档 §3；Java `RiskEngine.java:432-503,533-623,823-875`。
    //
    // 搭建：单 symbol（FUT_SYMBOL，base/quote/currency scale 全 1——恒等缩放，NSF 算式手推
    // 便于核对）+ mark price 缓存 + 一个已建档、按需充值的用户。leverage 默认 1（未配置
    // init_margin 表 → calculateInitMargin = notional/leverage，即 100% 名义价值）。
    // ================================================================================

    const FUT_SYMBOL: i32 = 200;
    const FUT_BASE: i32 = 1;
    const FUT_QUOTE: i32 = 2;

    fn futures_spec_for(symbol_id: i32, taker_fee: i64, fee_scale_k: i64) -> CoreSymbolSpecification {
        CoreSymbolSpecification {
            symbol_id,
            symbol_type: SymbolType::FuturesContractPerpetual,
            base_currency: FUT_BASE,
            quote_currency: FUT_QUOTE,
            base_scale_k: 1,
            quote_scale_k: 1,
            taker_fee,
            maker_fee: 0,
            fee_scale_k,
            ..Default::default()
        }
    }

    fn futures_spec(taker_fee: i64, fee_scale_k: i64) -> CoreSymbolSpecification {
        futures_spec_for(FUT_SYMBOL, taker_fee, fee_scale_k)
    }

    fn setup_futures(
        taker_fee: i64,
        fee_scale_k: i64,
        quote_balance: i64,
        mark_price: i64,
    ) -> (RiskEngine, UserProfileService, SymbolSpecificationProvider) {
        let mut ssp = SymbolSpecificationProvider::new();
        assert_eq!(ssp.add_symbol(futures_spec(taker_fee, fee_scale_k)), CommandResultCode::Success);
        ssp.add_currency(CoreCurrencySpecification { currency: FUT_QUOTE, currency_scale_k: 1 });
        ssp.add_currency(CoreCurrencySpecification { currency: FUT_BASE, currency_scale_k: 1 });

        let mut ups = UserProfileService::new();
        assert_eq!(ups.add_empty_user_profile(UID), CommandResultCode::Success);
        ups.get_mut(UID).unwrap().add_to_account(FUT_QUOTE, quote_balance);

        let mut engine = RiskEngine::new();
        engine.last_price_cache.insert(FUT_SYMBOL, mark_price);
        (engine, ups, ssp)
    }

    fn futures_place_cmd(
        action: OrderAction,
        size: i64,
        price: i64,
        leverage: i32,
        margin_mode: MarginMode,
        reduce_only: bool,
    ) -> OrderCommand {
        OrderCommand {
            command: OrderCommandType::PlaceOrder,
            order_id: 1,
            symbol: FUT_SYMBOL,
            price,
            size,
            action: Some(action),
            order_type: Some(OrderType::Gtc),
            uid: UID,
            leverage,
            margin_mode,
            order_flags: if reduce_only {
                crate::core::common::cmd::order_command::FLAG_REDUCE_ONLY
            } else {
                0
            },
            ..Default::default()
        }
    }

    // --------------------------------------------------------------------------
    // 成功开仓 + pending 记录 —— 固定费（fee_scale_k=0）+ ISOLATED 一组
    // --------------------------------------------------------------------------

    #[test]
    fn futures_place_order_long_sufficient_margin_fixed_fee_is_valid_and_records_pending() {
        // taker_fee=2（固定，每手 2）；balance=10_000（充裕）；mark=100。
        let (mut engine, mut ups, ssp) = setup_futures(2, 0, 10_000, 100);
        let mut cmd = futures_place_cmd(OrderAction::Bid, 10, 100, 1, MarginMode::Isolated, false);

        // 手推：positionMargin=1000（新开 notional 1000/leverage 1）+ pendingFee=20（10*2 固定费）
        // + openLoss=0（cmd.price==mark）= required 1020 ≤ spendable 10_000。
        assert_eq!(
            engine.place_order_risk_check(&mut cmd, &mut ups, &ssp),
            CommandResultCode::ValidForMatchingEngine
        );

        let position =
            ups.get_mut(UID).unwrap().positions.get(&FUT_SYMBOL).expect("NSF 通过后 position 必须已提交入 map");
        assert_eq!(position.pending_buy_size, 10);
        assert_eq!(position.pending_buy_avg_price, 100);
        assert_eq!(position.open_volume, 0); // R1 只挂单占用，未成交（成交结算在 R2，P4 Task 4+）
        assert_eq!(position.leverage, 1);
        assert_eq!(position.margin_mode, MarginMode::Isolated);
    }

    #[test]
    fn futures_place_order_insufficient_margin_fixed_fee_is_nsf_and_position_not_created() {
        // 同上参数，但 balance=1_000 < required 1020 → NSF；且不得污染 positions map。
        let (mut engine, mut ups, ssp) = setup_futures(2, 0, 1_000, 100);
        let mut cmd = futures_place_cmd(OrderAction::Bid, 10, 100, 1, MarginMode::Isolated, false);

        assert_eq!(engine.place_order_risk_check(&mut cmd, &mut ups, &ssp), CommandResultCode::RiskNsf);
        assert!(
            !ups.get_mut(UID).unwrap().positions.contains_key(&FUT_SYMBOL),
            "NSF 失败不得插入 position（NSF 前不插入是 P4 Task 3 的核心约束）"
        );
    }

    // --------------------------------------------------------------------------
    // 成功开仓 / NSF 边界 —— 比例费（fee_scale_k>0）+ ISOLATED 一组
    // --------------------------------------------------------------------------

    #[test]
    fn futures_place_order_proportional_fee_sufficient_margin_is_valid() {
        // taker_fee=100/fee_scale_k=10_000（1% 比例费率）：pendingFee=ceil(10*100*100/10_000)=10。
        // required=1000(positionMargin)+10(pendingFee)+0(openLoss)=1010 ≤ spendable 2_000。
        let (mut engine, mut ups, ssp) = setup_futures(100, 10_000, 2_000, 100);
        let mut cmd = futures_place_cmd(OrderAction::Bid, 10, 100, 1, MarginMode::Isolated, false);
        assert_eq!(
            engine.place_order_risk_check(&mut cmd, &mut ups, &ssp),
            CommandResultCode::ValidForMatchingEngine
        );
    }

    #[test]
    fn futures_place_order_proportional_fee_insufficient_margin_is_nsf() {
        // required=1010 > spendable 1_005 → NSF（验证比例费模型下 pendingFee 确实被算入比较）。
        let (mut engine, mut ups, ssp) = setup_futures(100, 10_000, 1_005, 100);
        let mut cmd = futures_place_cmd(OrderAction::Bid, 10, 100, 1, MarginMode::Isolated, false);
        assert_eq!(engine.place_order_risk_check(&mut cmd, &mut ups, &ssp), CommandResultCode::RiskNsf);
    }

    // --------------------------------------------------------------------------
    // 杠杆超档 → RiskInvalidLeverage
    // --------------------------------------------------------------------------

    #[test]
    fn futures_place_order_leverage_exceeds_tier_is_invalid_leverage() {
        // max_leverage 表只配一档 {floor:0 -> 5}：floor_value 对任意 notional 都定位到这唯一档，
        // 相当于全局杠杆上限 5x（语义见 CoreSymbolSpecification::floor_value 文档）。
        let mut spec = futures_spec(2, 0);
        spec.max_leverage.insert(0, 5);
        let mut ssp = SymbolSpecificationProvider::new();
        assert_eq!(ssp.add_symbol(spec), CommandResultCode::Success);
        ssp.add_currency(CoreCurrencySpecification { currency: FUT_QUOTE, currency_scale_k: 1 });
        ssp.add_currency(CoreCurrencySpecification { currency: FUT_BASE, currency_scale_k: 1 });

        let mut ups = UserProfileService::new();
        assert_eq!(ups.add_empty_user_profile(UID), CommandResultCode::Success);
        ups.get_mut(UID).unwrap().add_to_account(FUT_QUOTE, 100_000);

        let mut engine = RiskEngine::new();
        engine.last_price_cache.insert(FUT_SYMBOL, 100);

        // leverage=10 > 5x 上限，即便资金充裕也应在 isValidLeverage 处直接拒绝（NSF 检查之前）。
        let mut cmd = futures_place_cmd(OrderAction::Bid, 10, 100, 10, MarginMode::Isolated, false);
        assert_eq!(
            engine.place_order_risk_check(&mut cmd, &mut ups, &ssp),
            CommandResultCode::RiskInvalidLeverage
        );
    }

    // --------------------------------------------------------------------------
    // marginMode / leverage 跨腿一致性
    // --------------------------------------------------------------------------

    #[test]
    fn futures_place_order_margin_mode_mismatch_against_existing_position() {
        let (mut engine, mut ups, ssp) = setup_futures(2, 0, 100_000, 100);
        ups.get_mut(UID)
            .unwrap()
            .positions
            .insert(FUT_SYMBOL, SymbolPositionRecord::new(UID, FUT_SYMBOL, FUT_QUOTE, MarginMode::Isolated, 3));

        // 同 leverage(3)，marginMode 与现仓（ISOLATED）冲突（CROSS）→ 在 NSF 检查之前拒绝。
        let mut cmd = futures_place_cmd(OrderAction::Bid, 10, 100, 3, MarginMode::Cross, false);
        assert_eq!(
            engine.place_order_risk_check(&mut cmd, &mut ups, &ssp),
            CommandResultCode::RiskMarginModeMismatch
        );
    }

    #[test]
    fn futures_place_order_leverage_mismatch_against_existing_position() {
        let (mut engine, mut ups, ssp) = setup_futures(2, 0, 100_000, 100);
        ups.get_mut(UID)
            .unwrap()
            .positions
            .insert(FUT_SYMBOL, SymbolPositionRecord::new(UID, FUT_SYMBOL, FUT_QUOTE, MarginMode::Isolated, 3));

        // 同 marginMode(ISOLATED)，leverage 与现仓（3）冲突（5）。
        let mut cmd = futures_place_cmd(OrderAction::Bid, 10, 100, 5, MarginMode::Isolated, false);
        assert_eq!(
            engine.place_order_risk_check(&mut cmd, &mut ups, &ssp),
            CommandResultCode::RiskLeverageMismatch
        );
    }

    // --------------------------------------------------------------------------
    // ONEWAY + reduce-only 夹（§2 "ONEWAY 只减仓特例"）
    // --------------------------------------------------------------------------

    #[test]
    fn futures_place_order_oneway_reduce_only_clamps_size_to_open_volume_and_succeeds() {
        let (mut engine, mut ups, ssp) = setup_futures(2, 0, 100_000, 100);
        let mut pos = SymbolPositionRecord::new(UID, FUT_SYMBOL, FUT_QUOTE, MarginMode::Isolated, 1);
        pos.direction = PositionDirection::Long;
        pos.open_volume = 5;
        pos.open_price_sum = 500; // 均价 100
        pos.open_init_margin_sum = 500; // 与未配置 init_margin 表下 notional/leverage 一致
        ups.get_mut(UID).unwrap().positions.insert(FUT_SYMBOL, pos);

        // reduce-only ASK（与现仓 LONG 反向）请求 size=20 > openVolume=5 → 夹到 5。
        let mut cmd = futures_place_cmd(OrderAction::Ask, 20, 100, 1, MarginMode::Isolated, true);
        assert_eq!(
            engine.place_order_risk_check(&mut cmd, &mut ups, &ssp),
            CommandResultCode::ValidForMatchingEngine
        );
        assert_eq!(cmd.size, 5, "reduce-only 请求量必须被夹到可平量 openVolume");

        let updated = ups.get_mut(UID).unwrap().positions.get(&FUT_SYMBOL).unwrap();
        assert_eq!(updated.pending_sell_size, 5);
    }

    #[test]
    fn futures_place_order_oneway_reduce_only_no_position_clamps_to_zero_is_noop_success() {
        let (mut engine, mut ups, ssp) = setup_futures(2, 0, 100_000, 100);

        // 无仓时 maxClosableSize 恒 0——reduce-only 直接 SUCCESS no-op，绝不开新敞口。
        let mut cmd = futures_place_cmd(OrderAction::Ask, 5, 100, 1, MarginMode::Isolated, true);
        assert_eq!(engine.place_order_risk_check(&mut cmd, &mut ups, &ssp), CommandResultCode::Success);
        assert!(!ups.get_mut(UID).unwrap().positions.contains_key(&FUT_SYMBOL));
    }

    // --------------------------------------------------------------------------
    // crossFreeMargin 循环 —— CROSS 浮盈可抵扣 / ISOLATED 只扣不抵（§3.3 ②，最易出错的部分）
    // --------------------------------------------------------------------------

    #[test]
    fn futures_place_order_cross_position_unrealized_profit_credits_other_symbol_order() {
        const OTHER_SYMBOL: i32 = 201;
        let (mut engine, mut ups, mut ssp) = setup_futures(2, 0, 600, 100);
        assert_eq!(ssp.add_symbol(futures_spec_for(OTHER_SYMBOL, 2, 0)), CommandResultCode::Success);
        engine.last_price_cache.insert(OTHER_SYMBOL, 200); // OTHER_SYMBOL mark 涨到 200

        // 已有 CROSS 仓（OTHER_SYMBOL，LONG open_volume=10 @ 均价 100）：
        // estimatePnl = (10*200 - 1000)*+1 = 1000；calculateRequiredMarginForFutures = 500
        //（openInitMarginSum=500，无 pending，新增敞口 0）。
        let mut other_pos = SymbolPositionRecord::new(UID, OTHER_SYMBOL, FUT_QUOTE, MarginMode::Cross, 1);
        other_pos.direction = PositionDirection::Long;
        other_pos.open_volume = 10;
        other_pos.open_price_sum = 1000;
        other_pos.open_init_margin_sum = 500;
        ups.get_mut(UID).unwrap().positions.insert(OTHER_SYMBOL, other_pos);

        // 新单（FUT_SYMBOL，全新仓）required(不计cross抵扣)=1020（同 fixed_fee 成功用例）；
        // crossFreeMargin = scale(1000) - scale(500) = 500 → required 实际 = 1020-500=520。
        // spendable=600 ≥ 520：若没有 CROSS 抵扣（cross_free_margin 算错成 0），600 < 1020 应报
        // NSF——本测试断言 ValidForMatchingEngine，专门锁定"CROSS 其它仓浮盈能抵扣"这条通路。
        let mut cmd = futures_place_cmd(OrderAction::Bid, 10, 100, 1, MarginMode::Isolated, false);
        assert_eq!(
            engine.place_order_risk_check(&mut cmd, &mut ups, &ssp),
            CommandResultCode::ValidForMatchingEngine
        );
    }

    #[test]
    fn futures_place_order_isolated_other_position_margin_is_deducted_without_pnl_credit() {
        const OTHER_SYMBOL: i32 = 201;
        // balance=1_000：若 ISOLATED 其它仓的锁定保证金被错误地"抵扣浮盈但不扣保证金"（对称处理），
        // 1_000 会 ≥ 1020 通过；正确实现须把该仓 calculateRequiredMarginForFutures(=500) 也从
        // spendable 里剥离（不加浮盈抵扣，因为 marginMode=ISOLATED），required 实际 = 1020-(0-500)
        // = 1520 > 1_000 → 必须 NSF。
        let (mut engine, mut ups, mut ssp) = setup_futures(2, 0, 1_000, 100);
        assert_eq!(ssp.add_symbol(futures_spec_for(OTHER_SYMBOL, 2, 0)), CommandResultCode::Success);
        engine.last_price_cache.insert(OTHER_SYMBOL, 200);

        let mut other_pos = SymbolPositionRecord::new(UID, OTHER_SYMBOL, FUT_QUOTE, MarginMode::Isolated, 1);
        other_pos.direction = PositionDirection::Long;
        other_pos.open_volume = 10;
        other_pos.open_price_sum = 1000;
        other_pos.open_init_margin_sum = 500;
        ups.get_mut(UID).unwrap().positions.insert(OTHER_SYMBOL, other_pos);

        let mut cmd = futures_place_cmd(OrderAction::Bid, 10, 100, 1, MarginMode::Isolated, false);
        assert_eq!(engine.place_order_risk_check(&mut cmd, &mut ups, &ssp), CommandResultCode::RiskNsf);
    }

    // --------------------------------------------------------------------------
    // CLOSE_POSITION（§3 CLOSE_POSITION）
    // --------------------------------------------------------------------------

    #[test]
    fn close_position_risk_check_no_position_is_noop_success() {
        let (mut engine, mut ups, ssp) = setup_futures(2, 0, 100_000, 100);
        let mut cmd = OrderCommand {
            command: OrderCommandType::ClosePosition,
            symbol: FUT_SYMBOL,
            action: Some(OrderAction::Ask),
            size: 10,
            price: 100,
            uid: UID,
            ..Default::default()
        };
        assert_eq!(engine.close_position_risk_check(&mut cmd, &mut ups, &ssp), CommandResultCode::Success);
    }

    #[test]
    fn close_position_risk_check_existing_position_clamps_size_and_forces_leverage_margin_mode() {
        let (mut engine, mut ups, ssp) = setup_futures(2, 0, 100_000, 100);
        let mut pos = SymbolPositionRecord::new(UID, FUT_SYMBOL, FUT_QUOTE, MarginMode::Cross, 7);
        pos.direction = PositionDirection::Long;
        pos.open_volume = 5;
        pos.open_price_sum = 500;
        ups.get_mut(UID).unwrap().positions.insert(FUT_SYMBOL, pos);

        let mut cmd = OrderCommand {
            command: OrderCommandType::ClosePosition,
            symbol: FUT_SYMBOL,
            action: Some(OrderAction::Ask), // 反向平多
            size: 999,                      // 请求量超过持仓 → 应收敛到 open_volume=5
            price: 100,
            uid: UID,
            leverage: 1,                       // 与仓位 leverage(7) 不同——应被强制覆盖
            margin_mode: MarginMode::Isolated,  // 与仓位 marginMode(Cross) 不同——应被强制覆盖
            ..Default::default()
        };

        assert_eq!(
            engine.close_position_risk_check(&mut cmd, &mut ups, &ssp),
            CommandResultCode::ValidForMatchingEngine
        );
        assert_eq!(cmd.size, 5);
        assert_eq!(cmd.leverage, 7);
        assert_eq!(cmd.margin_mode, MarginMode::Cross);

        let updated = ups.get_mut(UID).unwrap().positions.get(&FUT_SYMBOL).unwrap();
        assert_eq!(updated.pending_sell_size, 5);
    }

    #[test]
    fn close_position_risk_check_unsupported_symbol_type_for_spot() {
        // CLOSE_POSITION 对现货 symbol 应直接拒绝（isFuturesContract 守卫），不查 positions。
        let mut ssp = SymbolSpecificationProvider::new();
        assert_eq!(
            ssp.add_symbol(CoreSymbolSpecification {
                symbol_id: SYMBOL,
                symbol_type: SymbolType::CurrencyExchangePair,
                base_currency: BASE,
                quote_currency: QUOTE,
                base_scale_k: 1,
                quote_scale_k: 1,
                ..Default::default()
            }),
            CommandResultCode::Success
        );
        let mut ups = UserProfileService::new();
        assert_eq!(ups.add_empty_user_profile(UID), CommandResultCode::Success);
        let mut engine = RiskEngine::new();

        let mut cmd = OrderCommand {
            command: OrderCommandType::ClosePosition,
            symbol: SYMBOL,
            action: Some(OrderAction::Ask),
            size: 10,
            price: 100,
            uid: UID,
            ..Default::default()
        };
        assert_eq!(
            engine.close_position_risk_check(&mut cmd, &mut ups, &ssp),
            CommandResultCode::UnsupportedSymbolType
        );
    }

    // --------------------------------------------------------------------------
    // pre_process_command 路由：CLOSE_POSITION → close_position_risk_check
    // --------------------------------------------------------------------------

    #[test]
    fn pre_process_command_routes_close_position_to_close_position_risk_check() {
        let (mut engine, mut ups, ssp) = setup_futures(2, 0, 100_000, 100);
        let mut pos = SymbolPositionRecord::new(UID, FUT_SYMBOL, FUT_QUOTE, MarginMode::Isolated, 1);
        pos.direction = PositionDirection::Long;
        pos.open_volume = 5;
        pos.open_price_sum = 500;
        ups.get_mut(UID).unwrap().positions.insert(FUT_SYMBOL, pos);

        let mut cmd = OrderCommand {
            command: OrderCommandType::ClosePosition,
            symbol: FUT_SYMBOL,
            action: Some(OrderAction::Ask),
            size: 999,
            price: 100,
            uid: UID,
            ..Default::default()
        };
        engine.pre_process_command(&mut cmd, &mut ups, &ssp);
        assert_eq!(cmd.result_code, Some(CommandResultCode::ValidForMatchingEngine));
        assert_eq!(cmd.size, 5);
    }
}
