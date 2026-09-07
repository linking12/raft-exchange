//! 对应 Java `LoanCommandDispatcher`：借贷命令 dispatch 表 + 公共 preamble（参考文档 §0/§2.1-2.4）；零大小类型，无 `engine` 字段，逐次显式传参。
use crate::core::common::cmd::command_result_code::CommandResultCode;
use crate::core::common::cmd::order_command::OrderCommand;
use crate::core::common::cmd::order_command_type::OrderCommandType;
use crate::core::common::core_symbol_specification::CoreSymbolSpecification;
use crate::core::common::cross_loan_record::CrossLoanRecord;
use crate::core::common::isolated_loan_record::{IsolatedLoanRecord, LoanRateMode};
use crate::core::common::order_action::OrderAction;
use crate::core::common::order_type::OrderType;
use crate::core::common::symbol_type::SymbolType;
use crate::core::common::user_profile::UserProfile;
use crate::core::common::user_status::UserStatus;
use crate::core::processors::loan::loan_service::{LoanService, BPS_SCALE};
use crate::core::processors::risk_engine::RiskEngine;
use crate::core::processors::symbol_specification_provider::SymbolSpecificationProvider;
use crate::core::processors::user_profile_service::UserProfileService;
use crate::core::utils::core_arithmetic_utils as arithmetic;

/// 对应 Java `Math.multiplyExact`：局部私有重复一份（风格对齐仓内同名 helper）。
fn mul_exact(a: i64, b: i64) -> i64 {
    i64::try_from(a as i128 * b as i128).unwrap_or_else(|_| panic!("overflow: {a} * {b}"))
}

/// 对应 Java `Math.addExact(long, long)`。
fn add_exact(a: i64, b: i64) -> i64 {
    i64::try_from(a as i128 + b as i128).unwrap_or_else(|_| panic!("overflow: {a} + {b}"))
}

/// 对应 Java `LoanCommandDispatcher`（范围子集）。零大小类型，见模块文档。
pub struct LoanCommandDispatcher;

impl LoanCommandDispatcher {
    /// 对应 Java `dispatch(OrderCommand cmd)`（`:51-121`）：按 `cmd.command` 路由；分片自过滤单 shard 下恒真，未搬迁（同既有 ruling）。
    pub fn dispatch(
        engine: &mut RiskEngine,
        cmd: &mut OrderCommand,
        ups: &mut UserProfileService,
        ssp: &SymbolSpecificationProvider,
    ) -> CommandResultCode {
        let rc = match cmd.command {
            OrderCommandType::LoanCreate => Self::handle_loan_create(engine, cmd, ups, ssp),
            OrderCommandType::LoanRepay => Self::handle_loan_repay(engine, cmd, ups, ssp),
            OrderCommandType::LoanAddCollateral => Self::handle_loan_add_collateral(engine, cmd, ups, ssp),
            OrderCommandType::LoanReleaseCollateral => {
                Self::handle_loan_release_collateral(engine, cmd, ups, ssp)
            }
            OrderCommandType::LoanCrossAddCollateral => {
                Self::handle_loan_cross_add_collateral(engine, cmd, ups, ssp)
            }
            OrderCommandType::LoanCrossWithdrawCollateral => {
                Self::handle_loan_cross_withdraw_collateral(engine, cmd, ups, ssp)
            }
            OrderCommandType::LoanCrossBorrow => Self::handle_loan_cross_borrow(engine, cmd, ups, ssp),
            OrderCommandType::LoanCrossRepay => Self::handle_loan_cross_repay(engine, cmd, ups, ssp),
            OrderCommandType::LoanForceLiquidate => Self::handle_loan_force_liquidate(engine, cmd, ups, ssp),
            OrderCommandType::LoanCrossForceLiquidate => {
                Self::handle_loan_cross_force_liquidate(engine, cmd, ups, ssp)
            }
            OrderCommandType::PoolDeposit => Self::handle_pool_deposit(engine, cmd),
            OrderCommandType::PoolWithdraw => Self::handle_pool_withdraw(engine, cmd),
            OrderCommandType::LoanIfDeposit => Self::handle_loan_if_deposit(engine, cmd),
            OrderCommandType::LoanIfWithdraw => Self::handle_loan_if_withdraw(engine, cmd),
            // 不可达：is_loan() 门守覆盖的 14 码上面已全部列举。
            _ => unreachable!("non-loan command dispatched to LoanCommandDispatcher: {:?}", cmd.command),
        };
        // loan 变更后在 dispatch 出口按 uid 幂等 reconcile 强平扫描器索引（end-state 等价 Java 逐点增量维护，见 loan_liquidation_engine.rs）；pool/IF 运营命令跳过。
        if matches!(
            cmd.command,
            OrderCommandType::LoanCreate
                | OrderCommandType::LoanRepay
                | OrderCommandType::LoanAddCollateral
                | OrderCommandType::LoanReleaseCollateral
                | OrderCommandType::LoanCrossAddCollateral
                | OrderCommandType::LoanCrossWithdrawCollateral
                | OrderCommandType::LoanCrossBorrow
                | OrderCommandType::LoanCrossRepay
                | OrderCommandType::LoanForceLiquidate
                | OrderCommandType::LoanCrossForceLiquidate
        ) {
            Self::reconcile_loan_indices(engine, ups, cmd.uid);
        }
        rc
    }

    /// 按 uid reconcile 借贷强平扫描器的 isolated/cross 两个 targeted 索引到当前敞口精确态（§6.7）。
    fn reconcile_loan_indices(engine: &mut RiskEngine, ups: &UserProfileService, uid: i64) {
        let up = match ups.get(uid) {
            Some(u) => u,
            None => return,
        };
        let lle = &mut engine.liquidation_engine.loan_liquidation_engine;
        for loan in up.isolated_loans.values() {
            if !loan.is_empty() {
                lle.on_isolated_loan_opened(uid, loan.symbol_id);
            }
        }
        // 摘除已无活 loan 的 isolated symbol（先收集再改，避免迭代中改容器）。
        let indexed: Vec<i32> = lle
            .isolated_loan_symbol_to_users
            .iter()
            .filter(|(_, users)| users.contains(&uid))
            .map(|(&s, _)| s)
            .collect();
        for sym in indexed {
            lle.on_isolated_loan_closed(up, sym);
        }
        lle.sync_cross_exposure(up);
    }

