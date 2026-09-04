//! 对应 Java `LoanService`：per-shard 单例，纯状态 + 纯函数工具类，不持有 `RiskEngine` 引用（`loan.md` line 105），参考文档 §1.6/§3/§4/§6。
use std::collections::BTreeMap;

use crate::core::common::cmd::command_result_code::CommandResultCode;
use crate::core::common::core_currency_specification::CoreCurrencySpecification;
use crate::core::common::core_symbol_specification::CoreSymbolSpecification;
use crate::core::common::isolated_loan_record::LoanRateMode;
use crate::core::common::loan_record::LoanRecord;
use crate::core::common::user_profile::UserProfile;
use crate::core::processors::loan::loan_global_config::LoanGlobalConfig;
use crate::core::processors::loan::rate::fixed_rate_model::FixedRateModel;
use crate::core::processors::loan::rate::floating_rate_model::FloatingRateModel;
use crate::core::processors::symbol_specification_provider::SymbolSpecificationProvider;
use crate::core::utils::core_arithmetic_utils as arithmetic;

/// 对应 Java `LoanService.YEAR_MS`（`:41`）：1 年（ms），跨节点唯一确定性形式，不依赖日历/闰年。
pub const YEAR_MS: i64 = 365 * 24 * 3600 * 1_000;
/// 对应 Java `LoanService.BPS_SCALE`（`:42`）：bps 精度基准（10000 = 100%）。
pub const BPS_SCALE: i64 = 10_000;

/// force-sell orderId 命名空间/子类型/位掩码（对应 Java `LoanService.java:44-49`），顶字节 `'L'` 独占命名空间，避开期货 `'I'`/ADL `'A'`。
pub const ORDERID_NAMESPACE_TAG: i64 = 0x4C; // 'L'
pub const ORDERID_SUBTYPE_ISOLATED: i64 = 0x53; // 'S'
pub const ORDERID_SUBTYPE_CROSS: i64 = 0x43; // 'C'
const ORDERID_UID_MASK: i64 = 0xF_FFFF; // 20 bit uid hash
const ORDERID_LOANID_MASK: i64 = 0xFFFF; // 16 bit loanId hash
const ORDERID_TS_MASK: i64 = 0xFFF; // 12 bit 秒

/// 对应 Java `Math.multiplyExact(long, long)`：局部私有重复一份（arithmetic 层零依赖 ruling）。
fn mul_exact(a: i64, b: i64) -> i64 {
    i64::try_from(a as i128 * b as i128).unwrap_or_else(|_| panic!("overflow: {a} * {b}"))
}

/// 对应 Java `Math.addExact(long, long)`。
fn add_exact(a: i64, b: i64) -> i64 {
    i64::try_from(a as i128 + b as i128).unwrap_or_else(|_| panic!("overflow: {a} + {b}"))
}

/// 对应 Java `crossLtvBps`（`:220-263`）里 try/catch 溢出折哨兵值的 `Math.addExact` 用法，与其余 panic-on-overflow 的 `add_exact` 语义不同故单独起名。
fn checked_add_i64(a: i64, b: i64) -> Option<i64> {
    i64::try_from(a as i128 + b as i128).ok()
}

/// 对应 Java `LoanService`（字段子集，`:51-59`）：4 个资金桶进 raft snapshot 参与全局守恒对账（§6），`loan_pool_borrowed` 是 tracker 不参与守恒（§6.2），`BTreeMap` 保确定序（禁 HashMap）。
#[derive(Debug, Clone, PartialEq, Eq, Default, serde::Serialize, serde::Deserialize)]
pub struct LoanService {
    pub loan_pool_available: BTreeMap<i32, i64>,
    pub loan_pool_borrowed: BTreeMap<i32, i64>,
    pub interest_revenue: BTreeMap<i32, i64>,
    /// LIF 保险基金，允许为负（接管坏账的被动结果，非运营透支）。
    pub loan_insurance_fund: BTreeMap<i32, i64>,
    /// 全局运行时配置（Cross 阈值 / pool 利用率上限 / numeraire）。
    pub global_config: LoanGlobalConfig,
    /// 活期利率：Isolated FLOATING + 全部 Cross。
    pub floating_rate: FloatingRateModel,
    /// 定期利率：Isolated LOCKED，开仓时锚定 `floating_rate` 当前利率（不持有其引用字段，见 `rate::fixed_rate_model` 模块文档）。
    pub fixed_rate: FixedRateModel,
}

impl LoanService {
    /// 对应 Java `LoanService()` 构造器（`:61-69`）：全部桶空、各字段默认值。
    pub fn new() -> Self {
        LoanService::default()
    }

    // 桶存取：对应 Java `IntLongHashMap.get`/`.addToValue`（缺省 0，delta 可为负）。

    pub fn get_loan_pool_available(&self, currency: i32) -> i64 {
        *self.loan_pool_available.get(&currency).unwrap_or(&0)
    }

    pub fn add_to_loan_pool_available(&mut self, currency: i32, delta: i64) {
        *self.loan_pool_available.entry(currency).or_insert(0) += delta;
    }

    pub fn get_loan_pool_borrowed(&self, currency: i32) -> i64 {
        *self.loan_pool_borrowed.get(&currency).unwrap_or(&0)
    }

    pub fn add_to_loan_pool_borrowed(&mut self, currency: i32, delta: i64) {
        *self.loan_pool_borrowed.entry(currency).or_insert(0) += delta;
    }

    pub fn get_interest_revenue(&self, currency: i32) -> i64 {
        *self.interest_revenue.get(&currency).unwrap_or(&0)
    }

    pub fn add_to_interest_revenue(&mut self, currency: i32, delta: i64) {
        *self.interest_revenue.entry(currency).or_insert(0) += delta;
    }

    pub fn get_loan_insurance_fund(&self, currency: i32) -> i64 {
        *self.loan_insurance_fund.get(&currency).unwrap_or(&0)
    }

    pub fn add_to_loan_insurance_fund(&mut self, currency: i32, delta: i64) {
        *self.loan_insurance_fund.entry(currency).or_insert(0) += delta;
    }

    // 利率模型二分派：对应 Java `accrueTo`/`calculateDisplayInterest`（`:123-131`），按 `loan.isFixedRate()` 分派。

    /// 写路径：补计截至 `now` 的利息进 `loan.accumulated_interest` 并推进游标，返回本次新增利息（≥ 0）。
    pub fn accrue_to<L: LoanRecord>(&self, loan: &mut L, now: i64) -> i64 {
        if loan.is_fixed_rate() {
            self.fixed_rate.accrue(loan, now)
        } else {
            self.floating_rate.accrue(loan, now)
        }
    }

    /// 读路径：`accumulated_interest` + 截至 `now` 的 pending 利息，不改 loan（展示/强平判定用）。
    pub fn calculate_display_interest<L: LoanRecord>(&self, loan: &L, now: i64) -> i64 {
        if loan.is_fixed_rate() {
            self.fixed_rate.display_interest(loan, now)
        } else {
            self.floating_rate.display_interest(loan, now)
        }
    }

    // ================================================================
    // Task 4：金钱原语，对应 Java LoanCommandDispatcher.java:1045-1069 + LoanService.java:138-152,412-420
    // ================================================================

    /// 对应 handleLoanCreate 处 `openRateBps` 二选一分派（`:186-188`）：按 rate_mode 选 floating 当前利率 / fixed 派生利率。
    pub fn open_rate_bps(&self, rate_mode: LoanRateMode, loan_currency: i32) -> i32 {
        match rate_mode {
            LoanRateMode::Floating => self.floating_rate.open_rate_bps(loan_currency),
            LoanRateMode::Locked => self.fixed_rate.open_rate_bps(&self.floating_rate, loan_currency),
        }
    }

    /// 对应 Java 私有静态 `verifyPoolCapacity`（`:1045-1062`）：池容量+利用率校验，LOAN_CREATE 与 LOAN_CROSS_BORROW 共用，逐字对齐 Java。
    pub fn verify_pool_capacity(&self, loan_currency: i32, principal: i64) -> CommandResultCode {
        let available = self.get_loan_pool_available(loan_currency);
        let borrowed = self.get_loan_pool_borrowed(loan_currency);
        if available < principal {
            return CommandResultCode::LoanPoolInsufficient;
        }
        let new_borrowed = add_exact(borrowed, principal);
        let total_pool = add_exact(available, borrowed);
        if total_pool > 0 {
            let new_utilization_scaled = mul_exact(new_borrowed, BPS_SCALE);
            let utilization_cap_scaled =
                mul_exact(total_pool, self.global_config.loan_pool_utilization_cap_bps as i64);
            if new_utilization_scaled > utilization_cap_scaled {
                return CommandResultCode::LoanPoolUtilizationExceeded;
            }
        }
        CommandResultCode::Success
    }

    /// 对应 Java 私有静态 `disburseLoan`（`:1065-1069`）：借款划账——`loanPoolAvailable` →
    /// 用户 `accounts`，`loanPoolBorrowed` 记账 `+principal`（tracker，不参与守恒，见模块文档）。
    pub fn disburse_loan(&mut self, up: &mut UserProfile, loan_currency: i32, principal: i64) {
        up.add_to_account(loan_currency, principal);
        self.add_to_loan_pool_available(loan_currency, -principal);
        self.add_to_loan_pool_borrowed(loan_currency, principal);
    }

