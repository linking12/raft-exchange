//! 翻译自 Java `exchange.core2.tests.integration.ITSpotTradingFeeCalculationTest`。
//!
//! Java 原测通过 `SimpleEventsProcessor4Test` 抓 `SpotExecutionReport.commission`（逐笔 maker/taker 佣金）。
//! Rust 引擎不暴露逐笔执行报告，改为对拍**手续费池**（`api.fees(quote)`）+ **逐用户余额** +
//! **全局守恒**，这三者是 Java per-report commission 的可观测聚合（见 Java `testFeeCalculationParameters`
//! 第 705-706 行：全局 fees == Σ commission 再 `sizePriceToCurrencyScale`）。
//!
//! TEST_SYMBOL: baseScaleK=1000, quoteScaleK=10（product scale=1e4），takerFee=20/makerFee=10（固定费，
//! feeScaleK=0），base=XBT/quote=LTC 均 digit 8（currency_scale_k=1e8）。
//! 因此每笔撮合入池费 = `sizePriceToCurrencyScale(taker_size*20 + maker_size*10, 1000,10,1e8)`
//! = 原始费 × (1e8/1e4) = 原始费 × 10_000。撮合市场里 taker 成交量 == maker 成交量 == V，
//! 故池 = V × (20+10) × 10_000 = V × 300_000。

#[cfg(test)]
mod tests {
    use crate::core::common::cmd::command_result_code::CommandResultCode;
    use crate::core::common::core_symbol_specification::CoreSymbolSpecification;
    use crate::core::common::order_action::OrderAction;
    use crate::core::common::order_type::OrderType;
    use crate::core::common::symbol_type::SymbolType;
    use crate::core::exchange_api::{ExchangeApi, PlaceOrderRequest};

    // TestConstants.CURRENECY_XBT / CURRENECY_LTC
    const BASE: i32 = 3762; // XBT satoshi
    const QUOTE: i32 = 4141; // LTC litoshi
    const SYMBOL: i32 = 20001;

    const BASE_SCALE_K: i64 = 1000;
    const QUOTE_SCALE_K: i64 = 10;
    const TAKER_FEE: i64 = 20;
    const MAKER_FEE: i64 = 10;
    // digit 8 → currency_scale_k = 1e8；product scale = 1000*10 = 1e4；放大因子 1e8/1e4 = 1e4。
    const CURRENCY_SCALE_K: i64 = 100_000_000;
    const FEE_SCALE_FACTOR: i64 = 10_000;

    // TestConstants.UID_1..UID_5
    const UID_1: i64 = 1_440_001;
    const UID_2: i64 = 1_440_002;
    const UID_3: i64 = 1_440_003;
    const UID_4: i64 = 1_440_004;
    const UID_5: i64 = 1_440_005;

    // createUserWithMoney 用的大额充值（对齐 Java: 100_000_000L * 100_000_000L = 1e16）。
    const BIG_MONEY: i64 = 100_000_000i64 * 100_000_000i64;

    fn test_symbol() -> CoreSymbolSpecification {
        CoreSymbolSpecification {
            symbol_id: SYMBOL,
            symbol_type: SymbolType::CurrencyExchangePair,
            base_currency: BASE,
            quote_currency: QUOTE,
            base_scale_k: BASE_SCALE_K,
            quote_scale_k: QUOTE_SCALE_K,
            taker_fee: TAKER_FEE,
            maker_fee: MAKER_FEE,
            fee_scale_k: 0,
            ..Default::default()
        }
    }

    /// 建 api + 注册 currency/symbol（对齐 Java before/each-test 的 addSymbol/addCurrency）。
    fn new_api() -> ExchangeApi {
        let mut api = ExchangeApi::new();
        api.add_currency(BASE, CURRENCY_SCALE_K);
        api.add_currency(QUOTE, CURRENCY_SCALE_K);
        assert_eq!(api.add_symbol(test_symbol()), CommandResultCode::Success);
        api
    }

    /// createUserWithMoney(uid, currency, amount)：加用户 + 充值。
    fn create_user_with_money(api: &mut ExchangeApi, uid: i64, currency: i32, amount: i64, txid: i64) {
        assert_eq!(api.add_user(uid), CommandResultCode::Success);
        assert_eq!(api.balance_adjustment(uid, currency, amount, txid), CommandResultCode::Success);
    }

