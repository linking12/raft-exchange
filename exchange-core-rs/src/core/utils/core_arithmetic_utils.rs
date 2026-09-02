//! 定点算术纯函数——现货子集。
//!
//! 对应 Java: `exchange.core2.core.utils.CoreArithmeticUtils`
//! 另含 P6 Task 4 提取的共享原语 [`distribute_remainder_by_one`]（"截断分配 + 1-unit 余数
//! 分配"，见该函数文档），供 Funding/IF/ADL（P6 Task 4/5/6）复用。
//! （`exchange-core/src/main/java/exchange/core2/core/utils/CoreArithmeticUtils.java`）。
//!
//! # 与 Java 的差异（刻意简化，非语义改变）
//!
//! Java 版本对 `ceilMulDiv` / `ceilMulMulDiv` / `truncMulDiv` 用了 hybrid fast/slow path：
//! fast path 在 64-bit 内做分块乘除（`Math.multiplyExact` 抛异常触发 fallback），slow path
//! 才升到 128-bit（`ceilMulDiv128` / `truncMulDiv128`）。这是纯粹的性能优化（Java 无原生
//! 128-bit 类型，需手写 bit-by-bit 长除）。Rust 有原生 `i128`，两个 `i64` 相乘必然落在 `i128`
//! 内（`i64::MAX^2 ≈ 8.5e37 < i128::MAX ≈ 1.7e38`），因此本文件的 `ceil_mul_div` /
//! `trunc_mul_div` 直接用 `i128` 中间精度，**一个函数同时覆盖 Java 的 fast path 和 slow
//! path（`*128` 变体）**——数学结果完全等价，Java 测试文件
//! `CoreArithmeticUtilsCeilMulDiv128Test` / `CoreArithmeticUtilsTruncMulDiv128Test` 的用例
//! 因此直接搬到这两个函数上验证，无需单独的 `_128` 函数。
//!
//! `ceil_mul_mul_div`（三元乘）例外：三个 `i64` 连乘可能超出 `i128`
//! （`i64::MAX^3` 远超 `i128::MAX`），所以复刻 Java 的两步重排策略：优先尝试
//! `a*b`（`i128` 内，再判断是否落回 `i64` 范围），必要时改为 `b*c`，最终仍只做一次
//! "两个 i64 相乘" 意义下的 128-bit 精度运算。这与 Java 的 `ceilMulMulDiv` catch
//! `ArithmeticException` 后重排的效果一致：只要 a·b 或 b·c 至少有一个能收窄回 `i64`，
//! 结果就精确；两者都不能则 panic（对应 Java 未捕获的 `ArithmeticException` 会向上抛出）。
//!
//! # Ruling（P3 Task 1）
//! 本模块**不 import 账户/规格模型**（`CoreSymbolSpecification` / `CoreCurrencySpecification`
//! 等），所有 fee/scale 参数都是裸标量（`taker_fee: i64, fee_scale_k: i64, ...`），保持
//! arithmetic 层零依赖、可被 Task 2 的 struct 复用而非反向依赖。
//!
//! `is_fixed_fee` 语义对应 Java `CoreSymbolSpecification#isFixedFee()`：`fee_scale_k == 0`
//! 表示固定费用（`size * fee`），否则按比例（`ceil(size*price*fee / feeScaleK)`）。
//!
//! # 取整语义（对财务结果 load-bearing，务必保持）
//! 手续费类一律向 +∞ 取整（ceil，平台不少收：`ceil_mul_div` / `ceil_mul_mul_div` /
//! `ceil_divide`）；盈利/退款方向用向零截断（`trunc_mul_div`）。翻转取整方向会破坏资金守恒。

/// 10^0..=10^18（`i64` 范围内的全部 10 次幂）。对应 Java `TenPowers.POW10`。
const POW10: [i64; 19] = [
    1,
    10,
    100,
    1_000,
    10_000,
    100_000,
    1_000_000,
    10_000_000,
    100_000_000,
    1_000_000_000,
    10_000_000_000,
    100_000_000_000,
    1_000_000_000_000,
    10_000_000_000_000,
    100_000_000_000_000,
    1_000_000_000_000_000,
    10_000_000_000_000_000,
    100_000_000_000_000_000,
    1_000_000_000_000_000_000,
];

/// 对应 Java `TenPowers.pow10(int)`。
fn pow10(n: u32) -> i64 {
    POW10[n as usize]
}

/// 对应 Java `TenPowers.log10(long)`：`x` 必须是 10 的整数次幂，否则 panic
/// （Java 对应抛 `IllegalArgumentException`）。
fn log10(x: i64) -> i32 {
    match POW10.iter().position(|&p| p == x) {
        Some(idx) => idx as i32,
        None => panic!("x not power of 10: {x}"),
    }
}

/// 对应 Java `Math.multiplyExact(long, long)`：`i128` 中间精度相乘后收窄回 `i64`，
/// 溢出则 panic（对应 Java 抛 `ArithmeticException`）。
fn mul_exact(a: i64, b: i64) -> i64 {
    let product = a as i128 * b as i128;
    i64::try_from(product).unwrap_or_else(|_| panic!("overflow: {a} * {b}"))
}

/// 对应 Java `Math.addExact(long, long)`。
fn add_exact(a: i64, b: i64) -> i64 {
    let sum = a as i128 + b as i128;
    i64::try_from(sum).unwrap_or_else(|_| panic!("overflow: {a} + {b}"))
}

/// 对应 Java `Math.subtractExact(long, long)`。
fn sub_exact(a: i64, b: i64) -> i64 {
    let diff = a as i128 - b as i128;
    i64::try_from(diff).unwrap_or_else(|_| panic!("overflow: {a} - {b}"))
}

/// `ceil(n / d)`，向 +∞ 取整（数学 ceiling，非"绝对值意义上的 ceil"），`n`/`d` 任意符号，
/// `d` 必须非零。对应 Java `ceilMulDiv128` 步骤 4 的符号还原逻辑
/// （正值 `q + (r!=0)`；负值 `-floor(|n|/|d|)`），但用标准两补码算法统一表达，
/// 不区分符号分支即可得到等价结果。
fn ceil_div_i128(n: i128, d: i128) -> i128 {
    assert!(d != 0, "/ by zero");
    let q = n / d; // Rust `/` 对 i128 向零截断，语义同 Java `long` 除法
    let r = n % d;
    if r != 0 && (r > 0) == (d > 0) {
        q + 1
    } else {
        q
    }
}

fn narrow_i128(v: i128, ctx: &str) -> i64 {
    i64::try_from(v).unwrap_or_else(|_| panic!("overflow narrowing to i64: {ctx} = {v}"))
}

// ============================================================================
// 取整乘除基础算子
// ============================================================================

/// `ceil((a × b) / c)`。对应 Java `ceilMulDiv`（fast path）与 `ceilMulDiv128`（slow path）
/// 的合并等价实现——两个 `i64` 相乘必落 `i128`，故本函数天然覆盖 Java 两条路径的全部输入域。
///
/// 前提：`c > 0`（对应 Java `ceilMulDiv128` 显式检查 `c <= 0` 抛异常；`a`, `b` 任意符号，
/// 与 Java `ceilMulDiv` 文档"a>=0, c>0, b 任意"及 `ceilMulDiv128` 的实际签名放宽到 a 任意保持一致）。
pub fn ceil_mul_div(a: i64, b: i64, c: i64) -> i64 {
    assert!(c > 0, "c must be positive: {c}");
    let product = a as i128 * b as i128;
    narrow_i128(ceil_div_i128(product, c as i128), "ceil_mul_div")
}