    /// 对应 Java `applyDebtPayment`（`:138-152`）：fund 按利息优先、本金其次抵债，封顶未偿本息之和，返回本次抵扣的利息部分；REPAY/强平/Cross 共用。
    pub fn apply_debt_payment<L: LoanRecord>(
        &mut self,
        loan: &mut L,
        account: &mut BTreeMap<i32, i64>,
        fund: i64,
    ) -> i64 {
        let currency = loan.loan_currency();
        let interest_part = fund.min(loan.accumulated_interest());
        let fund_after_interest = fund - interest_part;
        let principal_part = fund_after_interest.min(loan.outstanding_principal());
        let paid = add_exact(interest_part, principal_part);
        *account.entry(currency).or_insert(0) -= paid;
        loan.set_accumulated_interest(loan.accumulated_interest() - interest_part);
        loan.set_outstanding_principal(loan.outstanding_principal() - principal_part);
        loan.set_cum_interest_paid(add_exact(loan.cum_interest_paid(), interest_part)); // 单调累计
        self.add_to_interest_revenue(currency, interest_part);
        self.add_to_loan_pool_available(currency, principal_part);
        self.add_to_loan_pool_borrowed(currency, -principal_part);
        interest_part
    }

    /// 对应 Java 静态 `collateralValueInQuoteCurrency`（`:412-420`）：base amount（base
    /// currencyScale）经 `mark_price` 折算成 quote 等值量（quote currencyScale）——Isolated LTV
    /// 开仓/减抵押判定与 scanner 估值共用。`base_currency_spec`/`quote_currency_spec` 任一缺失
    /// （对应 Java 传 `null`）→ `-1`（价格未就绪，交由上层按各自的 `LOAN_MARKPRICE_NOT_READY`
    /// 语义 skip）。
    pub fn collateral_value_in_quote_currency(
        amount: i64,
        spec: &CoreSymbolSpecification,
        mark_price: i64,
        base_currency_spec: Option<&CoreCurrencySpecification>,
        quote_currency_spec: Option<&CoreCurrencySpecification>,
    ) -> i64 {
        let (base_spec, quote_spec) = match (base_currency_spec, quote_currency_spec) {
            (Some(b), Some(q)) => (b, q),
            _ => return -1,
        };
        let base_amount = arithmetic::convert_scale(amount, base_spec.currency_scale_k, spec.base_scale_k);
        let notional = mul_exact(base_amount, mark_price);
        arithmetic::size_price_to_currency_scale(
            notional,
            spec.base_scale_k,
            spec.quote_scale_k,
            quote_spec.currency_scale_k,
        )
    }

    // ================================================================
    // Task 5：Cross 账户级 LTV —— 参考文档 §3.2/§3.3，Java `LoanService.java:168-269,395-410,467-470`
    // ================================================================

    /// 对应 Java 静态 `collateralWeightForBase`（`:467-470`）：币种作 Cross 抵押的折价率
    /// （bps），直接读币种级 `CoreCurrencySpecification.collateral_weight_bps`；未配置/spec 缺失
    /// 返回 `0`（= 不可作抵押，`LOAN_COLLATERAL_NOT_ALLOWED`）。
    pub fn collateral_weight_for_base(currency: i32, ssp: &SymbolSpecificationProvider) -> i32 {
        ssp.get_currency(currency).map(|s| s.collateral_weight_bps).unwrap_or(0)
    }

    /// 对应 Java 私有静态 `valueInNumeraire`（`:395-410`）：把 `amount`（`currency` 的
    /// currencyScale）折算成 `numeraireCurrency` 的 currencyScale，经 `findSpotSymbol(currency,
    /// numeraireCurrency)` 的现货对 markPrice 折算；`currency == numeraireCurrency` 直接返回
    /// `amount`（同币种恒等）。任一 spec / markPrice 缺失 → `-1` 哨兵（"价格未就绪"，由调用方
    /// 按 `failClosedOnMissingPrice` 决定取舍）。`price_cache` 对应 Java
    /// `IntObjectHashMap<LastPriceCacheRecord> priceCache`——本移植 `RiskEngine.last_price_cache`
    /// 直接就是 `symbol -> markPrice`（无 record 包装），故这里签名比 Java 简单一层。
    pub fn value_in_numeraire(
        currency: i32,
        amount: i64,
        numeraire_currency: i32,
        numeraire_spec: &CoreCurrencySpecification,
        ssp: &SymbolSpecificationProvider,
        price_cache: &std::collections::BTreeMap<i32, i64>,
    ) -> i64 {
        if currency == numeraire_currency {
            return amount;
        }
        let spec = match ssp.find_spot_symbol(currency, numeraire_currency) {
            Some(s) => s,
            None => return -1,
        };
        let mark_price = match price_cache.get(&spec.symbol_id) {
            Some(&p) if p > 0 => p,
            _ => return -1,
        };
        // currency 视作 base、numeraire 视作 quote，复用 Isolated LTV 同一套折算（Java 注释同）。
        let currency_spec = ssp.get_currency(currency);
        Self::collateral_value_in_quote_currency(amount, spec, mark_price, currency_spec, Some(numeraire_spec))
    }

    /// 对应 Java 私有 `crossLtvBps`（`:208-269`）：账户级 LTV 核心，`calculateCrossAccountLtvBps`
    /// （`apply_weight=true`，触发/BORROW/WITHDRAW 用）与 `calculateCrossRawLtvBps`
    /// （`apply_weight=false`，仅定价用）共享。
    ///
    /// `numeraireCurrency` 直接读 `self.global_config.numeraire_currency`——Java 版把它作为显式
    /// 参数传入，但两个公开重载的调用方（`LoanCommandDispatcher`）永远传
    /// `loanService.getGlobalConfig().numeraireCurrency`，即调用方与被调用方本就是同一个
    /// `LoanService` 实例；本移植是 `LoanService` 自己的方法，直接读自身字段更省一次参数传递，
    /// 无行为差异。
    ///
    /// `crossLoans.isEmpty() || numeraireCurrency==0` → `0`（无债或未配置 numeraire，LTV 恒安全）；
    /// numeraireSpec 缺失 → `unevaluable`（`fail_closed_on_missing_price ? i64::MAX : 0`）。
    ///
    /// 三处溢出捕获逐字对齐 Java 的 `try { Math.addExact(...) } catch (ArithmeticException)`：
    /// ①②（debt 侧：单笔 realDebt 相加、累加进 totalDebt）溢出 → **恒定** `i64::MAX`（不受
    /// `fail_closed_on_missing_price` 影响——溢出视作无限大 LTV，倾向拒绝/强平而非放行）；
    /// ③（collateral 侧：weight 折算后累加进 totalCollateral）溢出 → `unevaluable`（溢出不放大
    /// 抵押，保守按不可估值处理）。用 [`checked_add_i64`] 而非 panic-on-overflow 的 `add_exact`
    /// 复刻这个"捕获而非崩溃"的语义。
    ///
    /// `totalCollateral<=0`（无合格抵押币，或全被 `weight<=0` 过滤掉）→ `i64::MAX`（无抵押则
    /// LTV 无穷大，同样不受 `fail_closed_on_missing_price` 影响）。
    fn cross_ltv_bps(
        &self,
        up: &UserProfile,
        now: i64,
        ssp: &SymbolSpecificationProvider,
        price_cache: &std::collections::BTreeMap<i32, i64>,
        fail_closed_on_missing_price: bool,
        apply_weight: bool,
    ) -> i64 {
        let numeraire_currency = self.global_config.numeraire_currency;
        if up.cross_loans.is_empty() || numeraire_currency == 0 {
            return 0;
        }
        let unevaluable = if fail_closed_on_missing_price { i64::MAX } else { 0 };
        let numeraire_spec = match ssp.get_currency(numeraire_currency) {
            Some(s) => s,
            None => return unevaluable,
        };

        // 债务侧：逐笔折算成 numeraire 后求和（pending-interest-inclusive）。
        let mut total_debt: i64 = 0;
        for loan in up.cross_loans.values() {
            if loan.outstanding_principal <= 0 {
                continue;
            }
            let display_interest = self.calculate_display_interest(loan, now);
            let real_debt = match checked_add_i64(loan.outstanding_principal, display_interest) {
                Some(v) => v,
                None => return i64::MAX,
            };
            let value_in_num =
                Self::value_in_numeraire(loan.loan_currency, real_debt, numeraire_currency, numeraire_spec, ssp, price_cache);
            if value_in_num < 0 {
                return unevaluable; // 缺 markPrice / spec
            }
            total_debt = match checked_add_i64(total_debt, value_in_num) {
                Some(v) => v,
                None => return i64::MAX,
            };
        }

        // 抵押侧：折算 numeraire 后求和，apply_weight 决定是否再打 collateralWeightBps 折。
        let mut total_collateral: i64 = 0;
        for (&currency, &amount) in up.cross_loan_collateral.iter() {
            if amount <= 0 {
                continue;
            }
            let weight = Self::collateral_weight_for_base(currency, ssp);
            if weight <= 0 {
                continue; // 非抵押白名单币：两种口径都不计入
            }
            let value_in_num = Self::value_in_numeraire(currency, amount, numeraire_currency, numeraire_spec, ssp, price_cache);
            if value_in_num < 0 {
                return unevaluable;
            }
            let contribution =
                if apply_weight { arithmetic::trunc_mul_div(value_in_num, weight as i64, BPS_SCALE) } else { value_in_num };
            total_collateral = match checked_add_i64(total_collateral, contribution) {
                Some(v) => v,
                None => return unevaluable, // 溢出不放大抵押，保守按不可估值处理
            };
        }

        if total_collateral <= 0 {
            return i64::MAX;
        }
        arithmetic::trunc_mul_div(total_debt, BPS_SCALE, total_collateral)
    }

