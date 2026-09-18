use std::hint::black_box;
use std::time::Instant;

use exchange_core_rs::core::common::cmd::command_result_code::CommandResultCode;
use exchange_core_rs::core::common::cmd::order_command::OrderCommand;
use exchange_core_rs::core::common::cmd::order_command_type::OrderCommandType;
use exchange_core_rs::core::common::core_currency_specification::CoreCurrencySpecification;
use exchange_core_rs::core::common::core_symbol_specification::CoreSymbolSpecification;
use exchange_core_rs::core::common::order_action::OrderAction;
use exchange_core_rs::core::common::order_type::OrderType;
use exchange_core_rs::core::common::symbol_type::SymbolType;
use exchange_core_rs::core::exchange_core::ExchangeCore;
use exchange_core_rs::core::orderbook::i_order_book::IOrderBook;
use exchange_core_rs::core::orderbook::order_book_direct_impl::OrderBookDirectImpl;
use exchange_core_rs::core::orderbook::order_book_naive_impl::OrderBookNaiveImpl;

const SYMBOL: i32 = 1;
const BASE: i32 = 10;
const QUOTE: i32 = 20;
const BUYER: i64 = 1;
const SELLER: i64 = 2;

fn spec() -> CoreSymbolSpecification {
    CoreSymbolSpecification {
        symbol_id: SYMBOL,
        symbol_type: SymbolType::CurrencyExchangePair,
        base_currency: BASE,
        quote_currency: QUOTE,
        base_scale_k: 1,
        quote_scale_k: 1,
        taker_fee: 0,
        maker_fee: 0,
        fee_scale_k: 0,
        ..Default::default()
    }
}

fn seeded_core() -> ExchangeCore {
    let mut core = ExchangeCore::new();
    core.ssp.add_currency(CoreCurrencySpecification { currency: BASE, currency_scale_k: 1, ..Default::default() });
    core.ssp.add_currency(CoreCurrencySpecification { currency: QUOTE, currency_scale_k: 1, ..Default::default() });
    assert_eq!(core.ssp.add_symbol(spec()), CommandResultCode::Success);
    core.matching.add_symbol(&spec());
    for uid in [BUYER, SELLER] {
        core.ups.add_empty_user_profile(uid);
    }

    core.ups.get_mut(BUYER).unwrap().add_to_account(QUOTE, i64::MAX / 4);
    core.ups.get_mut(SELLER).unwrap().add_to_account(BASE, i64::MAX / 4);
    core
}

fn place(order_id: i64, uid: i64, action: OrderAction, order_type: OrderType, price: i64, size: i64) -> OrderCommand {
    OrderCommand {
        command: OrderCommandType::PlaceOrder,
        order_id,
        symbol: SYMBOL,
        price,
        size,
        reserve_bid_price: if action == OrderAction::Bid { price } else { 0 },
        action: Some(action),
        order_type: Some(order_type),
        uid,
        ..Default::default()
    }
}

fn cancel(order_id: i64, uid: i64) -> OrderCommand {
    OrderCommand { command: OrderCommandType::CancelOrder, order_id, symbol: SYMBOL, uid, ..Default::default() }
}

fn measure(name: &str, iters: u64, mut op: impl FnMut(u64) -> CommandResultCode) {
    let t0 = Instant::now();
    for i in 0..iters {
        black_box(op(i));
    }
    let dt = t0.elapsed();
    let ops = iters as f64 / dt.as_secs_f64();
    let ns = dt.as_nanos() as f64 / iters as f64;
    println!(
        "{:<24} {:>12} ops  {:>8.1} ms  {:>12.0} ops/sec  {:>7.1} ns/op",
        name,
        iters,
        dt.as_secs_f64() * 1e3,
        ops,
        ns,
    );
}

fn bench_place_only(iters: u64) {
    let mut core = seeded_core();

    measure("A place-only (grow)", iters, |i| {
        let price = 1 + (i as i64 % 2000);
        let mut cmd = place(i as i64 + 1, BUYER, OrderAction::Bid, OrderType::Gtc, price, 1);
        core.process_command(&mut cmd);
        cmd.result_code.unwrap()
    });
}