    fn bid(order_id: i64, uid: i64, price: i64, reserve: i64, size: i64, ot: OrderType) -> PlaceOrderRequest {
        PlaceOrderRequest { order_id, uid, symbol: SYMBOL, price, size, reserve_bid_price: reserve, action: OrderAction::Bid, order_type: ot }
    }
    fn ask(order_id: i64, uid: i64, price: i64, size: i64, ot: OrderType) -> PlaceOrderRequest {
        PlaceOrderRequest { order_id, uid, symbol: SYMBOL, price, size, reserve_bid_price: 0, action: OrderAction::Ask, order_type: ot }
    }

    /// 撮合成交量 V（taker==maker==V）对应的入池手续费黄金值。
    fn fee_pool_for(matched_volume: i64) -> i64 {
        matched_volume * (TAKER_FEE + MAKER_FEE) * FEE_SCALE_FACTOR
    }

    /// 全局守恒量：Σ accounts[cur] + adjustments[cur] + fees[cur]（应恒为 0）。
    fn conserved(api: &ExchangeApi, cur: i32) -> i64 {
        api.ups().users.values().map(|p| p.account(cur)).sum::<i64>() + api.adjustments(cur) + api.fees(cur)
    }

    // ================================================================
    // 单 maker vs 单 taker：GTC / IOC / FOK_BUDGET / IOC_BUDGET
    // ================================================================

    // 对拍 testGtcMakerTakerFeeCalculation：GTC maker BID + GTC taker ASK，size=100 price=15000。
    #[test]
    fn gtc_maker_taker_fee_calculation() {
        let mut api = new_api();
        let size = 100;
        let price = 15_000;
        create_user_with_money(&mut api, UID_1, QUOTE, BIG_MONEY, 1); // maker BID 需 quote
        create_user_with_money(&mut api, UID_2, BASE, BIG_MONEY, 2); // taker ASK 需 base

        assert_eq!(api.place_order(bid(1001, UID_1, price, price, size, OrderType::Gtc)), CommandResultCode::Success);
        assert_eq!(api.place_order(ask(1002, UID_2, price, size, OrderType::Gtc)), CommandResultCode::Success);

        assert_eq!(api.fees(QUOTE), fee_pool_for(size), "GTC maker+taker 入池费 = 100×300000");
        assert_eq!(api.fees(BASE), 0);
        assert_eq!(conserved(&api, BASE), 0);
        assert_eq!(conserved(&api, QUOTE), 0);
    }

    // 对拍 testIocTakerFeeCalculation：GTC maker BID + IOC taker ASK，size=50 price=12000。
    #[test]
    fn ioc_taker_fee_calculation() {
        let mut api = new_api();
        let size = 50;
        let price = 12_000;
        create_user_with_money(&mut api, UID_1, QUOTE, BIG_MONEY, 1);
        create_user_with_money(&mut api, UID_2, BASE, BIG_MONEY, 2);

        assert_eq!(api.place_order(bid(2001, UID_1, price, price, size, OrderType::Gtc)), CommandResultCode::Success);
        assert_eq!(api.place_order(ask(2002, UID_2, price, size, OrderType::Ioc)), CommandResultCode::Success);

        assert_eq!(api.fees(QUOTE), fee_pool_for(size));
        assert_eq!(conserved(&api, QUOTE), 0);
    }

    // 对拍 testFokBudgetTakerFeeCalculation：GTC maker ASK + FOK_BUDGET taker BID 全成，size=75 price=14000。
    // maker 持 base（ASK），taker 持 quote（BID）；BUDGET 单 price 字段 = 总预算 size*price。
    #[test]
    fn fok_budget_taker_fee_calculation() {
        let mut api = new_api();
        let size = 75;
        let price = 14_000;
        let budget = size * price;
        create_user_with_money(&mut api, UID_1, BASE, BIG_MONEY, 1); // maker ASK 需 base
        create_user_with_money(&mut api, UID_2, QUOTE, BIG_MONEY, 2); // taker BID 需 quote

        assert_eq!(api.place_order(ask(3001, UID_1, price, size, OrderType::Gtc)), CommandResultCode::Success);
        assert_eq!(api.place_order(bid(3002, UID_2, budget, budget, size, OrderType::FokBudget)), CommandResultCode::Success);

        assert_eq!(api.fees(QUOTE), fee_pool_for(size));
        assert_eq!(conserved(&api, BASE), 0);
        assert_eq!(conserved(&api, QUOTE), 0);
    }

