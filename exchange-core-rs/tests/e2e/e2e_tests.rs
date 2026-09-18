use proptest::prelude::*;

use exchange_core_rs::core::common::cmd::command_result_code::CommandResultCode;
use exchange_core_rs::core::common::order_action::OrderAction;
use exchange_core_rs::core::common::order_type::OrderType;
use exchange_core_rs::core::common::symbol_type::SymbolType;
use exchange_core_rs::core::common::core_symbol_specification::CoreSymbolSpecification;

use exchange_core_rs::core::exchange_api::{CancelOrderRequest, ExchangeApi, PlaceOrderRequest, ReduceOrderRequest};

const BASE: i32 = 1;
const QUOTE: i32 = 2;
const SYMBOL: i32 = 100;

fn assert_global_conservation(api: &ExchangeApi) {
    for &cur in api.ssp().currencies.keys() {
        let user_sum: i64 = api.ups().users.values().map(|p| p.account(cur)).sum();
        let adjustments = api.adjustments(cur);
        let fees = api.fees(cur);
        let total = user_sum + adjustments + fees;
        assert_eq!(
            total, 0,
            "Global conservation broken: currency={cur} users_sum={user_sum} adjustments={adjustments} fees={fees}"
        );
    }
}

fn assert_accounts_non_negative(api: &ExchangeApi) {
    for p in api.ups().users.values() {
        for (&cur, &bal) in &p.accounts {
            assert!(bal >= 0, "user {}'s accounts[{cur}] is negative: {bal}", p.uid);
        }
    }
}

fn assert_locked_non_negative(api: &ExchangeApi) {
    for p in api.ups().users.values() {
        for (&cur, &locked) in &p.exchange_locked {
            assert!(locked >= 0, "user {}'s exchange_locked[{cur}] is negative: {locked}", p.uid);
        }
    }
}

fn assert_no_negative_balances(api: &ExchangeApi) {
    assert_accounts_non_negative(api);
    assert_locked_non_negative(api);
}

fn assert_invariants(api: &ExchangeApi) {
    assert_global_conservation(api);
    assert_no_negative_balances(api);
}

fn assert_invariants_gated(api: &ExchangeApi, fixed_fee: bool) {
    assert_global_conservation(api);
    assert_accounts_non_negative(api);
    if fixed_fee {
        assert_locked_non_negative(api);
    }
}

