//! 对应 Java `RiskEngine`（现货 :399-685，期货 :436-503/533-623/823-875）。
//! Ruling P3-B：不持有 ups/ssp 所有权，按需借用；单 shard 单线程，不做 uid 分片。

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
use crate::core::common::batch_add_loan_command::BatchAddLoanCommand;
use crate::core::common::core_currency_specification::CoreCurrencySpecification;
use crate::core::common::core_symbol_specification::CoreSymbolSpecification;
use crate::core::common::adl_user_position::AdlUserPosition;
use crate::core::processors::adl_command_processor::AdlCommandProcessor;
use crate::core::processors::funding_fee_command_processor::FundingFeeCommandProcessor;
use crate::core::processors::if_command_processor::IfCommandProcessor;
use crate::core::processors::internal_transfer_processor::InternalTransferProcessor;
use crate::core::processors::liquidation::liquidation_engine::LiquidationEngine;
use crate::core::processors::liquidation::liquidation_service::LiquidationService;
use crate::core::processors::loan::loan_command_dispatcher::LoanCommandDispatcher;
use crate::core::processors::loan::loan_service::LoanService;
use crate::core::processors::loan_rate_pricing_processor::LoanRatePricingProcessor;
use crate::core::utils::core_arithmetic_utils as arithmetic;

/// 对应 Java `Math.multiplyExact(long, long)`；局部私有重复，对齐 symbol_position_record.rs 同款 helper。
fn mul_exact(a: i64, b: i64) -> i64 {
    i64::try_from(a as i128 * b as i128).unwrap_or_else(|_| panic!("overflow: {a} * {b}"))
}

/// 对应 Java `Math.subtractExact(long, long)`。
fn sub_exact(a: i64, b: i64) -> i64 {
    i64::try_from(a as i128 - b as i128).unwrap_or_else(|_| panic!("overflow: {a} - {b}"))
}

/// 对应 Java `RiskEngine`（现货+期货R1子集）；suspends 桶未移植（SUSPEND_USER 未落地）。
#[derive(Debug, Default, serde::Serialize, serde::Deserialize)]
pub struct RiskEngine {
    pub adjustments: BTreeMap<i32, i64>,
    pub fees: BTreeMap<i32, i64>,
    pub last_price_cache: BTreeMap<i32, i64>,
    pub cfg_margin_trading_enabled: bool,
    /// 对应 Java `RiskEngine.loanService`：per-shard 借贷池状态+利率模型，供 LoanCommandDispatcher 读写；默认空，对现货/期货既有路径 no-op。
    pub loan_service: LoanService,
    /// 对应 Java `RiskEngine.liquidationService`：per-shard 期货保险基金（IF）状态，notionals/positions 都进 state_hash（Ruling P6-E），与 loan LIF 独立。
    pub liquidation_service: LiquidationService,
    /// 对应 Java `RiskEngine.liquidationEngine`：per-shard 期货强平引擎，仅 leader-local 不进 state_hash/snapshot（Ruling P6-E）；`#[serde(skip)]` 换届后 from_snapshot_bytes 重建索引。
    #[serde(skip)]
    pub liquidation_engine: LiquidationEngine,
}

impl RiskEngine {
    /// Ruling P4-B：cfg_margin_trading_enabled 默认 true（唯一构造入口，derive Default 不受影响）。
    pub fn new() -> Self {
        RiskEngine {
            adjustments: BTreeMap::new(),
            fees: BTreeMap::new(),
            last_price_cache: BTreeMap::new(),
            cfg_margin_trading_enabled: true,
            loan_service: LoanService::new(),
            liquidation_service: LiquidationService::new(),
            liquidation_engine: LiquidationEngine::new(),
        }
    }

    /// 对应 Java `lastPriceCache.get(symbol).markPrice`：None 与 Some(0) 统一折叠成 None（等价 Java 的 null/0 双判）。
    pub fn mark_price(&self, symbol: i32) -> Option<i64> {
        match self.last_price_cache.get(&symbol) {
            Some(&p) if p != 0 => Some(p),
            _ => None,
        }
    }

    /// 对应 Java `RiskEngine.preProcessCommand`：R1 入口，按 cmd.command 路由（非交易命令整块委托/PlaceOrder→风控冻结/ClosePosition→纯减仓/其余直落 ME）。返回 `()`（Java 的 boolean 批次控制信号无单线程语义）。
    pub fn pre_process_command(
        &mut self,
        cmd: &mut OrderCommand,
        ups: &mut UserProfileService,
        ssp: &SymbolSpecificationProvider,
    ) {
        // 对应 Java preProcessCommand（:260-264）第一级门守：is_loan() 命中→整块委托 LoanCommandDispatcher（与 is_non_trading 互斥）。
        if cmd.command.is_loan() {
            cmd.result_code = Some(LoanCommandDispatcher::dispatch(self, cmd, ups, ssp));
            return;
        }
        if cmd.command.is_non_trading() {
            let rc = match cmd.command {
                OrderCommandType::AddUser => self.add_user(cmd, ups),
                OrderCommandType::BalanceAdjustment => self.balance_adjustment(cmd, ups, ssp),
                OrderCommandType::MarginAdjustment => self.margin_adjustment(cmd, ups, ssp),
                OrderCommandType::LeverageAdjustment => self.leverage_adjustment(cmd, ups, ssp),
                OrderCommandType::MarkpriceAdjustment => self.markprice_adjustment(cmd, ups, ssp),
                OrderCommandType::RepriceLoanRates => self.reprice_loan_rates_collect(cmd),
                OrderCommandType::InternalTransfer => self.internal_transfer_collect(cmd, ups, ssp),
                OrderCommandType::IfDeposit => self.if_deposit(cmd, ssp),
                OrderCommandType::IfWithdraw => self.if_withdraw(cmd, ssp),
                OrderCommandType::SettlePnl => self.settle_pnl(cmd, ups, ssp),
                _ => CommandResultCode::MatchingUnsupportedCommand,
            };
            cmd.result_code = Some(rc);
            return;
        }

        if cmd.command == OrderCommandType::PlaceOrder {
            cmd.result_code = Some(self.place_order_risk_check(cmd, ups, ssp));
        } else if cmd.command == OrderCommandType::ClosePosition {
            cmd.result_code = Some(self.close_position_risk_check(cmd, ups, ssp));
        } else if cmd.command == OrderCommandType::SettleFundingfees {
            // SETTLE_FUNDINGFEES 不是 is_non_trading()，停留在主交易 switch。
            cmd.result_code = Some(self.settle_funding_fees_collect(cmd, ups, ssp));
        } else if cmd.command == OrderCommandType::ForceLiquidation {
            // FORCE_LIQUIDATION R1，对应 Java :291：normalize_cmd_position_size 夹取 size 后走 IOC 平仓单撮合。
            cmd.result_code = Some(Self::normalize_cmd_position_size(cmd, ups));
        } else if cmd.command == OrderCommandType::IfTakeover {
            // IF_TAKEOVER 不是 is_non_trading()，对应 Java :370：先 normalize_cmd_position_size 再 collect（结果码以 collect 为准）。
            Self::normalize_cmd_position_size(cmd, ups);
            cmd.result_code = Some(self.if_takeover_collect(cmd));
        } else if cmd.command == OrderCommandType::AutoDeleveraging {
            // AUTO_DELEVERAGING 不是 is_non_trading()，对应 Java :378：先 normalize_cmd_position_size 再 collect。
            Self::normalize_cmd_position_size(cmd, ups);
            cmd.result_code = Some(self.adl_collect(cmd, ups, ssp));
        } else if cmd.command == OrderCommandType::LiquidationScan {
            // LIQUIDATION_SCAN R1，对应 Java :324-327：纯扫描（check_positions），单 shard 恒 Success。
            self.liquidation_engine.check_positions(cmd, ups, ssp, &self.last_price_cache, &self.loan_service);
            cmd.result_code = Some(CommandResultCode::Success);
        }
        // CancelOrder/MoveOrder/ReduceOrder/OrderBookRequest/Reset/Nop：R1 无动作。
    }

    /// 对应 Java `placeOrderRiskCheck`（:399-420）：加载 profile/spec（缺失分别→AuthInvalidUser/InvalidSymbol），转 place_order。省略 cfgIgnoreRiskProcessing（未移植，恒走风控）。
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