    // 对拍 testIocBudgetFullFillTakerFeeCalculation：IOC_BUDGET 全成，size=60 price=13500，budget 精确覆盖。
    #[test]
    fn ioc_budget_full_fill_taker_fee_calculation() {
        let mut api = new_api();
        let size = 60;
        let price = 13_500;
        let budget = size * price;
        create_user_with_money(&mut api, UID_1, BASE, BIG_MONEY, 1);
        create_user_with_money(&mut api, UID_2, QUOTE, BIG_MONEY, 2);

        assert_eq!(api.place_order(ask(3101, UID_1, price, size, OrderType::Gtc)), CommandResultCode::Success);
        assert_eq!(api.place_order(bid(3102, UID_2, budget, budget, size, OrderType::IocBudget)), CommandResultCode::Success);

        // 全成：无残量挂单，费按全 size 计。
        let l2 = api.request_l2(SYMBOL, 10);
        assert!(l2.ask_prices.is_empty() && l2.bid_prices.is_empty(), "全成后盘口清空");
        assert_eq!(api.fees(QUOTE), fee_pool_for(size));
        assert_eq!(conserved(&api, QUOTE), 0);
    }

    // 对拍 testIocBudgetPartialFillTakerFeeCalculation：预算只够吃 60/100，taker fee 只按已成交 60 计。
    #[test]
    fn ioc_budget_partial_fill_taker_fee_calculation() {
        let mut api = new_api();
        let requested_size = 100;
        let filled_size = 60;
        let price = 14_000;
        let budget = filled_size * price; // 预算仅够 60
        create_user_with_money(&mut api, UID_1, BASE, BIG_MONEY, 1);
        create_user_with_money(&mut api, UID_2, QUOTE, BIG_MONEY, 2);

        // maker ASK 100，taker IOC_BUDGET 要 100 但预算只够 60。
        assert_eq!(api.place_order(ask(3201, UID_1, price, requested_size, OrderType::Gtc)), CommandResultCode::Success);
        assert_eq!(api.place_order(bid(3202, UID_2, budget, budget, requested_size, OrderType::IocBudget)), CommandResultCode::Success);

        // 关键：费按已成交 60 计，而非请求 100。
        assert_eq!(api.fees(QUOTE), fee_pool_for(filled_size), "fee 只按已成交量 60");
        assert_ne!(api.fees(QUOTE), fee_pool_for(requested_size), "不得按请求量 100 计费");
        assert!(api.fees(QUOTE) > 0);
        // maker 残量 40 仍挂在盘口。
        let l2 = api.request_l2(SYMBOL, 10);
        assert_eq!(l2.ask_prices, vec![price]);
        assert_eq!(l2.ask_volumes, vec![requested_size - filled_size]);
        // 全局对账闭环（对拍 isGlobalBalancesAllZero）。
        assert_eq!(conserved(&api, BASE), 0);
        assert_eq!(conserved(&api, QUOTE), 0);
    }

    // 对拍 testIocBudgetFullRejectGlobalBalanceReconciliation：预算=1 整单 reject，0 费，账面闭合。
    #[test]
    fn ioc_budget_full_reject_global_balance_reconciliation() {
        let mut api = new_api();
        let requested_size = 50;
        let price = 14_000;
        let budget = 1; // 远小于单价 → 一手都买不起
        create_user_with_money(&mut api, UID_1, BASE, BIG_MONEY, 1);
        create_user_with_money(&mut api, UID_2, QUOTE, BIG_MONEY, 2);

        assert_eq!(api.place_order(ask(3301, UID_1, price, requested_size, OrderType::Gtc)), CommandResultCode::Success);
        assert_eq!(api.place_order(bid(3302, UID_2, budget, budget, requested_size, OrderType::IocBudget)), CommandResultCode::Success);

        assert_eq!(api.fees(QUOTE), 0, "全单 reject 应 0 fee");
        assert_eq!(conserved(&api, BASE), 0);
        assert_eq!(conserved(&api, QUOTE), 0);
    }

    // ================================================================
    // 1-to-many
    // ================================================================

