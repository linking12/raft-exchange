//! 翻译自 Java `exchange.core2.tests.integration.ITOpenCloseFeeIntegration`（5 个 @Test）。
//! 端到端验证开仓 / 平仓 maker/taker 手续费的 5 个核心不变量。
//!
//! Java 版通过 `ExchangeTestContainer.totalBalanceReport()` 取 `fees` 桶 + 逐用户 `profile.getAccounts()`；
//! Rust `ExchangeApi` 等价地暴露 `api.fees(cur)` + `api.user_account(uid,cur)` + `api.user_position(uid,symbol)`。
//!
//! 期货 symbol 逐字复刻 Java `ExchangeTestContainer.initFutureSymbol(symbolId=2, quoteId=840)`：
//!   base=BASE_CURRENCY_ID(1) quote=USD(840), maker_fee=10 taker_fee=20, fee_scale_k=0（固定费，不依赖 price），
//!   init_margin=1/init_margin_scale_k=100, maintenance/leverage 分档表。
//! createBid/createAsk 默认 MarginMode::ISOLATED、leverage 未设（0 -> 归一 1）。
//!
//! 全 5 个 @Test 均已翻译（无强平 / 无 funding / 无 report 派生字段依赖）。

#[cfg(test)]
mod tests {
    use std::collections::BTreeMap;

    use exchange_core_rs::core::common::cmd::command_result_code::CommandResultCode;
    use exchange_core_rs::core::common::core_symbol_specification::CoreSymbolSpecification;
    use exchange_core_rs::core::common::margin_mode::MarginMode;
    use exchange_core_rs::core::common::order_action::OrderAction;
    use exchange_core_rs::core::common::order_type::OrderType;
    use exchange_core_rs::core::common::symbol_type::SymbolType;
    use exchange_core_rs::core::exchange_api::{ExchangeApi, PlaceFuturesOrderRequest};

    // Java `ITOpenCloseFeeIntegration` 常量 + `TestConstants`。
    const BASE_CURRENCY_ID: i32 = 1;
    const SYMBOL_ID: i32 = 2;
    const QUOTE_ID: i32 = 840; // CURRENECY_USD
    const DEPOSIT: i64 = 1_000_000;
    const PRICE: i64 = 10_000;

    const UID_1: i64 = 1_440_001;
    const UID_2: i64 = 1_440_002;

    // initFutureSymbol 写死的费率（固定费）。
    const MAKER_FEE_PER_CONTRACT: i64 = 10;
    const TAKER_FEE_PER_CONTRACT: i64 = 20;

    /// 逐字复刻 Java `initFutureSymbol(symbolId=2, quoteId=840)`。
    fn futures_spec() -> CoreSymbolSpecification {
        CoreSymbolSpecification {
            symbol_id: SYMBOL_ID,
            symbol_type: SymbolType::FuturesContractPerpetual,
            base_currency: BASE_CURRENCY_ID,
            quote_currency: QUOTE_ID,
            base_scale_k: 1,
            quote_scale_k: 1,
            maker_fee: MAKER_FEE_PER_CONTRACT,
            taker_fee: TAKER_FEE_PER_CONTRACT,
            fee_scale_k: 0, // 固定费（Java 未设 feeScaleK -> 默认 0）
            maintenance_margin: BTreeMap::from([(1_000, 5), (100_000, 10)]),
            maintenance_margin_scale_k: 1_000,
            max_leverage: BTreeMap::from([(2_000, 5), (100_000, 10)]),
            init_margin: 1,
            init_margin_scale_k: 100,
            ..Default::default()
        }
    }

    /// 对应 Java `freshContainer()`：建 currencies + 期货 symbol + 设 mark 价 PRICE。
    fn fresh_api() -> ExchangeApi {
        let mut api = ExchangeApi::new();
        api.add_currency(BASE_CURRENCY_ID, 1);
        api.add_currency(QUOTE_ID, 1);
        assert_eq!(api.add_futures_symbol(futures_spec()), CommandResultCode::Success);
        assert_eq!(api.set_mark_price(SYMBOL_ID, PRICE), CommandResultCode::Success);
        api
    }

    /// 对应 `createUserWithSpecificMoney(uid, money, quoteId)`。
    fn seed_user(api: &mut ExchangeApi, uid: i64, money: i64, txid: i64) {
        assert_eq!(api.add_user(uid), CommandResultCode::Success);
        assert_eq!(api.balance_adjustment(uid, QUOTE_ID, money, txid), CommandResultCode::Success);
    }

