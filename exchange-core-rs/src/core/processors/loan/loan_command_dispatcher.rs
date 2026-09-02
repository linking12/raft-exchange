//! 对应 Java: exchange.core2.core.processors.loan.LoanCommandDispatcher —— Isolated 生命周期
//! 命令 CREATE/REPAY/ADD_COLLATERAL/RELEASE_COLLATERAL + 公共 preamble + dispatch 表
//! （`OrderCommandType::is_loan()` 门守命中后的入口，参考文档 §0/§2.1-2.4）。
//!
//! **Task 4 范围**：`is_loan()` 覆盖 14 个命令码（参考文档 §0），本任务只落 4 个 Isolated
//! 生命周期命令。其余 10 个（Cross 5 个 `LOAN_CROSS_*`、两个 `*_FORCE_LIQUIDATE`、
//! `POOL_DEPOSIT`/`POOL_WITHDRAW`/`LOAN_IF_DEPOSIT`/`LOAN_IF_WITHDRAW` 4 个）留 Task 5-7，
//! 命中 [`LoanCommandDispatcher::dispatch`] 时返回 `CommandResultCode::LoanNotImplemented`——
//! 本任务测试集从不构造这些命令类型，该分支在当前测试范围内不可达，只是占位而非"假装支持"。
//!
//! # Rust 对齐 Java `LoanCommandDispatcher(RiskEngine engine)` 构造器持有 engine 的做法
//! Java 版把 `engine` 存成实例字段，构造一次、复用于每次 `dispatch`。Rust 版不持有任何状态
//! （`LoanCommandDispatcher` 是零大小类型，方法全是关联函数），改为每次调用显式传入
//! `engine: &mut RiskEngine`——既避免了 `RiskEngine`/`LoanCommandDispatcher` 相互持有导致的
//! 生命周期/所有权问题，又能复用 `RiskEngine::mark_price`/`calculate_locked`（均为 `&self`
//! 方法）：NLL 下这些只读调用与随后对 `engine.loan_service`（`RiskEngine` 的字段）的可变访问
//! 从不同时存活，不产生借用冲突。这与 brief 建议的 `dispatch(cmd, up_service, ssp,
//! loan_service)` 四参签名有出入——`calculateLocked`/`markPrice` 是 `RiskEngine` 的方法而非
//! `LoanService` 的，若不传整个 `engine` 就得把这两个方法复制一份到 dispatcher 里，属于更差的
//! 重复；显式传 `&mut RiskEngine` 是更小的偏差。
//!
//! # 借用设计：REPAY 为何按 `loan_id` 重查两次 map，而不是直接传 `&mut LoanRecord`
//! Java 的 `settleRepay(UserProfile up, LoanRecord loan, OrderCommand cmd)` 同时持有 `up` 和从
//! `up.isolatedLoans` 取出的 `loan` 引用（GC 语言里这只是两个别名，完全合法）。Rust 不允许：
//! 若 `loan: &mut IsolatedLoanRecord` 是从 `up.isolated_loans.get_mut(loan_id)` 借出的，就不能
//! 同时把 `up`（整个结构体）传给 `engine.calculate_locked(up, ..)`（需要 `&UserProfile`，对
//! 借用检查器不透明，视作借用 `up` 全部字段，与 `loan` 借用的 `isolated_loans` 字段重叠）。
//! 因此 [`Self::settle_repay_isolated`] 改为接收 `loan_id`，在需要访问 `loan` 与需要访问
//! `up`（整体）之间**顺序**、**分段**地各自重新 `get`/`get_mut` 一次——同一时刻只活一种借用，
//! NLL 允许其非重叠地相继发生。代价是同一 loan 多查了几次 `BTreeMap`（O(log n)，可忽略）；
//! 好处是不需要 `unsafe`/`RefCell` 就能逐字复刻 Java 的校验顺序与金钱流向。
//! Cross（Task 5）REPAY 复用相同的整体结构，但要在 `cross_loans` 上重复一份（Java 侧
//! `settleRepay` 通过 `LoanRecord` 接口天然共享；Rust 版每种 map 各自一份薄包装）。
use crate::core::common::cmd::command_result_code::CommandResultCode;
use crate::core::common::cmd::order_command::OrderCommand;
use crate::core::common::cmd::order_command_type::OrderCommandType;
use crate::core::common::core_symbol_specification::CoreSymbolSpecification;
use crate::core::common::isolated_loan_record::{IsolatedLoanRecord, LoanRateMode};
use crate::core::common::symbol_type::SymbolType;
use crate::core::common::user_profile::UserProfile;
use crate::core::common::user_status::UserStatus;
use crate::core::processors::loan::loan_service::{LoanService, BPS_SCALE};
use crate::core::processors::risk_engine::RiskEngine;
use crate::core::processors::symbol_specification_provider::SymbolSpecificationProvider;
use crate::core::processors::user_profile_service::UserProfileService;

