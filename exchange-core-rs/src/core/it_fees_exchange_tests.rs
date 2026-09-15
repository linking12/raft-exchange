//! 翻译自 Java 抽象基类 `exchange.core2.tests.integration.ITFeesExchange`（XBT/LTC 现货固定费）。
//!
//! Java 用 `TotalCurrencyBalanceReportResult`（fees / isGlobalBalancesAllZero）+ `validateUserState`
//! （`available` = accounts − exchangeLocked）对拍。Rust 映射：
//!   Java `getFees().get(cur)`            → `api.fees(cur)`
//!   Java `available(profile, cur)`       → `api.user_account(uid,cur) − api.user_locked(uid,cur)`
//!   Java `getAccounts().get(cur)`        → `api.user_account(uid,cur)`（含预留，现货 place 不从 accounts 扣）
//!   Java `isGlobalBalancesAllZero()`     → 每币种 Σ account + adjustments + fees == 0
//!
//! 逐字复刻 `ITFeesExchange.symbol`（静态字段，非 `SYMBOLSPECFEE_XBT_LTC`）：
//!   symbolId=9340(SYMBOL_EXCHANGE_FEE), CURRENCY_EXCHANGE_PAIR, base=XBT(3762) quote=LTC(4141),
//!   baseScaleK=1000, quoteScaleK=10, takerFee=1900 makerFee=700 feeScaleK=0（固定费）。
//!   货币 digit=8 → currency_scale_k=1e8；product scale = 1000*10 = 1e4 → 手续费缩放因子 1e4。
//!
//! 撮合费/余额均以 `core_arithmetic_utils` 复算（与引擎同一组公式），不硬编码放大后的数字。
//! 对撮合成交量的 quote(LTC) 精确余额（含 reserve/部分成交的 taker-hold 修正）由全局守恒兜底断言，
//! 逐用户 base(XBT) 余额直接闭式断言。
//!
//! 未翻译：各 @Test 尾部 `checkFeeAfterResetFee`（`ApiResetFee`）——harness 无 RESET-FEE 命令。

#[cfg(test)]
mod tests {
    use crate::core::common::cmd::command_result_code::CommandResultCode;
    use crate::core::common::core_symbol_specification::CoreSymbolSpecification;
    use crate::core::common::order_action::OrderAction;
    use crate::core::common::order_type::OrderType;
    use crate::core::common::symbol_type::SymbolType;
    use crate::core::exchange_api::{CancelOrderRequest, ExchangeApi, PlaceOrderRequest};
    use crate::core::utils::core_arithmetic_utils::{
        calculate_amount_bid_taker_fee, calculate_maker_fee, calculate_taker_fee,
        size_price_to_currency_scale, symbol_to_currency_scale,
    };

    const XBT: i32 = 3762; // base（satoshi）
    const LTC: i32 = 4141; // quote（litoshi）
    const SYM: i32 = 9340; // SYMBOL_EXCHANGE_FEE
    const UID_1: i64 = 1_440_001;
    const UID_2: i64 = 1_440_002;

    const BASE_SCALE_K: i64 = 1_000;
    const QUOTE_SCALE_K: i64 = 10;
    const CURRENCY_SCALE_K: i64 = 100_000_000; // digit 8
    const MAKER_FEE: i64 = 700;
    const TAKER_FEE: i64 = 1_900;
    const FEE_SCALE_K: i64 = 0;

    // 撮合价（maker order101 = 11_500 × quoteScaleK）。
    const PRICE: i64 = 11_500 * QUOTE_SCALE_K;

    fn spec() -> CoreSymbolSpecification {
        CoreSymbolSpecification {
            symbol_id: SYM,
            symbol_type: SymbolType::CurrencyExchangePair,
            base_currency: XBT,
            quote_currency: LTC,
            base_scale_k: BASE_SCALE_K,
            quote_scale_k: QUOTE_SCALE_K,
            taker_fee: TAKER_FEE,
            maker_fee: MAKER_FEE,
            fee_scale_k: FEE_SCALE_K,
            ..Default::default()
        }
    }

    fn new_api() -> ExchangeApi {
        let mut api = ExchangeApi::new();
        api.add_currency(XBT, CURRENCY_SCALE_K);
        api.add_currency(LTC, CURRENCY_SCALE_K);
        assert_eq!(api.add_symbol(spec()), CommandResultCode::Success);
        api
    }

    fn user_money(api: &mut ExchangeApi, uid: i64, cur: i32, amount: i64, txid: i64) {
        assert_eq!(api.add_user(uid), CommandResultCode::Success);
        assert_eq!(api.balance_adjustment(uid, cur, amount, txid), CommandResultCode::Success);
    }

