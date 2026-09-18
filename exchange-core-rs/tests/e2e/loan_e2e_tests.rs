use std::collections::BTreeMap;

use proptest::prelude::*;

use exchange_core_rs::core::common::last_price_cache_record::LastPriceCacheRecord;
use exchange_core_rs::core::common::cmd::command_result_code::CommandResultCode;
use exchange_core_rs::core::common::cmd::order_command::OrderCommand;
use exchange_core_rs::core::common::cmd::order_command_type::OrderCommandType;
use exchange_core_rs::core::common::core_currency_specification::CoreCurrencySpecification;
use exchange_core_rs::core::common::core_symbol_specification::CoreSymbolSpecification;
use exchange_core_rs::core::common::cross_loan_record::CrossLoanRecord;
use exchange_core_rs::core::common::isolated_loan_record::{IsolatedLoanRecord, LoanRateMode};
use exchange_core_rs::core::common::order_action::OrderAction;
use exchange_core_rs::core::common::order_type::OrderType;
use exchange_core_rs::core::common::symbol_type::SymbolType;
use exchange_core_rs::core::exchange_core::ExchangeCore;
use exchange_core_rs::core::processors::loan::loan_service::LoanService;

fn assert_loan_conservation(core: &ExchangeCore) {
    for &cur in core.ssp.currencies.keys() {
        let mut account_balances: i64 = 0;
        let mut exchange_locked_sum: i64 = 0;
        let mut loan_collateral_sum: i64 = 0;
        let mut extra_margin_sum: i64 = 0;

        for p in core.ups.users.values() {
            let acc = p.account(cur);
            let locked = p.locked(cur);

            let mut loan_collateral: i64 = 0;
            for loan in p.isolated_loans.values() {
                if loan.collateral_currency == cur {
                    loan_collateral += loan.collateral_amount;
                }
            }
            loan_collateral += p.cross_loan_collateral(cur);

            account_balances += acc - locked - loan_collateral;
            exchange_locked_sum += locked;
            loan_collateral_sum += loan_collateral;

            for pos in p.positions.values() {
                if pos.currency != cur {
                    continue;
                }
                let mark = core
                    .risk
                    .mark_price(pos.symbol)
                    .unwrap_or_else(|| panic!("open position on symbol {} missing mark price", pos.symbol));
                extra_margin_sum += pos.estimate_pnl(mark) + pos.extra_margin;
            }
        }

        let loan_balances = core.risk.loan_service.get_loan_pool_available(cur)
            + core.risk.loan_service.get_interest_revenue(cur)
            + core.risk.loan_service.get_loan_insurance_fund(cur);
        let fees = *core.risk.fees.get(&cur).unwrap_or(&0);
        let adjustments = *core.risk.adjustments.get(&cur).unwrap_or(&0);

        let total = account_balances
            + extra_margin_sum
            + exchange_locked_sum
            + loan_collateral_sum
            + loan_balances
            + fees
            + adjustments;

        assert_eq!(
            total, 0,
            "global conservation broken: currency={cur} accountBalances={account_balances} extraMargin={extra_margin_sum} \
             exchangeLocked={exchange_locked_sum} loanCollateral={loan_collateral_sum} \
             loanBalances={loan_balances} fees={fees} adjustments={adjustments}"
        );
    }
}

fn assert_loan_pool_borrowed_tracker_consistent(core: &ExchangeCore) {
    let mut outstanding: BTreeMap<i32, i64> = BTreeMap::new();
    for p in core.ups.users.values() {
        for loan in p.isolated_loans.values() {
            *outstanding.entry(loan.loan_currency).or_insert(0) += loan.outstanding_principal;
        }
        for loan in p.cross_loans.values() {
            *outstanding.entry(loan.loan_currency).or_insert(0) += loan.outstanding_principal;
        }
    }
    for &cur in core.ssp.currencies.keys() {
        let expected = *outstanding.get(&cur).unwrap_or(&0);
        let tracked = core.risk.loan_service.get_loan_pool_borrowed(cur);
        assert_eq!(
            tracked, expected,
            "loanPoolBorrowed tracker mismatch: currency={cur} tracked={tracked} expected(sum outstanding)={expected}"
        );
    }
}

fn assert_accounts_non_negative(core: &ExchangeCore) {
    for p in core.ups.users.values() {
        for (&cur, &bal) in &p.accounts {
            assert!(bal >= 0, "user {}'s accounts[{cur}] is negative: {bal}", p.uid);
        }
    }
}

fn assert_loan_invariants(core: &ExchangeCore) {
    assert_loan_conservation(core);
    assert_loan_pool_borrowed_tracker_consistent(core);
    assert_accounts_non_negative(core);
}

fn submit(core: &mut ExchangeCore, mut cmd: OrderCommand) -> (CommandResultCode, OrderCommand) {
    core.process_command(&mut cmd);
    let rc = cmd.result_code.expect("every command produces a result code");
    (rc, cmd)
}

#[allow(clippy::too_many_arguments)]
fn cmd_loan_create(
    order_id: i64,
    uid: i64,
    symbol: i32,
    loan_id: i64,
    collateral: i64,
    principal: i64,
    floating: bool,
    ts: i64,
) -> OrderCommand {
    OrderCommand {
        command: OrderCommandType::LoanCreate,
        order_id,
        uid,
        symbol,
        size: collateral,
        price: principal,
        reserve_bid_price: loan_id,
        user_cookie: if floating { LoanRateMode::Floating.code() as i32 } else { 0 },
        timestamp: ts,
        ..Default::default()
    }
}

fn cmd_loan_repay(order_id: i64, uid: i64, loan_id: i64, repay_amount: i64, ts: i64) -> OrderCommand {
    OrderCommand {
        command: OrderCommandType::LoanRepay,
        order_id,
        uid,
        price: repay_amount,
        reserve_bid_price: loan_id,
        timestamp: ts,
        ..Default::default()
    }
}

fn cmd_loan_add_collateral(order_id: i64, uid: i64, loan_id: i64, amount: i64, ts: i64) -> OrderCommand {
    OrderCommand {
        command: OrderCommandType::LoanAddCollateral,
        order_id,
        uid,
        size: amount,
        reserve_bid_price: loan_id,
        timestamp: ts,
        ..Default::default()
    }
}

fn cmd_loan_release_collateral(order_id: i64, uid: i64, loan_id: i64, amount: i64, ts: i64) -> OrderCommand {
    OrderCommand {
        command: OrderCommandType::LoanReleaseCollateral,
        order_id,
        uid,
        size: amount,
        reserve_bid_price: loan_id,
        timestamp: ts,
        ..Default::default()
    }
}

