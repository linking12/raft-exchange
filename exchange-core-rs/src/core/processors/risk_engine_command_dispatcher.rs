//! 对应 Java `exchange.core2.core.processors.RiskEngineCommandDispatcher`。
//!
//! `RiskEngine::pre_process_command`（R1）对 `cmd.command.is_non_trading()` 为真的命令整块委托到
//! `RiskEngineCommandDispatcher::dispatch`——Java 侧 `RiskEngine#preProcessCommand` 是三级路由
//! （`is_loan()` → LoanCommandDispatcher；`is_non_trading()` → 本类；其余交易类命令留在 RiskEngine 主体
//! 自己的 R1/R2），本类只负责第二级，让 RiskEngine 主体聚焦下单校验（R1）与撮合结果落账（R2）。
//!
//! 处理范围与 Java 版一致，按方法定义顺序分三类：账户维度（ADD_USER / BALANCE_ADJUSTMENT /
//! MARGIN_ADJUSTMENT / LEVERAGE_ADJUSTMENT / POSITION_MODE_ADJUSTMENT / SUSPEND_USER / RESUME_USER /
//! INTERNAL_TRANSFER）、行情（MARKPRICE_ADJUSTMENT，挂钩强平检测）、运营（IF_DEPOSIT / IF_WITHDRAW /
//! RESET_FEE / REPRICE_LOAN_RATES / SETTLE_PNL / SYSTEM_LIQUIDATION_NOTIFY）。
//!
//! 与 Java 版的结构性差异（均为已确认的合理重构，非遗漏，勿据此"补齐"）：
//! - Java 版按 shard 运行：账户维度命令过 `uidForThisHandler` 分片门，IF_DEPOSIT/IF_WITHDRAW 按
//!   `cmd.uid` 承载的目标 shardId 定向单 shard 写 resultCode，其余运营命令各 shard 都执行、只 shard 0
//!   写 `cmd.resultCode`。Rust 版是单实例（无分片），`dispatch` 直接返回 `CommandResultCode` 由调用方
//!   写回 `cmd.result_code`，故本文件不含任何分片门判断。
//! - RESET_FEE 在 Java 版委托给独立的两步处理器 `ResetFeeProcessor`（R1 `collectInput` / R2 `apply`）；
//!   Rust 版没有对应的两步处理器，`reset_fee` 把归集 fees/interest_revenue 并生成 FundEvent 的逻辑
//!   直接内联在本类里一次性完成。
//! - SETTLE_PNL（交割结算）与 SYSTEM_LIQUIDATION_NOTIFY 在 Java 版是 `RiskEngine#preProcessCommand`
//!   主 switch 里的独立 case（结算逻辑在 `RiskEngine.settlePnl()`），并不在
//!   `RiskEngineCommandDispatcher.java` 里；Rust 版把它们一并折叠进本类的 `dispatch`。
//! - Java 版 BINARY_DATA_COMMAND/QUERY 分支（`handleBinaryMessage`：批量加 currency/symbol/account、
//!   ADD_LOAN 全局/per-symbol/利率曲线配置）在 Rust 侧没有走 replicated 命令这条路径，改为
//!   `RiskEngine::apply_add_loan` 等直接调用；本文件不含这部分逻辑。

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
use crate::core::processors::internaltransfer_command_processor::InternalTransferCommandProcessor;
use crate::core::processors::loanratepricing_command_processor::LoanRatePricingCommandProcessor;
use crate::core::processors::twostep_command_processor::{TwoStepCommandProcessor, TwoStepContext};
use crate::core::processors::risk_engine::RiskEngine;
use crate::core::processors::symbol_specification_provider::SymbolSpecificationProvider;
use crate::core::processors::user_profile_service::UserProfileService;
use crate::core::utils::core_arithmetic_utils as arithmetic;

pub struct RiskEngineCommandDispatcher;

impl RiskEngineCommandDispatcher {
    /// 非交易命令分发入口，对应 Java `RiskEngineCommandDispatcher#dispatch`（并吸收了 Java `RiskEngine`
    /// 主 switch 里 SETTLE_PNL / SYSTEM_LIQUIDATION_NOTIFY 两个 case，见文件头说明）。
    /// REPRICE_LOAN_RATES / INTERNAL_TRANSFER 走 `TwoStepCommandProcessor::collect`（R1 阶段，对应
    /// Java 各自 processor 的 `collectInput`）。
    /// 成功后再按命令类型补发资金事件（BALANCE_ADJUSTMENT → emit_balance_adjustment_event，
    /// MARGIN_ADJUSTMENT → emit_margin_adjust_events）：Java 版这段发事件逻辑内联在 adjustBalance /
    /// adjustMargin 内部，这里拆成统一的收尾步骤。
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
            OrderCommandType::RepriceLoanRates => {
                let mut ctx = TwoStepContext::new(engine, ups, ssp);
                LoanRatePricingCommandProcessor.collect(&mut ctx, cmd)
            }
            OrderCommandType::InternalTransfer => {
                let mut ctx = TwoStepContext::new(engine, ups, ssp);
                InternalTransferCommandProcessor.collect(&mut ctx, cmd)
            }
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