fn fixed_fee_spec(taker_fee: i64, maker_fee: i64) -> CoreSymbolSpecification {
    CoreSymbolSpecification {
        symbol_id: SYMBOL,
        symbol_type: SymbolType::CurrencyExchangePair,
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

fn proportional_fee_spec(taker_fee: i64, maker_fee: i64, fee_scale_k: i64) -> CoreSymbolSpecification {
    CoreSymbolSpecification {
        symbol_id: SYMBOL,
        symbol_type: SymbolType::CurrencyExchangePair,
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

fn new_seeded_api(spec: CoreSymbolSpecification) -> ExchangeApi {
    let mut api = ExchangeApi::new();
    api.add_currency(BASE, 1);
    api.add_currency(QUOTE, 1);
    assert_eq!(api.add_symbol(spec), CommandResultCode::Success);
    api
}

#[test]
fn scenario_multi_level_partial_and_full_fill_fixed_fee() {
    const SELLER_A: i64 = 1;
    const SELLER_B: i64 = 2;
    const BUYER: i64 = 3;

    let mut api = new_seeded_api(fixed_fee_spec(2, 1));
    assert_eq!(api.add_user(SELLER_A), CommandResultCode::Success);
    assert_eq!(api.add_user(SELLER_B), CommandResultCode::Success);
    assert_eq!(api.add_user(BUYER), CommandResultCode::Success);
    assert_invariants(&api);

    assert_eq!(api.balance_adjustment(SELLER_A, BASE, 1_000, 1), CommandResultCode::Success);
    assert_invariants(&api);
    assert_eq!(api.balance_adjustment(SELLER_B, BASE, 1_000, 2), CommandResultCode::Success);
    assert_invariants(&api);
    assert_eq!(api.balance_adjustment(BUYER, QUOTE, 1_000_000, 3), CommandResultCode::Success);
    assert_invariants(&api);

    assert_eq!(
        api.place_order(PlaceOrderRequest {
            order_id: 1,
            uid: SELLER_A,
            symbol: SYMBOL,
            price: 100,
            size: 400,
            reserve_bid_price: 0,
            action: OrderAction::Ask,
            order_type: OrderType::Gtc,
        }),
        CommandResultCode::Success
    );
    assert_invariants(&api);

    assert_eq!(
        api.place_order(PlaceOrderRequest {
            order_id: 2,
            uid: SELLER_B,
            symbol: SYMBOL,
            price: 105,
            size: 600,
            reserve_bid_price: 0,
            action: OrderAction::Ask,
            order_type: OrderType::Gtc,
        }),
        CommandResultCode::Success
    );
    assert_invariants(&api);

    assert_eq!(
        api.place_order(PlaceOrderRequest {
            order_id: 3,
            uid: BUYER,
            symbol: SYMBOL,
            price: 105,
            size: 700,
            reserve_bid_price: 105,
            action: OrderAction::Bid,
            order_type: OrderType::Gtc,
        }),
        CommandResultCode::Success
    );
    assert_invariants(&api);

    assert_eq!(api.user_locked(BUYER, QUOTE), 0);
    assert_eq!(api.user_account(SELLER_A, BASE), 600);
    assert_eq!(api.user_locked(SELLER_A, BASE), 0);
    assert_eq!(api.user_locked(SELLER_B, BASE), 300);

    let l2 = api.request_l2(SYMBOL, 10);
    assert_eq!(l2.ask_prices, vec![105]);
    assert_eq!(l2.ask_volumes, vec![300]);
    assert!(l2.bid_prices.is_empty());

    assert_eq!(
        api.cancel_order(CancelOrderRequest { order_id: 2, uid: SELLER_B, symbol: SYMBOL }),
        CommandResultCode::Success
    );
    assert_invariants(&api);
    assert_eq!(api.user_locked(SELLER_B, BASE), 0);

    let l2_after_cancel = api.request_l2(SYMBOL, 10);
    assert!(l2_after_cancel.ask_prices.is_empty());
}

#[test]
fn scenario_reduce_order_then_full_fill_proportional_fee() {
    const MAKER: i64 = 1;
    const TAKER: i64 = 2;

    let mut api = new_seeded_api(proportional_fee_spec(1_000, 500, 1_000_000));
    assert_eq!(api.add_user(MAKER), CommandResultCode::Success);
    assert_eq!(api.add_user(TAKER), CommandResultCode::Success);
    assert_invariants(&api);

    assert_eq!(api.balance_adjustment(MAKER, BASE, 1_000, 1), CommandResultCode::Success);
    assert_invariants(&api);
    assert_eq!(api.balance_adjustment(TAKER, QUOTE, 10_000_000, 2), CommandResultCode::Success);
    assert_invariants(&api);

    assert_eq!(
        api.place_order(PlaceOrderRequest {
            order_id: 1,
            uid: MAKER,
            symbol: SYMBOL,
            price: 1_000,
            size: 1_000,
            reserve_bid_price: 0,
            action: OrderAction::Ask,
            order_type: OrderType::Gtc,
        }),
        CommandResultCode::Success
    );
    assert_invariants(&api);
    assert_eq!(api.user_locked(MAKER, BASE), 1_000);

    assert_eq!(
        api.reduce_order(ReduceOrderRequest {
            order_id: 1,
            uid: MAKER,
            symbol: SYMBOL,
            reduce_size: 300,
        }),
        CommandResultCode::Success
    );
    assert_invariants(&api);
    assert_eq!(api.user_locked(MAKER, BASE), 700);
    assert_eq!(api.user_account(MAKER, BASE), 1_000, "reduce only releases the lock; accounts is unchanged");

    assert_eq!(
        api.place_order(PlaceOrderRequest {
            order_id: 2,
            uid: TAKER,
            symbol: SYMBOL,
            price: 1_000,
            size: 700,
            reserve_bid_price: 1_000,
            action: OrderAction::Bid,
            order_type: OrderType::Gtc,
        }),
        CommandResultCode::Success
    );
    assert_invariants(&api);

    assert_eq!(api.user_locked(MAKER, BASE), 0);
    assert_eq!(api.user_account(MAKER, BASE), 300, "the remaining 300 unsold base stays in the account");
    assert_eq!(api.user_locked(TAKER, QUOTE), 0);
    assert!(api.fees(QUOTE) > 0, "proportional fee should record a non-zero fee");
}

#[test]
fn scenario_ioc_partial_fill_and_full_reject() {
    const MAKER: i64 = 1;
    const TAKER1: i64 = 2;
    const TAKER2: i64 = 3;

    let mut api = new_seeded_api(fixed_fee_spec(0, 0));
    assert_eq!(api.add_user(MAKER), CommandResultCode::Success);
    assert_eq!(api.add_user(TAKER1), CommandResultCode::Success);
    assert_eq!(api.add_user(TAKER2), CommandResultCode::Success);
    assert_invariants(&api);

    assert_eq!(api.balance_adjustment(MAKER, BASE, 200, 1), CommandResultCode::Success);
    assert_invariants(&api);
    assert_eq!(api.balance_adjustment(TAKER1, QUOTE, 1_000_000, 2), CommandResultCode::Success);
    assert_invariants(&api);
    assert_eq!(api.balance_adjustment(TAKER2, QUOTE, 1_000_000, 3), CommandResultCode::Success);
    assert_invariants(&api);

    assert_eq!(
        api.place_order(PlaceOrderRequest {
            order_id: 1,
            uid: MAKER,
            symbol: SYMBOL,
            price: 50,
            size: 200,
            reserve_bid_price: 0,
            action: OrderAction::Ask,
            order_type: OrderType::Gtc,
        }),
        CommandResultCode::Success
    );
    assert_invariants(&api);

    assert_eq!(
        api.place_order(PlaceOrderRequest {
            order_id: 2,
            uid: TAKER1,
            symbol: SYMBOL,
            price: 50,
            size: 500,
            reserve_bid_price: 50,
            action: OrderAction::Bid,
            order_type: OrderType::Ioc,
        }),
        CommandResultCode::Success
    );
    assert_invariants(&api);
    assert_eq!(api.user_account(TAKER1, BASE), 200);
    assert_eq!(api.user_locked(TAKER1, QUOTE), 0, "IOC's unfilled portion should fully release the lock");

    let l2 = api.request_l2(SYMBOL, 10);
    assert!(l2.ask_prices.is_empty(), "maker's ask order has been fully consumed");

    assert_eq!(
        api.place_order(PlaceOrderRequest {
            order_id: 3,
            uid: TAKER2,
            symbol: SYMBOL,
            price: 50,
            size: 100,
            reserve_bid_price: 50,
            action: OrderAction::Bid,
            order_type: OrderType::Ioc,
        }),
        CommandResultCode::Success
    );
    assert_invariants(&api);
    assert_eq!(api.user_account(TAKER2, BASE), 0);
    assert_eq!(api.user_locked(TAKER2, QUOTE), 0);
}

#[test]
fn scenario_fok_full_fill_and_full_reject() {
    const MAKER1: i64 = 1;
    const MAKER2: i64 = 2;
    const TAKER1: i64 = 3;
    const TAKER2: i64 = 4;

    let mut api = new_seeded_api(fixed_fee_spec(0, 0));
    for uid in [MAKER1, MAKER2, TAKER1, TAKER2] {
        assert_eq!(api.add_user(uid), CommandResultCode::Success);
    }
    assert_invariants(&api);

    assert_eq!(api.balance_adjustment(MAKER1, BASE, 500, 1), CommandResultCode::Success);
    assert_invariants(&api);
    assert_eq!(api.balance_adjustment(MAKER2, BASE, 100, 2), CommandResultCode::Success);
    assert_invariants(&api);
    assert_eq!(api.balance_adjustment(TAKER1, QUOTE, 1_000_000, 3), CommandResultCode::Success);
    assert_invariants(&api);
    assert_eq!(api.balance_adjustment(TAKER2, QUOTE, 1_000_000, 4), CommandResultCode::Success);
    assert_invariants(&api);

    assert_eq!(
        api.place_order(PlaceOrderRequest {
            order_id: 1,
            uid: MAKER1,
            symbol: SYMBOL,
            price: 80,
            size: 500,
            reserve_bid_price: 0,
            action: OrderAction::Ask,
            order_type: OrderType::Gtc,
        }),
        CommandResultCode::Success
    );
    assert_invariants(&api);

    assert_eq!(
        api.place_order(PlaceOrderRequest {
            order_id: 2,
            uid: TAKER1,
            symbol: SYMBOL,
            price: 80,
            size: 500,
            reserve_bid_price: 80,
            action: OrderAction::Bid,
            order_type: OrderType::Fok,
        }),
        CommandResultCode::Success
    );
    assert_invariants(&api);
    assert_eq!(api.user_account(TAKER1, BASE), 500);
    assert_eq!(api.user_locked(TAKER1, QUOTE), 0);

    assert_eq!(
        api.place_order(PlaceOrderRequest {
            order_id: 3,
            uid: MAKER2,
            symbol: SYMBOL,
            price: 80,
            size: 100,
            reserve_bid_price: 0,
            action: OrderAction::Ask,
            order_type: OrderType::Gtc,
        }),
        CommandResultCode::Success
    );
    assert_invariants(&api);

    assert_eq!(
        api.place_order(PlaceOrderRequest {
            order_id: 4,
            uid: TAKER2,
            symbol: SYMBOL,
            price: 80,
            size: 500,
            reserve_bid_price: 80,
            action: OrderAction::Bid,
            order_type: OrderType::Fok,
        }),
        CommandResultCode::Success
    );
    assert_invariants(&api);
    assert_eq!(api.user_account(TAKER2, BASE), 0, "FOK with insufficient quantity should reject the whole order, no partial fill");
    assert_eq!(api.user_locked(TAKER2, QUOTE), 0, "FOK rejection should fully release the lock");

    let l2 = api.request_l2(SYMBOL, 10);
    assert_eq!(l2.ask_volumes, vec![100], "MAKER2's resting order should remain untouched");
}

#[test]
fn scenario_self_trade_conserves_globally() {
    const USER: i64 = 1;

    let mut api = new_seeded_api(fixed_fee_spec(3, 1));
    assert_eq!(api.add_user(USER), CommandResultCode::Success);
    assert_invariants(&api);

    assert_eq!(api.balance_adjustment(USER, BASE, 1_000, 1), CommandResultCode::Success);
    assert_invariants(&api);
    assert_eq!(api.balance_adjustment(USER, QUOTE, 1_000_000, 2), CommandResultCode::Success);
    assert_invariants(&api);

    assert_eq!(
        api.place_order(PlaceOrderRequest {
            order_id: 1,
            uid: USER,
            symbol: SYMBOL,
            price: 60,
            size: 200,
            reserve_bid_price: 0,
            action: OrderAction::Ask,
            order_type: OrderType::Gtc,
        }),
        CommandResultCode::Success
    );
    assert_invariants(&api);

    let fees_before = api.fees(QUOTE);
    let quote_before = api.user_account(USER, QUOTE);

    assert_eq!(
        api.place_order(PlaceOrderRequest {
            order_id: 2,
            uid: USER,
            symbol: SYMBOL,
            price: 60,
            size: 200,
            reserve_bid_price: 60,
            action: OrderAction::Bid,
            order_type: OrderType::Gtc,
        }),
        CommandResultCode::Success
    );
    assert_invariants(&api);

    assert_eq!(api.user_account(USER, BASE), 1_000, "base net unchanged after self-trade");
    assert_eq!(api.user_locked(USER, BASE), 0);
    assert_eq!(api.user_locked(USER, QUOTE), 0);

    let fees_delta = api.fees(QUOTE) - fees_before;
    assert!(fees_delta > 0, "self-trade should still normally charge taker+maker fees");
    let quote_delta = api.user_account(USER, QUOTE) - quote_before;
    assert_eq!(quote_delta, -fees_delta, "the only net loss from self-trade is the fee deducted into the fees bucket");
}

#[derive(Debug, Clone)]
enum GenCmd {
    Place { uid_idx: usize, is_bid: bool, order_type_idx: u8, price: i64, size: i64 },
    Cancel { target_idx: usize },
    Reduce { target_idx: usize, reduce_size: i64 },
}

fn order_type_from_idx(idx: u8) -> OrderType {
    match idx % 3 {
        0 => OrderType::Gtc,
        1 => OrderType::Ioc,
        _ => OrderType::Fok,
    }
}

fn gen_cmd(n_users: usize) -> impl Strategy<Value = GenCmd> {
    let place = (0..n_users, any::<bool>(), 0u8..3, 1i64..=200_000, 1i64..=1_000).prop_map(
        |(uid_idx, is_bid, order_type_idx, price, size)| GenCmd::Place {
            uid_idx,
            is_bid,
            order_type_idx,
            price,
            size,
        },
    );
    let cancel = (0usize..64).prop_map(|target_idx| GenCmd::Cancel { target_idx });
    let reduce =
        (0usize..64, 1i64..=1_000).prop_map(|(target_idx, reduce_size)| GenCmd::Reduce {
            target_idx,
            reduce_size,
        });
    prop_oneof![6 => place, 2 => cancel, 2 => reduce]
}

fn scenario_strategy() -> impl Strategy<Value = (bool, usize, Vec<(i64, i64)>, Vec<GenCmd>)> {
    (any::<bool>(), 2usize..=5).prop_flat_map(|(fixed_fee, n_users)| {
        let balances =
            prop::collection::vec((1_000i64..=1_000_000_000, 1_000i64..=1_000_000_000), n_users);
        let cmds = prop::collection::vec(gen_cmd(n_users), 10..60);
        (Just(fixed_fee), Just(n_users), balances, cmds)
    })
}

proptest! {
    #![proptest_config(ProptestConfig::with_cases(256))]

    #[test]
    fn conservation_holds_for_random_command_stream(
        (fixed_fee, n_users, balances, cmds) in scenario_strategy()
    ) {
        let spec = if fixed_fee {
            fixed_fee_spec(2, 1)
        } else {
            proportional_fee_spec(20, 0, 1_000_000)
        };
        let mut api = new_seeded_api(spec);

        let uids: Vec<i64> = (1..=n_users as i64).collect();
        for &uid in &uids {
            prop_assert_eq!(api.add_user(uid), CommandResultCode::Success);
        }
        assert_invariants_gated(&api, fixed_fee);

        for (i, &uid) in uids.iter().enumerate() {
            let (base_amt, quote_amt) = balances[i];
            prop_assert_eq!(
                api.balance_adjustment(uid, BASE, base_amt, (i as i64) * 2 + 1),
                CommandResultCode::Success
            );
            assert_invariants_gated(&api, fixed_fee);
            prop_assert_eq!(
                api.balance_adjustment(uid, QUOTE, quote_amt, (i as i64) * 2 + 2),
                CommandResultCode::Success
            );
            assert_invariants_gated(&api, fixed_fee);
        }

        let mut issued: Vec<(i64, i64)> = Vec::new();
        let mut next_order_id: i64 = 1;

        for gen_cmd in &cmds {
            match gen_cmd {
                GenCmd::Place { uid_idx, is_bid, order_type_idx, price, size } => {
                    let uid = uids[*uid_idx];
                    let action = if *is_bid { OrderAction::Bid } else { OrderAction::Ask };
                    let order_type = order_type_from_idx(*order_type_idx);
                    let reserve_bid_price = if *is_bid { *price } else { 0 };
                    let order_id = next_order_id;
                    next_order_id += 1;

                    let rc = api.place_order(PlaceOrderRequest {
                        order_id,
                        uid,
                        symbol: SYMBOL,
                        price: *price,
                        size: *size,
                        reserve_bid_price,
                        action,
                        order_type,
                    });
                    if rc == CommandResultCode::Success && order_type == OrderType::Gtc {
                        issued.push((order_id, uid));
                    }
                }
                GenCmd::Cancel { target_idx } => {
                    if !issued.is_empty() {
                        let (order_id, uid) = issued[*target_idx % issued.len()];
                        let _ = api.cancel_order(CancelOrderRequest { order_id, uid, symbol: SYMBOL });
                    }
                }
                GenCmd::Reduce { target_idx, reduce_size } => {
                    if !issued.is_empty() {
                        let (order_id, uid) = issued[*target_idx % issued.len()];
                        let _ = api.reduce_order(ReduceOrderRequest {
                            order_id,
                            uid,
                            symbol: SYMBOL,
                            reduce_size: *reduce_size,
                        });
                    }
                }
            }

            assert_invariants_gated(&api, fixed_fee);
        }
    }
}

#[test]
fn characterization_proportional_fee_bid_multi_release_matches_java_negative_lock() {
    const MAKER: i64 = 1;
    const TAKER1: i64 = 2;
    const TAKER2: i64 = 3;

    let mut api = new_seeded_api(proportional_fee_spec(20, 0, 1_000_000));
    assert_eq!(api.add_user(MAKER), CommandResultCode::Success);
    assert_eq!(api.add_user(TAKER1), CommandResultCode::Success);
    assert_eq!(api.add_user(TAKER2), CommandResultCode::Success);

    assert_eq!(api.balance_adjustment(MAKER, QUOTE, 100_000_000, 1), CommandResultCode::Success);
    assert_eq!(api.balance_adjustment(TAKER1, BASE, 1_000, 2), CommandResultCode::Success);
    assert_eq!(api.balance_adjustment(TAKER2, BASE, 1_000, 3), CommandResultCode::Success);

    assert_eq!(
        api.place_order(PlaceOrderRequest {
            order_id: 1,
            uid: MAKER,
            symbol: SYMBOL,
            price: 758_000,
            size: 67,
            reserve_bid_price: 758_000,
            action: OrderAction::Bid,
            order_type: OrderType::Gtc,
        }),
        CommandResultCode::Success
    );
    assert_eq!(api.user_locked(MAKER, QUOTE), 50_787_016);

    assert_eq!(
        api.place_order(PlaceOrderRequest {
            order_id: 2,
            uid: TAKER1,
            symbol: SYMBOL,
            price: 758_000,
            size: 66,
            reserve_bid_price: 0,
            action: OrderAction::Ask,
            order_type: OrderType::Gtc,
        }),
        CommandResultCode::Success
    );
    assert_eq!(api.user_locked(MAKER, QUOTE), 758_015);

    assert_eq!(
        api.place_order(PlaceOrderRequest {
            order_id: 3,
            uid: TAKER2,
            symbol: SYMBOL,
            price: 758_000,
            size: 1,
            reserve_bid_price: 0,
            action: OrderAction::Ask,
            order_type: OrderType::Gtc,
        }),
        CommandResultCode::Success
    );

    assert_eq!(
        api.user_locked(MAKER, QUOTE),
        -1,
        "Java parity: ceiling super-additivity should make exchange_locked become -1, not release to 0"
    );

    assert_global_conservation(&api);
    assert_accounts_non_negative(&api);
}