/// `(a × b) / c`，向零截断。对应 Java `truncMulDiv`（fast path）与 `truncMulDiv128`
/// （slow path）的合并等价实现；`c` 可为负（对应全仓强平 SHORT 分母为负的场景）。
///
/// 前提：`c != 0`。
pub fn trunc_mul_div(a: i64, b: i64, c: i64) -> i64 {
    assert!(c != 0, "/ by zero");
    let product = a as i128 * b as i128;
    narrow_i128(product / c as i128, "trunc_mul_div")
}

/// `ceil((a × b × c) / d)`。对应 Java `ceilMulMulDiv`：`a*b*c` 三元连乘可能超出 `i128`
/// 表示范围，因此复刻 Java 的"两步重排"策略——优先用 `a*b`（收窄回 `i64` 后走
/// `ceil_mul_div`），若 `a*b` 超出 `i64` 范围则改用 `b*c`（同样收窄后走
/// `ceil_mul_div(a, bc, d)`）。两者都超出 `i64` 范围时 panic（对应 Java 未捕获的
/// `ArithmeticException` 向上抛出）。
///
/// 前提：`a, c, d > 0`；`b` 任意符号（fee 通常 > 0，maker rebate 场景可为负）。
pub fn ceil_mul_mul_div(a: i64, b: i64, c: i64, d: i64) -> i64 {
    let ab = a as i128 * b as i128;
    if let Ok(ab_i64) = i64::try_from(ab) {
        return ceil_mul_div(ab_i64, c, d);
    }
    let bc = b as i128 * c as i128;
    match i64::try_from(bc) {
        Ok(bc_i64) => ceil_mul_div(a, bc_i64, d),
        Err(_) => panic!("overflow: neither a*b nor b*c fits in i64 (a={a}, b={b}, c={c})"),
    }
}

/// 向上取整的整数除法：`dividend / divisor + (余数非零 ? 1 : 0)`。对应 Java
/// `ceilDivide(long, long)`。前提：`divisor != 0`（Rust 整数除法与 Java 一致，均向零截断，
/// 故负数场景下的公式行为与 Java 逐位相同，无需额外符号分支）。
pub fn ceil_divide(dividend: i64, divisor: i64) -> i64 {
    dividend / divisor + if dividend % divisor == 0 { 0 } else { 1 }
}

// ============================================================================
// scale 换算
// ============================================================================

/// 通用 scale 换算：`from_k` / `to_k` 必须都是 10 的整数次幂（对应 Java `TenPowers.log10`
/// 的隐含契约）。对应 Java `CoreArithmeticUtils#convertScale`（private，经由
/// `sizePriceToCurrencyScale` / `symbolToCurrencyScale` 等对外暴露）。
///
/// 缩小（`from_k` 量级更大）走整除；放大走乘法，`i128` 中间精度收窄回 `i64`（对应 Java
/// `Math.multiplyExact` 早抛）。
pub fn convert_scale(amount: i64, from_k: i64, to_k: i64) -> i64 {
    if from_k == to_k {
        return amount;
    }
    let diff = log10(from_k) - log10(to_k);
    if diff > 0 {
        amount / pow10(diff as u32)
    } else {
        let factor = pow10((-diff) as u32) as i128;
        narrow_i128(amount as i128 * factor, "convert_scale")
    }
}

/// 撮合内部乘积单位（`base_scale_k * quote_scale_k`）→ 币种记账单位（`currency_scale_k`）。
/// 对应 Java `sizePriceToCurrencyScale`。任何涉及 `price * size` 的计算结果都是这个内部乘积
/// 单位。
pub fn size_price_to_currency_scale(
    amount: i64,
    base_scale_k: i64,
    quote_scale_k: i64,
    currency_scale_k: i64,
) -> i64 {
    convert_scale(amount, mul_exact(base_scale_k, quote_scale_k), currency_scale_k)
}

/// 币种记账单位（`currency_scale_k`）→ 撮合内部乘积单位（`base_scale_k * quote_scale_k`）。
/// 对应 Java `currencyToSizePriceScale`——[`size_price_to_currency_scale`] 的反向换算，喂
/// `SymbolPositionRecord::calculate_bankruptcy_price`（P6）的 CROSS `marginBase`（与
/// `open_init_margin_sum` 同 sizePrice scale）。P4 Task 5:
/// `UserProfile::cross_margin_base_allocation` 用它把逐仓分摊到的 currency-scale marginBase
/// 换算回 sizePrice scale。
pub fn currency_to_size_price_scale(
    amount: i64,
    base_scale_k: i64,
    quote_scale_k: i64,
    currency_scale_k: i64,
) -> i64 {
    convert_scale(amount, currency_scale_k, mul_exact(base_scale_k, quote_scale_k))
}

/// 币对交易单位（base 或 quote 各自的 `scale_k`，由调用方按 currency id 选定）→ 币种记账
/// 单位。对应 Java `symbolToCurrencyScale`；Java 版本内部按 `currency.id` 匹配
/// `spec.baseCurrency`/`quoteCurrency` 选择 `baseScaleK`/`quoteScaleK`——那部分依赖账户/规格
/// 模型，按 Task 1 的"零依赖"约束下放给调用方（Task 2/4），本函数只做纯换算。
pub fn symbol_to_currency_scale(amount: i64, scale_k: i64, currency_scale_k: i64) -> i64 {
    convert_scale(amount, scale_k, currency_scale_k)
}

// ============================================================================
// 现货金额 / 手续费
// ============================================================================

/// 对应 Java `calculateAmountAsk`：ask 侧冻结金额就是 size 本身（base 货币）。
pub fn calculate_amount_ask(size: i64) -> i64 {
    size
}

/// 对应 Java `calculateAmountBid`：bid 侧名义金额 = size × price（quote 货币，内部乘积单位）。
pub fn calculate_amount_bid(size: i64, price: i64) -> i64 {
    mul_exact(size, price)
}

/// 对应 Java `calculateTakerFee`：`fee_scale_k == 0`（固定费）→ `size * taker_fee`；
/// 否则按比例 `ceil(size * price * taker_fee / fee_scale_k)`。
pub fn calculate_taker_fee(size: i64, price: i64, taker_fee: i64, fee_scale_k: i64) -> i64 {
    if fee_scale_k == 0 {
        mul_exact(size, taker_fee)
    } else {
        ceil_mul_mul_div(size, price, taker_fee, fee_scale_k)
    }
}

/// 对应 Java `calculateMakerFee`：结构与 `calculate_taker_fee` 相同，费率换成 `maker_fee`。
pub fn calculate_maker_fee(size: i64, price: i64, maker_fee: i64, fee_scale_k: i64) -> i64 {
    if fee_scale_k == 0 {
        mul_exact(size, maker_fee)
    } else {
        ceil_mul_mul_div(size, price, maker_fee, fee_scale_k)
    }
}