    fn bid(order_id: i64, uid: i64, price: i64, reserve: i64, size: i64, ot: OrderType) -> PlaceOrderRequest {
        PlaceOrderRequest { order_id, uid, symbol: SYM, price, size, reserve_bid_price: reserve, action: OrderAction::Bid, order_type: ot }
    }
    fn ask(order_id: i64, uid: i64, price: i64, size: i64, ot: OrderType) -> PlaceOrderRequest {
        PlaceOrderRequest { order_id, uid, symbol: SYM, price, size, reserve_bid_price: 0, action: OrderAction::Ask, order_type: ot }
    }

    /// 撮合成交量 `filled`（@`price`）入池的 LTC 手续费（maker+taker，缩放到币种单位）。
    fn fee_pool(filled: i64, price: i64) -> i64 {
        if filled == 0 {
            return 0;
        }
        let maker = size_price_to_currency_scale(
            calculate_maker_fee(filled, price, MAKER_FEE, FEE_SCALE_K),
            BASE_SCALE_K,
            QUOTE_SCALE_K,
            CURRENCY_SCALE_K,
        );
        let taker = size_price_to_currency_scale(
            calculate_taker_fee(filled, price, TAKER_FEE, FEE_SCALE_K),
            BASE_SCALE_K,
            QUOTE_SCALE_K,
            CURRENCY_SCALE_K,
        );
        maker + taker
    }

    /// base（撮合单位）→ XBT 币种单位。
    fn base_amt(size: i64) -> i64 {
        symbol_to_currency_scale(size, BASE_SCALE_K, CURRENCY_SCALE_K)
    }

    /// Java `available` 等价：accounts − exchangeLocked。
    fn available(api: &ExchangeApi, uid: i64, cur: i32) -> i64 {
        api.user_account(uid, cur) - api.user_locked(uid, cur)
    }

    /// 每币种全局守恒（== `isGlobalBalancesAllZero`）：Σ account + adjustments + fees == 0。
    fn conserved(api: &ExchangeApi, cur: i32) -> i64 {
        api.ups().users.values().map(|p| p.account(cur)).sum::<i64>() + api.adjustments(cur) + api.fees(cur)
    }

    /// 撮合成交后统一断言：LTC 费池、XBT 逐用户闭式余额、双币守恒。
    /// `buyer` 收到 base，`seller` 交出 base；`*_xbt_dep` 为各自初始 XBT 充值（无则 0）。
    fn assert_outcome(
        api: &ExchangeApi,
        filled: i64,
        price: i64,
        buyer: i64,
        buyer_xbt_dep: i64,
        seller: i64,
        seller_xbt_dep: i64,
    ) {
        assert_eq!(api.fees(LTC), fee_pool(filled, price), "LTC 费池");
        assert_eq!(api.fees(XBT), 0, "XBT 不收费");
        assert_eq!(api.user_account(buyer, XBT), buyer_xbt_dep + base_amt(filled), "buyer XBT");
        assert_eq!(api.user_account(seller, XBT), seller_xbt_dep - base_amt(filled), "seller XBT");
        assert_eq!(conserved(api, XBT), 0, "XBT 守恒");
        assert_eq!(conserved(api, LTC), 0, "LTC 守恒");
    }

    // ================================================================================================
    // 1. shouldRequireTakerFees_GtcCancel —— BID 需覆盖 taker fee 才能挂（NSF 边界），撤单不收费；ASK 挂/撤不收费。
    // ================================================================================================