    // 对拍 testMakerOneToManyFeeCalculation：1 maker BID 300，3 takers ASK(100 GTC/120 IOC/80 GTC) 全吃。
    #[test]
    fn maker_one_to_many_fee_calculation() {
        let mut api = new_api();
        let total_size = 300;
        let (s1, s2, s3) = (100, 120, 80);
        let price = 13_000;
        create_user_with_money(&mut api, UID_1, QUOTE, BIG_MONEY, 1); // maker BID
        create_user_with_money(&mut api, UID_2, BASE, BIG_MONEY, 2);
        create_user_with_money(&mut api, UID_3, BASE, BIG_MONEY, 3);
        create_user_with_money(&mut api, UID_4, BASE, BIG_MONEY, 4);

        assert_eq!(api.place_order(bid(4001, UID_1, price, price, total_size, OrderType::Gtc)), CommandResultCode::Success);
        assert_eq!(api.place_order(ask(4002, UID_2, price, s1, OrderType::Gtc)), CommandResultCode::Success);
        assert_eq!(api.place_order(ask(4003, UID_3, price, s2, OrderType::Ioc)), CommandResultCode::Success);
        assert_eq!(api.place_order(ask(4004, UID_4, price, s3, OrderType::Gtc)), CommandResultCode::Success);

        assert_eq!(api.fees(QUOTE), fee_pool_for(s1 + s2 + s3));
        assert_eq!(conserved(&api, QUOTE), 0);
    }

    // 对拍 testTakerOneToManyFeeCalculation：3 makers ASK 异价，1 taker BID 全吃。
    #[test]
    fn taker_one_to_many_fee_calculation() {
        let mut api = new_api();
        let (s1, s2, s3) = (80, 90, 70);
        let total = s1 + s2 + s3;
        let (p1, p2, p3) = (11_000, 11_100, 11_200);
        create_user_with_money(&mut api, UID_1, BASE, BIG_MONEY, 1);
        create_user_with_money(&mut api, UID_2, BASE, BIG_MONEY, 2);
        create_user_with_money(&mut api, UID_3, BASE, BIG_MONEY, 3);
        create_user_with_money(&mut api, UID_4, QUOTE, BIG_MONEY, 4); // taker BID

        assert_eq!(api.place_order(ask(5001, UID_1, p1, s1, OrderType::Gtc)), CommandResultCode::Success);
        assert_eq!(api.place_order(ask(5002, UID_2, p2, s2, OrderType::Gtc)), CommandResultCode::Success);
        assert_eq!(api.place_order(ask(5003, UID_3, p3, s3, OrderType::Gtc)), CommandResultCode::Success);
        assert_eq!(api.place_order(bid(5004, UID_4, p3, p3, total, OrderType::Gtc)), CommandResultCode::Success);

        // 固定费与价格无关，池 = 全成交量 × 300000。
        assert_eq!(api.fees(QUOTE), fee_pool_for(total));
        assert_eq!(conserved(&api, QUOTE), 0);
    }

    // 对拍 testMixedOrderTypesFeeCalculation：2 makers BID + GTC taker ASK + IOC taker ASK。
    #[test]
    fn mixed_order_types_fee_calculation() {
        let mut api = new_api();
        let size = 60;
        let price = 16_000;
        create_user_with_money(&mut api, UID_1, QUOTE, BIG_MONEY, 1);
        create_user_with_money(&mut api, UID_2, QUOTE, BIG_MONEY, 2);
        create_user_with_money(&mut api, UID_3, BASE, BIG_MONEY, 3);
        create_user_with_money(&mut api, UID_4, BASE, BIG_MONEY, 4);
        let _ = UID_5;

        assert_eq!(api.place_order(bid(6001, UID_1, price, price, size, OrderType::Gtc)), CommandResultCode::Success);
        assert_eq!(api.place_order(bid(6002, UID_2, price, price, size, OrderType::Gtc)), CommandResultCode::Success);
        assert_eq!(api.place_order(ask(6003, UID_3, price, size, OrderType::Gtc)), CommandResultCode::Success);
        assert_eq!(api.place_order(ask(6004, UID_4, price, size, OrderType::Ioc)), CommandResultCode::Success);

        // 两笔 taker 各吃一个 maker，全成交量 = 120。
        assert_eq!(api.fees(QUOTE), fee_pool_for(2 * size));
        assert_eq!(conserved(&api, QUOTE), 0);
    }