/// 对应 Java `calculateAmountBidTakerFee`：下单（taker）时按 taker 费率冻结的总金额
/// = 名义金额 + taker 手续费（ceil，平台不少收）。
pub fn calculate_amount_bid_taker_fee(
    size: i64,
    price: i64,
    taker_fee: i64,
    fee_scale_k: i64,
) -> i64 {
    let trade_amount = mul_exact(size, price);
    let fee = calculate_taker_fee(size, price, taker_fee, fee_scale_k);
    add_exact(trade_amount, fee)
}

/// 对应 Java `calculateAmountBidTakerFeeForBudget`：按预算（而非 size×price）冻结时的总金额。
/// 注意固定费分支仍按 `size * taker_fee` 计费（与 `calculate_amount_bid_taker_fee` 一致），
/// 而不是按 `budget`——这是 Java 原始实现的非对称之处，逐字保留。
pub fn calculate_amount_bid_taker_fee_for_budget(
    size: i64,
    budget_in_steps: i64,
    taker_fee: i64,
    fee_scale_k: i64,
) -> i64 {
    let budget_amount = budget_in_steps;
    let fee = if fee_scale_k == 0 {
        mul_exact(size, taker_fee)
    } else {
        ceil_mul_div(budget_amount, taker_fee, fee_scale_k)
    };
    add_exact(budget_amount, fee)
}

/// 对应 Java `calculateAmountBidReleaseCorrMaker`：订单以更优 maker 价成交后，退还给买方的
/// 超额冻结资金 = 本金差额（`size * (hold_price - trade_price)`）+ 手续费差额
/// （taker 费 - maker 费，按实际成交价重算）。
pub fn calculate_amount_bid_release_corr_maker(
    size: i64,
    hold_price: i64,
    trade_price: i64,
    taker_fee: i64,
    maker_fee: i64,
    fee_scale_k: i64,
) -> i64 {
    let trade_amount_diff = mul_exact(size, sub_exact(hold_price, trade_price));
    let fee_diff = if fee_scale_k == 0 {
        mul_exact(size, sub_exact(taker_fee, maker_fee))
    } else {
        let inner_numer = sub_exact(
            mul_exact(hold_price, taker_fee),
            mul_exact(trade_price, maker_fee),
        );
        ceil_mul_div(size, inner_numer, fee_scale_k)
    };
    add_exact(trade_amount_diff, fee_diff)
}

/// 对应 Java `isAskPriceTooLow`：判断 ask 价格是否低到"哪怕只成交 1 手也收不到手续费"。
/// 固定费模式下 `price < taker_fee` 即太低；比例模式下 `taker_fee == 0`（免手续费）永不算太低，
/// 否则等价改写为 `price < ceil(fee_scale_k / taker_fee)` 规避 `price * taker_fee` 溢出。
pub fn is_ask_price_too_low(price: i64, taker_fee: i64, fee_scale_k: i64) -> bool {
    if fee_scale_k == 0 {
        return price < taker_fee;
    }
    if taker_fee == 0 {
        return false;
    }
    price < ceil_divide(fee_scale_k, taker_fee)
}

// ========================================================================
// P6 Task 1：强平数学原语 —— 对应 Java `CoreArithmeticUtils.java:180-240`
// ========================================================================
//
// Java 这三个函数直接接受 `SymbolPositionRecord`/`CoreSymbolSpecification`/
// `LastPriceCacheRecord` 三个模型类型；本模块延续既有 Ruling（P3 Task 1，见文件头）——
// 零依赖模型层，`calculate_taker_fee`/`calculate_maker_fee` 已经是这个先例（接受裸标量费率，
// 不接受 spec）。因此下面三个函数把 Java 版本里"先从 position/spec 读一把标量再算"的那些局部
// 变量（`E`/`MM`/`Q`/`Pm`/`sign` 等）直接做成入参，调用方（`SymbolPositionRecord` 自身方法或
// 未来的清算引擎）负责用已有的 `estimate_unrealized_profit`/`calculate_maintenance_margin`
// 算好这些标量再传进来。这是**参数传递方式**上的刻意偏差（保持架构边界不变），不是算法偏差——
// 公式的运算顺序、哪些子表达式过 `*_exact`/`ceil_mul_div`、哪些保留 Java 原始的裸 `+`/`-`，
// 逐字对齐 Java 源码（Java 源文件本身的注释已经精确标注了这些取舍，照抄不重推）。

/// 对应 Java `calculateLiquidationFee(long size, long price, CoreSymbolSpecification spec)`：
/// `fee_scale_k == 0`（固定费）→ `size * liquidation_fee`；否则按比例
/// `ceil(size * price * liquidation_fee / fee_scale_k)`。结构与 `calculate_taker_fee`/
/// `calculate_maker_fee` 完全一致，费率换成 `liquidation_fee`。
pub fn calculate_liquidation_fee(size: i64, price: i64, liquidation_fee: i64, fee_scale_k: i64) -> i64 {
    if fee_scale_k == 0 {
        mul_exact(size, liquidation_fee)
    } else {
        ceil_mul_mul_div(size, price, liquidation_fee, fee_scale_k)
    }
}

/// 计算强平数量 x。对应 Java `calculateSizeToLiquidate(SymbolPositionRecord position,
/// CoreSymbolSpecification spec, LastPriceCacheRecord priceRecord)`（`:201-214`）。
///
/// <pre>
/// 原权益：     E = openInitMarginSum + unrealizedPnl
/// 减保证金：   ΔIM = openInitMarginSum * x / Q
/// 减未实现P&L：ΔPnl = sign * (Pm - Pe) * x
/// 新权益：     E' = E - ΔIM - ΔPnl
/// 新权益须 ≥ 维持保证金：E' ≥ Pm * (Q - x) * Rmm
///   ⇒  E - IMS * x / Q - sign * (Pm - Pe) * x ≥ Pm * (Q - x) * Rmm
///   ⇒  x ≥ (E - Pm * Q * Rmm) / (IMS / Q + Pm * (sign * 1 - Rmm) - sign * Pe)
///         其中 Rmm = MM / (Pm * Q),  Pe = openPriceSum / Q
///   ⇒  x ≥ (E - MM) / (IMS / Q + sign * Pm - MM / Q - sign * openPriceSum / Q)
///   ⇒  x ≥ (E - MM) * Q / (IMS + sign * Pm * Q - MM - sign * openPriceSum)
/// </pre>
///
/// 入参对应 Java 局部变量（调用方先算好再传入，见本节前言）：`equity` = E =
/// `openInitMarginSum + position.estimateUnrealizedProfit(priceRecord)`；
/// `maintenance_margin` = MM = `position.calculateMaintenanceMargin(spec, priceRecord)`；
/// `open_init_margin_sum`/`open_volume`/`open_price_sum`/`mark_price`/`sign` 直接对应
/// `position.openInitMarginSum`/`openVolume`/`openPriceSum`/`priceRecord.markPrice`/
/// `direction.getMultiplier()`。
pub fn calculate_size_to_liquidate(
    equity: i64,
    maintenance_margin: i64,
    open_init_margin_sum: i64,
    open_volume: i64,
    open_price_sum: i64,
    mark_price: i64,
    sign: i64,
) -> i64 {
    // 分子 (E-MM)*Q：大持仓 + 巨大 PnL 场景下溢出，用 multiplyExact 早抛。
    let numerator = mul_exact(equity - maintenance_margin, open_volume);
    // 分母 Pm*Q：notional 大单同样可能溢出——Java 只对这一处乘法用 multiplyExact，其余
    // 加减保留裸运算，逐字对齐（Java 源码注释同样只标注了这一处溢出风险）。
    let denominator =
        open_init_margin_sum + sign * mul_exact(mark_price, open_volume) - maintenance_margin - sign * open_price_sum;
    ceil_divide(numerator, denominator)
}