    #[test]
    fn should_require_taker_fees_gtc_cancel() {
        let mut api = new_api();
        let price = 11_400i64;
        let size = 30i64;
        // 挂未成交 BID 的资金门槛 = size_price_to_currency_scale(name+takerFee)。
        let need = size_price_to_currency_scale(
            calculate_amount_bid_taker_fee(size, price, TAKER_FEE, FEE_SCALE_K),
            BASE_SCALE_K,
            QUOTE_SCALE_K,
            CURRENCY_SCALE_K,
        );

        // 充 need-1 → 不足。
        user_money(&mut api, UID_2, LTC, need - 1, 1);
        assert_eq!(
            api.place_order(bid(203, UID_2, price, price, size, OrderType::Gtc)),
            CommandResultCode::RiskNsf,
            "差 1 litoshi 应 NSF"
        );
        // 补 1 → 恰好够，挂单成功。
        assert_eq!(api.balance_adjustment(UID_2, LTC, 1, 2), CommandResultCode::Success);
        assert_eq!(
            api.place_order(bid(203, UID_2, price, price, size, OrderType::Gtc)),
            CommandResultCode::Success
        );
        assert_eq!(api.fees(LTC), 0, "挂单不收费");
        assert_eq!(available(&api, UID_2, LTC), 0, "全额被预留");

        // 撤单 → 资金释放，全程 0 费。
        assert_eq!(api.cancel_order(CancelOrderRequest { order_id: 203, uid: UID_2, symbol: SYM }), CommandResultCode::Success);
        assert_eq!(available(&api, UID_2, LTC), need, "撤后可用回到 need");
        assert_eq!(api.fees(LTC), 0);
        assert_eq!(conserved(&api, LTC), 0);

        // ---- ASK 挂/撤：不需要额外手续费预留，撤后 base 全回，0 费 ----
        let btc_amount = 100_000_000i64;
        assert_eq!(api.balance_adjustment(UID_2, XBT, btc_amount, 3), CommandResultCode::Success);
        assert_eq!(api.place_order(ask(204, UID_2, price, 100, OrderType::Gtc)), CommandResultCode::Success);
        assert_eq!(api.cancel_order(CancelOrderRequest { order_id: 204, uid: UID_2, symbol: SYM }), CommandResultCode::Success);
        assert_eq!(available(&api, UID_2, XBT), btc_amount, "撤后 XBT 全回");
        assert_eq!(api.fees(LTC), 0);
        assert_eq!(api.fees(XBT), 0);
        assert_eq!(conserved(&api, LTC), 0);
        assert_eq!(conserved(&api, XBT), 0);
    }

    // 公共充值额（对齐 Java）。
    fn ltc_deposit() -> i64 {
        2_000_000_000i64 * CURRENCY_SCALE_K // 200B litoshi × scale
    }
    fn xbt_deposit() -> i64 {
        2_000i64 * CURRENCY_SCALE_K
    }

    // ================================================================================================
    // 2. shouldProcessFees_BidGtcMaker_AskIocTakerPartial —— maker BID 1731k 全成，taker IOC ASK 2000k。
    // ================================================================================================

    #[test]
    fn bid_gtc_maker_ask_ioc_taker_partial() {
        let mut api = new_api();
        let maker_size = 1731 * BASE_SCALE_K;
        user_money(&mut api, UID_1, LTC, ltc_deposit(), 1);
        assert_eq!(
            api.place_order(bid(101, UID_1, PRICE, 11_553 * QUOTE_SCALE_K, maker_size, OrderType::Gtc)),
            CommandResultCode::Success
        );
        assert_eq!(api.fees(LTC), 0);

        let xbt_dep = xbt_deposit();
        user_money(&mut api, UID_2, XBT, xbt_dep, 2);
        // taker IOC ASK 2000k @ 11_493×step → 成交 1731k（吃满 maker）。
        assert_eq!(
            api.place_order(ask(102, UID_2, 11_493 * QUOTE_SCALE_K, 2000 * BASE_SCALE_K, OrderType::Ioc)),
            CommandResultCode::Success
        );

        assert_outcome(&api, maker_size, PRICE, UID_1, 0, UID_2, xbt_dep);
    }

    // ================================================================================================
    // 3. shouldProcessFees_BidGtcMakerPartial_AskIocTaker —— maker BID 1731k，taker IOC ASK 1000k。
    // ================================================================================================

    #[test]
    fn bid_gtc_maker_partial_ask_ioc_taker() {
        let mut api = new_api();
        let maker_size = 1731 * BASE_SCALE_K;
        let taker_size = 1000 * BASE_SCALE_K;
        user_money(&mut api, UID_1, LTC, ltc_deposit(), 1);
        assert_eq!(
            api.place_order(bid(101, UID_1, PRICE, 11_553 * QUOTE_SCALE_K, maker_size, OrderType::Gtc)),
            CommandResultCode::Success
        );

        let xbt_dep = xbt_deposit();
        user_money(&mut api, UID_2, XBT, xbt_dep, 2);
        assert_eq!(
            api.place_order(ask(102, UID_2, 11_493 * QUOTE_SCALE_K, taker_size, OrderType::Ioc)),
            CommandResultCode::Success
        );

        // 成交 1000k；maker 残 731k 挂盘。
        assert_outcome(&api, taker_size, PRICE, UID_1, 0, UID_2, xbt_dep);
        let l2 = api.request_l2(SYM, 10);
        assert_eq!(l2.bid_prices, vec![PRICE]);
        assert_eq!(l2.bid_volumes, vec![maker_size - taker_size]);
    }

    // ================================================================================================
    // 4. shouldProcessFees_AskGtcMaker_BidIocTakerPartial —— maker ASK 2000k 全成，taker IOC BID 2197k。
    // ================================================================================================

