//! 对应 Java `exchange.core2.core.processors.RiskEngineCommandDispatcher`：把非交易命令（账户 / 行情 / 运营）
//! 的 R1 处理从 `RiskEngine` 抽出，令 RiskEngine 只留撮合交易 + R2 结算，与 `LoanCommandDispatcher` 对称。
//! `pre_process_command` 的 ② 非交易 lane 整块委托本 dispatcher。

use std::collections::BTreeMap;
use crate::core::common::cmd::command_result_code::CommandResultCode;
use crate::core::common::cmd::order_command::OrderCommand;
use crate::core::common::cmd::order_command_type::OrderCommandType;
use crate::core::common::fund_event::{FundEvent, FundEventType, SYSTEM_TRIGGERED_ORDER_ID};
use crate::core::common::margin_mode::MarginMode;
use crate::core::common::order_action::OrderAction;
use crate::core::common::position_direction::PositionDirection;
use crate::core::common::position_mode::PositionMode;
use crate::core::common::symbol_type::SymbolType;
use crate::core::processors::internal_transfer_processor::InternalTransferProcessor;
use crate::core::processors::loan_rate_pricing_processor::LoanRatePricingProcessor;
use crate::core::processors::risk_engine::RiskEngine;
use crate::core::processors::symbol_specification_provider::SymbolSpecificationProvider;
use crate::core::processors::user_profile_service::UserProfileService;
use crate::core::utils::core_arithmetic_utils as arithmetic;

/// 非交易命令分派器（无状态；所有状态在传入的 `&mut RiskEngine`）。
pub struct RiskEngineCommandDispatcher;

impl RiskEngineCommandDispatcher {
    /// 非交易命令分派（镜像 Java `RiskEngineCommandDispatcher.dispatch`）：账户 / 行情 / 运营命令。
    /// 结果码在此定死（ME/R2 对其 no-op）；`RepriceLoanRates`/`InternalTransfer` 仅在此做 R1 collect，其 R2 apply 仍在 `RiskEngine::handler_risk_release`。
    pub fn dispatch(
        engine: &mut RiskEngine,
        cmd: &mut OrderCommand,
        ups: &mut UserProfileService,
        ssp: &SymbolSpecificationProvider,
    ) -> CommandResultCode {
        let rc = match cmd.command {
            OrderCommandType::AddUser => Self::add_user(engine, cmd, ups),
            OrderCommandType::BalanceAdjustment => Self::balance_adjustment(engine, cmd, ups, ssp),
            OrderCommandType::MarginAdjustment => Self::margin_adjustment(engine, cmd, ups, ssp),
            OrderCommandType::LeverageAdjustment => Self::leverage_adjustment(engine, cmd, ups, ssp),
            OrderCommandType::MarkpriceAdjustment => Self::markprice_adjustment(engine, cmd, ups, ssp),
            OrderCommandType::RepriceLoanRates => Self::reprice_loan_rates_collect(engine, cmd),
            OrderCommandType::InternalTransfer => Self::internal_transfer_collect(engine, cmd, ups, ssp),
            OrderCommandType::IfDeposit => Self::if_deposit(engine, cmd, ssp),
            OrderCommandType::IfWithdraw => Self::if_withdraw(engine, cmd, ssp),
            OrderCommandType::SettlePnl => Self::settle_pnl(engine, cmd, ups, ssp),
            OrderCommandType::SuspendUser => Self::suspend_user(engine, cmd, ups),
            OrderCommandType::ResumeUser => ups.resume_user_profile(cmd.uid),
            OrderCommandType::PositionModeAdjustment => Self::position_mode_adjustment(engine, cmd, ups),
            OrderCommandType::ResetFee => Self::reset_fee(engine, cmd, ssp),
            OrderCommandType::SystemLiquidationNotify => CommandResultCode::Success,
            _ => CommandResultCode::MatchingUnsupportedCommand,
        };
        if rc == CommandResultCode::Success {
            match cmd.command {
                OrderCommandType::BalanceAdjustment => Self::emit_balance_adjustment_event(engine, cmd, ups, ssp),
                OrderCommandType::MarginAdjustment => Self::emit_margin_adjust_events(engine, cmd, ups, ssp),
                _ => {}
            }
        }
        rc
    }