/// 估算强平 x 手后，对缺口的改善。对应 Java `calculateDeficitAfterLiquidate(long size,
/// SymbolPositionRecord position, CoreSymbolSpecification spec, LastPriceCacheRecord
/// priceRecord)`（`:228-240`）。
///
/// <pre>
/// deficit  = totalMM - totalEquity
/// Δdeficit = ΔMM - ΔE,  其中 ΔE = ΔIM + ΔPnl
/// ΔD = ΔMM - ΔIM - ΔPnl
///    = ΔMM - openInitMarginSum * x / Q - sign * (Pm - Pe) * x,   Pe = openPriceSum / Q
///    = ΔMM - openInitMarginSum * x / Q - sign * (Pm - openPriceSum / Q) * x
///    = ΔMM - (openInitMarginSum + sign * (Pm * Q - openPriceSum) * x) / Q
/// </pre>
///
/// 入参对应 Java 局部变量：`sign` = `position.direction.getMultiplier()`；
/// `open_init_margin_sum`/`open_volume`/`open_price_sum`/`mark_price` 直接对应 position/
/// priceRecord 字段；`maintenance_margin_now`/`maintenance_margin_after` 对应 Java
/// `spec.calculateMaintenanceMargin(notionalNow)`/`spec.calculateMaintenanceMargin(notionalAfter)`
/// 的调用结果（`notionalNow = openVolume * markPrice`、`notionalAfter = (openVolume - size) *
/// markPrice`——分档 MM 表查询依赖 `CoreSymbolSpecification`，本模块零依赖模型层，故由调用方
/// 先算好两个 notional 对应的 MM 再传入，见本节前言）。
pub fn calculate_deficit_after_liquidate(
    size: i64,
    sign: i64,
    open_init_margin_sum: i64,
    open_volume: i64,
    open_price_sum: i64,
    mark_price: i64,
    maintenance_margin_now: i64,
    maintenance_margin_after: i64,
) -> i64 {
    let delta_mm = maintenance_margin_now - maintenance_margin_after;
    let numerator = open_init_margin_sum + sign * (mul_exact(mark_price, open_volume) - open_price_sum);
    // numerator * size 在大仓位场景溢出（同 fee 路径 bug 同款 pattern），ceilMulDiv 自带 hybrid 兜底。
    // numerator 可能为负（sign=-1 short），故把 size（恒正）放第一参，numerator 放第二参
    // （ceil_mul_div 支持 b 为负）。
    delta_mm - ceil_mul_div(size, numerator, open_volume)
}

// ========================================================================
// P6 Task 4：共享"截断分配 + 1-unit 余数分配"原语 —— 提取自
// `FundingFeeCommandProcessor.buildMatcherEvents`（:85-104）与
// `.applyEvent`/`settleFundingFee`（:150-161）两处完全同构的模式，供 IF/ADL（Task 5/6）
// 复用。参考文档 §4.2/§4.3/§11.2、`.superpowers/sdd/.../task-4-brief.md`"共享原语"一节。
// ========================================================================

use std::collections::BTreeMap;

/// 把 `total`（非负待分配总量）按 `weights`（key -> 非负权重，`BTreeMap` 保证按 key **升序**
/// 迭代——determinism 是 load-bearing，参考文档 §11.2 明确警告不能用 hash 容器迭代序）截断
/// 分配（[`trunc_mul_div`]，向零截断），再把截断产生的余数（`total - Σ 截断额`）按 key
/// 升序**单趟**逐 1 分配给 `weights` 中出现过的 key（不看该 key 自身权重是否为 0——"出现即
/// 有资格"，对应 Java 判定条件是 `receiverNotionals`/`payerAmounts` 是否为空，不是权重是否
/// 为零，两者在本域内恒等价：真实调用点的权重恒 > 0，非空 map 的权重也恒 > 0，此处按
/// "出现于 map"这个更宽松的条件实现以精确对齐 Java 语义）。
///
/// **单趟、不循环回绕**：对应 Java 两处余数分配循环都只跑一趟（`for` 到 `numShards`/
/// `LongIterator` 到 `keySet` 末尾即止），不会绕回从头再分配一轮。这在数学上总是够用——
/// N 次截断除法各自的余数 < 除数，累计截断损失严格小于"参与分配的 key 数"（每个截断至多
/// 损失"不到 1 个单位"的份额，N 个 key 至多损失 N-1 个单位余数），因此 `total >= 0` 时
/// remainder 恒 `< weights.len()`，单趟必能耗尽；调用方不需要处理"未耗尽"的情况（Java 侧
/// 对应打印 `log.error` 而非 panic——本移植同样不对这个理论上不可达的分支做特殊处理，如果
/// 出现将残留在返回的 map 分配额里而不 panic，不破坏 Raft 状态机热路径）。
///
/// `total_weight <= 0`（`weights` 为空或所有权重求和为 0）：所有截断额恒为 0，余数即
/// `total` 本身，直接按 key 升序单趟分配（对应 Java 分母为 0 时不会真的调用 `truncMulDiv`
/// 的等价效果——调用方在余数分配层面永远不会真的因除零 panic，因为这条路径完全绕开了
/// [`trunc_mul_div`]）。
///
/// 返回：`BTreeMap<K, i64>`，key 集合与 `weights` 完全相同（即使最终分配额为 0 也保留
/// entry——对应 Java `receiverFees` 对每个 `receiverNotionals` 的 key 都建一个 entry，即便
/// fee 恰好算出 0；调用方按需再过滤 0 值，不在这里过滤）。
pub fn distribute_remainder_by_one<K: Ord + Copy>(total: i64, weights: &BTreeMap<K, i64>) -> BTreeMap<K, i64> {
    let mut result: BTreeMap<K, i64> = BTreeMap::new();
    if weights.is_empty() {
        return result;
    }
    let total_weight: i64 = weights.values().sum();
    let mut distributed: i64 = 0;
    if total_weight != 0 {
        for (&k, &w) in weights {
            let amount = trunc_mul_div(total, w, total_weight);
            distributed += amount;
            result.insert(k, amount);
        }
    } else {
        for &k in weights.keys() {
            result.insert(k, 0);
        }
    }
    let mut remainder = total - distributed;
    if remainder > 0 {
        for &k in weights.keys() {
            if remainder <= 0 {
                break;
            }
            *result.get_mut(&k).expect("key inserted above for every weights entry") += 1;
            remainder -= 1;
        }
    }
    result
}

