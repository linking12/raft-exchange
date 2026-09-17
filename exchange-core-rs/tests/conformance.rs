//! Java↔Rust 黄金向量一致性(conformance)对拍。
//!
//! 一份**规范命令流** `.stream`(与实现无关的 DSL),两侧各有解释器喂各自引擎;
//! Java 侧(exchange-core `ConformanceExporter`)当 oracle 生成 `.golden`,本测试用 exchange-core-rs replay
//! **同一** `.stream`、产**同一格式**输出,逐行断言 == `.golden`。不依赖 Java 单测是否断言(直接拿 Java 引擎实际输出当黄金)。
//!
//! 输出分两段:
//!   - `R <seq> <CODE>`:每命令 result_code(v1)。
//!   - `STATE` + `A/POS/FEE`:最终状态摘要(v1,账户/仓位/费用池,排序,跳 0 值)。
//!   - `EVENTS` + `FE <TYPE> uid cur free locked`:**fund event 规范化多重集**(v2,全流累加含级联,排序)。
//!
//! **归一化(= 刻意差异清单)**:
//!   - 事件用**全流多重集**比,不比逐命令归属——清算触发时机是刻意差异(Rust markprice 定向扫 vs
//!     Java LIQUIDATION_SCAN),但"发了哪些 fund event"多重集可比。
//!   - 排除 MARGIN_ALERT / LIQUIDATION_ALERT(Rust 外置 no-op 不发,两侧都排)。
//!   - 撮合明细事件(MatcherTradeEvent)不进 v2:Java 是 SpotExecutionReport/FuturesExecutionReport 高层报告、
//!     与 Rust raw MatcherTradeEvent 抽象不同;撮合正确性已由 IT A 类逐值对拍 Java,不是缺口。
//!   - 清算/ADL 的 fund event 在 Java 走异步线程、捕获 flaky,v2 只对拍其**状态**(账户/仓位),fund event 层暂缓。

use std::collections::BTreeMap;
use std::fs;

use exchange_core_rs::core::common::cmd::command_result_code::CommandResultCode;
use exchange_core_rs::core::common::cmd::order_command::OrderCommand;
use exchange_core_rs::core::common::cmd::order_command_type::OrderCommandType;
use exchange_core_rs::core::common::core_symbol_specification::CoreSymbolSpecification;
use exchange_core_rs::core::common::fund_event::{FundEvent, FundEventType};
use exchange_core_rs::core::common::batch_add_loan_command::{BatchAddLoanCommand, GlobalLoanConfig, SymbolLoanConfig, UNSET, UNSET_AMOUNT};
use exchange_core_rs::core::common::margin_mode::MarginMode;
use exchange_core_rs::core::common::order_action::OrderAction;
use exchange_core_rs::core::common::order_type::OrderType;
use exchange_core_rs::core::common::symbol_loan_specification::SymbolLoanSpecification;
use exchange_core_rs::core::common::symbol_type::SymbolType;
use exchange_core_rs::core::exchange_api::{ExchangeApi, PlaceFuturesOrderRequest, PlaceOrderRequest};

fn parse_line(line: &str) -> Option<(String, BTreeMap<String, String>)> {
    let line = line.trim();
    if line.is_empty() || line.starts_with('#') {
        return None;
    }
    let mut it = line.split_whitespace();
    let verb = it.next()?.to_string();
    let mut kv = BTreeMap::new();
    for tok in it {
        if let Some((k, v)) = tok.split_once('=') {
            kv.insert(k.to_string(), v.to_string());
        }
    }
    Some((verb, kv))
}

fn i64_of(kv: &BTreeMap<String, String>, k: &str) -> i64 {
    kv.get(k).unwrap_or_else(|| panic!("缺字段 {k}")).parse().unwrap()
}
fn i32_of(kv: &BTreeMap<String, String>, k: &str) -> i32 {
    kv.get(k).unwrap_or_else(|| panic!("缺字段 {k}")).parse().unwrap()
}
fn opt_i64(kv: &BTreeMap<String, String>, k: &str, d: i64) -> i64 {
    kv.get(k).map(|v| v.parse().unwrap()).unwrap_or(d)
}