fn cmd_loan_force_liquidate(
    order_id: i64,
    uid: i64,
    symbol: i32,
    loan_id: i64,
    price: i64,
    lots: i64,
    ts: i64,
) -> OrderCommand {
    OrderCommand {
        command: OrderCommandType::LoanForceLiquidate,
        order_id,
        uid,
        symbol,
        price,
        size: lots,
        reserve_bid_price: loan_id,
        timestamp: ts,
        ..Default::default()
    }
}

fn cmd_loan_cross_add_collateral(order_id: i64, uid: i64, currency: i32, amount: i64, ts: i64) -> OrderCommand {
    OrderCommand {
        command: OrderCommandType::LoanCrossAddCollateral,
        order_id,
        uid,
        symbol: currency,
        size: amount,
        timestamp: ts,
        ..Default::default()
    }
}

fn cmd_loan_cross_withdraw_collateral(order_id: i64, uid: i64, currency: i32, amount: i64, ts: i64) -> OrderCommand {
    OrderCommand {
        command: OrderCommandType::LoanCrossWithdrawCollateral,
        order_id,
        uid,
        symbol: currency,
        size: amount,
        timestamp: ts,
        ..Default::default()
    }
}

fn cmd_loan_cross_borrow(order_id: i64, uid: i64, symbol: i32, loan_id: i64, principal: i64, ts: i64) -> OrderCommand {
    OrderCommand {
        command: OrderCommandType::LoanCrossBorrow,
        order_id,
        uid,
        symbol,
        price: principal,
        reserve_bid_price: loan_id,
        timestamp: ts,
        ..Default::default()
    }
}

fn cmd_loan_cross_repay(order_id: i64, uid: i64, loan_id: i64, repay_amount: i64, ts: i64) -> OrderCommand {
    OrderCommand {
        command: OrderCommandType::LoanCrossRepay,
        order_id,
        uid,
        price: repay_amount,
        reserve_bid_price: loan_id,
        timestamp: ts,
        ..Default::default()
    }
}

fn cmd_loan_cross_force_liquidate(
    order_id: i64,
    uid: i64,
    symbol: i32,
    target_loan_id: i64,
    price: i64,
    lots: i64,
    ts: i64,
) -> OrderCommand {
    OrderCommand {
        command: OrderCommandType::LoanCrossForceLiquidate,
        order_id,
        uid,
        symbol,
        price,
        size: lots,
        reserve_bid_price: target_loan_id,
        timestamp: ts,
        ..Default::default()
    }
}

fn cmd_pool_deposit(order_id: i64, currency: i32, amount: i64) -> OrderCommand {
    OrderCommand { command: OrderCommandType::PoolDeposit, order_id, symbol: currency, size: amount, ..Default::default() }
}

fn cmd_pool_withdraw(order_id: i64, currency: i32, amount: i64) -> OrderCommand {
    OrderCommand { command: OrderCommandType::PoolWithdraw, order_id, symbol: currency, size: amount, ..Default::default() }
}

fn cmd_loan_if_deposit(order_id: i64, currency: i32, amount: i64) -> OrderCommand {
    OrderCommand { command: OrderCommandType::LoanIfDeposit, order_id, symbol: currency, size: amount, ..Default::default() }
}

fn cmd_loan_if_withdraw(order_id: i64, currency: i32, amount: i64) -> OrderCommand {
    OrderCommand { command: OrderCommandType::LoanIfWithdraw, order_id, symbol: currency, size: amount, ..Default::default() }
}

fn cmd_balance_adjustment(order_id: i64, uid: i64, currency: i32, amount: i64) -> OrderCommand {
    OrderCommand { command: OrderCommandType::BalanceAdjustment, order_id, uid, symbol: currency, price: amount, ..Default::default() }
}

fn cmd_reprice(ts: i64) -> OrderCommand {
    OrderCommand { command: OrderCommandType::RepriceLoanRates, timestamp: ts, ..Default::default() }
}

fn cmd_place_order(
    order_id: i64,
    uid: i64,
    symbol: i32,
    price: i64,
    size: i64,
    action: OrderAction,
    order_type: OrderType,
    ts: i64,
) -> OrderCommand {
    let reserve_bid_price = if action == OrderAction::Bid { price } else { 0 };
    OrderCommand {
        command: OrderCommandType::PlaceOrder,
        order_id,
        uid,
        symbol,
        price,
        size,
        reserve_bid_price,
        action: Some(action),
        order_type: Some(order_type),
        timestamp: ts,
        ..Default::default()
    }
}

fn spot_spec(symbol_id: i32, base: i32, quote: i32) -> CoreSymbolSpecification {
    CoreSymbolSpecification {
        symbol_id,
        symbol_type: SymbolType::CurrencyExchangePair,
        base_currency: base,
        quote_currency: quote,
        base_scale_k: 1,
        quote_scale_k: 1,
        taker_fee: 0,
        maker_fee: 0,
        fee_scale_k: 0,
        ..Default::default()
    }
}

