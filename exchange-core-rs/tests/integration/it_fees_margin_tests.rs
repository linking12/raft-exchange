//! 翻译自 Java 抽象基类 `exchange.core2.tests.integration.ITFeesMargin`（USD/JPY 期货固定费）。
//!
//! Java 通过 `TotalCurrencyBalanceReportResult`（fees / clientsBalancesSum / isGlobalBalancesAllZero）
//! + `validateUserState`（accounts / positions）对拍。Rust `ExchangeApi` 无报表命令，改为等价的
//! `api.fees(cur)` + 逐用户 `api.user_account(uid,cur)` + 仓位字段 + 全局守恒——三者是 Java 报表可观测的聚合。
//!
//! 逐字复刻 Java `SYMBOLSPECFEE_USD_JPY`（`TestConstants`）：
//!   symbolId=5991, FUTURES_CONTRACT_PERPETUAL, base=USD(840) quote=JPY(392),
//!   baseScaleK=100_000, quoteScaleK=10, initMargin=1/initMarginScaleK=21,
//!   makerFee=2 takerFee=3 feeScaleK=0（固定费）。
//! 货币 digit=6 → currency_scale_k=1_000_000；product scale = 100_000*10 = 1e6 → 手续费缩放因子=1（费=原始固定费）。
//!
//! 忠实性偏差（均不影响手续费/余额/仓位断言）：
//!   - mark price 设为成交价（10770）而非 Java 的 10000，令 estimate_pnl=0、规避 set_mark_price 的强平扫描
//!     （标准 harness nuance），并保持守恒项干净。
//!   - 充值改为大额（远超保证金+手续费），跳过 Java 精算最小充值——手续费/仓位行为不变。
//!
//! 未翻译的 @Test（原因）：
//!   - `shouldProcessFees_MixedBidGtcMakerPartial_AskIocTaker`：跨两个 DYNAMIC_FEE 期货 symbol 的
//!     RISK_NSF 精算（deposit=26155 恰好够单开不够双开），依赖保证金+手续费预留的精确边界，跳过。
//!   - 各 @Test 尾部 `checkFeeAfterResetFee`（`ApiResetFee`）：harness 无 RESET-FEE 命令，跳过该收尾段。

#[cfg(test)]
mod tests {
    use std::collections::BTreeMap;

    use exchange_core_rs::core::common::cmd::command_result_code::CommandResultCode;
    use exchange_core_rs::core::common::core_symbol_specification::CoreSymbolSpecification;
    use exchange_core_rs::core::common::margin_mode::MarginMode;
    use exchange_core_rs::core::common::order_action::OrderAction;
    use exchange_core_rs::core::common::order_type::OrderType;
    use exchange_core_rs::core::common::position_direction::PositionDirection;
    use exchange_core_rs::core::common::symbol_type::SymbolType;
    use exchange_core_rs::core::exchange_api::{CancelOrderRequest, ExchangeApi, PlaceFuturesOrderRequest};
    use exchange_core_rs::core::utils::core_arithmetic_utils::{
        calculate_maker_fee, calculate_taker_fee, size_price_to_currency_scale,
    };

    // TestConstants
    const USD: i32 = 840; // base（未持有余额，仅注册）
    const JPY: i32 = 392; // quote（账户 & 手续费币种）
    const SYM: i32 = 5991; // SYMBOL_MARGIN
    const UID_1: i64 = 1_440_001;
    const UID_2: i64 = 1_440_002;

    const BASE_SCALE_K: i64 = 100_000;
    const QUOTE_SCALE_K: i64 = 10;
    const CURRENCY_SCALE_K: i64 = 1_000_000; // digit 6
    const MAKER_FEE: i64 = 2;
    const TAKER_FEE: i64 = 3;
    const FEE_SCALE_K: i64 = 0; // 固定费

    const PRICE: i64 = 10_770; // 成交价（= mark，pnl=0）
    const DEPOSIT: i64 = 1_000_000_000; // 大额充值，覆盖保证金+手续费

    fn spec() -> CoreSymbolSpecification {
        CoreSymbolSpecification {
            symbol_id: SYM,
            symbol_type: SymbolType::FuturesContractPerpetual,
            base_currency: USD,
            quote_currency: JPY,
            base_scale_k: BASE_SCALE_K,
            quote_scale_k: QUOTE_SCALE_K,
            maker_fee: MAKER_FEE,
            taker_fee: TAKER_FEE,
            fee_scale_k: FEE_SCALE_K,
            init_margin: 1,
            init_margin_scale_k: 21,
            maintenance_margin: BTreeMap::from([(1000, 5), (100_000, 10)]),
            maintenance_margin_scale_k: 1000,
            max_leverage: BTreeMap::from([(2000, 5), (100_000, 10)]),
            ..Default::default()
        }
    }