    #[test]
    fn ask_gtc_maker_bid_ioc_taker_partial() {
        let mut api = new_api();
        let maker_size = 2000 * BASE_SCALE_K;
        let xbt_dep = xbt_deposit();
        user_money(&mut api, UID_1, XBT, xbt_dep, 1);
        assert_eq!(api.place_order(ask(101, UID_1, PRICE, maker_size, OrderType::Gtc)), CommandResultCode::Success);
        assert_eq!(api.fees(LTC), 0);

        user_money(&mut api, UID_2, LTC, ltc_deposit(), 2);
        // taker IOC BID 2197k @ 11_521×step, reserve 11_659×step → 成交 2000k。
        assert_eq!(
            api.place_order(bid(102, UID_2, 11_521 * QUOTE_SCALE_K, 11_659 * QUOTE_SCALE_K, 2197 * BASE_SCALE_K, OrderType::Ioc)),
            CommandResultCode::Success
        );

        assert_outcome(&api, maker_size, PRICE, UID_2, 0, UID_1, xbt_dep);
    }

    // ================================================================================================
    // 5. shouldProcessFees_AskGtcMakerPartial_BidGtcTaker —— maker ASK 2000k，taker GTC BID 1997k。
    // ================================================================================================

    #[test]
    fn ask_gtc_maker_partial_bid_gtc_taker() {
        let mut api = new_api();
        let maker_size = 2000 * BASE_SCALE_K;
        let taker_size = 1997 * BASE_SCALE_K;
        let xbt_dep = xbt_deposit();
        user_money(&mut api, UID_1, XBT, xbt_dep, 1);
        assert_eq!(api.place_order(ask(101, UID_1, PRICE, maker_size, OrderType::Gtc)), CommandResultCode::Success);

        user_money(&mut api, UID_2, LTC, ltc_deposit(), 2);
        // taker BID 1997k @ 11_521×step, reserve 11_659×step → 全成 1997k（maker 残 3k）。
        assert_eq!(
            api.place_order(bid(102, UID_2, 11_521 * QUOTE_SCALE_K, 11_659 * QUOTE_SCALE_K, taker_size, OrderType::Gtc)),
            CommandResultCode::Success
        );

        assert_outcome(&api, taker_size, PRICE, UID_2, 0, UID_1, xbt_dep);
    }

    // ================================================================================================
    // 6. shouldNotProcessFees_AskGtcMakerPartial_BidFokTaker —— FOK_BUDGET 预算不足 → 不成交，0 费。
    // ================================================================================================

    #[test]
    fn should_not_process_fees_ask_gtc_maker_partial_bid_fok_taker() {
        let mut api = new_api();
        let maker_size = 2000 * BASE_SCALE_K;
        let xbt_dep = xbt_deposit();
        user_money(&mut api, UID_1, XBT, xbt_dep, 1);
        assert_eq!(api.place_order(ask(101, UID_1, PRICE, maker_size, OrderType::Gtc)), CommandResultCode::Success);

        let ltc_dep = ltc_deposit();
        user_money(&mut api, UID_2, LTC, ltc_dep, 2);
        // FOK_BUDGET price 字段 = 预算 = 单档步价（远不够 1000k 全成）→ 整单 kill。
        assert_eq!(
            api.place_order(bid(102, UID_2, PRICE, PRICE, 1000 * BASE_SCALE_K, OrderType::FokBudget)),
            CommandResultCode::Success
        );

        // 未成交：0 费，双方余额不动。
        assert_outcome(&api, 0, PRICE, UID_2, 0, UID_1, xbt_dep);
        assert_eq!(api.user_account(UID_2, LTC), ltc_dep, "taker LTC 未动");
    }

    // ================================================================================================
    // 7. shouldProcessFees_AskGtcMakerPartial_BidFokTaker —— FOK_BUDGET 预算充足 → 全成 1000k。
    // ================================================================================================

    #[test]
    fn should_process_fees_ask_gtc_maker_partial_bid_fok_taker() {
        let mut api = new_api();
        let maker_size = 2000 * BASE_SCALE_K;
        let taker_size = 1000 * BASE_SCALE_K;
        let xbt_dep = xbt_deposit();
        user_money(&mut api, UID_1, XBT, xbt_dep, 1);
        assert_eq!(api.place_order(ask(101, UID_1, PRICE, maker_size, OrderType::Gtc)), CommandResultCode::Success);

        user_money(&mut api, UID_2, LTC, ltc_deposit(), 2);
        // FOK_BUDGET 预算 = 全成 notional（size × 撮合价）。
        let budget = taker_size * PRICE;
        assert_eq!(
            api.place_order(bid(102, UID_2, budget, budget, taker_size, OrderType::FokBudget)),
            CommandResultCode::Success
        );

        assert_outcome(&api, taker_size, PRICE, UID_2, 0, UID_1, xbt_dep);
    }
}