/// 对应 Java `Math.multiplyExact(long, long)`：局部私有重复一份（风格对齐仓内各文件的同名
/// helper，如 `risk_engine.rs`/`loan_service.rs`）。
fn mul_exact(a: i64, b: i64) -> i64 {
    i64::try_from(a as i128 * b as i128).unwrap_or_else(|_| panic!("overflow: {a} * {b}"))
}

/// 对应 Java `Math.addExact(long, long)`。
fn add_exact(a: i64, b: i64) -> i64 {
    i64::try_from(a as i128 + b as i128).unwrap_or_else(|_| panic!("overflow: {a} + {b}"))
}

/// 对应 Java `LoanCommandDispatcher`（Task 4 范围子集）。零大小类型，见模块文档。
pub struct LoanCommandDispatcher;

impl LoanCommandDispatcher {
    /// 对应 Java `dispatch(OrderCommand cmd)`（`:51-121`）：按 `cmd.command` 路由。Task 4 只落
    /// 4 个 Isolated 生命周期命令；其余 10 个 `is_loan()` 码见模块文档。
    ///
    /// Java 原版按 `uidForThisHandler(cmd.uid)`/`(int) cmd.uid == shardId` 做分片自过滤——本移植
    /// 单 shard、恒真，未搬迁该判断（同 P3/P4 既有 ruling：单 shard 下分片过滤是恒真 no-op）。
    pub fn dispatch(
        engine: &mut RiskEngine,
        cmd: &mut OrderCommand,
        ups: &mut UserProfileService,
        ssp: &SymbolSpecificationProvider,
    ) -> CommandResultCode {
        match cmd.command {
            OrderCommandType::LoanCreate => Self::handle_loan_create(engine, cmd, ups, ssp),
            OrderCommandType::LoanRepay => Self::handle_loan_repay(engine, cmd, ups, ssp),
            OrderCommandType::LoanAddCollateral => Self::handle_loan_add_collateral(engine, cmd, ups, ssp),
            OrderCommandType::LoanReleaseCollateral => {
                Self::handle_loan_release_collateral(engine, cmd, ups, ssp)
            }
            // 不可达于本任务测试集：Task 5-7 落地前, 调用方从不构造这些 cmd.command。
            _ => CommandResultCode::LoanNotImplemented,
        }
    }

    /// 公共 preamble（参考文档 §2 顶部）：缺户 → `AuthInvalidUser`；冻结户 →
    /// `LoanUserSuspended`；`tryClaim` 幂等（claim-and-keep：claim 后即使后续校验失败也不释放，
    /// 重试须换新 `orderId`，对齐 `BALANCE_ADJUSTMENT`）。成功直接返回 `&mut UserProfile`，
    /// 避免调用方重复按 uid 查表。`loanId` 是纯业务键，不参与幂等（不在这里判重）。
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

    /// 对应 Java 私有 `evalCollateralInLoanCurrency`（`:1009-1015`）：从 `ssp` 取
    /// `spec.base_currency`/`quote_currency` 各自的 `CoreCurrencySpecification`，转调
    /// [`LoanService::collateral_value_in_quote_currency`]。
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

    // ====================================================================================
    // LOAN_CREATE — 参考文档 §2.1，Java `handleLoanCreate`（`:130-209`）
    // ====================================================================================