    /// 对应 Java `addUser`：创建空用户档案；已存在则由 `ups.add_empty_user_profile` 返回失败码。
    pub fn add_user(_engine: &mut RiskEngine, cmd: &OrderCommand, ups: &mut UserProfileService) -> CommandResultCode {
        ups.add_empty_user_profile(cmd.uid)
    }

    /// 对应 Java `adjustBalance` + `UserProfileService#balanceAdjustment` + `applyBalanceAdjustment`
    /// （Java 拆成三个方法，这里合并成一步）：充值 / 提现。
    /// 校验顺序（与 Java 严格一致，不能颠倒）：
    /// 1. 提现（amount_diff < 0）先过可提余额 NSF（`withdrawable_balance`：扣现货冻结 / 借贷抵押必扣，
    ///    可用期货浮盈；口径与内部转账一致）→ RiskNsf；
    /// 2. 再过账户原始余额下限校验（不看锁仓，只看 accounts 本身够不够扣）→ UserMgmtAccountBalanceAdjustmentNsf；
    /// 3. 幂等：NSF 都通过后才 try_claim_tx，故 NSF 失败路径不占用 transaction id，调用方可修正后同 id 重试。
    /// 成功后按 adjustment 类型（Suspend / Adjustment）反向更新对应守恒对冲桶，保证全局余额 sum 不变。
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

    /// 对应 Java `suspendUser`：挂起前把精度漂移残留的 dust 充缴到 fees、清空 exchangeLocked。
    /// dust 本质是应付未付的手续费尾差，从 accounts + exchangeLocked 扣除并入 fees，全局 sum delta = 0。
    /// 仅当同时满足以下条件才补缴（任一不满足则保持原状，只挂起不扫 dust）：
    /// 1. 无持仓；2. 每个 currency 的 exchangeLocked < DUST_SAFETY_LIMIT（防御兜底，非正常 dust 量级不碰）；
    /// 3. exchangeLocked 非零的 currency 在 accounts 上有等额（free 已提完，只剩锁仓尾差）；
    /// 4. accounts 非零的 currency 在 exchangeLocked 上也有等额（两个方向都要对得上，防止误扫正常余额）。
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

    /// 对应 Java `adjustPositionMode`：切换 ONEWAY/HEDGE。已是目标模式直接 Success；有持仓则拒
    /// （RiskMarginPositionExists，持仓下切换单/双向会打乱 positions_key 编码）。
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

    /// 对应 Java 独立的两步处理器 `ResetFeeProcessor`（R1 collectInput + R2 apply，本类里没有对应文件，
    /// 逻辑折叠成一次性调用）：把 fees 与 loan 利息收入两个桶归集进 adjustments（对冲守恒），
    /// 每个 currency 归集后生成一条 ResetFee FundEvent 供下游对账。
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