    /// 公共 preamble（参考文档 §2 顶部）：缺户 → `AuthInvalidUser`；冻结户 → `LoanUserSuspended`；`tryClaim` 幂等（claim-and-keep，对齐 `BALANCE_ADJUSTMENT`）。
    fn preamble<'a>(
        cmd: &OrderCommand,
        ups: &'a mut UserProfileService,
    ) -> Result<&'a mut UserProfile, CommandResultCode> {
        let up = ups.get_mut(cmd.uid).ok_or(CommandResultCode::AuthInvalidUser)?;
        if up.user_status == UserStatus::Suspended {
            return Err(CommandResultCode::LoanUserSuspended);
        }
        if !up.try_claim_tx(cmd.order_id) {
            return Err(CommandResultCode::UserMgmtAccountBalanceAdjustmentAlreadyAppliedSame);
        }
        Ok(up)
    }

    /// 对应 Java `evalCollateralInLoanCurrency`（`:1009-1015`）：转调 [`LoanService::collateral_value_in_quote_currency`]。
    fn eval_collateral_in_loan_currency(
        ssp: &SymbolSpecificationProvider,
        amount: i64,
        spec: &CoreSymbolSpecification,
        mark_price: i64,
    ) -> i64 {
        let base_spec = ssp.get_currency(spec.base_currency);
        let quote_spec = ssp.get_currency(spec.quote_currency);
        LoanService::collateral_value_in_quote_currency(amount, spec, mark_price, base_spec, quote_spec)
    }

    // LOAN_CREATE —— 参考文档 §2.1，Java handleLoanCreate（:130-209）

    /// 开仓 Isolated 借贷（参考文档 §2.1，逐字对齐 Java `:141-176`）：字段映射 + cheap→expensive 校验链（spec/enabled/loanId/amount/maxAmount/markPrice/LTV/free-collateral/pool）+ disburse。`LOAN_BORROW` 事件不移植（无事件总线）。
    fn handle_loan_create(
        engine: &mut RiskEngine,
        cmd: &mut OrderCommand,
        ups: &mut UserProfileService,
        ssp: &SymbolSpecificationProvider,
    ) -> CommandResultCode {
        let up = match Self::preamble(cmd, ups) {
            Ok(u) => u,
            Err(rc) => return rc,
        };

        let spec = match ssp.get_symbol(cmd.symbol) {
            Some(s) if s.symbol_type == SymbolType::CurrencyExchangePair => s,
            _ => return CommandResultCode::LoanNotEnabled,
        };
        if !spec.loan_config.is_enabled() {
            return CommandResultCode::LoanNotEnabled;
        }

        let loan_id = cmd.reserve_bid_price;
        if up.isolated_loans.contains_key(&loan_id) {
            return CommandResultCode::LoanAlreadyExists;
        }

        let collateral_amount = cmd.size;
        let principal = cmd.price;
        if principal <= 0 || collateral_amount <= 0 {
            return CommandResultCode::LoanInvalidAmount;
        }
        if spec.loan_config.max_amount != 0 && principal > spec.loan_config.max_amount {
            return CommandResultCode::LoanPrincipalExceedsLimit;
        }

        let mark_price = engine.mark_price(spec.symbol_id).unwrap_or(0);
        if mark_price <= 0 {
            return CommandResultCode::LoanMarkpriceNotReady;
        }

        let collateral_value_in_loan_currency =
            Self::eval_collateral_in_loan_currency(ssp, collateral_amount, spec, mark_price);
        if collateral_value_in_loan_currency < 0 {
            return CommandResultCode::LoanMarkpriceNotReady;
        }
        let lhs = mul_exact(principal, BPS_SCALE);
        let rhs = mul_exact(collateral_value_in_loan_currency, spec.loan_config.initial_ltv_bps as i64);
        if lhs > rhs {
            return CommandResultCode::LoanLtvTooHigh;
        }

        let collateral_currency = spec.base_currency;
        let loan_currency = spec.quote_currency;

        let collateral_currency_spec = ssp
            .get_currency(collateral_currency)
            .unwrap_or_else(|| panic!("currency spec missing for currency {collateral_currency}"));
        let free_collateral_currency = up.account(collateral_currency)
            - engine.calculate_locked(up, collateral_currency, ssp, collateral_currency_spec);
        if free_collateral_currency < collateral_amount {
            return CommandResultCode::LoanCollateralInsufficient;
        }

        let pool_check = engine.loan_service.verify_pool_capacity(loan_currency, principal);
        if pool_check != CommandResultCode::Success {
            return pool_check;
        }

        let rate_mode = if (cmd.user_cookie as i8) == LoanRateMode::Floating.code() {
            LoanRateMode::Floating
        } else {
            LoanRateMode::Locked
        };
        let open_rate_bps = engine.loan_service.open_rate_bps(rate_mode, loan_currency);

        let mut loan = IsolatedLoanRecord::new(
            cmd.uid,
            loan_id,
            spec.symbol_id,
            collateral_currency,
            loan_currency,
            open_rate_bps,
            cmd.timestamp,
        );
        loan.rate_mode = rate_mode;
        if rate_mode == LoanRateMode::Floating {
            // 计息游标锚定在当前累加器（Java `floatingRate.initOpenSnapshot`）。
            engine.loan_service.floating_rate.init_open_snapshot(&mut loan, cmd.timestamp);
        }
        loan.collateral_amount = collateral_amount;
        loan.outstanding_principal = principal;
        up.isolated_loans.insert(loan_id, loan);

        engine.loan_service.disburse_loan(up, loan_currency, principal);

        CommandResultCode::Success
    }

    // LOAN_REPAY —— 参考文档 §2.2，Java handleLoanRepay（:234-271）+ settleRepay（:215-232）

    /// Isolated REPAY 共用核心：accrue→算实抵债额→查余额→抵债（利息优先），对应 Java 私有 `settleRepay`（`:215-232`），不释放抵押。
    fn settle_repay_isolated(
        engine: &mut RiskEngine,
        up: &mut UserProfile,
        loan_id: i64,
        cmd: &OrderCommand,
        ssp: &SymbolSpecificationProvider,
    ) -> CommandResultCode {
        let requested_repay = cmd.price;
        if requested_repay < 0 {
            return CommandResultCode::LoanInvalidAmount;
        }

        // 阶段一：accrue（短暂持有 `&mut loan`，读出 loan_currency/payoff 后立即释放该借用）。
        let (loan_currency, payoff) = {
            let loan = up.isolated_loans.get_mut(&loan_id).expect("loan existence checked by caller");
            engine.loan_service.accrue_to(loan, cmd.timestamp);
            (loan.loan_currency, add_exact(loan.outstanding_principal, loan.accumulated_interest))
        };
        let actual_repay =
            if requested_repay == 0 || requested_repay >= payoff { payoff } else { requested_repay };

        // 阶段二：free-balance 校验需 &UserProfile 整体，此刻上面 &mut loan 借用已结束，二者不重叠。
        let loan_currency_spec = ssp
            .get_currency(loan_currency)
            .unwrap_or_else(|| panic!("currency spec missing for currency {loan_currency}"));
        let free = up.account(loan_currency) - engine.calculate_locked(up, loan_currency, ssp, loan_currency_spec);
        if free < actual_repay {
            return CommandResultCode::LoanAccountInsufficient;
        }

        // 阶段三：抵债——loan 与 &mut up.accounts 是不重叠的直接字段借用，可同时活。
        let loan = up.isolated_loans.get_mut(&loan_id).expect("loan existence checked by caller");
        engine.loan_service.apply_debt_payment(loan, &mut up.accounts, actual_repay);
        CommandResultCode::Success
    }

    /// 偿还 Isolated 借贷（本金+利息）；成功后若 `loan.is_empty()` 从 map 移除；事件缺口同 [`Self::handle_loan_create`]。
    fn handle_loan_repay(
        engine: &mut RiskEngine,
        cmd: &mut OrderCommand,
        ups: &mut UserProfileService,
        ssp: &SymbolSpecificationProvider,
    ) -> CommandResultCode {
        let up = match Self::preamble(cmd, ups) {
            Ok(u) => u,
            Err(rc) => return rc,
        };

        let loan_id = cmd.reserve_bid_price;
        let loan_uid = match up.isolated_loans.get(&loan_id) {
            Some(l) => l.uid,
            None => return CommandResultCode::LoanNotFound,
        };
        if loan_uid != cmd.uid {
            return CommandResultCode::LoanUidMismatch;
        }

        let rc = Self::settle_repay_isolated(engine, up, loan_id, cmd, ssp);
        if rc != CommandResultCode::Success {
            return rc;
        }

        let is_empty = up.isolated_loans.get(&loan_id).map(|l| l.is_empty()).unwrap_or(true);
        if is_empty {
            up.isolated_loans.remove(&loan_id);
        }
        CommandResultCode::Success
    }

    // LOAN_ADD_COLLATERAL —— 参考文档 §2.3，Java handleLoanAddCollateral（:274-310）

    /// 补抵押降 LTV，对应 Java handleLoanAddCollateral（:274-310）：校验存在/归属/amount>0/自由余额充足后 accrue_to 再加抵押；事件缺口同上不发送。
    fn handle_loan_add_collateral(
        engine: &mut RiskEngine,
        cmd: &mut OrderCommand,
        ups: &mut UserProfileService,
        ssp: &SymbolSpecificationProvider,
    ) -> CommandResultCode {
        let up = match Self::preamble(cmd, ups) {
            Ok(u) => u,
            Err(rc) => return rc,
        };

        let loan_id = cmd.reserve_bid_price;
        let (loan_uid, collateral_currency) = match up.isolated_loans.get(&loan_id) {
            Some(l) => (l.uid, l.collateral_currency),
            None => return CommandResultCode::LoanNotFound,
        };
        if loan_uid != cmd.uid {
            return CommandResultCode::LoanUidMismatch;
        }

        let amount = cmd.size;
        if amount <= 0 {
            return CommandResultCode::LoanInvalidAmount;
        }

        let collateral_currency_spec = ssp
            .get_currency(collateral_currency)
            .unwrap_or_else(|| panic!("currency spec missing for currency {collateral_currency}"));
        let free = up.account(collateral_currency)
            - engine.calculate_locked(up, collateral_currency, ssp, collateral_currency_spec);
        if free < amount {
            return CommandResultCode::LoanCollateralInsufficient;
        }

        let loan = up.isolated_loans.get_mut(&loan_id).expect("loan existence checked above");
        engine.loan_service.accrue_to(loan, cmd.timestamp);
        loan.collateral_amount = add_exact(loan.collateral_amount, amount);
        CommandResultCode::Success
    }

    // LOAN_RELEASE_COLLATERAL —— 参考文档 §2.4，Java handleLoanReleaseCollateral（:313-378）

    /// 减抵押，对应 Java handleLoanReleaseCollateral（:313-378）：允许释放到刚好高于清算线（严格 <），accrue 后按 pending-interest-inclusive 的 realDebt 校验 LTV，全零死壳从 map 移除让 loanId 复用；事件缺口同上不发送。
    fn handle_loan_release_collateral(
        engine: &mut RiskEngine,
        cmd: &mut OrderCommand,
        ups: &mut UserProfileService,
        ssp: &SymbolSpecificationProvider,
    ) -> CommandResultCode {
        let up = match Self::preamble(cmd, ups) {
            Ok(u) => u,
            Err(rc) => return rc,
        };

        let loan_id = cmd.reserve_bid_price;
        let (loan_uid, symbol_id, collateral_amount) = match up.isolated_loans.get(&loan_id) {
            Some(l) => (l.uid, l.symbol_id, l.collateral_amount),
            None => return CommandResultCode::LoanNotFound,
        };
        if loan_uid != cmd.uid {
            return CommandResultCode::LoanUidMismatch;
        }

        let amount = cmd.size;
        if amount <= 0 {
            return CommandResultCode::LoanInvalidAmount;
        }
        if amount > collateral_amount {
            return CommandResultCode::LoanCollateralExceedsLoan;
        }

        let spec = match ssp.get_symbol(symbol_id) {
            Some(s) => s,
            None => return CommandResultCode::LoanNotEnabled,
        };
        let mark_price = engine.mark_price(spec.symbol_id).unwrap_or(0);
        if mark_price <= 0 {
            return CommandResultCode::LoanMarkpriceNotReady;
        }

        let loan = up.isolated_loans.get_mut(&loan_id).expect("loan existence checked above");
        engine.loan_service.accrue_to(loan, cmd.timestamp);
        let real_debt =
            add_exact(loan.outstanding_principal, engine.loan_service.calculate_display_interest(loan, cmd.timestamp));
        let new_collateral = loan.collateral_amount - amount;

        if new_collateral == 0 && real_debt > 0 {
            return CommandResultCode::LoanLtvTooHighAfterRelease;
        }
        if new_collateral > 0 {
            let new_collateral_value_in_loan_currency =
                Self::eval_collateral_in_loan_currency(ssp, new_collateral, spec, mark_price);
            if new_collateral_value_in_loan_currency < 0 {
                return CommandResultCode::LoanMarkpriceNotReady;
            }
            let lhs = mul_exact(real_debt, BPS_SCALE);
            let rhs = mul_exact(new_collateral_value_in_loan_currency, spec.loan_config.liquidation_ltv_bps as i64);
            if lhs >= rhs {
                return CommandResultCode::LoanLtvTooHighAfterRelease;
            }
        }

        let loan = up.isolated_loans.get_mut(&loan_id).expect("loan existence checked above");
        loan.collateral_amount = new_collateral;
        let is_empty = loan.is_empty();
        if is_empty {
            up.isolated_loans.remove(&loan_id);
        }
        CommandResultCode::Success
    }

    // Isolated 强平：R1 挂 IOC → R2 结算，接不住转 LIF 接管，参考文档 §2.5，Java LoanCommandDispatcher.java:388-525,921-933

    /// R1：校验 + pre-move 抵押到 exchange_locked 转 spot ASK IOC，对应 Java handleLoanForceLiquidate（:388-417）；不走 [`Self::preamble`]（无视冻结、无 tryClaim），幂等靠 collateral_amount compare-and-consume。
    fn handle_loan_force_liquidate(
        _engine: &mut RiskEngine,
        cmd: &mut OrderCommand,
        ups: &mut UserProfileService,
        ssp: &SymbolSpecificationProvider,
    ) -> CommandResultCode {
        let up = match ups.get_mut(cmd.uid) {
            Some(u) => u,
            None => return CommandResultCode::AuthInvalidUser,
        };

        let loan_id = cmd.reserve_bid_price;
        let (loan_uid, collateral_currency, loan_currency, collateral_amount) = match up.isolated_loans.get(&loan_id) {
            Some(l) => (l.uid, l.collateral_currency, l.loan_currency, l.collateral_amount),
            None => return CommandResultCode::LoanNotFound,
        };
        if loan_uid != cmd.uid {
            return CommandResultCode::LoanUidMismatch;
        }

        let spec = match ssp.get_symbol(cmd.symbol) {
            Some(s)
                if s.symbol_type == SymbolType::CurrencyExchangePair
                    && s.base_currency == collateral_currency
                    && s.quote_currency == loan_currency =>
            {
                s
            }
            _ => return CommandResultCode::LoanNotEnabled,
        };

        let collateral_spec = ssp
            .get_currency(collateral_currency)
            .unwrap_or_else(|| panic!("currency spec missing for currency {collateral_currency}"));
        let sell_amount = LoanService::lots_to_collateral_amount(cmd.size, spec, collateral_spec);
        if sell_amount <= 0 || sell_amount > collateral_amount {
            return CommandResultCode::LoanInvalidAmount;
        }

        up.isolated_loans.get_mut(&loan_id).expect("existence checked above").collateral_amount -= sell_amount;
        up.add_to_locked(collateral_currency, sell_amount);

        cmd.action = Some(OrderAction::Ask);
        cmd.order_type = Some(OrderType::Ioc);
        CommandResultCode::ValidForMatchingEngine
    }

    /// R2：结算完 spot ASK IOC 后调用（owner shard；单 shard 恒真），对应 Java postProcessLoanForceLiquidate（:423-525）：REJECT 回填 collateralAmount，TRADE 所得走 [`LoanService::settle_liquidation_proceeds`]，接不住或抵押成尘埃时由 LIF 承接（[`Self::take_over_by_insurance_fund`]）；三聚合量由调用方非破坏性 peek `cmd.matcher_event` 后传入以替代重新遍历（数值与 Java 逐字一致）；无 FundEvent 事件总线故不发 LOAN_LIQUIDATED，账本状态是权威真相源。
    #[allow(clippy::too_many_arguments)]
    pub fn post_process_loan_force_liquidate(
        engine: &mut RiskEngine,
        cmd: &OrderCommand,
        spec: &CoreSymbolSpecification,
        taker_up: &mut UserProfile,
        ssp: &SymbolSpecificationProvider,
        traded_size: i64,
        traded_notional: i128,
        rejected_size: i64,
    ) {
        let loan_id = cmd.reserve_bid_price;
        let (loan_currency, collateral_currency) = match taker_up.isolated_loans.get(&loan_id) {
            Some(l) => (l.loan_currency, l.collateral_currency),
            // Java: log.error+return，理论不可达（R1 compare-and-consume 幂等设计下不会发生），无日志基础设施静默 no-op。
            None => return,
        };
        let loan_currency_spec = ssp
            .get_currency(loan_currency)
            .unwrap_or_else(|| panic!("currency spec missing for currency {loan_currency}"));
        let base_spec = ssp
            .get_currency(collateral_currency)
            .unwrap_or_else(|| panic!("currency spec missing for currency {collateral_currency}"));

        // ② REJECT 回填：spot handler 已把 exchangeLocked 释放回用户，抵押须归位保守恒。
        if rejected_size > 0 {
            let rejected_in_currency_scale =
                arithmetic::symbol_to_currency_scale(rejected_size, spec.base_scale_k, base_spec.currency_scale_k);
            let loan = taker_up.isolated_loans.get_mut(&loan_id).expect("checked above");
            loan.collateral_amount = add_exact(loan.collateral_amount, rejected_in_currency_scale);
        }

        // ③ TRADE 结算：所得扣 takerFee 后 → 强平费 → 利息 → 本金，overpay 留用户。
        if traded_size > 0 {
            let avg_taker_price = i64::try_from(traded_notional / traded_size as i128)
                .unwrap_or_else(|_| panic!("overflow narrowing avg_taker_price"));
            let taker_fee = arithmetic::calculate_taker_fee(traded_size, avg_taker_price, spec.taker_fee, spec.fee_scale_k);
            let traded_notional_i64 = i64::try_from(traded_notional)
                .unwrap_or_else(|_| panic!("overflow narrowing traded_notional"));
            let received_quote = arithmetic::size_price_to_currency_scale(
                traded_notional_i64 - taker_fee,
                spec.base_scale_k,
                spec.quote_scale_k,
                loan_currency_spec.currency_scale_k,
            );
            let loan = taker_up.isolated_loans.get_mut(&loan_id).expect("checked above");
            engine.loan_service.settle_liquidation_proceeds(loan, &mut taker_up.accounts, received_quote, cmd.timestamp);
        }

        // 全拒路径 settleLiquidationProceeds 未跑过，补计后接管才不漏 pending 利息。
        let loan = taker_up.isolated_loans.get_mut(&loan_id).expect("checked above");
        engine.loan_service.accrue_to(loan, cmd.timestamp);
        let remain_debt = add_exact(loan.outstanding_principal, loan.accumulated_interest);
        // 用"是否还有可卖整张"而非 collateralAmount==0 判定，否则 sub-lot 尘埃会被当成还有救。
        let sellable_lots = LoanService::collateral_amount_to_lots(loan.collateral_amount, spec, base_spec);
        let (principal, interest, collateral) =
            (loan.outstanding_principal, loan.accumulated_interest, loan.collateral_amount);

        // ④ 终态判定：债清关 loan / 接不住转 LIF / 其余保留等下轮。
        if remain_debt > 0 && (traded_size == 0 || sellable_lots == 0) {
            // 全拒或抵押已碎成卖不掉的尘埃而债务仍在 → LIF 承接，避免无限重试。
            Self::take_over_by_insurance_fund(
                engine,
                taker_up,
                principal,
                interest,
                loan_currency,
                collateral_currency,
                collateral,
            );
            taker_up.isolated_loans.remove(&loan_id);
        } else if principal == 0 && interest == 0 && collateral == 0 {
            taker_up.isolated_loans.remove(&loan_id);
        }
        // else：部分成交，loan 原样保留（无事件快照，见方法文档"无事件缺口"）。
    }

    /// LIF 承接不良 Isolated 贷款，对应 Java 私有 takeOverByInsuranceFund（:921-933）：按债务全额代偿、取走全部抵押；LIF 允许为负（垫资非损失），抵押从 accounts 真实划转，是整个借贷子系统唯一的物理资金转移（§6.3/§3.4）。
    fn take_over_by_insurance_fund(
        engine: &mut RiskEngine,
        up: &mut UserProfile,
        principal: i64,
        interest: i64,
        loan_currency: i32,
        collateral_currency: i32,
        collateral: i64,
    ) {
        let debt = add_exact(principal, interest);
        engine.loan_service.add_to_loan_insurance_fund(loan_currency, -debt);
        engine.loan_service.add_to_loan_pool_available(loan_currency, principal);
        engine.loan_service.add_to_loan_pool_borrowed(loan_currency, -principal);
        engine.loan_service.add_to_interest_revenue(loan_currency, interest);
        if collateral > 0 {
            up.add_to_account(collateral_currency, -collateral);
            engine.loan_service.add_to_loan_insurance_fund(collateral_currency, collateral);
        }
    }

    // Cross 用户命令：加减抵押/借款/还款，参考文档 §2.6-2.9，Java LoanCommandDispatcher.java:532-705

    /// Cross 账户级追加抵押（不校验 LTV，越多越安全），对应 Java handleLoanCrossAddCollateral（:532-556）：币种白名单权重>0、自由余额充足后 cross_loan_collateral.add；事件缺口同 Isolated 各 handler。
    fn handle_loan_cross_add_collateral(
        engine: &mut RiskEngine,
        cmd: &mut OrderCommand,
        ups: &mut UserProfileService,
        ssp: &SymbolSpecificationProvider,
    ) -> CommandResultCode {
        let up = match Self::preamble(cmd, ups) {
            Ok(u) => u,
            Err(rc) => return rc,
        };

        let currency = cmd.symbol;
        let amount = cmd.size;
        if amount <= 0 {
            return CommandResultCode::LoanInvalidAmount;
        }
        if LoanService::collateral_weight_for_base(currency, ssp) <= 0 {
            return CommandResultCode::LoanCollateralNotAllowed;
        }

        let currency_spec = ssp
            .get_currency(currency)
            .expect("collateral_weight_for_base>0 implies the currency spec exists");
        let free = up.account(currency) - engine.calculate_locked(up, currency, ssp, currency_spec);
        if free < amount {
            return CommandResultCode::LoanCollateralInsufficient;
        }

        up.add_to_cross_loan_collateral(currency, amount);
        CommandResultCode::Success
    }

    /// Cross 账户级提取抵押，对应 Java handleLoanCrossWithdrawCollateral（:558-587）：subtract-then-check——先扣后重算加权 LTV（fail-closed，缺价拒绝，§3.2），超 crossLiquidationLtvBps 则 revert 加回；事件缺口同上。
    fn handle_loan_cross_withdraw_collateral(
        engine: &mut RiskEngine,
        cmd: &mut OrderCommand,
        ups: &mut UserProfileService,
        ssp: &SymbolSpecificationProvider,
    ) -> CommandResultCode {
        let up = match Self::preamble(cmd, ups) {
            Ok(u) => u,
            Err(rc) => return rc,
        };

        let currency = cmd.symbol;
        let amount = cmd.size;
        if amount <= 0 {
            return CommandResultCode::LoanInvalidAmount;
        }
        if up.cross_loan_collateral(currency) < amount {
            return CommandResultCode::LoanCollateralExceedsLoan;
        }
        if !engine.loan_service.global_config.is_numeraire_configured() {
            return CommandResultCode::LoanNumeraireNotConfigured;
        }

        // subtract-then-check：先扣，重算 LTV 超线再原样加回。
        up.add_to_cross_loan_collateral(currency, -amount);
        let new_ltv =
            engine.loan_service.calculate_cross_account_ltv_bps(up, cmd.timestamp, ssp, &engine.last_price_cache, true);
        if new_ltv >= engine.loan_service.global_config.cross_liquidation_ltv_bps as i64 {
            up.add_to_cross_loan_collateral(currency, amount); // revert
            return CommandResultCode::LoanCrossLtvTooHighAfterWithdraw;
        }
        CommandResultCode::Success
    }

    /// Cross 借款，对应 Java handleLoanCrossBorrow（:589-635）：校验 loanId/principal/spec/池容量后恒 FLOATING 开仓，先插入 cross_loans 再核加权 LTV（fail-closed），超 initialLtvBps 则 remove revert（池子分文未动，无需还池），否则 disburse_loan；事件缺口同上。
    fn handle_loan_cross_borrow(
        engine: &mut RiskEngine,
        cmd: &mut OrderCommand,
        ups: &mut UserProfileService,
        ssp: &SymbolSpecificationProvider,
    ) -> CommandResultCode {
        let up = match Self::preamble(cmd, ups) {
            Ok(u) => u,
            Err(rc) => return rc,
        };

        let loan_id = cmd.reserve_bid_price;
        if up.cross_loans.contains_key(&loan_id) {
            return CommandResultCode::LoanAlreadyExists;
        }

        let symbol_id = cmd.symbol;
        let principal = cmd.price;
        if principal <= 0 {
            return CommandResultCode::LoanInvalidAmount;
        }

        let spec = match ssp.get_symbol(symbol_id) {
            Some(s) if s.symbol_type == SymbolType::CurrencyExchangePair && s.loan_config.is_enabled() => s,
            _ => return CommandResultCode::LoanNotEnabled,
        };
        let loan_currency = spec.quote_currency;
        if spec.loan_config.max_amount != 0 && principal > spec.loan_config.max_amount {
            return CommandResultCode::LoanPrincipalExceedsLimit;
        }

        if !engine.loan_service.global_config.is_numeraire_configured() {
            return CommandResultCode::LoanNumeraireNotConfigured;
        }
        let pool_check = engine.loan_service.verify_pool_capacity(loan_currency, principal);
        if pool_check != CommandResultCode::Success {
            return pool_check;
        }

        // Cross 恒 FLOATING（CrossLoanRecord::is_fixed_rate() 硬编码 false，无 LOCKED 选项）。
        let open_rate_bps = engine.loan_service.floating_rate.open_rate_bps(loan_currency);
        let mut loan =
            CrossLoanRecord::new(cmd.uid, loan_id, spec.symbol_id, loan_currency, open_rate_bps, cmd.timestamp);
        engine.loan_service.floating_rate.init_open_snapshot(&mut loan, cmd.timestamp);
        loan.outstanding_principal = principal;
        up.cross_loans.insert(loan_id, loan);

        let new_ltv =
            engine.loan_service.calculate_cross_account_ltv_bps(up, cmd.timestamp, ssp, &engine.last_price_cache, true);
        if new_ltv > spec.loan_config.initial_ltv_bps as i64 {
            up.cross_loans.remove(&loan_id); // 池子分文未动，见文档"还池"说明；无对象池可还
            return CommandResultCode::LoanLtvTooHighAfterBorrow;
        }

        engine.loan_service.disburse_loan(up, loan_currency, principal);
        CommandResultCode::Success
    }

    /// Cross REPAY 共用核心，对应 Java 共享私有 settleRepay（:215-232）：与 [`Self::settle_repay_isolated`] 逐字同构，改在 up.cross_loans 上操作，因借用检查器需按类型各写一份薄包装，逻辑零分叉，从不释放抵押（Cross 无 per-loan 抵押字段）。
    fn settle_repay_cross(
        engine: &mut RiskEngine,
        up: &mut UserProfile,
        loan_id: i64,
        cmd: &OrderCommand,
        ssp: &SymbolSpecificationProvider,
    ) -> CommandResultCode {
        let requested_repay = cmd.price;
        if requested_repay < 0 {
            return CommandResultCode::LoanInvalidAmount;
        }

        let (loan_currency, payoff) = {
            let loan = up.cross_loans.get_mut(&loan_id).expect("loan existence checked by caller");
            engine.loan_service.accrue_to(loan, cmd.timestamp);
            (loan.loan_currency, add_exact(loan.outstanding_principal, loan.accumulated_interest))
        };
        let actual_repay =
            if requested_repay == 0 || requested_repay >= payoff { payoff } else { requested_repay };

        let loan_currency_spec = ssp
            .get_currency(loan_currency)
            .unwrap_or_else(|| panic!("currency spec missing for currency {loan_currency}"));
        let free = up.account(loan_currency) - engine.calculate_locked(up, loan_currency, ssp, loan_currency_spec);
        if free < actual_repay {
            return CommandResultCode::LoanAccountInsufficient;
        }

        let loan = up.cross_loans.get_mut(&loan_id).expect("loan existence checked by caller");
        engine.loan_service.apply_debt_payment(loan, &mut up.accounts, actual_repay);
        CommandResultCode::Success
    }

    /// 偿还 Cross 借贷（本金+利息），对应 Java handleLoanCrossRepay（:637-664）：永不释放抵押（账户级共享池），is_empty 后从 cross_loans 移除让 loanId 可复用；事件缺口同上。
    fn handle_loan_cross_repay(
        engine: &mut RiskEngine,
        cmd: &mut OrderCommand,
        ups: &mut UserProfileService,
        ssp: &SymbolSpecificationProvider,
    ) -> CommandResultCode {
        let up = match Self::preamble(cmd, ups) {
            Ok(u) => u,
            Err(rc) => return rc,
        };

        let loan_id = cmd.reserve_bid_price;
        let loan_uid = match up.cross_loans.get(&loan_id) {
            Some(l) => l.uid,
            None => return CommandResultCode::LoanNotFound,
        };
        if loan_uid != cmd.uid {
            return CommandResultCode::LoanUidMismatch;
        }

        let rc = Self::settle_repay_cross(engine, up, loan_id, cmd, ssp);
        if rc != CommandResultCode::Success {
            return rc;
        }

        let is_empty = up.cross_loans.get(&loan_id).map(|l| l.is_empty()).unwrap_or(true);
        if is_empty {
            up.cross_loans.remove(&loan_id);
        }
        CommandResultCode::Success
    }

    // Cross 强平：R1 挂 IOC → R2 结算，接不住由 LIF 按债务占比接管，参考文档 §2.10，Java LoanCommandDispatcher.java:715-902

    /// R1：校验 + pre-move 卖出币抵押到 exchange_locked 转 spot ASK IOC，对应 Java handleLoanCrossForceLiquidate（:715-747）：不校验卖出币 base（可为账户抵押池任意合格币种，不像 Isolated 一对一绑定）；同 Isolated 不走 preamble、幂等靠 compare-and-consume（见 [`Self::handle_loan_force_liquidate`]）。
    fn handle_loan_cross_force_liquidate(
        _engine: &mut RiskEngine,
        cmd: &mut OrderCommand,
        ups: &mut UserProfileService,
        ssp: &SymbolSpecificationProvider,
    ) -> CommandResultCode {
        let up = match ups.get_mut(cmd.uid) {
            Some(u) => u,
            None => return CommandResultCode::AuthInvalidUser,
        };

        let target_loan_id = cmd.reserve_bid_price;
        let (target_loan_uid, target_loan_currency) = match up.cross_loans.get(&target_loan_id) {
            Some(l) => (l.uid, l.loan_currency),
            None => return CommandResultCode::LoanNotFound,
        };
        if target_loan_uid != cmd.uid {
            return CommandResultCode::LoanUidMismatch;
        }

        let spec = match ssp.get_symbol(cmd.symbol) {
            Some(s) if s.symbol_type == SymbolType::CurrencyExchangePair && s.quote_currency == target_loan_currency => s,
            _ => return CommandResultCode::LoanNotEnabled,
        };

        let selling_currency = spec.base_currency;
        let available_collateral = up.cross_loan_collateral(selling_currency);

        let selling_currency_spec = ssp
            .get_currency(selling_currency)
            .unwrap_or_else(|| panic!("currency spec missing for currency {selling_currency}"));
        let sell_amount = LoanService::lots_to_collateral_amount(cmd.size, spec, selling_currency_spec);
        if sell_amount <= 0 || sell_amount > available_collateral {
            return CommandResultCode::LoanInvalidAmount;
        }

        up.add_to_cross_loan_collateral(selling_currency, -sell_amount);
        up.add_to_locked(selling_currency, sell_amount);

        cmd.action = Some(OrderAction::Ask);
        cmd.order_type = Some(OrderType::Ioc);
        CommandResultCode::ValidForMatchingEngine
    }

    /// R2：结算完 spot ASK IOC 后调用，对应 Java postProcessLoanCrossForceLiquidate（:753-863）：REJECT 回填账户级 cross_loan_collateral，TRADE 所得走 [`LoanService::settle_liquidation_proceeds`] 偿 targetLoan，抵押结构性耗尽（或全拒）且债务未清则 LIF 按占比承接目标 loan（[`LoanService::take_over_cross_loan`]）及其余 Cross 债务（[`Self::take_over_remaining_cross_loans`]）；三聚合量/fail-closed/无事件说明同 [`Self::post_process_loan_force_liquidate`]。
    #[allow(clippy::too_many_arguments)]
    pub fn post_process_loan_cross_force_liquidate(
        engine: &mut RiskEngine,
        cmd: &OrderCommand,
        spec: &CoreSymbolSpecification,
        taker_up: &mut UserProfile,
        ssp: &SymbolSpecificationProvider,
        traded_size: i64,
        traded_notional: i128,
        rejected_size: i64,
    ) {
        let target_loan_id = cmd.reserve_bid_price;
        let selling_currency = spec.base_currency;
        let loan_currency = match taker_up.cross_loans.get(&target_loan_id) {
            Some(l) => l.loan_currency,
            // Java: log.warn(...) + return —— target loan 在 R2 前已不在（理论不可达）。
            None => return,
        };
        let loan_currency_spec = ssp
            .get_currency(loan_currency)
            .unwrap_or_else(|| panic!("currency spec missing for currency {loan_currency}"));
        let selling_currency_spec = ssp
            .get_currency(selling_currency)
            .unwrap_or_else(|| panic!("currency spec missing for currency {selling_currency}"));

        // ② REJECT 回填：spot handler 已释放 exchangeLocked，抵押归位到账户级抵押池保守恒。
        if rejected_size > 0 {
            let rejected_in_currency_scale =
                arithmetic::symbol_to_currency_scale(rejected_size, spec.base_scale_k, selling_currency_spec.currency_scale_k);
            taker_up.add_to_cross_loan_collateral(selling_currency, rejected_in_currency_scale);
        }

        // ③ TRADE 结算：所得扣 takerFee 后 → 强平费 → 利息 → 本金，overpay 留用户。
        if traded_size > 0 {
            let avg_taker_price = i64::try_from(traded_notional / traded_size as i128)
                .unwrap_or_else(|_| panic!("overflow narrowing avg_taker_price"));
            let taker_fee = arithmetic::calculate_taker_fee(traded_size, avg_taker_price, spec.taker_fee, spec.fee_scale_k);
            let traded_notional_i64 = i64::try_from(traded_notional)
                .unwrap_or_else(|_| panic!("overflow narrowing traded_notional"));
            let received_quote = arithmetic::size_price_to_currency_scale(
                traded_notional_i64 - taker_fee,
                spec.base_scale_k,
                spec.quote_scale_k,
                loan_currency_spec.currency_scale_k,
            );
            let loan = taker_up.cross_loans.get_mut(&target_loan_id).expect("checked above");
            engine.loan_service.settle_liquidation_proceeds(loan, &mut taker_up.accounts, received_quote, cmd.timestamp);
        }

        // 同 Isolated：全拒路径未结算过，补计后再判债务。
        let loan = taker_up.cross_loans.get_mut(&target_loan_id).expect("checked above");
        engine.loan_service.accrue_to(loan, cmd.timestamp);
        let remain_target_debt = add_exact(loan.outstanding_principal, loan.accumulated_interest);

        // ④ 终态判定：只看抵押结构上能否变现（与选币永久性条件同源）；markPrice 未就绪属临时状态不触发接管。
        let currencies: Vec<i32> = taker_up.cross_loan_collateral.keys().copied().collect();
        let mut all_collateral_exhausted = true;
        for currency in currencies {
            let amount = taker_up.cross_loan_collateral(currency);
            if LoanService::is_structurally_sellable(currency, amount, taker_up, ssp) {
                all_collateral_exhausted = false;
                break;
            }
        }

        // 市场按破产价都接不住（全拒），或抵押结构上已无法变现，而债务仍在 → LIF 按债务占比承接。
        if remain_target_debt > 0 && (traded_size == 0 || all_collateral_exhausted) {
            let taken_over =
                engine.loan_service.take_over_cross_loan(taker_up, target_loan_id, cmd.timestamp, ssp, &engine.last_price_cache);
            if taken_over {
                Self::close_and_recycle_cross_loan(taker_up, target_loan_id);
            }
            // else：喂价缺失无法估值 → fail-closed，保留 loan 原样等下一轮（Java 打 warn log）。
        } else {
            let is_empty = {
                let l = taker_up.cross_loans.get(&target_loan_id).expect("checked above");
                l.outstanding_principal == 0 && l.accumulated_interest == 0
            };
            if is_empty {
                taker_up.cross_loans.remove(&target_loan_id);
            }
            // else：部分成交，loan 原样保留（无事件快照）。
        }

        // 抵押结构性耗尽 → 账户其余未偿债务一并由 LIF 承接（按 loanId 升序，见方法文档）。
        if all_collateral_exhausted {
            Self::take_over_remaining_cross_loans(engine, taker_up, cmd.timestamp, target_loan_id, ssp);
        }
        // Java 在此调用 syncCrossExposure（非复制 scanner 索引维护，本仓未移植 LoanLiquidationEngine，§5.2）——跳过，不影响账本结算正确性。
    }

    /// LIF 承接后收尾，对应 Java 私有 closeAndRecycleCrossLoan（:905-910）：无对象池，直接 remove 即等价于清零+摘出+回收；调用后 loan_id 已移除，不可再读。
    fn close_and_recycle_cross_loan(up: &mut UserProfile, loan_id: i64) {
        up.cross_loans.remove(&loan_id);
    }

    /// 抵押结构性耗尽时把账户其余未偿 Cross 债务一并交给 LIF 承接，对应 Java 私有 takeOverRemainingCrossLoans（:873-902）：按 loanId 升序遍历（BTreeMap 天然升序，对齐 Java 显式 sort，必须确定性），跳过 target_loan_id 及 fail-closed 的笔；事件缺口同其余各处说明。
    fn take_over_remaining_cross_loans(
        engine: &mut RiskEngine,
        up: &mut UserProfile,
        now: i64,
        target_loan_id: i64,
        ssp: &SymbolSpecificationProvider,
    ) {
        // 先快照：循环内会 remove。BTreeMap 迭代天然按 loanId 升序，对齐 Java 显式 sort 后的效果。
        let loan_ids: Vec<i64> = up.cross_loans.keys().copied().collect();
        for loan_id in loan_ids {
            if loan_id == target_loan_id {
                continue;
            }
            let should_skip = match up.cross_loans.get(&loan_id) {
                Some(l) => l.outstanding_principal == 0 && l.accumulated_interest == 0,
                None => true,
            };
            if should_skip {
                continue;
            }
            let taken_over = engine.loan_service.take_over_cross_loan(up, loan_id, now, ssp, &engine.last_price_cache);
            if !taken_over {
                // fail-closed：Java 打 warn log，跳过继续下一笔。
                continue;
            }
            Self::close_and_recycle_cross_loan(up, loan_id);
        }
    }

    // 运营命令：借贷池/LIF 充提，参考文档 §2.11，Java handlePoolDeposit/handlePoolWithdraw/handleLoanIfDeposit/handleLoanIfWithdraw（LoanCommandDispatcher.java:940-997）；cmd.uid=shardId（非真实 uid，单 shard 恒真未搬迁）、symbol=currency、size=amount，不复用 preamble、无幂等去重（loan.md §5.1：运营侧不得重放，调用方自保证）。

    /// 对应 Java `handlePoolDeposit`（`:940-951`）：运营方注入池子流动性。
    fn handle_pool_deposit(engine: &mut RiskEngine, cmd: &OrderCommand) -> CommandResultCode {
        if cmd.size <= 0 {
            return CommandResultCode::LoanInvalidAmount;
        }
        engine.loan_service.add_to_loan_pool_available(cmd.symbol, cmd.size);
        *engine.adjustments.entry(cmd.symbol).or_insert(0) -= cmd.size;
        CommandResultCode::Success
    }

    /// 对应 Java handlePoolWithdraw（:953-966）：运营方从池子提取流动性，只能提未借出的 loanPoolAvailable 部分，不足即拒。
    fn handle_pool_withdraw(engine: &mut RiskEngine, cmd: &OrderCommand) -> CommandResultCode {
        if cmd.size <= 0 {
            return CommandResultCode::LoanInvalidAmount;
        }
        if engine.loan_service.get_loan_pool_available(cmd.symbol) < cmd.size {
            return CommandResultCode::LoanPoolInsufficient;
        }
        engine.loan_service.add_to_loan_pool_available(cmd.symbol, -cmd.size);
        *engine.adjustments.entry(cmd.symbol).or_insert(0) += cmd.size;
        CommandResultCode::Success
    }

    /// 对应 Java `handleLoanIfDeposit`（`:968-976`）：运营方给 LIF 注资（启动资金/接管后补仓）。
    fn handle_loan_if_deposit(engine: &mut RiskEngine, cmd: &OrderCommand) -> CommandResultCode {
        if cmd.size <= 0 {
            return CommandResultCode::LoanInvalidAmount;
        }
        engine.loan_service.add_to_loan_insurance_fund(cmd.symbol, cmd.size);
        *engine.adjustments.entry(cmd.symbol).or_insert(0) -= cmd.size;
        CommandResultCode::Success
    }

    /// 对应 Java handleLoanIfWithdraw（:978-997）：运营方从 LIF 提取（场外变现后再 deposit 回来），余额不足即拒——LIF 为负是接管被动结果非运营透支额度，本命令绝不能把它推得更负。
    fn handle_loan_if_withdraw(engine: &mut RiskEngine, cmd: &OrderCommand) -> CommandResultCode {
        if cmd.size <= 0 {
            return CommandResultCode::LoanInvalidAmount;
        }
        if engine.loan_service.get_loan_insurance_fund(cmd.symbol) < cmd.size {
            return CommandResultCode::LoanIfInsufficient;
        }
        engine.loan_service.add_to_loan_insurance_fund(cmd.symbol, -cmd.size);
        *engine.adjustments.entry(cmd.symbol).or_insert(0) += cmd.size;
        CommandResultCode::Success
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::core::common::core_currency_specification::CoreCurrencySpecification;
    use crate::core::common::loan_record::LoanRecord;
    use crate::core::processors::symbol_specification_provider::SymbolSpecificationProvider;

    const BASE: i32 = 1;
    const QUOTE: i32 = 2;
    const SYMBOL: i32 = 100;
    const UID: i64 = 7;

    fn spec_with_loan(initial_ltv_bps: i32, liquidation_ltv_bps: i32, max_amount: i64) -> CoreSymbolSpecification {
        let mut spec = CoreSymbolSpecification {
            symbol_id: SYMBOL,
            symbol_type: SymbolType::CurrencyExchangePair,
            base_currency: BASE,
            quote_currency: QUOTE,
            base_scale_k: 1,
            quote_scale_k: 1,
            ..Default::default()
        };
        spec.loan_config.update(initial_ltv_bps, liquidation_ltv_bps, 0, max_amount, 0);
        spec
    }

    /// 标准治具：spec 挂 50% initialLtv/80% liquidationLtv，markPrice=1，池注资 1_000_000 quote，用户 10_000 base 抵押。
    fn setup() -> (RiskEngine, UserProfileService, SymbolSpecificationProvider) {
        let mut engine = RiskEngine::new();
        engine.last_price_cache.insert(SYMBOL, 1);
        engine.loan_service.add_to_loan_pool_available(QUOTE, 1_000_000);

        let mut ups = UserProfileService::new();
        ups.add_empty_user_profile(UID);
        ups.get_mut(UID).unwrap().add_to_account(BASE, 10_000);

        let mut ssp = SymbolSpecificationProvider::new();
        ssp.add_symbol(spec_with_loan(5_000, 8_000, 0));
        ssp.add_currency(CoreCurrencySpecification { currency: BASE, currency_scale_k: 1, ..Default::default() });
        ssp.add_currency(CoreCurrencySpecification { currency: QUOTE, currency_scale_k: 1, ..Default::default() });

        (engine, ups, ssp)
    }

    fn create_cmd(order_id: i64, loan_id: i64, collateral: i64, principal: i64) -> OrderCommand {
        OrderCommand {
            command: OrderCommandType::LoanCreate,
            order_id,
            uid: UID,
            symbol: SYMBOL,
            size: collateral,
            price: principal,
            reserve_bid_price: loan_id,
            timestamp: 1_000,
            ..Default::default()
        }
    }

    /// 局部守恒断言（前简化版，§6.2）：accounts 总额+poolAvailable+interestRevenue 在借/还前后守恒（loanPoolBorrowed 是 tracker 不参与）。
    fn conserved_quote_total(engine: &RiskEngine, ups: &UserProfileService) -> i64 {
        let accounts_quote: i64 = ups.users.values().map(|u| u.account(QUOTE)).sum();
        accounts_quote + engine.loan_service.get_loan_pool_available(QUOTE) + engine.loan_service.get_interest_revenue(QUOTE)
    }

    // LOAN_CREATE

    #[test]
    fn loan_create_success_disburses_and_records_loan() {
        let (mut engine, mut ups, ssp) = setup();
        let before = conserved_quote_total(&engine, &ups);

        let mut cmd = create_cmd(1, 42, 1_000, 400); // collateralValue=1000*1=1000, principal=400 <= 1000*0.5=500 ok
        let rc = LoanCommandDispatcher::dispatch(&mut engine, &mut cmd, &mut ups, &ssp);

        assert_eq!(rc, CommandResultCode::Success);
        let up = ups.get(UID).unwrap();
        assert_eq!(up.account(QUOTE), 400); // disbursed principal
        assert_eq!(up.account(BASE), 10_000); // collateral is virtual-locked, not physically moved
        let loan = up.isolated_loans.get(&42).unwrap();
        assert_eq!(loan.collateral_amount, 1_000);
        assert_eq!(loan.outstanding_principal, 400);
        assert_eq!(loan.rate_mode, LoanRateMode::Locked); // default cmd.user_cookie=0 != FLOATING sentinel

        assert_eq!(engine.loan_service.get_loan_pool_available(QUOTE), 1_000_000 - 400);
        assert_eq!(engine.loan_service.get_loan_pool_borrowed(QUOTE), 400);
        assert_eq!(conserved_quote_total(&engine, &ups), before); // accounts+pool total unchanged
    }

    #[test]
    fn loan_create_floating_rate_mode_via_user_cookie_low_byte() {
        let (mut engine, mut ups, ssp) = setup();
        engine.loan_service.floating_rate.current_rate_bps.insert(QUOTE, 321);

        let mut cmd = create_cmd(1, 42, 1_000, 400);
        cmd.user_cookie = LoanRateMode::Floating.code() as i32; // low byte == 1 == RATE_MODE_FLOATING
        let rc = LoanCommandDispatcher::dispatch(&mut engine, &mut cmd, &mut ups, &ssp);

        assert_eq!(rc, CommandResultCode::Success);
        let loan = ups.get(UID).unwrap().isolated_loans.get(&42).unwrap();
        assert_eq!(loan.rate_mode, LoanRateMode::Floating);
        assert_eq!(loan.rate_bps, 321); // openRateBps = floating current rate at open
        assert_eq!(loan.acc_snapshot, 0); // init_open_snapshot anchored at cold-start acc=0
    }

    #[test]
    fn loan_create_rejects_auth_invalid_user() {
        let (mut engine, mut ups, ssp) = setup();
        let mut cmd = create_cmd(1, 42, 1_000, 400);
        cmd.uid = 999; // never registered
        assert_eq!(
            LoanCommandDispatcher::dispatch(&mut engine, &mut cmd, &mut ups, &ssp),
            CommandResultCode::AuthInvalidUser
        );
    }

    #[test]
    fn loan_create_rejects_suspended_user() {
        let (mut engine, mut ups, ssp) = setup();
        ups.get_mut(UID).unwrap().user_status = UserStatus::Suspended;
        let mut cmd = create_cmd(1, 42, 1_000, 400);
        assert_eq!(
            LoanCommandDispatcher::dispatch(&mut engine, &mut cmd, &mut ups, &ssp),
            CommandResultCode::LoanUserSuspended
        );
    }

    #[test]
    fn loan_create_idempotent_retry_of_same_order_id_is_rejected() {
        let (mut engine, mut ups, ssp) = setup();
        let mut cmd1 = create_cmd(1, 42, 1_000, 400);
        assert_eq!(LoanCommandDispatcher::dispatch(&mut engine, &mut cmd1, &mut ups, &ssp), CommandResultCode::Success);

        let mut cmd2 = create_cmd(1, 43, 1_000, 400); // same order_id, different loan_id
        assert_eq!(
            LoanCommandDispatcher::dispatch(&mut engine, &mut cmd2, &mut ups, &ssp),
            CommandResultCode::UserMgmtAccountBalanceAdjustmentAlreadyAppliedSame
        );
        assert!(ups.get(UID).unwrap().isolated_loans.get(&43).is_none()); // second loan never opened
    }

    #[test]
    fn loan_create_rejects_not_enabled_when_symbol_missing() {
        let (mut engine, mut ups, ssp) = setup();
        let mut cmd = create_cmd(1, 42, 1_000, 400);
        cmd.symbol = 999; // unregistered symbol
        assert_eq!(
            LoanCommandDispatcher::dispatch(&mut engine, &mut cmd, &mut ups, &ssp),
            CommandResultCode::LoanNotEnabled
        );
    }

    #[test]
    fn loan_create_rejects_not_enabled_when_loan_config_disabled() {
        let (mut engine, mut ups, mut ssp) = setup();
        // Overwrite the symbol with initialLtvBps=0 (disabled).
        ssp.symbols.get_mut(&SYMBOL).unwrap().loan_config.update(0, 0, 0, 0, 0);
        let mut cmd = create_cmd(1, 42, 1_000, 400);
        assert_eq!(
            LoanCommandDispatcher::dispatch(&mut engine, &mut cmd, &mut ups, &ssp),
            CommandResultCode::LoanNotEnabled
        );
    }

    #[test]
    fn loan_create_rejects_already_exists() {
        let (mut engine, mut ups, ssp) = setup();
        let mut cmd1 = create_cmd(1, 42, 1_000, 400);
        assert_eq!(LoanCommandDispatcher::dispatch(&mut engine, &mut cmd1, &mut ups, &ssp), CommandResultCode::Success);

        let mut cmd2 = create_cmd(2, 42, 1_000, 400); // same loan_id, new order_id
        assert_eq!(
            LoanCommandDispatcher::dispatch(&mut engine, &mut cmd2, &mut ups, &ssp),
            CommandResultCode::LoanAlreadyExists
        );
    }

    #[test]
    fn loan_create_rejects_invalid_amount() {
        let (mut engine, mut ups, ssp) = setup();
        let mut cmd = create_cmd(1, 42, 0, 400); // collateral<=0
        assert_eq!(
            LoanCommandDispatcher::dispatch(&mut engine, &mut cmd, &mut ups, &ssp),
            CommandResultCode::LoanInvalidAmount
        );
        let mut cmd2 = create_cmd(2, 43, 1_000, 0); // principal<=0
        assert_eq!(
            LoanCommandDispatcher::dispatch(&mut engine, &mut cmd2, &mut ups, &ssp),
            CommandResultCode::LoanInvalidAmount
        );
    }

    #[test]
    fn loan_create_rejects_principal_exceeds_limit() {
        let (mut engine, mut ups, mut ssp) = setup();
        ssp.symbols.get_mut(&SYMBOL).unwrap().loan_config.update(5_000, 8_000, 0, 100, 0); // maxAmount=100
        let mut cmd = create_cmd(1, 42, 1_000, 200); // principal 200 > maxAmount 100
        assert_eq!(
            LoanCommandDispatcher::dispatch(&mut engine, &mut cmd, &mut ups, &ssp),
            CommandResultCode::LoanPrincipalExceedsLimit
        );
    }

    #[test]
    fn loan_create_rejects_markprice_not_ready() {
        let (mut engine, mut ups, ssp) = setup();
        engine.last_price_cache.remove(&SYMBOL);
        let mut cmd = create_cmd(1, 42, 1_000, 400);
        assert_eq!(
            LoanCommandDispatcher::dispatch(&mut engine, &mut cmd, &mut ups, &ssp),
            CommandResultCode::LoanMarkpriceNotReady
        );
    }

    #[test]
    fn loan_create_rejects_ltv_too_high() {
        let (mut engine, mut ups, ssp) = setup();
        // collateralValue = 1000*1 = 1000; initialLtv=50% -> max principal 500. Ask for 501.
        let mut cmd = create_cmd(1, 42, 1_000, 501);
        assert_eq!(
            LoanCommandDispatcher::dispatch(&mut engine, &mut cmd, &mut ups, &ssp),
            CommandResultCode::LoanLtvTooHigh
        );
    }

    #[test]
    fn loan_create_rejects_collateral_insufficient() {
        let (mut engine, mut ups, ssp) = setup();
        // 用户仅有 10_000 base，质押 20_000 超出持仓（LTV 本身按比例仍会通过）。
        let mut cmd = create_cmd(1, 42, 20_000, 1_000);
        assert_eq!(
            LoanCommandDispatcher::dispatch(&mut engine, &mut cmd, &mut ups, &ssp),
            CommandResultCode::LoanCollateralInsufficient
        );
    }

    #[test]
    fn loan_create_rejects_pool_insufficient() {
        let (mut engine, mut ups, ssp) = setup();
        engine.loan_service.loan_pool_available.insert(QUOTE, 100); // far less than principal
        let mut cmd = create_cmd(1, 42, 1_000, 400);
        assert_eq!(
            LoanCommandDispatcher::dispatch(&mut engine, &mut cmd, &mut ups, &ssp),
            CommandResultCode::LoanPoolInsufficient
        );
    }

    #[test]
    fn loan_create_rejects_pool_utilization_exceeded() {
        let (mut engine, mut ups, ssp) = setup();
        engine.loan_service.loan_pool_available.insert(QUOTE, 1_000);
        engine.loan_service.loan_pool_borrowed.insert(QUOTE, 8_000); // total=9000, cap=90%
        let mut cmd = create_cmd(1, 42, 1_000, 200); // newBorrowed=8200 -> util 91.1% > 90%
        assert_eq!(
            LoanCommandDispatcher::dispatch(&mut engine, &mut cmd, &mut ups, &ssp),
            CommandResultCode::LoanPoolUtilizationExceeded
        );
    }

    // LOAN_REPAY

    fn repay_cmd(order_id: i64, loan_id: i64, repay_amount: i64, ts: i64) -> OrderCommand {
        OrderCommand {
            command: OrderCommandType::LoanRepay,
            order_id,
            uid: UID,
            reserve_bid_price: loan_id,
            price: repay_amount,
            timestamp: ts,
            ..Default::default()
        }
    }

    /// 开一笔标准 LOCKED 贷款供 REPAY/ADD/RELEASE 测试复用；rate_bps_seed 同时清零 floating_rate.base_bps，使 loan.rate_bps 精确等于 seed，不被默认 base_bps=200 干扰。
    fn open_loan(engine: &mut RiskEngine, ups: &mut UserProfileService, ssp: &SymbolSpecificationProvider, loan_id: i64, collateral: i64, principal: i64, rate_bps_seed: Option<i32>) {
        if let Some(rate) = rate_bps_seed {
            engine.loan_service.floating_rate.base_bps = 0;
            engine.loan_service.fixed_rate.locked_rate_adjust_bps = rate;
        }
        let mut cmd = create_cmd(loan_id, loan_id, collateral, principal);
        assert_eq!(LoanCommandDispatcher::dispatch(engine, &mut cmd, ups, ssp), CommandResultCode::Success);
    }

    #[test]
    fn loan_repay_interest_first_then_principal() {
        let (mut engine, mut ups, ssp) = setup();
        // 50% annual rate -> after 1 year, interest on 400 principal = 200.
        open_loan(&mut engine, &mut ups, &ssp, 42, 1_000, 400, Some(5_000));
        assert_eq!(ups.get(UID).unwrap().isolated_loans.get(&42).unwrap().rate_bps, 5_000);

        let mut cmd = repay_cmd(2, 42, 100, 1_000 + crate::core::processors::loan::loan_service::YEAR_MS);
        let rc = LoanCommandDispatcher::dispatch(&mut engine, &mut cmd, &mut ups, &ssp);
        assert_eq!(rc, CommandResultCode::Success);

        let loan = ups.get(UID).unwrap().isolated_loans.get(&42).unwrap();
        // interest accrued = 200 (50% of 400). repay=100 all goes to interest, none to principal.
        assert_eq!(loan.accumulated_interest, 100);
        assert_eq!(loan.outstanding_principal, 400);
        assert_eq!(loan.cum_interest_paid, 100);
        assert_eq!(engine.loan_service.get_interest_revenue(QUOTE), 100);
        assert_eq!(engine.loan_service.get_loan_pool_available(QUOTE), 1_000_000 - 400); // no principal repaid yet
    }

    /// repayAmount=0 结清本息但不释放抵押，loan 只有在债务先清零、再经 LOAN_RELEASE_COLLATERAL 后才会变空（见 loan_release_collateral_full_release_allowed_when_debt_fully_repaid_first）；本测试只验债务清零这一半。
    #[test]
    fn loan_repay_zero_means_full_payoff_but_collateral_survives_until_released() {
        let (mut engine, mut ups, ssp) = setup();
        open_loan(&mut engine, &mut ups, &ssp, 42, 1_000, 400, Some(5_000));
        let before = conserved_quote_total(&engine, &ups);

        // fund the user so they can afford the full payoff (principal + 1yr interest = 400+200=600)
        ups.get_mut(UID).unwrap().add_to_account(QUOTE, 600);

        let mut cmd = repay_cmd(2, 42, 0, 1_000 + crate::core::processors::loan::loan_service::YEAR_MS); // 0 = full payoff
        let rc = LoanCommandDispatcher::dispatch(&mut engine, &mut cmd, &mut ups, &ssp);
        assert_eq!(rc, CommandResultCode::Success);

        let loan = ups.get(UID).unwrap().isolated_loans.get(&42).expect("collateral still pledged, loan stays");
        assert_eq!(loan.outstanding_principal, 0);
        assert_eq!(loan.accumulated_interest, 0);
        assert_eq!(loan.collateral_amount, 1_000); // untouched by REPAY
        assert_eq!(engine.loan_service.get_loan_pool_available(QUOTE), 1_000_000); // principal fully returned
        assert_eq!(engine.loan_service.get_interest_revenue(QUOTE), 200);
        assert_eq!(conserved_quote_total(&engine, &ups), before + 600); // +600 external funding, otherwise conserved
    }

    #[test]
    fn loan_repay_rejects_not_found() {
        let (mut engine, mut ups, ssp) = setup();
        let mut cmd = repay_cmd(1, 999, 0, 1_000);
        assert_eq!(
            LoanCommandDispatcher::dispatch(&mut engine, &mut cmd, &mut ups, &ssp),
            CommandResultCode::LoanNotFound
        );
    }

    /// LOAN_UID_MISMATCH 是纵深防御检查：正常路径下 loan 必与其所在 map 的 uid 一致，只能靠直接嫁接外来 uid 记录（模拟 Java 对象池复用 bug）触发，用于证明 dispatcher 信任 loan.uid 而非仅 map 归属。
    #[test]
    fn loan_repay_rejects_uid_mismatch() {
        let (mut engine, mut ups, ssp) = setup();
        let mut foreign_loan = IsolatedLoanRecord::new(999, 42, SYMBOL, BASE, QUOTE, 0, 1_000);
        foreign_loan.outstanding_principal = 100;
        ups.get_mut(UID).unwrap().isolated_loans.insert(42, foreign_loan);

        let mut cmd = repay_cmd(2, 42, 0, 1_000); // cmd.uid = UID, but loan.uid = 999
        assert_eq!(
            LoanCommandDispatcher::dispatch(&mut engine, &mut cmd, &mut ups, &ssp),
            CommandResultCode::LoanUidMismatch
        );
    }

    #[test]
    fn loan_repay_rejects_invalid_amount_when_negative() {
        let (mut engine, mut ups, ssp) = setup();
        open_loan(&mut engine, &mut ups, &ssp, 42, 1_000, 400, None);
        let mut cmd = repay_cmd(2, 42, -1, 1_000);
        assert_eq!(
            LoanCommandDispatcher::dispatch(&mut engine, &mut cmd, &mut ups, &ssp),
            CommandResultCode::LoanInvalidAmount
        );
    }

    #[test]
    fn loan_repay_rejects_account_insufficient() {
        let (mut engine, mut ups, ssp) = setup();
        open_loan(&mut engine, &mut ups, &ssp, 42, 1_000, 400, None); // disburses 400 quote
        // 模拟资金已花掉，余额仅 50；请求偿还 200（< payoff，不会被封顶到全额）-> free(50) < actualRepay(200)。
        ups.get_mut(UID).unwrap().accounts.insert(QUOTE, 50);
        let mut cmd = repay_cmd(2, 42, 200, 1_000);
        assert_eq!(
            LoanCommandDispatcher::dispatch(&mut engine, &mut cmd, &mut ups, &ssp),
            CommandResultCode::LoanAccountInsufficient
        );
    }

    // LOAN_ADD_COLLATERAL

    fn add_collateral_cmd(order_id: i64, loan_id: i64, amount: i64) -> OrderCommand {
        OrderCommand {
            command: OrderCommandType::LoanAddCollateral,
            order_id,
            uid: UID,
            reserve_bid_price: loan_id,
            size: amount,
            timestamp: 2_000,
            ..Default::default()
        }
    }

    #[test]
    fn loan_add_collateral_increases_amount_and_accrues() {
        let (mut engine, mut ups, ssp) = setup();
        open_loan(&mut engine, &mut ups, &ssp, 42, 1_000, 400, Some(5_000));

        let mut cmd = add_collateral_cmd(2, 42, 500);
        let rc = LoanCommandDispatcher::dispatch(&mut engine, &mut cmd, &mut ups, &ssp);
        assert_eq!(rc, CommandResultCode::Success);

        let loan = ups.get(UID).unwrap().isolated_loans.get(&42).unwrap();
        assert_eq!(loan.collateral_amount, 1_500);
    }

    #[test]
    fn loan_add_collateral_rejects_not_found_and_invalid_amount() {
        let (mut engine, mut ups, ssp) = setup();
        open_loan(&mut engine, &mut ups, &ssp, 42, 1_000, 400, None);

        let mut not_found = add_collateral_cmd(2, 999, 100);
        assert_eq!(
            LoanCommandDispatcher::dispatch(&mut engine, &mut not_found, &mut ups, &ssp),
            CommandResultCode::LoanNotFound
        );

        let mut bad_amount = add_collateral_cmd(3, 42, 0);
        assert_eq!(
            LoanCommandDispatcher::dispatch(&mut engine, &mut bad_amount, &mut ups, &ssp),
            CommandResultCode::LoanInvalidAmount
        );
    }

    /// 同 loan_repay_rejects_uid_mismatch：只能靠直接嫁接外来 uid 记录触发。
    #[test]
    fn loan_add_collateral_rejects_uid_mismatch() {
        let (mut engine, mut ups, ssp) = setup();
        let foreign_loan = IsolatedLoanRecord::new(999, 42, SYMBOL, BASE, QUOTE, 0, 1_000);
        ups.get_mut(UID).unwrap().isolated_loans.insert(42, foreign_loan);

        let mut mismatch = add_collateral_cmd(4, 42, 100);
        assert_eq!(
            LoanCommandDispatcher::dispatch(&mut engine, &mut mismatch, &mut ups, &ssp),
            CommandResultCode::LoanUidMismatch
        );
    }

    #[test]
    fn loan_add_collateral_rejects_insufficient_free_balance() {
        let (mut engine, mut ups, ssp) = setup();
        open_loan(&mut engine, &mut ups, &ssp, 42, 9_000, 400, None); // uses 9000 of the 10000 base
        let mut cmd = add_collateral_cmd(2, 42, 2_000); // only 1000 free left
        assert_eq!(
            LoanCommandDispatcher::dispatch(&mut engine, &mut cmd, &mut ups, &ssp),
            CommandResultCode::LoanCollateralInsufficient
        );
    }

    // LOAN_RELEASE_COLLATERAL

    fn release_cmd(order_id: i64, loan_id: i64, amount: i64, ts: i64) -> OrderCommand {
        OrderCommand {
            command: OrderCommandType::LoanReleaseCollateral,
            order_id,
            uid: UID,
            reserve_bid_price: loan_id,
            size: amount,
            timestamp: ts,
            ..Default::default()
        }
    }

    #[test]
    fn loan_release_collateral_allows_down_to_just_above_liquidation_line() {
        let (mut engine, mut ups, ssp) = setup();
        // principal=400, liquidationLtv=80% -> collateral value must stay > 400/0.8 = 500.
        open_loan(&mut engine, &mut ups, &ssp, 42, 1_000, 400, None);

        // release 499 -> newCollateral=501，lhs(realDebt*10000)=4_000_000 < rhs(newCollateralValue*8000)=4_008_000 -> allowed。
        let mut cmd = release_cmd(2, 42, 499, 2_000);
        assert_eq!(LoanCommandDispatcher::dispatch(&mut engine, &mut cmd, &mut ups, &ssp), CommandResultCode::Success);
        assert_eq!(ups.get(UID).unwrap().isolated_loans.get(&42).unwrap().collateral_amount, 501);
    }

    #[test]
    fn loan_release_collateral_rejects_at_or_below_liquidation_line() {
        let (mut engine, mut ups, ssp) = setup();
        open_loan(&mut engine, &mut ups, &ssp, 42, 1_000, 400, None);

        // release 500 -> newCollateral=500，lhs=rhs=4_000_000 -> lhs>=rhs -> rejected（要求严格 <）。
        let mut cmd = release_cmd(2, 42, 500, 2_000);
        assert_eq!(
            LoanCommandDispatcher::dispatch(&mut engine, &mut cmd, &mut ups, &ssp),
            CommandResultCode::LoanLtvTooHighAfterRelease
        );
        // loan untouched by the rejected attempt
        assert_eq!(ups.get(UID).unwrap().isolated_loans.get(&42).unwrap().collateral_amount, 1_000);
    }

    #[test]
    fn loan_release_collateral_rejects_new_collateral_zero_with_remaining_debt() {
        let (mut engine, mut ups, ssp) = setup();
        open_loan(&mut engine, &mut ups, &ssp, 42, 1_000, 400, None);

        let mut cmd = release_cmd(2, 42, 1_000, 2_000); // releasing everything while principal remains
        assert_eq!(
            LoanCommandDispatcher::dispatch(&mut engine, &mut cmd, &mut ups, &ssp),
            CommandResultCode::LoanLtvTooHighAfterRelease
        );
    }

    #[test]
    fn loan_release_collateral_full_release_allowed_when_debt_fully_repaid_first() {
        let (mut engine, mut ups, ssp) = setup();
        open_loan(&mut engine, &mut ups, &ssp, 42, 1_000, 400, None);
        ups.get_mut(UID).unwrap().add_to_account(QUOTE, 400);
        let mut repay = repay_cmd(2, 42, 0, 2_000);
        assert_eq!(LoanCommandDispatcher::dispatch(&mut engine, &mut repay, &mut ups, &ssp), CommandResultCode::Success);
        // 本息清零但 collateral_amount 仍为 1000，loan 尚未被移除；接下来全额释放抵押。
        assert!(ups.get(UID).unwrap().isolated_loans.get(&42).is_some());

        let mut cmd = release_cmd(3, 42, 1_000, 3_000);
        assert_eq!(LoanCommandDispatcher::dispatch(&mut engine, &mut cmd, &mut ups, &ssp), CommandResultCode::Success);
        assert!(ups.get(UID).unwrap().isolated_loans.get(&42).is_none()); // empty shell recycled
    }

    #[test]
    fn loan_release_collateral_rejects_exceeds_loan_and_not_found() {
        let (mut engine, mut ups, ssp) = setup();
        open_loan(&mut engine, &mut ups, &ssp, 42, 1_000, 400, None);

        let mut exceeds = release_cmd(2, 42, 1_001, 2_000);
        assert_eq!(
            LoanCommandDispatcher::dispatch(&mut engine, &mut exceeds, &mut ups, &ssp),
            CommandResultCode::LoanCollateralExceedsLoan
        );

        let mut not_found = release_cmd(3, 999, 1, 2_000);
        assert_eq!(
            LoanCommandDispatcher::dispatch(&mut engine, &mut not_found, &mut ups, &ssp),
            CommandResultCode::LoanNotFound
        );
    }

    /// 同 loan_repay_rejects_uid_mismatch：只能靠直接嫁接外来 uid 记录触发。
    #[test]
    fn loan_release_collateral_rejects_uid_mismatch() {
        let (mut engine, mut ups, ssp) = setup();
        let mut foreign_loan = IsolatedLoanRecord::new(999, 42, SYMBOL, BASE, QUOTE, 0, 1_000);
        foreign_loan.collateral_amount = 100;
        ups.get_mut(UID).unwrap().isolated_loans.insert(42, foreign_loan);

        let mut mismatch = release_cmd(4, 42, 1, 2_000);
        assert_eq!(
            LoanCommandDispatcher::dispatch(&mut engine, &mut mismatch, &mut ups, &ssp),
            CommandResultCode::LoanUidMismatch
        );
    }

    // LOAN_CROSS_ADD_COLLATERAL / WITHDRAW / BORROW / REPAY —— 参考文档 §2.6-2.9

    /// 与 `loan_service.rs` 的分歧测试同一个权重，好让两处数字互相印证。
    const CROSS_COLLATERAL_WEIGHT_BPS: i32 = 5_000; // 50%

    /// 在 [`setup`] 基础上给 BASE 挂非零 collateral_weight_bps，但不配置 numeraire，供"numeraire 未配置"系列测试用。
    fn cross_setup_weight_only() -> (RiskEngine, UserProfileService, SymbolSpecificationProvider) {
        let (engine, ups, mut ssp) = setup();
        ssp.currencies.get_mut(&BASE).unwrap().collateral_weight_bps = CROSS_COLLATERAL_WEIGHT_BPS;
        (engine, ups, ssp)
    }

    /// 在 [`cross_setup_weight_only`] 基础上配置 numeraire_currency=QUOTE，使 debt 折算恒等，标准治具供 BORROW/WITHDRAW/REPAY 测试复用。
    fn cross_setup() -> (RiskEngine, UserProfileService, SymbolSpecificationProvider) {
        let (mut engine, ups, ssp) = cross_setup_weight_only();
        engine.loan_service.global_config.numeraire_currency = QUOTE;
        (engine, ups, ssp)
    }

    fn cross_add_cmd(order_id: i64, currency: i32, amount: i64) -> OrderCommand {
        OrderCommand {
            command: OrderCommandType::LoanCrossAddCollateral,
            order_id,
            uid: UID,
            symbol: currency,
            size: amount,
            timestamp: 1_000,
            ..Default::default()
        }
    }

    fn cross_withdraw_cmd(order_id: i64, currency: i32, amount: i64, ts: i64) -> OrderCommand {
        OrderCommand {
            command: OrderCommandType::LoanCrossWithdrawCollateral,
            order_id,
            uid: UID,
            symbol: currency,
            size: amount,
            timestamp: ts,
            ..Default::default()
        }
    }

    fn cross_borrow_cmd(order_id: i64, loan_id: i64, symbol_id: i32, principal: i64, ts: i64) -> OrderCommand {
        OrderCommand {
            command: OrderCommandType::LoanCrossBorrow,
            order_id,
            uid: UID,
            symbol: symbol_id,
            price: principal,
            reserve_bid_price: loan_id,
            timestamp: ts,
            ..Default::default()
        }
    }

    fn cross_repay_cmd(order_id: i64, loan_id: i64, repay_amount: i64, ts: i64) -> OrderCommand {
        OrderCommand {
            command: OrderCommandType::LoanCrossRepay,
            order_id,
            uid: UID,
            reserve_bid_price: loan_id,
            price: repay_amount,
            timestamp: ts,
            ..Default::default()
        }
    }

    fn pledge_cross_collateral(
        engine: &mut RiskEngine,
        ups: &mut UserProfileService,
        ssp: &SymbolSpecificationProvider,
        order_id: i64,
        amount: i64,
    ) {
        let mut cmd = cross_add_cmd(order_id, BASE, amount);
        assert_eq!(LoanCommandDispatcher::dispatch(engine, &mut cmd, ups, ssp), CommandResultCode::Success);
    }

    /// 开一笔标准 Cross 借贷（SYMBOL, loanCurrency=QUOTE）。`order_id` 复用 `loan_id`。
    fn open_cross_loan(
        engine: &mut RiskEngine,
        ups: &mut UserProfileService,
        ssp: &SymbolSpecificationProvider,
        loan_id: i64,
        principal: i64,
        ts: i64,
    ) {
        let mut cmd = cross_borrow_cmd(loan_id, loan_id, SYMBOL, principal, ts);
        assert_eq!(LoanCommandDispatcher::dispatch(engine, &mut cmd, ups, ssp), CommandResultCode::Success);
    }

    // LOAN_CROSS_ADD_COLLATERAL

    #[test]
    fn cross_add_collateral_credits_account_level_pool_when_weight_positive() {
        let (mut engine, mut ups, ssp) = cross_setup();
        let mut cmd = cross_add_cmd(1, BASE, 1_000);
        assert_eq!(LoanCommandDispatcher::dispatch(&mut engine, &mut cmd, &mut ups, &ssp), CommandResultCode::Success);

        let up = ups.get(UID).unwrap();
        assert_eq!(up.cross_loan_collateral(BASE), 1_000);
        assert_eq!(up.account(BASE), 10_000); // virtual lock: never physically moved out of accounts
    }

    #[test]
    fn cross_add_collateral_rejects_currency_with_zero_collateral_weight() {
        let (mut engine, mut ups, ssp) = cross_setup(); // only BASE has non-zero weight; QUOTE stays 0
        let mut cmd = cross_add_cmd(1, QUOTE, 100);
        assert_eq!(
            LoanCommandDispatcher::dispatch(&mut engine, &mut cmd, &mut ups, &ssp),
            CommandResultCode::LoanCollateralNotAllowed
        );
        assert_eq!(ups.get(UID).unwrap().cross_loan_collateral(QUOTE), 0); // nothing credited
    }

    #[test]
    fn cross_add_collateral_rejects_invalid_amount_and_insufficient_balance() {
        let (mut engine, mut ups, ssp) = cross_setup();
        let mut zero = cross_add_cmd(1, BASE, 0);
        assert_eq!(
            LoanCommandDispatcher::dispatch(&mut engine, &mut zero, &mut ups, &ssp),
            CommandResultCode::LoanInvalidAmount
        );

        let mut too_much = cross_add_cmd(2, BASE, 20_000); // user only has 10_000 BASE
        assert_eq!(
            LoanCommandDispatcher::dispatch(&mut engine, &mut too_much, &mut ups, &ssp),
            CommandResultCode::LoanCollateralInsufficient
        );
    }

    // LOAN_CROSS_BORROW

    #[test]
    fn cross_borrow_rejects_numeraire_not_configured() {
        let (mut engine, mut ups, ssp) = cross_setup_weight_only(); // numeraire left unset
        let mut cmd = cross_borrow_cmd(1, 42, SYMBOL, 200, 1_000);
        assert_eq!(
            LoanCommandDispatcher::dispatch(&mut engine, &mut cmd, &mut ups, &ssp),
            CommandResultCode::LoanNumeraireNotConfigured
        );
        assert!(ups.get(UID).unwrap().cross_loans.is_empty());
    }

    #[test]
    fn cross_borrow_success_is_always_floating_and_disburses() {
        let (mut engine, mut ups, ssp) = cross_setup();
        engine.loan_service.floating_rate.current_rate_bps.insert(QUOTE, 321);
        pledge_cross_collateral(&mut engine, &mut ups, &ssp, 1, 1_000); // weighted collateral = 500

        // debt=200 (loanCurrency==numeraire, no conversion) -> ltv = 200*10000/500 = 4000 <= initialLtv(5000).
        open_cross_loan(&mut engine, &mut ups, &ssp, 42, 200, 1_000);

        let up = ups.get(UID).unwrap();
        let loan = up.cross_loans.get(&42).unwrap();
        assert!(!loan.is_fixed_rate()); // Cross 恒 FLOATING，无 LOCKED 选项
        assert_eq!(loan.rate_bps, 321); // openRateBps = floating current rate at open, not a fixed-rate spread
        assert_eq!(loan.outstanding_principal, 200);
        assert_eq!(loan.loan_currency, QUOTE);
        assert_eq!(up.account(QUOTE), 200); // disbursed
        assert_eq!(engine.loan_service.get_loan_pool_available(QUOTE), 1_000_000 - 200);
        assert_eq!(engine.loan_service.get_loan_pool_borrowed(QUOTE), 200);
    }

    #[test]
    fn cross_borrow_rolls_back_on_ltv_too_high_after_borrow_without_touching_pool() {
        let (mut engine, mut ups, ssp) = cross_setup();
        pledge_cross_collateral(&mut engine, &mut ups, &ssp, 1, 100); // weighted collateral = 50

        // debt=200 -> ltv = 200*10000/50 = 40000 >> initialLtv(5000) -> must roll back.
        let mut cmd = cross_borrow_cmd(2, 42, SYMBOL, 200, 1_000);
        assert_eq!(
            LoanCommandDispatcher::dispatch(&mut engine, &mut cmd, &mut ups, &ssp),
            CommandResultCode::LoanLtvTooHighAfterBorrow
        );

        let up = ups.get(UID).unwrap();
        assert!(up.cross_loans.get(&42).is_none()); // record removed, not left dangling
        assert_eq!(up.account(QUOTE), 0); // disburse_loan never ran
        assert_eq!(engine.loan_service.get_loan_pool_available(QUOTE), 1_000_000); // pool untouched
        assert_eq!(engine.loan_service.get_loan_pool_borrowed(QUOTE), 0);
    }

    #[test]
    fn cross_borrow_rejects_already_exists_not_enabled_and_principal_exceeds_limit() {
        let (mut engine, mut ups, ssp) = cross_setup();
        pledge_cross_collateral(&mut engine, &mut ups, &ssp, 1, 1_000);
        open_cross_loan(&mut engine, &mut ups, &ssp, 42, 200, 1_000);

        let mut dup = cross_borrow_cmd(2, 42, SYMBOL, 100, 1_000); // same loan_id
        assert_eq!(
            LoanCommandDispatcher::dispatch(&mut engine, &mut dup, &mut ups, &ssp),
            CommandResultCode::LoanAlreadyExists
        );

        let mut bad_symbol = cross_borrow_cmd(3, 43, 999, 100, 1_000);
        assert_eq!(
            LoanCommandDispatcher::dispatch(&mut engine, &mut bad_symbol, &mut ups, &ssp),
            CommandResultCode::LoanNotEnabled
        );

        let mut invalid_principal = cross_borrow_cmd(4, 44, SYMBOL, 0, 1_000);
        assert_eq!(
            LoanCommandDispatcher::dispatch(&mut engine, &mut invalid_principal, &mut ups, &ssp),
            CommandResultCode::LoanInvalidAmount
        );
    }

    #[test]
    fn cross_borrow_rejects_principal_exceeds_limit_and_pool_insufficient() {
        let (mut engine, mut ups, mut ssp) = cross_setup();
        pledge_cross_collateral(&mut engine, &mut ups, &ssp, 1, 1_000);
        ssp.symbols.get_mut(&SYMBOL).unwrap().loan_config.update(5_000, 8_000, 0, 50, 0); // maxAmount=50
        let mut too_big = cross_borrow_cmd(2, 42, SYMBOL, 100, 1_000);
        assert_eq!(
            LoanCommandDispatcher::dispatch(&mut engine, &mut too_big, &mut ups, &ssp),
            CommandResultCode::LoanPrincipalExceedsLimit
        );

        ssp.symbols.get_mut(&SYMBOL).unwrap().loan_config.update(5_000, 8_000, 0, 0, 0); // remove cap
        engine.loan_service.loan_pool_available.insert(QUOTE, 10); // far less than principal
        let mut pool_short = cross_borrow_cmd(3, 43, SYMBOL, 100, 1_000);
        assert_eq!(
            LoanCommandDispatcher::dispatch(&mut engine, &mut pool_short, &mut ups, &ssp),
            CommandResultCode::LoanPoolInsufficient
        );
    }

    // LOAN_CROSS_WITHDRAW_COLLATERAL

    #[test]
    fn cross_withdraw_collateral_rejects_invalid_amount_and_exceeds_pledged() {
        let (mut engine, mut ups, ssp) = cross_setup();
        let mut zero = cross_withdraw_cmd(1, BASE, 0, 1_000);
        assert_eq!(
            LoanCommandDispatcher::dispatch(&mut engine, &mut zero, &mut ups, &ssp),
            CommandResultCode::LoanInvalidAmount
        );

        let mut exceeds = cross_withdraw_cmd(2, BASE, 1, 1_000); // nothing pledged yet
        assert_eq!(
            LoanCommandDispatcher::dispatch(&mut engine, &mut exceeds, &mut ups, &ssp),
            CommandResultCode::LoanCollateralExceedsLoan
        );
    }

    #[test]
    fn cross_withdraw_collateral_rejects_numeraire_not_configured() {
        let (mut engine, mut ups, ssp) = cross_setup_weight_only(); // numeraire left unset
        pledge_cross_collateral(&mut engine, &mut ups, &ssp, 1, 1_000);
        let mut cmd = cross_withdraw_cmd(2, BASE, 100, 1_000);
        assert_eq!(
            LoanCommandDispatcher::dispatch(&mut engine, &mut cmd, &mut ups, &ssp),
            CommandResultCode::LoanNumeraireNotConfigured
        );
        assert_eq!(ups.get(UID).unwrap().cross_loan_collateral(BASE), 1_000); // untouched
    }

    #[test]
    fn cross_withdraw_collateral_succeeds_when_new_ltv_stays_below_liquidation_line() {
        let (mut engine, mut ups, ssp) = cross_setup();
        pledge_cross_collateral(&mut engine, &mut ups, &ssp, 1, 1_000); // weighted = 500
        open_cross_loan(&mut engine, &mut ups, &ssp, 42, 200, 1_000); // debt=200

        // withdraw 100 -> newCollateral=900, weighted=450 -> ltv=200*10000/450=4444 < 8500 (default cross liquidation).
        let mut cmd = cross_withdraw_cmd(2, BASE, 100, 2_000);
        assert_eq!(LoanCommandDispatcher::dispatch(&mut engine, &mut cmd, &mut ups, &ssp), CommandResultCode::Success);
        assert_eq!(ups.get(UID).unwrap().cross_loan_collateral(BASE), 900);
    }

    #[test]
    fn cross_withdraw_collateral_reverts_subtraction_when_new_ltv_too_high() {
        let (mut engine, mut ups, ssp) = cross_setup();
        pledge_cross_collateral(&mut engine, &mut ups, &ssp, 1, 1_000); // weighted = 500
        open_cross_loan(&mut engine, &mut ups, &ssp, 42, 200, 1_000); // debt=200

        // withdraw 600 -> newCollateral=400, weighted=200 -> ltv=200*10000/200=10000 (100%) >= 8500 -> revert.
        let mut cmd = cross_withdraw_cmd(2, BASE, 600, 2_000);
        assert_eq!(
            LoanCommandDispatcher::dispatch(&mut engine, &mut cmd, &mut ups, &ssp),
            CommandResultCode::LoanCrossLtvTooHighAfterWithdraw
        );
        // subtract-then-check rollback: the tentative subtraction must be fully undone.
        assert_eq!(ups.get(UID).unwrap().cross_loan_collateral(BASE), 1_000);
    }

    // LOAN_CROSS_REPAY

    #[test]
    fn cross_repay_full_payoff_removes_loan_but_never_touches_collateral_pool() {
        let (mut engine, mut ups, ssp) = cross_setup();
        pledge_cross_collateral(&mut engine, &mut ups, &ssp, 1, 1_000);
        open_cross_loan(&mut engine, &mut ups, &ssp, 42, 200, 1_000); // disburses 200 QUOTE, no interest ever accrues (cold-start floating)

        let before_collateral = ups.get(UID).unwrap().cross_loan_collateral(BASE);
        let mut cmd = cross_repay_cmd(2, 42, 0, 2_000); // 0 = full payoff
        assert_eq!(LoanCommandDispatcher::dispatch(&mut engine, &mut cmd, &mut ups, &ssp), CommandResultCode::Success);

        let up = ups.get(UID).unwrap();
        assert!(up.cross_loans.get(&42).is_none()); // CrossLoanRecord has no collateral field -> is_empty() as soon as debt hits 0
        assert_eq!(up.cross_loan_collateral(BASE), before_collateral); // account-level pool untouched by REPAY
        assert_eq!(up.account(QUOTE), 0); // the 200 disbursed was exactly what was owed back
        assert_eq!(engine.loan_service.get_loan_pool_available(QUOTE), 1_000_000); // principal fully returned
    }

    #[test]
    fn cross_repay_partial_reduces_principal_and_keeps_loan_open() {
        let (mut engine, mut ups, ssp) = cross_setup();
        pledge_cross_collateral(&mut engine, &mut ups, &ssp, 1, 1_000);
        open_cross_loan(&mut engine, &mut ups, &ssp, 42, 200, 1_000);

        let mut cmd = cross_repay_cmd(2, 42, 50, 2_000);
        assert_eq!(LoanCommandDispatcher::dispatch(&mut engine, &mut cmd, &mut ups, &ssp), CommandResultCode::Success);

        let up = ups.get(UID).unwrap();
        let loan = up.cross_loans.get(&42).expect("partial repay keeps the loan open");
        assert_eq!(loan.outstanding_principal, 150);
        assert_eq!(up.cross_loan_collateral(BASE), 1_000); // untouched
    }

    #[test]
    fn cross_repay_rejects_not_found_uid_mismatch_and_invalid_amount() {
        let (mut engine, mut ups, ssp) = cross_setup();
        pledge_cross_collateral(&mut engine, &mut ups, &ssp, 1, 1_000);
        open_cross_loan(&mut engine, &mut ups, &ssp, 42, 200, 1_000);

        let mut not_found = cross_repay_cmd(2, 999, 0, 2_000);
        assert_eq!(
            LoanCommandDispatcher::dispatch(&mut engine, &mut not_found, &mut ups, &ssp),
            CommandResultCode::LoanNotFound
        );

        let mut negative = cross_repay_cmd(3, 42, -1, 2_000);
        assert_eq!(
            LoanCommandDispatcher::dispatch(&mut engine, &mut negative, &mut ups, &ssp),
            CommandResultCode::LoanInvalidAmount
        );

        // 同 loan_repay_rejects_uid_mismatch（Isolated）：只能靠直接嫁接外来 uid 记录触发，模拟 Java 对象池复用 bug。
        let mut foreign = CrossLoanRecord::new(999, 77, SYMBOL, QUOTE, 0, 1_000);
        foreign.outstanding_principal = 10;
        ups.get_mut(UID).unwrap().cross_loans.insert(77, foreign);
        let mut mismatch = cross_repay_cmd(4, 77, 0, 2_000);
        assert_eq!(
            LoanCommandDispatcher::dispatch(&mut engine, &mut mismatch, &mut ups, &ssp),
            CommandResultCode::LoanUidMismatch
        );
    }

    #[test]
    fn cross_repay_rejects_account_insufficient() {
        let (mut engine, mut ups, ssp) = cross_setup();
        pledge_cross_collateral(&mut engine, &mut ups, &ssp, 1, 1_000);
        open_cross_loan(&mut engine, &mut ups, &ssp, 42, 200, 1_000); // disburses 200 QUOTE
        ups.get_mut(UID).unwrap().accounts.insert(QUOTE, 50); // spent the borrowed funds elsewhere
        let mut cmd = cross_repay_cmd(2, 42, 100, 2_000); // request < payoff, so not auto-capped up
        assert_eq!(
            LoanCommandDispatcher::dispatch(&mut engine, &mut cmd, &mut ups, &ssp),
            CommandResultCode::LoanAccountInsufficient
        );
    }

    // LOAN_FORCE_LIQUIDATE R1（pre-move + compare-and-consume），§2.5；R2/LIF 全流程集成测试在 exchange_core.rs

    fn force_liquidate_cmd(order_id: i64, uid: i64, loan_id: i64, lots: i64) -> OrderCommand {
        OrderCommand {
            command: OrderCommandType::LoanForceLiquidate,
            order_id,
            uid,
            symbol: SYMBOL,
            size: lots,
            price: 1, // 破产/限价，R1 不校验其取值合理性（scanner/调用方职责，见方法文档）
            reserve_bid_price: loan_id,
            ..Default::default()
        }
    }

    #[test]
    fn force_liquidate_r1_success_premoves_collateral_and_sets_ask_ioc() {
        let (mut engine, mut ups, ssp) = setup();
        open_loan(&mut engine, &mut ups, &ssp, 42, 1_000, 400, None);

        let mut cmd = force_liquidate_cmd(1, UID, 42, 600);
        assert_eq!(
            LoanCommandDispatcher::dispatch(&mut engine, &mut cmd, &mut ups, &ssp),
            CommandResultCode::ValidForMatchingEngine
        );

        assert_eq!(cmd.action, Some(OrderAction::Ask));
        assert_eq!(cmd.order_type, Some(OrderType::Ioc));
        let up = ups.get(UID).unwrap();
        assert_eq!(up.isolated_loans.get(&42).unwrap().collateral_amount, 400); // 1000-600
        assert_eq!(up.locked(BASE), 600); // moved into exchange_locked, not accounts
        assert_eq!(up.account(BASE), 10_000); // accounts itself untouched (virtual lock all along; setup() funds 10_000 BASE)
    }

    #[test]
    fn force_liquidate_r1_rejects_not_found() {
        let (mut engine, mut ups, ssp) = setup();
        let mut cmd = force_liquidate_cmd(1, UID, 999, 100);
        assert_eq!(
            LoanCommandDispatcher::dispatch(&mut engine, &mut cmd, &mut ups, &ssp),
            CommandResultCode::LoanNotFound
        );
    }

    #[test]
    fn force_liquidate_r1_rejects_uid_mismatch() {
        // 同 loan_repay_rejects_uid_mismatch 构造：直接注入 uid 字段与所在 map 不一致的记录。
        let (mut engine, mut ups, ssp) = setup();
        let mut foreign_loan = IsolatedLoanRecord::new(999, 42, SYMBOL, BASE, QUOTE, 0, 1_000);
        foreign_loan.collateral_amount = 1_000;
        ups.get_mut(UID).unwrap().isolated_loans.insert(42, foreign_loan);

        let mut cmd = force_liquidate_cmd(1, UID, 42, 100);
        assert_eq!(
            LoanCommandDispatcher::dispatch(&mut engine, &mut cmd, &mut ups, &ssp),
            CommandResultCode::LoanUidMismatch
        );
    }

    #[test]
    fn force_liquidate_r1_rejects_not_enabled_when_symbol_or_currency_mismatch() {
        let (mut engine, mut ups, ssp) = setup();
        open_loan(&mut engine, &mut ups, &ssp, 42, 1_000, 400, None);

        let mut missing_symbol = force_liquidate_cmd(1, UID, 42, 100);
        missing_symbol.symbol = 999; // no such spec
        assert_eq!(
            LoanCommandDispatcher::dispatch(&mut engine, &mut missing_symbol, &mut ups, &ssp),
            CommandResultCode::LoanNotEnabled
        );
    }

    #[test]
    fn force_liquidate_r1_rejects_invalid_amount_zero_or_exceeding_collateral() {
        let (mut engine, mut ups, ssp) = setup();
        open_loan(&mut engine, &mut ups, &ssp, 42, 1_000, 400, None);

        let mut zero = force_liquidate_cmd(1, UID, 42, 0);
        assert_eq!(
            LoanCommandDispatcher::dispatch(&mut engine, &mut zero, &mut ups, &ssp),
            CommandResultCode::LoanInvalidAmount
        );

        let mut too_much = force_liquidate_cmd(2, UID, 42, 1_001); // > collateral_amount=1000
        assert_eq!(
            LoanCommandDispatcher::dispatch(&mut engine, &mut too_much, &mut ups, &ssp),
            CommandResultCode::LoanInvalidAmount
        );
        // Neither rejected attempt should have mutated state.
        assert_eq!(ups.get(UID).unwrap().isolated_loans.get(&42).unwrap().collateral_amount, 1_000);
    }

    /// 幂等核心 compare-and-consume：首次消费 700 抵押剩 300，二次再请求 700 因超余量被拒，collateral_amount 本身即幂等游标。
    #[test]
    fn force_liquidate_r1_duplicate_submission_is_compare_and_consume_idempotent() {
        let (mut engine, mut ups, ssp) = setup();
        open_loan(&mut engine, &mut ups, &ssp, 42, 1_000, 400, None);

        let mut first = force_liquidate_cmd(1, UID, 42, 700);
        assert_eq!(
            LoanCommandDispatcher::dispatch(&mut engine, &mut first, &mut ups, &ssp),
            CommandResultCode::ValidForMatchingEngine
        );
        assert_eq!(ups.get(UID).unwrap().isolated_loans.get(&42).unwrap().collateral_amount, 300);

        // 重试场景：scanner 用新 orderId 重发同一 loanId/lots，第二次已超过被首次消费后的剩余量。
        let mut second = force_liquidate_cmd(2, UID, 42, 700);
        assert_eq!(
            LoanCommandDispatcher::dispatch(&mut engine, &mut second, &mut ups, &ssp),
            CommandResultCode::LoanInvalidAmount
        );
        assert_eq!(ups.get(UID).unwrap().isolated_loans.get(&42).unwrap().collateral_amount, 300); // unchanged by the rejected retry
        assert_eq!(ups.get(UID).unwrap().locked(BASE), 700); // only the first attempt's lock stands
    }

    #[test]
    fn force_liquidate_r1_ignores_suspended_status_unlike_other_loan_commands() {
        // 与 LOAN_CREATE/REPAY 等不同，强平必须无视冻结账户执行——这正是强平的意义。
        let (mut engine, mut ups, ssp) = setup();
        open_loan(&mut engine, &mut ups, &ssp, 42, 1_000, 400, None);
        ups.get_mut(UID).unwrap().user_status = UserStatus::Suspended;

        let mut cmd = force_liquidate_cmd(1, UID, 42, 500);
        assert_eq!(
            LoanCommandDispatcher::dispatch(&mut engine, &mut cmd, &mut ups, &ssp),
            CommandResultCode::ValidForMatchingEngine
        );
    }

    // LOAN_CROSS_FORCE_LIQUIDATE R1，参考文档 §2.10

    fn cross_force_liquidate_cmd(order_id: i64, uid: i64, target_loan_id: i64, lots: i64) -> OrderCommand {
        OrderCommand {
            command: OrderCommandType::LoanCrossForceLiquidate,
            order_id,
            uid,
            symbol: SYMBOL, // base=BASE(selling currency)/quote=QUOTE(targetLoan.loanCurrency)
            size: lots,
            price: 1,
            reserve_bid_price: target_loan_id,
            ..Default::default()
        }
    }

    #[test]
    fn cross_force_liquidate_r1_success_premoves_collateral_and_sets_ask_ioc() {
        let (mut engine, mut ups, ssp) = cross_setup();
        pledge_cross_collateral(&mut engine, &mut ups, &ssp, 1, 1_000); // weighted collateral = 500
        open_cross_loan(&mut engine, &mut ups, &ssp, 42, 200, 1_000); // ltv=4000<=initialLtv(5000)

        let mut cmd = cross_force_liquidate_cmd(2, UID, 42, 600);
        assert_eq!(
            LoanCommandDispatcher::dispatch(&mut engine, &mut cmd, &mut ups, &ssp),
            CommandResultCode::ValidForMatchingEngine
        );

        assert_eq!(cmd.action, Some(OrderAction::Ask));
        assert_eq!(cmd.order_type, Some(OrderType::Ioc));
        let up = ups.get(UID).unwrap();
        assert_eq!(up.cross_loan_collateral(BASE), 400); // 1000-600
        assert_eq!(up.locked(BASE), 600);
        assert_eq!(up.account(BASE), 10_000); // untouched, still virtually locked (setup() funds 10_000 BASE)
    }

    #[test]
    fn cross_force_liquidate_r1_rejects_not_found() {
        let (mut engine, mut ups, ssp) = cross_setup();
        let mut cmd = cross_force_liquidate_cmd(1, UID, 999, 100);
        assert_eq!(
            LoanCommandDispatcher::dispatch(&mut engine, &mut cmd, &mut ups, &ssp),
            CommandResultCode::LoanNotFound
        );
    }

    #[test]
    fn cross_force_liquidate_r1_rejects_uid_mismatch() {
        // 同 Isolated 版本：向 UID 自己的 cross_loans map 注入 uid 字段不一致的 CrossLoanRecord。
        let (mut engine, mut ups, ssp) = cross_setup();
        let mut foreign_loan = CrossLoanRecord::new(999, 42, SYMBOL, QUOTE, 0, 1_000);
        foreign_loan.outstanding_principal = 200;
        ups.get_mut(UID).unwrap().cross_loans.insert(42, foreign_loan);
        ups.get_mut(UID).unwrap().add_to_cross_loan_collateral(BASE, 1_000);

        let mut cmd = cross_force_liquidate_cmd(2, UID, 42, 100);
        assert_eq!(
            LoanCommandDispatcher::dispatch(&mut engine, &mut cmd, &mut ups, &ssp),
            CommandResultCode::LoanUidMismatch
        );
    }

    #[test]
    fn cross_force_liquidate_r1_rejects_not_enabled_when_quote_currency_mismatch() {
        let (mut engine, mut ups, ssp) = cross_setup();
        pledge_cross_collateral(&mut engine, &mut ups, &ssp, 1, 1_000);
        open_cross_loan(&mut engine, &mut ups, &ssp, 42, 200, 1_000);

        let mut cmd = cross_force_liquidate_cmd(2, UID, 42, 100);
        cmd.symbol = 999; // no such spec
        assert_eq!(
            LoanCommandDispatcher::dispatch(&mut engine, &mut cmd, &mut ups, &ssp),
            CommandResultCode::LoanNotEnabled
        );
    }

    #[test]
    fn cross_force_liquidate_r1_rejects_invalid_amount_zero_or_exceeding_available() {
        let (mut engine, mut ups, ssp) = cross_setup();
        pledge_cross_collateral(&mut engine, &mut ups, &ssp, 1, 1_000);
        open_cross_loan(&mut engine, &mut ups, &ssp, 42, 200, 1_000);

        let mut zero = cross_force_liquidate_cmd(2, UID, 42, 0);
        assert_eq!(
            LoanCommandDispatcher::dispatch(&mut engine, &mut zero, &mut ups, &ssp),
            CommandResultCode::LoanInvalidAmount
        );
        let mut too_much = cross_force_liquidate_cmd(3, UID, 42, 1_001);
        assert_eq!(
            LoanCommandDispatcher::dispatch(&mut engine, &mut too_much, &mut ups, &ssp),
            CommandResultCode::LoanInvalidAmount
        );
        assert_eq!(ups.get(UID).unwrap().cross_loan_collateral(BASE), 1_000); // untouched
    }

    #[test]
    fn cross_force_liquidate_r1_duplicate_submission_is_compare_and_consume_idempotent() {
        let (mut engine, mut ups, ssp) = cross_setup();
        pledge_cross_collateral(&mut engine, &mut ups, &ssp, 1, 1_000);
        open_cross_loan(&mut engine, &mut ups, &ssp, 42, 200, 1_000);

        let mut first = cross_force_liquidate_cmd(2, UID, 42, 700);
        assert_eq!(
            LoanCommandDispatcher::dispatch(&mut engine, &mut first, &mut ups, &ssp),
            CommandResultCode::ValidForMatchingEngine
        );
        assert_eq!(ups.get(UID).unwrap().cross_loan_collateral(BASE), 300);

        let mut second = cross_force_liquidate_cmd(3, UID, 42, 700);
        assert_eq!(
            LoanCommandDispatcher::dispatch(&mut engine, &mut second, &mut ups, &ssp),
            CommandResultCode::LoanInvalidAmount
        );
        assert_eq!(ups.get(UID).unwrap().cross_loan_collateral(BASE), 300); // unchanged by rejected retry
    }

    // POOL_DEPOSIT / POOL_WITHDRAW / LOAN_IF_DEPOSIT / LOAN_IF_WITHDRAW

    fn pool_cmd(command: OrderCommandType, currency: i32, amount: i64) -> OrderCommand {
        // uid 在这 4 个命令里携 shardId（非真实 uid），本移植单 shard 未做过滤，任意值皆可。
        OrderCommand { command, uid: 0, symbol: currency, size: amount, ..Default::default() }
    }

    #[test]
    fn pool_deposit_credits_available_and_hedges_adjustments() {
        let (mut engine, mut ups, ssp) = setup(); // loan_pool_available[QUOTE] starts at 1_000_000
        let mut cmd = pool_cmd(OrderCommandType::PoolDeposit, QUOTE, 500);
        assert_eq!(LoanCommandDispatcher::dispatch(&mut engine, &mut cmd, &mut ups, &ssp), CommandResultCode::Success);
        assert_eq!(engine.loan_service.get_loan_pool_available(QUOTE), 1_000_500);
        assert_eq!(*engine.adjustments.get(&QUOTE).unwrap(), -500);
    }

    #[test]
    fn pool_deposit_rejects_invalid_amount() {
        let (mut engine, mut ups, ssp) = setup();
        let mut zero = pool_cmd(OrderCommandType::PoolDeposit, QUOTE, 0);
        assert_eq!(
            LoanCommandDispatcher::dispatch(&mut engine, &mut zero, &mut ups, &ssp),
            CommandResultCode::LoanInvalidAmount
        );
        let mut negative = pool_cmd(OrderCommandType::PoolDeposit, QUOTE, -5);
        assert_eq!(
            LoanCommandDispatcher::dispatch(&mut engine, &mut negative, &mut ups, &ssp),
            CommandResultCode::LoanInvalidAmount
        );
    }

    #[test]
    fn pool_withdraw_debits_available_and_hedges_adjustments() {
        let (mut engine, mut ups, ssp) = setup(); // loan_pool_available[QUOTE] = 1_000_000
        let mut cmd = pool_cmd(OrderCommandType::PoolWithdraw, QUOTE, 300_000);
        assert_eq!(LoanCommandDispatcher::dispatch(&mut engine, &mut cmd, &mut ups, &ssp), CommandResultCode::Success);
        assert_eq!(engine.loan_service.get_loan_pool_available(QUOTE), 700_000);
        assert_eq!(*engine.adjustments.get(&QUOTE).unwrap(), 300_000);
    }

    #[test]
    fn pool_withdraw_rejects_amount_exceeding_available_and_leaves_bucket_untouched() {
        let (mut engine, mut ups, ssp) = setup(); // available = 1_000_000
        let mut cmd = pool_cmd(OrderCommandType::PoolWithdraw, QUOTE, 1_000_001);
        assert_eq!(
            LoanCommandDispatcher::dispatch(&mut engine, &mut cmd, &mut ups, &ssp),
            CommandResultCode::LoanPoolInsufficient
        );
        assert_eq!(engine.loan_service.get_loan_pool_available(QUOTE), 1_000_000);
        assert_eq!(*engine.adjustments.get(&QUOTE).unwrap_or(&0), 0);
    }

    #[test]
    fn pool_deposit_then_withdraw_round_trip_hedges_back_to_zero() {
        let (mut engine, mut ups, ssp) = setup();
        let mut dep = pool_cmd(OrderCommandType::PoolDeposit, QUOTE, 1_000);
        assert_eq!(LoanCommandDispatcher::dispatch(&mut engine, &mut dep, &mut ups, &ssp), CommandResultCode::Success);
        let mut wd = pool_cmd(OrderCommandType::PoolWithdraw, QUOTE, 1_000);
        assert_eq!(LoanCommandDispatcher::dispatch(&mut engine, &mut wd, &mut ups, &ssp), CommandResultCode::Success);
        assert_eq!(*engine.adjustments.get(&QUOTE).unwrap(), 0); // -1000 then +1000
        assert_eq!(engine.loan_service.get_loan_pool_available(QUOTE), 1_000_000); // net unchanged
    }

    #[test]
    fn loan_if_deposit_credits_and_hedges_adjustments() {
        let (mut engine, mut ups, ssp) = setup();
        let mut cmd = pool_cmd(OrderCommandType::LoanIfDeposit, QUOTE, 200);
        assert_eq!(LoanCommandDispatcher::dispatch(&mut engine, &mut cmd, &mut ups, &ssp), CommandResultCode::Success);
        assert_eq!(engine.loan_service.get_loan_insurance_fund(QUOTE), 200);
        assert_eq!(*engine.adjustments.get(&QUOTE).unwrap(), -200);
    }

    #[test]
    fn loan_if_deposit_rejects_invalid_amount() {
        let (mut engine, mut ups, ssp) = setup();
        let mut cmd = pool_cmd(OrderCommandType::LoanIfDeposit, QUOTE, 0);
        assert_eq!(
            LoanCommandDispatcher::dispatch(&mut engine, &mut cmd, &mut ups, &ssp),
            CommandResultCode::LoanInvalidAmount
        );
    }

    #[test]
    fn loan_if_withdraw_debits_and_hedges_adjustments() {
        let (mut engine, mut ups, ssp) = setup();
        engine.loan_service.add_to_loan_insurance_fund(QUOTE, 500);
        let mut cmd = pool_cmd(OrderCommandType::LoanIfWithdraw, QUOTE, 300);
        assert_eq!(LoanCommandDispatcher::dispatch(&mut engine, &mut cmd, &mut ups, &ssp), CommandResultCode::Success);
        assert_eq!(engine.loan_service.get_loan_insurance_fund(QUOTE), 200);
        assert_eq!(*engine.adjustments.get(&QUOTE).unwrap(), 300);
    }

    #[test]
    fn loan_if_withdraw_rejects_when_insufficient_and_never_pushes_more_negative() {
        let (mut engine, mut ups, ssp) = setup();
        // LIF 因接管变负（被动结果非运营透支）——LOAN_IF_WITHDRAW 用同一 < 比较天然满足"不推更负"不变式。
        engine.loan_service.add_to_loan_insurance_fund(QUOTE, -500);
        let mut cmd = pool_cmd(OrderCommandType::LoanIfWithdraw, QUOTE, 100);
        assert_eq!(
            LoanCommandDispatcher::dispatch(&mut engine, &mut cmd, &mut ups, &ssp),
            CommandResultCode::LoanIfInsufficient
        );
        assert_eq!(engine.loan_service.get_loan_insurance_fund(QUOTE), -500); // untouched
        assert_eq!(*engine.adjustments.get(&QUOTE).unwrap_or(&0), 0);
    }

}