/// CamelCase Debug 名 → Java SCREAMING_SNAKE(result code / fund event type 通用)。
fn snake(camel: &str) -> String {
    let mut out = String::new();
    for (i, ch) in camel.chars().enumerate() {
        if ch.is_uppercase() && i != 0 {
            out.push('_');
        }
        out.push(ch.to_ascii_uppercase());
    }
    out
}

/// 期货固定档表(两侧一致,避免在 DSL 里编码 map)。
fn mm_table() -> BTreeMap<i64, i64> {
    BTreeMap::from([(1_000, 5), (100_000, 10)])
}
fn lev_table() -> BTreeMap<i64, i64> {
    BTreeMap::from([(2_000, 5), (100_000, 10)])
}

fn order_type_of(s: Option<&str>) -> OrderType {
    match s {
        Some("IOC") => OrderType::Ioc,
        Some("FOK") => OrderType::Fok,
        Some("FOK_BUDGET") => OrderType::FokBudget,
        Some("IOC_BUDGET") => OrderType::IocBudget,
        _ => OrderType::Gtc,
    }
}
fn action_of(s: Option<&str>) -> OrderAction {
    if s == Some("ASK") { OrderAction::Ask } else { OrderAction::Bid }
}
fn margin_of(s: Option<&str>) -> MarginMode {
    if s == Some("CROSS") { MarginMode::Cross } else { MarginMode::Isolated }
}

/// v2 事件白名单:只对拍**结算类** fund event(金额搬动的实质)。刻意排除:
///   - 记账/锁类(Deposit/Withdraw/Locked/Unlocked/LockPending/UnlockPending/Transfer):粒度是刻意差异
///     (如 balance_adjustment:Java 发、Rust 不发)。
///   - 仓位生命周期(OpenPosition/ClosePosition):与 STATE 的 POS 冗余,且两侧粒度不同
///     (期货开仓 Java 只对 maker 发 OPEN_POSITION、Rust 对 maker+taker 都发——钱一致、事件数不同)。
///   - alert(MarginAlert/LiquidationAlert):Rust 外置 no-op 不发。
fn fe_allowed(t: FundEventType) -> bool {
    use FundEventType::*;
    matches!(
        t,
        LiquidationClose
            | LiquidationFee
            | FundingfeeSettlement
            | PnlSettlement
            | MarginAdjust
            | MarginRefund
            | IfPositionClose
            | AdlOriginClose
            | AdlPositionClose
            | LoanBorrow
            | LoanRepay
            | LoanLiquidated
            | InternalTransfer
    )
}

/// fund event 规范化行(仅白名单结算类)。
fn fe_line(e: &FundEvent) -> Option<String> {
    if !fe_allowed(e.event_type) {
        return None;
    }
    Some(format!(
        "FE {} uid={} cur={} free={} locked={}",
        snake(&format!("{:?}", e.event_type)),
        e.uid,
        e.currency,
        e.free,
        e.locked
    ))
}