    /// 建空 UserProfile，已存在→UserMgmtUserAlreadyExists；uidForThisHandler 分片门未移植（单 shard 恒真）。
    pub fn add_user(_engine: &mut RiskEngine, cmd: &OrderCommand, ups: &mut UserProfileService) -> CommandResultCode {
        ups.add_empty_user_profile(cmd.uid)
    }

    /// 两层校验：外层现货 NSF→内层 NSF→幂等 claim→成功后 account += amount_diff 且 adjustments -= amount_diff（Σ恒定）；提现额度叠加 free futures margin。
    pub fn balance_adjustment(
        engine: &mut RiskEngine,
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
            if engine.withdrawable_balance(user_profile, currency, ssp) - withdrawal_amount < 0 {
                return CommandResultCode::RiskNsf;
            }
        }

        if amount_diff < 0 && user_profile.account(currency) + amount_diff < 0 {
            return CommandResultCode::UserMgmtAccountBalanceAdjustmentNsf;
        }

        if !user_profile.try_claim_tx(cmd.order_id, cmd.timestamp) {
            return CommandResultCode::UserMgmtAccountBalanceAdjustmentAlreadyAppliedSame;
        }

        user_profile.add_to_account(currency, amount_diff);

        // 守恒对冲入桶：SUSPEND 类型（挂起前清零余额）入 `suspends`，否则入 `adjustments`（对齐 Java
        // `applyBalanceAdjustment` 按 `BalanceAdjustmentType.of(cmd.orderType.getCode())` 分桶）。
        let adj_type = cmd
            .order_type
            .map(|ot| crate::core::common::balance_adjustment_type::BalanceAdjustmentType::of(ot.code()))
            .unwrap_or(crate::core::common::balance_adjustment_type::BalanceAdjustmentType::Adjustment);
        let bucket = match adj_type {
            crate::core::common::balance_adjustment_type::BalanceAdjustmentType::Suspend => &mut engine.suspends,
            crate::core::common::balance_adjustment_type::BalanceAdjustmentType::Adjustment => &mut engine.adjustments,
        };
        *bucket.entry(currency).or_insert(0) -= amount_diff;