#[test]
fn scenario_isolated_open_accrue_partial_full_repay() {
    const BASE: i32 = 1;
    const QUOTE: i32 = 2;
    const SYMBOL: i32 = 100;
    const BORROWER: i64 = 10;
    const LOAN_ID: i64 = 1;
    const YEAR_MS: i64 = 365 * 24 * 3600 * 1_000;

    let mut core = ExchangeCore::new();
    core.ssp.add_currency(CoreCurrencySpecification { currency: BASE, currency_scale_k: 1, ..Default::default() });
    core.ssp.add_currency(CoreCurrencySpecification { currency: QUOTE, currency_scale_k: 1, ..Default::default() });
    let mut spec = spot_spec(SYMBOL, BASE, QUOTE);
    spec.loan_config.update(5_000, 8_000, 0, 0, 0);
    assert_eq!(core.ssp.add_symbol(spec.clone()), CommandResultCode::Success);
    core.matching.add_symbol(&spec);
    core.ups.add_empty_user_profile(BORROWER);
    core.risk.last_price_cache.insert(SYMBOL, LastPriceCacheRecord::with_mark(1));

    let (rc, _) = submit(&mut core, cmd_pool_deposit(1, QUOTE, 1_000_000));
    assert_eq!(rc, CommandResultCode::Success);
    assert_loan_invariants(&core);

    let (rc, _) = submit(&mut core, cmd_balance_adjustment(5, BORROWER, BASE, 2_000));
    assert_eq!(rc, CommandResultCode::Success);
    assert_loan_invariants(&core);

    let (rc, _) = submit(&mut core, cmd_loan_create(10, BORROWER, SYMBOL, LOAN_ID, 1_000, 500, false, 1_000));
    assert_eq!(rc, CommandResultCode::Success);
    assert_loan_invariants(&core);
    {
        let loan = core.ups.get(BORROWER).unwrap().isolated_loans.get(&LOAN_ID).unwrap();
        assert_eq!(loan.rate_mode, LoanRateMode::Locked);
        assert_eq!(loan.rate_bps, 200);
        assert_eq!(loan.outstanding_principal, 500);
    }
    assert_eq!(core.ups.get(BORROWER).unwrap().account(QUOTE), 500);

    let (rc, _) = submit(&mut core, cmd_loan_add_collateral(11, BORROWER, LOAN_ID, 100, 1_000 + YEAR_MS));
    assert_eq!(rc, CommandResultCode::Success);
    assert_loan_invariants(&core);
    {
        let loan = core.ups.get(BORROWER).unwrap().isolated_loans.get(&LOAN_ID).unwrap();
        assert_eq!(loan.accumulated_interest, 10, "10% of a 2% annual rate on 500 principal over 1yr");
        assert_eq!(loan.collateral_amount, 1_100);
    }

    let (rc, _) = submit(&mut core, cmd_loan_repay(12, BORROWER, LOAN_ID, 200, 1_000 + YEAR_MS));
    assert_eq!(rc, CommandResultCode::Success);
    assert_loan_invariants(&core);
    {
        let loan = core.ups.get(BORROWER).unwrap().isolated_loans.get(&LOAN_ID).unwrap();
        assert_eq!(loan.accumulated_interest, 0);
        assert_eq!(loan.outstanding_principal, 500 - 190);
    }
    assert_eq!(core.ups.get(BORROWER).unwrap().account(QUOTE), 500 - 200);

    let (rc, _) = submit(&mut core, cmd_balance_adjustment(14, BORROWER, QUOTE, 20));
    assert_eq!(rc, CommandResultCode::Success);
    assert_loan_invariants(&core);

    let (rc, _) = submit(&mut core, cmd_loan_repay(15, BORROWER, LOAN_ID, 0, 1_000 + YEAR_MS));
    assert_eq!(rc, CommandResultCode::Success);
    assert_loan_invariants(&core);
    {
        let loan = core.ups.get(BORROWER).unwrap().isolated_loans.get(&LOAN_ID).unwrap();
        assert_eq!(loan.outstanding_principal, 0);
        assert_eq!(loan.accumulated_interest, 0);
        assert_eq!(loan.collateral_amount, 1_100);
    }
    assert_eq!(core.ups.get(BORROWER).unwrap().account(QUOTE), 500 - 200 + 20 - 310);
    assert_eq!(core.risk.loan_service.get_interest_revenue(QUOTE), 10);

    let (rc, _) = submit(&mut core, cmd_loan_release_collateral(16, BORROWER, LOAN_ID, 1_100, 1_000 + YEAR_MS));
    assert_eq!(rc, CommandResultCode::Success);
    assert_loan_invariants(&core);
    let borrower = core.ups.get(BORROWER).unwrap();
    assert!(!borrower.isolated_loans.contains_key(&LOAN_ID), "zero-debt/zero-collateral shell removed");
    assert_eq!(borrower.account(1), 2_000);
}

#[test]
fn scenario_cross_multi_borrow_withdraw_boundary_repay() {
    const SELL: i32 = 1;
    const QUOTE: i32 = 2;
    const SYMBOL: i32 = 101;
    const BORROWER: i64 = 10;

    let mut core = ExchangeCore::new();
    core.ssp.add_currency(CoreCurrencySpecification { currency: SELL, currency_scale_k: 1, collateral_weight_bps: 10_000, ..Default::default() });
    core.ssp.add_currency(CoreCurrencySpecification { currency: QUOTE, currency_scale_k: 1, ..Default::default() });
    let mut spec = spot_spec(SYMBOL, SELL, QUOTE);
    spec.loan_config.update(5_000, 8_000, 0, 0, 0);
    assert_eq!(core.ssp.add_symbol(spec.clone()), CommandResultCode::Success);
    core.matching.add_symbol(&spec);
    core.ups.add_empty_user_profile(BORROWER);
    core.risk.last_price_cache.insert(SYMBOL, LastPriceCacheRecord::with_mark(1));
    core.risk.loan_service.global_config.numeraire_currency = QUOTE;

    let (rc, _) = submit(&mut core, cmd_pool_deposit(1, QUOTE, 1_000_000));
    assert_eq!(rc, CommandResultCode::Success);
    assert_loan_invariants(&core);

    let (rc, _) = submit(&mut core, cmd_balance_adjustment(5, BORROWER, SELL, 2_000));
    assert_eq!(rc, CommandResultCode::Success);
    assert_loan_invariants(&core);

    let (rc, _) = submit(&mut core, cmd_loan_cross_add_collateral(10, BORROWER, SELL, 2_000, 1_000));
    assert_eq!(rc, CommandResultCode::Success);
    assert_loan_invariants(&core);

    let (rc, _) = submit(&mut core, cmd_loan_cross_borrow(11, BORROWER, SYMBOL, 1, 400, 1_000));
    assert_eq!(rc, CommandResultCode::Success);
    assert_loan_invariants(&core);
    let (rc, _) = submit(&mut core, cmd_loan_cross_borrow(12, BORROWER, SYMBOL, 2, 500, 1_000));
    assert_eq!(rc, CommandResultCode::Success);
    assert_loan_invariants(&core);
    assert_eq!(core.ups.get(BORROWER).unwrap().cross_loans.len(), 2);

    let before = core.ups.get(BORROWER).unwrap().cross_loan_collateral(SELL);
    let (rc, _) = submit(&mut core, cmd_loan_cross_withdraw_collateral(13, BORROWER, SELL, 1_200, 1_000));
    assert_eq!(rc, CommandResultCode::LoanCrossLtvTooHighAfterWithdraw);
    assert_loan_invariants(&core);
    assert_eq!(core.ups.get(BORROWER).unwrap().cross_loan_collateral(SELL), before, "rejected withdraw must revert");

    let (rc, _) = submit(&mut core, cmd_loan_cross_withdraw_collateral(14, BORROWER, SELL, 500, 1_000));
    assert_eq!(rc, CommandResultCode::Success);
    assert_loan_invariants(&core);
    assert_eq!(core.ups.get(BORROWER).unwrap().cross_loan_collateral(SELL), before - 500);

    let (rc, _) = submit(&mut core, cmd_loan_cross_repay(15, BORROWER, 1, 0, 1_000));
    assert_eq!(rc, CommandResultCode::Success);
    assert_loan_invariants(&core);
    let (rc, _) = submit(&mut core, cmd_loan_cross_repay(16, BORROWER, 2, 0, 1_000));
    assert_eq!(rc, CommandResultCode::Success);
    assert_loan_invariants(&core);
    assert!(core.ups.get(BORROWER).unwrap().cross_loans.is_empty());
    assert_eq!(core.risk.loan_service.get_loan_pool_borrowed(QUOTE), 0);
}