    /// 对应 `createBidWithOrderId`：ONEWAY / ISOLATED / leverage 0（归一 1）/ GTC。
    fn bid(api: &mut ExchangeApi, order_id: i64, uid: i64, size: i64) -> CommandResultCode {
        place(api, order_id, uid, size, OrderAction::Bid)
    }
    /// 对应 `createAskWithOrderId`。
    fn ask(api: &mut ExchangeApi, order_id: i64, uid: i64, size: i64) -> CommandResultCode {
        place(api, order_id, uid, size, OrderAction::Ask)
    }
    fn place(api: &mut ExchangeApi, order_id: i64, uid: i64, size: i64, action: OrderAction) -> CommandResultCode {
        api.place_futures_order(PlaceFuturesOrderRequest {
            order_id,
            uid,
            symbol: SYMBOL_ID,
            price: PRICE,
            size,
            action,
            order_type: OrderType::Gtc,
            leverage: 1,
            margin_mode: MarginMode::Isolated,
            reduce_only: false,
        })
    }

    /// 全局守恒（完整公式，含仓位 estimate_pnl + extra_margin），逐币种。
    fn assert_conserved(api: &ExchangeApi) {
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
            assert_eq!(total, 0, "期货全局守恒被打破：currency={cur} total={total}");
        }
    }

    // ============================================================================================
    // 1. pureOpen_chargesMakerAndTakerFee —— 纯开仓：maker 付 makerFee×size、taker 付 takerFee×size。
    // ============================================================================================

    #[test]
    fn pure_open_charges_maker_and_taker_fee() {
        let mut api = fresh_api();
        seed_user(&mut api, UID_1, DEPOSIT, 1);
        seed_user(&mut api, UID_2, DEPOSIT, 2);

        let size = 5i64;
        // maker BID（UID_1 挂 resting），taker ASK（UID_2 吃）。
        assert_eq!(bid(&mut api, 101, UID_1, size), CommandResultCode::Success);
        assert_eq!(ask(&mut api, 102, UID_2, size), CommandResultCode::Success);

        let expected_maker_fee = MAKER_FEE_PER_CONTRACT * size; // 50
        let expected_taker_fee = TAKER_FEE_PER_CONTRACT * size; // 100

        assert_eq!(api.user_account(UID_1, QUOTE_ID), DEPOSIT - expected_maker_fee, "maker 开仓后扣 makerFee");
        assert_eq!(api.user_account(UID_2, QUOTE_ID), DEPOSIT - expected_taker_fee, "taker 开仓后扣 takerFee");
        assert_eq!(api.fees(QUOTE_ID), expected_maker_fee + expected_taker_fee, "fees bucket = makerFee + takerFee");
        assert_conserved(&api);
    }

    // ============================================================================================
    // 2. pureClose_chargesMakerAndTakerFee_atSameRateAsOpen —— 开→平同费率；同价位（价差 0）。
    // ============================================================================================

    #[test]
    fn pure_close_charges_maker_and_taker_fee_at_same_rate_as_open() {
        let mut api = fresh_api();
        seed_user(&mut api, UID_1, DEPOSIT, 1);
        seed_user(&mut api, UID_2, DEPOSIT, 2);

        let size = 5i64;
        // 开仓：UID_1 maker BID, UID_2 taker ASK。
        assert_eq!(bid(&mut api, 101, UID_1, size), CommandResultCode::Success);
        assert_eq!(ask(&mut api, 102, UID_2, size), CommandResultCode::Success);
        // 平仓（反向，同价位）：UID_1 maker ASK, UID_2 taker BID。
        assert_eq!(ask(&mut api, 103, UID_1, size), CommandResultCode::Success);
        assert_eq!(bid(&mut api, 104, UID_2, size), CommandResultCode::Success);

        // UID_1 一直 maker（开 + 关），UID_2 一直 taker（开 + 关）。
        let expected_maker_fee_total = 2 * MAKER_FEE_PER_CONTRACT * size;
        let expected_taker_fee_total = 2 * TAKER_FEE_PER_CONTRACT * size;

        assert!(api.user_position(UID_1, SYMBOL_ID).is_none(), "UID_1 仓位平完");
        assert!(api.user_position(UID_2, SYMBOL_ID).is_none(), "UID_2 仓位平完");
        assert_eq!(api.user_account(UID_1, QUOTE_ID), DEPOSIT - expected_maker_fee_total, "UID_1: 开 + 关 maker fee（价差 0）");
        assert_eq!(api.user_account(UID_2, QUOTE_ID), DEPOSIT - expected_taker_fee_total, "UID_2: 开 + 关 taker fee（价差 0）");
        assert_eq!(api.fees(QUOTE_ID), expected_maker_fee_total + expected_taker_fee_total, "fees bucket = 4 笔 fee 之和");
        assert_conserved(&api);
    }

    // ============================================================================================
    // 3. closeFee_swapsSideOnRoleSwitch —— 换边：关仓费按新角色收，不是按开仓角色。
    // ============================================================================================

    #[test]
    fn close_fee_swaps_side_on_role_switch() {
        let mut api = fresh_api();
        seed_user(&mut api, UID_1, DEPOSIT, 1);
        seed_user(&mut api, UID_2, DEPOSIT, 2);

        let size = 5i64;
        // 开仓：UID_1 maker BID, UID_2 taker ASK。
        assert_eq!(bid(&mut api, 101, UID_1, size), CommandResultCode::Success);
        assert_eq!(ask(&mut api, 102, UID_2, size), CommandResultCode::Success);
        // 关仓换边：UID_2 maker BID（先挂）, UID_1 taker ASK（吃）。
        assert_eq!(bid(&mut api, 103, UID_2, size), CommandResultCode::Success);
        assert_eq!(ask(&mut api, 104, UID_1, size), CommandResultCode::Success);

        let uid1_fee = MAKER_FEE_PER_CONTRACT * size + TAKER_FEE_PER_CONTRACT * size; // 开 maker + 关 taker
        let uid2_fee = TAKER_FEE_PER_CONTRACT * size + MAKER_FEE_PER_CONTRACT * size; // 开 taker + 关 maker

        assert_eq!(api.user_account(UID_1, QUOTE_ID), DEPOSIT - uid1_fee, "UID_1: 开 maker + 关 taker");
        assert_eq!(api.user_account(UID_2, QUOTE_ID), DEPOSIT - uid2_fee, "UID_2: 开 taker + 关 maker");
        assert_eq!(api.fees(QUOTE_ID), uid1_fee + uid2_fee, "fees bucket = 4 笔");
        assert_conserved(&api);
    }

    // ============================================================================================
    // 4. reverseFill_chargesBothCloseAndOpenFee —— 反手单：一笔成交同时关 + 开。
    // ============================================================================================

    #[test]
    fn reverse_fill_charges_both_close_and_open_fee() {
        let mut api = fresh_api();
        seed_user(&mut api, UID_1, DEPOSIT, 1);
        seed_user(&mut api, UID_2, DEPOSIT, 2);

        // UID_1 LONG 5, UID_2 SHORT 5。
        assert_eq!(bid(&mut api, 101, UID_1, 5), CommandResultCode::Success);
        assert_eq!(ask(&mut api, 102, UID_2, 5), CommandResultCode::Success);

        // UID_2 挂 BID 10 同价（maker，先关自己 SHORT 5，剩 5 张挂单未匹配）；UID_1 吃 ASK 5（taker，关 LONG 5）。
        assert_eq!(bid(&mut api, 103, UID_2, 10), CommandResultCode::Success);
        assert_eq!(ask(&mut api, 104, UID_1, 5), CommandResultCode::Success);

        // UID_1: 开 maker fee (5) + 关 taker fee (5)。
        let uid1_fee = MAKER_FEE_PER_CONTRACT * 5 + TAKER_FEE_PER_CONTRACT * 5;
        // UID_2: 开 taker fee (5) + 关 maker fee (5)；剩 5 张 BID 挂单不收 fee。
        let uid2_fee = TAKER_FEE_PER_CONTRACT * 5 + MAKER_FEE_PER_CONTRACT * 5;

        assert!(api.user_position(UID_1, SYMBOL_ID).is_none(), "UID_1 全平");
        assert_eq!(api.user_account(UID_1, QUOTE_ID), DEPOSIT - uid1_fee, "UID_1 fee: 开 maker + 关 taker");
        assert_eq!(
            api.user_position(UID_2, SYMBOL_ID).expect("UID_2 仍有仓位记录（pending 非零）").pending_buy_size,
            5,
            "UID_2: 关 5 SHORT 后剩 5 张 BID 挂单未匹配"
        );
        assert_eq!(api.fees(QUOTE_ID), uid1_fee + uid2_fee, "fees bucket = UID_1 + UID_2 累计");
        assert_conserved(&api);
    }

    // ============================================================================================
    // 5. feesBucket_aggregatesAcrossMultipleFills —— 多笔成交，fees bucket 无遗漏无重复。
    // ============================================================================================

    #[test]
    fn fees_bucket_aggregates_across_multiple_fills() {
        let mut api = fresh_api();
        seed_user(&mut api, UID_1, DEPOSIT, 1);
        seed_user(&mut api, UID_2, DEPOSIT, 2);

        let n = 4i64;
        let size_per_fill = 2i64;
        for i in 0..n {
            let oid = 1000 + i * 2;
            // 开 + 平 一组：UID_1 maker × 2，UID_2 taker × 2。
            assert_eq!(bid(&mut api, oid, UID_1, size_per_fill), CommandResultCode::Success);
            assert_eq!(ask(&mut api, oid + 1, UID_2, size_per_fill), CommandResultCode::Success);
            assert_eq!(ask(&mut api, oid + 100, UID_1, size_per_fill), CommandResultCode::Success);
            assert_eq!(bid(&mut api, oid + 101, UID_2, size_per_fill), CommandResultCode::Success);
        }

        let expected_fees = n * size_per_fill * (2 * MAKER_FEE_PER_CONTRACT + 2 * TAKER_FEE_PER_CONTRACT);
        assert_eq!(api.fees(QUOTE_ID), expected_fees, "fees bucket = n × (开 + 关) × (maker + taker)");
        assert_conserved(&api);
    }
}
