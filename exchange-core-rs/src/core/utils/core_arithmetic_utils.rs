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

fn pow10(n: u32) -> i64 {
    POW10[n as usize]
}

fn log10(x: i64) -> i32 {
    match POW10.iter().position(|&p| p == x) {
        Some(idx) => idx as i32,
        None => panic!("x not power of 10: {x}"),
    }
}

pub(crate) fn mul_exact(a: i64, b: i64) -> i64 {
    let product = a as i128 * b as i128;
    i64::try_from(product).unwrap_or_else(|_| panic!("overflow: {a} * {b}"))
}

pub(crate) fn add_exact(a: i64, b: i64) -> i64 {
    let sum = a as i128 + b as i128;
    i64::try_from(sum).unwrap_or_else(|_| panic!("overflow: {a} + {b}"))
}

pub(crate) fn sub_exact(a: i64, b: i64) -> i64 {
    let diff = a as i128 - b as i128;
    i64::try_from(diff).unwrap_or_else(|_| panic!("overflow: {a} - {b}"))
}

fn ceil_div_i128(n: i128, d: i128) -> i128 {
    assert!(d != 0, "division by zero");
    let q = n / d;
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

pub fn ceil_mul_div(a: i64, b: i64, c: i64) -> i64 {
    assert!(c > 0, "c must be positive: {c}");
    let product = a as i128 * b as i128;
    narrow_i128(ceil_div_i128(product, c as i128), "ceil_mul_div")
}

pub fn trunc_mul_div(a: i64, b: i64, c: i64) -> i64 {
    assert!(c != 0, "division by zero");
    let product = a as i128 * b as i128;
    narrow_i128(product / c as i128, "trunc_mul_div")
}

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

pub fn ceil_divide(dividend: i64, divisor: i64) -> i64 {
    dividend / divisor + if dividend % divisor == 0 { 0 } else { 1 }
}

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

pub fn size_price_to_currency_scale(
    amount: i64,
    base_scale_k: i64,
    quote_scale_k: i64,
    currency_scale_k: i64,
) -> i64 {
    convert_scale(amount, mul_exact(base_scale_k, quote_scale_k), currency_scale_k)
}

pub fn currency_to_size_price_scale(
    amount: i64,
    base_scale_k: i64,
    quote_scale_k: i64,
    currency_scale_k: i64,
) -> i64 {
    convert_scale(amount, currency_scale_k, mul_exact(base_scale_k, quote_scale_k))
}

pub fn symbol_to_currency_scale(amount: i64, scale_k: i64, currency_scale_k: i64) -> i64 {
    convert_scale(amount, scale_k, currency_scale_k)
}

pub fn calculate_amount_ask(size: i64) -> i64 {
    size
}

pub fn calculate_amount_bid(size: i64, price: i64) -> i64 {
    mul_exact(size, price)
}

pub fn calculate_taker_fee(size: i64, price: i64, taker_fee: i64, fee_scale_k: i64) -> i64 {
    if fee_scale_k == 0 {
        mul_exact(size, taker_fee)
    } else {
        ceil_mul_mul_div(size, price, taker_fee, fee_scale_k)
    }
}

pub fn calculate_maker_fee(size: i64, price: i64, maker_fee: i64, fee_scale_k: i64) -> i64 {
    if fee_scale_k == 0 {
        mul_exact(size, maker_fee)
    } else {
        ceil_mul_mul_div(size, price, maker_fee, fee_scale_k)
    }
}

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

pub fn is_ask_price_too_low(price: i64, taker_fee: i64, fee_scale_k: i64) -> bool {
    if fee_scale_k == 0 {
        return price < taker_fee;
    }

    if taker_fee == 0 {
        return false;
    }
    price < ceil_divide(fee_scale_k, taker_fee)
}

pub fn calculate_liquidation_fee(size: i64, price: i64, liquidation_fee: i64, fee_scale_k: i64) -> i64 {
    if fee_scale_k == 0 {
        mul_exact(size, liquidation_fee)
    } else {
        ceil_mul_mul_div(size, price, liquidation_fee, fee_scale_k)
    }
}

pub fn calculate_size_to_liquidate(
    equity: i64,
    maintenance_margin: i64,
    open_init_margin_sum: i64,
    open_volume: i64,
    open_price_sum: i64,
    mark_price: i64,
    sign: i64,
) -> i64 {
    let numerator = mul_exact(equity - maintenance_margin, open_volume);
    let denominator =
        open_init_margin_sum + sign * mul_exact(mark_price, open_volume) - maintenance_margin - sign * open_price_sum;
    ceil_divide(numerator, denominator)
}

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
    delta_mm - ceil_mul_div(size, numerator, open_volume)
}