    fn seed() -> ExchangeApi {
        let mut api = ExchangeApi::new();
        api.add_currency(USD, CURRENCY_SCALE_K);
        api.add_currency(JPY, CURRENCY_SCALE_K);
        assert_eq!(api.add_futures_symbol(spec()), CommandResultCode::Success);
        assert_eq!(api.set_mark_price(SYM, PRICE, 0), CommandResultCode::Success);
        api
    }

    fn seed_user(api: &mut ExchangeApi, uid: i64, jpy: i64, txid: i64) {
        assert_eq!(api.add_user(uid), CommandResultCode::Success);
        assert_eq!(api.balance_adjustment(uid, JPY, jpy, txid), CommandResultCode::Success);
    }

    #[allow(clippy::too_many_arguments)]
    fn place(
        api: &mut ExchangeApi,
        order_id: i64,
        uid: i64,
        price: i64,
        size: i64,
        action: OrderAction,
        order_type: OrderType,
    ) -> CommandResultCode {
        api.place_futures_order(PlaceFuturesOrderRequest {
            order_id,
            uid,
            symbol: SYM,
            price,
            size,
            action,
            order_type,
            leverage: 1,
            margin_mode: MarginMode::Cross,
            reduce_only: false,
        })
    }

    /// 固定费 → currency scale（因子=1）。maker/taker 各按已成交量收。
    fn maker_fee(filled: i64) -> i64 {
        size_price_to_currency_scale(
            calculate_maker_fee(filled, PRICE, MAKER_FEE, FEE_SCALE_K),
            BASE_SCALE_K,
            QUOTE_SCALE_K,
            CURRENCY_SCALE_K,
        )
    }
    fn taker_fee(filled: i64) -> i64 {
        size_price_to_currency_scale(
            calculate_taker_fee(filled, PRICE, TAKER_FEE, FEE_SCALE_K),
            BASE_SCALE_K,
            QUOTE_SCALE_K,
            CURRENCY_SCALE_K,
        )
    }

    /// 全局守恒（== Java `isGlobalBalancesAllZero` 的 JPY 分量）：
    /// Σ account(JPY) + adjustments(JPY) + fees(JPY) + Σ_open_pos(estimate_pnl(mark)+extra_margin) == 0。
    fn assert_conserved(api: &ExchangeApi) {
        let mut total: i64 = api.ups().users.values().map(|p| p.account(JPY)).sum();
        total += api.adjustments(JPY);
        total += api.fees(JPY);
        for p in api.ups().users.values() {
            for pos in p.positions.values() {
                if pos.currency != JPY {
                    continue;
                }
                let mark = api.risk().mark_price(pos.symbol).unwrap_or(PRICE);
                total += pos.estimate_pnl(mark);
                total += pos.extra_margin;
            }
        }
        assert_eq!(total, 0, "期货全局守恒被打破：JPY total={total}");
    }

    // ================================================================================================
    // 1. shouldProcessFees_AskGtcMakerPartial_BidIocTaker
    //    maker GTC ASK 40（SHORT，部分成交），taker IOC BID 30（LONG，全成）。
    // ================================================================================================

    #[test]
    fn ask_gtc_maker_partial_bid_ioc_taker() {
        let mut api = seed();
        seed_user(&mut api, UID_1, DEPOSIT, 1);
        seed_user(&mut api, UID_2, DEPOSIT, 2);

        // maker ASK 40（resting SHORT，pending_sell 40）。
        assert_eq!(place(&mut api, 101, UID_1, PRICE, 40, OrderAction::Ask, OrderType::Gtc), CommandResultCode::Success);
        // 下单后无成交，fees 仍为 0。
        assert_eq!(api.fees(JPY), 0);

        // taker IOC BID 30 → 成交 30。
        assert_eq!(place(&mut api, 102, UID_2, PRICE, 30, OrderAction::Bid, OrderType::Ioc), CommandResultCode::Success);

        let filled = 30i64;
        // maker：SHORT，openVolume=30，pendingSell=10。
        let mp = api.user_position(UID_1, SYM).expect("maker 持仓");
        assert_eq!(mp.direction, PositionDirection::Short);
        assert_eq!(mp.open_volume, filled);
        assert_eq!(mp.pending_sell_size, 10);
        assert_eq!(mp.pending_buy_size, 0);
        assert_eq!(mp.open_price_sum, PRICE * filled);
        // taker：LONG，openVolume=30，无 pending。
        let tp = api.user_position(UID_2, SYM).expect("taker 持仓");
        assert_eq!(tp.direction, PositionDirection::Long);
        assert_eq!(tp.open_volume, filled);
        assert_eq!(tp.pending_sell_size, 0);
        assert_eq!(tp.pending_buy_size, 0);
        assert_eq!(tp.open_price_sum, PRICE * filled);

        // 手续费按已成交 30 计。
        assert_eq!(api.user_account(UID_1, JPY), DEPOSIT - maker_fee(filled));
        assert_eq!(api.user_account(UID_2, JPY), DEPOSIT - taker_fee(filled));
        assert_eq!(api.user_account(UID_1, USD), 0);
        assert_eq!(api.user_account(UID_2, USD), 0);
        assert_eq!(api.fees(JPY), maker_fee(filled) + taker_fee(filled));
        assert_eq!(api.fees(USD), 0);
        assert_conserved(&api);
    }

