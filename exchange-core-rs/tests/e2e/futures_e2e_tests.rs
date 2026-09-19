use proptest::prelude::*;

use exchange_core_rs::core::common::cmd::command_result_code::CommandResultCode;
use exchange_core_rs::core::common::core_symbol_specification::CoreSymbolSpecification;
use exchange_core_rs::core::common::margin_mode::MarginMode;
use exchange_core_rs::core::common::order_action::OrderAction;
use exchange_core_rs::core::common::order_type::OrderType;
use exchange_core_rs::core::common::position_direction::PositionDirection;
use exchange_core_rs::core::common::symbol_type::SymbolType;

use exchange_core_rs::core::exchange_api::{
    ClosePositionRequest, ExchangeApi, MarginAdjustmentRequest, PlaceFuturesOrderRequest,
};

const BASE: i32 = 1;
const QUOTE: i32 = 2;
const FUT_SYMBOL: i32 = 300;

fn assert_futures_conservation(api: &ExchangeApi) {
    for &cur in api.ssp().currencies.keys() {
        let mut total: i64 = api.ups().users.values().map(|p| p.account(cur)).sum();
        total += api.adjustments(cur);
        total += api.fees(cur);
        for p in api.ups().users.values() {
            for pos in p.positions.values() {
                if pos.currency != cur {
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
        assert_eq!(total, 0, "futures global conservation violated: currency={cur} total={total}");
    }
}

fn assert_accounts_non_negative(api: &ExchangeApi) {
    for p in api.ups().users.values() {
        for (&cur, &bal) in &p.accounts {
            assert!(bal >= 0, "user {}'s accounts[{cur}] is negative: {bal}", p.uid);
        }
    }
}

fn assert_positions_non_negative(api: &ExchangeApi) {
    for p in api.ups().users.values() {
        for pos in p.positions.values() {
            assert!(
                pos.open_volume >= 0,
                "user {} symbol {}'s open_volume is negative: {}",
                p.uid,
                pos.symbol,
                pos.open_volume
            );
            assert!(
                pos.open_init_margin_sum >= 0,
                "user {} symbol {}'s open_init_margin_sum is negative: {}",
                p.uid,
                pos.symbol,
                pos.open_init_margin_sum
            );
        }
    }
}

fn assert_futures_invariants(api: &ExchangeApi) {
    assert_futures_conservation(api);
    assert_accounts_non_negative(api);
    assert_positions_non_negative(api);
}

fn futures_spec_fixed_fee(taker_fee: i64, maker_fee: i64) -> CoreSymbolSpecification {
    CoreSymbolSpecification {
        symbol_id: FUT_SYMBOL,
        symbol_type: SymbolType::FuturesContractPerpetual,
        base_currency: BASE,
        quote_currency: QUOTE,
        base_scale_k: 1,
        quote_scale_k: 1,
        taker_fee,
        maker_fee,
        fee_scale_k: 0,
        ..Default::default()
    }
}

fn futures_spec_proportional_fee(taker_fee: i64, maker_fee: i64, fee_scale_k: i64) -> CoreSymbolSpecification {
    CoreSymbolSpecification {
        symbol_id: FUT_SYMBOL,
        symbol_type: SymbolType::FuturesContractPerpetual,
        base_currency: BASE,
        quote_currency: QUOTE,
        base_scale_k: 1,
        quote_scale_k: 1,
        taker_fee,
        maker_fee,
        fee_scale_k,
        ..Default::default()
    }
}

fn new_seeded_futures_api(spec: CoreSymbolSpecification) -> ExchangeApi {
    let mut api = ExchangeApi::new();
    api.add_currency(BASE, 1);
    api.add_currency(QUOTE, 1);
    assert_eq!(api.add_futures_symbol(spec), CommandResultCode::Success);
    api
}

fn naive_conservation(api: &ExchangeApi, currency: i32) -> i64 {
    let user_sum: i64 = api.ups().users.values().map(|p| p.account(currency)).sum();
    user_sum + api.adjustments(currency) + api.fees(currency)
}

#[test]
fn scenario_a_long_short_cross_then_mutual_close_settles_pnl() {
    const LONG_USER: i64 = 1;
    const SHORT_USER: i64 = 2;

    let mut api = new_seeded_futures_api(futures_spec_fixed_fee(10, 5));
    assert_eq!(api.add_user(LONG_USER), CommandResultCode::Success);
    assert_eq!(api.add_user(SHORT_USER), CommandResultCode::Success);
    assert_futures_invariants(&api);

    assert_eq!(api.balance_adjustment(LONG_USER, QUOTE, 100_000, 1), CommandResultCode::Success);
    assert_futures_invariants(&api);
    assert_eq!(api.balance_adjustment(SHORT_USER, QUOTE, 100_000, 2), CommandResultCode::Success);
    assert_futures_invariants(&api);

    assert_eq!(api.set_mark_price(FUT_SYMBOL, 100), CommandResultCode::Success);
    assert_futures_invariants(&api);

    assert_eq!(
        api.place_futures_order(PlaceFuturesOrderRequest {
            order_id: 1, uid: SHORT_USER, symbol: FUT_SYMBOL, price: 100, size: 6,
            action: OrderAction::Ask, order_type: OrderType::Gtc, leverage: 1,
            margin_mode: MarginMode::Isolated, reduce_only: false,
        }),
        CommandResultCode::Success
    );
    assert_futures_invariants(&api);
    assert_eq!(naive_conservation(&api, QUOTE), 0);

    assert_eq!(
        api.place_futures_order(PlaceFuturesOrderRequest {
            order_id: 2, uid: LONG_USER, symbol: FUT_SYMBOL, price: 100, size: 6,
            action: OrderAction::Bid, order_type: OrderType::Gtc, leverage: 1,
            margin_mode: MarginMode::Isolated, reduce_only: false,
        }),
        CommandResultCode::Success
    );
    assert_futures_invariants(&api);
    assert_eq!(naive_conservation(&api, QUOTE), 0);

    let long_pos = api.user_position(LONG_USER, FUT_SYMBOL).expect("long position must exist after opening");
    assert_eq!(long_pos.direction, PositionDirection::Long);
    assert_eq!(long_pos.open_volume, 6);
    let short_pos = api.user_position(SHORT_USER, FUT_SYMBOL).expect("short position must exist after opening");
    assert_eq!(short_pos.direction, PositionDirection::Short);
    assert_eq!(short_pos.open_volume, 6);

    assert_eq!(api.set_mark_price(FUT_SYMBOL, 150), CommandResultCode::Success);
    assert_futures_invariants(&api);
    assert_eq!(naive_conservation(&api, QUOTE), 0);

    assert_eq!(
        api.close_position(ClosePositionRequest {
            order_id: 3, uid: SHORT_USER, symbol: FUT_SYMBOL, action: OrderAction::Bid,
            price: 150, size: 6, order_type: OrderType::Gtc,
        }),
        CommandResultCode::Success
    );
    assert_futures_invariants(&api);
    assert_eq!(naive_conservation(&api, QUOTE), 0);

    assert_eq!(
        api.close_position(ClosePositionRequest {
            order_id: 4, uid: LONG_USER, symbol: FUT_SYMBOL, action: OrderAction::Ask,
            price: 150, size: 6, order_type: OrderType::Gtc,
        }),
        CommandResultCode::Success
    );
    assert_futures_invariants(&api);
    assert_eq!(naive_conservation(&api, QUOTE), 0, "same counterparty trading against each other throughout, naive formula should hold exactly");

    assert!(api.user_position(LONG_USER, FUT_SYMBOL).is_none(), "position record should be torn down after long fully closes");
    assert!(api.user_position(SHORT_USER, FUT_SYMBOL).is_none(), "position record should be torn down after short fully closes");
    assert!(api.user_account(LONG_USER, QUOTE) > 100_000 - 100, "long should realize positive PnL (mark went up)");
    assert!(api.user_account(SHORT_USER, QUOTE) < 100_000, "short should realize negative PnL");
}

#[test]
fn scenario_b_increase_then_partial_reduce_then_full_close() {
    const TRADER: i64 = 1;
    const COUNTER: i64 = 2;

    let mut api = new_seeded_futures_api(futures_spec_fixed_fee(10, 5));
    assert_eq!(api.add_user(TRADER), CommandResultCode::Success);
    assert_eq!(api.add_user(COUNTER), CommandResultCode::Success);
    assert_eq!(api.balance_adjustment(TRADER, QUOTE, 100_000, 1), CommandResultCode::Success);
    assert_eq!(api.balance_adjustment(COUNTER, QUOTE, 100_000, 2), CommandResultCode::Success);
    assert_eq!(api.set_mark_price(FUT_SYMBOL, 100), CommandResultCode::Success);
    assert_futures_invariants(&api);

    assert_eq!(
        api.place_futures_order(PlaceFuturesOrderRequest {
            order_id: 1, uid: COUNTER, symbol: FUT_SYMBOL, price: 100, size: 5,
            action: OrderAction::Ask, order_type: OrderType::Gtc, leverage: 1,
            margin_mode: MarginMode::Isolated, reduce_only: false,
        }),
        CommandResultCode::Success
    );
    assert_futures_invariants(&api);
    assert_eq!(
        api.place_futures_order(PlaceFuturesOrderRequest {
            order_id: 2, uid: TRADER, symbol: FUT_SYMBOL, price: 100, size: 5,
            action: OrderAction::Bid, order_type: OrderType::Gtc, leverage: 1,
            margin_mode: MarginMode::Isolated, reduce_only: false,
        }),
        CommandResultCode::Success
    );
    assert_futures_invariants(&api);
    assert_eq!(naive_conservation(&api, QUOTE), 0);

    assert_eq!(
        api.place_futures_order(PlaceFuturesOrderRequest {
            order_id: 3, uid: COUNTER, symbol: FUT_SYMBOL, price: 110, size: 5,
            action: OrderAction::Ask, order_type: OrderType::Gtc, leverage: 1,
            margin_mode: MarginMode::Isolated, reduce_only: false,
        }),
        CommandResultCode::Success
    );
    assert_futures_invariants(&api);
    assert_eq!(
        api.place_futures_order(PlaceFuturesOrderRequest {
            order_id: 4, uid: TRADER, symbol: FUT_SYMBOL, price: 110, size: 5,
            action: OrderAction::Bid, order_type: OrderType::Gtc, leverage: 1,
            margin_mode: MarginMode::Isolated, reduce_only: false,
        }),
        CommandResultCode::Success
    );
    assert_futures_invariants(&api);
    assert_eq!(naive_conservation(&api, QUOTE), 0);

    let trader_pos = api.user_position(TRADER, FUT_SYMBOL).expect("still holding position after adding");
    assert_eq!(trader_pos.open_volume, 10, "5+5=10, same-direction adds accumulate");
    assert_eq!(trader_pos.open_price_sum, 1_050, "500(5*100)+550(5*110)");

    assert_eq!(api.set_mark_price(FUT_SYMBOL, 130), CommandResultCode::Success);
    assert_futures_invariants(&api);
    assert_eq!(naive_conservation(&api, QUOTE), 0);

    assert_eq!(
        api.close_position(ClosePositionRequest {
            order_id: 5, uid: COUNTER, symbol: FUT_SYMBOL, action: OrderAction::Bid,
            price: 130, size: 4, order_type: OrderType::Gtc,
        }),
        CommandResultCode::Success
    );
    assert_futures_invariants(&api);
    assert_eq!(
        api.close_position(ClosePositionRequest {
            order_id: 6, uid: TRADER, symbol: FUT_SYMBOL, action: OrderAction::Ask,
            price: 130, size: 4, order_type: OrderType::Gtc,
        }),
        CommandResultCode::Success
    );
    assert_futures_invariants(&api);
    assert_eq!(naive_conservation(&api, QUOTE), 0);

    let trader_pos = api.user_position(TRADER, FUT_SYMBOL).expect("still non-empty after partial reduce");
    assert_eq!(trader_pos.open_volume, 6, "10-4=6");
    assert_eq!(trader_pos.profit, 0, "partial close does not realize PnL (deferred into remaining cost basis)");

    assert_eq!(api.set_mark_price(FUT_SYMBOL, 140), CommandResultCode::Success);
    assert_futures_invariants(&api);

    assert_eq!(
        api.close_position(ClosePositionRequest {
            order_id: 7, uid: COUNTER, symbol: FUT_SYMBOL, action: OrderAction::Bid,
            price: 140, size: 6, order_type: OrderType::Gtc,
        }),
        CommandResultCode::Success
    );
    assert_futures_invariants(&api);
    assert_eq!(
        api.close_position(ClosePositionRequest {
            order_id: 8, uid: TRADER, symbol: FUT_SYMBOL, action: OrderAction::Ask,
            price: 140, size: 6, order_type: OrderType::Gtc,
        }),
        CommandResultCode::Success
    );
    assert_futures_invariants(&api);
    assert_eq!(naive_conservation(&api, QUOTE), 0, "trading against each other throughout, naive formula holds exactly throughout");

    assert!(api.user_position(TRADER, FUT_SYMBOL).is_none());
    assert!(api.user_position(COUNTER, FUT_SYMBOL).is_none());

    assert_eq!(api.user_account(TRADER, QUOTE), 100_000 - 200 + 310);
    assert_eq!(api.user_account(COUNTER, QUOTE), 100_000 - 100 - 310);
}

#[test]
fn scenario_c_flip_via_oversized_opposite_order_defers_then_pays_profit() {
    const FLIPPER: i64 = 1;
    const COUNTER: i64 = 2;

    let mut api = new_seeded_futures_api(futures_spec_fixed_fee(10, 5));
    assert_eq!(api.add_user(FLIPPER), CommandResultCode::Success);
    assert_eq!(api.add_user(COUNTER), CommandResultCode::Success);
    assert_eq!(api.balance_adjustment(FLIPPER, QUOTE, 100_000, 1), CommandResultCode::Success);
    assert_eq!(api.balance_adjustment(COUNTER, QUOTE, 100_000, 2), CommandResultCode::Success);
    assert_eq!(api.set_mark_price(FUT_SYMBOL, 100), CommandResultCode::Success);
    assert_futures_invariants(&api);

    assert_eq!(
        api.place_futures_order(PlaceFuturesOrderRequest {
            order_id: 1, uid: COUNTER, symbol: FUT_SYMBOL, price: 100, size: 10,
            action: OrderAction::Ask, order_type: OrderType::Gtc, leverage: 1,
            margin_mode: MarginMode::Isolated, reduce_only: false,
        }),
        CommandResultCode::Success
    );
    assert_futures_invariants(&api);
    assert_eq!(
        api.place_futures_order(PlaceFuturesOrderRequest {
            order_id: 2, uid: FLIPPER, symbol: FUT_SYMBOL, price: 100, size: 10,
            action: OrderAction::Bid, order_type: OrderType::Gtc, leverage: 1,
            margin_mode: MarginMode::Isolated, reduce_only: false,
        }),
        CommandResultCode::Success
    );
    assert_futures_invariants(&api);
    assert_eq!(naive_conservation(&api, QUOTE), 0);

    assert_eq!(api.set_mark_price(FUT_SYMBOL, 120), CommandResultCode::Success);
    assert_futures_invariants(&api);

    assert_eq!(
        api.place_futures_order(PlaceFuturesOrderRequest {
            order_id: 3, uid: COUNTER, symbol: FUT_SYMBOL, price: 120, size: 15,
            action: OrderAction::Bid, order_type: OrderType::Gtc, leverage: 1,
            margin_mode: MarginMode::Isolated, reduce_only: false,
        }),
        CommandResultCode::Success
    );
    assert_futures_invariants(&api);
    assert_eq!(
        api.place_futures_order(PlaceFuturesOrderRequest {
            order_id: 4, uid: FLIPPER, symbol: FUT_SYMBOL, price: 120, size: 15,
            action: OrderAction::Ask, order_type: OrderType::Gtc, leverage: 1,
            margin_mode: MarginMode::Isolated, reduce_only: false,
        }),
        CommandResultCode::Success
    );
    assert_futures_invariants(&api);
    assert_eq!(naive_conservation(&api, QUOTE), 0, "both sides symmetrically flip in the same fill, naive formula still holds exactly");

    let flipper_pos = api.user_position(FLIPPER, FUT_SYMBOL).expect("new-direction position must be non-empty after flip");
    assert_eq!(flipper_pos.direction, PositionDirection::Short, "long flips to short");
    assert_eq!(flipper_pos.open_volume, 5);
    assert_eq!(flipper_pos.profit, 200, "realized PnL from the closing leg accrues but is not yet paid out (new position non-empty)");

    let counter_pos = api.user_position(COUNTER, FUT_SYMBOL).expect("new-direction position must be non-empty after flip");
    assert_eq!(counter_pos.direction, PositionDirection::Long, "short flips to long");
    assert_eq!(counter_pos.open_volume, 5);
    assert_eq!(counter_pos.profit, -200, "symmetric loss on the other side, likewise deferred and unpaid");

    assert_eq!(api.set_mark_price(FUT_SYMBOL, 90), CommandResultCode::Success);
    assert_futures_invariants(&api);

    assert_eq!(
        api.close_position(ClosePositionRequest {
            order_id: 5, uid: COUNTER, symbol: FUT_SYMBOL, action: OrderAction::Ask,
            price: 90, size: 5, order_type: OrderType::Gtc,
        }),
        CommandResultCode::Success
    );
    assert_futures_invariants(&api);
    assert_eq!(
        api.close_position(ClosePositionRequest {
            order_id: 6, uid: FLIPPER, symbol: FUT_SYMBOL, action: OrderAction::Bid,
            price: 90, size: 5, order_type: OrderType::Gtc,
        }),
        CommandResultCode::Success
    );
    assert_futures_invariants(&api);
    assert_eq!(naive_conservation(&api, QUOTE), 0);

    assert!(api.user_position(FLIPPER, FUT_SYMBOL).is_none(), "record torn down after final full close");
    assert!(api.user_position(COUNTER, FUT_SYMBOL).is_none());
    assert!(api.user_account(FLIPPER, QUOTE) > 100_000, "net profit is positive (earned 200 then another 150, still positive after fees)");
    assert!(api.user_account(COUNTER, QUOTE) < 100_000, "net loss");
}

#[test]
fn scenario_d_margin_adjustment_add_then_close_refunds_extra_margin() {
    const MARGIN_USER: i64 = 1;
    const COUNTER: i64 = 2;

    let mut api = new_seeded_futures_api(futures_spec_fixed_fee(10, 5));
    assert_eq!(api.add_user(MARGIN_USER), CommandResultCode::Success);
    assert_eq!(api.add_user(COUNTER), CommandResultCode::Success);
    assert_eq!(api.balance_adjustment(MARGIN_USER, QUOTE, 100_000, 1), CommandResultCode::Success);
    assert_eq!(api.balance_adjustment(COUNTER, QUOTE, 100_000, 2), CommandResultCode::Success);
    assert_eq!(api.set_mark_price(FUT_SYMBOL, 100), CommandResultCode::Success);
    assert_futures_invariants(&api);

    assert_eq!(
        api.place_futures_order(PlaceFuturesOrderRequest {
            order_id: 1, uid: COUNTER, symbol: FUT_SYMBOL, price: 100, size: 10,
            action: OrderAction::Ask, order_type: OrderType::Gtc, leverage: 1,
            margin_mode: MarginMode::Isolated, reduce_only: false,
        }),
        CommandResultCode::Success
    );
    assert_futures_invariants(&api);
    assert_eq!(
        api.place_futures_order(PlaceFuturesOrderRequest {
            order_id: 2, uid: MARGIN_USER, symbol: FUT_SYMBOL, price: 100, size: 10,
            action: OrderAction::Bid, order_type: OrderType::Gtc, leverage: 1,
            margin_mode: MarginMode::Isolated, reduce_only: false,
        }),
        CommandResultCode::Success
    );
    assert_futures_invariants(&api);
    assert_eq!(naive_conservation(&api, QUOTE), 0, "no extra margin added yet during opening, naive formula still holds");

    let acct_before_margin = api.user_account(MARGIN_USER, QUOTE);

    assert_eq!(
        api.margin_adjustment(MarginAdjustmentRequest {
            uid: MARGIN_USER, symbol: FUT_SYMBOL, action: OrderAction::Bid, amount: 500,
            margin_mode: MarginMode::Isolated, order_id: 100,
        }),
        CommandResultCode::Success
    );
    assert_futures_invariants(&api);
    assert_eq!(
        naive_conservation(&api, QUOTE),
        -500,
        "after MARGIN_ADJUSTMENT accounts genuinely decreases by 500; before counting extra_margin, naive formula should be off by -500"
    );
    assert_eq!(api.user_account(MARGIN_USER, QUOTE), acct_before_margin - 500, "accounts genuinely debited 500");
    let pos = api.user_position(MARGIN_USER, FUT_SYMBOL).expect("position must already exist before adding margin");
    assert_eq!(pos.extra_margin, 500, "500 moved into the position's extra_margin (scale_k=1, identity conversion)");

    assert_eq!(api.set_mark_price(FUT_SYMBOL, 100), CommandResultCode::Success);
    assert_futures_invariants(&api);

    assert_eq!(
        api.close_position(ClosePositionRequest {
            order_id: 3, uid: COUNTER, symbol: FUT_SYMBOL, action: OrderAction::Bid,
            price: 100, size: 10, order_type: OrderType::Gtc,
        }),
        CommandResultCode::Success
    );
    assert_futures_invariants(&api);
    assert_eq!(
        api.close_position(ClosePositionRequest {
            order_id: 4, uid: MARGIN_USER, symbol: FUT_SYMBOL, action: OrderAction::Ask,
            price: 100, size: 10, order_type: OrderType::Gtc,
        }),
        CommandResultCode::Success
    );
    assert_futures_invariants(&api);
    assert_eq!(naive_conservation(&api, QUOTE), 0, "extra_margin fully refunded after closing, naive formula holds again");

    assert!(api.user_position(MARGIN_USER, FUT_SYMBOL).is_none());
    assert!(api.user_position(COUNTER, FUT_SYMBOL).is_none());
    assert_eq!(
        api.user_account(MARGIN_USER, QUOTE),
        100_000 - 100  - 500  - 100  + 500,
    );
}

#[test]
fn scenario_e_multi_user_maker_taker_proportional_fee_conserves_exactly() {
    const MAKER: i64 = 1;
    const TAKER1: i64 = 2;
    const TAKER2: i64 = 3;

    let mut api = new_seeded_futures_api(futures_spec_proportional_fee(1_000, 500, 1_000_000));
    for uid in [MAKER, TAKER1, TAKER2] {
        assert_eq!(api.add_user(uid), CommandResultCode::Success);
        assert_eq!(api.balance_adjustment(uid, QUOTE, 10_000_000, uid), CommandResultCode::Success);
    }
    assert_eq!(api.set_mark_price(FUT_SYMBOL, 1_000), CommandResultCode::Success);
    assert_futures_invariants(&api);

    assert_eq!(
        api.place_futures_order(PlaceFuturesOrderRequest {
            order_id: 1, uid: MAKER, symbol: FUT_SYMBOL, price: 1_000, size: 20,
            action: OrderAction::Ask, order_type: OrderType::Gtc, leverage: 1,
            margin_mode: MarginMode::Isolated, reduce_only: false,
        }),
        CommandResultCode::Success
    );
    assert_futures_invariants(&api);
    assert_eq!(naive_conservation(&api, QUOTE), 0);

    assert_eq!(
        api.place_futures_order(PlaceFuturesOrderRequest {
            order_id: 2, uid: TAKER1, symbol: FUT_SYMBOL, price: 1_000, size: 8,
            action: OrderAction::Bid, order_type: OrderType::Gtc, leverage: 1,
            margin_mode: MarginMode::Isolated, reduce_only: false,
        }),
        CommandResultCode::Success
    );
    assert_futures_invariants(&api);
    assert_eq!(naive_conservation(&api, QUOTE), 0);

    assert_eq!(
        api.place_futures_order(PlaceFuturesOrderRequest {
            order_id: 3, uid: TAKER2, symbol: FUT_SYMBOL, price: 1_000, size: 12,
            action: OrderAction::Bid, order_type: OrderType::Gtc, leverage: 1,
            margin_mode: MarginMode::Isolated, reduce_only: false,
        }),
        CommandResultCode::Success
    );
    assert_futures_invariants(&api);
    assert_eq!(naive_conservation(&api, QUOTE), 0);

    assert_eq!(api.user_position(MAKER, FUT_SYMBOL).unwrap().open_volume, 20);
    assert_eq!(api.user_position(TAKER1, FUT_SYMBOL).unwrap().open_volume, 8);
    assert_eq!(api.user_position(TAKER2, FUT_SYMBOL).unwrap().open_volume, 12);
    assert!(api.fees(QUOTE) > 0, "proportional fee should result in a non-zero fee accrual");

    assert_eq!(
        api.place_futures_order(PlaceFuturesOrderRequest {
            order_id: 4, uid: MAKER, symbol: FUT_SYMBOL, price: 1_000, size: 20,
            action: OrderAction::Bid, order_type: OrderType::Gtc, leverage: 1,
            margin_mode: MarginMode::Isolated, reduce_only: false,
        }),
        CommandResultCode::Success
    );
    assert_futures_invariants(&api);
    assert_eq!(naive_conservation(&api, QUOTE), 0);

    assert_eq!(
        api.close_position(ClosePositionRequest {
            order_id: 5, uid: TAKER1, symbol: FUT_SYMBOL, action: OrderAction::Ask,
            price: 1_000, size: 8, order_type: OrderType::Gtc,
        }),
        CommandResultCode::Success
    );
    assert_futures_invariants(&api);
    assert_eq!(naive_conservation(&api, QUOTE), 0);

    assert_eq!(
        api.close_position(ClosePositionRequest {
            order_id: 6, uid: TAKER2, symbol: FUT_SYMBOL, action: OrderAction::Ask,
            price: 1_000, size: 12, order_type: OrderType::Gtc,
        }),
        CommandResultCode::Success
    );
    assert_futures_invariants(&api);
    assert_eq!(naive_conservation(&api, QUOTE), 0, "trading against each other at the same price (pnl is always 0), naive formula holds exactly throughout");

    assert!(api.user_position(MAKER, FUT_SYMBOL).is_none());
    assert!(api.user_position(TAKER1, FUT_SYMBOL).is_none());
    assert!(api.user_position(TAKER2, FUT_SYMBOL).is_none());
}

#[test]
fn scenario_f_cross_margin_mode_open_and_close_conserves() {
    const CROSS_USER: i64 = 1;
    const COUNTER: i64 = 2;

    let mut api = new_seeded_futures_api(futures_spec_fixed_fee(10, 5));
    assert_eq!(api.add_user(CROSS_USER), CommandResultCode::Success);
    assert_eq!(api.add_user(COUNTER), CommandResultCode::Success);
    assert_eq!(api.balance_adjustment(CROSS_USER, QUOTE, 100_000, 1), CommandResultCode::Success);
    assert_eq!(api.balance_adjustment(COUNTER, QUOTE, 100_000, 2), CommandResultCode::Success);
    assert_eq!(api.set_mark_price(FUT_SYMBOL, 100), CommandResultCode::Success);
    assert_futures_invariants(&api);

    assert_eq!(
        api.place_futures_order(PlaceFuturesOrderRequest {
            order_id: 1, uid: COUNTER, symbol: FUT_SYMBOL, price: 100, size: 10,
            action: OrderAction::Ask, order_type: OrderType::Gtc, leverage: 1,
            margin_mode: MarginMode::Cross, reduce_only: false,
        }),
        CommandResultCode::Success
    );
    assert_futures_invariants(&api);
    assert_eq!(
        api.place_futures_order(PlaceFuturesOrderRequest {
            order_id: 2, uid: CROSS_USER, symbol: FUT_SYMBOL, price: 100, size: 10,
            action: OrderAction::Bid, order_type: OrderType::Gtc, leverage: 1,
            margin_mode: MarginMode::Cross, reduce_only: false,
        }),
        CommandResultCode::Success
    );
    assert_futures_invariants(&api);
    assert_eq!(naive_conservation(&api, QUOTE), 0);

    let pos = api.user_position(CROSS_USER, FUT_SYMBOL).expect("position must exist after CROSS-mode opening");
    assert_eq!(pos.margin_mode, MarginMode::Cross);
    assert_eq!(pos.direction, PositionDirection::Long);

    assert_eq!(api.set_mark_price(FUT_SYMBOL, 130), CommandResultCode::Success);
    assert_futures_invariants(&api);

    assert_eq!(
        api.close_position(ClosePositionRequest {
            order_id: 3, uid: COUNTER, symbol: FUT_SYMBOL, action: OrderAction::Bid,
            price: 130, size: 10, order_type: OrderType::Gtc,
        }),
        CommandResultCode::Success
    );
    assert_futures_invariants(&api);
    assert_eq!(
        api.close_position(ClosePositionRequest {
            order_id: 4, uid: CROSS_USER, symbol: FUT_SYMBOL, action: OrderAction::Ask,
            price: 130, size: 10, order_type: OrderType::Gtc,
        }),
        CommandResultCode::Success
    );
    assert_futures_invariants(&api);
    assert_eq!(naive_conservation(&api, QUOTE), 0);

    assert!(api.user_position(CROSS_USER, FUT_SYMBOL).is_none());
    assert!(api.user_position(COUNTER, FUT_SYMBOL).is_none());
    assert!(api.user_account(CROSS_USER, QUOTE) > 100_000 - 100, "CROSS-mode long should realize positive PnL");
}

#[test]
fn characterization_naive_formula_misses_fresh_counterparty_unrealized_pnl() {
    const A: i64 = 1;
    const B: i64 = 2;
    const C: i64 = 3;

    let mut api = new_seeded_futures_api(futures_spec_fixed_fee(0, 0));
    for uid in [A, B, C] {
        assert_eq!(api.add_user(uid), CommandResultCode::Success);
        assert_eq!(api.balance_adjustment(uid, QUOTE, 10_000, uid), CommandResultCode::Success);
    }
    assert_eq!(api.set_mark_price(FUT_SYMBOL, 100), CommandResultCode::Success);

    assert_eq!(
        api.place_futures_order(PlaceFuturesOrderRequest {
            order_id: 1, uid: B, symbol: FUT_SYMBOL, price: 100, size: 10,
            action: OrderAction::Ask, order_type: OrderType::Gtc, leverage: 1,
            margin_mode: MarginMode::Isolated, reduce_only: false,
        }),
        CommandResultCode::Success
    );
    assert_eq!(
        api.place_futures_order(PlaceFuturesOrderRequest {
            order_id: 2, uid: A, symbol: FUT_SYMBOL, price: 100, size: 10,
            action: OrderAction::Bid, order_type: OrderType::Gtc, leverage: 1,
            margin_mode: MarginMode::Isolated, reduce_only: false,
        }),
        CommandResultCode::Success
    );
    assert_eq!(naive_conservation(&api, QUOTE), 0, "during opening both sides are within scope of the naive formula");
    assert_futures_conservation(&api);

    assert_eq!(api.set_mark_price(FUT_SYMBOL, 120), CommandResultCode::Success);

    assert_eq!(
        api.place_futures_order(PlaceFuturesOrderRequest {
            order_id: 3, uid: C, symbol: FUT_SYMBOL, price: 120, size: 10,
            action: OrderAction::Bid, order_type: OrderType::Gtc, leverage: 1,
            margin_mode: MarginMode::Isolated, reduce_only: false,
        }),
        CommandResultCode::Success
    );
    assert_eq!(
        api.close_position(ClosePositionRequest {
            order_id: 4, uid: A, symbol: FUT_SYMBOL, action: OrderAction::Ask,
            price: 120, size: 10, order_type: OrderType::Gtc,
        }),
        CommandResultCode::Success
    );

    assert!(api.user_position(A, FUT_SYMBOL).is_none(), "A has fully closed");
    assert_eq!(api.user_account(A, QUOTE), 10_000 + 200, "A has realized 200 profit (fee rate is 0)");
    assert_eq!(
        naive_conservation(&api, QUOTE),
        200,
        "naive formula is off by 200 — not a bug, B's unrealized loss on its still-open position hasn't left its own position field"
    );
    assert_futures_conservation(&api);
}

#[derive(Debug, Clone)]
enum FutGenCmd {
    PlaceOpen { uid_idx: usize, is_bid: bool, price: i64, size: i64, is_cross: bool },
    ClosePosition { uid_idx: usize, price: i64, size: i64 },
    MarginAdd { uid_idx: usize, amount: i64, is_cross: bool },
    SetMarkPrice { price: i64 },
    SettleFunding { is_ask: bool, rate: i64 },
}

fn gen_fut_cmd(n_users: usize) -> impl Strategy<Value = FutGenCmd> {
    let place = (0..n_users, any::<bool>(), 50i64..=200, 1i64..=50, any::<bool>())
        .prop_map(|(uid_idx, is_bid, price, size, is_cross)| FutGenCmd::PlaceOpen { uid_idx, is_bid, price, size, is_cross });
    let close = (0..n_users, 50i64..=200, 1i64..=50)
        .prop_map(|(uid_idx, price, size)| FutGenCmd::ClosePosition { uid_idx, price, size });
    let margin_add = (0..n_users, 1i64..=5_000, any::<bool>())
        .prop_map(|(uid_idx, amount, is_cross)| FutGenCmd::MarginAdd { uid_idx, amount, is_cross });
    let mark = (50i64..=200).prop_map(|price| FutGenCmd::SetMarkPrice { price });
    let funding = (any::<bool>(), 1i64..=1_000).prop_map(|(is_ask, rate)| FutGenCmd::SettleFunding { is_ask, rate });
    prop_oneof![5 => place, 3 => close, 1 => margin_add, 1 => mark, 1 => funding]
}

#[allow(clippy::type_complexity)]
fn fut_scenario_strategy() -> impl Strategy<Value = (bool, usize, Vec<i32>, Vec<i64>, Vec<bool>, Vec<FutGenCmd>)> {
    (any::<bool>(), 2usize..=4).prop_flat_map(|(fixed_fee, n_users)| {
        let leverages = prop::collection::vec(1i32..=5, n_users);
        let balances = prop::collection::vec(1_000_000i64..=100_000_000i64, n_users);
        let hedge_modes = prop::collection::vec(any::<bool>(), n_users);
        let cmds = prop::collection::vec(gen_fut_cmd(n_users), 10..80);
        (Just(fixed_fee), Just(n_users), leverages, balances, hedge_modes, cmds)
    })
}

proptest! {
    #![proptest_config(ProptestConfig::with_cases(256))]

    #[test]
    fn conservation_holds_for_random_futures_command_stream(
        (fixed_fee, n_users, leverages, balances, hedge_modes, cmds) in fut_scenario_strategy()
    ) {
        let spec = if fixed_fee {
            futures_spec_fixed_fee(10, 5)
        } else {
            futures_spec_proportional_fee(20, 10, 1_000_000)
        };
        let mut api = new_seeded_futures_api(spec);

        let uids: Vec<i64> = (1..=n_users as i64).collect();
        for &uid in &uids {
            prop_assert_eq!(api.add_user(uid), CommandResultCode::Success);
        }
        assert_futures_invariants(&api);

        for (i, &uid) in uids.iter().enumerate() {
            prop_assert_eq!(
                api.balance_adjustment(uid, QUOTE, balances[i], (i as i64) + 1),
                CommandResultCode::Success
            );
            if hedge_modes[i] {
                prop_assert_eq!(api.adjust_position_mode(uid, true), CommandResultCode::Success);
            }
            assert_futures_invariants(&api);
        }

        prop_assert_eq!(api.set_mark_price(FUT_SYMBOL, 100), CommandResultCode::Success);
        assert_futures_invariants(&api);

        let mut next_order_id: i64 = 1000;

        for cmd in &cmds {
            match cmd {
                FutGenCmd::PlaceOpen { uid_idx, is_bid, price, size, is_cross } => {
                    let uid = uids[*uid_idx];
                    let action = if *is_bid { OrderAction::Bid } else { OrderAction::Ask };
                    let order_id = next_order_id;
                    next_order_id += 1;
                    let _ = api.place_futures_order(PlaceFuturesOrderRequest {
                        order_id,
                        uid,
                        symbol: FUT_SYMBOL,
                        price: *price,
                        size: *size,
                        action,
                        order_type: OrderType::Gtc,
                        leverage: leverages[*uid_idx],
                        margin_mode: if *is_cross { MarginMode::Cross } else { MarginMode::Isolated },
                        reduce_only: false,
                    });
                }
                FutGenCmd::ClosePosition { uid_idx, price, size } => {
                    let uid = uids[*uid_idx];
                    let action = match api.user_position(uid, FUT_SYMBOL) {
                        Some(pos) if pos.direction == PositionDirection::Long => OrderAction::Ask,
                        Some(pos) if pos.direction == PositionDirection::Short => OrderAction::Bid,
                        _ => OrderAction::Bid,
                    };
                    let order_id = next_order_id;
                    next_order_id += 1;
                    let _ = api.close_position(ClosePositionRequest {
                        order_id,
                        uid,
                        symbol: FUT_SYMBOL,
                        action,
                        price: *price,
                        size: *size,
                        order_type: OrderType::Gtc,
                    });
                }
                FutGenCmd::MarginAdd { uid_idx, amount, is_cross } => {
                    let uid = uids[*uid_idx];
                    let order_id = next_order_id;
                    next_order_id += 1;
                    let _ = api.margin_adjustment(MarginAdjustmentRequest {
                        uid,
                        symbol: FUT_SYMBOL,
                        action: OrderAction::Bid,
                        amount: *amount,
                        margin_mode: if *is_cross { MarginMode::Cross } else { MarginMode::Isolated },
                        order_id,
                    });
                }
                FutGenCmd::SetMarkPrice { price } => {
                    let _ = api.set_mark_price(FUT_SYMBOL, *price);
                }
                FutGenCmd::SettleFunding { is_ask, rate } => {
                    let action = if *is_ask { OrderAction::Ask } else { OrderAction::Bid };
                    let order_id = next_order_id;
                    next_order_id += 1;
                    let _ = api.settle_funding_fees(FUT_SYMBOL, action, *rate, 1_000_000, order_id);
                }
            }
            assert_futures_invariants(&api);
        }
    }
}