fn bench_match_deep(iters: u64) {
    let mut core = seeded_core();
    for i in 0..iters {
        let mut ask = place(i as i64 + 1, SELLER, OrderAction::Ask, OrderType::Gtc, 100, 1);
        core.process_command(&mut ask);
    }
    let mut taker_id = iters;
    measure("B1 match DEEP (1 bucket)", iters, |_| {
        taker_id += 1;
        let mut cmd = place(taker_id as i64 + 1, BUYER, OrderAction::Bid, OrderType::Ioc, 100, 1);
        core.process_command(&mut cmd);
        cmd.result_code.unwrap()
    });
}

fn bench_match_wide(iters: u64) {
    let mut core = seeded_core();

    for i in 0..iters {
        let price = 100 + i as i64;
        let mut ask = place(i as i64 + 1, SELLER, OrderAction::Ask, OrderType::Gtc, price, 1);
        core.process_command(&mut ask);
    }
    let hi = 100 + iters as i64;
    let mut taker_id = iters;
    measure("B2 match WIDE (N buckets)", iters, |_| {
        taker_id += 1;

        let mut cmd = place(taker_id as i64 + 1, BUYER, OrderAction::Bid, OrderType::Ioc, hi, 1);
        core.process_command(&mut cmd);
        cmd.result_code.unwrap()
    });
}

fn bench_place_cancel(iters: u64) {
    let mut core = seeded_core();
    measure("C place+cancel (churn)", iters, |i| {
        let oid = i as i64 + 1;

        let mut p = place(oid, BUYER, OrderAction::Bid, OrderType::Gtc, 1, 1);
        core.process_command(&mut p);
        let mut c = cancel(oid, BUYER);
        core.process_command(&mut c);
        c.result_code.unwrap()
    });
}

fn bench_book<B: IOrderBook>(label: &str, book: &mut B, n: u64, deep: bool) {
    for i in 0..n {
        let price = if deep { 100 } else { 100 + i as i64 };
        let mut ask = place(i as i64 + 1, SELLER, OrderAction::Ask, OrderType::Gtc, price, 1);
        book.new_order(&mut ask);
    }
    let hi = if deep { 100 } else { 100 + n as i64 };
    let mut taker_id = n;
    measure(label, n, |_| {
        taker_id += 1;
        let mut cmd = place(taker_id as i64 + 1, BUYER, OrderAction::Bid, OrderType::Ioc, hi, 1);
        book.new_order(&mut cmd);
        cmd.result_code.unwrap_or(CommandResultCode::Success)
    });
}

fn main() {

    let fast_iters: u64 = std::env::args()
        .nth(1)
        .and_then(|s| s.parse().ok())
        .unwrap_or(1_000_000);

    println!("== exchange-core-rs 纯引擎吞吐基准（绕开 Raft）==");
    println!("A/C iters = {fast_iters}   （release + lto=true + codegen-units=1）\n");

    bench_place_only(50_000);
    bench_place_cancel(50_000);
    println!("-- warmup done --\n");
    bench_place_only(fast_iters);
    bench_place_cancel(fast_iters);

    println!("\n-- 撮合扫描：B1 深单桶 vs B2 宽浅桶（同 N，唯一变量=桶深）--");
    for &n in &[5_000u64, 10_000, 20_000, 40_000] {
        bench_match_deep(n);
        bench_match_wide(n);
        println!();
    }

    println!("\n-- ME-only 撮合对照：Naive vs Direct(纯 orderbook，wide=N 价档) --");
    for &n in &[5_000u64, 10_000, 20_000, 40_000] {
        let mut naive = OrderBookNaiveImpl::with_symbol_spec(spec());
        bench_book("  Naive WIDE", &mut naive, n, false);
        let mut direct = OrderBookDirectImpl::with_symbol_spec(spec());
        bench_book("  Direct WIDE", &mut direct, n, false);
        println!();
    }
    println!("-- ME-only 撮合对照：deep=单桶 N 深 --");
    for &n in &[5_000u64, 10_000, 20_000, 40_000] {
        let mut naive = OrderBookNaiveImpl::with_symbol_spec(spec());
        bench_book("  Naive DEEP", &mut naive, n, true);
        let mut direct = OrderBookDirectImpl::with_symbol_spec(spec());
        bench_book("  Direct DEEP", &mut direct, n, true);
        println!();
    }

    println!(
        "注：A/C 是引擎常数级快路径；B 看 ns/op 随 N 的斜率判断是否 O(N)。\n\
         以上是**引擎裸吞吐上限**（单线程、全内存、无共识）；部署时每条命令还要过 Raft 复制，\n\
         系统实际吞吐会被共识层封顶到低一两个数量级。"
    );
}