    // 对拍 testFeeCalculationParameters：非圆整 size=123 price=17789；校验全局账平 + 池费精确。
    #[test]
    fn fee_calculation_parameters() {
        let mut api = new_api();
        let size = 123;
        let price = 17_789;
        create_user_with_money(&mut api, UID_1, QUOTE, BIG_MONEY, 1);
        create_user_with_money(&mut api, UID_2, BASE, BIG_MONEY, 2);

        assert_eq!(api.place_order(bid(7001, UID_1, price, price, size, OrderType::Gtc)), CommandResultCode::Success);
        assert_eq!(api.place_order(ask(7002, UID_2, price, size, OrderType::Gtc)), CommandResultCode::Success);

        assert_eq!(api.fees(QUOTE), fee_pool_for(size));
        assert_eq!(api.fees(BASE), 0);
        // isGlobalBalancesAllZero 的可观测等价：两币种全局守恒。
        assert_eq!(conserved(&api, BASE), 0);
        assert_eq!(conserved(&api, QUOTE), 0);
    }

    // 对拍 testMakerOneToManyPartialFillFeeConsistency：maker BID 500 被 350 部分吃。
    #[test]
    fn maker_one_to_many_partial_fill_fee_consistency() {
        let mut api = new_api();
        let maker_total = 500;
        let (s1, s2, s3) = (80, 120, 150); // 合计 350 < 500
        let price = 14_500;
        create_user_with_money(&mut api, UID_1, QUOTE, BIG_MONEY, 1);
        create_user_with_money(&mut api, UID_2, BASE, BIG_MONEY, 2);
        create_user_with_money(&mut api, UID_3, BASE, BIG_MONEY, 3);
        create_user_with_money(&mut api, UID_4, BASE, BIG_MONEY, 4);

        assert_eq!(api.place_order(bid(8001, UID_1, price, price, maker_total, OrderType::Gtc)), CommandResultCode::Success);
        assert_eq!(api.place_order(ask(8002, UID_2, price, s1, OrderType::Gtc)), CommandResultCode::Success);
        assert_eq!(api.place_order(ask(8003, UID_3, price, s2, OrderType::Ioc)), CommandResultCode::Success);
        assert_eq!(api.place_order(ask(8004, UID_4, price, s3, OrderType::Gtc)), CommandResultCode::Success);

        let filled = s1 + s2 + s3;
        assert!(api.fees(QUOTE) > 0);
        assert_eq!(api.fees(QUOTE), fee_pool_for(filled));
        // maker 残量 150 仍挂盘。
        let l2 = api.request_l2(SYMBOL, 10);
        assert_eq!(l2.bid_prices, vec![price]);
        assert_eq!(l2.bid_volumes, vec![maker_total - filled]);
        assert_eq!(conserved(&api, QUOTE), 0);
    }

    // 对拍 testTakerOneToManyPartialFillFeeConsistency：taker BID 300 只吃到 230。
    #[test]
    fn taker_one_to_many_partial_fill_fee_consistency() {
        let mut api = new_api();
        let (s1, s2, s3) = (60, 80, 90); // 合计 230
        let taker_total = 300;
        let (p1, p2, p3) = (12_000, 12_100, 12_200);
        create_user_with_money(&mut api, UID_1, BASE, BIG_MONEY, 1);
        create_user_with_money(&mut api, UID_2, BASE, BIG_MONEY, 2);
        create_user_with_money(&mut api, UID_3, BASE, BIG_MONEY, 3);
        create_user_with_money(&mut api, UID_4, QUOTE, BIG_MONEY, 4);

        assert_eq!(api.place_order(ask(9001, UID_1, p1, s1, OrderType::Gtc)), CommandResultCode::Success);
        assert_eq!(api.place_order(ask(9002, UID_2, p2, s2, OrderType::Gtc)), CommandResultCode::Success);
        assert_eq!(api.place_order(ask(9003, UID_3, p3, s3, OrderType::Gtc)), CommandResultCode::Success);
        // GTC BID 高于所有 ask → 吃满 230，残 70 挂 bid 侧。
        assert_eq!(api.place_order(bid(9004, UID_4, p3, p3, taker_total, OrderType::Gtc)), CommandResultCode::Success);

        let filled = s1 + s2 + s3;
        assert!(api.fees(QUOTE) > 0);
        assert_eq!(api.fees(QUOTE), fee_pool_for(filled));
        // taker 残量 70 以 p3 挂 bid。
        let l2 = api.request_l2(SYMBOL, 10);
        assert_eq!(l2.bid_prices, vec![p3]);
        assert_eq!(l2.bid_volumes, vec![taker_total - filled]);
        assert_eq!(conserved(&api, QUOTE), 0);
    }