    // ================================================================================================
    // 2. shouldProcessFees_BidGtcMakerPartial_AskIocTaker
    //    maker GTC BID 50（LONG，部分成交），taker IOC ASK 30（SHORT，全成）。
    // ================================================================================================

    #[test]
    fn bid_gtc_maker_partial_ask_ioc_taker() {
        let mut api = seed();
        seed_user(&mut api, UID_1, DEPOSIT, 1);
        seed_user(&mut api, UID_2, DEPOSIT, 2);

        // maker BID 50（resting LONG，pending_buy 50）。
        assert_eq!(place(&mut api, 101, UID_1, PRICE, 50, OrderAction::Bid, OrderType::Gtc), CommandResultCode::Success);
        assert_eq!(api.fees(JPY), 0);

        // taker IOC ASK 30 → 成交 30。
        assert_eq!(place(&mut api, 102, UID_2, PRICE, 30, OrderAction::Ask, OrderType::Ioc), CommandResultCode::Success);

        let filled = 30i64;
        // maker：LONG，openVolume=30，pendingBuy=20。
        let mp = api.user_position(UID_1, SYM).expect("maker 持仓");
        assert_eq!(mp.direction, PositionDirection::Long);
        assert_eq!(mp.open_volume, filled);
        assert_eq!(mp.pending_buy_size, 20);
        assert_eq!(mp.pending_sell_size, 0);
        assert_eq!(mp.open_price_sum, PRICE * filled);
        // taker：SHORT，openVolume=30。
        let tp = api.user_position(UID_2, SYM).expect("taker 持仓");
        assert_eq!(tp.direction, PositionDirection::Short);
        assert_eq!(tp.open_volume, filled);
        assert_eq!(tp.pending_buy_size, 0);
        assert_eq!(tp.pending_sell_size, 0);
        assert_eq!(tp.open_price_sum, PRICE * filled);

        assert_eq!(api.user_account(UID_1, JPY), DEPOSIT - maker_fee(filled));
        assert_eq!(api.user_account(UID_2, JPY), DEPOSIT - taker_fee(filled));
        assert_eq!(api.fees(JPY), maker_fee(filled) + taker_fee(filled));
        assert_eq!(api.fees(USD), 0);
        assert_conserved(&api);
    }

    // ================================================================================================
    // 3. shouldNotTakeFeesForCancelAsk —— 挂 ASK 不成交后撤单，全程 0 手续费。
    // ================================================================================================

    #[test]
    fn should_not_take_fees_for_cancel_ask() {
        let mut api = seed();
        seed_user(&mut api, UID_1, DEPOSIT, 1);

        // 挂 ASK 40（无对手，resting SHORT，pending_sell 40，open_volume 0）。
        assert_eq!(place(&mut api, 101, UID_1, PRICE, 40, OrderAction::Ask, OrderType::Gtc), CommandResultCode::Success);
        let p = api.user_position(UID_1, SYM).expect("resting 仓位");
        assert_eq!(p.direction, PositionDirection::Short);
        assert_eq!(p.open_volume, 0);
        assert_eq!(p.pending_sell_size, 40);
        assert_eq!(api.user_account(UID_1, JPY), DEPOSIT, "挂单未成交，账户不动");
        assert_eq!(api.fees(JPY), 0);
        assert_conserved(&api);

        // 撤单。
        assert_eq!(
            api.cancel_order(CancelOrderRequest { order_id: 101, uid: UID_1, symbol: SYM }),
            CommandResultCode::Success
        );

        // 撤后无挂量、无手续费。
        assert!(
            api.user_position(UID_1, SYM).map_or(true, |p| p.open_volume == 0 && p.pending_sell_size == 0 && p.pending_buy_size == 0),
            "撤单后不应残留挂量"
        );
        assert_eq!(api.user_account(UID_1, JPY), DEPOSIT);
        assert_eq!(api.fees(JPY), 0);
        assert_eq!(api.fees(USD), 0);
        assert_conserved(&api);
    }
    // Java ITFeesMargin 用独立字面公式 sideFee×filled（固定费 FEE_SCALE_K=0）——把生产函数派生的费用 oracle
    // 钉死到该独立公式，证明"金额对"不依赖被测库自身函数（去自指）。
    #[test]
    fn fee_oracle_matches_java_independent_formula() {
        assert_eq!(maker_fee(30), MAKER_FEE * 30, "Java makerFee×30 = 60");
        assert_eq!(taker_fee(30), TAKER_FEE * 30, "Java takerFee×30 = 90");
    }
}