fn replay(stream: &str) -> (ExchangeApi, Vec<String>, Vec<String>) {
    let mut api = ExchangeApi::new();
    let mut results = Vec::new();
    let mut fund_lines: Vec<String> = Vec::new();
    let mut seq = 0i64;

    macro_rules! collect_events {
        () => {{
            for e in api.last_fund_events() {
                if let Some(l) = fe_line(e) { fund_lines.push(l); }
            }
            for e in api.cascade_fund_events() {
                if let Some(l) = fe_line(e) { fund_lines.push(l); }
            }
        }};
    }

    // MARK_AT/SCAN/IF_DEPOSIT/LIF_DEPOSIT 是触发/setup,不发 R 行(对齐 Java 把它们当 setup);其余真实命令发 R。
    let no_r = |v: &str| matches!(v, "MARK_AT" | "SCAN" | "IF_DEPOSIT" | "LIF_DEPOSIT");
    for line in stream.lines() {
        let Some((verb, kv)) = parse_line(line) else { continue };
        let rc: Option<CommandResultCode> = match verb.as_str() {
            "CUR" => {
                api.add_currency(i32_of(&kv, "id"), 10i64.pow(i32_of(&kv, "digit") as u32));
                None
            }
            "SYM_SPOT" => {
                // 可选 loan 配置(带 initialLtv 时启用现货借贷);5 字段与 Java SymbolLoanSpecification 逐一对齐,
                // 未给的字段两侧默认 0(0=未启用/无上限/无期限),故向量显式给全避免默认漂移。
                let loan_config = SymbolLoanSpecification {
                    initial_ltv_bps: opt_i64(&kv, "initialLtv", 0) as i32,
                    liquidation_ltv_bps: opt_i64(&kv, "liqLtv", 0) as i32,
                    margin_call_ltv_bps: opt_i64(&kv, "marginCallLtv", 0) as i32,
                    max_amount: opt_i64(&kv, "maxAmount", 0),
                    max_term_days: opt_i64(&kv, "maxTermDays", 0) as i32,
                };
                assert_eq!(api.add_symbol(CoreSymbolSpecification {
                    symbol_id: i32_of(&kv, "id"),
                    symbol_type: SymbolType::CurrencyExchangePair,
                    base_currency: i32_of(&kv, "base"),
                    quote_currency: i32_of(&kv, "quote"),
                    base_scale_k: i64_of(&kv, "baseScale"),
                    quote_scale_k: i64_of(&kv, "quoteScale"),
                    taker_fee: i64_of(&kv, "taker"),
                    maker_fee: i64_of(&kv, "maker"),
                    loan_config,
                    ..Default::default()
                }), CommandResultCode::Success);
                None
            }
            "SYM_FUT" => {
                let kind = if kv.get("kind").map(String::as_str) == Some("DELIVERY") {
                    SymbolType::FuturesContractDelivery
                } else {
                    SymbolType::FuturesContractPerpetual
                };
                assert_eq!(api.add_futures_symbol(CoreSymbolSpecification {
                    symbol_id: i32_of(&kv, "id"),
                    symbol_type: kind,
                    base_currency: i32_of(&kv, "base"),
                    quote_currency: i32_of(&kv, "quote"),
                    base_scale_k: i64_of(&kv, "baseScale"),
                    quote_scale_k: i64_of(&kv, "quoteScale"),
                    taker_fee: i64_of(&kv, "taker"),
                    maker_fee: i64_of(&kv, "maker"),
                    fee_scale_k: opt_i64(&kv, "feeScale", 0),
                    init_margin: opt_i64(&kv, "initMargin", 1),
                    init_margin_scale_k: opt_i64(&kv, "initMarginScaleK", 100),
                    maintenance_margin: mm_table(),
                    maintenance_margin_scale_k: 1_000,
                    max_leverage: lev_table(),
                    ..Default::default()
                }), CommandResultCode::Success);
                None
            }
            "MARK" => {
                assert_eq!(api.set_mark_price(i32_of(&kv, "sym"), i64_of(&kv, "price")), CommandResultCode::Success);
                None
            }
            "MARK_AT" => Some(api.set_mark_price(i32_of(&kv, "sym"), i64_of(&kv, "price"))),
            "ENABLE_LIQ" => {
                api.enable_liquidation();
                None
            }
            "USER" => Some(api.add_user(i64_of(&kv, "uid"))),
            "BAL" => Some(api.balance_adjustment(i64_of(&kv, "uid"), i32_of(&kv, "cur"), i64_of(&kv, "amount"), i64_of(&kv, "txid"))),
            "PLACE" => Some(api.place_order(PlaceOrderRequest {
                order_id: i64_of(&kv, "oid"),
                uid: i64_of(&kv, "uid"),
                symbol: i32_of(&kv, "sym"),
                price: i64_of(&kv, "price"),
                size: i64_of(&kv, "size"),
                reserve_bid_price: opt_i64(&kv, "reserve", 0),
                action: action_of(kv.get("action").map(String::as_str)),
                order_type: order_type_of(kv.get("type").map(String::as_str)),
            })),
            "PLACE_FUT" => Some(api.place_futures_order(PlaceFuturesOrderRequest {
                order_id: i64_of(&kv, "oid"),
                uid: i64_of(&kv, "uid"),
                symbol: i32_of(&kv, "sym"),
                price: i64_of(&kv, "price"),
                size: i64_of(&kv, "size"),
                action: action_of(kv.get("action").map(String::as_str)),
                order_type: order_type_of(kv.get("type").map(String::as_str)),
                leverage: opt_i64(&kv, "leverage", 1) as i32,
                margin_mode: margin_of(kv.get("margin").map(String::as_str)),
                reduce_only: false,
            })),
            "SCAN" => Some(api.submit(OrderCommand {
                command: OrderCommandType::LiquidationScan,
                symbol: -1,
                uid: opt_i64(&kv, "slice", 0),
                size: opt_i64(&kv, "sliceCount", 0),
                timestamp: opt_i64(&kv, "ts", 0),
                ..Default::default()
            })),
            "IF_DEPOSIT" => Some(api.submit(OrderCommand {
                command: OrderCommandType::IfDeposit,
                symbol: i32_of(&kv, "sym"),
                price: i64_of(&kv, "amount"),
                order_id: i64_of(&kv, "txid"),
                ..Default::default()
            })),
            "SETTLE_PNL" => Some(api.submit(OrderCommand {
                command: OrderCommandType::SettlePnl,
                symbol: i32_of(&kv, "sym"),
                price: i64_of(&kv, "price"),
                order_id: opt_i64(&kv, "txid", 0),
                ..Default::default()
            })),
            "SETTLE_FUNDING" => Some(api.submit(OrderCommand {
                command: OrderCommandType::SettleFundingfees,
                symbol: i32_of(&kv, "sym"),
                action: Some(action_of(kv.get("action").map(String::as_str))),
                price: i64_of(&kv, "rate"),
                size: i64_of(&kv, "rateScaleK"),
                order_id: opt_i64(&kv, "txid", 0),
                ..Default::default()
            })),
            // HEDGE:切换持仓模式(hedge=1 双向 / 0 单向),对应 Java ApiAdjustPositionMode。
            "POS_MODE" => Some(api.adjust_position_mode(i64_of(&kv, "uid"), i64_of(&kv, "hedge") != 0)),
            // loan 池注资:cmd.symbol=loan 币种、cmd.size=金额,对应 Java ApiPoolDeposit(currency/amount)。
            "POOL_DEPOSIT" => Some(api.submit(OrderCommand {
                command: OrderCommandType::PoolDeposit,
                symbol: i32_of(&kv, "cur"),
                size: i64_of(&kv, "amount"),
                order_id: opt_i64(&kv, "txid", 0),
                ..Default::default()
            })),
            // isolated loan 开仓:reserveBidPrice=loanId / size=collateral / price=principal / userCookie=rateMode。
            "LOAN_CREATE" => Some(api.submit(OrderCommand {
                command: OrderCommandType::LoanCreate,
                uid: i64_of(&kv, "uid"),
                symbol: i32_of(&kv, "sym"),
                reserve_bid_price: i64_of(&kv, "loanId"),
                size: i64_of(&kv, "collateral"),
                price: i64_of(&kv, "principal"),
                user_cookie: opt_i64(&kv, "rateMode", 0) as i32,
                order_id: opt_i64(&kv, "txid", 0),
                timestamp: opt_i64(&kv, "ts", 0),
                ..Default::default()
            })),
            // isolated loan 还款:reserveBidPrice=loanId / price=repayAmount。
            "LOAN_REPAY" => Some(api.submit(OrderCommand {
                command: OrderCommandType::LoanRepay,
                uid: i64_of(&kv, "uid"),
                reserve_bid_price: i64_of(&kv, "loanId"),
                price: i64_of(&kv, "repay"),
                order_id: opt_i64(&kv, "txid", 0),
                timestamp: opt_i64(&kv, "ts", 0),
                ..Default::default()
            })),
            // 全局 loan 运行时配置(numeraire/cross LTV 阈值等),对应 Java BatchAddLoanCommand.ofGlobal*。
            // 直接 facade(不经命令管线,与 CUR/SYM_SPOT 同),不发 R;未给字段=0=不改(partial-update)。
            "LOAN_GLOBAL" => {
                api.add_loan(BatchAddLoanCommand {
                    global: Some(GlobalLoanConfig {
                        numeraire_currency: opt_i64(&kv, "numeraire", 0) as i32,
                        cross_liquidation_ltv_bps: opt_i64(&kv, "crossLiqLtv", 0) as i32,
                        cross_margin_call_ltv_bps: opt_i64(&kv, "crossMcLtv", 0) as i32,
                        loan_pool_utilization_cap_bps: opt_i64(&kv, "poolCap", 0) as i32,
                        loan_liquidation_fee_bps: opt_i64(&kv, "liqFee", 0) as i32,
                        ltv_liquidation_buffer_bps: opt_i64(&kv, "liqBuf", 0) as i32,
                        ltv_margin_call_buffer_bps: opt_i64(&kv, "mcBuf", 0) as i32,
                    }),
                    symbol: None,
                    rate_curve: None,
                });
                None
            }
            // per-symbol loan 配置(含 collateralWeight → 落到 base 币),对应 Java BatchAddLoanCommand.ofSymbol。
            // 直接 facade,不发 R;省略字段用 UNSET(-1)派生。cross loan 抵押估值必须先设 collateralWeight。
            "LOAN_SYMBOL" => {
                api.add_loan(BatchAddLoanCommand {
                    global: None,
                    symbol: Some(SymbolLoanConfig {
                        symbol_id: i32_of(&kv, "sym"),
                        loan_initial_ltv_bps: opt_i64(&kv, "initialLtv", 0) as i32,
                        loan_liquidation_ltv_bps: opt_i64(&kv, "liqLtv", UNSET as i64) as i32,
                        loan_margin_call_ltv_bps: opt_i64(&kv, "marginCallLtv", UNSET as i64) as i32,
                        loan_max_amount: opt_i64(&kv, "maxAmount", UNSET_AMOUNT),
                        loan_max_term_days: opt_i64(&kv, "maxTermDays", UNSET as i64) as i32,
                        collateral_weight_bps: opt_i64(&kv, "collateralWeight", UNSET as i64) as i32,
                    }),
                    rate_curve: None,
                });
                None
            }
            // cross loan 抵押注资:cmd.symbol=currency / size=amount,对应 Java ApiLoanCrossAddCollateral。
            "LOAN_CROSS_ADD_COLLATERAL" => Some(api.submit(OrderCommand {
                command: OrderCommandType::LoanCrossAddCollateral,
                uid: i64_of(&kv, "uid"),
                symbol: i32_of(&kv, "cur"),
                size: i64_of(&kv, "amount"),
                order_id: opt_i64(&kv, "txid", 0),
                timestamp: opt_i64(&kv, "ts", 0),
                ..Default::default()
            })),
            // cross loan 抵押提取。
            "LOAN_CROSS_WITHDRAW_COLLATERAL" => Some(api.submit(OrderCommand {
                command: OrderCommandType::LoanCrossWithdrawCollateral,
                uid: i64_of(&kv, "uid"),
                symbol: i32_of(&kv, "cur"),
                size: i64_of(&kv, "amount"),
                order_id: opt_i64(&kv, "txid", 0),
                timestamp: opt_i64(&kv, "ts", 0),
                ..Default::default()
            })),
            // cross loan 借款:symbol=计息 symbol / price=principal / reserveBidPrice=loanId。
            "LOAN_CROSS_BORROW" => Some(api.submit(OrderCommand {
                command: OrderCommandType::LoanCrossBorrow,
                uid: i64_of(&kv, "uid"),
                symbol: i32_of(&kv, "sym"),
                price: i64_of(&kv, "principal"),
                reserve_bid_price: i64_of(&kv, "loanId"),
                order_id: opt_i64(&kv, "txid", 0),
                timestamp: opt_i64(&kv, "ts", 0),
                ..Default::default()
            })),
            // cross loan 还款:price=repayAmount / reserveBidPrice=loanId。
            "LOAN_CROSS_REPAY" => Some(api.submit(OrderCommand {
                command: OrderCommandType::LoanCrossRepay,
                uid: i64_of(&kv, "uid"),
                price: i64_of(&kv, "repay"),
                reserve_bid_price: i64_of(&kv, "loanId"),
                order_id: opt_i64(&kv, "txid", 0),
                timestamp: opt_i64(&kv, "ts", 0),
                ..Default::default()
            })),
            // loan 保险基金(LIF)注资:cmd.symbol=currency / size=amount,对应 Java ApiLoanIfDeposit。运维 setup,不发 R。
            "LIF_DEPOSIT" => Some(api.submit(OrderCommand {
                command: OrderCommandType::LoanIfDeposit,
                symbol: i32_of(&kv, "cur"),
                size: i64_of(&kv, "amount"),
                order_id: opt_i64(&kv, "txid", 0),
                ..Default::default()
            })),
            other => panic!("未支持的命令 verb: {other}"),
        };
        if let Some(rc) = rc {
            if !no_r(verb.as_str()) {
                results.push(format!("R {seq} {}", snake(&format!("{rc:?}"))));
            }
            collect_events!(); // 命令已走 run/submit(last_cmd 就位)后收集事件
        }
        seq += 1;
    }
    fund_lines.sort();
    (api, results, fund_lines)
}