#[cfg(test)]
mod tests {
    use super::*;

    // ------------------------------------------------------------------
    // ceil_mul_div — 翻译自 CoreArithmeticUtilsTest + CoreArithmeticUtilsCeilMulDiv128Test
    // ------------------------------------------------------------------

    #[test]
    fn ceil_mul_div_eth_trade_maker_fee_no_overflow() {
        let size = 100_000_000i64;
        let price = 3_000_000_000i64;
        let trade_amount = size * price; // 3e17，i64 内
        assert_eq!(ceil_mul_div(trade_amount, 500, 1_000_000), 150_000_000_000_000);
    }

    #[test]
    fn ceil_mul_div_btc_trade_taker_fee_no_overflow() {
        let size = 10_000_000i64;
        let price = 50_000_000_000i64;
        let trade_amount = size * price; // 5e17
        assert_eq!(ceil_mul_div(trade_amount, 1000, 1_000_000), 500_000_000_000_000);
    }

    #[test]
    fn ceil_mul_div_ceiling_rounding() {
        // (1e6+1)*3/7 = 3000003/7 = 428571.857... -> 428572
        assert_eq!(ceil_mul_div(1_000_001, 3, 7), 428_572);
        // 1e6*7/3 = 2333333.33... -> 2333334
        assert_eq!(ceil_mul_div(1_000_000, 7, 3), 2_333_334);
    }

    #[test]
    fn ceil_mul_div_exact_division() {
        assert_eq!(
            ceil_mul_div(300_000_000_000_000_000, 500, 1_000_000),
            150_000_000_000_000
        );
        assert_eq!(ceil_mul_div(0, 500, 1_000_000), 0);
    }

    #[test]
    fn ceil_mul_div_negative_b_rounds_toward_positive_infinity() {
        // ceil(5 * -3 / 2) = ceil(-7.5) = -7
        assert_eq!(ceil_mul_div(5, -3, 2), -7);
        // ceil(6 * -3 / 2) = ceil(-9) = -9（整除）
        assert_eq!(ceil_mul_div(6, -3, 2), -9);
        assert_eq!(
            ceil_mul_div(300_000_000_000_000_000, -500, 1_000_000),
            -150_000_000_000_000
        );
    }

    #[test]
    fn ceil_mul_div_block_subproduct_overflow_case() {
        // 原 Java 用于验证分块 fast path 子项溢出 fallback 到 128-bit；
        // Rust 单路径 i128 天然覆盖，无需区分。
        assert_eq!(
            ceil_mul_div(19_999_999_999, 1_000_000_000, 10_000_000_000),
            2_000_000_000
        );
    }

    #[test]
    fn ceil_mul_div_small_values_match_scalar_semantics() {
        assert_eq!(ceil_mul_div(0, 5, 3), 0);
        assert_eq!(ceil_mul_div(1, 1, 1), 1);
        assert_eq!(ceil_mul_div(2, 3, 4), 2); // ceil(6/4)
        assert_eq!(ceil_mul_div(7, 9, 5), 13); // ceil(63/5)
        assert_eq!(ceil_mul_div(7, 5, 2), 18); // ceil(35/2)
    }

    #[test]
    fn ceil_mul_div_product_overflows_i64_still_exact() {
        assert_eq!(
            ceil_mul_div(10_000_000_000, 10_000_000_000, 1_000_000),
            100_000_000_000_000
        );
        assert_eq!(
            ceil_mul_div(50_000_000_000, 20_000_000_000, 1_000_000),
            1_000_000_000_000_000
        );
        assert_eq!(
            ceil_mul_div(1_000_000_000_000, 50_000_000_000, 1_000_000),
            50_000_000_000_000_000
        );
    }

    #[test]
    fn ceil_mul_div_ceiling_rounding_on_large_overflow() {
        assert_eq!(
            ceil_mul_div(300_000_000_000_000_001, 500, 1_000_000),
            150_000_000_000_001
        );
    }

    #[test]
    fn ceil_mul_div_negative_product_overflows_i64() {
        assert_eq!(
            ceil_mul_div(10_000_000_000, -10_000_000_000, 1_000_000),
            -100_000_000_000_000
        );
        assert_eq!(
            ceil_mul_div(300_000_000_000_000_001, -500, 1_000_000),
            -150_000_000_000_000
        );
    }

    #[test]
    fn ceil_mul_div_fuzz_matches_bigint_oracle_positive() {
        // 值来自 Java CoreArithmeticUtilsCeilMulDiv128Test#fuzz_randomLargeValues_matchBigIntegerOracle
        // 期望值用 python Fraction+math.ceil 独立算出（见任务报告）。
        assert_eq!(
            ceil_mul_div(1_000_000_000_000_000_000, 9, 1),
            9_000_000_000_000_000_000
        );
        assert_eq!(ceil_mul_div(i64::MAX / 2, 3, 7), 1_976_436_865_040_309_102);
        assert_eq!(
            ceil_mul_div(1_152_921_504_606_846_976, 17, 13),
            1_507_666_582_947_415_277
        );
        assert_eq!(
            ceil_mul_div(9_223_372_036_854_775, 1000, 1_000_000),
            9_223_372_036_855
        );
        assert_eq!(ceil_mul_div(7, i64::MAX / 7, 11), 838_488_366_986_797_801);
    }

    #[test]
    fn ceil_mul_div_fuzz_matches_bigint_oracle_negative() {
        assert_eq!(
            ceil_mul_div(-1_000_000_000_000_000_000, 9, 7),
            -1_285_714_285_714_285_714
        );
        assert_eq!(
            ceil_mul_div(100_000_000 * 3_000_000_000, -500, 1_000_000),
            -150_000_000_000_000
        );
        assert_eq!(
            ceil_mul_div(-7, (100_000_000i64 * 3_000_000_000) / 7, 1_000_000),
            -299_999_999_999
        );
    }

    #[test]
    #[should_panic]
    fn ceil_mul_div_zero_divisor_panics() {
        ceil_mul_div(1, 1, 0);
    }

    #[test]
    #[should_panic]
    fn ceil_mul_div_negative_divisor_panics() {
        ceil_mul_div(1, 1, -1);
    }

    // ------------------------------------------------------------------
    // trunc_mul_div — 翻译自 CoreArithmeticUtilsTruncMulDiv128Test
    // ------------------------------------------------------------------

    #[test]
    fn trunc_mul_div_small_values_match_scalar_semantics() {
        assert_eq!(trunc_mul_div(0, 5, 3), 0);
        assert_eq!(trunc_mul_div(1, 1, 1), 1);
        assert_eq!(trunc_mul_div(7, 9, 5), 12); // trunc(63/5)
        assert_eq!(trunc_mul_div(7, 5, 2), 17); // trunc(35/2)
        assert_eq!(trunc_mul_div(6, 3, 3), 6);
    }

    #[test]
    fn trunc_mul_div_product_overflows_i64_positive_denom() {
        assert_eq!(
            trunc_mul_div(10_000_000_000, 10_000_000_000, 1_000_000),
            100_000_000_000_000
        );
        assert_eq!(
            trunc_mul_div(1_000_000_000_000, 50_000_000_000, 1_000_000),
            50_000_000_000_000_000
        );
    }