fn isolated_force_liquidate_world() -> (ExchangeCore, i32, i32, i32, i64, i64) {
    const BASE: i32 = 1;
    const QUOTE: i32 = 2;
    const SYMBOL: i32 = 100;
    const BORROWER: i64 = 10;
    const MAKER: i64 = 20;

    let mut core = ExchangeCore::new();
    core.ssp.add_currency(CoreCurrencySpecification { currency: BASE, currency_scale_k: 1, ..Default::default() });
    core.ssp.add_currency(CoreCurrencySpecification { currency: QUOTE, currency_scale_k: 1, ..Default::default() });
    let spec = spot_spec(SYMBOL, BASE, QUOTE);
    assert_eq!(core.ssp.add_symbol(spec.clone()), CommandResultCode::Success);
    core.matching.add_symbol(&spec);
    core.ups.add_empty_user_profile(BORROWER);
    core.ups.add_empty_user_profile(MAKER);
    (core, BASE, QUOTE, SYMBOL, BORROWER, MAKER)
}

#[allow(clippy::too_many_arguments)]
fn open_isolated_loan_direct(core: &mut ExchangeCore, borrower: i64, quote: i32, base: i32, symbol: i32, loan_id: i64, collateral: i64, principal: i64, rate_bps: i32, opened_at_ts: i64, fund_order_id: i64) {
    let (rc, _) = submit(core, cmd_pool_deposit(1, quote, 1_000_000));
    assert_eq!(rc, CommandResultCode::Success);
    let (rc, _) = submit(core, cmd_balance_adjustment(fund_order_id, borrower, base, collateral));
    assert_eq!(rc, CommandResultCode::Success);
    {
        let up = core.ups.get_mut(borrower).unwrap();
        let mut loan = IsolatedLoanRecord::new(borrower, loan_id, symbol, base, quote, rate_bps, opened_at_ts);
        loan.outstanding_principal = principal;
        loan.collateral_amount = collateral;
        up.isolated_loans.insert(loan_id, loan);
    }
    let up = core.ups.get_mut(borrower).unwrap();
    core.risk.loan_service.disburse_loan(up, quote, principal);
}

fn rest_maker_bid(core: &mut ExchangeCore, maker: i64, symbol: i32, order_id: i64, price: i64, size: i64, quote: i32, fund_order_id: i64) {
    let (rc, _) = submit(core, cmd_balance_adjustment(fund_order_id, maker, quote, 1_000_000_000));
    assert_eq!(rc, CommandResultCode::Success);
    let (rc, _) = submit(core, cmd_place_order(order_id, maker, symbol, price, size, OrderAction::Bid, OrderType::Gtc, 1_000));
    assert_eq!(rc, CommandResultCode::Success);
}

#[test]
fn scenario_isolated_force_liquidate_full_fill() {
    let (mut core, _base, quote, symbol, borrower, maker) = isolated_force_liquidate_world();
    const LOAN_ID: i64 = 42;

    open_isolated_loan_direct(&mut core, borrower, quote, 1, symbol, LOAN_ID, 1_000, 500, 0, 1_000, 5);
    assert_loan_invariants(&core);
    rest_maker_bid(&mut core, maker, symbol, 1, 1, 2_000, quote, 6);
    assert_loan_invariants(&core);

    let (rc, _) = submit(&mut core, cmd_loan_force_liquidate(2, borrower, symbol, LOAN_ID, 1, 1_000, 2_000));
    assert_eq!(rc, CommandResultCode::Success);
    assert_loan_invariants(&core);

    let up = core.ups.get(borrower).unwrap();
    assert!(!up.isolated_loans.contains_key(&LOAN_ID));
    assert_eq!(up.account(1), 0);
    assert_eq!(core.risk.loan_service.get_loan_insurance_fund(quote), 20);
    assert_eq!(core.risk.loan_service.get_loan_pool_borrowed(quote), 0);
}

#[test]
fn scenario_isolated_force_liquidate_lif_takeover_undercollateralized() {
    let (mut core, _base, quote, symbol, borrower, _maker) = isolated_force_liquidate_world();
    const LOAN_ID: i64 = 42;
    const YEAR_MS: i64 = 365 * 24 * 3600 * 1_000;

    open_isolated_loan_direct(&mut core, borrower, quote, 1, symbol, LOAN_ID, 1_000, 500, 1_000, 1_000, 5);
    assert_loan_invariants(&core);

    let (rc, _) = submit(&mut core, cmd_loan_force_liquidate(1, borrower, symbol, LOAN_ID, 1, 1_000, 1_000 + YEAR_MS));
    assert_eq!(rc, CommandResultCode::Success);
    assert_loan_invariants(&core);

    let up = core.ups.get(borrower).unwrap();
    assert!(!up.isolated_loans.contains_key(&LOAN_ID), "taken over -> removed");
    assert_eq!(up.account(1), 0, "collateral physically taken by LIF");
    assert_eq!(core.risk.loan_service.get_loan_insurance_fund(quote), -550);
    assert_eq!(core.risk.loan_service.get_loan_insurance_fund(1), 1_000);
    assert_eq!(core.risk.loan_service.get_interest_revenue(quote), 50);
}

