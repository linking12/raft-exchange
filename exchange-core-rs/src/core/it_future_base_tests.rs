//! 翻译自 Java `exchange.core2.tests.integration.ITFutureBase`（期货集成测试基类）。
//!
//! ITFutureBase 是抽象基类：其 `@Test` 方法（`testMultiBuy*` / `testMultiSell*`）本身不含逻辑，只把
//! `(symbolSpec, orderType, rejectionCause)` 组合委托给由子类 `ITFutureBasic` 实现的 `testMultiBuy` /
//! `testMultiSell`。本文件复刻这些参数化用例中**期货（MARGIN）symbol** 的分支：多个 maker 挂单 + 一笔大额
//! taker 单扫单，断言全局守恒（对应 Java `container.totalBalanceReport().isGlobalBalancesAllZero()`）。
//!
//! MARGIN symbol 规格精确复刻 `ITFutureBasic.doInit` 内联构造的 SYMBOL_MARGIN 期货 spec：
//!   base=USD(840)/quote=JPY(392)、base/quoteScaleK=1、makerFee=2、takerFee=3、固定费（fee_scale_k=0）、
//!   maintenanceMargin={1000:5,100000:10}（未设 scaleK -> 0 -> 维持保证金取 100%）、
//!   maxLeverage={2000:5,100000:10}、initMargin=1@scaleK=21；mark 价 = 10000（initFeeSymbolsMarkPrice）。
//! 用户 = UID_1..UID_4，各充 JPY 10_000_000（对应 `initFeeUser`，此处只需 quote 币）。
//!
//! **不可复刻而跳过**（harness 缺相应基础设施）：
//!   - Java `doCheckEvtCnt` 的跨命令 `verify(handler, times(N)).*ExecutionReport(...)` 事件计数 ——
//!     harness 无累计事件处理器，只暴露 `last_*`，故所有事件计数断言无法复刻。
//!   - EXCHANGE（现货 CURRENCY_EXCHANGE_PAIR，SYMBOLSPECFEE_XBT_LTC）变体 —— 属现货用例，已由
//!     `it_exchange_core_integration_tests.rs` 覆盖，不在期货文件重复。
//!   - `FOK_BUDGET` 变体与 `REJECTION_BY_BUDGET` —— budget 单需 reserve/budget 名义额字段，`place_futures_order`
//!     无此入参（期货风控不读 reserve），故 budget 单无法经 harness 提交；跳过。
//! 保留的可复刻分支：MARGIN × {GTC, IOC} × {NO_REJECTION, REJECTION_BY_SIZE}，断言下单结果码 + 全局守恒。

#[cfg(test)]
mod tests {
    use std::collections::BTreeMap;

    use crate::core::common::cmd::command_result_code::CommandResultCode;
    use crate::core::common::core_symbol_specification::CoreSymbolSpecification;
    use crate::core::common::margin_mode::MarginMode;
    use crate::core::common::order_action::OrderAction;
    use crate::core::common::order_type::OrderType;
    use crate::core::common::symbol_type::SymbolType;
    use crate::core::exchange_api::{ExchangeApi, PlaceFuturesOrderRequest};

    // Java `TestConstants`：SYMBOL_MARGIN=5991、CURRENECY_USD=840、CURRENECY_JPY=392、UID_1..4。
    const SYMBOL_MARGIN: i32 = 5991;
    const CURRENCY_USD: i32 = 840; // base
    const CURRENCY_JPY: i32 = 392; // quote（计费币）
    const MARK: i64 = 10_000;

    const UID_1: i64 = 1_440_001;
    const UID_2: i64 = 1_440_002;
    const UID_3: i64 = 1_440_003;
    const UID_4: i64 = 1_440_004;

    fn mm_table() -> BTreeMap<i64, i64> {
        BTreeMap::from([(1_000, 5), (100_000, 10)])
    }
    fn leverage_table() -> BTreeMap<i64, i64> {
        BTreeMap::from([(2_000, 5), (100_000, 10)])
    }

    /// 精确复刻 `ITFutureBasic.doInit` 内联 SYMBOL_MARGIN 期货 spec。
    fn margin_spec() -> CoreSymbolSpecification {
        CoreSymbolSpecification {
            symbol_id: SYMBOL_MARGIN,
            symbol_type: SymbolType::FuturesContractPerpetual,
            base_currency: CURRENCY_USD,
            quote_currency: CURRENCY_JPY,
            base_scale_k: 1,
            quote_scale_k: 1,
            maker_fee: 2,
            taker_fee: 3,
            fee_scale_k: 0, // 固定费
            maintenance_margin: mm_table(),
            maintenance_margin_scale_k: 0, // Java doInit 未设 -> 默认 0（维持保证金 = notional）
            max_leverage: leverage_table(),
            init_margin: 1,
            init_margin_scale_k: 21,
            ..Default::default()
        }
    }