    #[test]
    fn trunc_mul_div_truncates_toward_zero_on_large_overflow() {
        // 余 500，截断不进位（区别于 ceil 会 +1）
        assert_eq!(
            trunc_mul_div(300_000_000_000_000_001, 500, 1_000_000),
            150_000_000_000_000
        );
        assert_eq!(
            trunc_mul_div(-300_000_000_000_000_001, 500, 1_000_000),
            -150_000_000_000_000
        );
    }

    #[test]
    fn trunc_mul_div_negative_denom_small() {
        assert_eq!(trunc_mul_div(5, 3, -2), -7);
        assert_eq!(trunc_mul_div(5, -3, -2), 7);
        assert_eq!(trunc_mul_div(6, 3, -3), -6);
    }

    #[test]
    fn trunc_mul_div_negative_denom_product_overflows() {
        assert_eq!(
            trunc_mul_div(10_000_000_000, -10_000_000_000, -1_000_000),
            100_000_000_000_000
        );
        assert_eq!(
            trunc_mul_div(10_000_000_000, 10_000_000_000, -1_000_000),
            -100_000_000_000_000
        );
    }

    #[test]
    fn trunc_mul_div_negative_a_product_overflows() {
        assert_eq!(
            trunc_mul_div(-10_000_000_000, 10_000_000_000, 1_000_000),
            -100_000_000_000_000
        );
    }

    #[test]
    fn trunc_mul_div_fuzz_matches_bigint_oracle_positive_denom() {
        assert_eq!(
            trunc_mul_div(1_000_000_000_000_000_000, 9, 1),
            9_000_000_000_000_000_000
        );
        assert_eq!(trunc_mul_div(i64::MAX / 2, 3, 7), 1_976_436_865_040_309_101);
        assert_eq!(
            trunc_mul_div(1_152_921_504_606_846_976, 17, 13),
            1_507_666_582_947_415_276
        );
        assert_eq!(
            trunc_mul_div(9_223_372_036_854_775, 1000, 1_000_000),
            9_223_372_036_854
        );
        assert_eq!(trunc_mul_div(7, i64::MAX / 7, 11), 838_488_366_986_797_800);
    }

    #[test]
    fn trunc_mul_div_fuzz_matches_bigint_oracle_negative_denom() {
        let notional = 100_000_000i64 * 3_000_000_000; // 3e17
        assert_eq!(trunc_mul_div(notional, 500, -1_000_000), -150_000_000_000_000);
        assert_eq!(trunc_mul_div(-notional, 500, -1_000_000), 150_000_000_000_000);
        assert_eq!(
            trunc_mul_div(10_000_000_000, -10_000_000_000, -1_000_000),
            100_000_000_000_000
        );
        assert_eq!(
            trunc_mul_div(300_000_000_000_000_001, 500, -1_000_000),
            -150_000_000_000_000
        );
        assert_eq!(
            trunc_mul_div(-300_000_000_000_000_001, 500, -999_999),
            150_000_150_000_150
        );
    }

    #[test]
    fn trunc_mul_div_fuzz_all_sign_combinations() {
        let a = 1_234_567_890_123i64;
        let b = 9_876_543_210i64;
        let c = 7_777_777i64;
        let expected_magnitude = 1_567_705_414_089_185i64;
        for &sa in &[1i64, -1] {
            for &sb in &[1i64, -1] {
                for &sc in &[1i64, -1] {
                    let expected = if (sa * sb * sc) > 0 {
                        expected_magnitude
                    } else {
                        -expected_magnitude
                    };
                    assert_eq!(
                        trunc_mul_div(sa * a, sb * b, sc * c),
                        expected,
                        "sa={sa} sb={sb} sc={sc}"
                    );
                }
            }
        }
    }

    #[test]
    #[should_panic]
    fn trunc_mul_div_zero_divisor_panics() {
        trunc_mul_div(1, 1, 0);
    }

    #[test]
    fn trunc_mul_div_fast_path_no_overflow() {
        assert_eq!(trunc_mul_div(3, 4, 5), 2);
    }

    // ------------------------------------------------------------------
    // ceil_mul_mul_div
    // ------------------------------------------------------------------

    #[test]
    fn ceil_mul_mul_div_fast_path_small_values() {
        assert_eq!(
            ceil_mul_mul_div(100_000_000, 3_000_000_000, 500, 1_000_000),
            150_000_000_000_000
        );
    }

    #[test]
    fn ceil_mul_mul_div_slow_path_large_notional() {
        // a*b = 1e10*1e10 = 1e20 溢出 i64，重排为 a*(b*c)
        assert_eq!(
            ceil_mul_mul_div(10_000_000_000, 10_000_000_000, 500, 1_000_000),
            50_000_000_000_000_000
        );
    }

    // ------------------------------------------------------------------
    // ceil_divide
    // ------------------------------------------------------------------

    #[test]
    fn ceil_divide_exact_and_remainder() {
        assert_eq!(ceil_divide(10, 5), 2);
        assert_eq!(ceil_divide(11, 5), 3);
        assert_eq!(ceil_divide(0, 5), 0);
    }

    // ------------------------------------------------------------------
    // convert_scale / size_price_to_currency_scale / symbol_to_currency_scale
    // ------------------------------------------------------------------

    #[test]
    fn convert_scale_same_scale_is_noop() {
        assert_eq!(convert_scale(12345, 1_000_000, 1_000_000), 12345);
    }

    #[test]
    fn convert_scale_shrinking_divides() {
        // from=1e14 (product scale) -> to=1e6：diff=8，除以 1e8
        assert_eq!(convert_scale(150_000_000_000_000, 100_000_000_000_000, 1_000_000), 1_500_000);
    }

    #[test]
    fn convert_scale_growing_multiplies() {
        assert_eq!(convert_scale(1_500, 1_000_000, 100_000_000_000_000), 150_000_000_000);
    }

    #[test]
    fn size_price_to_currency_scale_matches_convert_scale() {
        // base_scale_k=1e8, quote_scale_k=1e6 -> product 1e14; currency_scale_k=1e6
        assert_eq!(
            size_price_to_currency_scale(150_000_000_000_000, 100_000_000, 1_000_000, 1_000_000),
            1_500_000
        );
    }

    #[test]
    fn symbol_to_currency_scale_delegates_to_convert_scale() {
        assert_eq!(symbol_to_currency_scale(150_000_000, 100_000_000, 1_000_000), 1_500_000);
    }

    #[test]
    fn currency_to_size_price_scale_matches_convert_scale() {
        // 与 size_price_to_currency_scale_matches_convert_scale 互为反向换算。
        assert_eq!(
            currency_to_size_price_scale(1_500_000, 100_000_000, 1_000_000, 1_000_000),
            150_000_000_000_000
        );
    }

    #[test]
    fn currency_to_size_price_scale_roundtrips_with_size_price_to_currency_scale() {
        let base_scale_k = 100_000_000;
        let quote_scale_k = 1_000_000;
        let currency_scale_k = 1_000_000;
        let currency_amount = 42_000_000; // 币种记账单位下的一个金额
        let size_price_amount =
            currency_to_size_price_scale(currency_amount, base_scale_k, quote_scale_k, currency_scale_k);
        assert_eq!(
            size_price_to_currency_scale(size_price_amount, base_scale_k, quote_scale_k, currency_scale_k),
            currency_amount
        );
    }