    /// 对应 Java `placeOrder`（:432-503）：现货→place_exchange_order；期货→校验分配仓位后 can_place_margin_order NSF 检查；非现货非期货→UnsupportedSymbolType（不 panic，:437-439）。
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
            return self.place_exchange_order(cmd, user_profile, spec, currency_spec, ssp);
        }
        if !spec.symbol_type.is_futures_contract() {
            // 对应 Java placeOrder（:437-439）：非现货非期货→UnsupportedSymbolType，绝不 panic（Option 型 symbol 今天已可注册，R1 热路径不能 crash）。
            return CommandResultCode::UnsupportedSymbolType;
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
        // 新仓需登记进 symbol_to_users 索引（对应 Java onPositionOpened，:490-491，仅新仓）。
        let is_new_position = !user_profile.positions.contains_key(&position_key);
        let mut position = match user_profile.positions.get(&position_key) {
            Some(existing) => existing.clone(),
            None => {
                let mut p = SymbolPositionRecord::default();
                p.initialize(user_profile.uid, spec.symbol_id, spec.quote_currency, action, cmd.leverage, cmd.margin_mode);
                p
            }
        };

        // ONEWAY+reduce-only：裁剪 size 到可平量；同向/无仓直接 Success no-op。
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

        // 校验全过：BUDGET 单用 pendingHoldBudget，普通限价用 pendingHold，再 commit 回 map。
        if matches!(cmd.order_type, Some(OrderType::FokBudget) | Some(OrderType::IocBudget)) {
            position.pending_hold_budget(action, cmd.size, cmd.price);
        } else {
            position.pending_hold(action, cmd.size, cmd.price);
        }
        user_profile.positions.insert(position_key, position);
        // 新仓 commit 后登记进强平索引（对应 Java onPositionOpened，仅新仓）。
        if is_new_position {
            self.liquidation_engine.on_position_opened(user_profile.uid, spec.symbol_id);
        }

        CommandResultCode::ValidForMatchingEngine
    }

    /// 对应 Java `canPlaceMarginOrder`（:533-623）：期货下单 NSF 校验，五项加总 scale 后比较可支配额度。
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

        // ① positionMargin：本仓含新挂单后总保证金；-1 哨兵→回退 calculateRequiredMarginForFutures。
        let order_notional = if is_budget_order { cmd.price } else { mul_exact(cmd.size, cmd.price) };
        let new_order_margin = position.calculate_required_margin_for_order(spec, action, order_notional);
        let position_margin = if new_order_margin == -1 {
            position.calculate_required_margin_for_futures(spec)
        } else {
            new_order_margin
        };

        // ② crossFreeMargin：遍历账户所有仓位——本仓 CROSS 才加浮盈；其它仓 CROSS 加浮盈、且无论 marginMode 都减其占用保证金。
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

        // ③ pendingFee：本单成交按 taker rate 预扣估算（NSF 预检用，不实收）。
        let pending_fee = if is_budget_order {
            position.calculate_pending_fee_for_order_budget(spec, action, cmd.size, cmd.price)
        } else {
            position.calculate_pending_fee_for_order(spec, action, cmd.size, cmd.price)
        };

        // ④ openLoss：开仓瞬间浮亏预留（防"开仓即爆仓"），BUDGET 单跳过；openingSize 为 ONEWAY 反向时先抵掉 openVolume 的剩余部分，否则为全部 cmd.size。
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

        // ⑤ 比较：可支配 = accounts − 现货冻结 − 借贷抵押；需求 = scale(positionMargin+pendingFee+openLoss) − crossFreeMargin。
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

    /// 对应 Java `loanCollateralLocked`（:1063-1072）：借贷抵押虚拟锁定额（currency scale）= Isolated 各 loan collateral_amount 求和 + Cross 池 currency 直取。抵押物仍在 accounts 里未物理转移，只是防止被顶用。
    fn loan_collateral_locked(&self, user_profile: &UserProfile, currency: i32) -> i64 {
        let mut locked: i64 = 0;
        for loan in user_profile.isolated_loans.values() {
            if loan.collateral_currency == currency {
                locked += loan.collateral_amount;
            }
        }
        locked += user_profile.cross_loan_collateral.get(&currency).copied().unwrap_or(0);
        locked
    }

    /// 对应 Java `calculateLockedMargin`（:1079-1083）：单仓期货保证金占用折算到 currency 记账单位，供 calculate_locked 跨 symbol 累加。
    fn calculate_locked_margin(
        &self,
        position: &SymbolPositionRecord,
        spec: &CoreSymbolSpecification,
        currency_spec: &CoreCurrencySpecification,
    ) -> i64 {
        let required = position.calculate_required_margin_for_futures(spec);
        arithmetic::size_price_to_currency_scale(
            required,
            spec.base_scale_k,
            spec.quote_scale_k,
            currency_spec.currency_scale_k,
        )
    }

    /// 对应 Java `calculateLocked`（:1040-1055）：用户某 currency 全量锁定额 = 期货保证金占用 + 现货冻结 + 借贷抵押。仅用于报表/事件/结算后余额校验，四处 NSF 场景不直接调用（各走 calculate_free_futures_margin）。
    pub fn calculate_locked(
        &self,
        user_profile: &UserProfile,
        currency: i32,
        ssp: &SymbolSpecificationProvider,
        currency_spec: &CoreCurrencySpecification,
    ) -> i64 {
        let mut locked: i64 = 0;
        for position in user_profile.positions.values() {
            if position.currency == currency {
                let spec = ssp
                    .get_symbol(position.symbol)
                    .unwrap_or_else(|| panic!("symbol spec missing for symbol {}", position.symbol));
                locked += self.calculate_locked_margin(position, spec, currency_spec);
            }
        }
        locked += user_profile.locked(currency);
        locked += self.loan_collateral_locked(user_profile, currency);
        locked
    }

    /// 对应 Java `calculateFreeFuturesMargin(UserProfile, int)`（:759-761）：双参重载，转发三参版本 cur_pos_symbol=-1（逐仓浮盈一律不计入）。
    pub fn calculate_free_futures_margin(
        &self,
        user_profile: &UserProfile,
        currency: i32,
        ssp: &SymbolSpecificationProvider,
    ) -> i64 {
        self.calculate_free_futures_margin_for_symbol(user_profile, currency, -1, ssp)
    }

    /// 对应 Java `calculateFreeFuturesMargin(UserProfile, int, int curPosSymbol)`（:767-805）：账户净期货盈余取两保守估计 min（计/不计未实现盈亏两种 CROSS 保证金口径）；cur_pos_symbol 仅该 symbol ISOLATED 仓浮盈计入。
    fn calculate_free_futures_margin_for_symbol(
        &self,
        user_profile: &UserProfile,
        currency: i32,
        cur_pos_symbol: i32,
        ssp: &SymbolSpecificationProvider,
    ) -> i64 {
        // Ruling P4-A 短路：该 currency 无期货持仓时五项累加器恒为 0，提前返回，避免纯现货用户被迫注册期货 currency spec。
        if !user_profile.positions.values().any(|p| p.currency == currency) {
            return 0;
        }
        let currency_spec = ssp
            .get_currency(currency)
            .unwrap_or_else(|| panic!("currency spec missing for currency {currency}"));

        let mut realized_pnl: i64 = 0;
        let mut unrealized_pnl: i64 = 0;
        let mut isolated_required_margin: i64 = 0;
        let mut cross_initial_margin: i64 = 0;
        let mut cross_maintenance_margin: i64 = 0;

        for position in user_profile.positions.values() {
            if position.currency != currency {
                continue;
            }
            let spec = ssp
                .get_symbol(position.symbol)
                .unwrap_or_else(|| panic!("symbol spec missing for symbol {}", position.symbol));
            let mark = self
                .mark_price(position.symbol)
                .unwrap_or_else(|| panic!("mark price missing for open position symbol {}", position.symbol));

            realized_pnl += arithmetic::size_price_to_currency_scale(
                position.profit,
                spec.base_scale_k,
                spec.quote_scale_k,
                currency_spec.currency_scale_k,
            );

            if position.margin_mode == MarginMode::Cross {
                unrealized_pnl += arithmetic::size_price_to_currency_scale(
                    position.estimate_unrealized_profit(mark),
                    spec.base_scale_k,
                    spec.quote_scale_k,
                    currency_spec.currency_scale_k,
                );
                let initial_margin = position.calculate_required_margin_for_futures(spec);
                cross_initial_margin += arithmetic::size_price_to_currency_scale(
                    initial_margin,
                    spec.base_scale_k,
                    spec.quote_scale_k,
                    currency_spec.currency_scale_k,
                );
                // 维持保证金口径：把已锁的初始保证金换成维持保证金。
                let maintenance_margin = initial_margin - position.open_init_margin_sum
                    + position.calculate_maintenance_margin(spec, mark);
                cross_maintenance_margin += arithmetic::size_price_to_currency_scale(
                    maintenance_margin,
                    spec.base_scale_k,
                    spec.quote_scale_k,
                    currency_spec.currency_scale_k,
                );
            } else {
                // 逐仓浮盈只能参与自身 symbol 的分摊。
                if position.symbol == cur_pos_symbol {
                    unrealized_pnl += arithmetic::size_price_to_currency_scale(
                        position.estimate_unrealized_profit(mark),
                        spec.base_scale_k,
                        spec.quote_scale_k,
                        currency_spec.currency_scale_k,
                    );
                }
                isolated_required_margin += self.calculate_locked_margin(position, spec, currency_spec);
            }
        }

        (realized_pnl + unrealized_pnl - cross_initial_margin - isolated_required_margin)
            .min(realized_pnl - cross_maintenance_margin - isolated_required_margin)
    }

    /// 对应 Java `closePositionRiskCheck`（:823-865）：CLOSE_POSITION R1，纯减仓无新敞口。缺仓或可平量<=0 恒 Success no-op；否则收敛 size 到可平量，leverage/marginMode 强制随仓，pendingHold 占用。
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

    /// 对应 Java `maxClosableSize`（:870-875）：可平量，仅方向相反时有意义，同向/EMPTY 返回 0（防止误开新敞口）。
    fn max_closable_size(pos: &SymbolPositionRecord, action: OrderAction, requested_size: i64) -> i64 {
        if !pos.direction.is_opposite_to_action(action) {
            return 0;
        }
        requested_size.min(pos.open_volume)
    }

    /// 对应 Java `placeExchangeOrder`（:633-685）：现货下单冻结（BID 锁 quote/ASK 锁 base）；NSF = accounts − exchange_locked − loan_locked − order_lock + freeFuturesMargin < 0（Ruling P4-A：无期货仓时恒 0）。
    fn place_exchange_order(
        &mut self,
        cmd: &OrderCommand,
        user_profile: &mut UserProfile,
        spec: &CoreSymbolSpecification,
        currency_spec: &CoreCurrencySpecification,
        ssp: &SymbolSpecificationProvider,
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
        // 期货净盈余顶现货 NSF 额度（对应 Java `:666-669`）。
        let free_futures_margin = if self.cfg_margin_trading_enabled {
            self.calculate_free_futures_margin(user_profile, currency, ssp)
        } else {
            0
        };
        // 借贷抵押必扣不能被现货挂单锁走，对应 Java :673（单独减 loanCollateralLocked，不调 calculateLocked）；无 loan 用户恒 0。
        let loan_locked = self.loan_collateral_locked(user_profile, currency);
        if balance - existing_locked - loan_locked - order_lock_amount + free_futures_margin < 0 {
            return CommandResultCode::RiskNsf;
        }
        user_profile.add_to_locked(currency, order_lock_amount);
        CommandResultCode::ValidForMatchingEngine
    }

    /// 对应 Java `handlerRiskRelease`（:885-1023，现货分支 :922-945）：R2 撮合后置分派骨架；非交易命令 R1 已定结果，此处直接返回。
    pub fn handler_risk_release(
        &mut self,
        cmd: &mut OrderCommand,
        ups: &mut UserProfileService,
        ssp: &SymbolSpecificationProvider,
    ) {
        // RepriceLoanRates 虽 is_non_trading() 但需真正 R2 处理，须在通用 is_non_trading 早退前特判，对应 Java handlerRiskRelease（:906-913）。
        if cmd.command == OrderCommandType::RepriceLoanRates {
            self.reprice_loan_rates_apply(cmd);
            return;
        }
        // InternalTransfer 同理需在 is_non_trading 早退前特判，对应 Java handlerRiskRelease 的 INTERNAL_TRANSFER_EVENT 分支；载体为 cmd.internal_transfer_event 而非 matcher_event。
        if cmd.command == OrderCommandType::InternalTransfer {
            self.internal_transfer_apply(cmd, ups);
            return;
        }
        // SettleFundingfees 非 is_non_trading，用 cmd.funding_fee_event 专属载体，须在 cmd.matcher_event.take() 之前特判，对应 Java handlerRiskRelease 的 FUNDING_EVENT 分支（:972-976）。
        if cmd.command == OrderCommandType::SettleFundingfees {
            // 对应 Java handlerRiskRelease :888-890 门控：无 funding 事件（cmd.funding_fee_event=None）则不触发 :977 checkPositions。
            let had_funding_event = cmd.funding_fee_event.is_some();
            self.settle_funding_fees_apply(cmd, ups, ssp);
            // 对应 Java handlerRiskRelease:977：资金费结算后触发同 symbol 强平检测（结算可能致仓破产）。
            if had_funding_event {
                self.liquidation_engine.check_positions(cmd, ups, ssp, &self.last_price_cache, &self.loan_service);
            }
            return;
        }
        // IfTakeover 用 cmd.if_takeover_size/if_preview_cover 专属载体，不走 matcher_event 链，对应 Java handlerRiskRelease 的 IF_TAKEOVER 分支（:962-966/:987-988）。
        if cmd.command == OrderCommandType::IfTakeover {
            self.if_takeover_apply(cmd, ups, ssp);
            // 对应 Java `handlerRiskRelease:997`——IF R2 结算后推进状态机（REJECT→ADL）。
            Self::advance_liquidation_for(&mut self.liquidation_engine, cmd, ups);
            return;
        }
        // AutoDeleveraging 用 cmd.adl_events/adl_user_positions 专属载体，对应 Java handlerRiskRelease 的 AUTO_DELEVERAGING 分支（:967-970 apply + :989-990 finalize）。
        if cmd.command == OrderCommandType::AutoDeleveraging {
            self.adl_apply(cmd, ups, ssp);
            // 对应 Java `handlerRiskRelease:997`——ADL R2 结算后推进状态机（恒终态）。
            Self::advance_liquidation_for(&mut self.liquidation_engine, cmd, ups);
            return;
        }
        if cmd.command.is_non_trading() {
            return;
        }
        // 期货分支专用，对应 Java lastPriceCache.get(spec.symbolId)；提前取值避免与后续 &mut self.fees 借用冲突，unwrap_or(0) 仅防御性兜底（R1 已保证 mark price 存在）。
        let mark_price_for_futures = self.mark_price(cmd.symbol).unwrap_or(0);
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
            // 期货分支，对应 Java handlerRiskRelease（:885-1023）非现货 catch-all；PLACE_ORDER/CLOSE_POSITION/ForceLiquidation 等统一走 handle_matcher_event_margin，对齐 Java 外循环整条链处理（不像现货分两段）。
            let taker_action = cmd.action.expect("futures matcher event requires taker action");
            let quote_currency_spec = ssp
                .get_currency(spec.quote_currency)
                .unwrap_or_else(|| panic!("currency spec missing for currency {}", spec.quote_currency))
                .clone();

            // ForceLiquidation 的 collect_liquidation_fee 需 TRADE 的 Σsize/Σ(size×price)，消费前先非破坏性 peek 一遍聚合（同 loan force-liquidate 先例）。
            let is_force = cmd.command == OrderCommandType::ForceLiquidation;
            let (force_taker_size, force_taker_size_price) = if is_force {
                let mut taker_size: i64 = 0;
                let mut taker_size_price: i128 = 0;
                let mut cursor = Some(mte.as_ref());
                while let Some(ev) = cursor {
                    if ev.event_type == MatcherEventType::Trade {
                        taker_size += ev.size;
                        taker_size_price += ev.size as i128 * ev.price as i128;
                    }
                    cursor = ev.next.as_deref();
                }
                (taker_size, taker_size_price)
            } else {
                (0i64, 0i128)
            };

            Self::handle_matcher_event_margin(
                cmd,
                mte,
                &spec,
                taker_action,
                ups,
                fees,
                &quote_currency_spec,
                mark_price_for_futures,
            );

            // ForceLiquidation R2-finalize 钩子，对应 Java handlerRiskRelease :992 collectLiquidationFee + :997 advanceLiquidation。
            if is_force {
                // 对应 Java collect_liquidation_fee（:1522-1550）：Σtrade 手续费从 taker 扣、计入 IFNotional.available；taker_size==0 时 no-op。
                if force_taker_size > 0 {
                    let avg_price = (force_taker_size_price / force_taker_size as i128) as i64;
                    let notional_fee = arithmetic::calculate_liquidation_fee(
                        force_taker_size,
                        avg_price,
                        spec.liquidation_fee,
                        spec.fee_scale_k,
                    );
                    let quote_fee = arithmetic::size_price_to_currency_scale(
                        notional_fee,
                        spec.base_scale_k,
                        spec.quote_scale_k,
                        quote_currency_spec.currency_scale_k,
                    );
                    // debit/credit 须对称全有全无，对应 Java collectLiquidationFee 的 takerSpr==null 守护，防守恒破坏。
                    if let Some(taker) = ups.get_mut(cmd.uid) {
                        taker.add_to_account(spec.quote_currency, -quote_fee);
                        self.liquidation_service.credit_liquidation_fee(cmd.symbol, notional_fee);
                    }
                }
                // 对应 Java advance_liquidation（:997）：FORCE apply 后推进状态机（全成交闭环 / REJECT→WAIT_IF 入队 IF）。
                Self::advance_liquidation_for(&mut self.liquidation_engine, cmd, ups);
            }
            return;
        }
        let taker_sell = matches!(cmd.action, Some(OrderAction::Ask));

        // loan force-liquidate 需 TRADE/REJECT 聚合（traded_size/notional/rejected_size），消费链前先非破坏性 peek 一遍算好，等价 Java 事后再遍历 cmd.matcherEvent。
        let is_loan_force_liquidate = matches!(
            cmd.command,
            OrderCommandType::LoanForceLiquidate | OrderCommandType::LoanCrossForceLiquidate
        );
        let (loan_traded_size, loan_traded_notional, loan_rejected_size) = if is_loan_force_liquidate {
            let mut traded_size: i64 = 0;
            let mut traded_notional: i128 = 0;
            let mut rejected_size: i64 = 0;
            let mut cursor = Some(mte.as_ref());
            while let Some(ev) = cursor {
                match ev.event_type {
                    MatcherEventType::Trade => {
                        traded_size = traded_size
                            .checked_add(ev.size)
                            .unwrap_or_else(|| panic!("overflow: loan force-liquidate traded_size"));
                        traded_notional += ev.size as i128 * ev.price as i128;
                    }
                    MatcherEventType::Reject => {
                        rejected_size = rejected_size
                            .checked_add(ev.size)
                            .unwrap_or_else(|| panic!("overflow: loan force-liquidate rejected_size"));
                    }
                    _ => {}
                }
                cursor = ev.next.as_deref();
            }
            (traded_size, traded_notional, rejected_size)
        } else {
            (0i64, 0i128, 0i64)
        };

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

        // Loan 强平：spot 标准结算后钩子，把 quote proceeds 路由到 loan/pool/fees/LIF，对应 Java handlerRiskRelease（:937-945），无论上面 TRADE 链是否跑过都执行。
        if is_loan_force_liquidate {
            let taker_up = ups.get_or_add_suspended(cmd.uid);
            match cmd.command {
                OrderCommandType::LoanForceLiquidate => {
                    LoanCommandDispatcher::post_process_loan_force_liquidate(
                        self,
                        cmd,
                        &spec,
                        taker_up,
                        ssp,
                        loan_traded_size,
                        loan_traded_notional,
                        loan_rejected_size,
                    );
                }
                OrderCommandType::LoanCrossForceLiquidate => {
                    LoanCommandDispatcher::post_process_loan_cross_force_liquidate(
                        self,
                        cmd,
                        &spec,
                        taker_up,
                        ssp,
                        loan_traded_size,
                        loan_traded_notional,
                        loan_rejected_size,
                    );
                }
                _ => unreachable!("is_loan_force_liquidate implies one of the two force-liquidate codes"),
            }
        }
    }

    /// 对应 Java `handleMatcherRejectReduceEventExchange`（:1094-1125）：撤单/拒单释放单方冻结（ASK 按 size 直退，BID 按订单类型分档计算），accounts 不动。
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

    /// 对应 Java `handleMatcherEventsExchangeSell`（:1134-1227）：taker 卖(ASK)/maker 买(BID) 现货成交结算，逐笔累加 maker 后一次性按均价结算 taker（避免逐笔 ceil 多产 dust）。
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

                // calculateAmountAsk(size)=size：ASK 侧不收费（费用走 quote 侧），maker 收到 base 即成交量本身。
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
            // fees 池按均价重算 taker+maker 费单次入账，避免逐笔 ceil 累积 dust（dust 沉 exchangeLocked，sweep 属后续）。
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

    /// 对应 Java `handleMatcherEventsExchangeBuy`（:1238-1343）：taker 买/maker 卖现货结算，是 sell 的镜像；BUDGET 子路径 hold_quote 恒为整份预算，与 Task5 REDUCE release_sp=0 对齐（见文中测试）。
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

                // maker 是卖方，下单时按 base 数量直接冻结（calculateAmountAsk(size)=size），全释放并扣 base。
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

            // effective_hold_notional：BUDGET 重赋值为 taker_notional，普通路径保持逐笔 bidder_hold_price 聚合，两路径共用同一 quote_refund 公式。
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
                // 普通单：feeHeld 按 bidderHoldPrice 均价冻、takerFee 按实际成交均价收，差额 leftover 退用户。
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

    // ==== R2 主线：期货 handlers —— 参考文档 §4；Java RiskEngine.java:1358-1511 ====

    /// 对应 Java handlerRiskRelease 驱动 handleMatcherEventMargin 的外层 do-while 循环，把整条事件链逐个喂给 handle_matcher_event_margin_one；期货不像现货那样需先摘出链头 REJECT/REDUCE（同函数按 event_type 分支处理）。
    #[allow(clippy::too_many_arguments)]
    fn handle_matcher_event_margin(
        cmd: &OrderCommand,
        first_mte: Box<MatcherTradeEvent>,
        spec: &CoreSymbolSpecification,
        taker_action: OrderAction,
        ups: &mut UserProfileService,
        fees: &mut BTreeMap<i32, i64>,
        quote_currency_spec: &CoreCurrencySpecification,
        mark_price: i64,
    ) {
        let mut node = Some(first_mte);
        while let Some(mut ev) = node {
            Self::handle_matcher_event_margin_one(
                cmd,
                &ev,
                spec,
                taker_action,
                ups,
                fees,
                quote_currency_spec,
                mark_price,
            );
            node = ev.next.take();
        }
    }

    /// 对应 Java `handleMatcherEventMargin`（:1358-1511）处理单个事件：taker 块恒执行 + maker 块仅 TRADE；maker 侧 createPositionsKey 用 mte.matched_order_command_type（非 cmd.command，对应 Java :1450），ONEWAY 下无影响，HEDGE 下才生效。
    #[allow(clippy::too_many_arguments)]
    fn handle_matcher_event_margin_one(
        cmd: &OrderCommand,
        mte: &MatcherTradeEvent,
        spec: &CoreSymbolSpecification,
        taker_action: OrderAction,
        ups: &mut UserProfileService,
        fees: &mut BTreeMap<i32, i64>,
        quote_currency_spec: &CoreCurrencySpecification,
        mark_price: i64,
    ) {
        // taker 块：单 shard 简化下 taker 恒本地（`uidForThisHandler(cmd.uid)` 恒真）。
        {
            let taker_up = ups.get_or_add_suspended(cmd.uid);
            let position_key = taker_up.create_positions_key(spec.symbol_id, taker_action, cmd.command);
            // 对应 Java takerSpr==null 的 log.warn 防御性跳过（不 panic）：taker 仓位理论上 R1 已建立，但不强制假设。
            Self::settle_margin_position_event(
                taker_up,
                position_key,
                /* required = */ false,
                mte,
                spec,
                taker_action,
                fees,
                quote_currency_spec,
                mark_price,
                /* is_taker = */ true,
            );
        }

        // maker 块：仅 TRADE 事件（REJECT/REDUCE 无对手方，`matched_order_uid` 恒 0/无意义）。
        if mte.event_type == MatcherEventType::Trade {
            let maker_action = taker_action.opposite();
            let maker_up = ups.get_or_add_suspended(mte.matched_order_uid);
            let position_key =
                maker_up.create_positions_key(spec.symbol_id, maker_action, mte.matched_order_command_type);
            // 对应 Java makerUp.getPositionRecordOrThrowEx：maker 仓位记录必须已存在，缺失即数据损坏，panic 而非静默吞掉。
            Self::settle_margin_position_event(
                maker_up,
                position_key,
                /* required = */ true,
                mte,
                spec,
                maker_action,
                fees,
                quote_currency_spec,
                mark_price,
                /* is_taker = */ false,
            );
        }
    }

    /// 对应 Java handleMatcherEventMargin taker 块（:1369-1443）/maker 块（:1445-1510）的状态迁移核心：TRADE 走 pendingRelease→closeCurrentPositionFutures→openPositionMargin，REJECT/REDUCE 仅 pendingRelease；is_empty() 后走 refundExtraMargin+removePositionRecord。
    #[allow(clippy::too_many_arguments)]
    fn settle_margin_position_event(
        up: &mut UserProfile,
        position_key: i32,
        required: bool,
        mte: &MatcherTradeEvent,
        spec: &CoreSymbolSpecification,
        action: OrderAction,
        fees: &mut BTreeMap<i32, i64>,
        quote_currency_spec: &CoreCurrencySpecification,
        mark_price: i64,
        is_taker: bool,
    ) {
        if !up.positions.contains_key(&position_key) {
            if required {
                panic!(
                    "handle_matcher_event_margin: maker position record missing for key {position_key} \
                     (matched_order_uid={})",
                    up.uid
                );
            }
            return; // 对应 Java taker 侧 takerSpr==null 的防御性 warn+skip。
        }

        let quote_currency = spec.quote_currency;

        match mte.event_type {
            MatcherEventType::Trade => {
                let pre_volume = up.positions.get(&position_key).unwrap().open_volume;
                up.positions.get_mut(&position_key).unwrap().pending_release(action, mte.size);

                let size_to_open = up
                    .positions
                    .get_mut(&position_key)
                    .unwrap()
                    .close_current_position_futures(action, mte.size, mte.price);
                let closed_size = 0i64.max(pre_volume - up.positions.get(&position_key).unwrap().open_volume);

                if closed_size > 0 {
                    let raw_fee = if is_taker {
                        arithmetic::calculate_taker_fee(closed_size, mte.price, spec.taker_fee, spec.fee_scale_k)
                    } else {
                        arithmetic::calculate_maker_fee(closed_size, mte.price, spec.maker_fee, spec.fee_scale_k)
                    };
                    let fee = arithmetic::size_price_to_currency_scale(
                        raw_fee,
                        spec.base_scale_k,
                        spec.quote_scale_k,
                        quote_currency_spec.currency_scale_k,
                    );
                    up.add_to_account(quote_currency, -fee);
                    *fees.entry(quote_currency).or_insert(0) += fee;
                }

                if size_to_open > 0 {
                    // 保证金按 mark_price（保守）、成本基按 mte.price（用于后续平仓算 PnL）。
                    up.positions.get_mut(&position_key).unwrap().open_position_margin(
                        action,
                        size_to_open,
                        mte.price,
                        spec,
                        mark_price,
                    );

                    let raw_fee = if is_taker {
                        arithmetic::calculate_taker_fee(size_to_open, mte.price, spec.taker_fee, spec.fee_scale_k)
                    } else {
                        arithmetic::calculate_maker_fee(size_to_open, mte.price, spec.maker_fee, spec.fee_scale_k)
                    };
                    let fee = arithmetic::size_price_to_currency_scale(
                        raw_fee,
                        spec.base_scale_k,
                        spec.quote_scale_k,
                        quote_currency_spec.currency_scale_k,
                    );
                    up.add_to_account(quote_currency, -fee);
                    *fees.entry(quote_currency).or_insert(0) += fee;
                }
            }
            MatcherEventType::Reject | MatcherEventType::Reduce => {
                up.positions.get_mut(&position_key).unwrap().pending_release(action, mte.size);
            }
            MatcherEventType::BinaryEvent => {
                // 不可达：`handler_risk_release` 顶层已对 BINARY_EVENT 短路返回，链上不会再出现。
            }
        }

        // 对应 Java takerSpr.isEmpty() 分支：仓位清零才触发 extraMargin 退款 + profit 结算 + 拆记录。
        let is_empty = up.positions.get(&position_key).unwrap().is_empty();
        if is_empty {
            let currency = up.positions.get(&position_key).unwrap().currency;

            // 对应 Java refundExtraMargin（:1553-1574）：extraMargin 以 sizePriceScale 存储，经 size_price_to_currency_scale（非 symbol_to_currency_scale）换算回 accounts。
            let extra_margin = up.positions.get(&position_key).unwrap().extra_margin;
            if extra_margin > 0 {
                let refund = arithmetic::size_price_to_currency_scale(
                    extra_margin,
                    spec.base_scale_k,
                    spec.quote_scale_k,
                    quote_currency_spec.currency_scale_k,
                );
                up.add_to_account(currency, refund);
                up.positions.get_mut(&position_key).unwrap().extra_margin = 0;
            }

            // 对应 Java removePositionRecord（:1580-1589）：残余已实现盈亏一次性打入 accounts，再从 map 摘除。
            let profit = up.positions.get(&position_key).unwrap().profit;
            if profit != 0 {
                let profit_scaled = arithmetic::size_price_to_currency_scale(
                    profit,
                    spec.base_scale_k,
                    spec.quote_scale_k,
                    quote_currency_spec.currency_scale_k,
                );
                up.add_to_account(currency, profit_scaled);
            }
            up.positions.remove(&position_key);
        }
    }

    /// 对应 Java RiskEngineCommandDispatcher.addUser（:177-181）：建空 UserProfile，已存在→UserMgmtUserAlreadyExists；uidForThisHandler 分片门未移植（单 shard 恒真）。
    pub fn add_user(&mut self, cmd: &OrderCommand, ups: &mut UserProfileService) -> CommandResultCode {
        ups.add_empty_user_profile(cmd.uid)
    }

    /// 对应 Java RiskEngineCommandDispatcher.adjustBalance（:183-211）+ UserProfileService.balanceAdjustment（:71-89）两层校验叠加：外层现货 NSF→内层 NSF→幂等 claim→成功后 account += amount_diff 且 adjustments -= amount_diff（Σ恒定）；提现额度叠加 free futures margin（Ruling P4-A：无期货仓恒 0 no-op）。
    pub fn balance_adjustment(
        &mut self,
        cmd: &OrderCommand,
        ups: &mut UserProfileService,
        ssp: &SymbolSpecificationProvider,
    ) -> CommandResultCode {
        let currency = cmd.symbol;
        let amount_diff = cmd.price;

        let user_profile = match ups.get_mut(cmd.uid) {
            Some(u) => u,
            None => return CommandResultCode::AuthInvalidUser,
        };

        if amount_diff < 0 {
            let withdrawal_amount = -amount_diff;
            if self.withdrawable_balance(user_profile, currency, ssp) - withdrawal_amount < 0 {
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

    /// 对应 Java RiskEngine.withdrawableBalance（:747-753）：提现/转账/加保证金(ISOLATED)共用 NSF 口径 = accounts − 现货冻结 − 借贷抵押 + 期货净盈余（margin trading 开启才计）；放宽到 pub(crate) 供跨模块复用。
    pub(crate) fn withdrawable_balance(
        &self,
        user_profile: &UserProfile,
        currency: i32,
        ssp: &SymbolSpecificationProvider,
    ) -> i64 {
        let free_futures_margin = if self.cfg_margin_trading_enabled {
            self.calculate_free_futures_margin(user_profile, currency, ssp)
        } else {
            0
        };
        user_profile.account(currency) - user_profile.locked(currency)
            - self.loan_collateral_locked(user_profile, currency)
            + free_futures_margin
    }

    /// 对应 Java RiskEngineCommandDispatcher.adjustMargin（:213-277）：CROSS 直接转发 balance_adjustment（同一原语）；ISOLATED 从 accounts 转入 position.extra_margin（不碰 adjustments 桶）；仅支持追加，无"移出保证金"路径。
    pub fn margin_adjustment(
        &mut self,
        cmd: &OrderCommand,
        ups: &mut UserProfileService,
        ssp: &SymbolSpecificationProvider,
    ) -> CommandResultCode {
        if !self.cfg_margin_trading_enabled {
            return CommandResultCode::RiskMarginTradingDisabled;
        }
        if cmd.price <= 0 {
            return CommandResultCode::RiskInvalidAmount;
        }

        if cmd.margin_mode == MarginMode::Cross {
            // CROSS：cmd.symbol 是 currency id，语义等价 BALANCE_ADJUSTMENT 的 ADJUSTMENT 充值，直接复用同一原语。
            return self.balance_adjustment(cmd, ups, ssp);
        }

        // ISOLATED
        let user_profile = match ups.get_mut(cmd.uid) {
            Some(u) => u,
            None => return CommandResultCode::AuthInvalidUser,
        };
        let action = cmd.action.expect("MARGIN_ADJUSTMENT (ISOLATED) requires action");
        let position_key = user_profile.create_positions_key(cmd.symbol, action, cmd.command);
        let (currency, pos_margin_mode, symbol) = match user_profile.positions.get(&position_key) {
            Some(p) => (p.currency, p.margin_mode, p.symbol),
            None => return CommandResultCode::RiskMarginPositionNotExists,
        };
        if pos_margin_mode != cmd.margin_mode {
            return CommandResultCode::RiskMarginModeMismatch;
        }

        // NSF：可提余额（现货冻结 / 借贷抵押必扣，不能拨进 isolated margin）≥ 追加保证金。
        if self.withdrawable_balance(user_profile, currency, ssp) - cmd.price < 0 {
            return CommandResultCode::RiskNsf;
        }

        // ISOLATED 无 adjustments 桶对冲，按 cmd.order_id 自行幂等；NSF 通过后再 claim。
        if !user_profile.try_claim_tx(cmd.order_id) {
            return CommandResultCode::UserMgmtAccountBalanceAdjustmentAlreadyAppliedSame;
        }

        // accounts −= price，extraMargin += price（须换算到 sizePrice scale，否则爆仓/破产价严重偏低）：同一笔钱内部搬移，不 touch adjustments 桶。
        user_profile.add_to_account(currency, -cmd.price);
        let spec = ssp
            .get_symbol(symbol)
            .unwrap_or_else(|| panic!("symbol spec missing for symbol {symbol}"));
        let currency_spec = ssp
            .get_currency(currency)
            .unwrap_or_else(|| panic!("currency spec missing for currency {currency}"));
        let extra_margin_delta = arithmetic::currency_to_size_price_scale(
            cmd.price,
            spec.base_scale_k,
            spec.quote_scale_k,
            currency_spec.currency_scale_k,
        );
        user_profile.positions.get_mut(&position_key).unwrap().extra_margin += extra_margin_delta;

        CommandResultCode::Success
    }

    /// 对应 Java RiskEngineCommandDispatcher.adjustLeverage（:287-333）：调整 symbol 下用户全部仓位杠杆，全部校验通过才落地（全改或全不改）；leverage==0 归一为 1；持仓存在但 mark price 缺失 panic（不可达不变量）。
    pub fn leverage_adjustment(
        &mut self,
        cmd: &OrderCommand,
        ups: &mut UserProfileService,
        ssp: &SymbolSpecificationProvider,
    ) -> CommandResultCode {
        if !self.cfg_margin_trading_enabled {
            return CommandResultCode::RiskMarginTradingDisabled;
        }
        let user_profile = match ups.get_mut(cmd.uid) {
            Some(u) => u,
            None => return CommandResultCode::AuthInvalidUser,
        };
        let spec = match ssp.get_symbol(cmd.symbol) {
            Some(s) => s,
            None => return CommandResultCode::InvalidSymbol,
        };

        if user_profile.count_position_record(cmd.symbol, |_| true) == 0 {
            return CommandResultCode::Success;
        }

        let mark_price = self
            .mark_price(cmd.symbol)
            .unwrap_or_else(|| panic!("mark price missing for symbol {} with existing position", cmd.symbol));
        let effective_leverage = if cmd.leverage == 0 { 1 } else { cmd.leverage };

        let mut invalid_leverage = false;
        let mut old_required: i64 = 0;
        let mut new_required: i64 = 0;
        user_profile.process_position_record(cmd.symbol, |position| {
            if invalid_leverage {
                return;
            }
            let notional = position.estimate_notional_for_order(OrderAction::Bid, 0, mark_price);
            if !spec.is_valid_leverage(notional, effective_leverage) {
                invalid_leverage = true;
                return;
            }
            old_required += position.calculate_required_margin_for_futures(spec);
            new_required += position.calculate_required_margin_for_futures_with_leverage(spec, effective_leverage);
        });
        if invalid_leverage {
            return CommandResultCode::RiskInvalidLeverage;
        }

        if new_required > old_required {
            let currency_spec = ssp
                .get_currency(spec.quote_currency)
                .unwrap_or_else(|| panic!("currency spec missing for currency {}", spec.quote_currency));
            let diff = arithmetic::size_price_to_currency_scale(
                new_required - old_required,
                spec.base_scale_k,
                spec.quote_scale_k,
                currency_spec.currency_scale_k,
            );
            let balance = user_profile.account(spec.quote_currency);
            let locked = self.calculate_locked(user_profile, spec.quote_currency, ssp, currency_spec);
            if diff > balance - locked {
                return CommandResultCode::RiskNsf;
            }
        }

        user_profile.process_position_record(cmd.symbol, |position| {
            position.update_leverage(effective_leverage);
        });

        CommandResultCode::Success
    }

    /// 对应 Java normalizeCmdPositionSize（:724-740）：FORCE/IF/ADL 的 R1 size 归一，cmd.size=min(cmd.size, open_volume)，FORCE 用平仓视角、IF/ADL 用接管视角。
    fn normalize_cmd_position_size(cmd: &mut OrderCommand, ups: &UserProfileService) -> CommandResultCode {
        let action = match cmd.action {
            Some(a) => a,
            None => return CommandResultCode::Success,
        };
        let profile = match ups.get(cmd.uid) {
            Some(p) => p,
            None => return CommandResultCode::AuthInvalidUser,
        };
        let key = profile.create_positions_key(cmd.symbol, action, cmd.command);
        let Some(position) = profile.positions.get(&key) else {
            return CommandResultCode::Success;
        };
        cmd.size = cmd.size.min(position.open_volume);
        CommandResultCode::ValidForMatchingEngine
    }

    /// 对应 Java handlerRiskRelease:996-999：强平类命令 R2 结算后推进 FORCE→IF→ADL 状态机；仓位若已 full-fill 移除则 skip，否则调 advance_liquidation。
    fn advance_liquidation_for(engine: &mut LiquidationEngine, cmd: &OrderCommand, ups: &mut UserProfileService) {
        let action = match cmd.action {
            Some(a) => a,
            None => return,
        };
        let key = match ups.get(cmd.uid) {
            Some(u) => u.create_positions_key(cmd.symbol, action, cmd.command),
            None => return,
        };
        if let Some(u) = ups.get_mut(cmd.uid) {
            if let Some(pos) = u.positions.get_mut(&key) {
                engine.advance_liquidation(cmd, pos);
            }
        }
    }

    /// 对应 Java RiskEngineCommandDispatcher.adjustMarkPrice（:437-451）：更新 lastPriceCache，拒绝 price<=0（Java 允许 0 但本移植三处对 None panic，故加固避免复制状态机 panic）。
    pub fn markprice_adjustment(
        &mut self,
        cmd: &OrderCommand,
        ups: &mut UserProfileService,
        ssp: &SymbolSpecificationProvider,
    ) -> CommandResultCode {
        if ssp.get_symbol(cmd.symbol).is_none() {
            return CommandResultCode::InvalidSymbol;
        }
        if cmd.price <= 0 {
            return CommandResultCode::RiskInvalidAmount;
        }
        self.set_mark_price(cmd.symbol, cmd.price);
        // 对应 Java adjustMarkPrice:447，价格更新后触发 targeted 强平检测；产出的 FORCE 命令入 liquidation_engine.pending_commands，由 ExchangeCore 排空重喂。
        self.liquidation_engine.check_positions(cmd, ups, ssp, &self.last_price_cache, &self.loan_service);
        CommandResultCode::Success
    }

    /// 对应 Java RiskEngine.settlePnl（:695-717）+ R1 dispatch SETTLE_PNL（:311-321）：交割合约到期，按交割价整仓平仓+退 extra_margin+结算盈亏+移除记录，复用 adl_close_and_settle；全在 R1 完成 R2 no-op，不发事件（Ruling P6-B）。
    fn settle_pnl(&mut self, cmd: &OrderCommand, ups: &mut UserProfileService, ssp: &SymbolSpecificationProvider) -> CommandResultCode {
        let symbol = cmd.symbol;
        let spec = match ssp.get_symbol(symbol) {
            Some(s) if s.symbol_type == SymbolType::FuturesContractDelivery => s.clone(),
            _ => return CommandResultCode::InvalidSymbol,
        };
        let currency_spec = match ssp.get_currency(spec.quote_currency) {
            Some(c) => c.clone(),
            None => return CommandResultCode::InvalidSymbol, // spec 存在则 currency 应存在，防御性
        };
        let price = cmd.price;
        for up in ups.users.values_mut() {
            // 该 symbol 上所有非空持仓 key（ONEWAY: symbol；HEDGE: ±symbol），先收集再改避免迭代中改容器。
            let keys: Vec<i32> = up
                .positions
                .iter()
                .filter(|(_, p)| p.symbol == symbol && p.open_volume != 0)
                .map(|(&k, _)| k)
                .collect();
            for key in keys {
                let (close_action, size) = {
                    let pos = &up.positions[&key];
                    let action = if pos.direction == PositionDirection::Long { OrderAction::Ask } else { OrderAction::Bid };
                    (action, pos.open_volume)
                };
                Self::adl_close_and_settle(up, key, close_action, size, price, &spec, &currency_spec);
            }
        }
        CommandResultCode::Success
    }

    /// 测试 / `ExchangeApi::set_mark_price` 内部调用的直接 setter：跳过 symbol 注册校验直接写 last_price_cache，命令路径请走 markprice_adjustment。
    pub fn set_mark_price(&mut self, symbol: i32, price: i64) {
        self.last_price_cache.insert(symbol, price);
    }

    /// RepriceLoanRates R1：对应 Java case REPRICE_LOAN_RATES（collectInput，参考文档 §4.2）；单 shard 下归并恒等，本移植把 R1 collect_input 与 ME 段 merge 一次性做完，写入 cmd.loan_reprice_events 供 R2 消费。
    fn reprice_loan_rates_collect(&mut self, cmd: &mut OrderCommand) -> CommandResultCode {
        let shard_data = LoanRatePricingProcessor::collect_input(&self.loan_service);
        cmd.loan_reprice_events = LoanRatePricingProcessor::build_matcher_events(&[shard_data]);
        CommandResultCode::Success
    }

    /// RepriceLoanRates R2：对应 Java handlerRiskRelease（:906-913），逐事件调 apply_event（advance_accumulator 先于 reprice_currency，顺序不可颠倒），循环后统一 set_last_reprice_ts 一次；空事件完全不做任何事（含不推进 ts）。
    fn reprice_loan_rates_apply(&mut self, cmd: &mut OrderCommand) {
        let events = std::mem::take(&mut cmd.loan_reprice_events);
        if events.is_empty() {
            return;
        }
        for (currency, util_bps) in events {
            LoanRatePricingProcessor::apply_event(&mut self.loan_service, currency, util_bps, cmd.timestamp);
        }
        self.loan_service.floating_rate.set_last_reprice_ts(cmd.timestamp);
    }

    /// InternalTransfer R1+merge：对应 Java case INTERNAL_TRANSFER collectInput（参考文档 §5，字段映射 cmd.uid=from_uid/cmd.size=to_uid/cmd.symbol=currency/cmd.price=amount）；单 shard 归并恒等（Ruling P6-C），R1 失败直接返回拒绝码，成功写入 cmd.internal_transfer_event 供 R2 消费。
    fn internal_transfer_collect(
        &mut self,
        cmd: &mut OrderCommand,
        ups: &mut UserProfileService,
        ssp: &SymbolSpecificationProvider,
    ) -> CommandResultCode {
        let from_uid = cmd.uid;
        let to_uid = cmd.size;
        let currency = cmd.symbol;
        let amount = cmd.price;
        let order_id = cmd.order_id;

        let rc =
            InternalTransferProcessor::collect_input(self, ups, ssp, from_uid, to_uid, currency, amount, order_id);
        if rc == CommandResultCode::Success {
            cmd.internal_transfer_event =
                Some(InternalTransferProcessor::build_matcher_events(to_uid, currency, amount));
        }
        rc
    }

    /// InternalTransfer R2：对应 Java handlerRiskRelease 的 INTERNAL_TRANSFER_EVENT 分支（applyEvent，:84-98），消费 cmd.internal_transfer_event 给 to-shard 入账（未知 to 自动建 SUSPENDED）。
    fn internal_transfer_apply(&mut self, cmd: &mut OrderCommand, ups: &mut UserProfileService) {
        let Some((to_uid, currency, amount)) = cmd.internal_transfer_event.take() else {
            return;
        };
        InternalTransferProcessor::apply_event(ups, to_uid, currency, amount);
    }

    /// SettleFundingfees R1+merge：对应 Java case SETTLE_FUNDINGFEES（:294-309）+ collectInput/buildMatcherEvents（:34-68/:70-126）；门禁顺序逐字对齐 Java：InvalidSymbol → RiskMarkpriceNotAvailable → RiskInvalidAmount；单 shard 归并恒等（Ruling P6-C），结果写入 cmd.funding_fee_event 供 R2 消费。
    fn settle_funding_fees_collect(
        &mut self,
        cmd: &mut OrderCommand,
        ups: &UserProfileService,
        ssp: &SymbolSpecificationProvider,
    ) -> CommandResultCode {
        let spec = match ssp.get_symbol(cmd.symbol) {
            Some(s) if s.symbol_type == SymbolType::FuturesContractPerpetual => s,
            _ => return CommandResultCode::InvalidSymbol,
        };
        let mark_price = match self.mark_price(cmd.symbol) {
            Some(p) => p,
            None => return CommandResultCode::RiskMarkpriceNotAvailable,
        };
        if cmd.size <= 0 {
            return CommandResultCode::RiskInvalidAmount;
        }
        let action = cmd.action.expect("SETTLE_FUNDINGFEES requires action");
        let symbol = spec.symbol_id;
        let shard = FundingFeeCommandProcessor::collect_input(ups, symbol, mark_price, action, cmd.price, cmd.size);
        let events = FundingFeeCommandProcessor::build_matcher_events(std::slice::from_ref(&shard));
        if let Some(&(_shard_id, amount)) = events.first() {
            cmd.funding_fee_event = Some((shard.payer_amounts, shard.receiver_notionals, amount));
        }
        CommandResultCode::Success
    }

    /// SettleFundingfees R2：对应 Java handlerRiskRelease 的 FUNDING_EVENT 分支（:972-976，applyEvent :128-167），消费 cmd.funding_fee_event（None 时早退）。
    fn settle_funding_fees_apply(
        &mut self,
        cmd: &mut OrderCommand,
        ups: &mut UserProfileService,
        ssp: &SymbolSpecificationProvider,
    ) {
        let Some((payer_amounts, receiver_notionals, shard_recv_amount)) = cmd.funding_fee_event.take() else {
            return;
        };
        let symbol = cmd.symbol;
        let action = cmd.action.expect("SETTLE_FUNDINGFEES requires action");
        let spec = ssp.get_symbol(symbol).cloned().unwrap_or_else(|| panic!("symbol spec missing for symbol {symbol}"));
        let currency_spec = ssp
            .get_currency(spec.quote_currency)
            .cloned()
            .unwrap_or_else(|| panic!("currency spec missing for currency {}", spec.quote_currency));
        FundingFeeCommandProcessor::apply_event(
            ups,
            symbol,
            action,
            &payer_amounts,
            &receiver_notionals,
            shard_recv_amount,
            &spec,
            &currency_spec,
        );
    }

    // ==== IF_TAKEOVER（保险基金接管）—— 参考文档 §2.2/§2.3；Java IFCommandProcessor.java + RiskEngine.java:365-373(R1)/:962-988(R2) ====

    /// IfTakeover R1+merge：对应 Java case IF_TAKEOVER（:365-373）collectInput + buildMatcherEvents（:39-72）；preview=min(available-reserved,size*price) 写 cmd.if_preview_cover，覆盖不满→None（全拒）否则 Some(cmd.size) 写 cmd.if_takeover_size；结果码恒 Success（REJECT 是事件级信号）。
    fn if_takeover_collect(&mut self, cmd: &mut OrderCommand) -> CommandResultCode {
        let preview = IfCommandProcessor::collect_input(&mut self.liquidation_service, cmd.symbol, cmd.size, cmd.price);
        cmd.if_preview_cover = preview;
        cmd.if_takeover_size = IfCommandProcessor::build_matcher_event(preview, cmd.size, cmd.price);
        CommandResultCode::Success
    }

    /// IfTakeover R2（apply+finalize 合并）：对应 Java handlerRiskRelease（:962-966/:987-988）+ IFCommandProcessor.applyEvent/finalizeForCommand（:75-86/:100-127）；成功则按 create_positions_key 关 taker 仓（不收手续费）+ 结算退款，释放 reserved 无论成败都执行。
    fn if_takeover_apply(&mut self, cmd: &mut OrderCommand, ups: &mut UserProfileService, ssp: &SymbolSpecificationProvider) {
        let symbol = cmd.symbol;
        let price = cmd.price;
        let action = cmd.action.expect("IF_TAKEOVER requires action");
        let accepted_size = cmd.if_takeover_size.take();

        if let Some(size) = accepted_size {
            let direction = PositionDirection::of_action(action);
            IfCommandProcessor::apply_event(&mut self.liquidation_service, symbol, direction, size, price);

            let spec = ssp.get_symbol(symbol).cloned().unwrap_or_else(|| panic!("symbol spec missing for symbol {symbol}"));
            let currency_spec = ssp
                .get_currency(spec.quote_currency)
                .cloned()
                .unwrap_or_else(|| panic!("currency spec missing for currency {}", spec.quote_currency));

            let up = ups.get_or_add_suspended(cmd.uid);
            // 对应 Java handlerRiskRelease:947-948：按 create_positions_key 查 taker 仓（非裸 symbol），ONEWAY 下等价 no-op，为 HEDGE 铺好正确接线。
            let position_key = up.create_positions_key(symbol, action, cmd.command);
            if up.positions.contains_key(&position_key) {
                up.positions.get_mut(&position_key).unwrap().close_current_position_futures(action.opposite(), cmd.size, price);

                let is_empty = up.positions.get(&position_key).unwrap().is_empty();
                if is_empty {
                    let currency = up.positions.get(&position_key).unwrap().currency;

                    let extra_margin = up.positions.get(&position_key).unwrap().extra_margin;
                    if extra_margin > 0 {
                        let refund = arithmetic::size_price_to_currency_scale(
                            extra_margin,
                            spec.base_scale_k,
                            spec.quote_scale_k,
                            currency_spec.currency_scale_k,
                        );
                        up.add_to_account(currency, refund);
                        up.positions.get_mut(&position_key).unwrap().extra_margin = 0;
                    }

                    let profit = up.positions.get(&position_key).unwrap().profit;
                    if profit != 0 {
                        let profit_scaled = arithmetic::size_price_to_currency_scale(
                            profit,
                            spec.base_scale_k,
                            spec.quote_scale_k,
                            currency_spec.currency_scale_k,
                        );
                        up.add_to_account(currency, profit_scaled);
                    }
                    up.positions.remove(&position_key);
                }
            }
        }

        // finalize 后半：无论接管成功/全拒都释放本命令预冻结的 reserved（跟 R1 对称）。
        self.liquidation_service.release_reserved_if_notional(symbol, cmd.if_preview_cover);
    }

    // ==== AUTO_DELEVERAGING（自动减仓 ADL）—— 参考文档 §3/§11.1；Java ADLCommandProcessor.java + LiquidationService.java:191-321 + RiskEngine.java:374-380(R1)/:962-990(R2) ====

    /// AutoDeleveraging R1+merge：对应 Java case AUTO_DELEVERAGING（:374-380）collectInput + buildMatcherEvents（:102-165）；候选取自 compute_profitable_positions_by_symbol，AdlCommandProcessor::collect_input 排序+贪心分配为 R1 最终预占量，写回 pending_adl_size 后 merge 产出 cmd.adl_events 并把 cmd.size 改写为实际消费量；结果码恒 Success。
    fn adl_collect(&mut self, cmd: &mut OrderCommand, ups: &mut UserProfileService, ssp: &SymbolSpecificationProvider) -> CommandResultCode {
        cmd.adl_user_positions.clear();
        cmd.adl_events.clear();

        let symbol = cmd.symbol;
        let action = cmd.action.expect("AUTO_DELEVERAGING requires action");
        let bankruptcy_price = cmd.price;
        let remaining_size = cmd.size;
        if remaining_size <= 0 {
            return CommandResultCode::Success;
        }

        let mut candidates_map = LiquidationService::compute_profitable_positions_by_symbol(ups, ssp, &self.last_price_cache);
        let candidates = candidates_map.remove(&symbol).unwrap_or_default();

        let picks = AdlCommandProcessor::collect_input(candidates, symbol, action, bankruptcy_price, remaining_size);

        // R1 写回：预占 pending_adl_size（与 finalize 对称释放，见 `adl_apply` 文档）。
        for pick in &picks {
            if let Some(profile) = ups.users.get_mut(&pick.uid) {
                let position_key = profile.create_positions_key(symbol, action.opposite(), OrderCommandType::AutoDeleveraging);
                if let Some(pos) = profile.positions.get_mut(&position_key) {
                    pos.pending_adl_size += pick.volume;
                }
            }
        }

        let (events, consumed) = AdlCommandProcessor::build_matcher_events(&picks, remaining_size);
        cmd.adl_user_positions = picks;
        cmd.adl_events = events;
        cmd.size = consumed; // 真实平仓数量，R2 finalize 用它关 taker 自己的仓

        CommandResultCode::Success
    }

    /// ADL 关仓+清算 helper：apply（关 counterparty 仓）与 finalize（关 taker 仓）共用，对应 Java ADLCommandProcessor.applyEvent（:196-212）/finalizeForCommand（:220-235）的重复三段；ADL 不收手续费（同 IF_TAKEOVER）故可安全共享。
    fn adl_close_and_settle(
        up: &mut UserProfile,
        position_key: i32,
        close_action: OrderAction,
        size: i64,
        price: i64,
        spec: &CoreSymbolSpecification,
        currency_spec: &CoreCurrencySpecification,
    ) {
        let Some(pos) = up.positions.get_mut(&position_key) else {
            return;
        };
        pos.close_current_position_futures(close_action, size, price);

        let is_empty = up.positions.get(&position_key).map(|p| p.is_empty()).unwrap_or(false);
        if !is_empty {
            return;
        }
        let currency = up.positions.get(&position_key).unwrap().currency;

        let extra_margin = up.positions.get(&position_key).unwrap().extra_margin;
        if extra_margin > 0 {
            let refund = arithmetic::size_price_to_currency_scale(
                extra_margin,
                spec.base_scale_k,
                spec.quote_scale_k,
                currency_spec.currency_scale_k,
            );
            up.add_to_account(currency, refund);
            up.positions.get_mut(&position_key).unwrap().extra_margin = 0;
        }

        let profit = up.positions.get(&position_key).unwrap().profit;
        if profit != 0 {
            let profit_scaled = arithmetic::size_price_to_currency_scale(
                profit,
                spec.base_scale_k,
                spec.quote_scale_k,
                currency_spec.currency_scale_k,
            );
            up.add_to_account(currency, profit_scaled);
        }
        up.positions.remove(&position_key);
    }

    /// AutoDeleveraging R2（apply+finalize 合并）：对应 Java handlerRiskRelease（:967-970 apply/:989-990 finalize）+ ADLCommandProcessor.applyEvent/finalizeForCommand（:167-213/:215-255）；apply 逐条关 counterparty 仓（缺失 best-effort skip），finalize 前半按 cmd.size（真实消费量）关 taker 仓，后半按 cmd.adl_user_positions 原始表释放 pending_adl_size（与 R1 += 对称，不管 apply 实际消费多少）。
    fn adl_apply(&mut self, cmd: &mut OrderCommand, ups: &mut UserProfileService, ssp: &SymbolSpecificationProvider) {
        let symbol = cmd.symbol;
        let price = cmd.price;
        let action = cmd.action.expect("AUTO_DELEVERAGING requires action");

        let spec = ssp.get_symbol(symbol).cloned().unwrap_or_else(|| panic!("symbol spec missing for symbol {symbol}"));
        let currency_spec = ssp
            .get_currency(spec.quote_currency)
            .cloned()
            .unwrap_or_else(|| panic!("currency spec missing for currency {}", spec.quote_currency));

        let events = std::mem::take(&mut cmd.adl_events);

        // R2 apply：per-event 关 counterparty 仓（best-effort skip，见文档）。
        for &(uid, exec_size) in &events {
            let Some(up) = ups.users.get_mut(&uid) else {
                // counterparty UserProfile 在 R1/R2 之间已消失 -> skip，不是 error。
                continue;
            };
            let position_key = up.create_positions_key(symbol, action.opposite(), OrderCommandType::AutoDeleveraging);
            if !up.positions.contains_key(&position_key) {
                // counterparty 仓位在 R1/R2 之间已被关掉 -> skip，不是 error。
                continue;
            }
            Self::adl_close_and_settle(up, position_key, action, exec_size, price, &spec, &currency_spec);
        }

        // finalize 前半：关 taker 自己的仓（只在有实际成交时，对应 Java != REJECT）。
        if !events.is_empty() {
            let taker_uid = cmd.uid;
            let taker_size = cmd.size;
            let up = ups.get_or_add_suspended(taker_uid);
            let taker_key = up.create_positions_key(symbol, action, OrderCommandType::AutoDeleveraging);
            if up.positions.contains_key(&taker_key) {
                Self::adl_close_and_settle(up, taker_key, action.opposite(), taker_size, price, &spec, &currency_spec);
            }
        }

        // finalize 后半：释放本命令全部候选（R1 原始表）的 pending_adl_size，跟 R1 `+=` 对称。
        let adl_positions: Vec<AdlUserPosition> = std::mem::take(&mut cmd.adl_user_positions);
        for pick in &adl_positions {
            if let Some(up) = ups.users.get_mut(&pick.uid) {
                let position_key = up.create_positions_key(symbol, action.opposite(), OrderCommandType::AutoDeleveraging);
                if let Some(pos) = up.positions.get_mut(&position_key) {
                    if pos.pending_adl_size > 0 {
                        pos.pending_adl_size -= pick.volume;
                    }
                }
            }
        }
    }

    /// 对应 Java RiskEngineCommandDispatcher.processIFDeposit（:465-495）：futures IF_DEPOSIT 运营充值，与 loan LOAN_IF_DEPOSIT 完全独立池子；校验序 symbol→amount>0→currency spec→精度可逆校验，全过才 deposit_to_insurance_fund + adjustments[quote_currency] -= amount（对冲恒定）。
    fn if_deposit(&mut self, cmd: &OrderCommand, ssp: &SymbolSpecificationProvider) -> CommandResultCode {
        let spec = match ssp.get_symbol(cmd.symbol) {
            Some(s) => s,
            None => return CommandResultCode::InvalidSymbol,
        };
        let currency_amount = cmd.price;
        if currency_amount <= 0 {
            return CommandResultCode::RiskInvalidAmount;
        }
        let currency_spec = match ssp.get_currency(spec.quote_currency) {
            Some(c) => c,
            None => return CommandResultCode::InvalidSymbol,
        };
        let notional = arithmetic::currency_to_size_price_scale(
            currency_amount,
            spec.base_scale_k,
            spec.quote_scale_k,
            currency_spec.currency_scale_k,
        );
        let round_tripped = arithmetic::size_price_to_currency_scale(
            notional,
            spec.base_scale_k,
            spec.quote_scale_k,
            currency_spec.currency_scale_k,
        );
        if round_tripped != currency_amount {
            return CommandResultCode::RiskInvalidAmount;
        }
        let quote_currency = spec.quote_currency;
        self.liquidation_service.deposit_to_insurance_fund(cmd.symbol, notional);
        *self.adjustments.entry(quote_currency).or_insert(0) -= currency_amount;
        CommandResultCode::Success
    }

    /// 对应 Java RiskEngineCommandDispatcher.processIFWithdraw（:502-531）：语义与 if_deposit 对称，available 不足→RiskIfInsufficient（与 loan 的 LoanIfInsufficient 互异）；只扣 available 不动 reserved。
    fn if_withdraw(&mut self, cmd: &OrderCommand, ssp: &SymbolSpecificationProvider) -> CommandResultCode {
        let spec = match ssp.get_symbol(cmd.symbol) {
            Some(s) => s,
            None => return CommandResultCode::InvalidSymbol,
        };
        let currency_amount = cmd.price;
        if currency_amount <= 0 {
            return CommandResultCode::RiskInvalidAmount;
        }
        let currency_spec = match ssp.get_currency(spec.quote_currency) {
            Some(c) => c,
            None => return CommandResultCode::InvalidSymbol,
        };
        let notional = arithmetic::currency_to_size_price_scale(
            currency_amount,
            spec.base_scale_k,
            spec.quote_scale_k,
            currency_spec.currency_scale_k,
        );
        let round_tripped = arithmetic::size_price_to_currency_scale(
            notional,
            spec.base_scale_k,
            spec.quote_scale_k,
            currency_spec.currency_scale_k,
        );
        if round_tripped != currency_amount {
            return CommandResultCode::RiskInvalidAmount;
        }
        if !self.liquidation_service.withdraw_from_insurance_fund(cmd.symbol, notional) {
            return CommandResultCode::RiskIfInsufficient;
        }
        let quote_currency = spec.quote_currency;
        *self.adjustments.entry(quote_currency).or_insert(0) += currency_amount;
        CommandResultCode::Success
    }

    /// 对应 Java RiskEngineCommandDispatcher.handleBinaryMessage（ADD_LOAN 分支，:563-646）：批量运行时配置命令，global/symbol/rate_curve 三段独立可选独立校验（一段非法只跳过）；本仓无 binary-command 组帧基建，故直接开放 apply_add_loan 作为配置入口（不经 preamble/幂等/结果码），逐字对齐 Java 三段语义（symbol 段强制 collateral_weight_bps∈[0,10000]，kill-switch 只清 initial_ltv_bps 保留存量）。
    pub fn apply_add_loan(&mut self, cmd: &BatchAddLoanCommand, ssp: &mut SymbolSpecificationProvider) {
        if let Some(g) = &cmd.global {
            let current_liq = self.loan_service.global_config.cross_liquidation_ltv_bps;
            let current_mc = self.loan_service.global_config.cross_margin_call_ltv_bps;
            let numeraire_ok = g.numeraire_currency <= 0 || ssp.get_currency(g.numeraire_currency).is_some();
            if numeraire_ok && g.thresholds_valid_given_current(current_liq, current_mc) {
                let config = &mut self.loan_service.global_config;
                if g.numeraire_currency > 0 {
                    config.numeraire_currency = g.numeraire_currency;
                }
                if g.cross_liquidation_ltv_bps > 0 {
                    config.cross_liquidation_ltv_bps = g.cross_liquidation_ltv_bps;
                }
                if g.cross_margin_call_ltv_bps > 0 {
                    config.cross_margin_call_ltv_bps = g.cross_margin_call_ltv_bps;
                }
                if g.loan_pool_utilization_cap_bps > 0 {
                    config.loan_pool_utilization_cap_bps = g.loan_pool_utilization_cap_bps;
                }
                if g.loan_liquidation_fee_bps > 0 {
                    config.loan_liquidation_fee_bps = g.loan_liquidation_fee_bps;
                }
                if g.ltv_liquidation_buffer_bps > 0 {
                    config.ltv_liquidation_buffer_bps = g.ltv_liquidation_buffer_bps;
                }
                if g.ltv_margin_call_buffer_bps > 0 {
                    config.ltv_margin_call_buffer_bps = g.ltv_margin_call_buffer_bps;
                }
            }
        }

        if let Some(s) = &cmd.symbol {
            let gc = self.loan_service.global_config;
            let resolved = s.resolve(gc.ltv_liquidation_buffer_bps, gc.ltv_margin_call_buffer_bps);
            let spec_ok = match ssp.symbols.get(&s.symbol_id) {
                Some(spec) => spec.symbol_type == SymbolType::CurrencyExchangePair,
                None => false,
            };
            if spec_ok && resolved.valid() {
                let base_currency = ssp.symbols.get(&s.symbol_id).unwrap().base_currency;
                let spec = ssp.symbols.get_mut(&s.symbol_id).unwrap();
                if resolved.initial_ltv_bps == 0 {
                    // 停借只关开关：liquidation/marginCall/maxAmount/maxTermDays 保留原值，避免存量贷款被连带强平。
                    let cur = spec.loan_config;
                    spec.loan_config.update(
                        0,
                        cur.liquidation_ltv_bps,
                        cur.margin_call_ltv_bps,
                        cur.max_amount,
                        cur.max_term_days,
                    );
                } else {
                    spec.loan_config.update(
                        resolved.initial_ltv_bps,
                        resolved.liquidation_ltv_bps,
                        resolved.margin_call_ltv_bps,
                        resolved.max_amount,
                        resolved.max_term_days,
                    );
                    // collateralWeightBps 是 base 币账户级折价率，同 base 多 pair 共享，后写覆盖前写。
                    if let Some(base_spec) = ssp.currencies.get_mut(&base_currency) {
                        base_spec.collateral_weight_bps = resolved.collateral_weight_bps;
                    }
                }
            }
        }

        if let Some(rc) = &cmd.rate_curve {
            if rc.valid() {
                self.loan_service.floating_rate.base_bps = rc.base_bps;
                self.loan_service.floating_rate.kink_util_bps = rc.kink_util_bps;
                self.loan_service.floating_rate.slope1_bps = rc.slope1_bps;
                self.loan_service.floating_rate.slope2_bps = rc.slope2_bps;
                self.loan_service.fixed_rate.locked_rate_adjust_bps = rc.locked_rate_adjust_bps;
            }
        }
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

    /// 搭建：一个现货 symbol（base/quote_scale_k 均非平凡）+ 两种 currency spec（ASK 侧缩放恒等，BID 侧非恒等）+ 一个已建档充值用户。
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
        ssp.add_currency(CoreCurrencySpecification { currency: BASE, currency_scale_k: 100, ..Default::default() });
        ssp.add_currency(CoreCurrencySpecification {
            currency: QUOTE,
            currency_scale_k: 1_000_000, ..Default::default()
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

    // ---- BID — 固定费 ----

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

    // ---- BID — 比例费 ----

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

    // ---- ASK — 固定费 / 比例费 ----

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

    // ---- 用户 / symbol 缺失 ----

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

    // ---- R2 — handler_risk_release：reject/reduce 释放冻结 ----

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
            matched_order_command_type: OrderCommandType::PlaceOrder,
            next,
        })
    }

    #[test]
    fn bid_plain_limit_pure_reject_releases_full_lock_and_leaves_accounts_untouched() {
        let (mut ups, ssp) = setup(2, 0, 1_000_000, 0);
        let mut engine = RiskEngine::new();
        // 先下单，冻结 quote（保守价 reserve_bid_price=50=price）。
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

        // 部分成交：REDUCE 链头后接一个 TRADE（400/1000 成交），本测试只关心 taker 侧 quote 冻结的完整生命周期。
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

        // 端到端验证：REDUCE 释放 0 + buy 结算全额释放 held_total → taker quote 冻结清零，验证 Task5 release_sp=0 的前提成立。
        assert_eq!(
            ups.get(UID).unwrap().locked(QUOTE),
            0,
            "REDUCE 不重复释放 + buy 结算全额释放 held_total，taker quote 冻结应归零"
        );
        // 剩余 TRADE 链已被 buy 结算完全消费。
        assert!(cmd.matcher_event.is_none(), "TRADE 链应被 buy 结算完全消费");
    }

    // ---- R2 — handler_risk_release/handle_matcher_events_exchange_sell：taker 卖(ASK)结算，逐 maker+聚合 taker+平台费，全程守恒断言 ----

    const BUYER1: i64 = 8;
    const BUYER2: i64 = 9;

    /// 与 spec_with_fee 的区别：额外暴露 maker_fee，quote currency_scale_k 取乘积单位使 size_price_to_currency_scale 恒等，便于精确整数断言。
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
        ssp.add_currency(CoreCurrencySpecification { currency: BASE, currency_scale_k: 100, ..Default::default() });
        ssp.add_currency(CoreCurrencySpecification {
            currency: QUOTE,
            currency_scale_k: 100 * 1_000_000, ..Default::default()
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
            matched_order_command_type: OrderCommandType::PlaceOrder,
            next,
        })
    }

    /// 断言结算全局守恒不变式（参考文档 §6）：base 腿逐笔精确守恒；quote 腿守恒 modulo fees[quote]。
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

        // quoteRefund = tradeAmountDiff(10000) + feeDiff(500) = 10500；maker quote 净变动 = 10500 - 60600 = -50100。
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

        // 两笔 TRADE：event1 对 BUYER1（size1000@50, holdPrice55，价格改善）；event2 对 BUYER2（size1000@60, holdPrice60，无改善）。
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

        // taker：avgTakerPrice=55；takerFee=2000*3=6000（固定费与价格无关）；quote += 110000-6000=104000。
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
        // holdQuote=40400；quoteRefund=320；净=320-40400=-40080。
        assert_eq!(buyer1_quote_delta, -40_080);
        let buyer1_base_delta = buyer1.account(BASE) - buyer1_base_before;
        assert_eq!(buyer1_base_delta, 1000);

        let buyer2 = ups.get(BUYER2).unwrap();
        assert_eq!(buyer2.locked(QUOTE), 0);
        // holdQuote=60600；quoteRefund=480；净=480-60600=-60120。
        let buyer2_quote_delta = buyer2.account(QUOTE) - buyer2_quote_before;
        assert_eq!(buyer2_quote_delta, -60_120);
        let buyer2_base_delta = buyer2.account(BASE) - buyer2_base_before;
        assert_eq!(buyer2_base_delta, 1000);

        // avgTakerPrice=50；takerFee=1000；quote += 100000-1000=99000。
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

    // ---- R2 — handler_risk_release/handle_matcher_events_exchange_buy：taker 买(BID)结算（sell 镜像，角色对调），逐 maker+聚合 taker+平台费，全程守恒断言 ----

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
        ssp.add_currency(CoreCurrencySpecification { currency: BASE, currency_scale_k: 100, ..Default::default() });
        ssp.add_currency(CoreCurrencySpecification {
            currency: QUOTE,
            currency_scale_k: 100 * 1_000_000, ..Default::default()
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

        // takerFee=500；feeHeld=600；leftover=100；quoteRefund=10100；净=10100-60600=-50500。
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

        // taker：avgTakerPrice=55；takerFee=6000（固定与价格无关）；leftover=0；holdQuote=126000；quoteRefund=10000；净=10000-126000=-116000。
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

        // avgTakerPrice=50；takerFee=1000；feeHeld=1200；leftover=200；holdQuote=121200；quoteRefund=20200；净=20200-121200=-101000。
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

    /// Task5/7 一致性证明：IOC_BUDGET 部分成交时须释放整份 held_total（非本次成交量），对应 Task5 的 release_sp=0 假设；断言 hold_quote 等于下单时锁定的 locked_after_place。
    #[test]
    fn buy_ioc_budget_partial_fill_releases_full_held_total_matching_task5_assumption() {
        // taker_fee=500/1_000_000=0.05%，maker_fee=100/1_000_000=0.01%。
        let (mut ups, ssp) = setup_buy(500, 100, 1_000_000, 1_000_000_000, &[(SELLER1, 1_000_000)]);
        let mut engine = RiskEngine::new();

        // maker：seller ASK size=400（等于挂单全部数量），price(2000)>=ceil(1_000_000/500)=2000 不触发 too-low。
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

        // 只成交 400（40%）；bidder_hold_price 对 BUDGET 分支无意义（被 taker_hold_notional=taker_notional 覆盖），填 60000 仅占位。
        buyer_cmd.matcher_event = Some(trade_event(400, 50, 60_000, SELLER1, None));
        engine.handler_risk_release(&mut buyer_cmd, &mut ups, &ssp);

        // 核心断言：taker quote 冻结应清零，hold_quote 恰等于 held_total 全额，与部分成交量无关，即 Task5 release_sp=0 的前提。
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

        // taker：takerFee=10；leftover=40020；quoteRefund=40020；净=40020-60030=-20010；base+=400。
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

        // taker：takerFee=3000；leftover=5000；quoteRefund=5000；净=5000-63000=-58000。
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

    // ==== ADD_USER / BALANCE_ADJUSTMENT + adjustments 守恒桶（参考文档 §5） ====

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
        let ssp = SymbolSpecificationProvider::new();
        engine.add_user(&add_user_cmd(UID), &mut ups);

        let cmd = balance_adjustment_cmd(UID, QUOTE, 1000, 1);
        assert_eq!(engine.balance_adjustment(&cmd, &mut ups, &ssp), CommandResultCode::Success);

        assert_eq!(ups.get(UID).unwrap().account(QUOTE), 1000);
        assert_eq!(*engine.adjustments.get(&QUOTE).unwrap(), -1000);
        // Σ account[cur] + adjustments[cur] 从空账户起恒为 0。
        assert_eq!(ups.get(UID).unwrap().account(QUOTE) + engine.adjustments.get(&QUOTE).unwrap(), 0);
    }

    #[test]
    fn balance_adjustment_withdrawal_exceeding_withdrawable_is_nsf_and_noop() {
        let mut ups = UserProfileService::new();
        let mut engine = RiskEngine::new();
        let ssp = SymbolSpecificationProvider::new();
        engine.add_user(&add_user_cmd(UID), &mut ups);
        engine.balance_adjustment(&balance_adjustment_cmd(UID, QUOTE, 1000, 1), &mut ups, &ssp);
        // 500 冻结在挂单里，可提余额只剩 500。
        ups.get_mut(UID).unwrap().add_to_locked(QUOTE, 500);

        let before_account = ups.get(UID).unwrap().account(QUOTE);
        let before_adjustments = *engine.adjustments.get(&QUOTE).unwrap_or(&0);

        let withdraw_cmd = balance_adjustment_cmd(UID, QUOTE, -600, 2);
        assert_eq!(engine.balance_adjustment(&withdraw_cmd, &mut ups, &ssp), CommandResultCode::RiskNsf);

        // NSF：账户与守恒桶都不应变化。
        assert_eq!(ups.get(UID).unwrap().account(QUOTE), before_account);
        assert_eq!(*engine.adjustments.get(&QUOTE).unwrap_or(&0), before_adjustments);
    }

    #[test]
    fn balance_adjustment_withdrawal_within_withdrawable_succeeds() {
        let mut ups = UserProfileService::new();
        let mut engine = RiskEngine::new();
        let ssp = SymbolSpecificationProvider::new();
        engine.add_user(&add_user_cmd(UID), &mut ups);
        engine.balance_adjustment(&balance_adjustment_cmd(UID, QUOTE, 1000, 1), &mut ups, &ssp);

        let withdraw_cmd = balance_adjustment_cmd(UID, QUOTE, -400, 2);
        assert_eq!(engine.balance_adjustment(&withdraw_cmd, &mut ups, &ssp), CommandResultCode::Success);
        assert_eq!(ups.get(UID).unwrap().account(QUOTE), 600);
        assert_eq!(*engine.adjustments.get(&QUOTE).unwrap(), -600);
    }

    #[test]
    fn balance_adjustment_duplicate_order_id_is_already_applied_same_noop() {
        let mut ups = UserProfileService::new();
        let mut engine = RiskEngine::new();
        let ssp = SymbolSpecificationProvider::new();
        engine.add_user(&add_user_cmd(UID), &mut ups);

        let cmd = balance_adjustment_cmd(UID, QUOTE, 1000, 42);
        assert_eq!(engine.balance_adjustment(&cmd, &mut ups, &ssp), CommandResultCode::Success);
        assert_eq!(ups.get(UID).unwrap().account(QUOTE), 1000);

        // 同 order_id 重复 → AlreadyAppliedSame，账户/adjustments 均不再变化（no-op）。
        let repeat = balance_adjustment_cmd(UID, QUOTE, 1000, 42);
        assert_eq!(
            engine.balance_adjustment(&repeat, &mut ups, &ssp),
            CommandResultCode::UserMgmtAccountBalanceAdjustmentAlreadyAppliedSame
        );
        assert_eq!(ups.get(UID).unwrap().account(QUOTE), 1000);
        assert_eq!(*engine.adjustments.get(&QUOTE).unwrap(), -1000);

        // 不同 order_id 正常再次生效。
        let different_id = balance_adjustment_cmd(UID, QUOTE, 500, 43);
        assert_eq!(engine.balance_adjustment(&different_id, &mut ups, &ssp), CommandResultCode::Success);
        assert_eq!(ups.get(UID).unwrap().account(QUOTE), 1500);
        assert_eq!(*engine.adjustments.get(&QUOTE).unwrap(), -1500);
    }

    #[test]
    fn balance_adjustment_nsf_does_not_claim_id_so_same_id_retry_after_funding_succeeds() {
        let mut ups = UserProfileService::new();
        let mut engine = RiskEngine::new();
        let ssp = SymbolSpecificationProvider::new();
        engine.add_user(&add_user_cmd(UID), &mut ups);
        engine.balance_adjustment(&balance_adjustment_cmd(UID, QUOTE, 500, 1), &mut ups, &ssp);

        // 提现 600 超过可提余额 500 → NSF，order_id=99 未被 claim。
        let nsf_attempt = balance_adjustment_cmd(UID, QUOTE, -600, 99);
        assert_eq!(engine.balance_adjustment(&nsf_attempt, &mut ups, &ssp), CommandResultCode::RiskNsf);

        // 补充资金后用同一 order_id=99 重试，必须放行（NSF 路径未 claim id）。
        engine.balance_adjustment(&balance_adjustment_cmd(UID, QUOTE, 1000, 2), &mut ups, &ssp);
        let retry = balance_adjustment_cmd(UID, QUOTE, -600, 99);
        assert_eq!(engine.balance_adjustment(&retry, &mut ups, &ssp), CommandResultCode::Success);
        assert_eq!(ups.get(UID).unwrap().account(QUOTE), 900); // 500 + 1000 - 600
    }

    #[test]
    fn balance_adjustment_unknown_user_is_auth_invalid_user() {
        let mut ups = UserProfileService::new();
        let mut engine = RiskEngine::new();
        let ssp = SymbolSpecificationProvider::new();
        let cmd = balance_adjustment_cmd(999, QUOTE, 100, 1);
        assert_eq!(engine.balance_adjustment(&cmd, &mut ups, &ssp), CommandResultCode::AuthInvalidUser);
    }

    // ==== 期货 R1 — place_order 期货分支/can_place_margin_order NSF/CLOSE_POSITION（参考文档 §3；Java RiskEngine.java:432-503,533-623,823-875）；搭建单 symbol（scale 全 1 恒等缩放）+ mark price + 已建档用户，leverage 默认 1 ====

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

    /// 同 [`futures_spec`]，但 `maker_fee` 可配置（R2 需要区分 taker/maker 费率的测试用）。
    fn futures_spec_with_fees(taker_fee: i64, maker_fee: i64, fee_scale_k: i64) -> CoreSymbolSpecification {
        CoreSymbolSpecification { maker_fee, ..futures_spec_for(FUT_SYMBOL, taker_fee, fee_scale_k) }
    }

    fn setup_futures(
        taker_fee: i64,
        fee_scale_k: i64,
        quote_balance: i64,
        mark_price: i64,
    ) -> (RiskEngine, UserProfileService, SymbolSpecificationProvider) {
        let mut ssp = SymbolSpecificationProvider::new();
        assert_eq!(ssp.add_symbol(futures_spec(taker_fee, fee_scale_k)), CommandResultCode::Success);
        ssp.add_currency(CoreCurrencySpecification { currency: FUT_QUOTE, currency_scale_k: 1, ..Default::default() });
        ssp.add_currency(CoreCurrencySpecification { currency: FUT_BASE, currency_scale_k: 1, ..Default::default() });

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

    // ---- 成功开仓 + pending 记录 —— 固定费（fee_scale_k=0）+ ISOLATED 一组 ----

    #[test]
    fn futures_place_order_long_sufficient_margin_fixed_fee_is_valid_and_records_pending() {
        // taker_fee=2（固定，每手 2）；balance=10_000（充裕）；mark=100。
        let (mut engine, mut ups, ssp) = setup_futures(2, 0, 10_000, 100);
        let mut cmd = futures_place_cmd(OrderAction::Bid, 10, 100, 1, MarginMode::Isolated, false);

        // 手推：positionMargin=1000+pendingFee=20+openLoss=0=required 1020 ≤ spendable 10_000。
        assert_eq!(
            engine.place_order_risk_check(&mut cmd, &mut ups, &ssp),
            CommandResultCode::ValidForMatchingEngine
        );

        let position =
            ups.get_mut(UID).unwrap().positions.get(&FUT_SYMBOL).expect("NSF 通过后 position 必须已提交入 map");
        assert_eq!(position.pending_buy_size, 10);
        assert_eq!(position.pending_buy_avg_price, 100);
        assert_eq!(position.open_volume, 0); // R1 只挂单占用，未成交（成交结算在 R2）
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

    // ---- 成功开仓 / NSF 边界 —— 比例费（fee_scale_k>0）+ ISOLATED 一组 ----

    #[test]
    fn futures_place_order_proportional_fee_sufficient_margin_is_valid() {
        // taker_fee=100/fee_scale_k=10_000（1%）：pendingFee=10；required=1000+10+0=1010 ≤ spendable 2_000。
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

    // ---- 杠杆超档 → RiskInvalidLeverage ----

    #[test]
    fn futures_place_order_leverage_exceeds_tier_is_invalid_leverage() {
        // max_leverage 只配一档 {floor:0->5}，相当于全局杠杆上限 5x（见 floor_value 文档）。
        let mut spec = futures_spec(2, 0);
        spec.max_leverage.insert(0, 5);
        let mut ssp = SymbolSpecificationProvider::new();
        assert_eq!(ssp.add_symbol(spec), CommandResultCode::Success);
        ssp.add_currency(CoreCurrencySpecification { currency: FUT_QUOTE, currency_scale_k: 1, ..Default::default() });
        ssp.add_currency(CoreCurrencySpecification { currency: FUT_BASE, currency_scale_k: 1, ..Default::default() });

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

    // ---- 崩溃安全性回归：非现货非期货 symbol 绝不 panic（对应 Java placeOrder:437-439 UnsupportedSymbolType，非 unimplemented!） ----

    #[test]
    fn futures_place_order_option_symbol_type_returns_unsupported_not_panic() {
        const OPTION_SYMBOL: i32 = 202;
        let mut ssp = SymbolSpecificationProvider::new();
        assert_eq!(
            ssp.add_symbol(CoreSymbolSpecification {
                symbol_id: OPTION_SYMBOL,
                symbol_type: SymbolType::Option,
                base_currency: FUT_BASE,
                quote_currency: FUT_QUOTE,
                base_scale_k: 1,
                quote_scale_k: 1,
                ..Default::default()
            }),
            CommandResultCode::Success
        );
        ssp.add_currency(CoreCurrencySpecification { currency: FUT_QUOTE, currency_scale_k: 1, ..Default::default() });
        ssp.add_currency(CoreCurrencySpecification { currency: FUT_BASE, currency_scale_k: 1, ..Default::default() });

        let mut ups = UserProfileService::new();
        assert_eq!(ups.add_empty_user_profile(UID), CommandResultCode::Success);
        ups.get_mut(UID).unwrap().add_to_account(FUT_QUOTE, 100_000);

        let mut engine = RiskEngine::new();

        let mut cmd = OrderCommand {
            command: OrderCommandType::PlaceOrder,
            order_id: 1,
            symbol: OPTION_SYMBOL,
            price: 100,
            size: 10,
            action: Some(OrderAction::Bid),
            order_type: Some(OrderType::Gtc),
            uid: UID,
            ..Default::default()
        };

        // 走 pre_process_command（真实 R1 入口）；若实现仍是 unimplemented!/panic! 会直接 abort 而非落到下面 assert_eq。
        engine.pre_process_command(&mut cmd, &mut ups, &ssp);
        assert_eq!(cmd.result_code, Some(CommandResultCode::UnsupportedSymbolType));
        assert!(
            !ups.get_mut(UID).unwrap().positions.contains_key(&OPTION_SYMBOL),
            "不支持的 symbol 类型不得创建 position"
        );
    }

    // ---- marginMode / leverage 跨腿一致性 ----

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

    // ---- ONEWAY + reduce-only 夹（§2 "ONEWAY 只减仓特例"） ----

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

    // ---- crossFreeMargin 循环 —— CROSS 浮盈可抵扣 / ISOLATED 只扣不抵（§3.3 ②） ----

    #[test]
    fn futures_place_order_cross_position_unrealized_profit_credits_other_symbol_order() {
        const OTHER_SYMBOL: i32 = 201;
        let (mut engine, mut ups, mut ssp) = setup_futures(2, 0, 600, 100);
        assert_eq!(ssp.add_symbol(futures_spec_for(OTHER_SYMBOL, 2, 0)), CommandResultCode::Success);
        engine.last_price_cache.insert(OTHER_SYMBOL, 200); // OTHER_SYMBOL mark 涨到 200

        // 已有 CROSS 仓（LONG open_volume=10@100）：estimatePnl=1000；calculateRequiredMarginForFutures=500。
        let mut other_pos = SymbolPositionRecord::new(UID, OTHER_SYMBOL, FUT_QUOTE, MarginMode::Cross, 1);
        other_pos.direction = PositionDirection::Long;
        other_pos.open_volume = 10;
        other_pos.open_price_sum = 1000;
        other_pos.open_init_margin_sum = 500;
        ups.get_mut(UID).unwrap().positions.insert(OTHER_SYMBOL, other_pos);

        // 新单 required（不计抵扣）=1020；crossFreeMargin=500 → 实际 required=520 ≤ spendable=600；断言 ValidForMatchingEngine 锁定 CROSS 浮盈抵扣通路。
        let mut cmd = futures_place_cmd(OrderAction::Bid, 10, 100, 1, MarginMode::Isolated, false);
        assert_eq!(
            engine.place_order_risk_check(&mut cmd, &mut ups, &ssp),
            CommandResultCode::ValidForMatchingEngine
        );
    }

    #[test]
    fn futures_place_order_isolated_other_position_margin_is_deducted_without_pnl_credit() {
        const OTHER_SYMBOL: i32 = 201;
        // balance=1_000：ISOLATED 其它仓需把 calculateRequiredMarginForFutures(=500) 从 spendable 剥离（不加浮盈抵扣）；required 实际=1520 > 1_000 → 必须 NSF。
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

    // ---- CLOSE_POSITION（§3 CLOSE_POSITION） ----

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

    // ---- pre_process_command 路由：CLOSE_POSITION → close_position_risk_check ----

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

    // ==== 期货 R2 — handleMatcherEventMargin（开/平/翻/PnL 结算，参考文档 §4/§6/§7；Java RiskEngine.java:1358-1511 + refundExtraMargin:1553-1574 + removePositionRecord:1580-1589）；两层测试：低层锁定 settle_margin_position_event 结算核心，集成层走 handler_risk_release 验证联动+守恒 ====

    const FUT2_MAKER_UID: i64 = 12;

    fn fut_currency_spec() -> CoreCurrencySpecification {
        CoreCurrencySpecification { currency: FUT_QUOTE, currency_scale_k: 1, ..Default::default() }
    }

    fn fut_trade_event(size: i64, price: i64, matched_order_uid: i64) -> MatcherTradeEvent {
        fut_trade_event_with_command(size, price, matched_order_uid, OrderCommandType::PlaceOrder)
    }

    /// 同 fut_trade_event，但允许显式指定 matched_order_command_type（新增，供 ONEWAY 不回归测试：即便与 cmd.command 不同，create_positions_key 应忽略该差异）。
    fn fut_trade_event_with_command(
        size: i64,
        price: i64,
        matched_order_uid: i64,
        matched_order_command_type: OrderCommandType,
    ) -> MatcherTradeEvent {
        MatcherTradeEvent {
            event_type: MatcherEventType::Trade,
            active_order_completed: false,
            maker_order_id: 0,
            maker_order_completed: false,
            price,
            size,
            bid_gt_ask: false,
            bidder_hold_price: 0, // 期货不用 bidderHoldPrice（现货专用字段，参考文档 §4）。
            matched_order_uid,
            matched_order_command_type,
            next: None,
        }
    }

    /// 模拟 R1 已挂单待撮合的前置状态（NSF 通过后才 insert 但一旦通过必已在 map 里）；已有记录则叠加 pending 而非覆盖。
    fn seed_pending_position(ups: &mut UserProfileService, uid: i64, action: OrderAction, size: i64, price: i64) {
        if ups.get(uid).is_none() {
            ups.add_empty_user_profile(uid);
        }
        let up = ups.get_mut(uid).unwrap();
        let mut pos = up
            .positions
            .get(&FUT_SYMBOL)
            .cloned()
            .unwrap_or_else(|| SymbolPositionRecord::new(uid, FUT_SYMBOL, FUT_QUOTE, MarginMode::Isolated, 1));
        pos.pending_hold(action, size, price);
        up.positions.insert(FUT_SYMBOL, pos);
    }

    fn fut_reject_reduce_event(event_type: MatcherEventType, size: i64) -> MatcherTradeEvent {
        MatcherTradeEvent {
            event_type,
            active_order_completed: false,
            maker_order_id: 0,
            maker_order_completed: false,
            price: 0,
            size,
            bid_gt_ask: false,
            bidder_hold_price: 0,
            matched_order_uid: 0,
            matched_order_command_type: OrderCommandType::PlaceOrder,
            next: None,
        }
    }

    // ---- 低层：settle_margin_position_event —— TRADE 开/平/翻 + REJECT/REDUCE + teardown ----

    #[test]
    fn settle_margin_open_new_position_no_pnl_charges_taker_fee_exact_conservation() {
        let spec = futures_spec(2, 0); // taker_fee=2（固定，每手 2）
        let currency_spec = fut_currency_spec();
        let mut ups = UserProfileService::new();
        assert_eq!(ups.add_empty_user_profile(UID), CommandResultCode::Success);
        {
            let up = ups.get_mut(UID).unwrap();
            up.add_to_account(FUT_QUOTE, 10_000);
            // 用刚挂单尚未成交的空仓记录模拟"R1 已建档"，而非假设 R2 能凭空插入新记录。
            let mut pos = SymbolPositionRecord::new(UID, FUT_SYMBOL, FUT_QUOTE, MarginMode::Isolated, 1);
            pos.pending_buy_size = 10;
            pos.pending_buy_avg_price = 100;
            up.positions.insert(FUT_SYMBOL, pos);
        }
        let mut fees: BTreeMap<i32, i64> = BTreeMap::new();
        let mte = fut_trade_event(10, 100, 0);

        let up = ups.get_mut(UID).unwrap();
        RiskEngine::settle_margin_position_event(
            up, FUT_SYMBOL, false, &mte, &spec, OrderAction::Bid, &mut fees, &currency_spec, 100, true,
        );

        let pos = up.positions.get(&FUT_SYMBOL).expect("open 后仍非空（open_volume>0），不应被拆记录");
        assert_eq!(pos.direction, PositionDirection::Long);
        assert_eq!(pos.open_volume, 10);
        assert_eq!(pos.open_price_sum, 1000); // 成本基按 trade price：10*100
        assert_eq!(pos.open_init_margin_sum, 1000); // 保证金按 mark：notional(100*10)/leverage(1)
        assert_eq!(pos.profit, 0, "开仓不产生已实现盈亏");

        // fee = taker_fee(2 固定) * size(10) = 20；无 PnL；仓非空不触发 teardown。
        assert_eq!(up.account(FUT_QUOTE), 10_000 - 20);
        assert_eq!(*fees.get(&FUT_QUOTE).unwrap(), 20);
        assert_eq!((up.account(FUT_QUOTE) - 10_000) + *fees.get(&FUT_QUOTE).unwrap(), 0, "唯一移动是费用配对");
    }

    #[test]
    fn settle_margin_partial_close_defers_pnl_into_cost_basis_no_realization() {
        let spec = futures_spec(2, 0);
        let currency_spec = fut_currency_spec();
        let mut ups = UserProfileService::new();
        assert_eq!(ups.add_empty_user_profile(UID), CommandResultCode::Success);
        {
            let up = ups.get_mut(UID).unwrap();
            up.add_to_account(FUT_QUOTE, 10_000);
            let mut pos = SymbolPositionRecord::new(UID, FUT_SYMBOL, FUT_QUOTE, MarginMode::Isolated, 1);
            pos.direction = PositionDirection::Long;
            pos.open_volume = 20;
            pos.open_price_sum = 2000; // 均价 100
            pos.open_init_margin_sum = 2000;
            up.positions.insert(FUT_SYMBOL, pos);
        }
        let mut fees: BTreeMap<i32, i64> = BTreeMap::new();
        // ASK 5（< openVolume 20）@110：部分平，价格优于成本但不实现盈亏——盈亏递延进剩余成本基。
        let mte = fut_trade_event(5, 110, 0);

        let up = ups.get_mut(UID).unwrap();
        RiskEngine::settle_margin_position_event(
            up, FUT_SYMBOL, false, &mte, &spec, OrderAction::Ask, &mut fees, &currency_spec, 100, true,
        );

        let pos = up.positions.get(&FUT_SYMBOL).expect("部分平仍非空，不应拆记录");
        assert_eq!(pos.open_volume, 15);
        assert_eq!(pos.open_init_margin_sum, 1500, "trunc(2000*5/20)=500 释放，剩 1500");
        assert_eq!(pos.open_price_sum, 1450, "2000 - tradeSize(5)*tradePrice(110)=2000-550");
        assert_eq!(pos.profit, 0, "部分平不实现盈亏");

        // fee = taker_fee(2) * closedSize(5) = 10；仅此账户变动，无 PnL 入账。
        assert_eq!(up.account(FUT_QUOTE), 10_000 - 10);
        assert_eq!(*fees.get(&FUT_QUOTE).unwrap(), 10);
    }

    #[test]
    fn settle_margin_full_close_realizes_pnl_and_removes_position_on_teardown() {
        let spec = futures_spec(2, 0);
        let currency_spec = fut_currency_spec();
        let mut ups = UserProfileService::new();
        assert_eq!(ups.add_empty_user_profile(UID), CommandResultCode::Success);
        {
            let up = ups.get_mut(UID).unwrap();
            up.add_to_account(FUT_QUOTE, 10_000);
            let mut pos = SymbolPositionRecord::new(UID, FUT_SYMBOL, FUT_QUOTE, MarginMode::Isolated, 1);
            pos.direction = PositionDirection::Long;
            pos.open_volume = 10;
            pos.open_price_sum = 1000; // 均价 100
            pos.open_init_margin_sum = 1000;
            up.positions.insert(FUT_SYMBOL, pos);
        }
        let mut fees: BTreeMap<i32, i64> = BTreeMap::new();
        // ASK 10（== openVolume）@120：全平，profit=(1200-1000)*(+1)=200，size_to_open=0（无翻仓）。
        let mte = fut_trade_event(10, 120, 0);

        let up = ups.get_mut(UID).unwrap();
        RiskEngine::settle_margin_position_event(
            up, FUT_SYMBOL, false, &mte, &spec, OrderAction::Ask, &mut fees, &currency_spec, 100, true,
        );

        assert!(!up.positions.contains_key(&FUT_SYMBOL), "全平且无残余挂单 → isEmpty → 拆记录");
        // fee = taker_fee(2)*closedSize(10)=20；PnL 结算：+200（isEmpty 唯一入账户处）。
        assert_eq!(up.account(FUT_QUOTE), 10_000 - 20 + 200);
        assert_eq!(*fees.get(&FUT_QUOTE).unwrap(), 20);
    }

    #[test]
    fn settle_margin_flip_closes_full_then_reopens_reverse_direction_defers_profit_payout() {
        let spec = futures_spec(2, 0);
        let currency_spec = fut_currency_spec();
        let mut ups = UserProfileService::new();
        assert_eq!(ups.add_empty_user_profile(UID), CommandResultCode::Success);
        {
            let up = ups.get_mut(UID).unwrap();
            up.add_to_account(FUT_QUOTE, 10_000);
            let mut pos = SymbolPositionRecord::new(UID, FUT_SYMBOL, FUT_QUOTE, MarginMode::Isolated, 1);
            pos.direction = PositionDirection::Long;
            pos.open_volume = 10;
            pos.open_price_sum = 1000; // 均价 100
            pos.open_init_margin_sum = 1000;
            up.positions.insert(FUT_SYMBOL, pos);
        }
        let mut fees: BTreeMap<i32, i64> = BTreeMap::new();
        // ASK 15（> openVolume 10）@120：平掉 10（实现 profit=200）+ 反手开空 5 @120（mark=100）。
        let mte = fut_trade_event(15, 120, 0);

        let up = ups.get_mut(UID).unwrap();
        RiskEngine::settle_margin_position_event(
            up, FUT_SYMBOL, false, &mte, &spec, OrderAction::Ask, &mut fees, &currency_spec, 100, true,
        );

        let pos = up.positions.get(&FUT_SYMBOL).expect("翻仓后新方向仓位非空，不拆记录");
        assert_eq!(pos.direction, PositionDirection::Short);
        assert_eq!(pos.open_volume, 5);
        assert_eq!(pos.open_price_sum, 600); // 5*120（成本基按 trade price）
        assert_eq!(pos.open_init_margin_sum, 500); // mark(100)*5/leverage(1)（保证金按 mark price）
        assert_eq!(pos.profit, 200, "平仓腿已实现盈亏累进 profit，但因新仓非空未结算入账户");

        // fee：close 腿 20 + open 腿 10 = 30；account 只扣 fee（profit 未结算，isEmpty()==false）。
        assert_eq!(up.account(FUT_QUOTE), 10_000 - 30);
        assert_eq!(*fees.get(&FUT_QUOTE).unwrap(), 30);
    }

    #[test]
    fn settle_margin_reject_reduce_only_releases_pending_no_account_change() {
        let spec = futures_spec(2, 0);
        let currency_spec = fut_currency_spec();
        let mut ups = UserProfileService::new();
        assert_eq!(ups.add_empty_user_profile(UID), CommandResultCode::Success);
        {
            let up = ups.get_mut(UID).unwrap();
            up.add_to_account(FUT_QUOTE, 5_000);
            let mut pos = SymbolPositionRecord::new(UID, FUT_SYMBOL, FUT_QUOTE, MarginMode::Isolated, 1);
            pos.pending_buy_size = 10;
            pos.pending_buy_avg_price = 100;
            up.positions.insert(FUT_SYMBOL, pos);
        }
        let mut fees: BTreeMap<i32, i64> = BTreeMap::new();
        let mte = fut_reject_reduce_event(MatcherEventType::Reject, 4);

        let up = ups.get_mut(UID).unwrap();
        RiskEngine::settle_margin_position_event(
            up, FUT_SYMBOL, false, &mte, &spec, OrderAction::Bid, &mut fees, &currency_spec, 100, true,
        );

        let pos = up.positions.get(&FUT_SYMBOL).unwrap();
        assert_eq!(pos.pending_buy_size, 6);
        assert_eq!(up.account(FUT_QUOTE), 5_000, "REJECT/REDUCE 只退 pending，不动账户");
        assert!(fees.is_empty());
    }

    #[test]
    fn settle_margin_reduce_full_pending_release_triggers_teardown_refunds_extra_margin_and_leftover_profit() {
        let spec = futures_spec(2, 0);
        let currency_spec = fut_currency_spec();
        let mut ups = UserProfileService::new();
        assert_eq!(ups.add_empty_user_profile(UID), CommandResultCode::Success);
        {
            let up = ups.get_mut(UID).unwrap();
            up.add_to_account(FUT_QUOTE, 1_000);
            // 合成场景：open_volume=0 但残余 pending 阻止此前 teardown，带 extraMargin/profit 残留，验证 teardown 一次性结清。
            let mut pos = SymbolPositionRecord::new(UID, FUT_SYMBOL, FUT_QUOTE, MarginMode::Isolated, 1);
            pos.pending_sell_size = 3;
            pos.profit = 50;
            pos.extra_margin = 30;
            up.positions.insert(FUT_SYMBOL, pos);
        }
        let mut fees: BTreeMap<i32, i64> = BTreeMap::new();
        let mte = fut_reject_reduce_event(MatcherEventType::Reduce, 3); // 释放全部剩余 pending → isEmpty

        let up = ups.get_mut(UID).unwrap();
        RiskEngine::settle_margin_position_event(
            up, FUT_SYMBOL, false, &mte, &spec, OrderAction::Ask, &mut fees, &currency_spec, 100, true,
        );

        assert!(!up.positions.contains_key(&FUT_SYMBOL), "isEmpty 后应拆记录");
        assert_eq!(up.account(FUT_QUOTE), 1_000 + 30 + 50, "extraMargin(30) + profit(50) 一次性入账");
        assert!(fees.is_empty(), "本次无成交，无 fee 移动");
    }

    #[test]
    fn settle_margin_missing_position_required_false_is_noop() {
        let spec = futures_spec(2, 0);
        let currency_spec = fut_currency_spec();
        let mut ups = UserProfileService::new();
        assert_eq!(ups.add_empty_user_profile(UID), CommandResultCode::Success);
        let mut fees: BTreeMap<i32, i64> = BTreeMap::new();
        let mte = fut_trade_event(10, 100, 0);

        let up = ups.get_mut(UID).unwrap();
        RiskEngine::settle_margin_position_event(
            up, FUT_SYMBOL, false, &mte, &spec, OrderAction::Bid, &mut fees, &currency_spec, 100, true,
        );

        assert!(!up.positions.contains_key(&FUT_SYMBOL), "缺失 position 时 required=false 应静默跳过");
        assert_eq!(up.account(FUT_QUOTE), 0);
        assert!(fees.is_empty());
    }

    #[test]
    #[should_panic(expected = "maker position record missing")]
    fn settle_margin_missing_position_required_true_panics() {
        let spec = futures_spec(2, 0);
        let currency_spec = fut_currency_spec();
        let mut ups = UserProfileService::new();
        assert_eq!(ups.add_empty_user_profile(UID), CommandResultCode::Success);
        let mut fees: BTreeMap<i32, i64> = BTreeMap::new();
        let mte = fut_trade_event(10, 100, 0);

        let up = ups.get_mut(UID).unwrap();
        RiskEngine::settle_margin_position_event(
            up, FUT_SYMBOL, true, &mte, &spec, OrderAction::Bid, &mut fees, &currency_spec, 100, false,
        );
    }

    #[test]
    fn settle_margin_maker_side_uses_maker_fee_rate_not_taker_rate() {
        let spec = futures_spec_with_fees(10, 3, 0); // taker=10、maker=3，均固定
        let currency_spec = fut_currency_spec();
        let mut ups = UserProfileService::new();
        assert_eq!(ups.add_empty_user_profile(UID), CommandResultCode::Success);
        {
            let up = ups.get_mut(UID).unwrap();
            up.add_to_account(FUT_QUOTE, 10_000);
            let mut pos = SymbolPositionRecord::new(UID, FUT_SYMBOL, FUT_QUOTE, MarginMode::Isolated, 1);
            pos.pending_buy_size = 10;
            pos.pending_buy_avg_price = 100;
            up.positions.insert(FUT_SYMBOL, pos);
        }
        let mut fees: BTreeMap<i32, i64> = BTreeMap::new();
        let mte = fut_trade_event(10, 100, 0);

        let up = ups.get_mut(UID).unwrap();
        RiskEngine::settle_margin_position_event(
            up, FUT_SYMBOL, false, &mte, &spec, OrderAction::Bid, &mut fees, &currency_spec, 100,
            false, // is_taker=false → 必须用 maker_fee(3)，不是 taker_fee(10)
        );

        assert_eq!(up.account(FUT_QUOTE), 10_000 - 30); // maker_fee(3)*size(10)=30
        assert_eq!(*fees.get(&FUT_QUOTE).unwrap(), 30);
    }

    #[test]
    fn settle_margin_proportional_fee_is_exact_no_ceil_drift_taker_and_maker() {
        // futures 费用同一次计算值借记 accounts+贷记 fees（无现货两套公式落差），比例费天然精确配对无 dust，本测试断言 EXACT 守恒。
        let spec = futures_spec_with_fees(333, 111, 10_000); // taker≈3.33%、maker≈1.11%，故意选不整除的比例
        let currency_spec = fut_currency_spec();
        let mut ups = UserProfileService::new();
        assert_eq!(ups.add_empty_user_profile(UID), CommandResultCode::Success);
        {
            let up = ups.get_mut(UID).unwrap();
            up.add_to_account(FUT_QUOTE, 100_000);
            let mut pos = SymbolPositionRecord::new(UID, FUT_SYMBOL, FUT_QUOTE, MarginMode::Isolated, 1);
            pos.pending_buy_size = 7;
            pos.pending_buy_avg_price = 101;
            up.positions.insert(FUT_SYMBOL, pos);
        }
        let mut fees: BTreeMap<i32, i64> = BTreeMap::new();
        let mte = fut_trade_event(7, 101, 0); // 刻意用不整除的 size/price 组合放大 ceil 效应

        let up = ups.get_mut(UID).unwrap();
        RiskEngine::settle_margin_position_event(
            up, FUT_SYMBOL, false, &mte, &spec, OrderAction::Bid, &mut fees, &currency_spec, 100, true,
        );
        let taker_fee = arithmetic::calculate_taker_fee(7, 101, 333, 10_000);
        assert_ne!(taker_fee, 0);
        assert_eq!(up.account(FUT_QUOTE), 100_000 - taker_fee, "借记的就是算出来的那一个值，逐位精确");
        assert_eq!(*fees.get(&FUT_QUOTE).unwrap(), taker_fee, "贷记的也是同一个值，精确配对");
        assert_eq!((up.account(FUT_QUOTE) - 100_000) + *fees.get(&FUT_QUOTE).unwrap(), 0, "EXACT 守恒，非近似");
    }

    // ---- 集成层：handler_risk_release —— taker+maker 联动 / 链路分派 / 跨用户全局守恒 ----

    #[test]
    fn handler_risk_release_futures_trade_opens_both_sides_and_conserves() {
        let (mut engine, mut ups, ssp) = setup_futures(2, 0, 10_000, 100); // taker=2 固定，maker=0
        assert_eq!(ups.add_empty_user_profile(FUT2_MAKER_UID), CommandResultCode::Success);
        ups.get_mut(FUT2_MAKER_UID).unwrap().add_to_account(FUT_QUOTE, 10_000);
        seed_pending_position(&mut ups, UID, OrderAction::Bid, 10, 100);
        seed_pending_position(&mut ups, FUT2_MAKER_UID, OrderAction::Ask, 10, 100);

        let mut cmd = OrderCommand {
            command: OrderCommandType::PlaceOrder,
            symbol: FUT_SYMBOL,
            action: Some(OrderAction::Bid),
            uid: UID,
            ..Default::default()
        };
        cmd.matcher_event = Some(Box::new(fut_trade_event(10, 100, FUT2_MAKER_UID)));

        let taker_before = ups.get(UID).unwrap().account(FUT_QUOTE);
        let maker_before = ups.get(FUT2_MAKER_UID).unwrap().account(FUT_QUOTE);

        engine.handler_risk_release(&mut cmd, &mut ups, &ssp);

        assert!(cmd.matcher_event.is_none(), "TRADE 链结算后应清空（对齐现货分支的消费语义）");
        let taker_pos = ups.get(UID).unwrap().positions.get(&FUT_SYMBOL).unwrap();
        assert_eq!(taker_pos.direction, PositionDirection::Long);
        assert_eq!(taker_pos.open_volume, 10);
        let maker_pos = ups.get(FUT2_MAKER_UID).unwrap().positions.get(&FUT_SYMBOL).unwrap();
        assert_eq!(maker_pos.direction, PositionDirection::Short);
        assert_eq!(maker_pos.open_volume, 10);

        let taker_delta = ups.get(UID).unwrap().account(FUT_QUOTE) - taker_before;
        let maker_delta = ups.get(FUT2_MAKER_UID).unwrap().account(FUT_QUOTE) - maker_before;
        let fees_delta = *engine.fees.get(&FUT_QUOTE).unwrap_or(&0);
        assert_eq!(taker_delta, -20, "taker_fee(2)*size(10)");
        assert_eq!(maker_delta, 0, "本 fixture maker_fee=0");
        assert_eq!(taker_delta + maker_delta + fees_delta, 0, "开仓：唯一移动是费用配对");
    }

    /// create_positions_key maker 侧改用 mte.matched_order_command_type 后 ONEWAY 行为不变，即便与 cmd.command 故意不同（taker=ForceLiquidation/maker=PlaceOrder），断言与开仓用例逐项相同。
    #[test]
    fn handler_risk_release_futures_trade_oneway_unaffected_by_matched_order_command_type_switch() {
        let (mut engine, mut ups, ssp) = setup_futures(2, 0, 10_000, 100);
        assert_eq!(ups.add_empty_user_profile(FUT2_MAKER_UID), CommandResultCode::Success);
        ups.get_mut(FUT2_MAKER_UID).unwrap().add_to_account(FUT_QUOTE, 10_000);
        seed_pending_position(&mut ups, UID, OrderAction::Bid, 10, 100);
        seed_pending_position(&mut ups, FUT2_MAKER_UID, OrderAction::Ask, 10, 100);

        // taker 命令 ForceLiquidation，maker matched_order_command_type 是 PlaceOrder，故意不同，模拟真实场景。
        let mut cmd = OrderCommand {
            command: OrderCommandType::ForceLiquidation,
            symbol: FUT_SYMBOL,
            action: Some(OrderAction::Bid),
            uid: UID,
            ..Default::default()
        };
        cmd.matcher_event = Some(Box::new(fut_trade_event_with_command(
            10,
            100,
            FUT2_MAKER_UID,
            OrderCommandType::PlaceOrder,
        )));

        let taker_before = ups.get(UID).unwrap().account(FUT_QUOTE);
        let maker_before = ups.get(FUT2_MAKER_UID).unwrap().account(FUT_QUOTE);

        engine.handler_risk_release(&mut cmd, &mut ups, &ssp);

        assert!(cmd.matcher_event.is_none());
        let taker_pos = ups.get(UID).unwrap().positions.get(&FUT_SYMBOL).unwrap();
        assert_eq!(taker_pos.direction, PositionDirection::Long);
        assert_eq!(taker_pos.open_volume, 10);
        let maker_pos = ups.get(FUT2_MAKER_UID).unwrap().positions.get(&FUT_SYMBOL).unwrap();
        assert_eq!(maker_pos.direction, PositionDirection::Short);
        assert_eq!(maker_pos.open_volume, 10);

        let taker_delta = ups.get(UID).unwrap().account(FUT_QUOTE) - taker_before;
        let maker_delta = ups.get(FUT2_MAKER_UID).unwrap().account(FUT_QUOTE) - maker_before;
        let fees_delta = *engine.fees.get(&FUT_QUOTE).unwrap_or(&0);
        assert_eq!(taker_delta, -20, "与 taker=PlaceOrder 基线完全一致（ONEWAY 忽略 command）");
        assert_eq!(maker_delta, 0);
        assert_eq!(taker_delta + maker_delta + fees_delta, 0);
    }

    #[test]
    fn handler_risk_release_futures_full_round_trip_open_then_close_is_zero_sum_between_counterparties() {
        let (mut engine, mut ups, ssp) = setup_futures(2, 0, 10_000, 100); // taker=2 固定，maker=0
        assert_eq!(ups.add_empty_user_profile(FUT2_MAKER_UID), CommandResultCode::Success);
        ups.get_mut(FUT2_MAKER_UID).unwrap().add_to_account(FUT_QUOTE, 10_000);
        seed_pending_position(&mut ups, UID, OrderAction::Bid, 10, 100);
        seed_pending_position(&mut ups, FUT2_MAKER_UID, OrderAction::Ask, 10, 100);

        // 开仓：UID 多头 10 @100 vs FUT2_MAKER_UID 空头 10 @100。
        let mut open_cmd = OrderCommand {
            command: OrderCommandType::PlaceOrder,
            symbol: FUT_SYMBOL,
            action: Some(OrderAction::Bid),
            uid: UID,
            ..Default::default()
        };
        open_cmd.matcher_event = Some(Box::new(fut_trade_event(10, 100, FUT2_MAKER_UID)));
        engine.handler_risk_release(&mut open_cmd, &mut ups, &ssp);

        let taker_after_open = ups.get(UID).unwrap().account(FUT_QUOTE);
        let maker_after_open = ups.get(FUT2_MAKER_UID).unwrap().account(FUT_QUOTE);

        // 平仓前：双方各自再挂一笔平仓单（R1 已建档，pending 叠加到既有 open_volume 之上）。
        seed_pending_position(&mut ups, UID, OrderAction::Ask, 10, 120);
        seed_pending_position(&mut ups, FUT2_MAKER_UID, OrderAction::Bid, 10, 120);

        // 平仓：UID 卖出 10 @120 平多，对手 FUT2_MAKER_UID 买回 10 平空——双方都整仓平掉。
        let mut close_cmd = OrderCommand {
            command: OrderCommandType::PlaceOrder,
            symbol: FUT_SYMBOL,
            action: Some(OrderAction::Ask),
            uid: UID,
            ..Default::default()
        };
        close_cmd.matcher_event = Some(Box::new(fut_trade_event(10, 120, FUT2_MAKER_UID)));
        engine.handler_risk_release(&mut close_cmd, &mut ups, &ssp);

        assert!(!ups.get(UID).unwrap().positions.contains_key(&FUT_SYMBOL), "taker 全平应拆记录");
        assert!(
            !ups.get(FUT2_MAKER_UID).unwrap().positions.contains_key(&FUT_SYMBOL),
            "maker 全平应拆记录"
        );

        let taker_final = ups.get(UID).unwrap().account(FUT_QUOTE);
        let maker_final = ups.get(FUT2_MAKER_UID).unwrap().account(FUT_QUOTE);

        // taker(多头) profit=(120-100)*10=200，close_fee=taker_fee(2)*10=20。
        assert_eq!(taker_final - taker_after_open, 200 - 20);
        // maker(空头) profit=-(120-100)*10=-200，close_fee=maker_fee(0)*10=0。
        assert_eq!(maker_final - maker_after_open, -200);

        // 全局守恒：两用户全程净变动 + fees 净变动 == 0（zero-sum PnL+fee 是唯一真实转移）。
        let total_user_delta = (taker_final - 10_000) + (maker_final - 10_000);
        let fees_total = *engine.fees.get(&FUT_QUOTE).unwrap();
        assert_eq!(total_user_delta + fees_total, 0);
    }

    #[test]
    fn handler_risk_release_futures_chain_head_reduce_then_trade_applies_both_in_one_call() {
        // 验证期货不预摘链头设计：REDUCE(4)+TRADE(6) 同链，一次 handler_risk_release 应把两事件都结算。
        let (mut engine, mut ups, ssp) = setup_futures(2, 0, 10_000, 100);
        assert_eq!(ups.add_empty_user_profile(FUT2_MAKER_UID), CommandResultCode::Success);
        ups.get_mut(FUT2_MAKER_UID).unwrap().add_to_account(FUT_QUOTE, 10_000);

        {
            let up = ups.get_mut(UID).unwrap();
            let mut pos = SymbolPositionRecord::new(UID, FUT_SYMBOL, FUT_QUOTE, MarginMode::Isolated, 1);
            pos.pending_buy_size = 10; // R1 挂单量（REDUCE(4) 先撤掉一部分，TRADE(6) 成交剩余）
            pos.pending_buy_avg_price = 100;
            up.positions.insert(FUT_SYMBOL, pos);
        }
        // maker 只参与 TRADE(6)（REDUCE 是 taker 自己撤单，与 maker 无关），故 pending 只需覆盖 size=6。
        seed_pending_position(&mut ups, FUT2_MAKER_UID, OrderAction::Ask, 6, 100);

        let mut cmd = OrderCommand {
            command: OrderCommandType::PlaceOrder,
            symbol: FUT_SYMBOL,
            action: Some(OrderAction::Bid),
            uid: UID,
            ..Default::default()
        };
        let trade = fut_trade_event(6, 100, FUT2_MAKER_UID);
        let mut reduce = fut_reject_reduce_event(MatcherEventType::Reduce, 4);
        reduce.next = Some(Box::new(trade));
        cmd.matcher_event = Some(Box::new(reduce));

        engine.handler_risk_release(&mut cmd, &mut ups, &ssp);

        let taker_pos = ups.get(UID).unwrap().positions.get(&FUT_SYMBOL).unwrap();
        assert_eq!(taker_pos.pending_buy_size, 0, "REDUCE(4) + TRADE(6) 应耗尽全部 10 挂单");
        assert_eq!(taker_pos.open_volume, 6, "TRADE 事件应正常开仓 6 手，未被链头 REDUCE 影响");

        let maker_pos = ups.get(FUT2_MAKER_UID).unwrap().positions.get(&FUT_SYMBOL).unwrap();
        assert_eq!(maker_pos.open_volume, 6, "maker 只在 TRADE 事件参与，REDUCE 与其无关");
    }

    // ==== 统一账户 — calculate_locked/calculate_free_futures_margin + 现货 NSF/withdrawable 顶账（参考文档 §4/§5；Java RiskEngine.java:759-805,1040-1055）；复用 setup_futures，leverage=1 无 pending 时 calculate_required_margin_for_futures 退化为 open_init_margin_sum ====

    #[test]
    fn calculate_locked_zero_when_no_positions_and_no_exchange_locked() {
        let (engine, ups, ssp) = setup_futures(0, 0, 0, 100);
        let up = ups.get(UID).unwrap();
        let currency_spec = ssp.get_currency(FUT_QUOTE).unwrap();
        assert_eq!(engine.calculate_locked(up, FUT_QUOTE, &ssp, currency_spec), 0);
    }

    #[test]
    fn calculate_locked_sums_futures_margin_and_exchange_locked() {
        let (engine, mut ups, ssp) = setup_futures(0, 0, 0, 100);
        {
            let up = ups.get_mut(UID).unwrap();
            up.add_to_locked(FUT_QUOTE, 200); // 现货挂单冻结
            let mut pos = SymbolPositionRecord::new(UID, FUT_SYMBOL, FUT_QUOTE, MarginMode::Isolated, 1);
            pos.direction = PositionDirection::Long;
            pos.open_volume = 10;
            pos.open_price_sum = 1000;
            pos.open_init_margin_sum = 1000; // 见上：leverage=1 无 pending → required = 本值
            up.positions.insert(FUT_SYMBOL, pos);
        }
        let up = ups.get(UID).unwrap();
        let currency_spec = ssp.get_currency(FUT_QUOTE).unwrap();
        // ① 期货保证金(1000) + ② 现货冻结(200) + ③④ 借贷抵押(0, 本用户无 loan)。
        assert_eq!(engine.calculate_locked(up, FUT_QUOTE, &ssp, currency_spec), 1200);
    }

    // ---- loanCollateralLocked 虚拟锁——借贷抵押留在 accounts 但不能被挂单/保证金/提现顶用（对应 Java :1063-1072） ----

    fn isolated_loan_with_collateral(loan_id: i64, collateral_currency: i32, collateral_amount: i64)
        -> crate::core::common::isolated_loan_record::IsolatedLoanRecord {
        crate::core::common::isolated_loan_record::IsolatedLoanRecord {
            loan_id,
            collateral_currency,
            collateral_amount,
            ..Default::default()
        }
    }

    #[test]
    fn loan_collateral_locked_sums_isolated_and_cross_by_currency() {
        let (engine, mut ups, _ssp) = setup_futures(0, 0, 0, 100);
        {
            let up = ups.get_mut(UID).unwrap();
            up.isolated_loans.insert(1, isolated_loan_with_collateral(1, FUT_QUOTE, 300));
            up.isolated_loans.insert(2, isolated_loan_with_collateral(2, FUT_QUOTE, 50));
            up.isolated_loans.insert(3, isolated_loan_with_collateral(3, FUT_BASE, 999)); // 他币不计入 FUT_QUOTE
            up.cross_loan_collateral.insert(FUT_QUOTE, 100);
        }
        let up = ups.get(UID).unwrap();
        // ③ Isolated(300+50，FUT_BASE 的 999 不算) + ④ Cross(100) = 450。
        assert_eq!(engine.loan_collateral_locked(up, FUT_QUOTE), 450);
        assert_eq!(engine.loan_collateral_locked(up, FUT_BASE), 999);
        // 无任何抵押的币种恒 0。
        assert_eq!(engine.loan_collateral_locked(up, 12345), 0);
    }

    #[test]
    fn loan_collateral_locked_zero_for_user_without_loans() {
        // Ruling P5-B：无 loan 用户两个 map 皆空，虚拟锁恒 0，与此前 stub 逐位相同。
        let (engine, ups, _ssp) = setup_futures(0, 0, 1_000, 100);
        assert_eq!(engine.loan_collateral_locked(ups.get(UID).unwrap(), FUT_QUOTE), 0);
    }

    #[test]
    fn calculate_locked_includes_loan_collateral() {
        let (engine, mut ups, ssp) = setup_futures(0, 0, 0, 100);
        {
            let up = ups.get_mut(UID).unwrap();
            up.add_to_locked(FUT_QUOTE, 200); // ② 现货冻结
            up.isolated_loans.insert(1, isolated_loan_with_collateral(1, FUT_QUOTE, 300)); // ③ 借贷抵押
        }
        let up = ups.get(UID).unwrap();
        let currency_spec = ssp.get_currency(FUT_QUOTE).unwrap();
        // ① 期货(0) + ② 现货冻结(200) + ③④ 借贷抵押(300) = 500。
        assert_eq!(engine.calculate_locked(up, FUT_QUOTE, &ssp, currency_spec), 500);
    }

    #[test]
    fn place_exchange_order_nsf_when_loan_collateral_locks_the_balance() {
        // accounts=100（其中 100 是 isolated loan 抵押物）：无 loan 时 notional=50 可放行，但借贷抵押虚拟锁走 100 → free=0 → NSF。
        let (mut engine, mut ups, mut ssp) = setup_futures(0, 0, 100, 100);
        add_spot_symbol_sharing_fut_quote(&mut ssp);
        ups.get_mut(UID).unwrap().isolated_loans.insert(1, isolated_loan_with_collateral(1, FUT_QUOTE, 100));

        let mut cmd = spot_bid_cmd(50, 1); // notional=50
        let result = engine.place_order_risk_check(&mut cmd, &mut ups, &ssp);
        assert_eq!(result, CommandResultCode::RiskNsf, "借贷抵押锁走余额后现货挂单应 NSF");
        assert_eq!(ups.get(UID).unwrap().locked(FUT_QUOTE), 0, "NSF 不得锁定任何额度");
    }

    #[test]
    fn place_exchange_order_succeeds_when_free_balance_covers_order_despite_loan() {
        // 对照：同样 100 抵押，但 accounts=200 → free=200-100=100 ≥ notional 50 → 放行。
        let (mut engine, mut ups, mut ssp) = setup_futures(0, 0, 200, 100);
        add_spot_symbol_sharing_fut_quote(&mut ssp);
        ups.get_mut(UID).unwrap().isolated_loans.insert(1, isolated_loan_with_collateral(1, FUT_QUOTE, 100));

        let mut cmd = spot_bid_cmd(50, 1);
        let result = engine.place_order_risk_check(&mut cmd, &mut ups, &ssp);
        assert_eq!(result, CommandResultCode::ValidForMatchingEngine);
        assert_eq!(ups.get(UID).unwrap().locked(FUT_QUOTE), 50);
    }

    #[test]
    fn withdrawable_balance_deducts_loan_collateral() {
        // accounts=500，isolated loan 抵押 300（同币）→ 可提 = 500 − 0 冻结 − 300 抵押 + 0 期货 = 200。
        let (engine, mut ups, ssp) = setup_futures(0, 0, 500, 100);
        ups.get_mut(UID).unwrap().isolated_loans.insert(1, isolated_loan_with_collateral(1, FUT_QUOTE, 300));
        let up = ups.get(UID).unwrap();
        assert_eq!(engine.withdrawable_balance(up, FUT_QUOTE, &ssp), 200);
    }

    #[test]
    fn calculate_free_futures_margin_zero_for_user_with_no_positions() {
        // Ruling P4-A 基线：无期货仓的用户，净期货盈余恒 0。
        let (engine, ups, ssp) = setup_futures(0, 0, 1_000, 100);
        let up = ups.get(UID).unwrap();
        assert_eq!(engine.calculate_free_futures_margin(up, FUT_QUOTE, &ssp), 0);
    }

    #[test]
    fn calculate_free_futures_margin_isolated_position_never_credits_its_own_upnl() {
        // ISOLATED 仓：required margin 扣，但 2 参重载（curPosSymbol=-1 永不匹配）下浮盈不外借。
        let (engine, mut ups, ssp) = setup_futures(0, 0, 0, 300);
        {
            let up = ups.get_mut(UID).unwrap();
            let mut pos = SymbolPositionRecord::new(UID, FUT_SYMBOL, FUT_QUOTE, MarginMode::Isolated, 1);
            pos.direction = PositionDirection::Long;
            pos.open_volume = 10;
            pos.open_price_sum = 900;
            pos.open_init_margin_sum = 200; // required margin = 200（新敞口=0）
            // mark=300 → 浮盈 = 1*(10*300-900) = 2100，但 ISOLATED + curPosSymbol 不匹配 → 不计。
            up.positions.insert(FUT_SYMBOL, pos);
        }
        let up = ups.get(UID).unwrap();
        // 两个估计都只剩 "0 - isolatedRequiredMargin(200)"。
        assert_eq!(engine.calculate_free_futures_margin(up, FUT_QUOTE, &ssp), -200);
    }

    #[test]
    fn calculate_free_futures_margin_cross_position_takes_min_of_two_conservative_estimates() {
        // 维持保证金分档：单档 5%（rate=50/scale_k=1000），覆盖任意 notional，故不复用 setup_futures，改手工搭建。
        let mut fut_spec = futures_spec(0, 0);
        fut_spec.maintenance_margin_scale_k = 1000;
        fut_spec.maintenance_margin.insert(i64::MAX, 50);
        let mut ssp = SymbolSpecificationProvider::new();
        assert_eq!(ssp.add_symbol(fut_spec), CommandResultCode::Success);
        ssp.add_currency(CoreCurrencySpecification { currency: FUT_QUOTE, currency_scale_k: 1, ..Default::default() });
        ssp.add_currency(CoreCurrencySpecification { currency: FUT_BASE, currency_scale_k: 1, ..Default::default() });

        let mut ups = UserProfileService::new();
        assert_eq!(ups.add_empty_user_profile(UID), CommandResultCode::Success);
        let mut engine = RiskEngine::new();
        engine.last_price_cache.insert(FUT_SYMBOL, 200);
        {
            let up = ups.get_mut(UID).unwrap();
            let mut pos = SymbolPositionRecord::new(UID, FUT_SYMBOL, FUT_QUOTE, MarginMode::Cross, 1);
            pos.direction = PositionDirection::Long;
            pos.open_volume = 10;
            pos.open_price_sum = 900;
            pos.open_init_margin_sum = 200; // required(初始) margin = 200（新敞口=0）
            pos.profit = 500; // 已实现但未派发的"翻仓遗留"利润（见头部注释）
            up.positions.insert(FUT_SYMBOL, pos);
        }
        let up = ups.get(UID).unwrap();
        // ①（计浮盈扣初始保证金）=500+1100-200=1400；②（不计浮盈扣维持保证金）=500-100=400；min=400（更保守胜出）。
        assert_eq!(engine.calculate_free_futures_margin(up, FUT_QUOTE, &ssp), 400);
    }

    #[test]
    fn calculate_free_futures_margin_flat_cross_position_with_carried_profit() {
        // open_volume=0（同一 flip 循环内 profit 未派发的过渡态）：required margin/浮盈恒 0，净期货盈余=已实现 profit 全额。
        let (engine, mut ups, ssp) = setup_futures(0, 0, 0, 100);
        {
            let up = ups.get_mut(UID).unwrap();
            let mut pos = SymbolPositionRecord::new(UID, FUT_SYMBOL, FUT_QUOTE, MarginMode::Cross, 1);
            pos.profit = 500;
            up.positions.insert(FUT_SYMBOL, pos);
        }
        let up = ups.get(UID).unwrap();
        assert_eq!(engine.calculate_free_futures_margin(up, FUT_QUOTE, &ssp), 500);
    }

    // ---- Ruling P4-A 接线验证：无期货仓用户现货 NSF/withdrawable 是纯 no-op；有 CROSS 浮盈用户两处 NSF 额度顶账 ----

    const SPOT_SYMBOL: i32 = 300;
    const SPOT_BASE: i32 = 3;

    fn add_spot_symbol_sharing_fut_quote(ssp: &mut SymbolSpecificationProvider) {
        let spot_spec = CoreSymbolSpecification {
            symbol_id: SPOT_SYMBOL,
            symbol_type: SymbolType::CurrencyExchangePair,
            base_currency: SPOT_BASE,
            quote_currency: FUT_QUOTE, // 与期货 symbol 共用同一 quote currency，才能顶账
            base_scale_k: 1,
            quote_scale_k: 1,
            taker_fee: 0,
            maker_fee: 0,
            fee_scale_k: 0,
            ..Default::default()
        };
        assert_eq!(ssp.add_symbol(spot_spec), CommandResultCode::Success);
        ssp.add_currency(CoreCurrencySpecification { currency: SPOT_BASE, currency_scale_k: 1, ..Default::default() });
    }

    fn spot_bid_cmd(size: i64, price: i64) -> OrderCommand {
        OrderCommand {
            command: OrderCommandType::PlaceOrder,
            order_id: 1,
            symbol: SPOT_SYMBOL,
            price,
            size,
            reserve_bid_price: price,
            action: Some(OrderAction::Bid),
            order_type: Some(OrderType::Gtc),
            uid: UID,
            ..Default::default()
        }
    }

    #[test]
    fn place_exchange_order_position_less_user_nsf_is_unaffected_by_wiring() {
        // Ruling P4-A：无期货仓 → calculate_free_futures_margin 恒 0 → 现货 NSF 判定与改动前相同。
        let (mut engine, mut ups, mut ssp) = setup_futures(0, 0, 0, 100);
        add_spot_symbol_sharing_fut_quote(&mut ssp);

        let mut cmd = spot_bid_cmd(100, 1); // notional=100，quote 余额=0 → 应 NSF
        let result = engine.place_order_risk_check(&mut cmd, &mut ups, &ssp);

        assert_eq!(result, CommandResultCode::RiskNsf);
        assert_eq!(ups.get(UID).unwrap().locked(FUT_QUOTE), 0);
    }

    #[test]
    fn place_exchange_order_spot_nsf_topped_up_by_futures_cross_profit() {
        // 同上测试参数（本应 NSF），但用户有 CROSS 期货仓携带浮盈 500，对应 Java :666-669 现货 NSF 顶账公式。
        let (mut engine, mut ups, mut ssp) = setup_futures(0, 0, 0, 100);
        add_spot_symbol_sharing_fut_quote(&mut ssp);
        {
            let up = ups.get_mut(UID).unwrap();
            let mut pos = SymbolPositionRecord::new(UID, FUT_SYMBOL, FUT_QUOTE, MarginMode::Cross, 1);
            pos.profit = 500;
            up.positions.insert(FUT_SYMBOL, pos);
        }

        let mut cmd = spot_bid_cmd(100, 1); // notional=100 > 0 余额，但 < 500 期货净盈余顶账后的额度
        let result = engine.place_order_risk_check(&mut cmd, &mut ups, &ssp);

        assert_eq!(result, CommandResultCode::ValidForMatchingEngine);
        assert_eq!(ups.get(UID).unwrap().locked(FUT_QUOTE), 100);
    }

    #[test]
    fn withdrawable_position_less_user_nsf_is_unaffected_by_wiring() {
        // Ruling P4-A：无期货仓的提现 NSF 判定与改动前相同。
        let mut ups = UserProfileService::new();
        let mut engine = RiskEngine::new();
        let ssp = SymbolSpecificationProvider::new();
        engine.add_user(&add_user_cmd(UID), &mut ups);
        engine.balance_adjustment(&balance_adjustment_cmd(UID, QUOTE, 100, 1), &mut ups, &ssp);

        let withdraw = balance_adjustment_cmd(UID, QUOTE, -400, 2);
        assert_eq!(engine.balance_adjustment(&withdraw, &mut ups, &ssp), CommandResultCode::RiskNsf);
        assert_eq!(ups.get(UID).unwrap().account(QUOTE), 100);
    }

    #[test]
    fn withdrawable_topped_up_by_futures_cross_profit() {
        // account=500/locked=200：不顶账 withdrawable=300，提现 400 先撞外层 NSF；内层 account+amount_diff 不受影响。
        let (mut engine, mut ups, ssp) = setup_futures(0, 0, 500, 100);
        {
            let up = ups.get_mut(UID).unwrap();
            up.add_to_locked(FUT_QUOTE, 200);
            let mut pos = SymbolPositionRecord::new(UID, FUT_SYMBOL, FUT_QUOTE, MarginMode::Cross, 1);
            pos.profit = 500;
            up.positions.insert(FUT_SYMBOL, pos);
        }

        // 提现 400：不顶账 withdrawable=300 不够，+500 期货净盈余顶账后足够（800 >= 400）。
        let withdraw_cmd = OrderCommand {
            command: OrderCommandType::BalanceAdjustment,
            uid: UID,
            symbol: FUT_QUOTE,
            price: -400,
            order_id: 2,
            ..Default::default()
        };
        assert_eq!(engine.balance_adjustment(&withdraw_cmd, &mut ups, &ssp), CommandResultCode::Success);
        assert_eq!(ups.get(UID).unwrap().account(FUT_QUOTE), 500 - 400);
    }

    // ==== MARGIN_ADJUSTMENT + mark-price 更新 + LEVERAGE_ADJUSTMENT（参考文档 §1(extraMargin)/§8；Java adjustMargin:213-277/adjustLeverage:287-333/adjustMarkPrice:437-451）；复用 setup_futures 治具 ====

    fn margin_adjustment_cmd(
        action: OrderAction,
        symbol: i32,
        price: i64,
        margin_mode: MarginMode,
        order_id: i64,
    ) -> OrderCommand {
        OrderCommand {
            command: OrderCommandType::MarginAdjustment,
            uid: UID,
            symbol,
            price,
            action: Some(action),
            margin_mode,
            order_id,
            ..Default::default()
        }
    }

    fn isolated_position(leverage: i32) -> SymbolPositionRecord {
        SymbolPositionRecord::new(UID, FUT_SYMBOL, FUT_QUOTE, MarginMode::Isolated, leverage)
    }

    #[test]
    fn margin_adjustment_isolated_add_debits_accounts_credits_extra_margin_and_conserves() {
        // scale 全 1 恒等缩放：currency_to_size_price_scale 恒等映射，price 直接等于 extra_margin 增量。
        let (mut engine, mut ups, ssp) = setup_futures(0, 0, 1_000, 100);
        ups.get_mut(UID).unwrap().positions.insert(FUT_SYMBOL, isolated_position(1));

        let cmd = margin_adjustment_cmd(OrderAction::Bid, FUT_SYMBOL, 200, MarginMode::Isolated, 1);
        assert_eq!(engine.margin_adjustment(&cmd, &mut ups, &ssp), CommandResultCode::Success);

        let up = ups.get(UID).unwrap();
        assert_eq!(up.account(FUT_QUOTE), 1_000 - 200, "accounts 物理扣 200");
        assert_eq!(up.positions.get(&FUT_SYMBOL).unwrap().extra_margin, 200, "extraMargin 收到等额 200");
        // 守恒：accounts 减量 == extraMargin 增量（同一笔钱仓↔账户内部搬移，不touch adjustments）。
        assert_eq!(*engine.adjustments.get(&FUT_QUOTE).unwrap_or(&0), 0, "ISOLATED 不touch adjustments 桶");
    }

    #[test]
    fn margin_adjustment_isolated_nsf_rejects_and_leaves_state_unchanged() {
        let (mut engine, mut ups, ssp) = setup_futures(0, 0, 50, 100); // 余额 50 < 追加 200
        ups.get_mut(UID).unwrap().positions.insert(FUT_SYMBOL, isolated_position(1));

        let cmd = margin_adjustment_cmd(OrderAction::Bid, FUT_SYMBOL, 200, MarginMode::Isolated, 1);
        assert_eq!(engine.margin_adjustment(&cmd, &mut ups, &ssp), CommandResultCode::RiskNsf);

        let up = ups.get(UID).unwrap();
        assert_eq!(up.account(FUT_QUOTE), 50);
        assert_eq!(up.positions.get(&FUT_SYMBOL).unwrap().extra_margin, 0);
    }

    #[test]
    fn margin_adjustment_isolated_position_not_exists_returns_error() {
        let (mut engine, mut ups, ssp) = setup_futures(0, 0, 1_000, 100); // 未插入任何 position

        let cmd = margin_adjustment_cmd(OrderAction::Bid, FUT_SYMBOL, 200, MarginMode::Isolated, 1);
        assert_eq!(engine.margin_adjustment(&cmd, &mut ups, &ssp), CommandResultCode::RiskMarginPositionNotExists);
    }

    #[test]
    fn margin_adjustment_invalid_amount_when_price_non_positive() {
        let (mut engine, mut ups, ssp) = setup_futures(0, 0, 1_000, 100);
        ups.get_mut(UID).unwrap().positions.insert(FUT_SYMBOL, isolated_position(1));

        let zero_cmd = margin_adjustment_cmd(OrderAction::Bid, FUT_SYMBOL, 0, MarginMode::Isolated, 1);
        assert_eq!(engine.margin_adjustment(&zero_cmd, &mut ups, &ssp), CommandResultCode::RiskInvalidAmount);

        let negative_cmd = margin_adjustment_cmd(OrderAction::Bid, FUT_SYMBOL, -1, MarginMode::Isolated, 2);
        assert_eq!(engine.margin_adjustment(&negative_cmd, &mut ups, &ssp), CommandResultCode::RiskInvalidAmount);
    }

    #[test]
    fn margin_adjustment_margin_trading_disabled_rejects() {
        let (mut engine, mut ups, ssp) = setup_futures(0, 0, 1_000, 100);
        ups.get_mut(UID).unwrap().positions.insert(FUT_SYMBOL, isolated_position(1));
        engine.cfg_margin_trading_enabled = false;

        let cmd = margin_adjustment_cmd(OrderAction::Bid, FUT_SYMBOL, 200, MarginMode::Isolated, 1);
        assert_eq!(engine.margin_adjustment(&cmd, &mut ups, &ssp), CommandResultCode::RiskMarginTradingDisabled);
    }

    #[test]
    fn margin_adjustment_duplicate_order_id_is_already_applied_same_noop() {
        let (mut engine, mut ups, ssp) = setup_futures(0, 0, 1_000, 100);
        ups.get_mut(UID).unwrap().positions.insert(FUT_SYMBOL, isolated_position(1));

        let cmd = margin_adjustment_cmd(OrderAction::Bid, FUT_SYMBOL, 200, MarginMode::Isolated, 1);
        assert_eq!(engine.margin_adjustment(&cmd, &mut ups, &ssp), CommandResultCode::Success);
        // 同 order_id 重放：不得二次扣款/二次加 extraMargin。
        assert_eq!(
            engine.margin_adjustment(&cmd, &mut ups, &ssp),
            CommandResultCode::UserMgmtAccountBalanceAdjustmentAlreadyAppliedSame
        );

        let up = ups.get(UID).unwrap();
        assert_eq!(up.account(FUT_QUOTE), 1_000 - 200);
        assert_eq!(up.positions.get(&FUT_SYMBOL).unwrap().extra_margin, 200);
    }

    #[test]
    fn margin_adjustment_margin_mode_mismatch_when_position_is_cross_but_cmd_says_isolated() {
        let (mut engine, mut ups, ssp) = setup_futures(0, 0, 1_000, 100);
        // ONEWAY 模式键恒为 symbol，与 marginMode 无关——position 是 CROSS，cmd 传 ISOLATED。
        ups.get_mut(UID)
            .unwrap()
            .positions
            .insert(FUT_SYMBOL, SymbolPositionRecord::new(UID, FUT_SYMBOL, FUT_QUOTE, MarginMode::Cross, 1));

        let cmd = margin_adjustment_cmd(OrderAction::Bid, FUT_SYMBOL, 200, MarginMode::Isolated, 1);
        assert_eq!(engine.margin_adjustment(&cmd, &mut ups, &ssp), CommandResultCode::RiskMarginModeMismatch);
    }

    #[test]
    fn margin_adjustment_cross_credits_account_directly_and_touches_adjustments_bucket() {
        // CROSS：cmd.symbol 即 currency，无需已有 position，直接转发 balance_adjustment（accounts+=price、adjustments-=price 对冲）。
        let (mut engine, mut ups, ssp) = setup_futures(0, 0, 1_000, 100);

        let cmd = margin_adjustment_cmd(OrderAction::Bid, FUT_QUOTE, 300, MarginMode::Cross, 1);
        assert_eq!(engine.margin_adjustment(&cmd, &mut ups, &ssp), CommandResultCode::Success);

        assert_eq!(ups.get(UID).unwrap().account(FUT_QUOTE), 1_000 + 300);
        assert_eq!(*engine.adjustments.get(&FUT_QUOTE).unwrap(), -300);
        assert_eq!(ups.get(UID).unwrap().account(FUT_QUOTE) + engine.adjustments.get(&FUT_QUOTE).unwrap(), 1_000);
    }

    #[test]
    fn margin_adjustment_unknown_user_is_auth_invalid_user() {
        let mut ups = UserProfileService::new();
        let mut engine = RiskEngine::new();
        let ssp = SymbolSpecificationProvider::new();

        let cmd = margin_adjustment_cmd(OrderAction::Bid, FUT_SYMBOL, 200, MarginMode::Isolated, 1);
        assert_eq!(engine.margin_adjustment(&cmd, &mut ups, &ssp), CommandResultCode::AuthInvalidUser);
    }

    #[test]
    fn margin_adjustment_routes_through_pre_process_command() {
        // 走真实 R1 入口（`is_non_trading()` 门守 + 主 switch）而非直调方法。
        let (mut engine, mut ups, ssp) = setup_futures(0, 0, 1_000, 100);
        ups.get_mut(UID).unwrap().positions.insert(FUT_SYMBOL, isolated_position(1));

        let mut cmd = margin_adjustment_cmd(OrderAction::Bid, FUT_SYMBOL, 200, MarginMode::Isolated, 1);
        engine.pre_process_command(&mut cmd, &mut ups, &ssp);

        assert_eq!(cmd.result_code, Some(CommandResultCode::Success));
        assert_eq!(ups.get(UID).unwrap().positions.get(&FUT_SYMBOL).unwrap().extra_margin, 200);
    }

    // ---- mark-price 更新命令：last_price_cache 写入 + R1 消费方可见 ----

    fn markprice_adjustment_cmd(symbol: i32, price: i64) -> OrderCommand {
        OrderCommand { command: OrderCommandType::MarkpriceAdjustment, symbol, price, ..Default::default() }
    }

    #[test]
    fn markprice_adjustment_sets_last_price_cache() {
        let (mut engine, mut ups, ssp) = setup_futures(0, 0, 0, 100); // 治具已设 mark=100
        let cmd = markprice_adjustment_cmd(FUT_SYMBOL, 250);
        assert_eq!(engine.markprice_adjustment(&cmd, &mut ups, &ssp), CommandResultCode::Success);
        assert_eq!(engine.mark_price(FUT_SYMBOL), Some(250));
    }

    #[test]
    fn markprice_adjustment_unknown_symbol_is_invalid_symbol_and_does_not_write_cache() {
        let (mut engine, mut ups, ssp) = setup_futures(0, 0, 0, 100);
        let cmd = markprice_adjustment_cmd(9999, 250);
        assert_eq!(engine.markprice_adjustment(&cmd, &mut ups, &ssp), CommandResultCode::InvalidSymbol);
        assert_eq!(engine.mark_price(9999), None);
    }

    #[test]
    fn markprice_adjustment_then_place_order_risk_check_sees_new_mark_price() {
        // 前置：mark price 缺失时期货下单须 RiskMarkpriceNotAvailable；MARKPRICE_ADJUSTMENT 设价后 place_order 才能看到并算出保证金。
        let mut ssp = SymbolSpecificationProvider::new();
        assert_eq!(ssp.add_symbol(futures_spec(0, 0)), CommandResultCode::Success);
        ssp.add_currency(CoreCurrencySpecification { currency: FUT_QUOTE, currency_scale_k: 1, ..Default::default() });
        ssp.add_currency(CoreCurrencySpecification { currency: FUT_BASE, currency_scale_k: 1, ..Default::default() });
        let mut ups = UserProfileService::new();
        assert_eq!(ups.add_empty_user_profile(UID), CommandResultCode::Success);
        ups.get_mut(UID).unwrap().add_to_account(FUT_QUOTE, 10_000);
        let mut engine = RiskEngine::new();

        let mut place_cmd = futures_place_cmd(OrderAction::Bid, 10, 100, 1, MarginMode::Isolated, false);
        assert_eq!(
            engine.place_order_risk_check(&mut place_cmd, &mut ups, &ssp),
            CommandResultCode::RiskMarkpriceNotAvailable,
            "mark price 未设时期货下单必须被拒"
        );

        let mut mark_cmd = markprice_adjustment_cmd(FUT_SYMBOL, 100);
        engine.pre_process_command(&mut mark_cmd, &mut ups, &ssp);
        assert_eq!(mark_cmd.result_code, Some(CommandResultCode::Success));
        assert_eq!(engine.mark_price(FUT_SYMBOL), Some(100));

        let mut place_cmd2 = futures_place_cmd(OrderAction::Bid, 10, 100, 1, MarginMode::Isolated, false);
        assert_eq!(
            engine.place_order_risk_check(&mut place_cmd2, &mut ups, &ssp),
            CommandResultCode::ValidForMatchingEngine,
            "MARKPRICE_ADJUSTMENT 落地后 R1 应能看到新 mark price 并放行"
        );
    }

    #[test]
    fn set_mark_price_test_hook_writes_cache_without_symbol_validation() {
        // `ExchangeApi::set_mark_price` 内部调用的直接 setter：跳过 symbol 注册校验。
        let mut engine = RiskEngine::new();
        engine.set_mark_price(424_242, 777);
        assert_eq!(engine.mark_price(424_242), Some(777));
    }

    // crash-safety 回归：markprice_adjustment 拒绝 price<=0，保住"有头寸⇒标记价有效"不变量，避免下游三处 mark_price().unwrap() panic（proptest 覆盖不到的非法输入角）。
    #[test]
    fn markprice_adjustment_rejects_zero_price_and_prior_mark_survives_without_panic() {
        // 复刻终审给出的可达崩溃序列：开逐仓仓位(mark=100) → 设 mark=0 → free-futures-margin。
        let (mut engine, mut ups, ssp) = setup_futures(0, 0, 1_000, 100);
        ups.get_mut(UID).unwrap().positions.insert(FUT_SYMBOL, isolated_position(1));

        // 步骤2：mark=0 被拒（Java 会存 0；本移植收窄为 RiskInvalidAmount），缓存保持上一有效值。
        let zero = markprice_adjustment_cmd(FUT_SYMBOL, 0);
        assert_eq!(engine.markprice_adjustment(&zero, &mut ups, &ssp), CommandResultCode::RiskInvalidAmount);
        assert_eq!(engine.mark_price(FUT_SYMBOL), Some(100), "被拒的 0 标记价不得污染缓存");

        // 步骤3：free-futures-margin 走到 mark_price()——修复前会因 None panic，修复后仍 Some(100)；不 panic 本身即回归断言。
        let free = engine.calculate_free_futures_margin(ups.get(UID).unwrap(), FUT_QUOTE, &ssp);
        assert_eq!(free, 0, "空逐仓仓位(open_volume=0)净期货盈余为 0，且未 panic");
    }

    #[test]
    fn markprice_adjustment_rejects_negative_price_and_keeps_cache() {
        let (mut engine, mut ups, ssp) = setup_futures(0, 0, 1_000, 100);
        let neg = markprice_adjustment_cmd(FUT_SYMBOL, -5);
        assert_eq!(engine.markprice_adjustment(&neg, &mut ups, &ssp), CommandResultCode::RiskInvalidAmount);
        assert_eq!(engine.mark_price(FUT_SYMBOL), Some(100));
    }

    // ---- LEVERAGE_ADJUSTMENT：仅有 pending 挂单时才改变 required margin（杠杆变更不追溯已开仓部分） ----

    fn leverage_adjustment_cmd(symbol: i32, leverage: i32) -> OrderCommand {
        OrderCommand { command: OrderCommandType::LeverageAdjustment, uid: UID, symbol, leverage, ..Default::default() }
    }

    fn position_with_pending_buy(leverage: i32, pending_size: i64, pending_price: i64) -> SymbolPositionRecord {
        let mut pos = SymbolPositionRecord::new(UID, FUT_SYMBOL, FUT_QUOTE, MarginMode::Isolated, leverage);
        pos.pending_buy_size = pending_size;
        pos.pending_buy_avg_price = pending_price;
        pos
    }

    #[test]
    fn leverage_adjustment_no_position_is_noop_success() {
        let (mut engine, mut ups, ssp) = setup_futures(0, 0, 0, 100);
        let cmd = leverage_adjustment_cmd(FUT_SYMBOL, 5);
        assert_eq!(engine.leverage_adjustment(&cmd, &mut ups, &ssp), CommandResultCode::Success);
    }

    #[test]
    fn leverage_adjustment_increase_never_needs_nsf_and_updates_position() {
        // leverage 1 -> 2：required 从 1000(=1000/1) 降到 500(=1000/2)，new<=old，跳过 NSF，恒成功。
        let (mut engine, mut ups, ssp) = setup_futures(0, 0, 0, 100); // 余额 0，若走 NSF 必挂
        ups.get_mut(UID).unwrap().positions.insert(FUT_SYMBOL, position_with_pending_buy(1, 10, 100));

        let cmd = leverage_adjustment_cmd(FUT_SYMBOL, 2);
        assert_eq!(engine.leverage_adjustment(&cmd, &mut ups, &ssp), CommandResultCode::Success);
        assert_eq!(ups.get(UID).unwrap().positions.get(&FUT_SYMBOL).unwrap().leverage, 2);
    }

    #[test]
    fn leverage_adjustment_decrease_sufficient_balance_succeeds_and_updates() {
        // leverage 2->1：required 500升到1000，diff=500；balance=2000，free=1500 ≥ 500 → 通过。
        let (mut engine, mut ups, ssp) = setup_futures(0, 0, 2_000, 100);
        ups.get_mut(UID).unwrap().positions.insert(FUT_SYMBOL, position_with_pending_buy(2, 10, 100));

        let cmd = leverage_adjustment_cmd(FUT_SYMBOL, 1);
        assert_eq!(engine.leverage_adjustment(&cmd, &mut ups, &ssp), CommandResultCode::Success);
        assert_eq!(ups.get(UID).unwrap().positions.get(&FUT_SYMBOL).unwrap().leverage, 1);
    }

    #[test]
    fn leverage_adjustment_decrease_insufficient_balance_is_nsf_and_leaves_leverage_unchanged() {
        // 同上但 balance=600：free = 600-500=100 < diff 500 → NSF，杠杆保持原值 2。
        let (mut engine, mut ups, ssp) = setup_futures(0, 0, 600, 100);
        ups.get_mut(UID).unwrap().positions.insert(FUT_SYMBOL, position_with_pending_buy(2, 10, 100));

        let cmd = leverage_adjustment_cmd(FUT_SYMBOL, 1);
        assert_eq!(engine.leverage_adjustment(&cmd, &mut ups, &ssp), CommandResultCode::RiskNsf);
        assert_eq!(ups.get(UID).unwrap().positions.get(&FUT_SYMBOL).unwrap().leverage, 2);
    }

    #[test]
    fn leverage_adjustment_invalid_leverage_returns_error_and_leaves_leverage_unchanged() {
        // max_leverage 只配一档 {floor:0->1}，等同全局上限 1x（同前面用例治具手法）。
        let mut spec = futures_spec(0, 0);
        spec.max_leverage.insert(0, 1);
        let mut ssp = SymbolSpecificationProvider::new();
        assert_eq!(ssp.add_symbol(spec), CommandResultCode::Success);
        ssp.add_currency(CoreCurrencySpecification { currency: FUT_QUOTE, currency_scale_k: 1, ..Default::default() });
        ssp.add_currency(CoreCurrencySpecification { currency: FUT_BASE, currency_scale_k: 1, ..Default::default() });

        let mut ups = UserProfileService::new();
        assert_eq!(ups.add_empty_user_profile(UID), CommandResultCode::Success);
        ups.get_mut(UID).unwrap().add_to_account(FUT_QUOTE, 100_000);
        ups.get_mut(UID).unwrap().positions.insert(FUT_SYMBOL, position_with_pending_buy(1, 10, 100));

        let mut engine = RiskEngine::new();
        engine.last_price_cache.insert(FUT_SYMBOL, 100);

        let cmd = leverage_adjustment_cmd(FUT_SYMBOL, 5);
        assert_eq!(engine.leverage_adjustment(&cmd, &mut ups, &ssp), CommandResultCode::RiskInvalidLeverage);
        assert_eq!(ups.get(UID).unwrap().positions.get(&FUT_SYMBOL).unwrap().leverage, 1);
    }

    #[test]
    fn leverage_adjustment_zero_normalizes_to_one() {
        let (mut engine, mut ups, ssp) = setup_futures(0, 0, 0, 100);
        ups.get_mut(UID).unwrap().positions.insert(FUT_SYMBOL, position_with_pending_buy(2, 10, 100));

        let cmd = leverage_adjustment_cmd(FUT_SYMBOL, 0); // 归一到 1（比 2 更严格 -> 需要更多保证金）
        // required 500(lev2)升到1000(lev1)，diff=500>free(0)→NSF；验证 leverage==0 走归一分支而非除零 panic。
        assert_eq!(engine.leverage_adjustment(&cmd, &mut ups, &ssp), CommandResultCode::RiskNsf);
    }

    #[test]
    fn leverage_adjustment_margin_trading_disabled_rejects() {
        let (mut engine, mut ups, ssp) = setup_futures(0, 0, 0, 100);
        ups.get_mut(UID).unwrap().positions.insert(FUT_SYMBOL, position_with_pending_buy(1, 10, 100));
        engine.cfg_margin_trading_enabled = false;

        let cmd = leverage_adjustment_cmd(FUT_SYMBOL, 2);
        assert_eq!(engine.leverage_adjustment(&cmd, &mut ups, &ssp), CommandResultCode::RiskMarginTradingDisabled);
    }

    #[test]
    fn leverage_adjustment_unknown_user_is_auth_invalid_user() {
        let mut ups = UserProfileService::new();
        let mut engine = RiskEngine::new();
        let ssp = SymbolSpecificationProvider::new();
        let cmd = leverage_adjustment_cmd(FUT_SYMBOL, 2);
        assert_eq!(engine.leverage_adjustment(&cmd, &mut ups, &ssp), CommandResultCode::AuthInvalidUser);
    }

    #[test]
    fn leverage_adjustment_unknown_symbol_is_invalid_symbol() {
        let (mut engine, mut ups, ssp) = setup_futures(0, 0, 0, 100);
        let cmd = leverage_adjustment_cmd(999_999, 2);
        assert_eq!(engine.leverage_adjustment(&cmd, &mut ups, &ssp), CommandResultCode::InvalidSymbol);
    }

    #[test]
    fn leverage_adjustment_routes_through_pre_process_command() {
        let (mut engine, mut ups, ssp) = setup_futures(0, 0, 0, 100);
        ups.get_mut(UID).unwrap().positions.insert(FUT_SYMBOL, position_with_pending_buy(1, 10, 100));

        let mut cmd = leverage_adjustment_cmd(FUT_SYMBOL, 2);
        engine.pre_process_command(&mut cmd, &mut ups, &ssp);

        assert_eq!(cmd.result_code, Some(CommandResultCode::Success));
        assert_eq!(ups.get(UID).unwrap().positions.get(&FUT_SYMBOL).unwrap().leverage, 2);
    }

    // ==== apply_add_loan (ADD_LOAN 三段运行时配置) ====
    mod add_loan_tests {
        use super::*;
        use crate::core::common::batch_add_loan_command::{
            BatchAddLoanCommand, GlobalLoanConfig, RateCurveConfig, SymbolLoanConfig, UNSET, UNSET_AMOUNT,
        };
        use crate::core::processors::loan::loan_global_config::LoanGlobalConfig;

        fn add_loan_symbol_spec() -> CoreSymbolSpecification {
            CoreSymbolSpecification {
                symbol_id: SYMBOL,
                symbol_type: SymbolType::CurrencyExchangePair,
                base_currency: BASE,
                quote_currency: QUOTE,
                base_scale_k: 1,
                quote_scale_k: 1,
                ..Default::default()
            }
        }

        fn add_loan_setup() -> (RiskEngine, SymbolSpecificationProvider) {
            let engine = RiskEngine::new();
            let mut ssp = SymbolSpecificationProvider::new();
            ssp.add_symbol(add_loan_symbol_spec());
            ssp.add_currency(CoreCurrencySpecification { currency: BASE, currency_scale_k: 1, ..Default::default() });
            ssp.add_currency(CoreCurrencySpecification { currency: QUOTE, currency_scale_k: 1, ..Default::default() });
            (engine, ssp)
        }

        fn no_change_global() -> GlobalLoanConfig {
            GlobalLoanConfig {
                numeraire_currency: 0,
                cross_liquidation_ltv_bps: 0,
                cross_margin_call_ltv_bps: 0,
                loan_pool_utilization_cap_bps: 0,
                loan_liquidation_fee_bps: 0,
                ltv_liquidation_buffer_bps: 0,
                ltv_margin_call_buffer_bps: 0,
            }
        }

        fn unset_symbol_config(symbol_id: i32, initial: i32, weight: i32) -> SymbolLoanConfig {
            SymbolLoanConfig {
                symbol_id,
                loan_initial_ltv_bps: initial,
                loan_liquidation_ltv_bps: UNSET,
                loan_margin_call_ltv_bps: UNSET,
                loan_max_amount: UNSET_AMOUNT,
                loan_max_term_days: UNSET,
                collateral_weight_bps: weight,
            }
        }

        // ---- global 段 ----

        #[test]
        fn global_section_applies_partial_update_fields_and_skips_non_positive_ones() {
            let (mut engine, mut ssp) = add_loan_setup();
            let g = GlobalLoanConfig {
                numeraire_currency: QUOTE,
                cross_liquidation_ltv_bps: 9000,
                cross_margin_call_ltv_bps: 8500,
                loan_pool_utilization_cap_bps: 0,  // no-change
                loan_liquidation_fee_bps: 300,
                ltv_liquidation_buffer_bps: 0, // no-change
                ltv_margin_call_buffer_bps: 1500,
            };
            let cmd = BatchAddLoanCommand { global: Some(g), symbol: None, rate_curve: None };
            engine.apply_add_loan(&cmd, &mut ssp);

            let cfg = engine.loan_service.global_config;
            assert_eq!(cfg.numeraire_currency, QUOTE);
            assert_eq!(cfg.cross_liquidation_ltv_bps, 9000);
            assert_eq!(cfg.cross_margin_call_ltv_bps, 8500);
            assert_eq!(cfg.loan_pool_utilization_cap_bps, LoanGlobalConfig::default().loan_pool_utilization_cap_bps);
            assert_eq!(cfg.loan_liquidation_fee_bps, 300);
            assert_eq!(cfg.ltv_liquidation_buffer_bps, LoanGlobalConfig::default().ltv_liquidation_buffer_bps);
            assert_eq!(cfg.ltv_margin_call_buffer_bps, 1500);
        }

        #[test]
        fn global_section_rejects_invalid_thresholds_and_leaves_config_untouched() {
            let (mut engine, mut ssp) = add_loan_setup();
            let g = GlobalLoanConfig {
                cross_liquidation_ltv_bps: 8000,
                cross_margin_call_ltv_bps: 8000, // eff_margin_call == eff_liquidation -> invalid
                ..no_change_global()
            };
            let cmd = BatchAddLoanCommand { global: Some(g), symbol: None, rate_curve: None };
            engine.apply_add_loan(&cmd, &mut ssp);
            assert_eq!(engine.loan_service.global_config, LoanGlobalConfig::default());
        }

        #[test]
        fn global_section_rejects_when_numeraire_currency_spec_missing() {
            let (mut engine, mut ssp) = add_loan_setup();
            let g = GlobalLoanConfig { numeraire_currency: 999, ..no_change_global() };
            let cmd = BatchAddLoanCommand { global: Some(g), symbol: None, rate_curve: None };
            engine.apply_add_loan(&cmd, &mut ssp);
            assert_eq!(engine.loan_service.global_config, LoanGlobalConfig::default());
        }

        // ---- symbol 段 ----

        #[test]
        fn symbol_section_resolves_unset_fields_and_writes_collateral_weight_to_base_currency() {
            let (mut engine, mut ssp) = add_loan_setup(); // global buffers at default (2000/1000)
            let s = unset_symbol_config(SYMBOL, 6_000, 7_000);
            let cmd = BatchAddLoanCommand { global: None, symbol: Some(s), rate_curve: None };
            engine.apply_add_loan(&cmd, &mut ssp);

            let spec = ssp.symbols.get(&SYMBOL).unwrap();
            assert_eq!(spec.loan_config.initial_ltv_bps, 6_000);
            assert_eq!(spec.loan_config.liquidation_ltv_bps, 8_000); // 6000 + 2000 buffer
            assert_eq!(spec.loan_config.margin_call_ltv_bps, 7_000); // 8000 - 1000 buffer
            assert_eq!(spec.loan_config.max_amount, 0);
            assert_eq!(spec.loan_config.max_term_days, 0);
            // collateralWeightBps lands on the BASE currency, not the symbol/quote.
            assert_eq!(ssp.currencies.get(&BASE).unwrap().collateral_weight_bps, 7_000);
            assert_eq!(ssp.currencies.get(&QUOTE).unwrap().collateral_weight_bps, 0);
        }

        #[test]
        fn symbol_section_kill_switch_zeroes_only_initial_and_preserves_the_rest() {
            let (mut engine, mut ssp) = add_loan_setup();
            // Pre-existing config, as if a prior ADD_LOAN had opened the market.
            ssp.symbols.get_mut(&SYMBOL).unwrap().loan_config.update(6_000, 8_000, 7_000, 500_000, 30);

            let s = unset_symbol_config(SYMBOL, 0, UNSET); // initial==0 kill-switch
            let cmd = BatchAddLoanCommand { global: None, symbol: Some(s), rate_curve: None };
            engine.apply_add_loan(&cmd, &mut ssp);

            let spec = ssp.symbols.get(&SYMBOL).unwrap();
            assert_eq!(spec.loan_config.initial_ltv_bps, 0); // only this flips
            assert_eq!(spec.loan_config.liquidation_ltv_bps, 8_000); // preserved
            assert_eq!(spec.loan_config.margin_call_ltv_bps, 7_000); // preserved
            assert_eq!(spec.loan_config.max_amount, 500_000); // preserved
            assert_eq!(spec.loan_config.max_term_days, 30); // preserved
            // Kill-switch branch never touches collateralWeightBps.
            assert_eq!(ssp.currencies.get(&BASE).unwrap().collateral_weight_bps, 0);
        }

        #[test]
        fn symbol_section_rejects_collateral_weight_above_10000_and_applies_nothing() {
            // 前瞻性护栏测试：保证 collateral_weight_bps 恒在 [0,10000]，防 Task5 cross-LTV trunc_mul_div panic 不可达失效。
            let (mut engine, mut ssp) = add_loan_setup();
            let s = SymbolLoanConfig {
                symbol_id: SYMBOL,
                loan_initial_ltv_bps: 6_000,
                loan_liquidation_ltv_bps: 8_000,
                loan_margin_call_ltv_bps: 7_000,
                loan_max_amount: 0,
                loan_max_term_days: 0,
                collateral_weight_bps: 10_001, // out of [0,10000]
            };
            let cmd = BatchAddLoanCommand { global: None, symbol: Some(s), rate_curve: None };
            engine.apply_add_loan(&cmd, &mut ssp);

            let spec = ssp.symbols.get(&SYMBOL).unwrap();
            assert_eq!(spec.loan_config, Default::default()); // untouched
            assert_eq!(ssp.currencies.get(&BASE).unwrap().collateral_weight_bps, 0); // untouched
        }

        #[test]
        fn symbol_section_rejects_negative_collateral_weight_and_applies_nothing() {
            let (mut engine, mut ssp) = add_loan_setup();
            let s = SymbolLoanConfig {
                symbol_id: SYMBOL,
                loan_initial_ltv_bps: 6_000,
                loan_liquidation_ltv_bps: 8_000,
                loan_margin_call_ltv_bps: 7_000,
                loan_max_amount: 0,
                loan_max_term_days: 0,
                collateral_weight_bps: -2, // out of [0,10000] (not the UNSET==-1 sentinel)
            };
            let cmd = BatchAddLoanCommand { global: None, symbol: Some(s), rate_curve: None };
            engine.apply_add_loan(&cmd, &mut ssp);

            let spec = ssp.symbols.get(&SYMBOL).unwrap();
            assert_eq!(spec.loan_config, Default::default());
        }

        #[test]
        fn symbol_section_rejects_unregistered_or_non_spot_symbol() {
            let (mut engine, mut ssp) = add_loan_setup();
            let missing = unset_symbol_config(999_999, 6_000, 5_000);
            let cmd = BatchAddLoanCommand { global: None, symbol: Some(missing), rate_curve: None };
            engine.apply_add_loan(&cmd, &mut ssp);
            assert_eq!(ssp.symbols.get(&999_999), None); // no spec created
        }

        // ---- rate_curve 段 ----

        #[test]
        fn rate_curve_section_replaces_floating_curve_and_fixed_spread() {
            let (mut engine, mut ssp) = add_loan_setup();
            let rc = RateCurveConfig {
                base_bps: 300,
                kink_util_bps: 7_500,
                slope1_bps: 500,
                slope2_bps: 7_000,
                locked_rate_adjust_bps: -100,
            };
            let cmd = BatchAddLoanCommand { global: None, symbol: None, rate_curve: Some(rc) };
            engine.apply_add_loan(&cmd, &mut ssp);

            assert_eq!(engine.loan_service.floating_rate.base_bps, 300);
            assert_eq!(engine.loan_service.floating_rate.kink_util_bps, 7_500);
            assert_eq!(engine.loan_service.floating_rate.slope1_bps, 500);
            assert_eq!(engine.loan_service.floating_rate.slope2_bps, 7_000);
            assert_eq!(engine.loan_service.fixed_rate.locked_rate_adjust_bps, -100);
        }

        #[test]
        fn rate_curve_section_rejects_invalid_kink_and_leaves_curve_untouched() {
            let (mut engine, mut ssp) = add_loan_setup();
            let default_floating = engine.loan_service.floating_rate.clone();
            let rc = RateCurveConfig {
                base_bps: 300,
                kink_util_bps: 0, // must be strictly > 0
                slope1_bps: 500,
                slope2_bps: 7_000,
                locked_rate_adjust_bps: -100,
            };
            let cmd = BatchAddLoanCommand { global: None, symbol: None, rate_curve: Some(rc) };
            engine.apply_add_loan(&cmd, &mut ssp);

            assert_eq!(engine.loan_service.floating_rate, default_floating);
            assert_eq!(engine.loan_service.fixed_rate.locked_rate_adjust_bps, 0);
        }

        // ---- 三段独立性：一段非法只 warn-skip，不影响另外两段 ----

        #[test]
        fn one_invalid_section_does_not_prevent_the_other_two_from_applying() {
            let (mut engine, mut ssp) = add_loan_setup();
            let valid_global = GlobalLoanConfig {
                numeraire_currency: QUOTE,
                cross_liquidation_ltv_bps: 9_000,
                cross_margin_call_ltv_bps: 8_500,
                ..no_change_global()
            };
            let invalid_symbol = SymbolLoanConfig {
                symbol_id: SYMBOL,
                loan_initial_ltv_bps: 6_000,
                loan_liquidation_ltv_bps: 8_000,
                loan_margin_call_ltv_bps: 7_000,
                loan_max_amount: 0,
                loan_max_term_days: 0,
                collateral_weight_bps: 50_000, // invalid, must not poison the other two sections
            };
            let valid_rate_curve = RateCurveConfig {
                base_bps: 300,
                kink_util_bps: 7_500,
                slope1_bps: 500,
                slope2_bps: 7_000,
                locked_rate_adjust_bps: 25,
            };
            let cmd = BatchAddLoanCommand {
                global: Some(valid_global),
                symbol: Some(invalid_symbol),
                rate_curve: Some(valid_rate_curve),
            };
            engine.apply_add_loan(&cmd, &mut ssp);

            assert_eq!(engine.loan_service.global_config.numeraire_currency, QUOTE);
            assert_eq!(engine.loan_service.global_config.cross_liquidation_ltv_bps, 9_000);
            assert_eq!(engine.loan_service.floating_rate.base_bps, 300);
            assert_eq!(engine.loan_service.fixed_rate.locked_rate_adjust_bps, 25);
            // The invalid symbol section left the spec untouched.
            assert_eq!(ssp.symbols.get(&SYMBOL).unwrap().loan_config, Default::default());
        }
    }

    // ==== REPRICE_LOAN_RATES 全管线（R1 pre_process_command → R2 handler_risk_release），对应参考文档 §4.2 + RiskEngine.java:906-913 ====
    mod reprice_loan_rates_tests {
        use super::*;
        use crate::core::processors::loan::rate::floating_rate_model::FloatingRateModel;

        fn reprice_cmd(timestamp: i64) -> OrderCommand {
            OrderCommand { command: OrderCommandType::RepriceLoanRates, timestamp, ..Default::default() }
        }

        fn run_full_pipeline(engine: &mut RiskEngine, cmd: &mut OrderCommand) {
            let mut ups = UserProfileService::new();
            let ssp = SymbolSpecificationProvider::new();
            engine.pre_process_command(cmd, &mut ups, &ssp);
            assert_eq!(cmd.result_code, Some(CommandResultCode::Success));
            engine.handler_risk_release(cmd, &mut ups, &ssp);
        }

        #[test]
        fn single_currency_borrowed_and_available_reprices_to_curve_rate_for_computed_utilization() {
            let mut engine = RiskEngine::new();
            let cur = 5;
            engine.loan_service.loan_pool_borrowed.insert(cur, 8_000);
            engine.loan_service.loan_pool_available.insert(cur, 2_000); // util = 8000 bps (80%)
            let expected_rate = engine.loan_service.floating_rate.curve_rate_bps(8_000);

            let mut cmd = reprice_cmd(1_000);
            run_full_pipeline(&mut engine, &mut cmd);

            assert_eq!(
                engine.loan_service.floating_rate.current_rate_bps_or_base(cur),
                expected_rate as i32,
                "R2 必须把 util 过曲线写成新生效利率"
            );
            assert_eq!(engine.loan_service.floating_rate.last_reprice_ts, 1_000);
        }

        #[test]
        fn advance_accumulator_runs_before_reprice_settling_the_old_interval_at_the_old_rate() {
            // 模拟"距上次 reprice 已过一段时间"：advance_accumulator 须用旧利率结清旧区间，顺序颠倒会错按新利率结算。
            let mut engine = RiskEngine::new();
            let cur = 3;
            engine.loan_service.floating_rate.last_reprice_ts = 1_000;
            engine.loan_service.floating_rate.current_rate_bps.insert(cur, 300); // old rate 3%
            // util after reprice will be above kink -> new rate very different from 300.
            engine.loan_service.loan_pool_borrowed.insert(cur, 9_000);
            engine.loan_service.loan_pool_available.insert(cur, 1_000);

            let mut cmd = reprice_cmd(2_000); // 1000ms elapsed since last_reprice_ts
            run_full_pipeline(&mut engine, &mut cmd);

            let acc = *engine.loan_service.floating_rate.acc_rate_bps_ms.get(&cur).unwrap();
            assert_eq!(acc, 300 * 1_000, "旧区间必须按旧利率（300bps）结清");

            let new_rate = engine.loan_service.floating_rate.current_rate_bps_or_base(cur);
            assert_ne!(new_rate as i64, 300, "sanity: reprice 必须真正改变利率，测试才有意义");
        }

        #[test]
        fn multiple_currencies_all_reprice_correctly_regardless_of_processing_order() {
            let mut engine = RiskEngine::new();
            // 3 币种，构造成升序处理时互不影响（各自独立的池子/累加器）。
            for &(cur, borrowed, available) in &[(9, 100, 900), (2, 500, 500), (5, 9_000, 1_000)] {
                engine.loan_service.loan_pool_borrowed.insert(cur, borrowed);
                engine.loan_service.loan_pool_available.insert(cur, available);
            }
            let expected: Vec<(i32, i64)> = vec![
                (2, FloatingRateModel::utilization_bps(500, 500)),
                (5, FloatingRateModel::utilization_bps(9_000, 1_000)),
                (9, FloatingRateModel::utilization_bps(100, 900)),
            ];

            let mut cmd = reprice_cmd(4_000);
            run_full_pipeline(&mut engine, &mut cmd);

            for (cur, util) in expected {
                let expected_rate = engine.loan_service.floating_rate.curve_rate_bps(util);
                assert_eq!(
                    engine.loan_service.floating_rate.current_rate_bps_or_base(cur),
                    expected_rate as i32,
                    "currency {cur} 的生效利率必须按其自身 util 算出，不受其它币种/处理顺序影响"
                );
            }
            assert_eq!(engine.loan_service.floating_rate.last_reprice_ts, 4_000, "全部事件处理完后只设一次");
        }

        #[test]
        fn last_reprice_ts_is_set_exactly_once_after_the_loop_not_stale_from_before() {
            let mut engine = RiskEngine::new();
            engine.loan_service.floating_rate.last_reprice_ts = 1; // pre-existing stale value
            for &cur in &[1, 2, 3] {
                engine.loan_service.loan_pool_borrowed.insert(cur, 100);
                engine.loan_service.loan_pool_available.insert(cur, 100);
            }
            let mut cmd = reprice_cmd(9_999);
            run_full_pipeline(&mut engine, &mut cmd);
            assert_eq!(engine.loan_service.floating_rate.last_reprice_ts, 9_999);
        }

        #[test]
        fn empty_pool_does_not_advance_last_reprice_ts_mirroring_java_null_matcher_event_early_return() {
            // 借贷池全空→build_matcher_events 无 currency 可报→R2 完全不做任何事（含不推进 ts），对应 Java mte==null 早退（§4.2）。
            let mut engine = RiskEngine::new();
            engine.loan_service.floating_rate.last_reprice_ts = 42;

            let mut cmd = reprice_cmd(9_000);
            run_full_pipeline(&mut engine, &mut cmd);

            assert_eq!(engine.loan_service.floating_rate.last_reprice_ts, 42, "空事件早退：last_reprice_ts 保持不变");
            assert!(cmd.loan_reprice_events.is_empty());
        }

        #[test]
        fn cold_start_before_any_reprice_current_rate_falls_back_to_base_then_reprice_sets_real_rate() {
            let mut engine = RiskEngine::new();
            let cur = 1;
            assert_eq!(engine.loan_service.floating_rate.current_rate_bps_or_base(cur), engine.loan_service.floating_rate.base_bps);

            engine.loan_service.loan_pool_borrowed.insert(cur, 4_000);
            engine.loan_service.loan_pool_available.insert(cur, 4_000); // util = 5000 bps
            let mut cmd = reprice_cmd(500);
            run_full_pipeline(&mut engine, &mut cmd);

            let expected_rate = engine.loan_service.floating_rate.curve_rate_bps(5_000);
            assert_eq!(engine.loan_service.floating_rate.current_rate_bps_or_base(cur), expected_rate as i32);
            assert_ne!(expected_rate as i32, engine.loan_service.floating_rate.base_bps, "sanity: util=5000 必须偏离 base");
        }
    }

    // ==== INTERNAL_TRANSFER 全管线（R1 pre_process_command → R2 handler_risk_release），对应参考文档 §5 + InternalTransferProcessor.java ====
    mod internal_transfer_tests {
        use super::*;

        const TO_UID: i64 = 99;

        fn transfer_cmd(from_uid: i64, to_uid: i64, currency: i32, amount: i64, order_id: i64) -> OrderCommand {
            OrderCommand {
                command: OrderCommandType::InternalTransfer,
                uid: from_uid,
                size: to_uid, // overloaded: carries a uid, not an amount
                symbol: currency,
                price: amount,
                order_id,
                ..Default::default()
            }
        }

        fn run_full_pipeline(
            engine: &mut RiskEngine,
            cmd: &mut OrderCommand,
            ups: &mut UserProfileService,
            ssp: &SymbolSpecificationProvider,
        ) {
            engine.pre_process_command(cmd, ups, ssp);
            engine.handler_risk_release(cmd, ups, ssp);
        }

        #[test]
        fn successful_transfer_debits_from_credits_to_and_conserves_total() {
            let (mut engine, mut ups, ssp) = setup_futures(0, 0, 1_000, 100); // UID balance 1000 FUT_QUOTE
            let mut cmd = transfer_cmd(UID, TO_UID, FUT_QUOTE, 300, 1);

            run_full_pipeline(&mut engine, &mut cmd, &mut ups, &ssp);

            assert_eq!(cmd.result_code, Some(CommandResultCode::Success));
            assert_eq!(ups.get(UID).unwrap().account(FUT_QUOTE), 700);
            assert_eq!(ups.get(TO_UID).unwrap().account(FUT_QUOTE), 300);
            assert_eq!(
                ups.get(UID).unwrap().account(FUT_QUOTE) + ups.get(TO_UID).unwrap().account(FUT_QUOTE),
                1_000,
                "conservation: from -= amount, to += amount"
            );
        }

        #[test]
        fn transfer_to_never_seen_uid_auto_creates_suspended_profile() {
            let (mut engine, mut ups, ssp) = setup_futures(0, 0, 1_000, 100);
            assert!(ups.get(TO_UID).is_none(), "sanity: to_uid must not pre-exist");
            let mut cmd = transfer_cmd(UID, TO_UID, FUT_QUOTE, 300, 1);

            run_full_pipeline(&mut engine, &mut cmd, &mut ups, &ssp);

            let to = ups.get(TO_UID).expect("R2 must auto-create the target profile");
            assert_eq!(to.user_status, crate::core::common::user_status::UserStatus::Suspended);
            assert_eq!(to.account(FUT_QUOTE), 300);
        }

        #[test]
        fn self_transfer_rejected_before_any_balance_change() {
            let (mut engine, mut ups, ssp) = setup_futures(0, 0, 1_000, 100);
            let mut cmd = transfer_cmd(UID, UID, FUT_QUOTE, 300, 1);

            run_full_pipeline(&mut engine, &mut cmd, &mut ups, &ssp);

            assert_eq!(cmd.result_code, Some(CommandResultCode::InternalTransferInvalidSelf));
            assert_eq!(ups.get(UID).unwrap().account(FUT_QUOTE), 1_000, "self-transfer must not touch the balance");
        }

        #[test]
        fn non_positive_amount_rejected() {
            let (mut engine, mut ups, ssp) = setup_futures(0, 0, 1_000, 100);
            let mut cmd = transfer_cmd(UID, TO_UID, FUT_QUOTE, 0, 1);

            run_full_pipeline(&mut engine, &mut cmd, &mut ups, &ssp);

            assert_eq!(cmd.result_code, Some(CommandResultCode::RiskInvalidAmount));
            assert!(ups.get(TO_UID).is_none(), "rejected transfer must not auto-create the target");
        }

        #[test]
        fn negative_amount_rejected() {
            let (mut engine, mut ups, ssp) = setup_futures(0, 0, 1_000, 100);
            let mut cmd = transfer_cmd(UID, TO_UID, FUT_QUOTE, -5, 1);

            run_full_pipeline(&mut engine, &mut cmd, &mut ups, &ssp);

            assert_eq!(cmd.result_code, Some(CommandResultCode::RiskInvalidAmount));
        }

        #[test]
        fn missing_from_profile_rejected_with_auth_invalid_user() {
            let mut ssp = SymbolSpecificationProvider::new();
            ssp.add_currency(CoreCurrencySpecification { currency: FUT_QUOTE, currency_scale_k: 1, ..Default::default() });
            let mut ups = UserProfileService::new(); // UID never registered
            let mut engine = RiskEngine::new();
            let mut cmd = transfer_cmd(UID, TO_UID, FUT_QUOTE, 300, 1);

            run_full_pipeline(&mut engine, &mut cmd, &mut ups, &ssp);

            assert_eq!(cmd.result_code, Some(CommandResultCode::AuthInvalidUser));
            assert!(ups.get(TO_UID).is_none());
        }

        #[test]
        fn plain_insufficient_balance_rejected_with_nsf() {
            let (mut engine, mut ups, ssp) = setup_futures(0, 0, 100, 100); // only 100 available
            let mut cmd = transfer_cmd(UID, TO_UID, FUT_QUOTE, 300, 1);

            run_full_pipeline(&mut engine, &mut cmd, &mut ups, &ssp);

            assert_eq!(cmd.result_code, Some(CommandResultCode::RiskNsf));
            assert_eq!(ups.get(UID).unwrap().account(FUT_QUOTE), 100, "NSF must not debit");
            assert!(ups.get(TO_UID).is_none());
        }

        #[test]
        fn loan_collateral_lock_makes_an_otherwise_sufficient_balance_nsf() {
            // accounts=300 但同额被 isolated loan 抵押锁住 → withdrawable_balance=0<300，证明 NSF 复用与提现相同的锁。
            let (mut engine, mut ups, ssp) = setup_futures(0, 0, 300, 100);
            ups.get_mut(UID).unwrap().isolated_loans.insert(1, isolated_loan_with_collateral(1, FUT_QUOTE, 300));
            let mut cmd = transfer_cmd(UID, TO_UID, FUT_QUOTE, 300, 1);

            run_full_pipeline(&mut engine, &mut cmd, &mut ups, &ssp);

            assert_eq!(cmd.result_code, Some(CommandResultCode::RiskNsf));
            assert_eq!(ups.get(UID).unwrap().account(FUT_QUOTE), 300, "NSF must not debit");
        }

        #[test]
        fn same_order_id_twice_is_claim_and_keep_not_double_debited() {
            let (mut engine, mut ups, ssp) = setup_futures(0, 0, 1_000, 100);
            let mut cmd1 = transfer_cmd(UID, TO_UID, FUT_QUOTE, 100, 42);
            run_full_pipeline(&mut engine, &mut cmd1, &mut ups, &ssp);
            assert_eq!(cmd1.result_code, Some(CommandResultCode::Success));
            assert_eq!(ups.get(UID).unwrap().account(FUT_QUOTE), 900);

            // 同 order_id 重放，余额(900)本还够再转100，隔离出 claim 检查（而非 NSF）才是真正拦截点。
            let mut cmd2 = transfer_cmd(UID, TO_UID, FUT_QUOTE, 100, 42);
            run_full_pipeline(&mut engine, &mut cmd2, &mut ups, &ssp);

            assert_eq!(
                cmd2.result_code,
                Some(CommandResultCode::UserMgmtAccountBalanceAdjustmentAlreadyAppliedSame)
            );
            assert_eq!(ups.get(UID).unwrap().account(FUT_QUOTE), 900, "must not be double-debited");
            assert_eq!(ups.get(TO_UID).unwrap().account(FUT_QUOTE), 100, "must not be double-credited");
        }
    }

    // ==== SETTLE_FUNDINGFEES 全管线（R1→R2，参考文档 §4 + FundingFeeCommandProcessor.java）；处理器函数级测试见其自己的 #[cfg(test)]，这里只测经 RiskEngine 两段管线的接线本身 ====
    mod funding_fee_tests {
        use super::*;
        use crate::core::common::position_direction::PositionDirection;

        const PAYER_UID: i64 = 1;
        const RECEIVER_UID: i64 = 2;

        fn funding_cmd(action: OrderAction, rate: i64, rate_scale_k: i64) -> OrderCommand {
            OrderCommand {
                command: OrderCommandType::SettleFundingfees,
                symbol: FUT_SYMBOL,
                action: Some(action),
                price: rate,
                size: rate_scale_k,
                order_id: 1,
                ..Default::default()
            }
        }

        fn run_full_pipeline(
            engine: &mut RiskEngine,
            cmd: &mut OrderCommand,
            ups: &mut UserProfileService,
            ssp: &SymbolSpecificationProvider,
        ) {
            engine.pre_process_command(cmd, ups, ssp);
            engine.handler_risk_release(cmd, ups, ssp);
        }

        /// setup_futures 治具全 1 恒等缩放，便于手推核对；PAYER_UID 开多头，RECEIVER_UID 开空头。
        fn setup_with_payer_and_receiver(
            mark_price: i64,
            payer_volume: i64,
            receiver_volume: i64,
        ) -> (RiskEngine, UserProfileService, SymbolSpecificationProvider) {
            let (engine, mut ups, ssp) = setup_futures(0, 0, 0, mark_price);
            // setup_futures 预注册的是 UID(=7)，非本组 PAYER_UID/RECEIVER_UID，故清空后显式重新注册两者。
            ups.users.clear();
            assert_eq!(ups.add_empty_user_profile(PAYER_UID), CommandResultCode::Success);
            assert_eq!(ups.add_empty_user_profile(RECEIVER_UID), CommandResultCode::Success);
            ups.get_mut(PAYER_UID).unwrap().positions.insert(
                FUT_SYMBOL,
                SymbolPositionRecord {
                    direction: PositionDirection::Long,
                    open_volume: payer_volume,
                    ..SymbolPositionRecord::new(PAYER_UID, FUT_SYMBOL, FUT_QUOTE, MarginMode::Isolated, 1)
                },
            );
            ups.get_mut(RECEIVER_UID).unwrap().positions.insert(
                FUT_SYMBOL,
                SymbolPositionRecord {
                    direction: PositionDirection::Short,
                    open_volume: receiver_volume,
                    ..SymbolPositionRecord::new(RECEIVER_UID, FUT_SYMBOL, FUT_QUOTE, MarginMode::Isolated, 1)
                },
            );
            (engine, ups, ssp)
        }

        fn total_conserved(ups: &UserProfileService) -> i64 {
            ups.users
                .values()
                .map(|u| {
                    let accounts_sum: i64 = u.accounts.values().sum();
                    let profit_sum: i64 = u.positions.values().map(|p| p.profit).sum();
                    accounts_sum + profit_sum
                })
                .sum()
        }

        // ---- R1 gates ----

        #[test]
        fn invalid_symbol_rejected() {
            let (mut engine, mut ups, ssp) = setup_with_payer_and_receiver(100, 100, 100);
            let mut cmd = funding_cmd(OrderAction::Bid, 5, 1000);
            cmd.symbol = 99_999; // never registered
            run_full_pipeline(&mut engine, &mut cmd, &mut ups, &ssp);
            assert_eq!(cmd.result_code, Some(CommandResultCode::InvalidSymbol));
        }

        #[test]
        fn missing_mark_price_rejected_before_size_gate() {
            // mark price 缺失且 size<=0 同时成立：Java 嵌套校验顺序下 RiskMarkpriceNotAvailable 胜出（外层门先于 collectInput 的 size 校验）。
            let mut ssp = SymbolSpecificationProvider::new();
            assert_eq!(ssp.add_symbol(futures_spec(0, 0)), CommandResultCode::Success);
            ssp.add_currency(CoreCurrencySpecification { currency: FUT_QUOTE, currency_scale_k: 1, ..Default::default() });
            ssp.add_currency(CoreCurrencySpecification { currency: FUT_BASE, currency_scale_k: 1, ..Default::default() });
            let mut ups = UserProfileService::new();
            let mut engine = RiskEngine::new(); // last_price_cache empty
            let mut cmd = funding_cmd(OrderAction::Bid, 5, 0); // size<=0 too
            run_full_pipeline(&mut engine, &mut cmd, &mut ups, &ssp);
            assert_eq!(cmd.result_code, Some(CommandResultCode::RiskMarkpriceNotAvailable));
        }

        #[test]
        fn non_positive_rate_scale_k_rejected_with_invalid_amount() {
            let (mut engine, mut ups, ssp) = setup_with_payer_and_receiver(100, 100, 100);
            let mut cmd = funding_cmd(OrderAction::Bid, 5, 0);
            run_full_pipeline(&mut engine, &mut cmd, &mut ups, &ssp);
            assert_eq!(cmd.result_code, Some(CommandResultCode::RiskInvalidAmount));
        }

        // ---- full pipeline ----

        #[test]
        fn full_pipeline_settles_zero_sum_and_conserves_total() {
            // payer LONG 100@mark=10→notional=1000，rate=5/1000→fee=5；receiver SHORT 100（唯一接收方，收全部 5）。
            let (mut engine, mut ups, ssp) = setup_with_payer_and_receiver(10, 100, 100);
            let before = total_conserved(&ups);
            let mut cmd = funding_cmd(OrderAction::Bid, 5, 1000);

            run_full_pipeline(&mut engine, &mut cmd, &mut ups, &ssp);

            assert_eq!(cmd.result_code, Some(CommandResultCode::Success));
            assert_eq!(ups.get(PAYER_UID).unwrap().positions.get(&FUT_SYMBOL).unwrap().profit, -5);
            assert_eq!(ups.get(RECEIVER_UID).unwrap().positions.get(&FUT_SYMBOL).unwrap().profit, 5);
            assert_eq!(total_conserved(&ups), before, "zero-sum: Σaccounts + Σposition.profit unchanged");
        }

        #[test]
        fn empty_receiver_pool_produces_no_event_and_no_state_change() {
            // Only a payer, no receiver -> total_recv_notional==0 -> no event, still Success.
            let (engine0, mut ups, ssp) = setup_with_payer_and_receiver(10, 100, 100);
            let mut engine = engine0;
            // Flatten the receiver's position so it contributes nothing to either pool.
            ups.get_mut(RECEIVER_UID).unwrap().positions.remove(&FUT_SYMBOL);
            let before = total_conserved(&ups);
            let mut cmd = funding_cmd(OrderAction::Bid, 5, 1000);

            run_full_pipeline(&mut engine, &mut cmd, &mut ups, &ssp);

            assert_eq!(cmd.result_code, Some(CommandResultCode::Success));
            assert_eq!(ups.get(PAYER_UID).unwrap().positions.get(&FUT_SYMBOL).unwrap().profit, 0, "no event -> no settlement");
            assert_eq!(total_conserved(&ups), before);
        }
    }

    /// `IF_TAKEOVER` 全流程（R1+merge+R2 apply+finalize）——参考文档 §2.2。
    mod if_takeover_tests {
        use super::*;
        use crate::core::common::position_direction::PositionDirection;
        use crate::core::processors::liquidation::liquidation_service::IfNotional;

        const TAKER_UID: i64 = 42;

        fn run_full_pipeline(
            engine: &mut RiskEngine,
            cmd: &mut OrderCommand,
            ups: &mut UserProfileService,
            ssp: &SymbolSpecificationProvider,
        ) {
            engine.pre_process_command(cmd, ups, ssp);
            engine.handler_risk_release(cmd, ups, ssp);
        }

        fn if_takeover_cmd(action: OrderAction, size: i64, price: i64) -> OrderCommand {
            OrderCommand {
                command: OrderCommandType::IfTakeover,
                symbol: FUT_SYMBOL,
                uid: TAKER_UID,
                action: Some(action),
                size,
                price,
                order_id: 1,
                ..Default::default()
            }
        }

        /// taker（即将被 IF 接管的破产用户）持一笔 LONG 仓位，open_volume/open_price_sum 由调用方指定成本基（制造非零 PnL）。
        fn setup_with_taker_long_position(
            mark_price: i64,
            open_volume: i64,
            open_price_sum: i64,
        ) -> (RiskEngine, UserProfileService, SymbolSpecificationProvider) {
            let (engine, mut ups, ssp) = setup_futures(0, 0, 0, mark_price);
            ups.users.clear();
            assert_eq!(ups.add_empty_user_profile(TAKER_UID), CommandResultCode::Success);
            ups.get_mut(TAKER_UID).unwrap().positions.insert(
                FUT_SYMBOL,
                SymbolPositionRecord {
                    direction: PositionDirection::Long,
                    open_volume,
                    open_price_sum,
                    ..SymbolPositionRecord::new(TAKER_UID, FUT_SYMBOL, FUT_QUOTE, MarginMode::Isolated, 1)
                },
            );
            (engine, ups, ssp)
        }

        #[test]
        fn full_cover_accepts_position_closes_taker_and_settles_pnl() {
            // taker LONG 100 @ 成本基 9000（均价 90）；IF 以 price=100 接管全部 100 手。
            let (mut engine, mut ups, ssp) = setup_with_taker_long_position(100, 100, 9_000);
            engine.liquidation_service.deposit_to_insurance_fund(FUT_SYMBOL, 100_000); // 足额覆盖
            let taker_account_before = ups.get(TAKER_UID).unwrap().account(FUT_QUOTE);

            // cmd.action == BID：对应 taker 持仓方向 LONG（IF 接管同向仓位，参考文档 §2.2 字段映射）。
            let mut cmd = if_takeover_cmd(OrderAction::Bid, 100, 100);
            run_full_pipeline(&mut engine, &mut cmd, &mut ups, &ssp);

            assert_eq!(cmd.result_code, Some(CommandResultCode::Success));

            // IF 侧：接管仓位入账，key = Long.multiplier()(1) * FUT_SYMBOL。
            let key = FUT_SYMBOL as i64;
            let if_pos = engine.liquidation_service.positions.get(&key).expect("IF position must be recorded");
            assert_eq!(if_pos.open_volume, 100);
            assert_eq!(if_pos.open_price_sum, 100 * 100, "spend = size*price = 10000");

            // IF available 精确扣掉 spend；reserved 经 finalize 释放归零（跟 R1 reserve 对称）。
            assert_eq!(
                engine.liquidation_service.notionals[&FUT_SYMBOL],
                IfNotional { available: 100_000 - 10_000, reserved: 0 },
                "finalize 必须释放 reserved（即使接管成功也要释放，跟 R1 对称）"
            );

            // taker 侧：全平仓，PnL = (100*100 - 9000)*1(LONG multiplier) = 1000，结算进账户后仓位被移除。
            assert!(!ups.get(TAKER_UID).unwrap().positions.contains_key(&FUT_SYMBOL), "全平仓后仓位记录必须被移除");
            assert_eq!(
                ups.get(TAKER_UID).unwrap().account(FUT_QUOTE),
                taker_account_before + 1_000,
                "已实现 PnL 必须精确结算进账户（currency scale 恒等换算，因 base/quote/currency_scale_k 全为 1）"
            );
        }

        #[test]
        fn undersize_rejects_all_or_nothing_but_still_releases_preview() {
            // IF 只有 500 可用，接管 100 手 @ price=100 需要 10000 —— 严重覆盖不足，必须全拒。
            let (mut engine, mut ups, ssp) = setup_with_taker_long_position(100, 100, 9_000);
            engine.liquidation_service.deposit_to_insurance_fund(FUT_SYMBOL, 500);

            let mut cmd = if_takeover_cmd(OrderAction::Bid, 100, 100);
            run_full_pipeline(&mut engine, &mut cmd, &mut ups, &ssp);

            assert_eq!(cmd.result_code, Some(CommandResultCode::Success), "REJECT 是 matcher-event 级别信号，命令级结果码仍是 Success（同 ADL/FundingFee 先例）");

            // 全拒：IF 没有接管任何仓位。
            assert!(engine.liquidation_service.positions.is_empty(), "全拒不产生任何 IFPositionRecord");

            // finalize 仍然必须释放 R1 预冻结的 reserved（跟成功路径对称，不留孤儿 reserved）。
            assert_eq!(
                engine.liquidation_service.notionals[&FUT_SYMBOL],
                IfNotional { available: 500, reserved: 0 },
                "available 分毫未动（从未 accept），reserved 必须归零（finalize 全拒路径仍释放 preview）"
            );

            // taker 侧：全拒时完全不动 taker 仓位（对应 Java `matcherEvent.eventType != REJECT` 门）。
            let taker_spr = ups.get(TAKER_UID).unwrap().positions.get(&FUT_SYMBOL).expect("REJECT 不应关闭 taker 仓位");
            assert_eq!(taker_spr.open_volume, 100);
            assert_eq!(taker_spr.open_price_sum, 9_000);
        }

        #[test]
        fn if_deposit_and_withdraw_hedge_adjustments_and_conserve() {
            let (mut engine, mut ups, ssp) = setup_futures(0, 0, 0, 100);

            let mut deposit_cmd = OrderCommand {
                command: OrderCommandType::IfDeposit,
                symbol: FUT_SYMBOL,
                price: 700, // currency_amount
                order_id: 1,
                ..Default::default()
            };
            engine.pre_process_command(&mut deposit_cmd, &mut ups, &ssp);
            assert_eq!(deposit_cmd.result_code, Some(CommandResultCode::Success));
            assert_eq!(engine.liquidation_service.notionals[&FUT_SYMBOL].available, 700);
            assert_eq!(*engine.adjustments.get(&FUT_QUOTE).unwrap(), -700, "对冲桶反向记账");

            let mut withdraw_cmd = OrderCommand {
                command: OrderCommandType::IfWithdraw,
                symbol: FUT_SYMBOL,
                price: 300,
                order_id: 2,
                ..Default::default()
            };
            engine.pre_process_command(&mut withdraw_cmd, &mut ups, &ssp);
            assert_eq!(withdraw_cmd.result_code, Some(CommandResultCode::Success));
            assert_eq!(engine.liquidation_service.notionals[&FUT_SYMBOL].available, 400);
            assert_eq!(*engine.adjustments.get(&FUT_QUOTE).unwrap(), -400, "withdraw 反向抵消一部分 deposit 的对冲");

            // Σ IFNotional.available + adjustments[currency] 恒定（对冲闭环，同 balance_adjustment/loan pool 先例）。
            assert_eq!(
                engine.liquidation_service.notionals[&FUT_SYMBOL].available + engine.adjustments[&FUT_QUOTE],
                0,
                "IF 充提必须与 adjustments 桶精确对冲闭环"
            );
        }

        #[test]
        fn if_withdraw_over_available_is_rejected_and_leaves_state_unchanged() {
            let (mut engine, mut ups, ssp) = setup_futures(0, 0, 0, 100);
            let mut deposit_cmd = OrderCommand {
                command: OrderCommandType::IfDeposit,
                symbol: FUT_SYMBOL,
                price: 100,
                order_id: 1,
                ..Default::default()
            };
            engine.pre_process_command(&mut deposit_cmd, &mut ups, &ssp);

            let mut withdraw_cmd = OrderCommand {
                command: OrderCommandType::IfWithdraw,
                symbol: FUT_SYMBOL,
                price: 101, // 超过 available=100
                order_id: 2,
                ..Default::default()
            };
            engine.pre_process_command(&mut withdraw_cmd, &mut ups, &ssp);

            assert_eq!(withdraw_cmd.result_code, Some(CommandResultCode::RiskIfInsufficient));
            assert_eq!(engine.liquidation_service.notionals[&FUT_SYMBOL].available, 100, "拒绝的提取不改状态");
            assert_eq!(*engine.adjustments.get(&FUT_QUOTE).unwrap(), -100, "拒绝的提取不触碰 adjustments");
        }

        #[test]
        fn if_deposit_unknown_symbol_is_invalid_symbol() {
            let (mut engine, mut ups, ssp) = setup_futures(0, 0, 0, 100);
            let mut cmd = OrderCommand {
                command: OrderCommandType::IfDeposit,
                symbol: 99_999,
                price: 100,
                order_id: 1,
                ..Default::default()
            };
            engine.pre_process_command(&mut cmd, &mut ups, &ssp);
            assert_eq!(cmd.result_code, Some(CommandResultCode::InvalidSymbol));
        }

        #[test]
        fn if_deposit_non_positive_amount_is_invalid_amount() {
            let (mut engine, mut ups, ssp) = setup_futures(0, 0, 0, 100);
            let mut cmd =
                OrderCommand { command: OrderCommandType::IfDeposit, symbol: FUT_SYMBOL, price: 0, order_id: 1, ..Default::default() };
            engine.pre_process_command(&mut cmd, &mut ups, &ssp);
            assert_eq!(cmd.result_code, Some(CommandResultCode::RiskInvalidAmount));
        }

        #[test]
        fn taker_position_lookup_goes_through_create_positions_key_not_raw_symbol() {
            // 对应 Java handlerRiskRelease:947-948：按 create_positions_key 查 taker 仓（非裸 symbol）；本测试显式断言 ONEWAY 下退化为裸 symbol 且 finalize 确实经此 key 找到并关闭仓位。
            let (mut engine, mut ups, ssp) = setup_with_taker_long_position(100, 100, 9_000);
            engine.liquidation_service.deposit_to_insurance_fund(FUT_SYMBOL, 100_000);

            let position_key = ups.get(TAKER_UID).unwrap().create_positions_key(FUT_SYMBOL, OrderAction::Bid, OrderCommandType::IfTakeover);
            assert_eq!(position_key, FUT_SYMBOL, "ONEWAY: create_positions_key 退化为裸 symbol");

            let mut cmd = if_takeover_cmd(OrderAction::Bid, 100, 100);
            run_full_pipeline(&mut engine, &mut cmd, &mut ups, &ssp);

            assert_eq!(cmd.result_code, Some(CommandResultCode::Success));
            // 仓位在 position_key 这个 key 上被关闭（ONEWAY 下数值同裸 symbol，但查找路径须走 create_positions_key）。
            assert!(!ups.get(TAKER_UID).unwrap().positions.contains_key(&position_key));
        }
    }

    /// AUTO_DELEVERAGING 全流程（R1 选候选+预占→merge→R2 apply+finalize 对称释放，参考文档 §3/§11.1）；算法级测试见 adl_command_processor.rs/liquidation_service.rs 各自模块，这里只测 RiskEngine 两段管线接线本身。
    mod adl_tests {
        use super::*;
        use crate::core::common::position_direction::PositionDirection;

        const TAKER_UID: i64 = 42;
        const CP_A: i64 = 100; // 高分候选（高杠杆/高浮盈）
        const CP_B: i64 = 101; // 低分候选
        const CP_C: i64 = 102; // 预算耗尽后完全够不到的候选

        fn run_full_pipeline(
            engine: &mut RiskEngine,
            cmd: &mut OrderCommand,
            ups: &mut UserProfileService,
            ssp: &SymbolSpecificationProvider,
        ) {
            engine.pre_process_command(cmd, ups, ssp);
            engine.handler_risk_release(cmd, ups, ssp);
        }

        fn adl_cmd(action: OrderAction, size: i64, bankruptcy_price: i64) -> OrderCommand {
            OrderCommand {
                command: OrderCommandType::AutoDeleveraging,
                symbol: FUT_SYMBOL,
                uid: TAKER_UID,
                action: Some(action),
                size,
                price: bankruptcy_price,
                order_id: 1,
                ..Default::default()
            }
        }

        fn short_position(uid: i64, open_volume: i64, open_price_sum: i64, open_init_margin_sum: i64) -> SymbolPositionRecord {
            SymbolPositionRecord {
                direction: PositionDirection::Short,
                open_volume,
                open_price_sum,
                open_init_margin_sum,
                ..SymbolPositionRecord::new(uid, FUT_SYMBOL, FUT_QUOTE, MarginMode::Isolated, 1)
            }
        }

        /// taker LONG 100（成本基9000）+ 三个 ISOLATED SHORT 候选：CP_A(60手,score最高)>CP_B(80手)>CP_C(50手,预算耗尽摸不到)。
        fn setup_taker_and_three_candidates() -> (RiskEngine, UserProfileService, SymbolSpecificationProvider) {
            let (engine, mut ups, ssp) = setup_futures(0, 0, 0, 100);
            ups.users.clear();
            for uid in [TAKER_UID, CP_A, CP_B, CP_C] {
                assert_eq!(ups.add_empty_user_profile(uid), CommandResultCode::Success);
            }
            ups.get_mut(TAKER_UID).unwrap().positions.insert(
                FUT_SYMBOL,
                SymbolPositionRecord {
                    direction: PositionDirection::Long,
                    open_volume: 100,
                    open_price_sum: 9_000,
                    ..SymbolPositionRecord::new(TAKER_UID, FUT_SYMBOL, FUT_QUOTE, MarginMode::Isolated, 1)
                },
            );
            // A: volume=60, avg=133.33(8000/60), margin=800 -> leverage=10, uPnl=(8000-6000)=2000, score=10*2000*100=2,000,000
            ups.get_mut(CP_A).unwrap().positions.insert(FUT_SYMBOL, short_position(CP_A, 60, 8_000, 800));
            // B: volume=80, avg=130(10400/80), margin=2000 -> leverage=5, uPnl=(10400-8000)=2400, score=5*2400*100=1,200,000
            ups.get_mut(CP_B).unwrap().positions.insert(FUT_SYMBOL, short_position(CP_B, 80, 10_400, 2_000));
            // C: volume=50, avg=120(6000/50), margin=6000 -> leverage=1, uPnl=(6000-5000)=1000, score=1*1000*100=100,000（垫底）
            ups.get_mut(CP_C).unwrap().positions.insert(FUT_SYMBOL, short_position(CP_C, 50, 6_000, 6_000));
            (engine, ups, ssp)
        }

        // ---- risk_score 排序驱动的选取顺序 + 预算耗尽边界 ----

        #[test]
        fn r1_selects_by_risk_score_desc_and_stops_once_size_exhausted() {
            // remaining=100：A(60,得分最高)先取满60，剩40 -> B 只部分取 40（剩80里的一部分），C 完全摸不到。
            let (mut engine, mut ups, ssp) = setup_taker_and_three_candidates();
            let mut cmd = adl_cmd(OrderAction::Bid, 100, 100);

            engine.pre_process_command(&mut cmd, &mut ups, &ssp);

            assert_eq!(cmd.result_code, Some(CommandResultCode::Success));
            assert_eq!(cmd.adl_user_positions.len(), 2, "只有 A、B 被选中，C 预算耗尽前摸不到");
            assert_eq!(cmd.adl_user_positions[0].uid, CP_A, "得分最高的候选必须排第一个被选中");
            assert_eq!(cmd.adl_user_positions[0].volume, 60);
            assert_eq!(cmd.adl_user_positions[1].uid, CP_B);
            assert_eq!(cmd.adl_user_positions[1].volume, 40, "B 只能部分取：min(available=80, remaining=40)=40");

            // R1 写回：A/B 的 pending_adl_size 立即体现预占；C 完全未被触碰。
            assert_eq!(ups.get(CP_A).unwrap().positions[&FUT_SYMBOL].pending_adl_size, 60);
            assert_eq!(ups.get(CP_B).unwrap().positions[&FUT_SYMBOL].pending_adl_size, 40);
            assert_eq!(ups.get(CP_C).unwrap().positions[&FUT_SYMBOL].pending_adl_size, 0, "预算耗尽前摸不到的候选，pending_adl_size 必须保持 0");
        }

        #[test]
        fn merge_rewrites_cmd_size_to_actual_consumed_when_candidates_fall_short() {
            // remaining=200 请求，但 A+B+C 总可用只有 60+80+50=190 -> 实际消费=190，cmd.size 必须改写。
            let (mut engine, mut ups, ssp) = setup_taker_and_three_candidates();
            // R1 先经 normalize_cmd_position_size 把 cmd.size 夹到 taker open_volume；本用例把 taker 仓放大到 300，让 normalize 不夹取，隔离候选不足路径。
            ups.get_mut(TAKER_UID).unwrap().positions.get_mut(&FUT_SYMBOL).unwrap().open_volume = 300;
            ups.get_mut(TAKER_UID).unwrap().positions.get_mut(&FUT_SYMBOL).unwrap().open_price_sum = 27_000; // 300*90
            let mut cmd = adl_cmd(OrderAction::Bid, 200, 100);

            engine.pre_process_command(&mut cmd, &mut ups, &ssp);

            assert_eq!(cmd.result_code, Some(CommandResultCode::Success));
            assert_eq!(cmd.size, 190, "merge 必须把 cmd.size 改写为实际消费总量，不是原始请求量");
            assert_eq!(cmd.adl_user_positions.len(), 3, "预算够大，A/B/C 全部入选");
            let total_events: i64 = cmd.adl_events.iter().map(|(_, v)| v).sum();
            assert_eq!(total_events, 190, "events 里每条 exec_volume 之和必须等于改写后的 cmd.size");
        }

        // ---- R1 预占 / finalize 对称释放（含守恒）----

        #[test]
        fn finalize_releases_pending_adl_size_symmetrically_for_every_selected_candidate() {
            let (mut engine, mut ups, ssp) = setup_taker_and_three_candidates();
            let mut cmd = adl_cmd(OrderAction::Bid, 100, 100);

            run_full_pipeline(&mut engine, &mut cmd, &mut ups, &ssp);

            assert_eq!(cmd.result_code, Some(CommandResultCode::Success));
            // A 全平仓→仓位记录被移除，无从查 pending_adl_size 残留，等价确认无孤儿预占。
            assert!(!ups.get(CP_A).unwrap().positions.contains_key(&FUT_SYMBOL), "A 60 手全部被吃，仓位清空后移除");
            // B 部分平仓(40)，仓位还在，pending_adl_size 须归 0（对称释放，不管 apply 实际消费多少）。
            let b_pos = &ups.get(CP_B).unwrap().positions[&FUT_SYMBOL];
            assert_eq!(b_pos.pending_adl_size, 0, "finalize 必须把 B 的 pending_adl_size 释放回 0");
            assert_eq!(b_pos.open_volume, 40, "B 原 80 手，被吃 40，剩 40");
            // C 完全没被选中，本就没有预占过，finalize 走它是 no-op。
            assert_eq!(ups.get(CP_C).unwrap().positions[&FUT_SYMBOL].pending_adl_size, 0);
            assert_eq!(ups.get(CP_C).unwrap().positions[&FUT_SYMBOL].open_volume, 50, "C 完全未受影响");
        }

        #[test]
        fn full_pipeline_closes_taker_and_settles_realized_pnl_exactly() {
            // ADL taker 与 counterparty 非原始成交对手，局部 Σaccounts+Σprofit 不必然守恒（全局零和需整个交易所范围）；本测试改断言每账户精确期望值。
            let (mut engine, mut ups, ssp) = setup_taker_and_three_candidates();
            let mut cmd = adl_cmd(OrderAction::Bid, 100, 100);

            run_full_pipeline(&mut engine, &mut cmd, &mut ups, &ssp);

            assert_eq!(cmd.result_code, Some(CommandResultCode::Success));
            // taker LONG 100 手全部被 ADL 平掉 -> 仓位清空移除，PnL = (100*100-9000)*1 = 1000 结算入账户。
            assert!(!ups.get(TAKER_UID).unwrap().positions.contains_key(&FUT_SYMBOL));
            assert_eq!(ups.get(TAKER_UID).unwrap().account(FUT_QUOTE), 1_000);
            // CP_A 60 手全部被吃（全平）-> PnL = (8000 - 60*100)*-1(Short) = 2000，结算入账户，仓位移除。
            assert!(!ups.get(CP_A).unwrap().positions.contains_key(&FUT_SYMBOL));
            assert_eq!(ups.get(CP_A).unwrap().account(FUT_QUOTE), 2_000);
            // CP_B 只被吃 40（部分平仓）-> PnL 递延进成本基，不结算，账户不动，仓位记录还在。
            assert_eq!(ups.get(CP_B).unwrap().account(FUT_QUOTE), 0);
            assert_eq!(ups.get(CP_B).unwrap().positions[&FUT_SYMBOL].profit, 0, "部分平仓不实现盈亏");
            // CP_C 完全未被触碰。
            assert_eq!(ups.get(CP_C).unwrap().account(FUT_QUOTE), 0);
            assert_eq!(ups.get(CP_C).unwrap().positions[&FUT_SYMBOL].open_volume, 50);
        }

        // ---- counterparty 在 R1/R2 之间消失：best-effort skip，不是 error ----

        #[test]
        fn counterparty_profile_vanished_between_r1_and_r2_is_skipped_not_error() {
            let (mut engine, mut ups, ssp) = setup_taker_and_three_candidates();
            let mut cmd = adl_cmd(OrderAction::Bid, 100, 100);

            engine.pre_process_command(&mut cmd, &mut ups, &ssp); // R1：A、B 被选中并预占

            // 模拟 R1/R2 之间 B 的 UserProfile 整个消失（用户被清号等极端时序）。
            ups.users.remove(&CP_B);

            engine.handler_risk_release(&mut cmd, &mut ups, &ssp); // R2：不能 panic

            assert_eq!(cmd.result_code, Some(CommandResultCode::Success), "counterparty 消失不影响命令级结果码");
            // A（仍存在）正常被平仓结算。
            assert!(!ups.get(CP_A).unwrap().positions.contains_key(&FUT_SYMBOL));
            // taker 仍按 cmd.size 全部平仓，不受 B 消失影响（pendingADLSize 修正统一在 finalize 做，不依赖 apply 是否成功）。
            assert!(!ups.get(TAKER_UID).unwrap().positions.contains_key(&FUT_SYMBOL));
        }

        #[test]
        fn counterparty_position_vanished_between_r1_and_r2_is_skipped_not_error() {
            let (mut engine, mut ups, ssp) = setup_taker_and_three_candidates();
            let mut cmd = adl_cmd(OrderAction::Bid, 100, 100);

            engine.pre_process_command(&mut cmd, &mut ups, &ssp); // R1：A、B 被选中并预占

            // 模拟 R1/R2 之间 B 的仓位记录被其他路径关掉（profile 还在，仓位没了）。
            ups.get_mut(CP_B).unwrap().positions.remove(&FUT_SYMBOL);
            let b_account_before = ups.get(CP_B).unwrap().account(FUT_QUOTE);

            engine.handler_risk_release(&mut cmd, &mut ups, &ssp); // R2：不能 panic

            assert_eq!(cmd.result_code, Some(CommandResultCode::Success));
            assert_eq!(ups.get(CP_B).unwrap().account(FUT_QUOTE), b_account_before, "仓位已消失，apply 与 finalize 释放都必须 no-op，不能凭空造账");
            assert!(!ups.get(CP_A).unwrap().positions.contains_key(&FUT_SYMBOL), "A 不受 B 消失影响，正常结算");
        }

        // ---- HEDGE 模式：create_positions_key（不是裸 symbol）----

        #[test]
        fn hedge_mode_uses_create_positions_key_not_raw_symbol() {
            // 对应 Java up.createPositionsKey（ADLCommandProcessor.applyEvent/finalizeForCommand + handlerRiskRelease:947-948）：HEDGE 下 key=±symbol，非裸 symbol。
            let (mut engine, mut ups, ssp) = setup_taker_and_three_candidates();
            ups.get_mut(TAKER_UID).unwrap().position_mode = PositionMode::Hedge;
            ups.get_mut(CP_A).unwrap().position_mode = PositionMode::Hedge;
            // taker open_volume 收窄到 60，恰好等于 ADL 请求量，否则只会部分平仓，测不出 key 是否用对。
            ups.get_mut(TAKER_UID).unwrap().positions.get_mut(&FUT_SYMBOL).unwrap().open_volume = 60;
            ups.get_mut(TAKER_UID).unwrap().positions.get_mut(&FUT_SYMBOL).unwrap().open_price_sum = 5_400; // 60*90
            // taker LONG -> HEDGE key = +FUT_SYMBOL；重新按 HEDGE 规则插入（键必须与 direction 一致）。
            let taker_pos = ups.get_mut(TAKER_UID).unwrap().positions.remove(&FUT_SYMBOL).unwrap();
            ups.get_mut(TAKER_UID).unwrap().positions.insert(FUT_SYMBOL, taker_pos);
            // CP_A SHORT -> HEDGE key = -FUT_SYMBOL。
            let cp_a_pos = ups.get_mut(CP_A).unwrap().positions.remove(&FUT_SYMBOL).unwrap();
            ups.get_mut(CP_A).unwrap().positions.insert(-FUT_SYMBOL, cp_a_pos);

            let taker_key = ups.get(TAKER_UID).unwrap().create_positions_key(FUT_SYMBOL, OrderAction::Bid, OrderCommandType::AutoDeleveraging);
            let cp_a_key = ups.get(CP_A).unwrap().create_positions_key(FUT_SYMBOL, OrderAction::Ask, OrderCommandType::AutoDeleveraging);
            assert_eq!(taker_key, FUT_SYMBOL);
            assert_eq!(cp_a_key, -FUT_SYMBOL, "HEDGE 下 counterparty(SHORT) 的 key 必须是 -symbol");

            let mut cmd = adl_cmd(OrderAction::Bid, 60, 100); // 只请求 60，恰好吃满 A 也恰好平掉 taker 全部仓位
            run_full_pipeline(&mut engine, &mut cmd, &mut ups, &ssp);

            assert_eq!(cmd.result_code, Some(CommandResultCode::Success));
            assert!(!ups.get(TAKER_UID).unwrap().positions.contains_key(&taker_key), "taker 必须按 create_positions_key 算出的 key 被关闭，不是巧合命中裸 symbol");
            assert!(!ups.get(CP_A).unwrap().positions.contains_key(&cp_a_key), "counterparty 必须按 -symbol 这个 key 被关闭，证明查找路径确实走了 create_positions_key 而非裸 symbol");
        }

        // ---- 请求量非正 / 无候选：全拒但不 panic ----

        #[test]
        fn non_positive_size_is_noop_success() {
            let (mut engine, mut ups, ssp) = setup_taker_and_three_candidates();
            let mut cmd = adl_cmd(OrderAction::Bid, 0, 100);
            run_full_pipeline(&mut engine, &mut cmd, &mut ups, &ssp);
            assert_eq!(cmd.result_code, Some(CommandResultCode::Success));
            assert!(cmd.adl_user_positions.is_empty());
            assert!(cmd.adl_events.is_empty());
            assert!(ups.get(TAKER_UID).unwrap().positions.contains_key(&FUT_SYMBOL), "size<=0 不应关掉 taker 的仓");
        }

        #[test]
        fn no_eligible_candidates_rejects_without_touching_taker_position() {
            let (mut engine, mut ups, ssp) = setup_taker_and_three_candidates();
            // 把三个候选全部翻成同向（LONG），过滤条件 `!is_same_as_action` 会把它们全部排除。
            for uid in [CP_A, CP_B, CP_C] {
                ups.get_mut(uid).unwrap().positions.get_mut(&FUT_SYMBOL).unwrap().direction = PositionDirection::Long;
            }
            let mut cmd = adl_cmd(OrderAction::Bid, 100, 100);
            run_full_pipeline(&mut engine, &mut cmd, &mut ups, &ssp);

            assert_eq!(cmd.result_code, Some(CommandResultCode::Success), "空候选是 matcher-event 级别的全拒，不是命令失败（同 IF_TAKEOVER 先例）");
            assert!(cmd.adl_events.is_empty());
            assert!(ups.get(TAKER_UID).unwrap().positions.contains_key(&FUT_SYMBOL), "全拒（events 为空）时 finalize 不应关闭 taker 自己的仓");
        }
    }
}
