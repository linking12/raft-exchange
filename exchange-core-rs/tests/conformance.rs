//! Java↔Rust 黄金向量对拍框架(以 Java 为 oracle)。
//!
//! 本文件没有对应的单个 Java 测试类,而是与
//! `exchange-core/src/test/java/exchange/core2/tests/conformance/ConformanceExporter.java`
//! 配套的 Rust 侧回放器:读取 `tests/conformance_vectors/*.stream` 文本 DSL 脚本,
//! 在 Rust 引擎上重放同一套命令序列,并将结果(命令返回码 + 账户/仓位/手续费状态
//! + 资金事件)与 Java `ConformanceExporter` 预先生成的 `*.golden` 文件逐行对拍,
//! 验证两侧引擎行为完全一致。
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
use exchange_core_rs::core::exchange_api::{ExchangeApi, MarginAdjustmentRequest, PlaceFuturesOrderRequest, PlaceOrderRequest};

// 解析向量文件的一行 DSL:首个 token 是命令 verb,其余 `key=value` token 收集为键值表;空行/`#` 注释行跳过。
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
    kv.get(k).unwrap_or_else(|| panic!("missing field {k}")).parse().unwrap()
}
fn i32_of(kv: &BTreeMap<String, String>, k: &str) -> i32 {
    kv.get(k).unwrap_or_else(|| panic!("missing field {k}")).parse().unwrap()
}
fn opt_i64(kv: &BTreeMap<String, String>, k: &str, d: i64) -> i64 {
    kv.get(k).map(|v| v.parse().unwrap()).unwrap_or(d)
}

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

// golden 输出只收录这些资金事件类型(与 Java ConformanceExporter 收录的类型集合对齐)。
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
            | MarginAlert
            | LiquidationAlert
            | LoanMarginCall
    )
}

// 将一条资金事件格式化为 golden 文件里的一行文本。
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

// 核心回放器:逐行解析 DSL 命令,依次提交给 Rust 引擎,记录每条命令的返回码
// 和沿途产生的资金事件,返回最终引擎状态 + 结果行 + 排序后的事件行。
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

    let no_r = |v: &str| matches!(v, "MARK_AT" | "SCAN" | "IF_DEPOSIT" | "LIF_DEPOSIT");
    for line in stream.lines() {
        let Some((verb, kv)) = parse_line(line) else { continue };
        let rc: Option<CommandResultCode> = match verb.as_str() {
            "CUR" => {
                api.add_currency(i32_of(&kv, "id"), 10i64.pow(i32_of(&kv, "digit") as u32));
                None
            }
            "SYM_SPOT" => {
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
                reduce_only: opt_i64(&kv, "reduceOnly", 0) != 0,
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
            "MARGIN_ADJUST" => Some(api.margin_adjustment(MarginAdjustmentRequest {
                uid: i64_of(&kv, "uid"),
                symbol: i32_of(&kv, "sym"),
                action: action_of(kv.get("action").map(String::as_str)),
                amount: i64_of(&kv, "amount"),
                margin_mode: margin_of(kv.get("margin").map(String::as_str)),
                order_id: opt_i64(&kv, "txid", 0),
            })),
            "POS_MODE" => Some(api.adjust_position_mode(i64_of(&kv, "uid"), i64_of(&kv, "hedge") != 0)),
            "POOL_DEPOSIT" => Some(api.submit(OrderCommand {
                command: OrderCommandType::PoolDeposit,
                symbol: i32_of(&kv, "cur"),
                size: i64_of(&kv, "amount"),
                order_id: opt_i64(&kv, "txid", 0),
                ..Default::default()
            })),
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
            "LOAN_REPAY" => Some(api.submit(OrderCommand {
                command: OrderCommandType::LoanRepay,
                uid: i64_of(&kv, "uid"),
                reserve_bid_price: i64_of(&kv, "loanId"),
                price: i64_of(&kv, "repay"),
                order_id: opt_i64(&kv, "txid", 0),
                timestamp: opt_i64(&kv, "ts", 0),
                ..Default::default()
            })),
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
            "LOAN_CROSS_ADD_COLLATERAL" => Some(api.submit(OrderCommand {
                command: OrderCommandType::LoanCrossAddCollateral,
                uid: i64_of(&kv, "uid"),
                symbol: i32_of(&kv, "cur"),
                size: i64_of(&kv, "amount"),
                order_id: opt_i64(&kv, "txid", 0),
                timestamp: opt_i64(&kv, "ts", 0),
                ..Default::default()
            })),
            "LOAN_CROSS_WITHDRAW_COLLATERAL" => Some(api.submit(OrderCommand {
                command: OrderCommandType::LoanCrossWithdrawCollateral,
                uid: i64_of(&kv, "uid"),
                symbol: i32_of(&kv, "cur"),
                size: i64_of(&kv, "amount"),
                order_id: opt_i64(&kv, "txid", 0),
                timestamp: opt_i64(&kv, "ts", 0),
                ..Default::default()
            })),
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
            "LOAN_CROSS_REPAY" => Some(api.submit(OrderCommand {
                command: OrderCommandType::LoanCrossRepay,
                uid: i64_of(&kv, "uid"),
                price: i64_of(&kv, "repay"),
                reserve_bid_price: i64_of(&kv, "loanId"),
                order_id: opt_i64(&kv, "txid", 0),
                timestamp: opt_i64(&kv, "ts", 0),
                ..Default::default()
            })),
            "LIF_DEPOSIT" => Some(api.submit(OrderCommand {
                command: OrderCommandType::LoanIfDeposit,
                symbol: i32_of(&kv, "cur"),
                size: i64_of(&kv, "amount"),
                order_id: opt_i64(&kv, "txid", 0),
                ..Default::default()
            })),
            other => panic!("unsupported command verb: {other}"),
        };
        if let Some(rc) = rc {
            if !no_r(verb.as_str()) {
                results.push(format!("R {seq} {}", snake(&format!("{rc:?}"))));
            }
            collect_events!();
        }
        seq += 1;
    }
    fund_lines.sort();
    (api, results, fund_lines)
}

