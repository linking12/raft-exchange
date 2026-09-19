#[cfg(test)]
mod tests {

    use std::collections::BTreeMap;

    use exchange_core_rs::core::common::cmd::command_result_code::CommandResultCode;
    use exchange_core_rs::core::common::core_symbol_specification::CoreSymbolSpecification;
    use exchange_core_rs::core::common::margin_mode::MarginMode;
    use exchange_core_rs::core::common::order_action::OrderAction;
    use exchange_core_rs::core::common::order_type::OrderType;
    use exchange_core_rs::core::common::position_direction::PositionDirection;
    use exchange_core_rs::core::common::symbol_position_record::SymbolPositionRecord;
    use exchange_core_rs::core::common::symbol_type::SymbolType;
    use exchange_core_rs::core::exchange_api::{
        ClosePositionRequest, ExchangeApi, LiquidationScanRequest, MarginAdjustmentRequest, PlaceFuturesOrderRequest,
    };
    use exchange_core_rs::core::utils::core_arithmetic_utils::{calculate_maker_fee, calculate_taker_fee};

const XBT: i32 = 3762;
const USD: i32 = 840;
const ETH: i32 = 3928;
const BTC_SYM: i32 = 10000;
const ETH_SYM: i32 = 10001;

const BTC_MAKER_FEE: i64 = 10;
const BTC_TAKER_FEE: i64 = 20;
const BTC_FEE_SCALE_K: i64 = 0;

const ETH_MAKER_FEE: i64 = 1;
const ETH_TAKER_FEE: i64 = 2;
const ETH_FEE_SCALE_K: i64 = 100;

fn btc_futures_spec() -> CoreSymbolSpecification {
    CoreSymbolSpecification {
        symbol_id: BTC_SYM,
        symbol_type: SymbolType::FuturesContractPerpetual,
        base_currency: XBT,
        quote_currency: USD,
        base_scale_k: 1,
        quote_scale_k: 1,
        maker_fee: BTC_MAKER_FEE,
        taker_fee: BTC_TAKER_FEE,
        fee_scale_k: BTC_FEE_SCALE_K,
        init_margin: 1,
        init_margin_scale_k: 100,
        maintenance_margin: BTreeMap::from([(1000, 5), (100_000, 10)]),
        maintenance_margin_scale_k: 1000,
        max_leverage: BTreeMap::from([(2000, 5), (100_000, 10)]),
        ..Default::default()
    }
}

fn eth_futures_spec() -> CoreSymbolSpecification {
    CoreSymbolSpecification {
        symbol_id: ETH_SYM,
        symbol_type: SymbolType::FuturesContractPerpetual,
        base_currency: ETH,
        quote_currency: USD,
        base_scale_k: 1,
        quote_scale_k: 1,
        maker_fee: ETH_MAKER_FEE,
        taker_fee: ETH_TAKER_FEE,
        fee_scale_k: ETH_FEE_SCALE_K,
        init_margin: 1,
        init_margin_scale_k: 100,
        maintenance_margin: BTreeMap::from([(1000, 5), (100_000, 10)]),
        maintenance_margin_scale_k: 1000,
        max_leverage: BTreeMap::from([(2000, 5), (100_000, 10)]),
        ..Default::default()
    }
}

fn seed_btc(mark: i64) -> ExchangeApi {
    let mut api = ExchangeApi::new();
    api.add_currency(XBT, 1);
    api.add_currency(USD, 1);
    assert_eq!(api.add_futures_symbol(btc_futures_spec()), CommandResultCode::Success);
    assert_eq!(api.set_mark_price(BTC_SYM, mark), CommandResultCode::Success);
    api
}

fn seed_user(api: &mut ExchangeApi, uid: i64, usd: i64, txid: i64) {
    assert_eq!(api.add_user(uid), CommandResultCode::Success);
    assert_eq!(api.balance_adjustment(uid, USD, usd, txid), CommandResultCode::Success);
}

#[allow(clippy::too_many_arguments)]
fn place(
    api: &mut ExchangeApi,
    order_id: i64,
    uid: i64,
    symbol: i32,
    price: i64,
    size: i64,
    action: OrderAction,
    order_type: OrderType,
    margin_mode: MarginMode,
    leverage: i32,
) -> CommandResultCode {
    api.place_futures_order(PlaceFuturesOrderRequest {
        order_id,
        uid,
        symbol,
        price,
        size,
        action,
        order_type,
        leverage,
        margin_mode,
        reduce_only: false,
    })
}

fn btc_maker(size: i64, price: i64) -> i64 {
    calculate_maker_fee(size, price, BTC_MAKER_FEE, BTC_FEE_SCALE_K)
}
fn btc_taker(size: i64, price: i64) -> i64 {
    calculate_taker_fee(size, price, BTC_TAKER_FEE, BTC_FEE_SCALE_K)
}

fn assert_conserved_usd(api: &ExchangeApi) {
    let mut total: i64 = api.ups().users.values().map(|p| p.account(USD)).sum();
    total += api.adjustments(USD);
    total += api.fees(USD);
    for p in api.ups().users.values() {
        for pos in p.positions.values() {
            if pos.currency != USD {
                continue;
            }
            let mark = api
                .risk()
                .mark_price(pos.symbol)
                .unwrap_or_else(|| panic!("open position on symbol {} missing mark price", pos.symbol));
            total += pos.estimate_pnl(mark);
            total += pos.extra_margin;
        }
    }
    assert_eq!(total, 0, "futures global conservation broken: USD total={total}");
}

fn hedge_leg(api: &ExchangeApi, uid: i64, symbol: i32, dir: PositionDirection) -> Option<&SymbolPositionRecord> {
    api.ups()
        .get(uid)?
        .positions
        .values()
        .find(|p| p.symbol == symbol && p.direction == dir)
}

fn hedge_open_volume_sum(api: &ExchangeApi, uid: i64, symbol: i32) -> i64 {
    api.ups()
        .get(uid)
        .map(|p| p.positions.values().filter(|r| r.symbol == symbol).map(|r| r.open_volume).sum())
        .unwrap_or(0)
}

#[test]
fn futures_gtc_maker_taker_fee_calculation() {
    const MAKER: i64 = 1;
    const TAKER: i64 = 2;
    let size = 10i64;
    let price = 50_000i64;
    let deposit = 100_000i64;

    let mut api = seed_btc(price);
    seed_user(&mut api, MAKER, deposit, 1);
    seed_user(&mut api, TAKER, deposit, 2);

    assert_eq!(place(&mut api, 1001, MAKER, BTC_SYM, price, size, OrderAction::Bid, OrderType::Gtc, MarginMode::Cross, 1), CommandResultCode::Success);
    assert_eq!(place(&mut api, 1002, TAKER, BTC_SYM, price, size, OrderAction::Ask, OrderType::Gtc, MarginMode::Cross, 1), CommandResultCode::Success);

    let maker_pos = api.user_position(MAKER, BTC_SYM).expect("maker should have a position");
    assert_eq!(maker_pos.direction, PositionDirection::Long);
    assert_eq!(maker_pos.open_volume, size);
    let taker_pos = api.user_position(TAKER, BTC_SYM).expect("taker should have a position");
    assert_eq!(taker_pos.direction, PositionDirection::Short);
    assert_eq!(taker_pos.open_volume, size);

    let expected_maker_fee = btc_maker(size, price);
    let expected_taker_fee = btc_taker(size, price);
    assert_eq!(expected_maker_fee, 100);
    assert_eq!(expected_taker_fee, 200);

    assert_eq!(api.user_account(MAKER, USD), deposit - expected_maker_fee);
    assert_eq!(api.user_account(TAKER, USD), deposit - expected_taker_fee);
    assert_eq!(api.fees(USD), expected_maker_fee + expected_taker_fee);
    assert_conserved_usd(&api);
}

#[test]
fn futures_ioc_taker_fee_calculation() {
    const MAKER: i64 = 1;
    const TAKER: i64 = 2;
    let size = 5i64;
    let price = 45_000i64;
    let deposit = 100_000i64;

    let mut api = seed_btc(price);
    seed_user(&mut api, MAKER, deposit, 1);
    seed_user(&mut api, TAKER, deposit, 2);

    assert_eq!(place(&mut api, 2001, MAKER, BTC_SYM, price, size, OrderAction::Bid, OrderType::Gtc, MarginMode::Cross, 1), CommandResultCode::Success);
    assert_eq!(place(&mut api, 2002, TAKER, BTC_SYM, price, size, OrderAction::Ask, OrderType::Ioc, MarginMode::Cross, 1), CommandResultCode::Success);

    let expected_maker_fee = btc_maker(size, price);
    let expected_taker_fee = btc_taker(size, price);
    assert_eq!(expected_taker_fee, 100);

    assert_eq!(api.user_account(MAKER, USD), deposit - expected_maker_fee);
    assert_eq!(api.user_account(TAKER, USD), deposit - expected_taker_fee);
    assert_eq!(api.fees(USD), expected_maker_fee + expected_taker_fee);
    assert_eq!(api.user_position(TAKER, BTC_SYM).unwrap().open_volume, size);
    assert_conserved_usd(&api);
}

fn run_per_user_balance(taker_type: OrderType) {
    const MAKER: i64 = 9001;
    const TAKER: i64 = 9002;
    let size = 4i64;
    let price = 50_000i64;
    let deposit = 1_000_000i64;

    let mut api = seed_btc(price);
    seed_user(&mut api, MAKER, deposit, 1);
    seed_user(&mut api, TAKER, deposit, 2);

    assert_eq!(place(&mut api, 9101, MAKER, BTC_SYM, price, size, OrderAction::Ask, OrderType::Gtc, MarginMode::Cross, 1), CommandResultCode::Success);
    let taker_price = match taker_type {
        OrderType::FokBudget | OrderType::IocBudget => size * price,
        _ => price,
    };
    assert_eq!(place(&mut api, 9102, TAKER, BTC_SYM, taker_price, size, OrderAction::Bid, taker_type, MarginMode::Cross, 1), CommandResultCode::Success);

    assert_eq!(place(&mut api, 9103, TAKER, BTC_SYM, price, size, OrderAction::Ask, OrderType::Gtc, MarginMode::Cross, 1), CommandResultCode::Success);
    assert_eq!(place(&mut api, 9104, MAKER, BTC_SYM, price, size, OrderAction::Bid, OrderType::Gtc, MarginMode::Cross, 1), CommandResultCode::Success);

    assert!(api.user_position(MAKER, BTC_SYM).is_none(), "[{taker_type:?}] maker position should be fully closed");
    assert!(api.user_position(TAKER, BTC_SYM).is_none(), "[{taker_type:?}] taker position should be fully closed");

    let maker_fee = btc_maker(size, price);
    let taker_fee = btc_taker(size, price);
    assert_eq!(api.user_account(MAKER, USD), deposit - maker_fee - taker_fee, "[{taker_type:?}] maker account");
    assert_eq!(api.user_account(TAKER, USD), deposit - taker_fee - maker_fee, "[{taker_type:?}] taker account");
    assert_eq!(api.fees(USD), 2 * (maker_fee + taker_fee));
    assert_conserved_usd(&api);
}

#[test]
fn futures_per_user_balance_across_order_types() {
    run_per_user_balance(OrderType::Gtc);
    run_per_user_balance(OrderType::Ioc);
    run_per_user_balance(OrderType::FokBudget);
    run_per_user_balance(OrderType::IocBudget);
}

fn run_full_lifecycle(taker_type: OrderType) {
    const MAKER: i64 = 9201;
    const TAKER: i64 = 9202;
    let size = 4i64;
    let price = 50_000i64;
    let deposit = 1_000_000i64;

    let mut api = seed_btc(price);
    seed_user(&mut api, MAKER, deposit, 1);
    seed_user(&mut api, TAKER, deposit, 2);
    assert_eq!(api.user_account(MAKER, USD), deposit);
    assert_eq!(api.user_account(TAKER, USD), deposit);
    assert_eq!(api.adjustments(USD), -2 * deposit, "[{taker_type:?}] adjustments = -2*deposit after deposit");
    assert_conserved_usd(&api);

    assert_eq!(place(&mut api, 9301, MAKER, BTC_SYM, price, size, OrderAction::Ask, OrderType::Gtc, MarginMode::Cross, 1), CommandResultCode::Success);
    let taker_price = match taker_type {
        OrderType::FokBudget | OrderType::IocBudget => size * price,
        _ => price,
    };
    assert_eq!(place(&mut api, 9302, TAKER, BTC_SYM, taker_price, size, OrderAction::Bid, taker_type, MarginMode::Cross, 1), CommandResultCode::Success);

    assert_eq!(place(&mut api, 9303, TAKER, BTC_SYM, price, size, OrderAction::Ask, OrderType::Gtc, MarginMode::Cross, 1), CommandResultCode::Success);
    assert_eq!(place(&mut api, 9304, MAKER, BTC_SYM, price, size, OrderAction::Bid, OrderType::Gtc, MarginMode::Cross, 1), CommandResultCode::Success);

    let maker_fee = btc_maker(size, price);
    let taker_fee = btc_taker(size, price);
    let maker_bal = deposit - maker_fee - taker_fee;
    let taker_bal = deposit - taker_fee - maker_fee;
    assert_eq!(api.user_account(MAKER, USD), maker_bal);
    assert_eq!(api.user_account(TAKER, USD), taker_bal);
    assert_conserved_usd(&api);

    assert_eq!(api.balance_adjustment(MAKER, USD, -maker_bal, 3), CommandResultCode::Success);
    assert_eq!(api.balance_adjustment(TAKER, USD, -taker_bal, 4), CommandResultCode::Success);

    assert_eq!(api.user_account(MAKER, USD), 0);
    assert_eq!(api.user_account(TAKER, USD), 0);
    let expected_adjustments = -2 * (maker_fee + taker_fee);
    assert_eq!(api.adjustments(USD), expected_adjustments, "[{taker_type:?}] net adjustments");
    assert_eq!(api.fees(USD), 2 * (maker_fee + taker_fee), "[{taker_type:?}] total fees");
    assert_conserved_usd(&api);
}

#[test]
fn futures_full_lifecycle_with_deposit_withdraw() {
    run_full_lifecycle(OrderType::Gtc);
    run_full_lifecycle(OrderType::Ioc);
    run_full_lifecycle(OrderType::FokBudget);
    run_full_lifecycle(OrderType::IocBudget);
}

#[test]
fn futures_extra_margin_full_lifecycle_conservation() {
    const TAKER: i64 = 9701;
    const MAKER: i64 = 9702;
    let deposit = 1_000_000i64;
    let extra_margin_amount = 200_000i64;
    let price = 50_000i64;
    let size = 4i64;
    let leverage = 10i32;

    let mut api = seed_btc(price);
    seed_user(&mut api, TAKER, deposit, 1);
    seed_user(&mut api, MAKER, deposit * 10, 2);
    assert_conserved_usd(&api);

    assert_eq!(place(&mut api, 9801, MAKER, BTC_SYM, price, size, OrderAction::Ask, OrderType::Gtc, MarginMode::Isolated, leverage), CommandResultCode::Success);
    assert_eq!(place(&mut api, 9802, TAKER, BTC_SYM, price, size, OrderAction::Bid, OrderType::Gtc, MarginMode::Isolated, leverage), CommandResultCode::Success);
    assert_conserved_usd(&api);

    let account_before = api.user_account(TAKER, USD);
    assert_eq!(
        api.margin_adjustment(MarginAdjustmentRequest {
            uid: TAKER,
            symbol: BTC_SYM,
            action: OrderAction::Bid,
            amount: extra_margin_amount,
            margin_mode: MarginMode::Isolated,
            order_id: 9999,
        }),
        CommandResultCode::Success
    );
    assert_eq!(api.user_account(TAKER, USD), account_before - extra_margin_amount, "account minus extraMarginAmount after topping up");
    assert_eq!(api.user_position(TAKER, BTC_SYM).unwrap().extra_margin, extra_margin_amount, "position.extra_margin == extraMarginAmount");
    assert_conserved_usd(&api);

    assert_eq!(place(&mut api, 9803, TAKER, BTC_SYM, price, size, OrderAction::Ask, OrderType::Gtc, MarginMode::Isolated, leverage), CommandResultCode::Success);
    assert_eq!(place(&mut api, 9804, MAKER, BTC_SYM, price, size, OrderAction::Bid, OrderType::Gtc, MarginMode::Isolated, leverage), CommandResultCode::Success);
    assert!(api.user_position(TAKER, BTC_SYM).is_none(), "position is torn down after closing, extra_margin refunded");
    assert_conserved_usd(&api);

    let taker_bal = api.user_account(TAKER, USD);
    let maker_bal = api.user_account(MAKER, USD);
    if taker_bal != 0 {
        assert_eq!(api.balance_adjustment(TAKER, USD, -taker_bal, 3), CommandResultCode::Success);
    }
    if maker_bal != 0 {
        assert_eq!(api.balance_adjustment(MAKER, USD, -maker_bal, 4), CommandResultCode::Success);
    }

    assert_eq!(api.user_account(TAKER, USD), 0);
    assert_eq!(api.user_account(MAKER, USD), 0);
    assert_conserved_usd(&api);
}

#[test]
fn futures_position_closing_fee_calculation() {
    const MAKER: i64 = 1;
    const TAKER: i64 = 2;
    const U3: i64 = 3;
    let open_size = 20i64;
    let close_size = 10i64;
    let price = 48_000i64;
    let deposit = 100_000i64;

    let mut api = seed_btc(price);
    seed_user(&mut api, MAKER, deposit, 1);
    seed_user(&mut api, TAKER, deposit, 2);
    seed_user(&mut api, U3, deposit, 3);

    assert_eq!(place(&mut api, 3001, MAKER, BTC_SYM, price, open_size, OrderAction::Bid, OrderType::Gtc, MarginMode::Cross, 1), CommandResultCode::Success);
    assert_eq!(place(&mut api, 3002, TAKER, BTC_SYM, price, open_size, OrderAction::Ask, OrderType::Gtc, MarginMode::Cross, 1), CommandResultCode::Success);
    let fees_after_open = api.fees(USD);

    assert_eq!(place(&mut api, 3003, TAKER, BTC_SYM, price, close_size, OrderAction::Bid, OrderType::Gtc, MarginMode::Cross, 1), CommandResultCode::Success);
    assert_eq!(place(&mut api, 3004, U3, BTC_SYM, price, close_size, OrderAction::Ask, OrderType::Gtc, MarginMode::Cross, 1), CommandResultCode::Success);

    let expected_maker_close = btc_maker(close_size, price);
    let expected_taker_close = btc_taker(close_size, price);
    assert_eq!(api.fees(USD) - fees_after_open, expected_maker_close + expected_taker_close);

    assert_eq!(api.user_account(MAKER, USD), deposit - btc_maker(open_size, price));
    assert_eq!(api.user_account(TAKER, USD), deposit - btc_taker(open_size, price) - expected_maker_close);
    assert_eq!(api.user_account(U3, USD), deposit - expected_taker_close);

    assert_eq!(api.user_position(MAKER, BTC_SYM).unwrap().open_volume, open_size);
    assert_eq!(api.user_position(TAKER, BTC_SYM).unwrap().open_volume, open_size - close_size);
    assert_eq!(api.user_position(TAKER, BTC_SYM).unwrap().direction, PositionDirection::Short);
    assert_eq!(api.user_position(U3, BTC_SYM).unwrap().open_volume, close_size);
    assert_conserved_usd(&api);
}

#[test]
fn dynamic_fee_calculation_accuracy() {
    let price = 50_000i64;
    for size in [15i64, 1, 5, 10, 100, 1000] {
        let expected_maker = size * price * ETH_MAKER_FEE / ETH_FEE_SCALE_K;
        let expected_taker = size * price * ETH_TAKER_FEE / ETH_FEE_SCALE_K;
        assert_eq!(calculate_maker_fee(size, price, ETH_MAKER_FEE, ETH_FEE_SCALE_K), expected_maker, "maker fee size={size}");
        assert_eq!(calculate_taker_fee(size, price, ETH_TAKER_FEE, ETH_FEE_SCALE_K), expected_taker, "taker fee size={size}");
    }
}

#[test]
fn dynamic_fee_trading_with_eth_symbol() {
    const MAKER: i64 = 1;
    const TAKER: i64 = 2;
    let size = 5i64;
    let price = 3_000i64;
    let deposit = 100_000i64;

    let mut api = ExchangeApi::new();
    api.add_currency(XBT, 1);
    api.add_currency(USD, 1);
    api.add_currency(ETH, 1);
    assert_eq!(api.add_futures_symbol(eth_futures_spec()), CommandResultCode::Success);
    assert_eq!(api.set_mark_price(ETH_SYM, price), CommandResultCode::Success);
    seed_user(&mut api, MAKER, deposit, 1);
    seed_user(&mut api, TAKER, deposit, 2);

    assert_eq!(place(&mut api, 7001, MAKER, ETH_SYM, price, size, OrderAction::Bid, OrderType::Gtc, MarginMode::Cross, 1), CommandResultCode::Success);
    assert_eq!(place(&mut api, 7002, TAKER, ETH_SYM, price, size, OrderAction::Ask, OrderType::Gtc, MarginMode::Cross, 1), CommandResultCode::Success);

    let expected_maker_fee = calculate_maker_fee(size, price, ETH_MAKER_FEE, ETH_FEE_SCALE_K);
    let expected_taker_fee = calculate_taker_fee(size, price, ETH_TAKER_FEE, ETH_FEE_SCALE_K);
    assert_eq!(expected_maker_fee, 150);
    assert_eq!(expected_taker_fee, 300);

    assert_eq!(api.user_account(MAKER, USD), deposit - expected_maker_fee);
    assert_eq!(api.user_account(TAKER, USD), deposit - expected_taker_fee);
    assert_eq!(api.fees(USD), expected_maker_fee + expected_taker_fee);
    assert_conserved_usd(&api);
}

#[test]
fn futures_maker_one_to_many_fee_calculation() {
    const MAKER: i64 = 1;
    const T1: i64 = 2;
    const T2: i64 = 3;
    const T3: i64 = 4;
    let total = 30i64;
    let (s1, s2, s3) = (10i64, 12i64, 8i64);
    let price = 52_000i64;
    let deposit = 100_000i64;

    let mut api = seed_btc(price);
    seed_user(&mut api, MAKER, deposit, 1);
    seed_user(&mut api, T1, deposit, 2);
    seed_user(&mut api, T2, deposit, 3);
    seed_user(&mut api, T3, deposit, 4);

    assert_eq!(place(&mut api, 4001, MAKER, BTC_SYM, price, total, OrderAction::Bid, OrderType::Gtc, MarginMode::Cross, 1), CommandResultCode::Success);
    assert_eq!(place(&mut api, 4002, T1, BTC_SYM, price, s1, OrderAction::Ask, OrderType::Gtc, MarginMode::Cross, 1), CommandResultCode::Success);
    assert_eq!(place(&mut api, 4003, T2, BTC_SYM, price, s2, OrderAction::Ask, OrderType::Ioc, MarginMode::Cross, 1), CommandResultCode::Success);
    assert_eq!(place(&mut api, 4004, T3, BTC_SYM, price, s3, OrderAction::Ask, OrderType::Gtc, MarginMode::Cross, 1), CommandResultCode::Success);

    assert_eq!(api.user_position(MAKER, BTC_SYM).unwrap().open_volume, total);
    let expected_total_maker_fee = btc_maker(s1, price) + btc_maker(s2, price) + btc_maker(s3, price);
    let expected_total_taker_fee = btc_taker(s1, price) + btc_taker(s2, price) + btc_taker(s3, price);
    assert_eq!(api.user_account(MAKER, USD), deposit - expected_total_maker_fee);
    assert_eq!(api.fees(USD), expected_total_maker_fee + expected_total_taker_fee);
    assert_eq!(api.user_position(T1, BTC_SYM).unwrap().open_volume, s1);
    assert_eq!(api.user_position(T2, BTC_SYM).unwrap().open_volume, s2);
    assert_eq!(api.user_position(T3, BTC_SYM).unwrap().open_volume, s3);
    assert_conserved_usd(&api);
}

#[test]
fn futures_taker_one_to_many_fee_calculation() {
    const M1: i64 = 1;
    const M2: i64 = 2;
    const M3: i64 = 3;
    const TAKER: i64 = 4;
    let (s1, s2, s3) = (8i64, 9i64, 7i64);
    let total = s1 + s2 + s3;
    let (p1, p2, p3) = (49_000i64, 49_100i64, 49_200i64);
    let deposit = 100_000i64;

    let mut api = seed_btc(p1);
    seed_user(&mut api, M1, deposit, 1);
    seed_user(&mut api, M2, deposit, 2);
    seed_user(&mut api, M3, deposit, 3);
    seed_user(&mut api, TAKER, deposit, 4);

    assert_eq!(place(&mut api, 5001, M1, BTC_SYM, p1, s1, OrderAction::Ask, OrderType::Gtc, MarginMode::Cross, 1), CommandResultCode::Success);
    assert_eq!(place(&mut api, 5002, M2, BTC_SYM, p2, s2, OrderAction::Ask, OrderType::Gtc, MarginMode::Cross, 1), CommandResultCode::Success);
    assert_eq!(place(&mut api, 5003, M3, BTC_SYM, p3, s3, OrderAction::Ask, OrderType::Gtc, MarginMode::Cross, 1), CommandResultCode::Success);
    assert_eq!(place(&mut api, 5004, TAKER, BTC_SYM, p3, total, OrderAction::Bid, OrderType::Gtc, MarginMode::Cross, 1), CommandResultCode::Success);

    let expected_total_taker_fee = btc_taker(s1, p1) + btc_taker(s2, p2) + btc_taker(s3, p3);
    let expected_total_maker_fee = btc_maker(s1, p1) + btc_maker(s2, p2) + btc_maker(s3, p3);
    assert_eq!(api.user_position(TAKER, BTC_SYM).unwrap().open_volume, total);
    assert_eq!(api.user_position(TAKER, BTC_SYM).unwrap().direction, PositionDirection::Long);
    assert_eq!(api.user_account(TAKER, USD), deposit - expected_total_taker_fee);
    assert_eq!(api.fees(USD), expected_total_maker_fee + expected_total_taker_fee);
    assert_conserved_usd(&api);
}

#[test]
fn futures_mixed_order_types_fee_calculation() {
    const M1: i64 = 1;
    const M2: i64 = 2;
    const T1: i64 = 3;
    const T2: i64 = 4;
    let size = 6i64;
    let price = 51_000i64;
    let deposit = 100_000i64;

    let mut api = seed_btc(price);
    seed_user(&mut api, M1, deposit, 1);
    seed_user(&mut api, M2, deposit, 2);
    seed_user(&mut api, T1, deposit, 3);
    seed_user(&mut api, T2, deposit, 4);

    assert_eq!(place(&mut api, 6001, M1, BTC_SYM, price, size, OrderAction::Bid, OrderType::Gtc, MarginMode::Cross, 1), CommandResultCode::Success);
    assert_eq!(place(&mut api, 6002, M2, BTC_SYM, price, size, OrderAction::Bid, OrderType::Gtc, MarginMode::Cross, 1), CommandResultCode::Success);
    assert_eq!(place(&mut api, 6003, T1, BTC_SYM, price, size, OrderAction::Ask, OrderType::Gtc, MarginMode::Cross, 1), CommandResultCode::Success);
    assert_eq!(place(&mut api, 6004, T2, BTC_SYM, price, size, OrderAction::Ask, OrderType::Ioc, MarginMode::Cross, 1), CommandResultCode::Success);

    let maker_fee = btc_maker(size, price);
    let taker_fee = btc_taker(size, price);
    assert_eq!(api.user_account(M1, USD), deposit - maker_fee);
    assert_eq!(api.user_account(M2, USD), deposit - maker_fee);
    assert_eq!(api.user_account(T1, USD), deposit - taker_fee);
    assert_eq!(api.user_account(T2, USD), deposit - taker_fee);
    assert_eq!(api.fees(USD), 2 * (maker_fee + taker_fee));
    assert_eq!(api.user_position(M1, BTC_SYM).unwrap().direction, PositionDirection::Long);
    assert_eq!(api.user_position(T1, BTC_SYM).unwrap().direction, PositionDirection::Short);
    assert_conserved_usd(&api);
}

#[test]
fn futures_fok_budget_taker_fee_calculation() {
    const MAKER: i64 = 1;
    const TAKER: i64 = 2;
    let size = 8i64;
    let price = 46_000i64;
    let budget = size * price;
    let deposit = 100_000i64;

    let mut api = seed_btc(price);
    seed_user(&mut api, MAKER, deposit, 1);
    seed_user(&mut api, TAKER, deposit, 2);

    assert_eq!(place(&mut api, 7001, MAKER, BTC_SYM, price, size, OrderAction::Ask, OrderType::Gtc, MarginMode::Cross, 1), CommandResultCode::Success);
    assert_eq!(place(&mut api, 7002, TAKER, BTC_SYM, budget, size, OrderAction::Bid, OrderType::FokBudget, MarginMode::Cross, 1), CommandResultCode::Success);

    let expected_taker_fee = btc_taker(size, price);
    assert_eq!(api.user_position(TAKER, BTC_SYM).unwrap().open_volume, size);
    assert_eq!(api.user_account(TAKER, USD), deposit - expected_taker_fee);
    assert_eq!(api.fees(USD), btc_maker(size, price) + expected_taker_fee);
    assert_conserved_usd(&api);
}

#[test]
fn futures_ioc_budget_full_fill_taker_fee_calculation() {
    const MAKER: i64 = 1;
    const TAKER: i64 = 2;
    let size = 8i64;
    let price = 46_000i64;
    let budget = size * price;
    let deposit = 100_000i64;

    let mut api = seed_btc(price);
    seed_user(&mut api, MAKER, deposit, 1);
    seed_user(&mut api, TAKER, deposit, 2);

    assert_eq!(place(&mut api, 7101, MAKER, BTC_SYM, price, size, OrderAction::Ask, OrderType::Gtc, MarginMode::Cross, 1), CommandResultCode::Success);
    assert_eq!(place(&mut api, 7102, TAKER, BTC_SYM, budget, size, OrderAction::Bid, OrderType::IocBudget, MarginMode::Cross, 1), CommandResultCode::Success);

    let expected_taker_fee = btc_taker(size, price);
    assert_eq!(api.user_position(TAKER, BTC_SYM).unwrap().open_volume, size, "IOC_BUDGET fully filled, lastQty == size");
    assert_eq!(api.user_account(TAKER, USD), deposit - expected_taker_fee);
    assert_eq!(api.fees(USD), btc_maker(size, price) + expected_taker_fee);
    assert_conserved_usd(&api);
}

#[test]
fn futures_ioc_budget_partial_fill_taker_fee_calculation() {
    const MAKER: i64 = 1;
    const TAKER: i64 = 2;
    let requested = 10i64;
    let filled = 6i64;
    let price = 48_000i64;
    let budget = filled * price;
    let deposit = 100_000i64;

    let mut api = seed_btc(price);
    seed_user(&mut api, MAKER, deposit, 1);
    seed_user(&mut api, TAKER, deposit, 2);

    assert_eq!(place(&mut api, 7201, MAKER, BTC_SYM, price, requested, OrderAction::Ask, OrderType::Gtc, MarginMode::Cross, 1), CommandResultCode::Success);
    assert_eq!(place(&mut api, 7202, TAKER, BTC_SYM, budget, requested, OrderAction::Bid, OrderType::IocBudget, MarginMode::Cross, 1), CommandResultCode::Success);

    let expected_taker_fee = btc_taker(filled, price);
    let wrong_taker_fee = btc_taker(requested, price);
    assert_ne!(expected_taker_fee, wrong_taker_fee, "sanity: fee for filled differs from fee for requested");

    assert_eq!(api.user_position(TAKER, BTC_SYM).unwrap().open_volume, filled, "taker only opens the filled 6, not the requested amount");
    assert_eq!(api.user_account(TAKER, USD), deposit - expected_taker_fee);
    assert_eq!(api.user_position(MAKER, BTC_SYM).unwrap().open_volume, filled);
    assert_eq!(api.fees(USD), btc_maker(filled, price) + expected_taker_fee);
    assert!(api.fees(USD) > 0, "should have collected a USD fee");
    assert_conserved_usd(&api);
}

#[test]
fn futures_fee_calculation_parameters() {
    const MAKER: i64 = 1;
    const TAKER: i64 = 2;
    let size = 13i64;
    let price = 47_123i64;
    let deposit = 100_000i64;

    let mut api = seed_btc(price);
    seed_user(&mut api, MAKER, deposit, 1);
    seed_user(&mut api, TAKER, deposit, 2);

    assert_eq!(place(&mut api, 8001, MAKER, BTC_SYM, price, size, OrderAction::Bid, OrderType::Gtc, MarginMode::Cross, 1), CommandResultCode::Success);
    assert_eq!(place(&mut api, 8002, TAKER, BTC_SYM, price, size, OrderAction::Ask, OrderType::Gtc, MarginMode::Cross, 1), CommandResultCode::Success);

    let maker_fee = btc_maker(size, price);
    let taker_fee = btc_taker(size, price);
    assert_eq!(api.user_account(MAKER, USD), deposit - maker_fee);
    assert_eq!(api.user_account(TAKER, USD), deposit - taker_fee);
    assert_eq!(api.fees(USD), maker_fee + taker_fee);
    assert_conserved_usd(&api);
}

#[test]
fn futures_maker_one_to_many_partial_fill_fee_consistency() {
    const MAKER: i64 = 1;
    const T1: i64 = 2;
    const T2: i64 = 3;
    const T3: i64 = 4;
    let maker_total = 50i64;
    let (s1, s2, s3) = (8i64, 12i64, 15i64);
    let price = 48_500i64;
    let deposit = 100_000i64;

    let mut api = seed_btc(price);
    seed_user(&mut api, MAKER, deposit, 1);
    seed_user(&mut api, T1, deposit, 2);
    seed_user(&mut api, T2, deposit, 3);
    seed_user(&mut api, T3, deposit, 4);
    let fees_before = api.fees(USD);

    assert_eq!(place(&mut api, 9001, MAKER, BTC_SYM, price, maker_total, OrderAction::Bid, OrderType::Gtc, MarginMode::Cross, 1), CommandResultCode::Success);
    assert_eq!(place(&mut api, 9002, T1, BTC_SYM, price, s1, OrderAction::Ask, OrderType::Gtc, MarginMode::Cross, 1), CommandResultCode::Success);
    assert_eq!(place(&mut api, 9003, T2, BTC_SYM, price, s2, OrderAction::Ask, OrderType::Ioc, MarginMode::Cross, 1), CommandResultCode::Success);
    assert_eq!(place(&mut api, 9004, T3, BTC_SYM, price, s3, OrderAction::Ask, OrderType::Gtc, MarginMode::Cross, 1), CommandResultCode::Success);

    let filled = s1 + s2 + s3;
    assert_eq!(api.user_position(MAKER, BTC_SYM).unwrap().open_volume, filled);
    let expected_maker_fees = btc_maker(s1, price) + btc_maker(s2, price) + btc_maker(s3, price);
    let expected_taker_fees = btc_taker(s1, price) + btc_taker(s2, price) + btc_taker(s3, price);
    let global_fees_collected = api.fees(USD) - fees_before;
    assert_eq!(global_fees_collected, expected_maker_fees + expected_taker_fees, "global fee == sum of per-trade maker+taker fees");
    assert!(global_fees_collected > 0);
    assert!(global_fees_collected < filled * price / 10);
    assert_conserved_usd(&api);
}

#[test]
fn futures_taker_one_to_many_partial_fill_fee_consistency() {
    const M1: i64 = 1;
    const M2: i64 = 2;
    const M3: i64 = 3;
    const TAKER: i64 = 4;
    let (s1, s2, s3) = (6i64, 8i64, 9i64);
    let taker_total = 30i64;
    let (p1, p2, p3) = (45_000i64, 45_100i64, 45_200i64);
    let deposit = 100_000i64;

    let mut api = seed_btc(p1);
    seed_user(&mut api, M1, deposit, 1);
    seed_user(&mut api, M2, deposit, 2);
    seed_user(&mut api, M3, deposit, 3);
    seed_user(&mut api, TAKER, deposit, 4);
    let fees_before = api.fees(USD);

    assert_eq!(place(&mut api, 10001, M1, BTC_SYM, p1, s1, OrderAction::Ask, OrderType::Gtc, MarginMode::Cross, 1), CommandResultCode::Success);
    assert_eq!(place(&mut api, 10002, M2, BTC_SYM, p2, s2, OrderAction::Ask, OrderType::Gtc, MarginMode::Cross, 1), CommandResultCode::Success);
    assert_eq!(place(&mut api, 10003, M3, BTC_SYM, p3, s3, OrderAction::Ask, OrderType::Gtc, MarginMode::Cross, 1), CommandResultCode::Success);
    assert_eq!(place(&mut api, 10004, TAKER, BTC_SYM, p3, taker_total, OrderAction::Bid, OrderType::Gtc, MarginMode::Cross, 1), CommandResultCode::Success);

    let filled = s1 + s2 + s3;
    assert_eq!(api.user_position(TAKER, BTC_SYM).unwrap().open_volume, filled);
    let expected_maker_fees = btc_maker(s1, p1) + btc_maker(s2, p2) + btc_maker(s3, p3);
    let expected_taker_fees = btc_taker(s1, p1) + btc_taker(s2, p2) + btc_taker(s3, p3);
    let global_fees_collected = api.fees(USD) - fees_before;
    assert_eq!(global_fees_collected, expected_maker_fees + expected_taker_fees);
    assert!(global_fees_collected > 0);
    let total_volume = s1 * p1 + s2 * p2 + s3 * p3;
    assert!(global_fees_collected < total_volume / 10);
    assert_conserved_usd(&api);
}

#[test]
fn futures_reverse_opening_fee_calculation() {
    const U1: i64 = 1;
    const U2: i64 = 2;
    const U3: i64 = 3;
    let initial_size = 10i64;
    let reverse_size = 12i64;
    let initial_price = 50_000i64;
    let reverse_price = 52_000i64;
    let deposit = 100_000i64;

    let mut api = seed_btc(initial_price);
    seed_user(&mut api, U1, deposit, 1);
    seed_user(&mut api, U2, deposit, 2);
    seed_user(&mut api, U3, deposit, 3);

    assert_eq!(place(&mut api, 11001, U1, BTC_SYM, initial_price, initial_size, OrderAction::Bid, OrderType::Gtc, MarginMode::Cross, 1), CommandResultCode::Success);
    assert_eq!(place(&mut api, 11002, U2, BTC_SYM, initial_price, initial_size, OrderAction::Ask, OrderType::Gtc, MarginMode::Cross, 1), CommandResultCode::Success);
    let fees_after_open = api.fees(USD);

    assert_eq!(api.set_mark_price(BTC_SYM, reverse_price), CommandResultCode::Success);

    assert_eq!(place(&mut api, 11003, U1, BTC_SYM, reverse_price, reverse_size, OrderAction::Ask, OrderType::Gtc, MarginMode::Cross, 1), CommandResultCode::Success);
    assert_eq!(place(&mut api, 11004, U3, BTC_SYM, reverse_price, reverse_size, OrderAction::Bid, OrderType::Gtc, MarginMode::Cross, 1), CommandResultCode::Success);

    let u1_pos = api.user_position(U1, BTC_SYM).expect("U1 should still hold a position after reversal");
    assert_eq!(u1_pos.direction, PositionDirection::Short);
    assert_eq!(u1_pos.open_volume, reverse_size - initial_size);
    assert_eq!(u1_pos.profit, 20_000, "closing leg's realized PnL accrues but is not paid out (new position is non-empty)");

    assert_eq!(api.user_position(U3, BTC_SYM).unwrap().direction, PositionDirection::Long);
    assert_eq!(api.user_position(U3, BTC_SYM).unwrap().open_volume, reverse_size);

    let expected_taker_fee = btc_taker(reverse_size, reverse_price);
    let expected_maker_fee = btc_maker(reverse_size, reverse_price);
    assert_eq!(api.user_account(U3, USD), deposit - expected_taker_fee);
    assert_eq!(api.user_account(U1, USD), deposit - btc_maker(initial_size, initial_price) - expected_maker_fee);
    let global_fees_collected = api.fees(USD) - fees_after_open;
    assert_eq!(global_fees_collected, expected_maker_fee + expected_taker_fee);
    assert!(global_fees_collected > 0);
    assert_conserved_usd(&api);
}

#[test]
fn hedge_mode_position_opening_fee_events() {
    const USER: i64 = 1;
    const CP1: i64 = 2;
    const CP2: i64 = 3;
    let long_size = 15i64;
    let short_size = 10i64;
    let price = 50_000i64;
    let deposit = 100_000i64;

    let mut api = seed_btc(price);
    seed_user(&mut api, USER, deposit, 1);
    seed_user(&mut api, CP1, deposit, 2);
    seed_user(&mut api, CP2, deposit, 3);

    assert_eq!(api.adjust_position_mode(USER, true), CommandResultCode::Success);

    assert_eq!(place(&mut api, 12001, USER, BTC_SYM, price, long_size, OrderAction::Bid, OrderType::Gtc, MarginMode::Cross, 0), CommandResultCode::Success);
    assert_eq!(place(&mut api, 12002, CP1, BTC_SYM, price, long_size, OrderAction::Ask, OrderType::Gtc, MarginMode::Cross, 0), CommandResultCode::Success);
    assert_eq!(place(&mut api, 12003, USER, BTC_SYM, price, short_size, OrderAction::Ask, OrderType::Gtc, MarginMode::Cross, 0), CommandResultCode::Success);
    assert_eq!(place(&mut api, 12004, CP2, BTC_SYM, price, short_size, OrderAction::Bid, OrderType::Gtc, MarginMode::Cross, 0), CommandResultCode::Success);

    assert_eq!(hedge_leg(&api, USER, BTC_SYM, PositionDirection::Long).unwrap().open_volume, long_size);
    assert_eq!(hedge_leg(&api, USER, BTC_SYM, PositionDirection::Short).unwrap().open_volume, short_size);

    let expected_long_maker = btc_maker(long_size, price);
    let expected_short_maker = btc_maker(short_size, price);
    assert_eq!(api.user_account(USER, USD), deposit - expected_long_maker - expected_short_maker);
    assert_eq!(api.user_account(CP1, USD), deposit - btc_taker(long_size, price));
    assert_eq!(api.user_account(CP2, USD), deposit - btc_taker(short_size, price));
    assert_eq!(api.fees(USD), expected_long_maker + expected_short_maker + btc_taker(long_size, price) + btc_taker(short_size, price));
    assert_conserved_usd(&api);
}

#[test]
fn hedge_mode_partial_closing_fee_events() {
    const USER: i64 = 1;
    const CP1: i64 = 2;
    const CP2: i64 = 3;
    const CP3: i64 = 4;
    let long_size = 20i64;
    let short_size = 15i64;
    let partial_close = 8i64;
    let price = 48_000i64;
    let deposit = 100_000i64;

    let mut api = seed_btc(price);
    seed_user(&mut api, USER, deposit, 1);
    seed_user(&mut api, CP1, deposit, 2);
    seed_user(&mut api, CP2, deposit, 3);
    seed_user(&mut api, CP3, deposit, 4);

    assert_eq!(api.adjust_position_mode(USER, true), CommandResultCode::Success);

    assert_eq!(place(&mut api, 13001, USER, BTC_SYM, price, long_size, OrderAction::Bid, OrderType::Gtc, MarginMode::Cross, 0), CommandResultCode::Success);
    assert_eq!(place(&mut api, 13002, CP1, BTC_SYM, price, long_size, OrderAction::Ask, OrderType::Gtc, MarginMode::Cross, 0), CommandResultCode::Success);
    assert_eq!(place(&mut api, 13003, USER, BTC_SYM, price, short_size, OrderAction::Ask, OrderType::Gtc, MarginMode::Cross, 0), CommandResultCode::Success);
    assert_eq!(place(&mut api, 13004, CP2, BTC_SYM, price, short_size, OrderAction::Bid, OrderType::Gtc, MarginMode::Cross, 0), CommandResultCode::Success);

    assert_eq!(hedge_leg(&api, USER, BTC_SYM, PositionDirection::Long).unwrap().open_volume, long_size);
    assert_eq!(hedge_leg(&api, USER, BTC_SYM, PositionDirection::Short).unwrap().open_volume, short_size);
    let fees_after_open = api.fees(USD);
    let account_after_open = api.user_account(USER, USD);

    assert_eq!(
        api.close_position(ClosePositionRequest {
            order_id: 13005,
            uid: USER,
            symbol: BTC_SYM,
            action: OrderAction::Ask,
            price,
            size: partial_close,
            order_type: OrderType::Gtc,
        }),
        CommandResultCode::Success
    );
    assert_eq!(place(&mut api, 13006, CP3, BTC_SYM, price, partial_close, OrderAction::Bid, OrderType::Gtc, MarginMode::Cross, 0), CommandResultCode::Success);

    assert_eq!(hedge_leg(&api, USER, BTC_SYM, PositionDirection::Long).unwrap().open_volume, long_size - partial_close);
    assert_eq!(hedge_leg(&api, USER, BTC_SYM, PositionDirection::Short).unwrap().open_volume, short_size);

    let expected_close_maker = btc_maker(partial_close, price);
    let expected_close_taker = btc_taker(partial_close, price);
    assert_eq!(api.user_account(USER, USD), account_after_open - expected_close_maker);
    assert_eq!(api.user_account(CP3, USD), deposit - expected_close_taker);
    assert_eq!(api.fees(USD) - fees_after_open, expected_close_maker + expected_close_taker);
    assert_conserved_usd(&api);
}

#[test]
fn hedge_mode_position_reversal_fee_events() {
    const USER: i64 = 1;
    const CP1: i64 = 2;
    const CP2: i64 = 3;
    let initial_long = 12i64;
    let reversal = 18i64;
    let new_short = reversal - initial_long;
    let price = 51_000i64;
    let deposit = 100_000i64;

    let mut api = seed_btc(price);
    seed_user(&mut api, USER, deposit, 1);
    seed_user(&mut api, CP1, deposit, 2);
    seed_user(&mut api, CP2, deposit, 3);

    assert_eq!(api.adjust_position_mode(USER, true), CommandResultCode::Success);

    assert_eq!(place(&mut api, 14001, USER, BTC_SYM, price, initial_long, OrderAction::Bid, OrderType::Gtc, MarginMode::Cross, 0), CommandResultCode::Success);
    assert_eq!(place(&mut api, 14002, CP1, BTC_SYM, price, initial_long, OrderAction::Ask, OrderType::Gtc, MarginMode::Cross, 0), CommandResultCode::Success);
    assert_eq!(hedge_leg(&api, USER, BTC_SYM, PositionDirection::Long).unwrap().open_volume, initial_long);
    let fees_after_open = api.fees(USD);

    assert_eq!(
        api.close_position(ClosePositionRequest {
            order_id: 14003,
            uid: USER,
            symbol: BTC_SYM,
            action: OrderAction::Ask,
            price,
            size: initial_long,
            order_type: OrderType::Gtc,
        }),
        CommandResultCode::Success
    );
    assert_eq!(place(&mut api, 14004, USER, BTC_SYM, price, new_short, OrderAction::Ask, OrderType::Gtc, MarginMode::Cross, 0), CommandResultCode::Success);
    assert_eq!(place(&mut api, 14005, CP2, BTC_SYM, price, reversal, OrderAction::Bid, OrderType::Gtc, MarginMode::Cross, 0), CommandResultCode::Success);

    assert!(hedge_leg(&api, USER, BTC_SYM, PositionDirection::Long).is_none(), "LONG leg should be torn down once fully closed");
    assert_eq!(hedge_leg(&api, USER, BTC_SYM, PositionDirection::Short).unwrap().open_volume, new_short);

    let expected_close_maker = btc_maker(initial_long, price);
    let expected_short_maker = btc_maker(new_short, price);
    assert_eq!(api.user_account(USER, USD), deposit - btc_maker(initial_long, price) - expected_close_maker - expected_short_maker);
    assert_eq!(api.user_account(CP2, USD), deposit - btc_taker(reversal, price));
    let global_delta = api.fees(USD) - fees_after_open;
    assert_eq!(global_delta, expected_close_maker + expected_short_maker + btc_taker(reversal, price));
    assert!(global_delta > 0);
    assert_conserved_usd(&api);
}

#[test]
fn hedge_mode_mixed_order_types_fee_events() {
    const USER: i64 = 1;
    const CP1: i64 = 2;
    const CP2: i64 = 3;
    const CP3: i64 = 4;
    let long_size = 10i64;
    let short_size = 8i64;
    let ioc_size = 5i64;
    let price = 49_000i64;
    let deposit = 100_000i64;

    let mut api = seed_btc(price);
    seed_user(&mut api, USER, deposit, 1);
    seed_user(&mut api, CP1, deposit, 2);
    seed_user(&mut api, CP2, deposit, 3);
    seed_user(&mut api, CP3, deposit, 4);

    assert_eq!(api.adjust_position_mode(USER, true), CommandResultCode::Success);

    assert_eq!(place(&mut api, 15001, USER, BTC_SYM, price, long_size, OrderAction::Bid, OrderType::Gtc, MarginMode::Cross, 0), CommandResultCode::Success);
    assert_eq!(place(&mut api, 15002, CP1, BTC_SYM, price, long_size, OrderAction::Ask, OrderType::Gtc, MarginMode::Cross, 0), CommandResultCode::Success);
    assert_eq!(place(&mut api, 15003, USER, BTC_SYM, price, short_size, OrderAction::Ask, OrderType::Gtc, MarginMode::Cross, 0), CommandResultCode::Success);
    assert_eq!(place(&mut api, 15004, CP2, BTC_SYM, price, short_size, OrderAction::Bid, OrderType::Gtc, MarginMode::Cross, 0), CommandResultCode::Success);

    let fees_after_gtc = api.fees(USD);

    assert_eq!(place(&mut api, 15005, USER, BTC_SYM, price, ioc_size, OrderAction::Bid, OrderType::Ioc, MarginMode::Cross, 0), CommandResultCode::Success);
    assert_eq!(place(&mut api, 15006, CP3, BTC_SYM, price, ioc_size, OrderAction::Ask, OrderType::Gtc, MarginMode::Cross, 0), CommandResultCode::Success);

    assert_eq!(hedge_leg(&api, USER, BTC_SYM, PositionDirection::Long).unwrap().open_volume, long_size);
    assert_eq!(hedge_leg(&api, USER, BTC_SYM, PositionDirection::Short).unwrap().open_volume, short_size);
    assert_eq!(api.fees(USD), fees_after_gtc);
    assert_eq!(api.user_account(USER, USD), deposit - btc_maker(long_size, price) - btc_maker(short_size, price));
    assert_eq!(api.fees(USD), btc_maker(long_size, price) + btc_maker(short_size, price) + btc_taker(long_size, price) + btc_taker(short_size, price));
    assert_conserved_usd(&api);
}

#[test]
fn hedge_mode_fee_consistency_with_global_balance() {
    const USER: i64 = 1;
    const CP1: i64 = 2;
    const CP2: i64 = 3;
    let long_size = 25i64;
    let short_size = 20i64;
    let price = 47_000i64;
    let deposit = 100_000i64;

    let mut api = seed_btc(price);
    seed_user(&mut api, USER, deposit, 1);
    seed_user(&mut api, CP1, deposit, 2);
    seed_user(&mut api, CP2, deposit, 3);

    let initial_fees = api.fees(USD);
    assert_eq!(api.adjust_position_mode(USER, true), CommandResultCode::Success);

    assert_eq!(place(&mut api, 16001, USER, BTC_SYM, price, long_size, OrderAction::Bid, OrderType::Gtc, MarginMode::Cross, 0), CommandResultCode::Success);
    assert_eq!(place(&mut api, 16002, CP1, BTC_SYM, price, long_size, OrderAction::Ask, OrderType::Gtc, MarginMode::Cross, 0), CommandResultCode::Success);
    assert_eq!(place(&mut api, 16003, USER, BTC_SYM, price, short_size, OrderAction::Ask, OrderType::Gtc, MarginMode::Cross, 0), CommandResultCode::Success);
    assert_eq!(place(&mut api, 16004, CP2, BTC_SYM, price, short_size, OrderAction::Bid, OrderType::Gtc, MarginMode::Cross, 0), CommandResultCode::Success);

    let global_fees_collected = api.fees(USD) - initial_fees;
    let expected = btc_maker(long_size, price) + btc_taker(long_size, price)
        + btc_maker(short_size, price) + btc_taker(short_size, price);
    assert_eq!(global_fees_collected, expected, "global fees == sum of the four maker+taker fees");
    assert!(global_fees_collected > 0);
    assert!(api.total_balance().is_global_zero(), "global conservation holds after HEDGE two-sided opening");
    assert_conserved_usd(&api);
}

fn run_hedge_full_lifecycle(taker_type: OrderType) {
    const MAKER: i64 = 9401;
    const TAKER: i64 = 9402;
    let size = 4i64;
    let price = 50_000i64;
    let deposit = 100_000_000i64;

    let mut api = seed_btc(price);
    seed_user(&mut api, MAKER, deposit, 1);
    seed_user(&mut api, TAKER, deposit, 2);
    assert_conserved_usd(&api);

    assert_eq!(api.adjust_position_mode(TAKER, true), CommandResultCode::Success);

    let taker_price = match taker_type {
        OrderType::FokBudget | OrderType::IocBudget => size * price,
        _ => price,
    };
    let ask_taker_type = if taker_type == OrderType::IocBudget { OrderType::Gtc } else { taker_type };
    let ask_taker_price = match ask_taker_type {
        OrderType::FokBudget | OrderType::IocBudget => size * price,
        _ => price,
    };

    assert_eq!(place(&mut api, 9501, MAKER, BTC_SYM, price, size, OrderAction::Ask, OrderType::Gtc, MarginMode::Cross, 0), CommandResultCode::Success);
    assert_eq!(place(&mut api, 9502, TAKER, BTC_SYM, taker_price, size, OrderAction::Bid, taker_type, MarginMode::Cross, 0), CommandResultCode::Success);
    assert_eq!(place(&mut api, 9503, MAKER, BTC_SYM, price, size, OrderAction::Bid, OrderType::Gtc, MarginMode::Cross, 0), CommandResultCode::Success);
    assert_eq!(place(&mut api, 9504, TAKER, BTC_SYM, ask_taker_price, size, OrderAction::Ask, ask_taker_type, MarginMode::Cross, 0), CommandResultCode::Success);

    assert_eq!(hedge_leg(&api, TAKER, BTC_SYM, PositionDirection::Long).unwrap().open_volume, size, "[{taker_type:?}] taker LONG openVolume");
    assert_eq!(hedge_leg(&api, TAKER, BTC_SYM, PositionDirection::Short).unwrap().open_volume, size, "[{taker_type:?}] taker SHORT openVolume");
    assert_conserved_usd(&api);

    assert_eq!(api.close_position(ClosePositionRequest { order_id: 9505, uid: TAKER, symbol: BTC_SYM, action: OrderAction::Ask, price, size, order_type: OrderType::Gtc }), CommandResultCode::Success);
    assert_eq!(place(&mut api, 9506, MAKER, BTC_SYM, price, size, OrderAction::Bid, OrderType::Gtc, MarginMode::Cross, 0), CommandResultCode::Success);
    assert_eq!(api.close_position(ClosePositionRequest { order_id: 9507, uid: TAKER, symbol: BTC_SYM, action: OrderAction::Bid, price, size, order_type: OrderType::Gtc }), CommandResultCode::Success);
    assert_eq!(place(&mut api, 9508, MAKER, BTC_SYM, price, size, OrderAction::Ask, OrderType::Gtc, MarginMode::Cross, 0), CommandResultCode::Success);

    assert_eq!(hedge_open_volume_sum(&api, TAKER, BTC_SYM), 0, "[{taker_type:?}] openVolume sums to 0 after closing");
    assert_conserved_usd(&api);

    let maker_bal = api.user_account(MAKER, USD);
    let taker_bal = api.user_account(TAKER, USD);
    if maker_bal != 0 { assert_eq!(api.balance_adjustment(MAKER, USD, -maker_bal, 3), CommandResultCode::Success); }
    if taker_bal != 0 { assert_eq!(api.balance_adjustment(TAKER, USD, -taker_bal, 4), CommandResultCode::Success); }

    assert_eq!(api.user_account(MAKER, USD), 0, "[{taker_type:?}] maker account");
    assert_eq!(api.user_account(TAKER, USD), 0, "[{taker_type:?}] taker account");
    assert_eq!(api.adjustments(USD) + api.fees(USD), 0, "[{taker_type:?}] adjustments + fees == 0");
    assert_conserved_usd(&api);
}

#[test]
fn futures_hedge_full_lifecycle_with_deposit_withdraw() {
    run_hedge_full_lifecycle(OrderType::Gtc);
    run_hedge_full_lifecycle(OrderType::Ioc);
    run_hedge_full_lifecycle(OrderType::FokBudget);
    run_hedge_full_lifecycle(OrderType::IocBudget);
}

fn run_isolated_hedge_full_lifecycle(taker_type: OrderType) {
    const MAKER: i64 = 9601;
    const TAKER: i64 = 9602;
    let size = 4i64;
    let price = 50_000i64;
    let leverage = 10i32;
    let deposit = 100_000_000i64;

    let mut api = seed_btc(price);
    seed_user(&mut api, MAKER, deposit, 1);
    seed_user(&mut api, TAKER, deposit, 2);
    assert_conserved_usd(&api);

    assert_eq!(api.adjust_position_mode(TAKER, true), CommandResultCode::Success);

    let taker_price = match taker_type {
        OrderType::FokBudget | OrderType::IocBudget => size * price,
        _ => price,
    };
    let ask_taker_type = if taker_type == OrderType::IocBudget { OrderType::Gtc } else { taker_type };
    let ask_taker_price = match ask_taker_type {
        OrderType::FokBudget | OrderType::IocBudget => size * price,
        _ => price,
    };

    assert_eq!(place(&mut api, 9701, MAKER, BTC_SYM, price, size, OrderAction::Ask, OrderType::Gtc, MarginMode::Isolated, leverage), CommandResultCode::Success);
    assert_eq!(place(&mut api, 9702, TAKER, BTC_SYM, taker_price, size, OrderAction::Bid, taker_type, MarginMode::Isolated, leverage), CommandResultCode::Success);
    assert_eq!(place(&mut api, 9703, MAKER, BTC_SYM, price, size, OrderAction::Bid, OrderType::Gtc, MarginMode::Isolated, leverage), CommandResultCode::Success);
    assert_eq!(place(&mut api, 9704, TAKER, BTC_SYM, ask_taker_price, size, OrderAction::Ask, ask_taker_type, MarginMode::Isolated, leverage), CommandResultCode::Success);

    let long_leg = hedge_leg(&api, TAKER, BTC_SYM, PositionDirection::Long).expect("LONG leg");
    assert_eq!(long_leg.open_volume, size, "[{taker_type:?}] LONG openVolume");
    assert_eq!(long_leg.margin_mode, MarginMode::Isolated, "[{taker_type:?}] LONG marginMode");
    assert!(long_leg.open_init_margin_sum > 0, "[{taker_type:?}] LONG openInitMarginSum > 0");
    let short_leg = hedge_leg(&api, TAKER, BTC_SYM, PositionDirection::Short).expect("SHORT leg");
    assert_eq!(short_leg.open_volume, size, "[{taker_type:?}] SHORT openVolume");
    assert_eq!(short_leg.margin_mode, MarginMode::Isolated, "[{taker_type:?}] SHORT marginMode");
    assert!(short_leg.open_init_margin_sum > 0, "[{taker_type:?}] SHORT openInitMarginSum > 0");
    assert_conserved_usd(&api);

    assert_eq!(api.close_position(ClosePositionRequest { order_id: 9705, uid: TAKER, symbol: BTC_SYM, action: OrderAction::Ask, price, size, order_type: OrderType::Gtc }), CommandResultCode::Success);
    assert_eq!(place(&mut api, 9706, MAKER, BTC_SYM, price, size, OrderAction::Bid, OrderType::Gtc, MarginMode::Isolated, leverage), CommandResultCode::Success);
    assert_eq!(api.close_position(ClosePositionRequest { order_id: 9707, uid: TAKER, symbol: BTC_SYM, action: OrderAction::Bid, price, size, order_type: OrderType::Gtc }), CommandResultCode::Success);
    assert_eq!(place(&mut api, 9708, MAKER, BTC_SYM, price, size, OrderAction::Ask, OrderType::Gtc, MarginMode::Isolated, leverage), CommandResultCode::Success);

    assert_eq!(hedge_open_volume_sum(&api, TAKER, BTC_SYM), 0, "[{taker_type:?}] openVolume sums to 0 after closing");
    assert_conserved_usd(&api);

    let maker_bal = api.user_account(MAKER, USD);
    let taker_bal = api.user_account(TAKER, USD);
    if maker_bal != 0 { assert_eq!(api.balance_adjustment(MAKER, USD, -maker_bal, 3), CommandResultCode::Success); }
    if taker_bal != 0 { assert_eq!(api.balance_adjustment(TAKER, USD, -taker_bal, 4), CommandResultCode::Success); }

    assert_eq!(api.user_account(MAKER, USD), 0, "[{taker_type:?}] maker account");
    assert_eq!(api.user_account(TAKER, USD), 0, "[{taker_type:?}] taker account");
    assert_eq!(api.adjustments(USD) + api.fees(USD), 0, "[{taker_type:?}] adjustments + fees == 0");
    assert_conserved_usd(&api);
}

#[test]
fn futures_isolated_hedge_full_lifecycle_with_deposit_withdraw() {
    run_isolated_hedge_full_lifecycle(OrderType::Gtc);
    run_isolated_hedge_full_lifecycle(OrderType::Ioc);
    run_isolated_hedge_full_lifecycle(OrderType::FokBudget);
    run_isolated_hedge_full_lifecycle(OrderType::IocBudget);
}

#[test]
fn futures_liquidation_full_lifecycle_conservation() {
    const VICTIM: i64 = 9801;
    const LP: i64 = 9802;
    const ACCEPTOR: i64 = 9803;
    let victim_deposit = 10_000i64;
    let lp_deposit = 100_000_000i64;
    let acceptor_deposit = 100_000_000i64;
    let open_price = 50_000i64;
    let crash_price = 25_000i64;
    let size = 4i64;
    let leverage = 10i32;

    let mut api = seed_btc(open_price);
    seed_user(&mut api, VICTIM, victim_deposit, 1);
    seed_user(&mut api, LP, lp_deposit, 2);
    seed_user(&mut api, ACCEPTOR, acceptor_deposit, 3);
    assert_conserved_usd(&api);

    assert_eq!(place(&mut api, 9901, LP, BTC_SYM, open_price, size, OrderAction::Ask, OrderType::Gtc, MarginMode::Cross, leverage), CommandResultCode::Success);
    assert_eq!(place(&mut api, 9902, VICTIM, BTC_SYM, open_price, size, OrderAction::Bid, OrderType::Gtc, MarginMode::Isolated, leverage), CommandResultCode::Success);
    assert_eq!(api.user_position(VICTIM, BTC_SYM).expect("victim LONG must exist").open_volume, size);
    assert_conserved_usd(&api);

    assert_eq!(place(&mut api, 9903, ACCEPTOR, BTC_SYM, crash_price, size * 2, OrderAction::Bid, OrderType::Gtc, MarginMode::Cross, leverage), CommandResultCode::Success);

    assert_eq!(api.set_mark_price(BTC_SYM, crash_price), CommandResultCode::Success);
    api.enable_liquidation();
    assert_eq!(api.submit_liquidation_scan(LiquidationScanRequest { scan_slice: 0, slice_count: 1, timestamp: 1 }), CommandResultCode::Success);

    let victim_vol = api.user_position(VICTIM, BTC_SYM).map(|p| p.open_volume).unwrap_or(0);
    assert_eq!(victim_vol, 0, "victim LONG must be fully liquidated");
    assert_conserved_usd(&api);

    let victim_bal = api.user_account(VICTIM, USD);
    if victim_bal != 0 {
        assert_eq!(api.balance_adjustment(VICTIM, USD, -victim_bal, 4), CommandResultCode::Success);
    }
    assert_eq!(api.user_account(VICTIM, USD), 0, "victim account should be zero after withdraw");
    assert_conserved_usd(&api);
}

#[test]
fn futures_hedge_liquidation_full_lifecycle_conservation() {
    const VICTIM: i64 = 9901;
    const LP: i64 = 9902;
    let victim_deposit = 100_000i64;
    let lp_deposit = 100_000_000i64;
    let open_price = 50_000i64;
    let crash_price = 25_000i64;
    let bp_fill_price = 49_970i64;
    let size = 4i64;
    let leverage = 10i32;

    let mut api = seed_btc(open_price);
    seed_user(&mut api, VICTIM, victim_deposit, 1);
    seed_user(&mut api, LP, lp_deposit, 2);
    assert_conserved_usd(&api);

    assert_eq!(api.adjust_position_mode(VICTIM, true), CommandResultCode::Success);

    assert_eq!(place(&mut api, 10001, LP, BTC_SYM, open_price, size, OrderAction::Ask, OrderType::Gtc, MarginMode::Cross, leverage), CommandResultCode::Success);
    assert_eq!(place(&mut api, 10002, VICTIM, BTC_SYM, open_price, size, OrderAction::Bid, OrderType::Gtc, MarginMode::Isolated, leverage), CommandResultCode::Success);

    assert_eq!(place(&mut api, 10003, LP, BTC_SYM, open_price, size, OrderAction::Bid, OrderType::Gtc, MarginMode::Cross, leverage), CommandResultCode::Success);
    assert_eq!(place(&mut api, 10004, VICTIM, BTC_SYM, open_price, size, OrderAction::Ask, OrderType::Gtc, MarginMode::Isolated, leverage), CommandResultCode::Success);

    assert_eq!(hedge_leg(&api, VICTIM, BTC_SYM, PositionDirection::Long).expect("LONG leg").open_volume, size);
    assert_eq!(hedge_leg(&api, VICTIM, BTC_SYM, PositionDirection::Short).expect("SHORT leg").open_volume, size);
    assert_conserved_usd(&api);

    assert_eq!(api.set_mark_price(BTC_SYM, crash_price), CommandResultCode::Success);
    assert_eq!(place(&mut api, 10005, LP, BTC_SYM, bp_fill_price, size, OrderAction::Bid, OrderType::Gtc, MarginMode::Cross, 1), CommandResultCode::Success);
    api.enable_liquidation();
    assert_eq!(api.submit_liquidation_scan(LiquidationScanRequest { scan_slice: 0, slice_count: 1, timestamp: 1 }), CommandResultCode::Success);

    let long_vol = hedge_leg(&api, VICTIM, BTC_SYM, PositionDirection::Long).map(|p| p.open_volume).unwrap_or(0);
    assert_eq!(long_vol, 0, "LONG leg must be liquidated");
    assert_eq!(
        hedge_leg(&api, VICTIM, BTC_SYM, PositionDirection::Short).expect("SHORT leg preserved").open_volume,
        size,
        "SHORT leg is profitable during the crash and must survive"
    );

    assert_conserved_usd(&api);
}
}