    /// 对应 Java 公开 `calculateCrossAccountLtvBps(..., boolean failClosedOnMissingPrice)`
    /// （`:184-195`）：**加权**分母口径（`applyWeight=true`），trigger 决策与 Cross
    /// BORROW/WITHDRAW 前置 guard 共用。`fail_closed_on_missing_price`：`true` = 缺价 →
    /// `i64::MAX`（拒绝，BORROW/WITHDRAW 用，防超借/提空）；`false` = 缺价 → `0`（保守 skip，
    /// scanner/展示用）。Java 另有一个 6 参"默认 `failClosedOnMissingPrice=false`"的重载——本
    /// 移植不做重载，调用方必须对这个安全相关的 flag 显式表态。
    pub fn calculate_cross_account_ltv_bps(
        &self,
        up: &UserProfile,
        now: i64,
        ssp: &SymbolSpecificationProvider,
        price_cache: &std::collections::BTreeMap<i32, i64>,
        fail_closed_on_missing_price: bool,
    ) -> i64 {
        self.cross_ltv_bps(up, now, ssp, price_cache, fail_closed_on_missing_price, true)
    }

    /// 对应 Java 公开 `calculateCrossRawLtvBps`（`:201-206`）：**不加权**市值口径
    /// （`applyWeight=false`），仅供破产价定价用（Task 7）——用加权口径定价会把破产价抬高
    /// `1/weight` 倍（loan.md §18.3）。`fail_closed_on_missing_price` 恒 `false`（同 Java 该重载
    /// 固定传 `false`，缺价保守返 0，由调用方兜底）。
    pub fn calculate_cross_raw_ltv_bps(
        &self,
        up: &UserProfile,
        now: i64,
        ssp: &SymbolSpecificationProvider,
        price_cache: &std::collections::BTreeMap<i32, i64>,
    ) -> i64 {
        self.cross_ltv_bps(up, now, ssp, price_cache, false, false)
    }

    // ================================================================
    // Task 7：force-liquidate 结算原语 + Cross LIF 接管 —— 参考文档 §2.5/§2.10/§6.3，
    // Java `LoanService.java:154-166,287-385,412-464`
    // ================================================================

    /// 对应 Java 静态 `lotsToCollateralAmount`（`:429-432`）：强平张数（lot，base symbolScale）→
    /// 抵押金额（base currencyScale）——R1 pre-move 记账用，[`Self::collateral_amount_to_lots`]
    /// 的反向。
    pub fn lots_to_collateral_amount(
        lots: i64,
        spec: &CoreSymbolSpecification,
        base_spec: &CoreCurrencySpecification,
    ) -> i64 {
        arithmetic::symbol_to_currency_scale(lots, spec.base_scale_k, base_spec.currency_scale_k)
    }

    /// 对应 Java 静态 `collateralAmountToLots`（`:422-426`）：抵押金额（base currencyScale）→
    /// 强平下单张数（lot，base symbolScale）；不足一张截断为 0——R2 用"是否还有可卖整张"而非
    /// `collateralAmount==0` 判定尘埃，见参考文档 §2.5。
    pub fn collateral_amount_to_lots(
        amount: i64,
        spec: &CoreSymbolSpecification,
        base_spec: &CoreCurrencySpecification,
    ) -> i64 {
        arithmetic::convert_scale(amount, base_spec.currency_scale_k, spec.base_scale_k)
    }

    /// 对应 Java 静态 `quoteAmountToLots`（`:435-438`）：借款币金额（quote currencyScale）→ 按
    /// `mark_price`（此处传破产价 limit）折算的下单张数（lot，ceil 向上取整不少卖）。Task 8 Cross
    /// scanner `calculate_cross_sell_size` 消费。
    pub fn quote_amount_to_lots(
        quote_amount: i64,
        mark_price: i64,
        spec: &CoreSymbolSpecification,
        quote_spec: &CoreCurrencySpecification,
    ) -> i64 {
        let notional = arithmetic::currency_to_size_price_scale(
            quote_amount,
            spec.base_scale_k,
            spec.quote_scale_k,
            quote_spec.currency_scale_k,
        );
        arithmetic::ceil_divide(notional, mark_price)
    }

    /// 对应 Java 静态 `forceSellOrderId`（`:481-486`）：force-sell orderId 位编码——
    /// `tag<<56 | subtype<<48 | uidHash<<28 | loanIdHash<<12 | tsSec`。`subtype` 取
    /// [`ORDERID_SUBTYPE_ISOLATED`]/[`ORDERID_SUBTYPE_CROSS`]。`tickTimeMs` 用触发命令 timestamp
    /// （确定性，同 futures `generate_liquidation_order_id` 的偏差，leader-only 生成经 raft 复制）。
    pub fn force_sell_order_id(subtype: i64, uid: i64, loan_id: i64, tick_time_ms: i64) -> i64 {
        let uid_hash = (uid.wrapping_mul(31).wrapping_add(17)) & ORDERID_UID_MASK;
        let loan_id_hash = (loan_id.wrapping_mul(31).wrapping_add(17)) & ORDERID_LOANID_MASK;
        let ts_sec = (tick_time_ms / 1000) & ORDERID_TS_MASK;
        (ORDERID_NAMESPACE_TAG << 56) | (subtype << 48) | (uid_hash << 28) | (loan_id_hash << 12) | ts_sec
    }

    /// 对应 Java `settleLiquidationProceeds`（`:159-166`）：强平所得 `received_quote`（已扣撮合
    /// takerFee）的统一去向——先按 `loanLiquidationFeeBps` 抽强平费（ceil 向交易所取整，不少收）
    /// 进 `loan_insurance_fund`，再 `accrue_to` 补计利息后走 [`Self::apply_debt_payment`] 抵债，
    /// 剩余 overpay 留在 `account`。返回本次结算的利息部分（≥ 0）。Isolated / Cross 强平共用。
    pub fn settle_liquidation_proceeds<L: LoanRecord>(
        &mut self,
        loan: &mut L,
        account: &mut BTreeMap<i32, i64>,
        received_quote: i64,
        now: i64,
    ) -> i64 {
        let fee_by_rate = arithmetic::ceil_mul_div(received_quote, self.global_config.loan_liquidation_fee_bps as i64, BPS_SCALE);
        let liq_fee = received_quote.min(fee_by_rate);
        let currency = loan.loan_currency();
        *account.entry(currency).or_insert(0) -= liq_fee;
        self.add_to_loan_insurance_fund(currency, liq_fee);
        self.accrue_to(loan, now);
        self.apply_debt_payment(loan, account, received_quote - liq_fee)
    }

    /// 对应 Java 静态 `isStructurallySellable`（`:446-464`）：该抵押币是否**结构上可变现**——只看
    /// 永久能力，不看 `markPrice` 这类临时状态。`collateral_weight_bps > 0`（币种级白名单）且存在
    /// base=该币、quote=本账户某笔未偿 Cross 债币种的现货对、量够 ≥1 lot（卖了能真的还上债）。
    /// 与 `LoanLiquidationEngine.pickCrossCollateralToSell` 的永久性条件同源（P6 范围，未移植，
    /// 本函数独立成立）。
    pub fn is_structurally_sellable(
        currency: i32,
        amount: i64,
        up: &UserProfile,
        ssp: &SymbolSpecificationProvider,
    ) -> bool {
        if amount <= 0 {
            return false;
        }
        let currency_spec = match ssp.get_currency(currency) {
            Some(s) if s.collateral_weight_bps > 0 => s,
            _ => return false,
        };
        for loan in up.cross_loans.values() {
            if loan.outstanding_principal <= 0 {
                continue;
            }
            if let Some(spec) = ssp.find_spot_symbol(currency, loan.loan_currency) {
                if Self::collateral_amount_to_lots(amount, spec, currency_spec) > 0 {
                    return true;
                }
            }
        }
        false
    }

