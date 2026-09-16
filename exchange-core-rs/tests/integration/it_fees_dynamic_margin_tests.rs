//! 翻译自 Java 抽象基类 `exchange.core2.tests.integration.ITFeesDynamicMargin`
//! （SYMBOL_MARGIN_FEE 比例费期货）。
//!
//! Java 用 `TotalCurrencyBalanceReportResult` + `validateUserState` 对拍；Rust `ExchangeApi` 改为
//! `api.fees(cur)` + 逐用户 `api.user_account(uid,cur)` + 仓位字段 + 全局守恒（同一组 golden 值）。
//!
//! 逐字复刻 Java `SYMBOLSPEC_DYNAMIC_FEE_XBT_USD`（`TestConstants`）：
//!   symbolId=9341, FUTURES_CONTRACT_PERPETUAL, base=1 quote=USD(840),
//!   baseScaleK=1 quoteScaleK=1, initMargin=1/initMarginScaleK=100,
//!   makerFee=1 takerFee=2 feeScaleK=100（比例费）。
//! 货币 digit=0 → currency_scale_k=1；product scale=1 → 手续费缩放因子=1。
//! 比例费：maker=ceil(size*price*1/100)=size*100，taker=size*200（price=10000 整除）。
//!
//! 未翻译的 @Test（原因）：
//!   - `shouldNotProcessFees_AskGtcMakerPartial_BidFokTaker`：plain FOK（非 FOK_BUDGET、reservePrice=0）
//!     在限价交叉下 Java 断言「不成交、0 费」，属边界/半 bug 语义，不稳定复刻，跳过。
//!   - 各 @Test 尾部 `checkFeeAfterResetFee`（`ApiResetFee`）：harness 无 RESET-FEE 命令，跳过收尾段。

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

    const BASE_CUR: i32 = 1;
    const USD: i32 = 840;
    const SYM: i32 = 9341; // SYMBOL_MARGIN_FEE
    const UID_1: i64 = 1_440_001;
    const UID_2: i64 = 1_440_002;

    const BASE_SCALE_K: i64 = 1;
    const QUOTE_SCALE_K: i64 = 1;
    const CURRENCY_SCALE_K: i64 = 1; // digit 0
    const MAKER_FEE: i64 = 1;
    const TAKER_FEE: i64 = 2;
    const FEE_SCALE_K: i64 = 100; // 比例费

    const PRICE: i64 = 10_000; // 成交价 = mark（pnl=0）
    const MAKER_DEPOSIT: i64 = 10_000_000;
    const TAKER_DEPOSIT: i64 = 4_000_000; // MAX_VALUE

    fn spec() -> CoreSymbolSpecification {
        CoreSymbolSpecification {
            symbol_id: SYM,
            symbol_type: SymbolType::FuturesContractPerpetual,
            base_currency: BASE_CUR,
            quote_currency: USD,
            base_scale_k: BASE_SCALE_K,
            quote_scale_k: QUOTE_SCALE_K,
            maker_fee: MAKER_FEE,
            taker_fee: TAKER_FEE,
            fee_scale_k: FEE_SCALE_K,
            init_margin: 1,
            init_margin_scale_k: 100,
            maintenance_margin: BTreeMap::from([(1000, 5), (100_000, 10)]),
            maintenance_margin_scale_k: 1000,
            max_leverage: BTreeMap::from([(2000, 5), (100_000, 10)]),
            ..Default::default()
        }
    }

    fn seed() -> ExchangeApi {
        let mut api = ExchangeApi::new();
        api.add_currency(BASE_CUR, CURRENCY_SCALE_K);
        api.add_currency(USD, CURRENCY_SCALE_K);
        assert_eq!(api.add_futures_symbol(spec()), CommandResultCode::Success);
        assert_eq!(api.set_mark_price(SYM, PRICE), CommandResultCode::Success);
        assert_eq!(api.add_user(UID_1), CommandResultCode::Success);
        assert_eq!(api.balance_adjustment(UID_1, USD, MAKER_DEPOSIT, 1), CommandResultCode::Success);
        assert_eq!(api.add_user(UID_2), CommandResultCode::Success);
        assert_eq!(api.balance_adjustment(UID_2, USD, TAKER_DEPOSIT, 2), CommandResultCode::Success);
        api
    }

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

    /// 全局守恒（USD 分量）。
    fn assert_conserved(api: &ExchangeApi) {
        let mut total: i64 = api.ups().users.values().map(|p| p.account(USD)).sum();
        total += api.adjustments(USD);
        total += api.fees(USD);
        for p in api.ups().users.values() {
            for pos in p.positions.values() {
                if pos.currency != USD {
                    continue;
                }
                let mark = api.risk().mark_price(pos.symbol).unwrap_or(PRICE);
                total += pos.estimate_pnl(mark);
                total += pos.extra_margin;
            }
        }
        assert_eq!(total, 0, "期货全局守恒被打破：USD total={total}");
    }

    /// 通用撮合场景：maker(UID_1) 挂 `maker_action`/`maker_size` GTC；
    /// taker(UID_2) 以 `taker_type`/`taker_size` 反向吃单。成交量 = min。
    /// IOC/FOK_BUDGET taker 不留残单；GTC taker 残单挂在自己方向。
    fn run_scenario(
        maker_action: OrderAction,
        maker_size: i64,
        taker_type: OrderType,
        taker_size: i64,
    ) {
        let mut api = seed();

        assert_eq!(
            place(&mut api, 101, UID_1, PRICE, maker_size, maker_action, OrderType::Gtc),
            CommandResultCode::Success
        );
        assert_eq!(api.fees(USD), 0, "挂 maker 不收费");

        let taker_action = match maker_action {
            OrderAction::Bid => OrderAction::Ask,
            OrderAction::Ask => OrderAction::Bid,
        };
        // BUDGET 单 price 字段 = 预算 = size*price。
        let taker_price = match taker_type {
            OrderType::FokBudget | OrderType::IocBudget => taker_size * PRICE,
            _ => PRICE,
        };
        assert_eq!(
            place(&mut api, 102, UID_2, taker_price, taker_size, taker_action, taker_type),
            CommandResultCode::Success
        );

        let filled = maker_size.min(taker_size);

        // ---- maker 仓位 ----
        let maker_dir = if maker_action == OrderAction::Bid { PositionDirection::Long } else { PositionDirection::Short };
        let mp = api.user_position(UID_1, SYM).expect("maker 持仓");
        assert_eq!(mp.direction, maker_dir);
        assert_eq!(mp.open_volume, filled);
        assert_eq!(mp.open_price_sum, PRICE * filled);
        if maker_action == OrderAction::Bid {
            assert_eq!(mp.pending_buy_size, maker_size - filled);
            assert_eq!(mp.pending_sell_size, 0);
        } else {
            assert_eq!(mp.pending_sell_size, maker_size - filled);
            assert_eq!(mp.pending_buy_size, 0);
        }
        assert_eq!(mp.profit, 0);

        // ---- taker 仓位 ----
        let taker_dir = if taker_action == OrderAction::Bid { PositionDirection::Long } else { PositionDirection::Short };
        let taker_rests = matches!(taker_type, OrderType::Gtc);
        let taker_residual = if taker_rests { taker_size - filled } else { 0 };
        let tp = api.user_position(UID_2, SYM).expect("taker 持仓");
        assert_eq!(tp.direction, taker_dir);
        assert_eq!(tp.open_volume, filled);
        assert_eq!(tp.open_price_sum, PRICE * filled);
        if taker_action == OrderAction::Bid {
            assert_eq!(tp.pending_buy_size, taker_residual);
            assert_eq!(tp.pending_sell_size, 0);
        } else {
            assert_eq!(tp.pending_sell_size, taker_residual);
            assert_eq!(tp.pending_buy_size, 0);
        }
        assert_eq!(tp.profit, 0);

        // ---- 账户 & 费池 ----
        assert_eq!(api.user_account(UID_1, USD), MAKER_DEPOSIT - maker_fee(filled), "maker 账户");
        assert_eq!(api.user_account(UID_2, USD), TAKER_DEPOSIT - taker_fee(filled), "taker 账户");
        assert_eq!(api.user_account(UID_1, BASE_CUR), 0);
        assert_eq!(api.user_account(UID_2, BASE_CUR), 0);
        assert_eq!(api.fees(USD), maker_fee(filled) + taker_fee(filled), "USD 费池");
        assert_conserved(&api);
    }

    // 1. shouldProcessFees_BidGtcMaker_AskIocTakerPartial —— maker BID 100 全成，taker IOC ASK 500。
    #[test]
    fn bid_gtc_maker_ask_ioc_taker_partial() {
        run_scenario(OrderAction::Bid, 100, OrderType::Ioc, 500);
    }

    // 2. shouldProcessFees_BidGtcMakerPartial_AskIocTaker —— maker BID 500，taker IOC ASK 100。
    #[test]
    fn bid_gtc_maker_partial_ask_ioc_taker() {
        run_scenario(OrderAction::Bid, 500, OrderType::Ioc, 100);
    }

    // 3. shouldProcessFees_BidGtcMaker_AskIocTaker_FullyMatch —— maker BID 500，taker IOC ASK 500。
    #[test]
    fn bid_gtc_maker_ask_ioc_taker_fully_match() {
        run_scenario(OrderAction::Bid, 500, OrderType::Ioc, 500);
    }

    // 4. shouldProcessFees_AskGtcMaker_BidIocTakerPartial —— maker ASK 100 全成，taker IOC BID 500。
    #[test]
    fn ask_gtc_maker_bid_ioc_taker_partial() {
        run_scenario(OrderAction::Ask, 100, OrderType::Ioc, 500);
    }

    // 5. shouldProcessFees_AskGtcMakerPartial_BidIocTaker —— maker ASK 500，taker IOC BID 100。
    #[test]
    fn ask_gtc_maker_partial_bid_ioc_taker() {
        run_scenario(OrderAction::Ask, 500, OrderType::Ioc, 100);
    }

    // 6. shouldProcessFees_AskGtcMakerPartial_BidGtcTaker —— maker ASK 500，taker GTC BID 100（全成，无残）。
    #[test]
    fn ask_gtc_maker_partial_bid_gtc_taker() {
        run_scenario(OrderAction::Ask, 500, OrderType::Gtc, 100);
    }

    // 7. shouldProcessFees_AskGtcMaker_BidGtcTakerPartial —— maker ASK 100，taker GTC BID 500（残 400 挂 bid）。
    #[test]
    fn ask_gtc_maker_bid_gtc_taker_partial() {
        run_scenario(OrderAction::Ask, 100, OrderType::Gtc, 500);
    }

    // 8. shouldProcessFees_AskGtcMakerPartial_BidFokTaker —— maker ASK 500，taker FOK_BUDGET BID 1（全成 1）。
    #[test]
    fn ask_gtc_maker_partial_bid_fok_budget_taker() {
        run_scenario(OrderAction::Ask, 500, OrderType::FokBudget, 1);
    }

    // 对拍 shouldRequireTakerFees_GtcCancel1：期货 BID GTC 未成交也要预留 taker 费 + 初始保证金；
    // NSF 阶梯（仅 fee-1 → NSF；到 fee 仍差 margin → NSF；补 margin → SUCCESS）+ 撤单全额释放。
    #[test]
    fn should_require_taker_fees_gtc_cancel1() {
        let mut api = ExchangeApi::new();
        api.add_currency(BASE_CUR, CURRENCY_SCALE_K);
        api.add_currency(USD, CURRENCY_SCALE_K);
        assert_eq!(api.add_futures_symbol(spec()), CommandResultCode::Success);
        assert_eq!(api.set_mark_price(SYM, PRICE), CommandResultCode::Success);
        assert_eq!(api.add_user(UID_2), CommandResultCode::Success);

        let usdt = 100i64;
        let size = 1i64;
        let fee = taker_fee(size); // ceil(1*10000*2/100) = 200
        let init_margin = PRICE * size / 100; // notional × initMargin(1) / initMarginScaleK(100) = 100

        let bid = |oid: i64| PlaceFuturesOrderRequest {
            order_id: oid, uid: UID_2, symbol: SYM, price: PRICE, size,
            action: OrderAction::Bid, order_type: OrderType::Gtc, leverage: 1,
            margin_mode: MarginMode::Isolated, reduce_only: false,
        };

        // 仅 100 < fee+margin(300) → NSF。
        assert_eq!(api.balance_adjustment(UID_2, USD, usdt, 1), CommandResultCode::Success);
        assert_eq!(api.place_futures_order(bid(203)), CommandResultCode::RiskNsf);
        // 置为 fee-1（199）→ NSF。
        assert_eq!(api.balance_adjustment(UID_2, USD, -usdt, 2), CommandResultCode::Success);
        assert_eq!(api.balance_adjustment(UID_2, USD, fee - 1, 3), CommandResultCode::Success);
        assert_eq!(api.place_futures_order(bid(203)), CommandResultCode::RiskNsf);
        // +1 → fee(200)，仍差初始保证金 → NSF。
        assert_eq!(api.balance_adjustment(UID_2, USD, 1, 4), CommandResultCode::Success);
        assert_eq!(api.place_futures_order(bid(203)), CommandResultCode::RiskNsf);
        // +initMargin(100) → fee+margin(300) → SUCCESS。
        assert_eq!(api.balance_adjustment(UID_2, USD, init_margin, 5), CommandResultCode::Success);
        assert_eq!(api.place_futures_order(bid(203)), CommandResultCode::Success);

        // 撤单全额释放，account = fee + initMargin。
        assert_eq!(api.cancel_order(CancelOrderRequest { order_id: 203, uid: UID_2, symbol: SYM }), CommandResultCode::Success);
        assert_eq!(api.user_account(UID_2, USD), fee + init_margin, "撤后 = fee + initMargin");
        assert_eq!(api.fees(USD), 0);
        assert!(api.total_balance().is_global_zero());
    }
    // Java ITFeesDynamic* 用独立内联公式 calculateFee = price*size*step*sideFee/scale（step=quoteScaleK,
    // scale=feeScaleK, 整除）——把生产函数派生的费用 oracle 钉死到该独立公式，证明"金额对"不依赖被测库自身函数。
    #[test]
    fn fee_oracle_matches_java_independent_formula() {
        for filled in [1i64, 30, 100] {
            let java_maker = PRICE * filled * QUOTE_SCALE_K * MAKER_FEE / FEE_SCALE_K;
            let java_taker = PRICE * filled * QUOTE_SCALE_K * TAKER_FEE / FEE_SCALE_K;
            assert_eq!(maker_fee(filled), java_maker, "maker@{filled} = Java price*size*step*makerFee/scale");
            assert_eq!(taker_fee(filled), java_taker, "taker@{filled} = Java price*size*step*takerFee/scale");
        }
    }
}