use std::collections::BTreeMap;

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

    #[test]
    fn ceil_mul_div_eth_trade_maker_fee_no_overflow() {
        let size = 100_000_000i64;
        let price = 3_000_000_000i64;
        let trade_amount = size * price;
        assert_eq!(ceil_mul_div(trade_amount, 500, 1_000_000), 150_000_000_000_000);
    }

    #[test]
    fn ceil_mul_div_btc_trade_taker_fee_no_overflow() {
        let size = 10_000_000i64;
        let price = 50_000_000_000i64;
        let trade_amount = size * price;
        assert_eq!(ceil_mul_div(trade_amount, 1000, 1_000_000), 500_000_000_000_000);
    }

    #[test]
    fn ceil_mul_div_ceiling_rounding() {
        assert_eq!(ceil_mul_div(1_000_001, 3, 7), 428_572);
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
        assert_eq!(ceil_mul_div(5, -3, 2), -7);
        assert_eq!(ceil_mul_div(6, -3, 2), -9);
        assert_eq!(
            ceil_mul_div(300_000_000_000_000_000, -500, 1_000_000),
            -150_000_000_000_000
        );
    }

    #[test]
    fn ceil_mul_div_block_subproduct_overflow_case() {
        assert_eq!(
            ceil_mul_div(19_999_999_999, 1_000_000_000, 10_000_000_000),
            2_000_000_000
        );
    }

    #[test]
    fn ceil_mul_div_small_values_match_scalar_semantics() {
        assert_eq!(ceil_mul_div(0, 5, 3), 0);
        assert_eq!(ceil_mul_div(1, 1, 1), 1);
        assert_eq!(ceil_mul_div(2, 3, 4), 2);
        assert_eq!(ceil_mul_div(7, 9, 5), 13);
        assert_eq!(ceil_mul_div(7, 5, 2), 18);
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

    #[test]
    fn trunc_mul_div_small_values_match_scalar_semantics() {
        assert_eq!(trunc_mul_div(0, 5, 3), 0);
        assert_eq!(trunc_mul_div(1, 1, 1), 1);
        assert_eq!(trunc_mul_div(7, 9, 5), 12);
        assert_eq!(trunc_mul_div(7, 5, 2), 17);
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
        let notional = 100_000_000i64 * 3_000_000_000;
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

    #[test]
    fn ceil_mul_mul_div_fast_path_small_values() {
        assert_eq!(
            ceil_mul_mul_div(100_000_000, 3_000_000_000, 500, 1_000_000),
            150_000_000_000_000
        );
    }

    #[test]
    fn ceil_mul_mul_div_slow_path_large_notional() {
        assert_eq!(
            ceil_mul_mul_div(10_000_000_000, 10_000_000_000, 500, 1_000_000),
            50_000_000_000_000_000
        );
    }

    #[test]
    fn ceil_divide_exact_and_remainder() {
        assert_eq!(ceil_divide(10, 5), 2);
        assert_eq!(ceil_divide(11, 5), 3);
        assert_eq!(ceil_divide(0, 5), 0);
    }

    #[test]
    fn convert_scale_same_scale_is_noop() {
        assert_eq!(convert_scale(12345, 1_000_000, 1_000_000), 12345);
    }

    #[test]
    fn convert_scale_shrinking_divides() {
        assert_eq!(convert_scale(150_000_000_000_000, 100_000_000_000_000, 1_000_000), 1_500_000);
    }

    #[test]
    fn convert_scale_growing_multiplies() {
        assert_eq!(convert_scale(1_500, 1_000_000, 100_000_000_000_000), 150_000_000_000);
    }

    #[test]
    fn size_price_to_currency_scale_matches_convert_scale() {
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
        let currency_amount = 42_000_000;
        let size_price_amount =
            currency_to_size_price_scale(currency_amount, base_scale_k, quote_scale_k, currency_scale_k);
        assert_eq!(
            size_price_to_currency_scale(size_price_amount, base_scale_k, quote_scale_k, currency_scale_k),
            currency_amount
        );
    }

    #[test]
    fn is_ask_price_too_low_no_overflow_high_price_high_fee() {
        assert!(!is_ask_price_too_low(1_000_000_000_000, 10_000_000, 1_000_000));
    }

    #[test]
    fn is_ask_price_too_low_boundary_at_fee_rate() {
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
        assert!(is_ask_price_too_low(1, 5, 0));
        assert!(!is_ask_price_too_low(5, 5, 0));
        assert!(!is_ask_price_too_low(6, 5, 0));
    }

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
        assert_eq!(calculate_amount_bid_taker_fee(100, 5, 2, 0), 700);
    }

    #[test]
    fn calculate_amount_bid_taker_fee_proportional() {
        let size = 100_000_000i64;
        let price = 3_000_000_000i64;
        let trade_amount = size * price;
        let fee = 150_000_000_000_000i64;
        assert_eq!(
            calculate_amount_bid_taker_fee(size, price, 500, 1_000_000),
            trade_amount + fee
        );
    }

    #[test]
    fn calculate_amount_bid_taker_fee_for_budget_fixed_uses_size() {
        assert_eq!(calculate_amount_bid_taker_fee_for_budget(100, 500, 2, 0), 700);
    }

    #[test]
    fn calculate_amount_bid_taker_fee_for_budget_proportional() {
        assert_eq!(
            calculate_amount_bid_taker_fee_for_budget(100_000_000, 300_000_000_000_000_000, 500, 1_000_000),
            300_000_000_000_000_000 + 150_000_000_000_000
        );
    }

    #[test]
    fn calculate_amount_bid_release_corr_maker_fixed_fee_doc_example() {
        assert_eq!(calculate_amount_bid_release_corr_maker(100, 5, 4, 2, 1, 0), 200);
    }

    #[test]
    fn calculate_amount_bid_release_corr_maker_proportional_fee_doc_example() {
        assert_eq!(
            calculate_amount_bid_release_corr_maker(100, 5, 4, 100_000, 10_000, 1_000_000),
            146
        );
    }

    #[test]
    fn calculate_liquidation_fee_fixed_vs_proportional() {
        assert_eq!(calculate_liquidation_fee(100, 3_000_000_000, 2, 0), 200);
        assert_eq!(
            calculate_liquidation_fee(100_000_000, 3_000_000_000, 500, 1_000_000),
            150_000_000_000_000
        );
    }

    #[test]
    fn calculate_size_to_liquidate_long_exact_division() {
        assert_eq!(calculate_size_to_liquidate(-50, 40, 50, 10, 1000, 90, 1), 10);
    }

    #[test]
    fn calculate_size_to_liquidate_positive_sign_ceils_up_on_remainder() {
        assert_eq!(calculate_size_to_liquidate(100, 30, 20, 7, 50, 13, 1), 16);
    }

    #[test]
    fn calculate_size_to_liquidate_short_sign_flips_denominator_terms() {
        assert_eq!(calculate_size_to_liquidate(100, 30, 20, 7, 50, 13, -1), -8);
    }

    #[test]
    fn calculate_deficit_after_liquidate_positive_sign() {
        assert_eq!(calculate_deficit_after_liquidate(3, 1, 20, 7, 50, 13, 40, 25), -12);
    }

    #[test]
    fn calculate_deficit_after_liquidate_negative_sign() {
        assert_eq!(calculate_deficit_after_liquidate(3, -1, 20, 7, 50, 13, 40, 25), 24);
    }

    #[test]
    fn calculate_deficit_after_liquidate_zero_size_is_pure_delta_mm() {
        assert_eq!(calculate_deficit_after_liquidate(0, 1, 20, 7, 50, 13, 40, 25), 15);
    }

    #[test]
    fn distribute_remainder_by_one_exact_division_no_remainder() {
        let weights = BTreeMap::from([(1i64, 50i64), (2i64, 50i64)]);
        let result = distribute_remainder_by_one(100, &weights);
        assert_eq!(result, BTreeMap::from([(1, 50), (2, 50)]));
    }

    #[test]
    fn distribute_remainder_by_one_truncation_dust_goes_to_lowest_key_first() {
        let weights = BTreeMap::from([(1i64, 1i64), (2i64, 1i64), (3i64, 1i64)]);
        let result = distribute_remainder_by_one(10, &weights);
        assert_eq!(result, BTreeMap::from([(1, 4), (2, 3), (3, 3)]), "the 1-unit remainder must go to the lowest key in ascending order, key=1");
        assert_eq!(result.values().sum::<i64>(), 10, "distributed total must equal total, no dust leakage");
    }

    #[test]
    fn distribute_remainder_by_one_remainder_spans_multiple_keys_deterministic_order() {
        let weights = BTreeMap::from([(10i64, 1i64), (20i64, 1i64), (30i64, 1i64), (40i64, 1i64), (50i64, 1i64)]);
        let result = distribute_remainder_by_one(13, &weights);
        assert_eq!(
            result,
            BTreeMap::from([(10, 3), (20, 3), (30, 3), (40, 2), (50, 2)]),
            "the 3-unit remainder must go to the first 3 keys in ascending order (10,20,30), no wraparound or skipping"
        );
        assert_eq!(result.values().sum::<i64>(), 13);
    }

    #[test]
    fn distribute_remainder_by_one_uneven_weights_pro_rata_plus_deterministic_remainder() {
        let weights = BTreeMap::from([(1i64, 33i64), (2i64, 33i64), (3i64, 34i64)]);
        let result = distribute_remainder_by_one(100, &weights);
        assert_eq!(result, BTreeMap::from([(1, 33), (2, 33), (3, 34)]));

        let weights2 = BTreeMap::from([(1i64, 1i64), (2i64, 1i64), (3i64, 1i64)]);
        let result2 = distribute_remainder_by_one(7, &weights2);
        assert_eq!(result2, BTreeMap::from([(1, 3), (2, 2), (3, 2)]), "deterministic dust lands on key=1");
        assert_eq!(result2.values().sum::<i64>(), 7);
    }

    #[test]
    fn distribute_remainder_by_one_zero_total_weight_distributes_purely_by_key_order() {
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
        assert_eq!(result, BTreeMap::from([(1, 0), (2, 0)]), "total=0: every key still has an entry, value always 0");
    }

    #[test]
    fn distribute_remainder_by_one_single_key_gets_everything() {
        let weights = BTreeMap::from([(42i64, 7i64)]);
        assert_eq!(distribute_remainder_by_one(999, &weights), BTreeMap::from([(42, 999)]));
    }

    #[allow(non_snake_case)]
    mod java_parity {
    use super::super::*;

    #[test]
    fn java_scale_sizePriceToCurrencyScale_convertToQuoteCurrency() {
        assert_eq!(size_price_to_currency_scale(1, 100_000, 10, 100), 0);
        assert_eq!(size_price_to_currency_scale(1_000_000, 100_000, 10, 100), 100);
    }

    #[test]
    fn java_scale_symbolToCurrencyScale_baseToBaseCurrency() {
        assert_eq!(symbol_to_currency_scale(1, 100_000, 100_000_000), 1_000);
        assert_eq!(symbol_to_currency_scale(2, 100_000, 100_000_000), 2_000);
    }

    #[test]
    fn java_scale_symbolToCurrencyScale_quoteToQuoteCurrency() {
        assert_eq!(symbol_to_currency_scale(1, 10, 100), 10);
        assert_eq!(symbol_to_currency_scale(2, 10, 100), 20);
    }

    #[test]
    fn java_scale_currencyToSymbolScale_baseCurrencyToBase() {
        assert_eq!(convert_scale(1_000, 100_000_000, 100_000), 1);
        assert_eq!(convert_scale(1_500, 100_000_000, 100_000), 1);
    }

    #[test]
    fn java_scale_currencyToSymbolScale_quoteCurrencyToQuote() {
        assert_eq!(convert_scale(10, 100, 10), 1);
        assert_eq!(convert_scale(15, 100, 10), 1);
    }

    #[test]
    fn java_scale_methods_withZeroInput() {
        assert_eq!(size_price_to_currency_scale(0, 100_000, 10, 100), 0);
        assert_eq!(symbol_to_currency_scale(0, 100_000, 100_000_000), 0);
        assert_eq!(symbol_to_currency_scale(0, 10, 100), 0);
        assert_eq!(convert_scale(0, 100_000_000, 100_000), 0);
        assert_eq!(convert_scale(0, 100, 10), 0);
    }

    #[test]
    fn java_scale_methods_withZeroDigitCurrency() {
        assert_eq!(size_price_to_currency_scale(1, 100_000, 10, 1), 0);
        assert_eq!(symbol_to_currency_scale(1, 100_000, 1), 0);
        assert_eq!(convert_scale(1, 1, 100_000), 100_000);
    }

    #[test]
    fn java_fee_fixed_priceIndependent() {
        assert_eq!(calculate_taker_fee(10, 50_000, 20, 0), 200);
        assert_eq!(calculate_taker_fee(10, 1, 20, 0), 200);
        assert_eq!(calculate_taker_fee(10, i64::MAX / 2, 20, 0), 200);
        assert_eq!(calculate_maker_fee(10, 50_000, 10, 0), 100);
        assert_eq!(calculate_maker_fee(10, 1, 10, 0), 100);
    }

    #[test]
    fn java_fee_fixed_zeroSize() {
        assert_eq!(calculate_taker_fee(0, 50_000, 20, 0), 0);
        assert_eq!(calculate_maker_fee(0, 50_000, 10, 0), 0);
    }

    #[test]
    fn java_fee_dynamic_formula() {
        assert_eq!(calculate_taker_fee(10, 50_000, 2, 100), 10_000);
        assert_eq!(calculate_maker_fee(10, 50_000, 1, 100), 5_000);
    }

    #[test]
    fn java_fee_dynamic_ceilingRounding() {
        assert_eq!(calculate_taker_fee(1, 1, 2, 100), 1);
    }

    #[test]
    fn java_fee_dynamic_zeroSize() {
        assert_eq!(calculate_taker_fee(0, 50_000, 2, 100), 0);
        assert_eq!(calculate_maker_fee(0, 50_000, 1, 100), 0);
    }

    #[test]
    fn java_fee_currencyScale_identityPassthrough() {
        let fee = calculate_taker_fee(10, 50_000, 20, 0);
        assert_eq!(size_price_to_currency_scale(fee, 1, 1, 1), 200);
        let fee2 = calculate_taker_fee(3, 25_000, 20, 0);
        assert_eq!(size_price_to_currency_scale(fee2, 1, 1, 1), 3 * 20);
    }

    fn size_to_liquidate_from_position(
        long: bool,
        open_volume: i64,
        open_price_sum: i64,
        open_init_margin_sum: i64,
        mm_rate: i64,
        mm_scale_k: i64,
        mark_price: i64,
    ) -> i64 {
        let sign = if long { 1 } else { -1 };
        let notional = mark_price * open_volume;
        let pnl = sign * (mark_price * open_volume - open_price_sum);
        let equity = open_init_margin_sum + pnl;
        let mm = (notional as i128 * mm_rate as i128 / mm_scale_k as i128) as i64;
        calculate_size_to_liquidate(
            equity,
            mm,
            open_init_margin_sum,
            open_volume,
            open_price_sum,
            mark_price,
            sign,
        )
    }

    #[test]
    fn java_sizeToLiquidate_longPosition() {
        assert_eq!(size_to_liquidate_from_position(true, 10, 1_000, 100, 100, 1_000, 90), 10);
    }

    #[test]
    fn java_sizeToLiquidate_shortPosition() {
        assert_eq!(size_to_liquidate_from_position(false, 5, 500, 50, 100, 1_000, 110), 5);
    }

    #[test]
    fn java_sizeToLiquidate_case1_long() {
        assert_eq!(size_to_liquidate_from_position(true, 100, 10_000, 2_000, 50, 1_000, 95), 100);
    }

    #[test]
    fn java_sizeToLiquidate_case2_long() {
        assert_eq!(size_to_liquidate_from_position(true, 200, 20_000, 4_000, 40, 1_000, 98), 200);
    }

    #[test]
    fn java_sizeToLiquidate_case3_short() {
        assert_eq!(size_to_liquidate_from_position(false, 150, 15_000, 3_000, 50, 1_000, 105), 150);
    }

    #[test]
    fn java_sizeToLiquidate_case4_short() {
        assert_eq!(size_to_liquidate_from_position(false, 300, 30_000, 6_000, 30, 1_000, 102), 300);
    }
    }
}
