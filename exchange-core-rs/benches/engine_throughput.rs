//! 纯引擎吞吐基准（**绕开 Raft**）：直接对 `ExchangeCore::process_command` 灌命令，测单线程撮合引擎的裸吞吐。
//!
//! 不涉及共识/网络/落盘——这是引擎能力的**上限**，部署形态下会被 Raft 共识层封顶到低得多的量级。
//!
//! 自带 `main`（Cargo.toml `harness = false`），零额外依赖，用 `std::time::Instant` 计时。
//! 跑：`cargo bench --bench engine_throughput`（release + lto，见 `[profile.release]`）。
//!
//! 三个场景，各测稳态 ops/sec：
//!   A. place-only    —— 只挂单不撮合，压 `order_id_index`(BTreeMap) 插入 + 簿内挂单（簿持续增长）。
//!   B. match         —— 对手盘吃单，压撮合主循环 + 成交事件 + 结算（R1→ME→R2 全程）。
//!   C. place+cancel  —— 挂单即撤，稳态簿深恒定，压 id_index 插入/删除 churn（最贴近真实做市）。

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

/// 建一个已加币/加符号、两个用户各充足额资金的 core（BUYER 充 QUOTE 买、SELLER 充 BASE 卖）。
fn seeded_core() -> ExchangeCore {
    let mut core = ExchangeCore::new();
    core.ssp.add_currency(CoreCurrencySpecification { currency: BASE, currency_scale_k: 1, ..Default::default() });
    core.ssp.add_currency(CoreCurrencySpecification { currency: QUOTE, currency_scale_k: 1, ..Default::default() });
    assert_eq!(core.ssp.add_symbol(spec()), CommandResultCode::Success);
    core.matching.add_symbol(&spec());
    for uid in [BUYER, SELLER] {
        core.ups.add_empty_user_profile(uid);
    }
    // 充到接近 i64 上限的一半，保证整个基准不触 NSF。
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

/// 计时 `iters` 次操作，打印 ops/sec + 每op纳秒。`op` 返回被 black_box 的结果码防优化消除。
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

/// 场景 A：只挂 BID GTC（无 ASK 对手→全部挂簿），簿持续增长。压 id_index 插入 + 挂单。
fn bench_place_only(iters: u64) {
    let mut core = seeded_core();
    // 价格在窄带内循环，size=1：簿深随挂单数增长，桶内 FIFO 链变长，贴近深簿。
    measure("A place-only (grow)", iters, |i| {
        let price = 1 + (i as i64 % 2000);
        let mut cmd = place(i as i64 + 1, BUYER, OrderAction::Bid, OrderType::Gtc, price, 1);
        core.process_command(&mut cmd);
        cmd.result_code.unwrap()
    });
}

/// 场景 B1（深单桶）：N 笔 ASK 全堆在**同一价位 100**（一个桶、深度 N 的 FIFO 链），再逐笔 IOC 吃桶头。
/// 与 B2 唯一差别是桶深——若 B1 慢而 B2 快，瓶颈就在"桶头摘除/桶内链"随桶深线性扫描。
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

/// 场景 B2（宽浅桶）：N 笔 ASK 分散到 N 个**不同价位**（每桶深度 1），taker 每次扫掉当前最低价那一档。
/// 簿内总单数 / id_index 规模与 B1 相同（都是 N），唯一变量是桶深=1。
fn bench_match_wide(iters: u64) {
    let mut core = seeded_core();
    // ASK 分散在 [100, 100+N)，每价一笔。best_ask 恒为当前最低价。
    for i in 0..iters {
        let price = 100 + i as i64;
        let mut ask = place(i as i64 + 1, SELLER, OrderAction::Ask, OrderType::Gtc, price, 1);
        core.process_command(&mut ask);
    }
    let hi = 100 + iters as i64; // 高于所有 ASK，保证能吃到当前最低价
    let mut taker_id = iters;
    measure("B2 match WIDE (N buckets)", iters, |_| {
        taker_id += 1;
        // BID IOC@hi size1：吃掉当前最低价的深度1档，该桶随即整档移除。
        let mut cmd = place(taker_id as i64 + 1, BUYER, OrderAction::Bid, OrderType::Ioc, hi, 1);
        core.process_command(&mut cmd);
        cmd.result_code.unwrap()
    });
}

/// 场景 C：挂一笔 GTC 立刻撤掉，稳态簿深≈0。压 id_index 插入+删除的 churn（做市撤挂）。
fn bench_place_cancel(iters: u64) {
    let mut core = seeded_core();
    measure("C place+cancel (churn)", iters, |i| {
        let oid = i as i64 + 1;
        // 挂一笔远离盘口的 BID（price=1，不会与任何单撮合），随即撤掉。
        let mut p = place(oid, BUYER, OrderAction::Bid, OrderType::Gtc, 1, 1);
        core.process_command(&mut p);
        let mut c = cancel(oid, BUYER);
        core.process_command(&mut c);
        c.result_code.unwrap()
    });
}

/// orderbook-only 撮合对照(绕开 router/R1/R2,只测 ME 撮合本身)：泛型跑任意 IOrderBook 实现。
/// 预铺 N 笔 ASK(deep=同价单桶 / wide=N 个不同价位)，再计时 N 笔 BID IOC 逐笔吃掉。
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
    // 可选传 A/C 快场景迭代数，默认 1_000_000。B1/B2 用固定深度阶梯（下方），因为 B1 是 O(N²) 不能放大。
    let fast_iters: u64 = std::env::args()
        .nth(1)
        .and_then(|s| s.parse().ok())
        .unwrap_or(1_000_000);

    println!("== exchange-core-rs 纯引擎吞吐基准（绕开 Raft）==");
    println!("A/C iters = {fast_iters}   （release + lto=true + codegen-units=1）\n");

    // --- 快路径 A/C：常数级，可放大 ---
    bench_place_only(50_000); // warmup
    bench_place_cancel(50_000);
    println!("-- warmup done --\n");
    bench_place_only(fast_iters);
    bench_place_cancel(fast_iters);

    // --- 撮合 B1(深单桶) vs B2(宽浅桶)：同一深度阶梯并排，看 ns/op 随簿深 N 怎么长 ---
    // 若 B1 的 ns/op 随 N 线性上升、B2 保持平稳 → 瓶颈在桶深（桶头摘除/桶内链），而非撮合本身。
    println!("\n-- 撮合扫描：B1 深单桶 vs B2 宽浅桶（同 N，唯一变量=桶深）--");
    for &n in &[5_000u64, 10_000, 20_000, 40_000] {
        bench_match_deep(n);
        bench_match_wide(n);
        println!();
    }

    // --- orderbook-only 撮合对照：Naive vs Direct(绕开 router/R1/R2，纯 ME) ---
    // 若 Naive 的 ns/op 随 N 线性上升、Direct 保持近乎平坦 → Direct 把撮合从 O(N) 拉回 O(log N)。
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