    /// 对应 Java `takeOverCrossLoan`（`:287-385`）：Cross LIF 承接——按 `target_loan_id` 债务占
    /// 账户总债的比例，从共享抵押池按 `collateralWeightBps` 降序、同权重按 currency 升序**定额**
    /// 扣走等值抵押（不逐币种等比切，避免尘埃碎片化，见参考文档 §6.3）。**fail-closed**：任一
    /// 价格/spec 缺失 → 返回 `false`，调用方须保留 loan 原样、不使用失真价格。
    ///
    /// 只触碰 `up.cross_loan_collateral`/`up.accounts`（真实扣抵押）与 `self` 的 3 个资金桶
    /// （LIF/poolAvailable/poolBorrowed/interestRevenue）——**不**清零 `targetLoan` 本身的
    /// 本金/利息字段，那是调用方（`LoanCommandDispatcher::close_and_recycle_cross_loan`）的职责，
    /// 逐字对齐 Java：`takeOverCrossLoan` 只管钱，调用方决定何时清账 + 摘出 map。
    ///
    /// 排序确定性是硬要求（R2 在所有副本执行，哈希序会导致状态分叉）——`BTreeMap` 天然升序
    /// 迭代 + 显式按 weight 降序/currency 升序排序，逐字对齐 Java `Arrays.sort` 的比较器。
    pub fn take_over_cross_loan(
        &mut self,
        up: &mut UserProfile,
        target_loan_id: i64,
        now: i64,
        ssp: &SymbolSpecificationProvider,
        price_cache: &BTreeMap<i32, i64>,
    ) -> bool {
        let numeraire_currency = self.global_config.numeraire_currency;
        if numeraire_currency == 0 {
            return false;
        }
        let numeraire_spec = match ssp.get_currency(numeraire_currency) {
            Some(s) => s,
            None => return false,
        };

        let (target_loan_currency, target_outstanding_principal) = match up.cross_loans.get(&target_loan_id) {
            Some(l) => (l.loan_currency, l.outstanding_principal),
            None => return false, // caller guarantees existence; fail-closed if it somehow doesn't
        };
        let target_debt = {
            let loan = up.cross_loans.get(&target_loan_id).expect("checked above");
            add_exact(loan.outstanding_principal, self.calculate_display_interest(loan, now))
        };
        let target_debt_in_num =
            Self::value_in_numeraire(target_loan_currency, target_debt, numeraire_currency, numeraire_spec, ssp, price_cache);
        if target_debt_in_num < 0 {
            return false;
        }

        let mut total_debt_in_num: i64 = 0;
        for loan in up.cross_loans.values() {
            let debt = add_exact(loan.outstanding_principal, self.calculate_display_interest(loan, now));
            if debt <= 0 {
                continue;
            }
            let v = Self::value_in_numeraire(loan.loan_currency, debt, numeraire_currency, numeraire_spec, ssp, price_cache);
            if v < 0 {
                return false;
            }
            total_debt_in_num = add_exact(total_debt_in_num, v);
        }
        if total_debt_in_num <= 0 {
            return false;
        }

        // 抵押币按 weight 降序、currency 升序排定，保证各副本扣减顺序一致。
        let mut ordered: Vec<i32> = up.cross_loan_collateral.keys().copied().collect();
        ordered.sort_by(|&a, &b| {
            let wa = Self::collateral_weight_for_base(a, ssp);
            let wb = Self::collateral_weight_for_base(b, ssp);
            wb.cmp(&wa).then(a.cmp(&b))
        });

        let mut total_collateral_in_num: i64 = 0;
        for &currency in &ordered {
            let amount = *up.cross_loan_collateral.get(&currency).unwrap_or(&0);
            // 零权重币不撑 LTV（口径同 calculateCrossAccountLtvBps），接管也不能取。
            if amount <= 0 || Self::collateral_weight_for_base(currency, ssp) <= 0 {
                continue;
            }
            let v = Self::value_in_numeraire(currency, amount, numeraire_currency, numeraire_spec, ssp, price_cache);
            if v < 0 {
                return false;
            }
            total_collateral_in_num = add_exact(total_collateral_in_num, v);
        }

        // 应取估值 = 账户抵押总值 × 该笔债占比。不足一张的尘埃在 numeraire 估值中截断为 0，
        // 因而分摊不到、留给借款人——LIF 不囤无法变现的碎屑。
        let mut remaining_to_take =
            arithmetic::trunc_mul_div(total_collateral_in_num, target_debt_in_num, total_debt_in_num);
        for &currency in &ordered {
            if remaining_to_take <= 0 {
                break;
            }
            let amount = *up.cross_loan_collateral.get(&currency).unwrap_or(&0);
            if amount <= 0 {
                continue;
            }
            let value_in_num = Self::value_in_numeraire(currency, amount, numeraire_currency, numeraire_spec, ssp, price_cache);
            if value_in_num <= 0 {
                continue;
            }
            let take = if value_in_num <= remaining_to_take {
                amount
            } else {
                arithmetic::trunc_mul_div(amount, remaining_to_take, value_in_num)
            };
            if take <= 0 {
                continue;
            }
            up.add_to_cross_loan_collateral(currency, -take);
            up.add_to_account(currency, -take); // 抵押原为虚拟锁定，接管时真实扣走
            self.add_to_loan_insurance_fund(currency, take);
            remaining_to_take -= value_in_num.min(remaining_to_take);
        }

        // LIF 代偿债务：池子回血、利息落收入，LIF 转负（负值即已垫资额，非损失）。
        self.add_to_loan_insurance_fund(target_loan_currency, -target_debt);
        self.add_to_loan_pool_available(target_loan_currency, target_outstanding_principal);
        self.add_to_loan_pool_borrowed(target_loan_currency, -target_outstanding_principal);
        self.add_to_interest_revenue(target_loan_currency, add_exact(target_debt, -target_outstanding_principal));
        true
    }