    // ------------------------------------------------------------------
    // is_ask_price_too_low — 翻译自 CoreArithmeticUtilsTest
    // ------------------------------------------------------------------

    #[test]
    fn is_ask_price_too_low_no_overflow_high_price_high_fee() {
        assert!(!is_ask_price_too_low(1_000_000_000_000, 10_000_000, 1_000_000));
    }

    #[test]
    fn is_ask_price_too_low_boundary_at_fee_rate() {
        // ceil(1000/3) = 334
        assert!(!is_ask_price_too_low(334, 3, 1000));
        assert!(is_ask_price_too_low(333, 3, 1000));
    }

    #[test]
    fn is_ask_price_too_low_zero_taker_fee_proportional_returns_false() {
        assert!(!is_ask_price_too_low(1, 0, 1_000_000));
        assert!(!is_ask_price_too_low(1_000_000_000_000, 0, 1_000_000));
    }

    #[test]
    fn is_ask_price_too_low_zero_taker_fee_fixed_returns_false() {
        assert!(!is_ask_price_too_low(0, 0, 0));
        assert!(!is_ask_price_too_low(1, 0, 0));
    }

    #[test]
    fn is_ask_price_too_low_fixed_fee_mode() {
        // fee_scale_k == 0 (fixed): price < taker_fee
        assert!(is_ask_price_too_low(1, 5, 0));
        assert!(!is_ask_price_too_low(5, 5, 0));
        assert!(!is_ask_price_too_low(6, 5, 0));
    }

    // ------------------------------------------------------------------
    // 现货金额 / 手续费函数
    // ------------------------------------------------------------------

    #[test]
    fn calculate_amount_ask_is_identity() {
        assert_eq!(calculate_amount_ask(12345), 12345);
    }

    #[test]
    fn calculate_amount_bid_multiplies() {
        assert_eq!(calculate_amount_bid(100, 30), 3000);
    }

    #[test]
    fn calculate_taker_fee_fixed_vs_proportional() {
        assert_eq!(calculate_taker_fee(100, 3_000_000_000, 2, 0), 200);
        assert_eq!(
            calculate_taker_fee(100_000_000, 3_000_000_000, 500, 1_000_000),
            150_000_000_000_000
        );
    }

    #[test]
    fn calculate_maker_fee_fixed_vs_proportional() {
        assert_eq!(calculate_maker_fee(100, 3_000_000_000, 1, 0), 100);
        assert_eq!(
            calculate_maker_fee(100_000_000, 3_000_000_000, 500, 1_000_000),
            150_000_000_000_000
        );
    }

    #[test]
    fn calculate_amount_bid_taker_fee_fixed() {
        // 下单（taker）5块，100手，固定费2：扣 500 + 200 = 700
        assert_eq!(calculate_amount_bid_taker_fee(100, 5, 2, 0), 700);
    }

    #[test]
    fn calculate_amount_bid_taker_fee_proportional() {
        // 1 ETH @ 3000, taker_fee=500(bps-like)/1e6
        let size = 100_000_000i64;
        let price = 3_000_000_000i64;
        let trade_amount = size * price;
        let fee = 150_000_000_000_000i64; // ceil(3e17*500/1e6)
        assert_eq!(
            calculate_amount_bid_taker_fee(size, price, 500, 1_000_000),
            trade_amount + fee
        );
    }

    #[test]
    fn calculate_amount_bid_taker_fee_for_budget_fixed_uses_size() {
        // 固定费分支按 size 计费，不按 budget（Java 原始非对称行为）
        assert_eq!(calculate_amount_bid_taker_fee_for_budget(100, 500, 2, 0), 700);
    }

    #[test]
    fn calculate_amount_bid_taker_fee_for_budget_proportional() {
        // fee = ceil(3e17 * 500 / 1e6) = 1.5e14
        assert_eq!(
            calculate_amount_bid_taker_fee_for_budget(100_000_000, 300_000_000_000_000_000, 500, 1_000_000),
            300_000_000_000_000_000 + 150_000_000_000_000
        );
    }

    #[test]
    fn calculate_amount_bid_release_corr_maker_fixed_fee_doc_example() {
        // 文档示例：taker 固定2 / maker 固定1；size=100，hold=5，trade=4
        // 本金差 (5-4)*100=100；手续费差 100*(2-1)=100；合计 200
        assert_eq!(calculate_amount_bid_release_corr_maker(100, 5, 4, 2, 1, 0), 200);
    }

    #[test]
    fn calculate_amount_bid_release_corr_maker_proportional_fee_doc_example() {
        // 文档示例：taker 浮动0.1(=100000/1e6) maker 浮动0.01(=10000/1e6)；size=100 hold=5 trade=4
        // 本金差 100；手续费差 5*100*0.1 - 4*100*0.01 = 50 - 4 = 46；合计 146
        assert_eq!(
            calculate_amount_bid_release_corr_maker(100, 5, 4, 100_000, 10_000, 1_000_000),
            146
        );
    }

    // ------------------------------------------------------------------
    // P6 Task 1：强平数学原语 —— CoreArithmeticUtils.java:180-240
    // ------------------------------------------------------------------

    #[test]
    fn calculate_liquidation_fee_fixed_vs_proportional() {
        // 固定费：结构同 calculate_taker_fee。
        assert_eq!(calculate_liquidation_fee(100, 3_000_000_000, 2, 0), 200);
        // 比例费：与 calculate_taker_fee_fixed_vs_proportional 用同一组数字核对。
        assert_eq!(
            calculate_liquidation_fee(100_000_000, 3_000_000_000, 500, 1_000_000),
            150_000_000_000_000
        );
    }

    #[test]
    fn calculate_size_to_liquidate_long_exact_division() {
        // LONG（sign=+1）：E=-50, MM=40, Q=10, Pm=90, openInitMarginSum=50, openPriceSum=1000。
        // numerator=(E-MM)*Q=(-50-40)*10=-900
        // denominator=50+1*90*10-40-1*1000=50+900-40-1000=-90
        // ceil_divide(-900,-90)=10（整除，无需进位）
        assert_eq!(calculate_size_to_liquidate(-50, 40, 50, 10, 1000, 90, 1), 10);
    }

    #[test]
    fn calculate_size_to_liquidate_positive_sign_ceils_up_on_remainder() {
        // 任意标量组合验证 ceil 分支：sign=1, Q=7, Pm=13, openInitMarginSum=20, openPriceSum=50,
        // E=100, MM=30。
        // numerator=(100-30)*7=490
        // denominator=20+1*13*7-30-1*50=20+91-30-50=31
        // ceil_divide(490,31): 490/31=15 余25(31*15=465)≠0 -> 16
        assert_eq!(calculate_size_to_liquidate(100, 30, 20, 7, 50, 13, 1), 16);
    }