#[test]
fn scenario_pool_and_if_ops() {
    const QUOTE: i32 = 2;
    let mut core = ExchangeCore::new();
    core.ssp.add_currency(CoreCurrencySpecification { currency: QUOTE, currency_scale_k: 1, ..Default::default() });
    assert_loan_invariants(&core);

    let (rc, _) = submit(&mut core, cmd_pool_deposit(1, QUOTE, 1_000));
    assert_eq!(rc, CommandResultCode::Success);
    assert_loan_invariants(&core);
    assert_eq!(core.risk.loan_service.get_loan_pool_available(QUOTE), 1_000);
    assert_eq!(*core.risk.adjustments.get(&QUOTE).unwrap(), -1_000);

    let (rc, _) = submit(&mut core, cmd_pool_withdraw(2, QUOTE, 5_000));
    assert_eq!(rc, CommandResultCode::LoanPoolInsufficient);
    assert_loan_invariants(&core);
    assert_eq!(core.risk.loan_service.get_loan_pool_available(QUOTE), 1_000);

    let (rc, _) = submit(&mut core, cmd_pool_withdraw(3, QUOTE, 400));
    assert_eq!(rc, CommandResultCode::Success);
    assert_loan_invariants(&core);
    assert_eq!(core.risk.loan_service.get_loan_pool_available(QUOTE), 600);

    let (rc, _) = submit(&mut core, cmd_loan_if_deposit(4, QUOTE, 300));
    assert_eq!(rc, CommandResultCode::Success);
    assert_loan_invariants(&core);
    assert_eq!(core.risk.loan_service.get_loan_insurance_fund(QUOTE), 300);

    let (rc, _) = submit(&mut core, cmd_loan_if_withdraw(5, QUOTE, 900));
    assert_eq!(rc, CommandResultCode::LoanIfInsufficient);
    assert_loan_invariants(&core);

    let (rc, _) = submit(&mut core, cmd_loan_if_withdraw(6, QUOTE, 300));
    assert_eq!(rc, CommandResultCode::Success);
    assert_loan_invariants(&core);
    assert_eq!(core.risk.loan_service.get_loan_insurance_fund(QUOTE), 0);
}

#[test]
fn scenario_reprice_then_accrue_then_repay() {
    const SELL: i32 = 1;
    const QUOTE: i32 = 2;
    const SYMBOL: i32 = 101;
    const BORROWER: i64 = 10;
    const LOAN_ID: i64 = 1;
    const YEAR_MS: i64 = 365 * 24 * 3600 * 1_000;

    let mut core = ExchangeCore::new();
    core.ssp.add_currency(CoreCurrencySpecification { currency: SELL, currency_scale_k: 1, collateral_weight_bps: 10_000, ..Default::default() });
    core.ssp.add_currency(CoreCurrencySpecification { currency: QUOTE, currency_scale_k: 1, ..Default::default() });
    let mut spec = spot_spec(SYMBOL, SELL, QUOTE);
    spec.loan_config.update(5_000, 8_000, 0, 0, 0);
    assert_eq!(core.ssp.add_symbol(spec.clone()), CommandResultCode::Success);
    core.matching.add_symbol(&spec);
    core.ups.add_empty_user_profile(BORROWER);
    core.risk.last_price_cache.insert(SYMBOL, LastPriceCacheRecord::with_mark(1));
    core.risk.loan_service.global_config.numeraire_currency = QUOTE;

    let (rc, _) = submit(&mut core, cmd_pool_deposit(1, QUOTE, 1_000));
    assert_eq!(rc, CommandResultCode::Success);

    let (rc, _) = submit(&mut core, cmd_balance_adjustment(5, BORROWER, SELL, 2_000));
    assert_eq!(rc, CommandResultCode::Success);
    let (rc, _) = submit(&mut core, cmd_loan_cross_add_collateral(10, BORROWER, SELL, 2_000, 1_000));
    assert_eq!(rc, CommandResultCode::Success);
    assert_loan_invariants(&core);

    let (rc, _) = submit(&mut core, cmd_reprice(1_000));
    assert_eq!(rc, CommandResultCode::Success);
    assert_eq!(*core.risk.loan_service.floating_rate.current_rate_bps.get(&QUOTE).unwrap(), 200);

    let (rc, _) = submit(&mut core, cmd_loan_cross_borrow(11, BORROWER, SYMBOL, LOAN_ID, 900, 1_000));
    assert_eq!(rc, CommandResultCode::Success);
    assert_loan_invariants(&core);
    let rate_before_reprice = core.ups.get(BORROWER).unwrap().cross_loans.get(&LOAN_ID).unwrap().rate_bps;
    assert_eq!(rate_before_reprice, 200);

    let (rc, reprice_cmd) = submit(&mut core, cmd_reprice(1_000 + YEAR_MS));
    assert_eq!(rc, CommandResultCode::Success);
    assert_loan_invariants(&core);
    let new_rate = *core.risk.loan_service.floating_rate.current_rate_bps.get(&QUOTE).unwrap();
    assert!(new_rate > 200, "90% utilization should push the rate above the base 2%: got {new_rate}");
    drop(reprice_cmd);

    let interest_after_first_year = {
        let loan = core.ups.get(BORROWER).unwrap().cross_loans.get(&LOAN_ID).unwrap();
        core.risk.loan_service.calculate_display_interest(loan, 1_000 + YEAR_MS)
    };
    assert_eq!(interest_after_first_year, 18);

    let expected_segment2 = (900i64 * new_rate) / 10_000;
    let interest_after_second_year = {
        let loan = core.ups.get(BORROWER).unwrap().cross_loans.get(&LOAN_ID).unwrap();
        core.risk.loan_service.calculate_display_interest(loan, 1_000 + 2 * YEAR_MS)
    };
    assert_eq!(interest_after_second_year, 18 + expected_segment2, "second year accrues at the repriced rate");

    let (rc, _) = submit(&mut core, cmd_balance_adjustment(13, BORROWER, QUOTE, interest_after_second_year));
    assert_eq!(rc, CommandResultCode::Success);
    assert_loan_invariants(&core);

    let (rc, _) = submit(&mut core, cmd_loan_cross_repay(14, BORROWER, LOAN_ID, 0, 1_000 + 2 * YEAR_MS));
    assert_eq!(rc, CommandResultCode::Success);
    assert_loan_invariants(&core);
    assert!(core.ups.get(BORROWER).unwrap().cross_loans.is_empty());
    assert_eq!(core.risk.loan_service.get_interest_revenue(QUOTE), interest_after_second_year);
}