    /// 确定性状态 hash：折叠排序后的 4 个资金桶 + `global_config`/`floating_rate`/`fixed_rate`
    /// 各自的 `state_hash()`。风格对齐 `UserProfile::state_hash`（`h=h*31+field` 滚动折叠）；
    /// 不保证与 Java `Objects.hash(...)`-style 数值相等，只保证「同状态 -> 同 hash，不同状态 ->
    /// 不同 hash」。
    pub fn state_hash(&self) -> i32 {
        let mut h: i64 = 17;
        for (&cur, &amt) in &self.loan_pool_available {
            h = h.wrapping_mul(31).wrapping_add(cur as i64);
            h = h.wrapping_mul(31).wrapping_add(amt);
        }
        for (&cur, &amt) in &self.loan_pool_borrowed {
            h = h.wrapping_mul(31).wrapping_add(cur as i64);
            h = h.wrapping_mul(31).wrapping_add(amt);
        }
        for (&cur, &amt) in &self.interest_revenue {
            h = h.wrapping_mul(31).wrapping_add(cur as i64);
            h = h.wrapping_mul(31).wrapping_add(amt);
        }
        for (&cur, &amt) in &self.loan_insurance_fund {
            h = h.wrapping_mul(31).wrapping_add(cur as i64);
            h = h.wrapping_mul(31).wrapping_add(amt);
        }
        h = h.wrapping_mul(31).wrapping_add(self.global_config.state_hash() as i64);
        h = h.wrapping_mul(31).wrapping_add(self.floating_rate.state_hash() as i64);
        h = h.wrapping_mul(31).wrapping_add(self.fixed_rate.state_hash() as i64);
        ((h >> 32) as i32) ^ (h as i32)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn new_has_empty_buckets_and_default_config() {
        let s = LoanService::new();
        assert!(s.loan_pool_available.is_empty());
        assert!(s.loan_pool_borrowed.is_empty());
        assert!(s.interest_revenue.is_empty());
        assert!(s.loan_insurance_fund.is_empty());
        assert_eq!(s.global_config, LoanGlobalConfig::default());
        assert_eq!(s.floating_rate, FloatingRateModel::default());
        assert_eq!(s.fixed_rate, FixedRateModel::default());
    }

    #[test]
    fn get_defaults_to_zero_for_unseen_currency() {
        let s = LoanService::new();
        assert_eq!(s.get_loan_pool_available(1), 0);
        assert_eq!(s.get_loan_pool_borrowed(1), 0);
        assert_eq!(s.get_interest_revenue(1), 0);
        assert_eq!(s.get_loan_insurance_fund(1), 0);
    }

    #[test]
    fn add_to_loan_pool_available_accumulates_and_allows_negative_delta() {
        let mut s = LoanService::new();
        s.add_to_loan_pool_available(1, 1_000);
        s.add_to_loan_pool_available(1, -300);
        assert_eq!(s.get_loan_pool_available(1), 700);
        assert_eq!(s.get_loan_pool_available(2), 0); // 未涉及币种仍缺省 0
    }

    #[test]
    fn add_to_loan_pool_borrowed_accumulates() {
        let mut s = LoanService::new();
        s.add_to_loan_pool_borrowed(1, 500);
        s.add_to_loan_pool_borrowed(1, 250);
        assert_eq!(s.get_loan_pool_borrowed(1), 750);
    }

    #[test]
    fn add_to_interest_revenue_accumulates() {
        let mut s = LoanService::new();
        s.add_to_interest_revenue(1, 10);
        s.add_to_interest_revenue(1, 5);
        assert_eq!(s.get_interest_revenue(1), 15);
    }

    #[test]
    fn add_to_loan_insurance_fund_allows_negative_balance() {
        let mut s = LoanService::new();
        s.add_to_loan_insurance_fund(1, -100); // LIF 接管坏账场景：允许为负
        assert_eq!(s.get_loan_insurance_fund(1), -100);
        s.add_to_loan_insurance_fund(1, 40);
        assert_eq!(s.get_loan_insurance_fund(1), -60);
    }

    #[test]
    fn state_hash_deterministic_for_same_state() {
        let mut a = LoanService::new();
        a.add_to_loan_pool_available(1, 100);
        let mut b = LoanService::new();
        b.add_to_loan_pool_available(1, 100);
        assert_eq!(a.state_hash(), b.state_hash());
    }

    #[test]
    fn state_hash_changes_with_each_bucket_and_substructure() {
        let base = LoanService::new();
        let h0 = base.state_hash();

        let mut diff_available = LoanService::new();
        diff_available.add_to_loan_pool_available(1, 1);
        assert_ne!(h0, diff_available.state_hash());

        let mut diff_borrowed = LoanService::new();
        diff_borrowed.add_to_loan_pool_borrowed(1, 1);
        assert_ne!(h0, diff_borrowed.state_hash());

        let mut diff_interest = LoanService::new();
        diff_interest.add_to_interest_revenue(1, 1);
        assert_ne!(h0, diff_interest.state_hash());

        let mut diff_if = LoanService::new();
        diff_if.add_to_loan_insurance_fund(1, 1);
        assert_ne!(h0, diff_if.state_hash());

        let mut diff_config = LoanService::new();
        diff_config.global_config.numeraire_currency = 1;
        assert_ne!(h0, diff_config.state_hash());

        let mut diff_floating = LoanService::new();
        diff_floating.floating_rate.base_bps = 999;
        assert_ne!(h0, diff_floating.state_hash());

        let mut diff_fixed = LoanService::new();
        diff_fixed.fixed_rate.locked_rate_adjust_bps = 5;
        assert_ne!(h0, diff_fixed.state_hash());
    }

    use crate::core::common::isolated_loan_record::{IsolatedLoanRecord, LoanRateMode};

    #[test]
    fn accrue_to_dispatches_fixed_loans_to_fixed_rate_model() {
        let s = LoanService::new();
        let mut fixed_loan = IsolatedLoanRecord::new(1, 1, 100, 10, 20, 5_000 /* 50% */, 0);
        fixed_loan.set_outstanding_principal(1_000_000);
        assert!(fixed_loan.is_fixed_rate()); // default rate_mode = Locked

        let delta = s.accrue_to(&mut fixed_loan, YEAR_MS);

        assert_eq!(delta, 500_000); // simple interest, matches FixedRateModel::accrue directly
        assert_eq!(fixed_loan.accumulated_interest(), 500_000);
    }

    #[test]
    fn accrue_to_dispatches_floating_loans_to_floating_rate_model() {
        let mut s = LoanService::new();
        s.floating_rate.last_reprice_ts = 1_000;
        s.floating_rate.current_rate_bps.insert(20, 500); // 5%

        let mut floating_loan = IsolatedLoanRecord::new(2, 2, 100, 10, 20, 0, 1_000);
        floating_loan.rate_mode = LoanRateMode::Floating;
        floating_loan.set_outstanding_principal(315_360_000_000);
        assert!(!floating_loan.is_fixed_rate());

        let delta = s.accrue_to(&mut floating_loan, 3_000); // 2000ms since last_reprice_ts at 5%

        assert_eq!(delta, 1_000);
        assert_eq!(floating_loan.accumulated_interest(), 1_000);
        assert_eq!(floating_loan.acc_snapshot(), 1_000_000); // cursor advanced to live acc
    }

    #[test]
    fn calculate_display_interest_dispatches_by_is_fixed_rate_and_does_not_mutate() {
        let s = LoanService::new();

        let mut fixed_loan = IsolatedLoanRecord::new(1, 1, 100, 10, 20, 5_000, 0);
        fixed_loan.set_outstanding_principal(1_000_000);
        assert_eq!(s.calculate_display_interest(&fixed_loan, YEAR_MS), 500_000);
        assert_eq!(fixed_loan.accumulated_interest(), 0); // unchanged: read path

        let mut floating_loan = IsolatedLoanRecord::new(2, 2, 100, 10, 20, 0, 1_000);
        floating_loan.rate_mode = LoanRateMode::Floating;
        floating_loan.set_outstanding_principal(315_360_000_000);
        let mut s2 = LoanService::new();
        s2.floating_rate.last_reprice_ts = 1_000;
        s2.floating_rate.current_rate_bps.insert(20, 500);
        assert_eq!(s2.calculate_display_interest(&floating_loan, 3_000), 1_000);
        assert_eq!(floating_loan.accumulated_interest(), 0); // unchanged: read path
    }

    // ====================================================================
    // Task 4：open_rate_bps / verify_pool_capacity / disburse_loan /
    // apply_debt_payment / collateral_value_in_quote_currency
    // ====================================================================

    #[test]
    fn open_rate_bps_dispatches_floating_directly() {
        let mut s = LoanService::new();
        s.floating_rate.current_rate_bps.insert(20, 777);
        assert_eq!(s.open_rate_bps(LoanRateMode::Floating, 20), 777);
    }

    #[test]
    fn open_rate_bps_locked_derives_from_floating_plus_spread() {
        let mut s = LoanService::new();
        s.floating_rate.current_rate_bps.insert(20, 700);
        s.fixed_rate.locked_rate_adjust_bps = 50;
        assert_eq!(s.open_rate_bps(LoanRateMode::Locked, 20), 750);
    }

    #[test]
    fn verify_pool_capacity_rejects_when_available_below_principal() {
        let mut s = LoanService::new();
        s.add_to_loan_pool_available(1, 100);
        assert_eq!(s.verify_pool_capacity(1, 101), CommandResultCode::LoanPoolInsufficient);
        // 100% utilization would exceed the default 90% cap (see the utilization test below),
        // so pick a principal within both the available AND the utilization limit.
        assert_eq!(s.verify_pool_capacity(1, 90), CommandResultCode::Success);
    }

    #[test]
    fn verify_pool_capacity_rejects_when_utilization_exceeds_cap() {
        let mut s = LoanService::new();
        // default cap = 9000 bps (90%). available=1000, borrowed=8000 -> total=9000.
        // borrowing 200 more -> newBorrowed=8200, util=8200/9000=91.1% > 90% -> exceeded.
        s.add_to_loan_pool_available(1, 1_000);
        s.add_to_loan_pool_borrowed(1, 8_000);
        assert_eq!(s.verify_pool_capacity(1, 200), CommandResultCode::LoanPoolUtilizationExceeded);
        // borrowing exactly to the cap boundary succeeds: newBorrowed=8900, util=8900/9000=98.9%...
        // pick a principal that lands exactly at the cap: need newBorrowed/9000 <= 0.9 -> newBorrowed<=8100.
        assert_eq!(s.verify_pool_capacity(1, 100), CommandResultCode::Success); // newBorrowed=8100 = 90% exactly
    }

    #[test]
    fn verify_pool_capacity_skips_utilization_check_when_pool_untouched() {
        let s = LoanService::new(); // available=borrowed=0 for currency 1 -> total_pool<=0
        // available(0) < principal(1) triggers LoanPoolInsufficient before utilization is ever checked.
        assert_eq!(s.verify_pool_capacity(1, 1), CommandResultCode::LoanPoolInsufficient);
        // principal=0 never fails the available check and never touches the (skipped) utilization branch.
        assert_eq!(s.verify_pool_capacity(1, 0), CommandResultCode::Success);
    }

    #[test]
    fn disburse_loan_moves_principal_from_pool_available_to_account_and_marks_borrowed() {
        let mut s = LoanService::new();
        s.add_to_loan_pool_available(1, 1_000);
        let mut up = UserProfile::new(1, crate::core::common::user_status::UserStatus::Active);

        s.disburse_loan(&mut up, 1, 300);

        assert_eq!(up.account(1), 300);
        assert_eq!(s.get_loan_pool_available(1), 700);
        assert_eq!(s.get_loan_pool_borrowed(1), 300);
    }

    #[test]
    fn apply_debt_payment_pays_interest_before_principal() {
        let mut s = LoanService::new();
        let mut loan = IsolatedLoanRecord::new(1, 1, 100, 10, 20, 500, 0);
        loan.set_outstanding_principal(1_000);
        loan.set_accumulated_interest(100);
        let mut accounts: BTreeMap<i32, i64> = BTreeMap::new();
        accounts.insert(20, 10_000);

        // fund=150 covers all interest (100) plus 50 of principal.
        let interest_paid = s.apply_debt_payment(&mut loan, &mut accounts, 150);

        assert_eq!(interest_paid, 100);
        assert_eq!(loan.accumulated_interest(), 0);
        assert_eq!(loan.outstanding_principal(), 950);
        assert_eq!(loan.cum_interest_paid(), 100);
        assert_eq!(*accounts.get(&20).unwrap(), 10_000 - 150);
        assert_eq!(s.get_interest_revenue(20), 100);
        assert_eq!(s.get_loan_pool_available(20), 50);
        assert_eq!(s.get_loan_pool_borrowed(20), -50); // no prior borrowed tracked in this isolated test
    }

    #[test]
    fn apply_debt_payment_full_payoff_zeroes_both_debt_fields() {
        let mut s = LoanService::new();
        let mut loan = IsolatedLoanRecord::new(1, 1, 100, 10, 20, 500, 0);
        loan.set_outstanding_principal(1_000);
        loan.set_accumulated_interest(100);
        let mut accounts: BTreeMap<i32, i64> = BTreeMap::new();

        s.apply_debt_payment(&mut loan, &mut accounts, 1_100);

        assert_eq!(loan.accumulated_interest(), 0);
        assert_eq!(loan.outstanding_principal(), 0);
        assert!(*accounts.get(&20).unwrap() < 0); // test never funded the account; only checking the delta applied
    }

    #[test]
    fn collateral_value_in_quote_currency_scales_through_symbol_and_currency() {
        let spec = CoreSymbolSpecification {
            symbol_id: 1,
            base_currency: 1,
            quote_currency: 2,
            base_scale_k: 1,
            quote_scale_k: 1,
            ..Default::default()
        };
        let base_spec = CoreCurrencySpecification { currency: 1, currency_scale_k: 1, ..Default::default() };
        let quote_spec = CoreCurrencySpecification { currency: 2, currency_scale_k: 1, ..Default::default() };

        // amount=10 (base) * markPrice=5 -> notional=50, scale-identity -> 50 (quote).
        let value = LoanService::collateral_value_in_quote_currency(10, &spec, 5, Some(&base_spec), Some(&quote_spec));
        assert_eq!(value, 50);
    }

    #[test]
    fn collateral_value_in_quote_currency_returns_negative_one_when_a_currency_spec_is_missing() {
        let spec = CoreSymbolSpecification {
            symbol_id: 1,
            base_currency: 1,
            quote_currency: 2,
            base_scale_k: 1,
            quote_scale_k: 1,
            ..Default::default()
        };
        let base_spec = CoreCurrencySpecification { currency: 1, currency_scale_k: 1, ..Default::default() };

        assert_eq!(LoanService::collateral_value_in_quote_currency(10, &spec, 5, Some(&base_spec), None), -1);
        assert_eq!(LoanService::collateral_value_in_quote_currency(10, &spec, 5, None, Some(&base_spec)), -1);
        assert_eq!(LoanService::collateral_value_in_quote_currency(10, &spec, 5, None, None), -1);
    }

    // ====================================================================
    // Task 5：Cross 账户级 LTV —— collateral_weight_for_base / value_in_numeraire /
    // calculate_cross_account_ltv_bps (weighted) / calculate_cross_raw_ltv_bps (unweighted)
    // ====================================================================

    use crate::core::common::cross_loan_record::CrossLoanRecord;

    const COLLATERAL_CUR: i32 = 1; // base，Cross 抵押币，weight<100%
    const NUMERAIRE_CUR: i32 = 2; // quote，同时充作 loanCurrency，免去 debt 侧折算
    const SPOT_SYMBOL: i32 = 100; // base=COLLATERAL_CUR / quote=NUMERAIRE_CUR

    fn cross_fixture(weight_bps: i32) -> (SymbolSpecificationProvider, std::collections::BTreeMap<i32, i64>) {
        let mut ssp = SymbolSpecificationProvider::new();
        ssp.add_symbol(CoreSymbolSpecification {
            symbol_id: SPOT_SYMBOL,
            symbol_type: crate::core::common::symbol_type::SymbolType::CurrencyExchangePair,
            base_currency: COLLATERAL_CUR,
            quote_currency: NUMERAIRE_CUR,
            base_scale_k: 1,
            quote_scale_k: 1,
            ..Default::default()
        });
        ssp.add_currency(CoreCurrencySpecification {
            currency: COLLATERAL_CUR,
            currency_scale_k: 1,
            collateral_weight_bps: weight_bps,
        });
        ssp.add_currency(CoreCurrencySpecification { currency: NUMERAIRE_CUR, currency_scale_k: 1, ..Default::default() });
        let mut price_cache = std::collections::BTreeMap::new();
        price_cache.insert(SPOT_SYMBOL, 1); // markPrice=1, scale-identity -> value_in_numeraire 恒等于 amount
        (ssp, price_cache)
    }

    /// 开一笔 Cross 债务，`opened_at_ts == now` 且从不 reprice（`last_reprice_ts` 恒 0，冷启动），
    /// 令 `calculateDisplayInterest` 恒为 0（`FloatingRateModel::live_acc_rate_bps_ms` 文档），
    /// 借此把测试焦点收在 LTV 分母（抵押）而不是利息累加上。
    fn cross_loan(uid: i64, loan_id: i64, principal: i64, now: i64) -> CrossLoanRecord {
        let mut loan = CrossLoanRecord::new(uid, loan_id, SPOT_SYMBOL, NUMERAIRE_CUR, 0, now);
        loan.outstanding_principal = principal;
        loan
    }

    #[test]
    fn collateral_weight_for_base_reads_spec_and_defaults_to_zero_when_missing() {
        let (ssp, _) = cross_fixture(5_000);
        assert_eq!(LoanService::collateral_weight_for_base(COLLATERAL_CUR, &ssp), 5_000);
        assert_eq!(LoanService::collateral_weight_for_base(999, &ssp), 0); // 未注册币种
    }

    #[test]
    fn value_in_numeraire_identity_for_same_currency() {
        let (ssp, price_cache) = cross_fixture(5_000);
        let numeraire_spec = ssp.get_currency(NUMERAIRE_CUR).unwrap();
        assert_eq!(
            LoanService::value_in_numeraire(NUMERAIRE_CUR, 12_345, NUMERAIRE_CUR, numeraire_spec, &ssp, &price_cache),
            12_345
        );
    }

    #[test]
    fn value_in_numeraire_converts_through_spot_symbol_and_mark_price() {
        let (ssp, price_cache) = cross_fixture(5_000);
        let numeraire_spec = ssp.get_currency(NUMERAIRE_CUR).unwrap();
        // amount=1000 (COLLATERAL_CUR) * markPrice=1, scale-identity -> 1000 (NUMERAIRE_CUR).
        assert_eq!(
            LoanService::value_in_numeraire(COLLATERAL_CUR, 1_000, NUMERAIRE_CUR, numeraire_spec, &ssp, &price_cache),
            1_000
        );
    }

    #[test]
    fn value_in_numeraire_returns_negative_one_sentinel_when_spot_symbol_or_price_missing() {
        let (ssp, price_cache) = cross_fixture(5_000);
        let numeraire_spec = ssp.get_currency(NUMERAIRE_CUR).unwrap();
        // 无 base=999/quote=NUMERAIRE_CUR 现货对。
        assert_eq!(LoanService::value_in_numeraire(999, 1_000, NUMERAIRE_CUR, numeraire_spec, &ssp, &price_cache), -1);
        // 有现货对，但 price_cache 里没有该 symbol 的 markPrice。
        let empty_price_cache: std::collections::BTreeMap<i32, i64> = std::collections::BTreeMap::new();
        assert_eq!(
            LoanService::value_in_numeraire(COLLATERAL_CUR, 1_000, NUMERAIRE_CUR, numeraire_spec, &ssp, &empty_price_cache),
            -1
        );
    }

    #[test]
    fn calculate_cross_account_ltv_bps_zero_when_no_cross_loans_or_numeraire_unset() {
        let (ssp, price_cache) = cross_fixture(5_000);
        let mut s = LoanService::new();
        s.global_config.numeraire_currency = NUMERAIRE_CUR;
        let up = UserProfile::new(1, crate::core::common::user_status::UserStatus::Active); // no cross_loans
        assert_eq!(s.calculate_cross_account_ltv_bps(&up, 1_000, &ssp, &price_cache, true), 0);

        let s2 = LoanService::new(); // numeraire_currency left at NUMERAIRE_UNSET (0)
        let mut up2 = UserProfile::new(1, crate::core::common::user_status::UserStatus::Active);
        up2.cross_loans.insert(1, cross_loan(1, 1, 400, 1_000));
        assert_eq!(s2.calculate_cross_account_ltv_bps(&up2, 1_000, &ssp, &price_cache, true), 0);
    }

    /// 核心分歧断言（brief Step1 要求）：同一账户状态下，加权口径（`applyWeight=true`，
    /// `collateralWeightBps=5000`=50%）与不加权口径（`applyWeight=false`，pricing 用）必须给出
    /// 不同的 LTV 数值——分母打了 5 折，加权 LTV 应恰好是不加权 LTV 的 2 倍。
    #[test]
    fn weighted_and_raw_cross_ltv_diverge_when_collateral_weight_below_full() {
        let (ssp, price_cache) = cross_fixture(5_000); // 50% weight
        let mut s = LoanService::new();
        s.global_config.numeraire_currency = NUMERAIRE_CUR;

        let mut up = UserProfile::new(1, crate::core::common::user_status::UserStatus::Active);
        up.cross_loans.insert(1, cross_loan(1, 1, 400, 1_000)); // debt=400 (numeraire == loanCurrency, no conversion needed)
        up.cross_loan_collateral.insert(COLLATERAL_CUR, 1_000); // collateral value in numeraire = 1000 (markPrice=1)

        let weighted = s.calculate_cross_account_ltv_bps(&up, 1_000, &ssp, &price_cache, false);
        let raw = s.calculate_cross_raw_ltv_bps(&up, 1_000, &ssp, &price_cache);

        // weighted: denom = 1000*5000/10000 = 500 -> ltv = 400*10000/500 = 8000 (80%).
        assert_eq!(weighted, 8_000);
        // raw: denom = 1000 (no discount) -> ltv = 400*10000/1000 = 4000 (40%).
        assert_eq!(raw, 4_000);
        assert_ne!(weighted, raw);
        assert_eq!(weighted, raw * 2); // weight=50% halves the denominator -> doubles the LTV
    }

    #[test]
    fn calculate_cross_account_ltv_bps_ignores_collateral_currency_with_zero_weight() {
        let (ssp, price_cache) = cross_fixture(0); // weight=0 -> not eligible as Cross collateral
        let mut s = LoanService::new();
        s.global_config.numeraire_currency = NUMERAIRE_CUR;

        let mut up = UserProfile::new(1, crate::core::common::user_status::UserStatus::Active);
        up.cross_loans.insert(1, cross_loan(1, 1, 400, 1_000));
        up.cross_loan_collateral.insert(COLLATERAL_CUR, 1_000); // present but weight=0 -> excluded from both denominators

        // No eligible collateral counted -> totalCollateral<=0 -> Long.MAX_VALUE sentinel (unconditional).
        assert_eq!(s.calculate_cross_account_ltv_bps(&up, 1_000, &ssp, &price_cache, false), i64::MAX);
        assert_eq!(s.calculate_cross_raw_ltv_bps(&up, 1_000, &ssp, &price_cache), i64::MAX);
    }

    /// `fail_closed_on_missing_price`：BORROW/WITHDRAW guard 传 `true`（缺价 -> `i64::MAX` 拒绝）；
    /// scanner/展示传 `false`（缺价 -> `0` 保守跳过）。这里用"debt 侧现货对缺失"制造缺价场景。
    #[test]
    fn calculate_cross_account_ltv_bps_fail_closed_flag_controls_missing_price_sentinel() {
        let mut ssp = SymbolSpecificationProvider::new();
        // 只注册 numeraire 币种 spec；debt currency 用一个没有对应现货对的另一个币种，逼 valueInNumeraire 返回 -1。
        let debt_currency = 3;
        ssp.add_currency(CoreCurrencySpecification { currency: NUMERAIRE_CUR, currency_scale_k: 1, ..Default::default() });
        let price_cache: std::collections::BTreeMap<i32, i64> = std::collections::BTreeMap::new();

        let mut s = LoanService::new();
        s.global_config.numeraire_currency = NUMERAIRE_CUR;
        let mut up = UserProfile::new(1, crate::core::common::user_status::UserStatus::Active);
        let mut loan = CrossLoanRecord::new(1, 1, SPOT_SYMBOL, debt_currency, 0, 1_000);
        loan.outstanding_principal = 400;
        up.cross_loans.insert(1, loan);

        assert_eq!(s.calculate_cross_account_ltv_bps(&up, 1_000, &ssp, &price_cache, true), i64::MAX); // fail-closed
        assert_eq!(s.calculate_cross_account_ltv_bps(&up, 1_000, &ssp, &price_cache, false), 0); // fail-open
    }

    // ====================================================================================
    // Task 7：lots_to_collateral_amount / collateral_amount_to_lots / settle_liquidation_proceeds /
    // is_structurally_sellable / take_over_cross_loan —— 参考文档 §2.5/§2.10/§6.3
    // ====================================================================================

    fn spec_scaled(base_scale_k: i64, quote_scale_k: i64) -> CoreSymbolSpecification {
        CoreSymbolSpecification {
            symbol_id: SPOT_SYMBOL,
            symbol_type: crate::core::common::symbol_type::SymbolType::CurrencyExchangePair,
            base_currency: COLLATERAL_CUR,
            quote_currency: NUMERAIRE_CUR,
            base_scale_k,
            quote_scale_k,
            ..Default::default()
        }
    }

    #[test]
    fn lots_to_collateral_amount_and_back_round_trip_at_scale_identity() {
        let spec = spec_scaled(1, 1);
        let base_spec = CoreCurrencySpecification { currency: COLLATERAL_CUR, currency_scale_k: 1, ..Default::default() };
        assert_eq!(LoanService::lots_to_collateral_amount(10, &spec, &base_spec), 10);
        assert_eq!(LoanService::collateral_amount_to_lots(10, &spec, &base_spec), 10);
    }

    /// `base_scale_k=1 < currency_scale_k=100`：1 lot = 100 currency 单位；不足一张的余量
    /// （dust）在 `collateral_amount_to_lots` 里截断为 0，逐字对齐 Java `currencyToSymbolScale`
    /// 的整除截断语义（参考文档 §2.5 "用是否还有可卖整张而非 collateralAmount==0 判定"）。
    #[test]
    fn collateral_amount_to_lots_truncates_sub_lot_dust() {
        let spec = spec_scaled(1, 1);
        let base_spec = CoreCurrencySpecification { currency: COLLATERAL_CUR, currency_scale_k: 100, ..Default::default() };
        assert_eq!(LoanService::lots_to_collateral_amount(10, &spec, &base_spec), 1_000); // 10 lots -> 1000 units
        assert_eq!(LoanService::collateral_amount_to_lots(1_050, &spec, &base_spec), 10); // 50 units dust truncated
        assert_eq!(LoanService::collateral_amount_to_lots(50, &spec, &base_spec), 0); // pure dust -> 0 lots
    }

    /// 对应 Java `settleLiquidationProceeds`（`:159-166`）：ceil 强平费先抽进 LIF，再
    /// accrue+applyDebtPayment 抵债，overpay 留 account。
    #[test]
    fn settle_liquidation_proceeds_skims_ceil_fee_before_debt_payment() {
        let mut s = LoanService::new();
        assert_eq!(s.global_config.loan_liquidation_fee_bps, 200); // 2% default
        let mut loan = IsolatedLoanRecord::new(1, 1, SPOT_SYMBOL, COLLATERAL_CUR, NUMERAIRE_CUR, 0, 0);
        loan.outstanding_principal = 500;
        let mut account: BTreeMap<i32, i64> = BTreeMap::new();
        account.insert(NUMERAIRE_CUR, 10_000); // pre-existing free balance, unrelated to this settlement

        // received_quote=1000 -> feeByRate=ceil(1000*200/10000)=20 -> liqFee=20 -> fund=980,
        // principal_part=min(980,500)=500 (capped at outstanding debt) -> 480 overpay stays in account.
        let interest_paid = s.settle_liquidation_proceeds(&mut loan, &mut account, 1_000, 0);

        assert_eq!(interest_paid, 0);
        assert_eq!(loan.outstanding_principal, 0);
        assert_eq!(s.get_loan_insurance_fund(NUMERAIRE_CUR), 20); // fee skimmed to LIF
        assert_eq!(s.get_loan_pool_available(NUMERAIRE_CUR), 500);
        assert_eq!(*account.get(&NUMERAIRE_CUR).unwrap(), 10_000 - 20 - 500); // fee + principal debited, overpay kept
    }

    #[test]
    fn settle_liquidation_proceeds_caps_fee_at_received_quote_when_rate_would_exceed_it() {
        // Degenerate guard: liqFee = min(receivedQuote, feeByRate) never exceeds what was received.
        let mut s = LoanService::new();
        s.global_config.loan_liquidation_fee_bps = 20_000; // pathological >100% rate, still capped
        let mut loan = IsolatedLoanRecord::new(1, 1, SPOT_SYMBOL, COLLATERAL_CUR, NUMERAIRE_CUR, 0, 0);
        loan.outstanding_principal = 5;
        let mut account: BTreeMap<i32, i64> = BTreeMap::new();

        s.settle_liquidation_proceeds(&mut loan, &mut account, 100, 0);

        assert_eq!(s.get_loan_insurance_fund(NUMERAIRE_CUR), 100); // fee capped at receivedQuote, not 200
        assert_eq!(loan.outstanding_principal, 5); // nothing left over to pay debt
    }

    #[test]
    fn is_structurally_sellable_requires_positive_weight_and_a_ready_spot_pair_to_outstanding_debt() {
        let (ssp, _) = cross_fixture(5_000);
        let mut up = UserProfile::new(1, crate::core::common::user_status::UserStatus::Active);
        up.cross_loans.insert(1, cross_loan(1, 1, 400, 1_000)); // debt in NUMERAIRE_CUR

        assert!(LoanService::is_structurally_sellable(COLLATERAL_CUR, 1_000, &up, &ssp));
        assert!(!LoanService::is_structurally_sellable(COLLATERAL_CUR, 0, &up, &ssp)); // amount<=0
        assert!(!LoanService::is_structurally_sellable(999, 1_000, &up, &ssp)); // no spot pair to any debt currency

        let (ssp0, _) = cross_fixture(0); // weight=0 -> permanently ineligible, regardless of amount/pair
        assert!(!LoanService::is_structurally_sellable(COLLATERAL_CUR, 1_000, &up, &ssp0));
    }

    #[test]
    fn is_structurally_sellable_false_when_no_outstanding_cross_debt() {
        let (ssp, _) = cross_fixture(5_000);
        let up = UserProfile::new(1, crate::core::common::user_status::UserStatus::Active); // no cross_loans at all
        assert!(!LoanService::is_structurally_sellable(COLLATERAL_CUR, 1_000, &up, &ssp));
    }

    #[test]
    fn take_over_cross_loan_fails_closed_when_numeraire_unconfigured_and_leaves_state_untouched() {
        let (ssp, price_cache) = cross_fixture(5_000);
        let mut s = LoanService::new(); // numeraire_currency left at NUMERAIRE_UNSET
        let mut up = UserProfile::new(1, crate::core::common::user_status::UserStatus::Active);
        up.cross_loans.insert(1, cross_loan(1, 1, 400, 1_000));
        up.cross_loan_collateral.insert(COLLATERAL_CUR, 1_000);

        let taken = s.take_over_cross_loan(&mut up, 1, 1_000, &ssp, &price_cache);

        assert!(!taken);
        assert_eq!(up.cross_loans.get(&1).unwrap().outstanding_principal, 400); // untouched
        assert_eq!(up.cross_loan_collateral.get(&COLLATERAL_CUR), Some(&1_000)); // untouched
        assert_eq!(s.get_loan_insurance_fund(NUMERAIRE_CUR), 0);
    }

    #[test]
    fn take_over_cross_loan_fails_closed_when_a_debt_currency_price_is_missing() {
        let mut ssp = SymbolSpecificationProvider::new();
        ssp.add_currency(CoreCurrencySpecification { currency: NUMERAIRE_CUR, currency_scale_k: 1, ..Default::default() });
        // debt_currency (3) != NUMERAIRE_CUR and has no registered spot pair to it at all ->
        // value_in_numeraire returns -1 (unlike using NUMERAIRE_CUR itself as the debt currency,
        // which would hit the same-currency identity shortcut and never need a price at all).
        let debt_currency = 3;
        let price_cache: BTreeMap<i32, i64> = BTreeMap::new();
        let mut s = LoanService::new();
        s.global_config.numeraire_currency = NUMERAIRE_CUR;
        let mut up = UserProfile::new(1, crate::core::common::user_status::UserStatus::Active);
        let mut loan = CrossLoanRecord::new(1, 1, SPOT_SYMBOL, debt_currency, 0, 1_000);
        loan.outstanding_principal = 400;
        up.cross_loans.insert(1, loan);

        let taken = s.take_over_cross_loan(&mut up, 1, 1_000, &ssp, &price_cache);

        assert!(!taken);
        assert_eq!(up.cross_loans.get(&1).unwrap().outstanding_principal, 400); // untouched
    }

    /// 完整承接路径：**账户内有第二笔（未被清算的）Cross 债**，让 target 只占总债务的一部分
    /// （400/1000=40%），从而定额扣抵押只取走"该笔债占比"而非账户全部抵押——展示
    /// pro-rata 分摊而非"取走恰好覆盖 target 债务的量"（单笔账户=100% 占比时会取走全部抵押，
    /// 见下一条 `..._takes_the_whole_pool_when_it_is_the_sole_debt` 测试对照）。LIF 两币变化
    /// （loanCcy 变负 = 已垫资，collateralCcy 变正 = 收到抵押），`up.accounts`/
    /// `cross_loan_collateral` 真实扣减，不触碰 `targetLoan` 自身字段（那是调用方
    /// `close_and_recycle_cross_loan` 的职责）。
    #[test]
    fn take_over_cross_loan_moves_lif_two_currencies_and_physically_debits_collateral() {
        let (ssp, price_cache) = cross_fixture(5_000); // markPrice=1, scale-identity
        let mut s = LoanService::new();
        s.global_config.numeraire_currency = NUMERAIRE_CUR;
        let mut up = UserProfile::new(1, crate::core::common::user_status::UserStatus::Active);
        up.cross_loans.insert(1, cross_loan(1, 1, 400, 1_000)); // target: debt=400 in NUMERAIRE_CUR
        up.cross_loans.insert(2, cross_loan(1, 2, 600, 1_000)); // other, unaffected loan: debt=600
        up.cross_loan_collateral.insert(COLLATERAL_CUR, 1_000); // total collateral value = 1000 (debt sum)
        up.add_to_account(COLLATERAL_CUR, 1_000); // virtual-locked collateral physically still sits in accounts

        let taken = s.take_over_cross_loan(&mut up, 1, 1_000, &ssp, &price_cache);

        assert!(taken);
        // targetLoan itself untouched by design (caller's job to zero/remove):
        assert_eq!(up.cross_loans.get(&1).unwrap().outstanding_principal, 400);
        // The OTHER (non-target) loan is completely untouched — takeover is per-loan, not per-account.
        assert_eq!(up.cross_loans.get(&2).unwrap().outstanding_principal, 600);
        // LIF: -400 loanCcy (advanced target's debt only), +400 collateralCcy (target's 40% pro-rata share).
        assert_eq!(s.get_loan_insurance_fund(NUMERAIRE_CUR), -400);
        assert_eq!(s.get_loan_insurance_fund(COLLATERAL_CUR), 400);
        // Pool made whole immediately for target's principal only; interestRevenue += (debt-principal) = 0.
        assert_eq!(s.get_loan_pool_available(NUMERAIRE_CUR), 400);
        assert_eq!(s.get_loan_pool_borrowed(NUMERAIRE_CUR), -400);
        assert_eq!(s.get_interest_revenue(NUMERAIRE_CUR), 0);
        // Physical debit: real accounts + cross_loan_collateral both drop by exactly target's share (400),
        // leaving the other loan's 60% share (600) untouched in the shared pool.
        assert_eq!(up.cross_loan_collateral.get(&COLLATERAL_CUR), Some(&600));
        assert_eq!(up.account(COLLATERAL_CUR), 1_000 - 400);
    }

    /// 对照：账户只有这一笔 Cross 债时，target 占总债务 100%，pro-rata 公式取走**全部**抵押
    /// （不封顶在"恰好覆盖自身债务"）——单笔债务即代表整户份额，loan.md §18 "不整户接管"指的是
    /// 不牵连其他债，而非把 target 的取用额限制在自身债务名义值。
    #[test]
    fn take_over_cross_loan_takes_the_whole_pool_when_it_is_the_sole_debt() {
        let (ssp, price_cache) = cross_fixture(5_000);
        let mut s = LoanService::new();
        s.global_config.numeraire_currency = NUMERAIRE_CUR;
        let mut up = UserProfile::new(1, crate::core::common::user_status::UserStatus::Active);
        up.cross_loans.insert(1, cross_loan(1, 1, 400, 1_000)); // sole debt=400
        up.cross_loan_collateral.insert(COLLATERAL_CUR, 1_000); // collateral value far exceeds the debt
        up.add_to_account(COLLATERAL_CUR, 1_000);

        let taken = s.take_over_cross_loan(&mut up, 1, 1_000, &ssp, &price_cache);

        assert!(taken);
        assert_eq!(s.get_loan_insurance_fund(NUMERAIRE_CUR), -400); // debt absorbed is still just 400
        assert_eq!(s.get_loan_insurance_fund(COLLATERAL_CUR), 1_000); // but 100% debt-share takes 100% collateral
        assert_eq!(up.cross_loan_collateral.get(&COLLATERAL_CUR), Some(&0));
        assert_eq!(up.account(COLLATERAL_CUR), 0);
    }

    /// 抵押不足以覆盖全部债务时：只取走全部可得抵押（不会扣成负数/超额），剩余债务仍全额记入 LIF
    /// 亏空——`take_over_cross_loan` 不做"部分接管"，只做"抵押定额封顶"。
    #[test]
    fn take_over_cross_loan_caps_collateral_take_at_available_amount_when_undercollateralized() {
        let (ssp, price_cache) = cross_fixture(10_000); // weight=100%: full raw value counts
        let mut s = LoanService::new();
        s.global_config.numeraire_currency = NUMERAIRE_CUR;
        let mut up = UserProfile::new(1, crate::core::common::user_status::UserStatus::Active);
        up.cross_loans.insert(1, cross_loan(1, 1, 1_000, 1_000)); // debt=1000, way more than collateral
        up.cross_loan_collateral.insert(COLLATERAL_CUR, 300); // only 300 available
        up.add_to_account(COLLATERAL_CUR, 300);

        let taken = s.take_over_cross_loan(&mut up, 1, 1_000, &ssp, &price_cache);

        assert!(taken);
        assert_eq!(up.cross_loan_collateral.get(&COLLATERAL_CUR), Some(&0)); // fully drained, not negative
        assert_eq!(up.account(COLLATERAL_CUR), 0);
        assert_eq!(s.get_loan_insurance_fund(COLLATERAL_CUR), 300); // only what existed
        assert_eq!(s.get_loan_insurance_fund(NUMERAIRE_CUR), -1_000); // full debt still absorbed
    }
}