    /// 对应 Java `adjustMargin`：给持仓追加/退回保证金。
    /// CROSS 模式无 extraMargin 概念，直接走 `balance_adjustment` 充入 accounts（同 currency 的所有 CROSS
    /// 仓位共享 accounts，故无需按 position 分账）。ISOLATED 模式从 accounts 转入
    /// `position.extra_margin`：NSF 用 `withdrawable_balance`（不能拨可用资金去补 isolate margin，
    /// 否则贷款抵押物被挪用变裸债）；NSF 通过后才 `try_claim_tx` 做幂等（ISOLATED 没有 adjustments 桶
    /// 对冲，纯靠 try_claim_tx 防重放）。extra_margin_delta 必须走 `currency_to_size_price_scale`
    /// 换到跟 open_init_margin_sum 同一单位（base_scale_k × quote_scale_k），否则爆仓价/破产价会算错。
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
            return Self::balance_adjustment(engine, cmd, ups, ssp);
        }

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

        if engine.withdrawable_balance(user_profile, currency, ssp) - cmd.price < 0 {
            return CommandResultCode::RiskNsf;
        }

        if !user_profile.try_claim_tx(cmd.order_id, cmd.timestamp) {
            return CommandResultCode::UserMgmtAccountBalanceAdjustmentAlreadyAppliedSame;
        }

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

    /// 对应 Java `adjustLeverage`：
    /// 1. 收集该用户在该 symbol 下的所有 position（无仓位直接 Success，不用查杠杆合法性）；
    /// 2. 逐仓校验新杠杆是否合法（`is_valid_leverage`，基于用 mark price 估算的 notional），
    ///    同时累加新旧所需保证金；leverage=0 归一成 1x（与落地侧 `update_leverage` 保持一致，避免除零）；
    /// 3. 若新所需保证金 > 旧所需，做一次 NSF（可用余额需覆盖差额）；
    /// 4. 全部通过后才真正逐仓 `update_leverage`（先校验全部再落地，任一仓位不合法则整体拒绝、不留半更新）。
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

    /// 对应 Java `adjustMarkPrice`：更新标记价缓存并触发挂钩的强平检测
    /// （`liquidation_engine.check_positions`，产生的强平预警事件追加到 cmd.fund_events）。
    /// Java 版按 shard 各自维护 last_price_cache、只 shard 0 写 resultCode；Rust 版单实例无需该区分。
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
        engine.last_price_cache.entry(cmd.symbol).or_default().mark_price_ts = cmd.timestamp;
        let mut alerts = Vec::new();
        engine.liquidation_engine.check_positions(cmd, ups, ssp, &engine.last_price_cache, &engine.loan_service, &mut alerts);
        cmd.fund_events.append(&mut alerts);
        CommandResultCode::Success
    }

    /// 对应 Java `RiskEngine#settlePnl`（该方法在 Java 侧挂在 `RiskEngine` 主类的 SETTLE_PNL case 下，
    /// 不在 `RiskEngineCommandDispatcher.java` 里，Rust 版折叠进本类，见文件头说明）：
    /// 交割合约到期强制按交割价平掉所有用户在该 symbol 下的持仓。仅限 FuturesContractDelivery 类型。
    /// 每个仓位：按交割价 close_current_position_futures → 退还 extra_margin（若有）→ 已实现盈亏入账 →
    /// 发 MarginRefund/PnlSettlement 事件 → 从 positions 表移除。先把 key 收集到 Vec 并排序（降序）再
    /// 逐个处理，是为了拿到确定的遍历顺序（跨节点回放一致），同时避开边遍历 BTreeMap 边删除的借用冲突。
    fn settle_pnl(engine: &mut RiskEngine, cmd: &mut OrderCommand, ups: &mut UserProfileService, ssp: &SymbolSpecificationProvider) -> CommandResultCode {
        let symbol = cmd.symbol;
        let order_id = SYSTEM_TRIGGERED_ORDER_ID;
        let spec = match ssp.get_symbol(symbol) {
            Some(s) if s.symbol_type == SymbolType::FuturesContractDelivery => s.clone(),
            _ => return CommandResultCode::InvalidSymbol,
        };
        let currency_spec = match ssp.get_currency(spec.quote_currency) {
            Some(c) => c.clone(),
            None => return CommandResultCode::InvalidSymbol,
        };
        let price = cmd.price;
        for up in ups.users.values_mut() {
            let mut keys: Vec<i32> = up
                .positions
                .iter()
                .filter(|(_, p)| p.symbol == symbol && p.open_volume != 0)
                .map(|(&k, _)| k)
                .collect();
            keys.sort_unstable_by(|a, b| b.cmp(a));
            for key in keys {
                let (close_action, size) = {
                    let pos = &up.positions[&key];
                    let action = if pos.direction == PositionDirection::Long { OrderAction::Ask } else { OrderAction::Bid };
                    (action, pos.open_volume)
                };
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
                RiskEngine::push_futures_event(&mut cmd.fund_events, &engine.last_price_cache, FundEventType::PnlSettlement, order_id, up.positions.get(&key).unwrap(), &spec, up, ssp);
                up.positions.remove(&key);
            }
        }
        CommandResultCode::Success
    }

    /// 对应 Java `processIFDeposit`：往某 symbol 的保险基金池充值。Java 版按 `cmd.uid` 承载的目标 shardId
    /// 定向单 shard 记账（其余 shard 静默 no-op），Rust 版单实例无需该区分。
    /// 精度可逆校验：currency 尺度 → 撮合 size*price 尺度（notional）→ 再转回 currency 尺度，必须严格等于
    /// 原始 currency_amount，否则说明存在精度截断，会导致 adjustments 对冲桶留有残量、对账漂移，直接拒绝。
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

    /// 对应 Java `processIFWithdraw`：语义与 `if_deposit` 对称（同一套精度可逆校验），差别在于失败码
    /// ——保险基金 available 不足时返回 RiskIfInsufficient；只扣 available，不动 reserved
    /// （reserved 是正在保护某笔强平的预冻结部分，withdraw 不能动）。
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

    /// 对应 Java `adjustBalance` 里内联的 `sendDepositEvent`/`sendWithdrawEvent` 调用（Rust 版拆到
    /// dispatch 成功后统一收尾，见 dispatch 的文档注释）：`amountDiff > 0` 视为充值，否则(含 0)视为提现
    /// （对齐 Java `adjustBalance` 的 `amountDiff > 0 ? DEPOSIT : WITHDRAW`）。
    fn emit_balance_adjustment_event(_engine: &mut RiskEngine, cmd: &mut OrderCommand, ups: &UserProfileService, ssp: &SymbolSpecificationProvider) {
        let ev_type = if cmd.price > 0 { FundEventType::Deposit } else { FundEventType::Withdraw };
        RiskEngine::push_spot_balance_event(cmd, ups, ssp, ev_type, cmd.order_id, cmd.uid, cmd.symbol, 0);
    }

    /// 对应 Java `adjustMargin` 里内联的 `sendMarginAdjustmentEvent` 调用（同上，拆到收尾统一处理）：
    /// CROSS 模式给该 currency 下所有 CROSS 仓位各发一条事件（它们共享同一份 accounts 余额）；
    /// ISOLATED 模式只给 cmd 对应的那一个 position 发一条。
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