#[test]
fn scenario_cross_force_liquidate_multi_loan_takeover_sweeps_in_ascending_order() {
    const SELL1: i32 = 1;
    const QUOTE: i32 = 2;
    const SELL2: i32 = 3;
    const NUM: i32 = 4;
    const SYM_TARGET: i32 = 100;
    const SYM_DEBT_NUM: i32 = 101;
    const SYM_COLLAT_NUM: i32 = 102;
    const BORROWER: i64 = 10;
    const MAKER: i64 = 20;
    const TARGET_ID: i64 = 42;

    let mut core = ExchangeCore::new();
    core.ssp.add_currency(CoreCurrencySpecification { currency: SELL1, currency_scale_k: 1, ..Default::default() });
    core.ssp.add_currency(CoreCurrencySpecification { currency: QUOTE, currency_scale_k: 1, ..Default::default() });
    core.ssp.add_currency(CoreCurrencySpecification { currency: SELL2, currency_scale_k: 1, collateral_weight_bps: 10_000, ..Default::default() });
    core.ssp.add_currency(CoreCurrencySpecification { currency: NUM, currency_scale_k: 1, ..Default::default() });

    let target_spec = spot_spec(SYM_TARGET, SELL1, QUOTE);
    assert_eq!(core.ssp.add_symbol(target_spec.clone()), CommandResultCode::Success);
    core.matching.add_symbol(&target_spec);
    assert_eq!(core.ssp.add_symbol(spot_spec(SYM_DEBT_NUM, QUOTE, NUM)), CommandResultCode::Success);
    assert_eq!(core.ssp.add_symbol(spot_spec(SYM_COLLAT_NUM, SELL2, NUM)), CommandResultCode::Success);

    core.ups.add_empty_user_profile(BORROWER);
    core.ups.add_empty_user_profile(MAKER);
    core.risk.last_price_cache.insert(SYM_TARGET, LastPriceCacheRecord::with_mark(1));
    core.risk.last_price_cache.insert(SYM_DEBT_NUM, LastPriceCacheRecord::with_mark(1));
    core.risk.last_price_cache.insert(SYM_COLLAT_NUM, LastPriceCacheRecord::with_mark(1));
    core.risk.loan_service.global_config.numeraire_currency = NUM;
    core.risk.loan_service.global_config.loan_liquidation_fee_bps = 0;

    let (rc, _) = submit(&mut core, cmd_pool_deposit(1, QUOTE, 1_000_000));
    assert_eq!(rc, CommandResultCode::Success);
    let (rc, _) = submit(&mut core, cmd_balance_adjustment(3, BORROWER, SELL1, 100));
    assert_eq!(rc, CommandResultCode::Success);
    {
        let up = core.ups.get_mut(BORROWER).unwrap();
        up.add_to_cross_loan_collateral(SELL1, 100);
        let mut target = CrossLoanRecord::new(BORROWER, TARGET_ID, SYM_TARGET, QUOTE, 0, 1_000);
        target.outstanding_principal = 100;
        up.cross_loans.insert(TARGET_ID, target);
    }
    let up = core.ups.get_mut(BORROWER).unwrap();
    core.risk.loan_service.disburse_loan(up, QUOTE, 100);

    let (rc, _) = submit(&mut core, cmd_balance_adjustment(4, BORROWER, SELL2, 999));
    assert_eq!(rc, CommandResultCode::Success);
    {
        let up = core.ups.get_mut(BORROWER).unwrap();
        up.add_to_cross_loan_collateral(SELL2, 999);
        let mut loan90 = CrossLoanRecord::new(BORROWER, 90, SYM_TARGET, QUOTE, 0, 1_000);
        loan90.outstanding_principal = 700;
        up.cross_loans.insert(90, loan90);
        let mut loan50 = CrossLoanRecord::new(BORROWER, 50, SYM_TARGET, QUOTE, 0, 1_000);
        loan50.outstanding_principal = 300;
        up.cross_loans.insert(50, loan50);
    }
    core.risk.loan_service.add_to_loan_pool_borrowed(QUOTE, 700 + 300);

    rest_maker_bid(&mut core, MAKER, SYM_TARGET, 1, 1, 100, QUOTE, 5);
    assert_loan_invariants(&core);

    let before_quote = 0i64;
    let _ = before_quote;

    let (rc, _) = submit(&mut core, cmd_loan_cross_force_liquidate(2, BORROWER, SYM_TARGET, TARGET_ID, 1, 100, 2_000));
    assert_eq!(rc, CommandResultCode::Success);
    assert_loan_invariants(&core);

    let up = core.ups.get(BORROWER).unwrap();
    assert!(up.cross_loans.is_empty(), "target + both out-of-order-inserted remaining loans all swept exactly once");
    assert_eq!(up.cross_loan_collateral(SELL1), 0);
    assert_eq!(up.cross_loan_collateral(SELL2), 0, "entire SELL2 pool consumed across the two sequential takeovers");
    assert_eq!(up.account(SELL1), 0);
    assert_eq!(up.account(SELL2), 0);

    assert_eq!(core.risk.loan_service.get_loan_insurance_fund(QUOTE), -(300 + 700));
    assert_eq!(core.risk.loan_service.get_loan_insurance_fund(SELL2), 999);
    assert_eq!(core.risk.loan_service.get_loan_pool_borrowed(QUOTE), 0);
    assert_eq!(core.risk.loan_service.get_interest_revenue(QUOTE), 0);
}

#[derive(Debug, Clone)]
enum GenLoanCmd {
    Create { uid_idx: usize, loan_id: i64, collateral: i64, principal: i64, floating: bool },
    Repay { uid_idx: usize, loan_id: i64, amount: i64 },
    AddCollateral { uid_idx: usize, loan_id: i64, amount: i64 },
    ReleaseCollateral { uid_idx: usize, loan_id: i64, amount: i64 },
    CrossAdd { uid_idx: usize, amount: i64 },
    CrossWithdraw { uid_idx: usize, amount: i64 },
    CrossBorrow { uid_idx: usize, loan_id: i64, principal: i64 },
    CrossRepay { uid_idx: usize, loan_id: i64, amount: i64 },
    PoolDeposit { currency_idx: usize, amount: i64 },
    PoolWithdraw { currency_idx: usize, amount: i64 },
    IfDeposit { currency_idx: usize, amount: i64 },
    IfWithdraw { currency_idx: usize, amount: i64 },
    ForceLiquidate { uid_idx: usize, loan_id: i64, lots: i64, full_drain: bool },
    CrossForceLiquidate { uid_idx: usize, loan_id: i64, lots: i64 },
    Reprice,
    SetMarkPrice { on_cross_symbol: bool, price: i64 },
    AdvanceTime { delta_ms: i64 },
}