    /// 开仓 Isolated 借贷。字段映射（参考文档 §2.1，逐字对齐 Java）：`cmd.symbol` = 现货
    /// symbolId，`cmd.size` = collateralAmount（base scale），`cmd.price` = principal（quote
    /// scale），`cmd.reserve_bid_price` = loanId，`cmd.user_cookie` 低字节 = rateMode
    /// （`== RATE_MODE_FLOATING` sentinel 则 FLOATING，否则 LOCKED）。
    ///
    /// 校验顺序 cheap→expensive（逐条对应 Java `:141-176`）：spec 存在且
    /// `type==CURRENCY_EXCHANGE_PAIR` → `LoanNotEnabled`；`loan_config.is_enabled()` →
    /// `LoanNotEnabled`；`loanId` 未被占用 → `LoanAlreadyExists`；`principal>0 &&
    /// collateralAmount>0` → `LoanInvalidAmount`；`maxAmount!=0 && principal>maxAmount` →
    /// `LoanPrincipalExceedsLimit`；`markPrice>0` → `LoanMarkpriceNotReady`；LTV
    /// `principal×10000 ≤ collateralValue×initialLtvBps` → `LoanLtvTooHigh`；自由抵押余额
    /// `accounts−calculateLocked ≥ collateralAmount` → `LoanCollateralInsufficient`；池容量/
    /// 利用率 → `LoanPoolInsufficient`/`LoanPoolUtilizationExceeded`。
    ///
    /// 成功后 disburse：`accounts[loanCurrency] += principal`；`loanPoolAvailable -=
    /// principal`；`loanPoolBorrowed += principal`（[`LoanService::disburse_loan`]）。
    ///
    /// **事件缺口**：Java 版在此发 `LOAN_BORROW` 事件（`EventsHelper.sendLoanBorrowEvent`）。
    /// 本仓库尚无 FundEvent/EventsHelper 风格的事件总线（现货/期货既有的 P1-P4 命令路径也从
    /// 未发送任何此类事件，只做记账副作用），故本任务同样不发——账本状态（loan 记录 + 4 个
    /// 资金桶 + accounts）已经是权威、可测试的真相源；下游若要接事件总线是后续任务的范围，
    /// 这里不假装已经支持。
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

    // ====================================================================================
    // LOAN_REPAY — 参考文档 §2.2，Java `handleLoanRepay`（`:234-271`）+ `settleRepay`（`:215-232`）
    // ====================================================================================

    /// Isolated REPAY 共用核心：校验金额 → accrue → 算实抵债额（0 或 ≥payoff 则视为全额）→ 查
    /// 可用余额 → 抵债（利息优先，[`LoanService::apply_debt_payment`]）。对应 Java 私有
    /// `settleRepay`（`:215-232`）。**不释放抵押**（部分还款也不动 `collateralAmount`）。
    ///
    /// 接收 `loan_id` 而非 `&mut LoanRecord`——见模块文档"借用设计"一节。调用方须已确认
    /// `loan_id` 存在于 `up.isolated_loans` 且归属 `cmd.uid`。
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

        // 阶段二：free-balance 校验，需要 `&UserProfile` 整体（loanCollateralLocked 会扫
        // isolated_loans），此刻上面的 `&mut loan` 借用已结束，二者不重叠。
        let loan_currency_spec = ssp
            .get_currency(loan_currency)
            .unwrap_or_else(|| panic!("currency spec missing for currency {loan_currency}"));
        let free = up.account(loan_currency) - engine.calculate_locked(up, loan_currency, ssp, loan_currency_spec);
        if free < actual_repay {
            return CommandResultCode::LoanAccountInsufficient;
        }