    // 对拍 testSpotFullLifecycleWithDepositWithdraw：充值 → 撮合 → 提现 → 对账，四种 taker OrderType 各一轮。
    #[test]
    fn spot_full_lifecycle_with_deposit_withdraw() {
        for taker_type in [OrderType::Gtc, OrderType::Ioc, OrderType::FokBudget, OrderType::IocBudget] {
            run_spot_full_lifecycle(taker_type);
        }
    }

    fn run_spot_full_lifecycle(taker_type: OrderType) {
        let maker_uid = 7101;
        let taker_uid = 7102;
        let size = 5;
        let price = 12_000;

        let mut api = new_api();
        // 双方都充 base + quote（撮合后两边都会有 base+quote 需提走）。
        assert_eq!(api.add_user(maker_uid), CommandResultCode::Success);
        assert_eq!(api.balance_adjustment(maker_uid, BASE, BIG_MONEY, 1), CommandResultCode::Success);
        assert_eq!(api.balance_adjustment(maker_uid, QUOTE, BIG_MONEY, 2), CommandResultCode::Success);
        assert_eq!(api.add_user(taker_uid), CommandResultCode::Success);
        assert_eq!(api.balance_adjustment(taker_uid, QUOTE, BIG_MONEY, 3), CommandResultCode::Success);
        assert_eq!(api.balance_adjustment(taker_uid, BASE, BIG_MONEY, 4), CommandResultCode::Success);

        assert_eq!(conserved(&api, BASE), 0, "[{taker_type:?}] 充值后 base 账平");
        assert_eq!(conserved(&api, QUOTE), 0, "[{taker_type:?}] 充值后 quote 账平");

        // 撮合：maker GTC ASK + taker <taker_type> BID。
        assert_eq!(api.place_order(ask(7201, maker_uid, price, size, OrderType::Gtc)), CommandResultCode::Success);
        let is_budget = matches!(taker_type, OrderType::FokBudget | OrderType::IocBudget);
        let taker_price_field = if is_budget { size * price } else { price };
        assert_eq!(
            api.place_order(bid(7202, taker_uid, taker_price_field, taker_price_field, size, taker_type)),
            CommandResultCode::Success
        );
        assert_eq!(conserved(&api, BASE), 0, "[{taker_type:?}] 撮合后 base 账平");
        assert_eq!(conserved(&api, QUOTE), 0, "[{taker_type:?}] 撮合后 quote 账平");

        // 读出双方各币余额，全数提走。
        for (uid, txbase, txquote) in [(maker_uid, 10, 11), (taker_uid, 12, 13)] {
            let b = api.user_account(uid, BASE);
            let q = api.user_account(uid, QUOTE);
            if b != 0 {
                assert_eq!(api.balance_adjustment(uid, BASE, -b, txbase), CommandResultCode::Success);
            }
            if q != 0 {
                assert_eq!(api.balance_adjustment(uid, QUOTE, -q, txquote), CommandResultCode::Success);
            }
        }

        // 终态：双方所有账户清零。
        for uid in [maker_uid, taker_uid] {
            assert_eq!(api.user_account(uid, BASE), 0, "[{taker_type:?}] 提现后 base 清零");
            assert_eq!(api.user_account(uid, QUOTE), 0, "[{taker_type:?}] 提现后 quote 清零");
        }

        // adjustments + fees == 0：充/提差额恰好等于引擎收的手续费。
        assert_eq!(api.adjustments(QUOTE) + api.fees(QUOTE), 0, "[{taker_type:?}] quote adjustments+fees==0");
        assert_eq!(api.adjustments(BASE) + api.fees(BASE), 0, "[{taker_type:?}] base adjustments+fees==0");
        assert_eq!(conserved(&api, BASE), 0);
        assert_eq!(conserved(&api, QUOTE), 0);

        // 每轮撮合量 V=5，quote 费池应涨 5×300000。
        assert_eq!(api.fees(QUOTE), fee_pool_for(size), "[{taker_type:?}] quote 费池");
    }
}