fn gen_loan_cmd(n_users: usize) -> impl Strategy<Value = GenLoanCmd> {
    let loan_id_space = 0i64..8;
    let amount_space = 1i64..=2_000;
    let create = (0..n_users, loan_id_space.clone(), amount_space.clone(), amount_space.clone(), any::<bool>())
        .prop_map(|(uid_idx, loan_id, collateral, principal, floating)| GenLoanCmd::Create {
            uid_idx,
            loan_id,
            collateral,
            principal,
            floating,
        });
    let repay = (0..n_users, loan_id_space.clone(), 0i64..=2_000)
        .prop_map(|(uid_idx, loan_id, amount)| GenLoanCmd::Repay { uid_idx, loan_id, amount });
    let add_collateral = (0..n_users, loan_id_space.clone(), amount_space.clone())
        .prop_map(|(uid_idx, loan_id, amount)| GenLoanCmd::AddCollateral { uid_idx, loan_id, amount });
    let release_collateral = (0..n_users, loan_id_space.clone(), amount_space.clone())
        .prop_map(|(uid_idx, loan_id, amount)| GenLoanCmd::ReleaseCollateral { uid_idx, loan_id, amount });
    let cross_add = (0..n_users, amount_space.clone())
        .prop_map(|(uid_idx, amount)| GenLoanCmd::CrossAdd { uid_idx, amount });
    let cross_withdraw = (0..n_users, amount_space.clone())
        .prop_map(|(uid_idx, amount)| GenLoanCmd::CrossWithdraw { uid_idx, amount });
    let cross_borrow = (0..n_users, loan_id_space.clone(), amount_space.clone())
        .prop_map(|(uid_idx, loan_id, principal)| GenLoanCmd::CrossBorrow { uid_idx, loan_id, principal });
    let cross_repay = (0..n_users, loan_id_space.clone(), 0i64..=2_000)
        .prop_map(|(uid_idx, loan_id, amount)| GenLoanCmd::CrossRepay { uid_idx, loan_id, amount });
    let pool_deposit =
        (0usize..3, 1i64..=5_000).prop_map(|(currency_idx, amount)| GenLoanCmd::PoolDeposit { currency_idx, amount });
    let pool_withdraw =
        (0usize..3, 1i64..=5_000).prop_map(|(currency_idx, amount)| GenLoanCmd::PoolWithdraw { currency_idx, amount });
    let if_deposit =
        (0usize..3, 1i64..=5_000).prop_map(|(currency_idx, amount)| GenLoanCmd::IfDeposit { currency_idx, amount });
    let if_withdraw =
        (0usize..3, 1i64..=5_000).prop_map(|(currency_idx, amount)| GenLoanCmd::IfWithdraw { currency_idx, amount });
    let force_liquidate = (0..n_users, loan_id_space.clone(), 1i64..=2_000, prop::bool::weighted(0.4))
        .prop_map(|(uid_idx, loan_id, lots, full_drain)| GenLoanCmd::ForceLiquidate { uid_idx, loan_id, lots, full_drain });
    let cross_force_liquidate = (0..n_users, loan_id_space, 1i64..=2_000)
        .prop_map(|(uid_idx, loan_id, lots)| GenLoanCmd::CrossForceLiquidate { uid_idx, loan_id, lots });
    let reprice = Just(GenLoanCmd::Reprice);
    let set_mark_price =
        (any::<bool>(), 1i64..=1_000).prop_map(|(on_cross_symbol, price)| GenLoanCmd::SetMarkPrice { on_cross_symbol, price });
    let advance_time = (0i64..=30 * 24 * 3_600_000).prop_map(|delta_ms| GenLoanCmd::AdvanceTime { delta_ms });

    prop_oneof![
        3 => create,
        3 => repay,
        2 => add_collateral,
        2 => release_collateral,
        2 => cross_add,
        2 => cross_withdraw,
        3 => cross_borrow,
        3 => cross_repay,
        1 => pool_deposit,
        1 => pool_withdraw,
        1 => if_deposit,
        1 => if_withdraw,
        2 => force_liquidate,
        2 => cross_force_liquidate,
        1 => reprice,
        2 => set_mark_price,
        2 => advance_time,
    ]
}

const PT_BASE: i32 = 1;
const PT_QUOTE: i32 = 2;
const PT_SELL: i32 = 3;
const PT_SYMBOL: i32 = 100;
const PT_SYMBOL_CROSS: i32 = 101;
const PT_CURRENCIES: [i32; 3] = [PT_BASE, PT_QUOTE, PT_SELL];

fn proptest_world(n_users: usize) -> (ExchangeCore, Vec<i64>) {
    let mut core = ExchangeCore::new();
    core.ssp.add_currency(CoreCurrencySpecification { currency: PT_BASE, currency_scale_k: 1, ..Default::default() });
    core.ssp.add_currency(CoreCurrencySpecification { currency: PT_QUOTE, currency_scale_k: 1, ..Default::default() });
    core.ssp.add_currency(CoreCurrencySpecification { currency: PT_SELL, currency_scale_k: 1, collateral_weight_bps: 10_000, ..Default::default() });

    let mut isolated_spec = spot_spec(PT_SYMBOL, PT_BASE, PT_QUOTE);
    isolated_spec.loan_config.update(5_000, 8_000, 0, 0, 0);
    core.ssp.add_symbol(isolated_spec.clone());
    core.matching.add_symbol(&isolated_spec);

    let mut cross_spec = spot_spec(PT_SYMBOL_CROSS, PT_SELL, PT_QUOTE);
    cross_spec.loan_config.update(5_000, 8_000, 0, 0, 0);
    core.ssp.add_symbol(cross_spec.clone());
    core.matching.add_symbol(&cross_spec);

    core.risk.last_price_cache.insert(PT_SYMBOL, LastPriceCacheRecord::with_mark(50));
    core.risk.last_price_cache.insert(PT_SYMBOL_CROSS, LastPriceCacheRecord::with_mark(50));
    core.risk.loan_service.global_config.numeraire_currency = PT_QUOTE;
    let (rc, _) = submit(&mut core, cmd_pool_deposit(1, PT_QUOTE, 1_000_000_000));
    assert_eq!(rc, CommandResultCode::Success);

    let mut fund_order_id: i64 = 1;
    let uids: Vec<i64> = (1..=n_users as i64).collect();
    for &uid in &uids {
        core.ups.add_empty_user_profile(uid);
        for &cur in &[PT_BASE, PT_QUOTE, PT_SELL] {
            let (rc, _) = submit(&mut core, cmd_balance_adjustment(fund_order_id, uid, cur, 1_000_000));
            fund_order_id += 1;
            assert_eq!(rc, CommandResultCode::Success);
        }
    }

    const MAKER: i64 = 9_000;
    core.ups.add_empty_user_profile(MAKER);
    let (rc, _) = submit(&mut core, cmd_balance_adjustment(fund_order_id, MAKER, PT_QUOTE, 1_000_000_000_000));
    assert_eq!(rc, CommandResultCode::Success);
    let (rc, _) = submit(&mut core, cmd_place_order(1_000_000, MAKER, PT_SYMBOL, 1, 1_000_000, OrderAction::Bid, OrderType::Gtc, 0));
    assert_eq!(rc, CommandResultCode::Success);
    let (rc, _) = submit(&mut core, cmd_place_order(1_000_001, MAKER, PT_SYMBOL_CROSS, 1, 1_000_000, OrderAction::Bid, OrderType::Gtc, 0));
    assert_eq!(rc, CommandResultCode::Success);

    (core, uids)
}