        CommandResultCode::Success
    }

    fn suspend_user(engine: &mut RiskEngine, cmd: &OrderCommand, ups: &mut UserProfileService) -> CommandResultCode {
        const DUST_SAFETY_LIMIT: i64 = 1000;
        if let Some(up) = ups.get_mut(cmd.uid) {
            if !up.positions.values().any(|p| !p.is_empty()) {
                let mut eligible = up
                    .exchange_locked
                    .iter()
                    .all(|(&c, &locked)| locked < DUST_SAFETY_LIMIT && up.accounts.get(&c).copied().unwrap_or(0) == locked);
                if eligible {
                    eligible = up
                        .accounts
                        .iter()
                        .all(|(&c, &acc)| acc == 0 || up.exchange_locked.get(&c).copied().unwrap_or(0) == acc);
                }
                if eligible {
                    let dust: Vec<(i32, i64)> =
                        up.exchange_locked.iter().filter(|(_, &v)| v > 0).map(|(&c, &v)| (c, v)).collect();
                    for (c, d) in dust {
                        *up.accounts.entry(c).or_insert(0) -= d;
                        *engine.fees.entry(c).or_insert(0) += d;
                    }
                    up.exchange_locked.clear();
                }
            }
        }
        ups.suspend_user_profile(cmd.uid)
    }

    fn position_mode_adjustment(_engine: &mut RiskEngine, cmd: &OrderCommand, ups: &mut UserProfileService) -> CommandResultCode {
        let Some(up) = ups.get_mut(cmd.uid) else {
            return CommandResultCode::AuthInvalidUser;
        };
        let target = PositionMode::of_code(cmd.action.map(|a| a.code()).unwrap_or(0));
        if up.position_mode == target {
            return CommandResultCode::Success;
        }
        if !up.positions.is_empty() {
            return CommandResultCode::RiskMarginPositionExists;
        }
        up.position_mode = target;
        CommandResultCode::Success
    }

    fn reset_fee(engine: &mut RiskEngine, cmd: &mut OrderCommand, ssp: &SymbolSpecificationProvider) -> CommandResultCode {
        let mut harvested: BTreeMap<i32, i64> = BTreeMap::new();
        RiskEngine::harvest_into(&mut engine.fees, &mut engine.adjustments, &mut harvested);
        RiskEngine::harvest_into(&mut engine.loan_service.interest_revenue, &mut engine.adjustments, &mut harvested);
        for (c, amount) in harvested {
            let cur_scale = ssp.get_currency(c).map(|s| s.currency_scale_k).unwrap_or(0);
            let mut ev = FundEvent::spot(FundEventType::ResetFee, SYSTEM_TRIGGERED_ORDER_ID, 0, c, amount, 0);
            ev.currency_scale_k = cur_scale;
            cmd.fund_events.push(ev);
        }
        CommandResultCode::Success
    }

    /// CROSS 直接转发 balance_adjustment（同一原语）；ISOLATED 从 accounts 转入 position.extra_margin（不碰 adjustments 桶）；仅支持追加，无"移出保证金"路径。
    pub fn margin_adjustment(
        engine: &mut RiskEngine,
        cmd: &OrderCommand,
        ups: &mut UserProfileService,
        ssp: &SymbolSpecificationProvider,
    ) -> CommandResultCode {
        if !engine.cfg_margin_trading_enabled {
            return CommandResultCode::RiskMarginTradingDisabled;
        }
        if cmd.price <= 0 {
            return CommandResultCode::RiskInvalidAmount;
        }

        if cmd.margin_mode == MarginMode::Cross {
            // CROSS：cmd.symbol 是 currency id，语义等价 BALANCE_ADJUSTMENT 的 ADJUSTMENT 充值，直接复用同一原语。
            return Self::balance_adjustment(engine, cmd, ups, ssp);
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
        if engine.withdrawable_balance(user_profile, currency, ssp) - cmd.price < 0 {
            return CommandResultCode::RiskNsf;
        }

        // ISOLATED 无 adjustments 桶对冲，按 cmd.order_id 自行幂等；NSF 通过后再 claim。
        if !user_profile.try_claim_tx(cmd.order_id, cmd.timestamp) {
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

    /// 调整 symbol 下用户全部仓位杠杆，全部校验通过才落地（全改或全不改）；leverage==0 归一为 1；持仓存在但 mark price 缺失 panic（不可达不变量）。
    pub fn leverage_adjustment(
        engine: &mut RiskEngine,
        cmd: &OrderCommand,
        ups: &mut UserProfileService,
        ssp: &SymbolSpecificationProvider,
    ) -> CommandResultCode {
        if !engine.cfg_margin_trading_enabled {
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

        let mark_price = engine
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
            let locked = RiskEngine::calculate_locked(user_profile, spec.quote_currency, ssp, currency_spec);
            if diff > balance - locked {
                return CommandResultCode::RiskNsf;
            }
        }

        user_profile.process_position_record(cmd.symbol, |position| {
            position.update_leverage(effective_leverage);
        });

        CommandResultCode::Success
    }

    /// 更新 lastPriceCache，拒绝 price<=0（Java 允许 0，此处收窄避免下游对 None panic 复制状态机）。
    pub fn markprice_adjustment(
        engine: &mut RiskEngine,
        cmd: &mut OrderCommand,
        ups: &mut UserProfileService,
        ssp: &SymbolSpecificationProvider,
    ) -> CommandResultCode {
        if ssp.get_symbol(cmd.symbol).is_none() {
            return CommandResultCode::InvalidSymbol;
        }
        if cmd.price <= 0 {
            return CommandResultCode::RiskInvalidAmount;
        }
        engine.set_mark_price(cmd.symbol, cmd.price);
        // 外部喂价也推进 ts，使后续现货 applyTradePrice EMA 从此刻起算。
        engine.mark_price_ts.insert(cmd.symbol, cmd.timestamp);
        // 价格更新后触发 targeted 强平检测（价格波动是主强平触发）；产出的 FORCE 命令入 liquidation_engine.pending_commands 由
        // ExchangeCore 排空重喂，margin/liquidation 告警须并入 cmd.fund_events（同 scan/funding 两路）。
        let mut alerts = Vec::new();
        engine.liquidation_engine.check_positions(cmd, ups, ssp, &engine.last_price_cache, &engine.loan_service, &mut alerts);
        cmd.fund_events.append(&mut alerts);
        CommandResultCode::Success
    }

    fn settle_pnl(engine: &mut RiskEngine, cmd: &mut OrderCommand, ups: &mut UserProfileService, ssp: &SymbolSpecificationProvider) -> CommandResultCode {
        let symbol = cmd.symbol;
        let order_id = cmd.order_id;
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
                // 交割结算：关满仓后无条件退保证金+结算盈亏+移除仓位（对齐 Java settlePnl，
                // 不复用 ADL 的 is_empty 门——交割即便仓位还挂着单也要清算并移除）。
                up.positions.get_mut(&key).unwrap().close_current_position_futures(close_action, size, price);
                let currency = up.positions.get(&key).unwrap().currency;

                let extra_margin = up.positions.get(&key).unwrap().extra_margin;
                if extra_margin > 0 {
                    let refund = arithmetic::size_price_to_currency_scale(
                        extra_margin,
                        spec.base_scale_k,
                        spec.quote_scale_k,
                        currency_spec.currency_scale_k,
                    );
                    up.add_to_account(currency, refund);
                    up.positions.get_mut(&key).unwrap().extra_margin = 0;
                    RiskEngine::push_futures_event(&mut cmd.fund_events, &engine.last_price_cache, FundEventType::MarginRefund, order_id, up.positions.get(&key).unwrap(), &spec, up, ssp);
                }

                let profit = up.positions.get(&key).unwrap().profit;
                if profit != 0 {
                    let profit_scaled = arithmetic::size_price_to_currency_scale(
                        profit,
                        spec.base_scale_k,
                        spec.quote_scale_k,
                        currency_spec.currency_scale_k,
                    );
                    up.add_to_account(currency, profit_scaled);
                }
                // 单条 PnlSettlement（Java 无条件发一条；先发再移除，令快照能读到仓位）。
                RiskEngine::push_futures_event(&mut cmd.fund_events, &engine.last_price_cache, FundEventType::PnlSettlement, order_id, up.positions.get(&key).unwrap(), &spec, up, ssp);
                up.positions.remove(&key);
            }
        }
        CommandResultCode::Success
    }

    /// RepriceLoanRates R1：单 shard 归并恒等，collect_input 与 merge 一次性做完，写入 cmd.loan_reprice_events 供 R2 消费。
    fn reprice_loan_rates_collect(engine: &mut RiskEngine, cmd: &mut OrderCommand) -> CommandResultCode {
        let shard_data = LoanRatePricingProcessor::collect_input(&engine.loan_service);
        cmd.loan_reprice_events = LoanRatePricingProcessor::build_matcher_events(&[shard_data]);
        CommandResultCode::Success
    }

    /// InternalTransfer R1+merge：字段映射 cmd.uid=from_uid/cmd.size=to_uid/cmd.symbol=currency/cmd.price=amount；R1 失败直接返回拒绝码，成功写入 cmd.internal_transfer_event 供 R2 消费。
    fn internal_transfer_collect(
        engine: &mut RiskEngine,
        cmd: &mut OrderCommand,
        ups: &mut UserProfileService,
        ssp: &SymbolSpecificationProvider,
    ) -> CommandResultCode {
        let from_uid = cmd.uid;
        let to_uid = cmd.size;
        let currency = cmd.symbol;
        let amount = cmd.price;
        let order_id = cmd.order_id;
        let timestamp = cmd.timestamp;

        let rc =
            InternalTransferProcessor::collect_input(engine, ups, ssp, from_uid, to_uid, currency, amount, order_id, timestamp);
        if rc == CommandResultCode::Success {
            cmd.internal_transfer_event =
                Some(InternalTransferProcessor::build_matcher_events(to_uid, currency, amount));
            RiskEngine::push_spot_balance_event(cmd, ups, ssp, FundEventType::InternalTransfer, order_id, from_uid, currency, 0);
        }
        rc
    }

    /// futures IF_DEPOSIT 运营充值，与 loan LOAN_IF_DEPOSIT 独立池子；校验序 symbol→amount>0→currency spec→精度可逆，全过才 deposit_to_insurance_fund + adjustments[quote_currency] -= amount（对冲恒定）。
    fn if_deposit(engine: &mut RiskEngine, cmd: &OrderCommand, ssp: &SymbolSpecificationProvider) -> CommandResultCode {
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
        engine.liquidation_service.deposit_to_insurance_fund(cmd.symbol, notional);
        *engine.adjustments.entry(quote_currency).or_insert(0) -= currency_amount;
        CommandResultCode::Success
    }

    /// 语义与 if_deposit 对称，available 不足→RiskIfInsufficient（与 loan 的 LoanIfInsufficient 互异）；只扣 available 不动 reserved。
    fn if_withdraw(engine: &mut RiskEngine, cmd: &OrderCommand, ssp: &SymbolSpecificationProvider) -> CommandResultCode {
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
        if !engine.liquidation_service.withdraw_from_insurance_fund(cmd.symbol, notional) {
            return CommandResultCode::RiskIfInsufficient;
        }
        let quote_currency = spec.quote_currency;
        *engine.adjustments.entry(quote_currency).or_insert(0) += currency_amount;
        CommandResultCode::Success
    }

    fn emit_balance_adjustment_event(_engine: &mut RiskEngine, cmd: &mut OrderCommand, ups: &UserProfileService, ssp: &SymbolSpecificationProvider) {
        let ev_type = if cmd.price >= 0 { FundEventType::Deposit } else { FundEventType::Withdraw };
        RiskEngine::push_spot_balance_event(cmd, ups, ssp, ev_type, cmd.order_id, cmd.uid, cmd.symbol, 0);
    }

    fn emit_margin_adjust_events(engine: &mut RiskEngine, cmd: &mut OrderCommand, ups: &UserProfileService, ssp: &SymbolSpecificationProvider) {
        let Some(up) = ups.get(cmd.uid) else { return };
        let order_id = cmd.order_id;
        let lpc = &engine.last_price_cache;
        if cmd.margin_mode == MarginMode::Cross {
            let currency = cmd.symbol;
            for pos in up.positions.values() {
                if pos.margin_mode == MarginMode::Cross && pos.currency == currency {
                    if let Some(spec) = ssp.get_symbol(pos.symbol) {
                        RiskEngine::push_futures_event(&mut cmd.fund_events, lpc, FundEventType::MarginAdjust, order_id, pos, spec, up, ssp);
                    }
                }
            }
        } else if let Some(action) = cmd.action {
            let key = up.create_positions_key(cmd.symbol, action, cmd.command);
            if let Some(pos) = up.positions.get(&key) {
                if let Some(spec) = ssp.get_symbol(pos.symbol) {
                    RiskEngine::push_futures_event(&mut cmd.fund_events, lpc, FundEventType::MarginAdjust, order_id, pos, spec, up, ssp);
                }
            }
        }
    }
}