fn state_digest(api: &ExchangeApi) -> Vec<String> {
    let mut out = Vec::new();
    let mut uids: Vec<i64> = api.ups().users.keys().copied().collect();
    uids.sort_unstable();
    for uid in uids {
        let p = api.ups().get(uid).unwrap();
        let mut curs: Vec<i32> = p.accounts.keys().copied().collect();
        curs.sort_unstable();
        for c in curs {
            let a = p.account(c);
            if a != 0 {
                out.push(format!("A {uid} {c} {a}"));
            }
        }
        let mut syms: Vec<i32> = p.positions.values().filter(|r| r.open_volume != 0).map(|r| r.symbol).collect();
        syms.sort_unstable();
        syms.dedup();
        for s in syms {
            // HEDGE 同 symbol 可有 LONG/SHORT 两腿:按方向名排序,两侧口径一致(单腿向量下为 no-op)。
            let mut legs: Vec<(String, i64, i64)> = p
                .positions
                .values()
                .filter(|r| r.symbol == s && r.open_volume != 0)
                .map(|r| (format!("{:?}", r.direction).to_uppercase(), r.open_volume, r.open_price_sum))
                .collect();
            legs.sort();
            for (dir, vol, sum) in legs {
                out.push(format!("POS {uid} {s} {dir} {vol} {sum}"));
            }
        }
    }
    let mut curs: Vec<i32> = api.ssp().currencies.keys().copied().collect();
    curs.sort_unstable();
    for c in curs {
        let f = api.fees(c);
        if f != 0 {
            out.push(format!("FEE {c} {f}"));
        }
    }
    out
}