fn scenario_strategy() -> impl Strategy<Value = (usize, Vec<GenLoanCmd>)> {
    (2usize..=4).prop_flat_map(|n_users| {
        let cmds = prop::collection::vec(gen_loan_cmd(n_users), 20..80);
        (Just(n_users), cmds)
    })
}

proptest! {
    #![proptest_config(ProptestConfig::with_cases(200))]

    #[test]
    fn loan_conservation_holds_for_random_command_stream(
        (n_users, cmds) in scenario_strategy()
    ) {
        let (mut core, uids) = proptest_world(n_users);
        assert_loan_invariants(&core);

        let mut now: i64 = 1_000;
        let mut next_order_id: i64 = 1_000;

        for gen_cmd in &cmds {
            let order_id = next_order_id;
            next_order_id += 1;

            match gen_cmd {
                GenLoanCmd::Create { uid_idx, loan_id, collateral, principal, floating } => {
                    let uid = uids[*uid_idx];
                    let _ = submit(&mut core, cmd_loan_create(order_id, uid, PT_SYMBOL, *loan_id, *collateral, *principal, *floating, now));
                }
                GenLoanCmd::Repay { uid_idx, loan_id, amount } => {
                    let uid = uids[*uid_idx];
                    let _ = submit(&mut core, cmd_loan_repay(order_id, uid, *loan_id, *amount, now));
                }
                GenLoanCmd::AddCollateral { uid_idx, loan_id, amount } => {
                    let uid = uids[*uid_idx];
                    let _ = submit(&mut core, cmd_loan_add_collateral(order_id, uid, *loan_id, *amount, now));
                }
                GenLoanCmd::ReleaseCollateral { uid_idx, loan_id, amount } => {
                    let uid = uids[*uid_idx];
                    let _ = submit(&mut core, cmd_loan_release_collateral(order_id, uid, *loan_id, *amount, now));
                }
                GenLoanCmd::CrossAdd { uid_idx, amount } => {
                    let uid = uids[*uid_idx];
                    let _ = submit(&mut core, cmd_loan_cross_add_collateral(order_id, uid, PT_SELL, *amount, now));
                }
                GenLoanCmd::CrossWithdraw { uid_idx, amount } => {
                    let uid = uids[*uid_idx];
                    let _ = submit(&mut core, cmd_loan_cross_withdraw_collateral(order_id, uid, PT_SELL, *amount, now));
                }
                GenLoanCmd::CrossBorrow { uid_idx, loan_id, principal } => {
                    let uid = uids[*uid_idx];
                    let _ = submit(&mut core, cmd_loan_cross_borrow(order_id, uid, PT_SYMBOL_CROSS, *loan_id, *principal, now));
                }
                GenLoanCmd::CrossRepay { uid_idx, loan_id, amount } => {
                    let uid = uids[*uid_idx];
                    let _ = submit(&mut core, cmd_loan_cross_repay(order_id, uid, *loan_id, *amount, now));
                }
                GenLoanCmd::PoolDeposit { currency_idx, amount } => {
                    let cur = PT_CURRENCIES[*currency_idx];
                    let _ = submit(&mut core, cmd_pool_deposit(order_id, cur, *amount));
                }
                GenLoanCmd::PoolWithdraw { currency_idx, amount } => {
                    let cur = PT_CURRENCIES[*currency_idx];
                    let _ = submit(&mut core, cmd_pool_withdraw(order_id, cur, *amount));
                }
                GenLoanCmd::IfDeposit { currency_idx, amount } => {
                    let cur = PT_CURRENCIES[*currency_idx];
                    let _ = submit(&mut core, cmd_loan_if_deposit(order_id, cur, *amount));
                }
                GenLoanCmd::IfWithdraw { currency_idx, amount } => {
                    let cur = PT_CURRENCIES[*currency_idx];
                    let _ = submit(&mut core, cmd_loan_if_withdraw(order_id, cur, *amount));
                }
                GenLoanCmd::ForceLiquidate { uid_idx, loan_id, lots, full_drain } => {
                    let uid = uids[*uid_idx];
                    let effective_lots = if *full_drain {
                        core.ups.get(uid).and_then(|up| up.isolated_loans.get(loan_id)).and_then(|loan| {
                            let spec = core.ssp.get_symbol(PT_SYMBOL)?;
                            let base_spec = core.ssp.get_currency(PT_BASE)?;
                            let full = LoanService::collateral_amount_to_lots(loan.collateral_amount, spec, base_spec);
                            (full > 0).then_some(full)
                        })
                    } else {
                        None
                    }
                    .unwrap_or(*lots);
                    let _ = submit(&mut core, cmd_loan_force_liquidate(order_id, uid, PT_SYMBOL, *loan_id, 1, effective_lots, now));
                }
                GenLoanCmd::CrossForceLiquidate { uid_idx, loan_id, lots } => {
                    let uid = uids[*uid_idx];
                    let _ = submit(&mut core, cmd_loan_cross_force_liquidate(order_id, uid, PT_SYMBOL_CROSS, *loan_id, 1, *lots, now));
                }
                GenLoanCmd::Reprice => {
                    let _ = submit(&mut core, cmd_reprice(now));
                }
                GenLoanCmd::SetMarkPrice { on_cross_symbol, price } => {
                    let symbol = if *on_cross_symbol { PT_SYMBOL_CROSS } else { PT_SYMBOL };
                    core.risk.last_price_cache.insert(symbol, LastPriceCacheRecord::with_mark(*price));
                }
                GenLoanCmd::AdvanceTime { delta_ms } => {
                    now += *delta_ms;
                }
            }

            assert_loan_invariants(&core);
        }
    }
}