    /// 建 currencies + 期货 symbol + mark 价 10000 + 4 名 fee 用户（各充 JPY 10_000_000）。
    fn setup() -> ExchangeApi {
        let mut api = ExchangeApi::new();
        api.add_currency(CURRENCY_USD, 1);
        api.add_currency(CURRENCY_JPY, 1);
        assert_eq!(api.add_futures_symbol(margin_spec()), CommandResultCode::Success);
        assert_eq!(api.set_mark_price(SYMBOL_MARGIN, MARK), CommandResultCode::Success);
        for uid in [UID_1, UID_2, UID_3, UID_4] {
            assert_eq!(api.add_user(uid), CommandResultCode::Success);
            assert_eq!(api.balance_adjustment(uid, CURRENCY_JPY, 10_000_000, 1), CommandResultCode::Success);
        }
        api
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
            symbol: SYMBOL_MARGIN,
            price,
            size,
            action,
            order_type,
            leverage: 0, // Java builderPlace 不设 leverage -> 默认 0 -> 引擎归一为 1
            margin_mode: MarginMode::Isolated,
            reduce_only: false,
        })
    }

    /// 全局守恒（完整公式）：对应 Java `isGlobalBalancesAllZero()`。见 `futures_e2e_tests.rs` 文件头。
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

    /// 对应 Java `testMultiBuy` 的 MARGIN 分支：4 个 ASK maker（合计 40 手）+ 一笔 BID taker 扫单。
    /// `sweep_size`：NO_REJECTION 用 40（正好扫完），REJECTION_BY_SIZE 用 41（多 1 手，GTC 残挂 / IOC 撤余）。
    fn run_multi_buy(order_type: OrderType, sweep_size: i64) {
        let mut api = setup();

        // makers（ASK）：UID_1 7@160000、UID_2 10@159900、UID_3 3@160000、UID_3 20@160500。
        assert_eq!(place(&mut api, 101, UID_1, 160_000, 7, OrderAction::Ask, OrderType::Gtc), CommandResultCode::Success);
        assert_eq!(place(&mut api, 202, UID_2, 159_900, 10, OrderAction::Ask, OrderType::Gtc), CommandResultCode::Success);
        assert_eq!(place(&mut api, 303, UID_3, 160_000, 3, OrderAction::Ask, OrderType::Gtc), CommandResultCode::Success);
        assert_eq!(place(&mut api, 304, UID_3, 160_500, 20, OrderAction::Ask, OrderType::Gtc), CommandResultCode::Success);

        // taker（BID）@160500 扫单。
        assert_eq!(place(&mut api, 405, UID_4, 160_500, sweep_size, OrderAction::Bid, order_type), CommandResultCode::Success);

        assert_conserved(&api);
    }

    /// 对应 Java `testMultiSell` 的 MARGIN 分支：4 个 BID maker（合计 22 手）+ 一笔 ASK taker 扫单。
    fn run_multi_sell(order_type: OrderType, sweep_size: i64) {
        let mut api = setup();

        // makers（BID）：UID_1 12@160000、UID_2 1@159900、UID_3 8@160000、UID_3 1@160500。
        assert_eq!(place(&mut api, 101, UID_1, 160_000, 12, OrderAction::Bid, OrderType::Gtc), CommandResultCode::Success);
        assert_eq!(place(&mut api, 202, UID_2, 159_900, 1, OrderAction::Bid, OrderType::Gtc), CommandResultCode::Success);
        assert_eq!(place(&mut api, 303, UID_3, 160_000, 8, OrderAction::Bid, OrderType::Gtc), CommandResultCode::Success);
        assert_eq!(place(&mut api, 304, UID_3, 160_500, 1, OrderAction::Bid, OrderType::Gtc), CommandResultCode::Success);

        // taker（ASK）@159900 扫单。
        assert_eq!(place(&mut api, 405, UID_4, 159_900, sweep_size, OrderAction::Ask, order_type), CommandResultCode::Success);

        assert_conserved(&api);
    }

    // -------------------------- buy no rejection --------------------------

    // testMultiBuyNoRejectionMarginGtc
    #[test]
    fn multi_buy_no_rejection_margin_gtc() {
        run_multi_buy(OrderType::Gtc, 40);
    }

    // testMultiBuyNoRejectionMarginIoc
    #[test]
    fn multi_buy_no_rejection_margin_ioc() {
        run_multi_buy(OrderType::Ioc, 40);
    }

    // -------------------------- buy with size rejection --------------------------

    // testMultiBuyWithRejectionMarginGtc（size=41：多 1 手，GTC 残量挂在盘口）。
    #[test]
    fn multi_buy_with_size_rejection_margin_gtc() {
        run_multi_buy(OrderType::Gtc, 41);
    }

    // testMultiBuyWithRejectionMarginIoc（size=41：多 1 手，IOC 撤销未成交余量）。
    #[test]
    fn multi_buy_with_size_rejection_margin_ioc() {
        run_multi_buy(OrderType::Ioc, 41);
    }

    // -------------------------- sell no rejection --------------------------

    // testMultiSellNoRejectionMarginGtc
    #[test]
    fn multi_sell_no_rejection_margin_gtc() {
        run_multi_sell(OrderType::Gtc, 22);
    }

    // testMultiSellNoRejectionMarginIoc
    #[test]
    fn multi_sell_no_rejection_margin_ioc() {
        run_multi_sell(OrderType::Ioc, 22);
    }

    // -------------------------- sell with size rejection --------------------------

    // testMultiSellWithRejectionMarginGtc（size=23）。
    #[test]
    fn multi_sell_with_size_rejection_margin_gtc() {
        run_multi_sell(OrderType::Gtc, 23);
    }

    // testMultiSellWithRejectionMarginIoc（size=23）。
    #[test]
    fn multi_sell_with_size_rejection_margin_ioc() {
        run_multi_sell(OrderType::Ioc, 23);
    }
}