/// `.stream` 首部含 `#!events=off` 时只对拍 result+state(清算/ADL 向量用:Java fund event 走异步、捕获 flaky)。
fn events_enabled(stream: &str) -> bool {
    !stream.lines().any(|l| l.trim_start_matches('#').trim() == "!events=off")
}

fn rust_output(stream: &str) -> String {
    let (api, results, fund_lines) = replay(stream);
    let mut lines = results;
    lines.push("STATE".to_string());
    lines.extend(state_digest(&api));
    if events_enabled(stream) {
        lines.push("EVENTS".to_string());
        lines.extend(fund_lines);
    }
    lines.join("\n") + "\n"
}

#[test]
fn conformance_golden_vectors() {
    // 默认对拍入库向量;live-diff 编排(conformance_live_diff.sh)用 CONFORMANCE_VECTORS_DIR 指向临时目录跑新鲜随机流。
    let dir = std::env::var("CONFORMANCE_VECTORS_DIR")
        .unwrap_or_else(|_| concat!(env!("CARGO_MANIFEST_DIR"), "/tests/conformance_vectors").to_string());
    let entries = match fs::read_dir(&dir) {
        Ok(e) => e,
        Err(_) => {
            eprintln!("无 conformance_vectors 目录,跳过");
            return;
        }
    };
    let mut checked = 0;
    let mut names: Vec<_> = entries
        .flatten()
        .map(|e| e.path())
        .filter(|p| p.extension().and_then(|e| e.to_str()) == Some("stream"))
        .collect();
    names.sort();
    for path in names {
        let golden_path = path.with_extension("golden");
        let stream = fs::read_to_string(&path).unwrap();
        let expected = fs::read_to_string(&golden_path)
            .unwrap_or_else(|_| panic!("缺 golden: {}(先用 Java ConformanceExporter 生成)", golden_path.display()));
        let actual = rust_output(&stream);
        assert_eq!(
            actual.trim_end(),
            expected.trim_end(),
            "\n向量 {} 的 Rust 输出与 Java golden 不一致",
            path.file_name().unwrap().to_string_lossy()
        );
        checked += 1;
    }
    assert!(checked > 0, "conformance_vectors 里没有 .stream 向量");
}