    #[test]
    fn calculate_size_to_liquidate_short_sign_flips_denominator_terms() {
        // 同上但 sign=-1（SHORT）：numerator 不受 sign 影响，仅 denominator 变。
        // denominator=20+(-1)*13*7-30-(-1)*50=20-91-30+50=-51
        // ceil_divide(490,-51): 490/-51 向零截断=-9（51*9=459<490<510=51*10），余数
        // 490-(-51*-9)=490-459=31≠0 -> -9+1=-8（本函数逐字对齐 ceil_divide 的既有实现，
        // 不重新论证其数学 ceiling 语义，见该函数文档）。
        assert_eq!(calculate_size_to_liquidate(100, 30, 20, 7, 50, 13, -1), -8);
    }

    #[test]
    fn calculate_deficit_after_liquidate_positive_sign() {
        // size=3, sign=1, openInitMarginSum=20, openVolume=7, openPriceSum=50, markPrice=13,
        // MM_now=40, MM_after=25。
        // deltaMM=40-25=15
        // numerator=20+1*(13*7-50)=20+41=61
        // ceil_mul_div(3,61,7)=ceil(183/7)=ceil(26.14..)=27
        // result=15-27=-12
        assert_eq!(calculate_deficit_after_liquidate(3, 1, 20, 7, 50, 13, 40, 25), -12);
    }

    #[test]
    fn calculate_deficit_after_liquidate_negative_sign() {
        // 同上但 sign=-1：numerator=20+(-1)*(91-50)=20-41=-21
        // ceil_mul_div(3,-21,7)=ceil(-63/7)=ceil(-9)=-9（整除，无需进位）
        // result=15-(-9)=24
        assert_eq!(calculate_deficit_after_liquidate(3, -1, 20, 7, 50, 13, 40, 25), 24);
    }

    #[test]
    fn calculate_deficit_after_liquidate_zero_size_is_pure_delta_mm() {
        // size=0：ceil_mul_div(0, numerator, Q)=0，结果退化为纯 deltaMM。
        assert_eq!(calculate_deficit_after_liquidate(0, 1, 20, 7, 50, 13, 40, 25), 15);
    }

    // ------------------------------------------------------------------
    // distribute_remainder_by_one — P6 Task 4 共享余数分配原语
    // ------------------------------------------------------------------

    #[test]
    fn distribute_remainder_by_one_exact_division_no_remainder() {
        // total=100, weights 1:1 且整除：50/50 各得 50，无截断损失。
        let weights = BTreeMap::from([(1i64, 50i64), (2i64, 50i64)]);
        let result = distribute_remainder_by_one(100, &weights);
        assert_eq!(result, BTreeMap::from([(1, 50), (2, 50)]));
    }

    #[test]
    fn distribute_remainder_by_one_truncation_dust_goes_to_lowest_key_first() {
        // total=10, 三个 key 等权重（各 1）：trunc(10*1/3)=3 每个，截断后 distributed=9，
        // remainder=1 -> 分给 key 升序第一个（key=1）。
        let weights = BTreeMap::from([(1i64, 1i64), (2i64, 1i64), (3i64, 1i64)]);
        let result = distribute_remainder_by_one(10, &weights);
        assert_eq!(result, BTreeMap::from([(1, 4), (2, 3), (3, 3)]), "余数 1 单位必须分给升序最小的 key=1");
        assert_eq!(result.values().sum::<i64>(), 10, "分配总额必须等于 total，无 dust 泄漏");
    }

    #[test]
    fn distribute_remainder_by_one_remainder_spans_multiple_keys_deterministic_order() {
        // total=11, 5 个 key 等权重（各 1）：trunc(11/5)=2 每个，distributed=10，remainder=1
        // -> 只有 key=1 (升序最小) 拿到 +1；这一版再验证 remainder=3 时前 3 个 key 都 +1。
        let weights = BTreeMap::from([(10i64, 1i64), (20i64, 1i64), (30i64, 1i64), (40i64, 1i64), (50i64, 1i64)]);
        let result = distribute_remainder_by_one(13, &weights); // trunc(13/5)=2 each, distributed=10, remainder=3
        assert_eq!(
            result,
            BTreeMap::from([(10, 3), (20, 3), (30, 3), (40, 2), (50, 2)]),
            "余数 3 单位必须按 key 升序分给前 3 个 key（10,20,30），不能绕回或跳过"
        );
        assert_eq!(result.values().sum::<i64>(), 13);
    }

    #[test]
    fn distribute_remainder_by_one_uneven_weights_pro_rata_plus_deterministic_remainder() {
        // 构造真实业务形状：total=100, 三个 receiver 按 notional 30/30/40 pro-rata。
        // trunc(100*30/100)=30, trunc(100*30/100)=30, trunc(100*40/100)=40 -> 恰好整除，无余数。
        // 换一组制造截断 dust：total=100, weights 33/33/34。
        let weights = BTreeMap::from([(1i64, 33i64), (2i64, 33i64), (3i64, 34i64)]);
        // trunc(100*33/100)=33 每个前两个, trunc(100*34/100)=34 -> distributed=100，remainder=0。
        let result = distribute_remainder_by_one(100, &weights);
        assert_eq!(result, BTreeMap::from([(1, 33), (2, 33), (3, 34)]));

        // 真正制造 dust：total=10, weights 3/3/4（分母 total_weight=10）。
        // trunc(10*3/10)=3, trunc(10*3/10)=3, trunc(10*4/10)=4 -> 恰好整除。改用 total_weight=3。
        let weights2 = BTreeMap::from([(1i64, 1i64), (2i64, 1i64), (3i64, 1i64)]);
        let result2 = distribute_remainder_by_one(7, &weights2); // trunc(7/3)=2 each, distributed=6, remainder=1
        assert_eq!(result2, BTreeMap::from([(1, 3), (2, 2), (3, 2)]), "确定性 dust 落到 key=1");
        assert_eq!(result2.values().sum::<i64>(), 7);
    }

    #[test]
    fn distribute_remainder_by_one_zero_total_weight_distributes_purely_by_key_order() {
        // total_weight==0（所有权重为 0）：截断额恒 0，remainder=total 全部按 key 升序单趟分配。
        let weights = BTreeMap::from([(1i64, 0i64), (2i64, 0i64), (3i64, 0i64)]);
        let result = distribute_remainder_by_one(2, &weights);
        assert_eq!(result, BTreeMap::from([(1, 1), (2, 1), (3, 0)]));
    }

    #[test]
    fn distribute_remainder_by_one_empty_weights_yields_empty_result() {
        let weights: BTreeMap<i64, i64> = BTreeMap::new();
        assert!(distribute_remainder_by_one(100, &weights).is_empty());
    }

    #[test]
    fn distribute_remainder_by_one_zero_total_yields_all_zero_entries() {
        let weights = BTreeMap::from([(1i64, 10i64), (2i64, 20i64)]);
        let result = distribute_remainder_by_one(0, &weights);
        assert_eq!(result, BTreeMap::from([(1, 0), (2, 0)]), "total=0：每个 key 都有 entry，值恒 0");
    }

    #[test]
    fn distribute_remainder_by_one_single_key_gets_everything() {
        let weights = BTreeMap::from([(42i64, 7i64)]);
        assert_eq!(distribute_remainder_by_one(999, &weights), BTreeMap::from([(42, 999)]));
    }
}