        // 阶段三：抵债——`loan`（`up.isolated_loans` 的字段投影）与 `&mut up.accounts`（另一
        // 字段）是不重叠的直接字段借用，可同时活。
        let loan = up.isolated_loans.get_mut(&loan_id).expect("loan existence checked by caller");
        engine.loan_service.apply_debt_payment(loan, &mut up.accounts, actual_repay);
        CommandResultCode::Success
    }

    /// 偿还 Isolated 借贷（本金+利息）。字段映射：`cmd.reserve_bid_price` = loanId，
    /// `cmd.price` = repayAmount（`0` = 结清全部本息）。成功后若 `loan.is_empty()`（本金/利息/
    /// 抵押全 0），从 map 移除（对应 Java 归还对象池——本移植无对象池，直接 drop）。
    ///
    /// **事件缺口**：同 [`Self::handle_loan_create`] 文档，Java 侧的 `LOAN_REPAY` 事件本任务
    /// 不发送。
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

    // ====================================================================================
    // LOAN_ADD_COLLATERAL — 参考文档 §2.3，Java `handleLoanAddCollateral`（`:274-310`）
    // ====================================================================================

    /// 补抵押降 LTV。字段映射：`cmd.reserve_bid_price` = loanId，`cmd.size` = amount。校验
    /// loan 存在/归属、`amount>0`、自由抵押余额 `accounts−calculateLocked ≥ amount`；通过后先
    /// `accrue_to`（让后续 LTV/事件快照吃到最新利息，此处只做 accrue，无事件），再
    /// `collateral_amount += amount`。
    ///
    /// **事件缺口**：同上，Java 侧 `LOAN_COLLATERAL_CHANGE` 事件本任务不发送。
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

    // ====================================================================================
    // LOAN_RELEASE_COLLATERAL — 参考文档 §2.4，Java `handleLoanReleaseCollateral`（`:313-378`）
    // ====================================================================================

    /// 减抵押；允许释放到刚好高于清算线（严格 `<`），拒绝撤到清算线或以下。字段映射：
    /// `cmd.reserve_bid_price` = loanId，`cmd.size` = amount。
    ///
    /// 校验：`amount>0`；`amount ≤ collateralAmount` 否则 `LoanCollateralExceedsLoan`；spec
    /// 存在（按 `loan.symbol_id`，只查存在性不再查 `symbol_type`——开仓时已经验证过一次，
    /// 类型此后不变）否则 `LoanNotEnabled`；`markPrice>0` 否则 `LoanMarkpriceNotReady`；
    /// `accrue_to` 后算 `realDebt = outstandingPrincipal + calculateDisplayInterest(loan, now)`
    /// （pending-interest-inclusive，避免低估 LTV）；`newCollateral==0 && realDebt>0` →
    /// `LoanLtvTooHighAfterRelease`；`newCollateral>0` 时检查 `realDebt×10000 <
    /// newCollateralValue×liquidationLtvBps`（**严格 `<`**，即允许释放到刚好等于清算线，
    /// Binance 语义"允许到 marginCall 上方，用户自担风险"），否则同一拒绝码。成功后若
    /// `loan.is_empty()`（全零死壳）从 map 移除，让同 loanId 可被 `LOAN_CREATE` 复用。
    ///
    /// **事件缺口**：同上，`LOAN_COLLATERAL_CHANGE` 事件本任务不发送。
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
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::core::common::core_currency_specification::CoreCurrencySpecification;
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

    /// 标准治具：spec 上挂 50% initialLtv / 80% liquidationLtv，markPrice=1，无 maxAmount 上限，
    /// 借贷池里已注资 1_000_000 quote，用户账上有 10_000 base 抵押可用。
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

    /// 局部守恒断言（Task 9 前的简化版，参考文档 §6.2）：`accounts[base]+accounts[quote] +
    /// loanPoolAvailable[quote] + loanPoolBorrowed[quote]*0（tracker 不参与）+
    /// interestRevenue[quote]` 里，唯一物理进出的是本任务未建模的外部充值（这里用不到）——
    /// 单测场景下只需要 `accounts总额 + poolAvailable + interestRevenue` 在借/还前后守恒
    /// （借款时 principal 从池子搬进账户，`loanPoolBorrowed` 是 tracker 不参与，见 loan_service
    /// 模块文档）。
    fn conserved_quote_total(engine: &RiskEngine, ups: &UserProfileService) -> i64 {
        let accounts_quote: i64 = ups.users.values().map(|u| u.account(QUOTE)).sum();
        accounts_quote + engine.loan_service.get_loan_pool_available(QUOTE) + engine.loan_service.get_interest_revenue(QUOTE)
    }

    // ================================================================
    // LOAN_CREATE
    // ================================================================

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
        // user only has 10_000 base; ask to pledge more than that (LTV math would still pass
        // since we scale principal down accordingly: collateral=20_000 -> value 20_000, ltv50% -> principal<=10_000).
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

    // ================================================================
    // LOAN_REPAY
    // ================================================================

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

    /// 开一笔标准贷款（LOCKED，年化 rate_bps），供 REPAY/ADD/RELEASE 测试复用。`rate_bps_seed`
    /// 同时清零 `floating_rate.base_bps`（LOCKED 的 openRateBps = floating 当前利率 +
    /// `locked_rate_adjust_bps`，见 `LoanService::open_rate_bps`），让最终 `loan.rate_bps` 精确
    /// 等于 `rate_bps_seed`，不被 floating 的默认 base_bps=200 干扰。
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

    /// `repayAmount=0` pays off principal+interest in full, but REPAY never touches
    /// `collateral_amount` — since `LOAN_CREATE` requires `collateral_amount>0` and
    /// `LOAN_RELEASE_COLLATERAL` refuses to zero collateral while debt remains, a loan can only
    /// ever reach `is_empty()` (all three amount fields zero) via a *subsequent*
    /// `LOAN_RELEASE_COLLATERAL` call once debt is already zero — see
    /// `loan_release_collateral_full_release_allowed_when_debt_fully_repaid_first` for that half.
    /// This test only covers the debt-zeroing half: the loan survives with its collateral intact.
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

    /// `LOAN_UID_MISMATCH` is a defense-in-depth check: through the normal command API a loan
    /// always lives inside the very `UserProfile.isolated_loans` map keyed by its own `uid`
    /// (it's created via `up.isolated_loans.insert(...)` on the `up` looked up by `cmd.uid`), so
    /// two distinct real users can never collide on the same map. The only way to exercise this
    /// branch is to directly graft a record with a foreign `uid` into a user's map (mirrors what
    /// an object-pool reuse bug would look like in Java) — done here on purpose to prove the
    /// dispatcher trusts `loan.uid`, not just map ownership.
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
        // Simulate the user having spent the borrowed funds elsewhere, leaving too little to
        // repay. Request 200 (< payoff, so it is NOT capped up to full payoff) against a balance
        // of only 50 -> free(50) < actualRepay(200).
        ups.get_mut(UID).unwrap().accounts.insert(QUOTE, 50);
        let mut cmd = repay_cmd(2, 42, 200, 1_000);
        assert_eq!(
            LoanCommandDispatcher::dispatch(&mut engine, &mut cmd, &mut ups, &ssp),
            CommandResultCode::LoanAccountInsufficient
        );
    }

    // ================================================================
    // LOAN_ADD_COLLATERAL
    // ================================================================

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

    /// See the `loan_repay_rejects_uid_mismatch` doc comment for why this can only be exercised
    /// by directly grafting a foreign-`uid` record into the map.
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

    // ================================================================
    // LOAN_RELEASE_COLLATERAL
    // ================================================================

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

        // release 499 -> newCollateral=501, value=501 (markPrice=1) -> realDebt(400)*10000=4_000_000
        // vs newCollateralValue(501)*8000=4_008_000 -> lhs<rhs -> allowed.
        let mut cmd = release_cmd(2, 42, 499, 2_000);
        assert_eq!(LoanCommandDispatcher::dispatch(&mut engine, &mut cmd, &mut ups, &ssp), CommandResultCode::Success);
        assert_eq!(ups.get(UID).unwrap().isolated_loans.get(&42).unwrap().collateral_amount, 501);
    }

    #[test]
    fn loan_release_collateral_rejects_at_or_below_liquidation_line() {
        let (mut engine, mut ups, ssp) = setup();
        open_loan(&mut engine, &mut ups, &ssp, 42, 1_000, 400, None);

        // release 500 -> newCollateral=500, value=500 -> lhs=400*10000=4_000_000 vs rhs=500*8000=4_000_000
        // -> lhs>=rhs -> rejected (strict `<` required to allow).
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
        // repay with 0 debt and 0 interest closes the loan outright (isEmpty after outstanding=0,
        // accumulated_interest=0, but collateral_amount still 1000) -> loan NOT removed yet since
        // collateral remains; now release the collateral fully.
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

    /// See the `loan_repay_rejects_uid_mismatch` doc comment for why this can only be exercised
    /// by directly grafting a foreign-`uid` record into the map.
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

    // ================================================================
    // dispatch fallthrough for not-yet-implemented is_loan() codes
    // ================================================================

    #[test]
    fn dispatch_returns_not_implemented_for_unimplemented_loan_codes() {
        let (mut engine, mut ups, ssp) = setup();
        for command in [
            OrderCommandType::LoanCrossAddCollateral,
            OrderCommandType::LoanCrossWithdrawCollateral,
            OrderCommandType::LoanCrossBorrow,
            OrderCommandType::LoanCrossRepay,
            OrderCommandType::LoanCrossForceLiquidate,
            OrderCommandType::LoanForceLiquidate,
            OrderCommandType::PoolDeposit,
            OrderCommandType::PoolWithdraw,
            OrderCommandType::LoanIfDeposit,
            OrderCommandType::LoanIfWithdraw,
        ] {
            let mut cmd = OrderCommand { command, uid: UID, ..Default::default() };
            assert_eq!(
                LoanCommandDispatcher::dispatch(&mut engine, &mut cmd, &mut ups, &ssp),
                CommandResultCode::LoanNotImplemented,
                "{command:?} should not be implemented yet"
            );
        }
    }
}