// 把回放结束后的引擎状态(非零余额、非零仓位、非零手续费)序列化成确定顺序的文本行,供 golden 对拍。
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
            let mut legs: Vec<(String, i64, i64, i64, i64)> = p
                .positions
                .values()
                .filter(|r| r.symbol == s && r.open_volume != 0)
                .map(|r| {
                    (format!("{:?}", r.direction).to_uppercase(), r.open_volume, r.open_price_sum, r.open_init_margin_sum, r.extra_margin)
                })
                .collect();
            legs.sort();
            for (dir, vol, sum, im, em) in legs {
                out.push(format!("POS {uid} {s} {dir} {vol} {sum} {im} {em}"));
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

// 向量文件里的 `#!events=off` 指令可关闭事件行对拍(部分向量不关心事件顺序)。
fn events_enabled(stream: &str) -> bool {
    !stream.lines().any(|l| l.trim_start_matches('#').trim() == "!events=off")
}

// 拼出与 Java golden 文件同格式的完整输出:命令结果行 + STATE 状态段 + (可选)EVENTS 事件段。
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

// 遍历 tests/conformance_vectors/*.stream,对每个向量回放并与同名 .golden 逐行比对。
#[test]
fn conformance_golden_vectors() {
    let dir = std::env::var("CONFORMANCE_VECTORS_DIR")
        .unwrap_or_else(|_| concat!(env!("CARGO_MANIFEST_DIR"), "/tests/conformance_vectors").to_string());
    let entries = match fs::read_dir(&dir) {
        Ok(e) => e,
        Err(_) => {
            eprintln!("no conformance_vectors directory, skipping");
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
            .unwrap_or_else(|_| panic!("missing golden: {} (generate it first with Java ConformanceExporter)", golden_path.display()));
        let actual = rust_output(&stream);
        assert_eq!(
            actual.trim_end(),
            expected.trim_end(),
            "\nRust output for vector {} does not match Java golden",
            path.file_name().unwrap().to_string_lossy()
        );
        checked += 1;
    }
    assert!(checked > 0, "no .stream vectors found in conformance_vectors");
}
