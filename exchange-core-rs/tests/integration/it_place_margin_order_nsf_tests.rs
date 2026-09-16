//! 翻译自 Java `exchange.core2.tests.integration.ITPlaceMarginOrderNsfChecks`（`RiskEngine.canPlaceMarginOrder`
//! 期货下单 NSF 校验，9 个 @Test）。直连引擎（无 raft/Disruptor，撮合同步落地——Java 的 `groupingControl(0,1)`
//! 强制 R2 在本 harness 里天然满足，故省略）。逐条对拍 Java 黄金结果码（RiskNsf / Success）+ 仓位态
//! （direction/open_volume/open_init_margin_sum/pending_*）+ accounts + 全局守恒。
//!
//! 期货 symbol 精确复刻 Java `ExchangeTestContainer.initFutureSymbol(symbolId, CURRENECY_USD)`：
//!   base=BASE_CURRENCY_ID(1)、quote=USD(840)、base/quoteScaleK=1、makerFee=10、takerFee=20、feeScaleK=0（固定费）、
//!   maintenance={1000:5,100000:10}@scaleK=1000、maxLeverage={2000:5,100000:10}、initMargin=1@scaleK=100。
//!   → 初始保证金率 1%/leverage，taker 费 fixed 20，维持保证金率 0.5%。
//!
//! **跳过的 @Test（需 harness 扩展）**：
//!   - `hedge_oppositeLegSubtractsSiblingIM`：用 `ApiAdjustPositionMode` 切 HEDGE + 同 symbol ±腿双持仓；
//!     `ExchangeApi` 未暴露 position-mode 切换，`user_position(uid,symbol)` 只处理 ONEWAY 单键，无法建/验双腿。
//!   其余 8 个已翻译。

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
    use exchange_core_rs::core::exchange_api::{ExchangeApi, PlaceFuturesOrderRequest};

    const BASE: i32 = 1; // BASE_CURRENCY_ID
    const USD: i32 = 840; // CURRENECY_USD

    const SYMBOL: i32 = 5001;
    const LEVERAGE: i32 = 5;
    const MARK_PRICE: i64 = 1000;
    const OPEN_SIZE: i64 = 5;

    // Java TestConstants UID_1/2 值本身无关紧要，只需引擎内唯一。
    const TRADER: i64 = 1;
    const LP: i64 = 2;

    fn mm_table() -> BTreeMap<i64, i64> {
        BTreeMap::from([(1_000, 5), (100_000, 10)])
    }
    fn leverage_table() -> BTreeMap<i64, i64> {
        BTreeMap::from([(2_000, 5), (100_000, 10)])
    }

    /// 精确复刻 Java `initFutureSymbol(symbol_id, USD)`。
    fn futures_spec(symbol_id: i32) -> CoreSymbolSpecification {
        CoreSymbolSpecification {
            symbol_id,
            symbol_type: SymbolType::FuturesContractPerpetual,
            base_currency: BASE,
            quote_currency: USD,
            base_scale_k: 1,
            quote_scale_k: 1,
            maker_fee: 10,
            taker_fee: 20,
            fee_scale_k: 0,
            maintenance_margin: mm_table(),
            maintenance_margin_scale_k: 1_000,
            max_leverage: leverage_table(),
            init_margin: 1,
            init_margin_scale_k: 100,
            ..Default::default()
        }
    }

    /// 注册 currencies + 单个期货 symbol + 设 mark 价（对应 initFutureSymbol + addCurrency + initMarkPrice）。
    fn setup_single(mark: i64) -> ExchangeApi {
        let mut api = ExchangeApi::new();
        api.add_currency(BASE, 1);
        api.add_currency(USD, 1);
        assert_eq!(api.add_futures_symbol(futures_spec(SYMBOL)), CommandResultCode::Success);
        assert_eq!(api.set_mark_price(SYMBOL, mark, 0), CommandResultCode::Success);
        api
    }

    /// 对应 `createUserWithSpecificMoney(uid, amount, USD)`。
    fn add_user_money(api: &mut ExchangeApi, uid: i64, amount: i64, txid: i64) {
        assert_eq!(api.add_user(uid), CommandResultCode::Success);
        assert_eq!(api.balance_adjustment(uid, USD, amount, txid), CommandResultCode::Success);
    }

    #[allow(clippy::too_many_arguments)]
    fn place(
        api: &mut ExchangeApi,
        order_id: i64,
        uid: i64,
        symbol: i32,
        price: i64,
        size: i64,
        action: OrderAction,
        order_type: OrderType,
        margin_mode: MarginMode,
        leverage: i32,
    ) -> CommandResultCode {
        api.place_futures_order(PlaceFuturesOrderRequest {
            order_id,
            uid,
            symbol,
            price,
            size,
            action,
            order_type,
            leverage,
            margin_mode,
            reduce_only: false,
        })
    }

    /// 全局守恒（完整公式）：Σ accounts + adjustments + fees + Σ_open_positions(estimate_pnl(mark)+extra_margin) == 0。
    /// scale_k 恒为 1，estimate_pnl/extra_margin 可直接求和。
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

    // ==========================================================================================
    // openLoss 预留：Place Order Cost = Required Margin + Open Loss（doc §1）。
    // ==========================================================================================

    /// openLoss_bidAboveMark_rejectedByNSF：mark=1000，BID @2000 size5：
    ///   IM = 10000×1/(100×5)=20，openLoss = 5×(2000−1000)=5000，fee=100 → 需 5120；deposit=300 → RiskNsf。
    #[test]
    fn open_loss_bid_above_mark_rejected_by_nsf() {
        let mut api = setup_single(MARK_PRICE);
        add_user_money(&mut api, TRADER, 300, 1);
        add_user_money(&mut api, LP, 10_000_000, 2);

        assert_eq!(
            place(&mut api, 10001, LP, SYMBOL, 2000, OPEN_SIZE, OrderAction::Ask, OrderType::Gtc, MarginMode::Cross, LEVERAGE),
            CommandResultCode::Success
        );
        assert_eq!(
            place(&mut api, 10002, TRADER, SYMBOL, 2000, OPEN_SIZE, OrderAction::Bid, OrderType::Gtc, MarginMode::Isolated, LEVERAGE),
            CommandResultCode::RiskNsf
        );
        assert!(api.user_position(TRADER, SYMBOL).is_none());
        assert_conserved(&api);
    }

    /// openLoss_bidAboveMark_acceptedWithSufficientBalance：同上 deposit=6000 > 5120 → Success，开 LONG 5。
    #[test]
    fn open_loss_bid_above_mark_accepted_with_sufficient_balance() {
        let mut api = setup_single(MARK_PRICE);
        add_user_money(&mut api, TRADER, 6000, 1);
        add_user_money(&mut api, LP, 10_000_000, 2);

        assert_eq!(
            place(&mut api, 10001, LP, SYMBOL, 2000, OPEN_SIZE, OrderAction::Ask, OrderType::Gtc, MarginMode::Cross, LEVERAGE),
            CommandResultCode::Success
        );
        assert_eq!(
            place(&mut api, 10002, TRADER, SYMBOL, 2000, OPEN_SIZE, OrderAction::Bid, OrderType::Gtc, MarginMode::Isolated, LEVERAGE),
            CommandResultCode::Success
        );

        let pos = api.user_position(TRADER, SYMBOL).expect("开仓后必有仓位");
        assert_eq!(pos.direction, PositionDirection::Long);
        assert_eq!(pos.open_volume, OPEN_SIZE);
        assert_conserved(&api);
    }

    /// openLoss_askBelowMark_rejectedByNSF：mark=1000，ASK @500 size5（贱卖）：
    ///   IM=2500×1/(100×5)=5，openLoss=5×(1000−500)=2500，fee=100 → 需 2605；deposit=1000 → RiskNsf。
    #[test]
    fn open_loss_ask_below_mark_rejected_by_nsf() {
        let mut api = setup_single(MARK_PRICE);
        add_user_money(&mut api, TRADER, 1000, 1);
        add_user_money(&mut api, LP, 10_000_000, 2);

        assert_eq!(
            place(&mut api, 40001, LP, SYMBOL, 500, OPEN_SIZE, OrderAction::Bid, OrderType::Gtc, MarginMode::Cross, LEVERAGE),
            CommandResultCode::Success
        );
        assert_eq!(
            place(&mut api, 40002, TRADER, SYMBOL, 500, OPEN_SIZE, OrderAction::Ask, OrderType::Gtc, MarginMode::Isolated, LEVERAGE),
            CommandResultCode::RiskNsf
        );
        assert!(api.user_position(TRADER, SYMBOL).is_none());
        assert_conserved(&api);
    }

    /// openLoss_onewayReverseOrder_truncatedToOpeningPortion：LONG5@1000 后 ASK 10@500（ONEWAY 反向大单）：
    ///   openingSize=max(0,10−5)=5，openLoss 只对超出部分=5×(1000−500)=2500（非 10×500=5000），
    ///   pendingFee=10×20=200，positionMargin 回退到 openInitMarginSum=10 → 需 2710；
    ///   deposit=3000（开仓 fee100 后剩 2900）≥ 2710 → Success（不截断则需 5210 会 NSF）。
    #[test]
    fn open_loss_oneway_reverse_order_truncated_to_opening_portion() {
        let mut api = setup_single(MARK_PRICE);
        add_user_money(&mut api, TRADER, 3000, 1);
        add_user_money(&mut api, LP, 10_000_000, 2);

        assert_eq!(
            place(&mut api, 50001, LP, SYMBOL, MARK_PRICE, OPEN_SIZE, OrderAction::Ask, OrderType::Gtc, MarginMode::Cross, LEVERAGE),
            CommandResultCode::Success
        );
        assert_eq!(
            place(&mut api, 50002, TRADER, SYMBOL, MARK_PRICE, OPEN_SIZE, OrderAction::Bid, OrderType::Gtc, MarginMode::Isolated, LEVERAGE),
            CommandResultCode::Success
        );
        // 撮合同步落地：LONG 开仓 pending 已释放、open_init_margin_sum 已写入（对应 Java groupingControl(0,1)）。
        assert_eq!(api.user_position(TRADER, SYMBOL).unwrap().open_init_margin_sum, 10);

        assert_eq!(
            place(&mut api, 50003, TRADER, SYMBOL, 500, 10, OrderAction::Ask, OrderType::Gtc, MarginMode::Isolated, LEVERAGE),
            CommandResultCode::Success
        );
        assert_conserved(&api);
    }

    // ==========================================================================================
    // order margin：反向纯减仓不占额外保证金；同向加仓正常锁 orderMargin（doc §7）。
    // ==========================================================================================

    /// orderMargin_reduceSideOffset_pureReduceNoExtraMargin：LONG5@1000（fee100 后 accounts=115，
    ///   openInitMarginSum=10），再挂 ASK 5@1000（纯反向减仓）：required = openInitMarginSum(10) + askFee(100)
    ///   = 110 ≤ 115 → Success（若错误地对 pending 收 IM 会 NSF）。
    #[test]
    fn order_margin_reduce_side_offset_pure_reduce_no_extra_margin() {
        let mut api = setup_single(MARK_PRICE);
        add_user_money(&mut api, TRADER, 215, 1);
        add_user_money(&mut api, LP, 10_000_000, 2);

        assert_eq!(
            place(&mut api, 20001, LP, SYMBOL, MARK_PRICE, OPEN_SIZE, OrderAction::Ask, OrderType::Gtc, MarginMode::Cross, LEVERAGE),
            CommandResultCode::Success
        );
        assert_eq!(
            place(&mut api, 20002, TRADER, SYMBOL, MARK_PRICE, OPEN_SIZE, OrderAction::Bid, OrderType::Gtc, MarginMode::Isolated, LEVERAGE),
            CommandResultCode::Success
        );

        let pos = api.user_position(TRADER, SYMBOL).expect("开仓后必有仓位");
        assert_eq!(pos.open_init_margin_sum, 10, "开仓后 openInitMarginSum=10");
        assert_eq!(api.user_account(TRADER, USD), 115, "taker fee 100 已扣");

        assert_eq!(
            place(&mut api, 20003, TRADER, SYMBOL, MARK_PRICE, OPEN_SIZE, OrderAction::Ask, OrderType::Gtc, MarginMode::Isolated, LEVERAGE),
            CommandResultCode::Success
        );
        assert_conserved(&api);
    }

    /// orderMargin_sameSideOpen_reservesPendingIM：LONG5@1000 再挂 BID 3@500（同向加仓，未成交挂单）：
    ///   pending_buy_size=3、pending_buy_avg_price=500；deposit 充足 → Success。
    #[test]
    fn order_margin_same_side_open_reserves_pending_im() {
        let mut api = setup_single(MARK_PRICE);
        add_user_money(&mut api, TRADER, 10_000, 1);
        add_user_money(&mut api, LP, 10_000_000, 2);

        assert_eq!(
            place(&mut api, 30001, LP, SYMBOL, MARK_PRICE, OPEN_SIZE, OrderAction::Ask, OrderType::Gtc, MarginMode::Cross, LEVERAGE),
            CommandResultCode::Success
        );
        assert_eq!(
            place(&mut api, 30002, TRADER, SYMBOL, MARK_PRICE, OPEN_SIZE, OrderAction::Bid, OrderType::Gtc, MarginMode::Isolated, LEVERAGE),
            CommandResultCode::Success
        );

        assert_eq!(
            place(&mut api, 30003, TRADER, SYMBOL, 500, 3, OrderAction::Bid, OrderType::Gtc, MarginMode::Isolated, LEVERAGE),
            CommandResultCode::Success
        );

        let pos = api.user_position(TRADER, SYMBOL).expect("加仓挂单后仍持仓");
        assert_eq!(pos.pending_buy_size, 3);
        assert_eq!(pos.pending_sell_size, 0);
        assert_eq!(pos.pending_buy_avg_price, 500);
        assert_eq!(pos.open_volume, OPEN_SIZE, "挂单未成交，持仓量不变");
        assert_conserved(&api);
    }

    // ==========================================================================================
    // ISOLATED 仓位隔离：ISOLATED 浮盈不参与 cross 抵扣；CROSS 浮盈可参与。两 symbol，A 拉高 mark 造浮盈。
    // set_mark_price(A) 从 1000→2000 对 LONG 是利好、对 SHORT LP（余额巨大）不触发强平，故安全。
    // ==========================================================================================

    /// 建两个期货 symbol + 各设 mark。
    fn setup_two(symbol_a: i32, symbol_b: i32, mark: i64) -> ExchangeApi {
        let mut api = ExchangeApi::new();
        api.add_currency(BASE, 1);
        api.add_currency(USD, 1);
        assert_eq!(api.add_futures_symbol(futures_spec(symbol_a)), CommandResultCode::Success);
        assert_eq!(api.add_futures_symbol(futures_spec(symbol_b)), CommandResultCode::Success);
        assert_eq!(api.set_mark_price(symbol_a, mark, 0), CommandResultCode::Success);
        assert_eq!(api.set_mark_price(symbol_b, mark, 0), CommandResultCode::Success);
        api
    }

    /// isolatedCrossSubsidy_isolatedPnlBlockedFromCrossCapacity：
    ///   A ISOLATED LONG 5@1000（fee100 后 accounts=10，openInitMarginSum=10），拉 mark(A)→2000（A.PnL=+5000）；
    ///   开 B CROSS LONG 5@1000：crossFreeMargin = 0（ISOLATED 浮盈不算）− A保证金(10) = −10；
    ///   required = (10+100+0) − (−10) = 120 > spendable(10) → RiskNsf。
    #[test]
    fn isolated_cross_subsidy_isolated_pnl_blocked_from_cross_capacity() {
        let symbol_a = 7001;
        let symbol_b = 7002;
        let mut api = setup_two(symbol_a, symbol_b, MARK_PRICE);
        add_user_money(&mut api, TRADER, 110, 1);
        add_user_money(&mut api, LP, 10_000_000, 2);

        // A 开 ISOLATED LONG 5@1000。
        assert_eq!(
            place(&mut api, 70001, LP, symbol_a, MARK_PRICE, OPEN_SIZE, OrderAction::Ask, OrderType::Gtc, MarginMode::Cross, LEVERAGE),
            CommandResultCode::Success
        );
        assert_eq!(
            place(&mut api, 70002, TRADER, symbol_a, MARK_PRICE, OPEN_SIZE, OrderAction::Bid, OrderType::Gtc, MarginMode::Isolated, LEVERAGE),
            CommandResultCode::Success
        );

        let pos_a = api.user_position(TRADER, symbol_a).expect("A 开仓后必有仓位");
        assert_eq!(pos_a.margin_mode, MarginMode::Isolated, "A ISOLATED");
        assert_eq!(pos_a.open_init_margin_sum, 10, "A LONG openInitMarginSum=10");
        assert_eq!(api.user_account(TRADER, USD), 10, "扣 fee 100 后 accounts=10");

        // 拉高 mark(A) 到 2000 → A.PnL = +5000。
        assert_eq!(api.set_mark_price(symbol_a, 2000, 0), CommandResultCode::Success);

        // B CROSS LONG 5@1000：ISOLATED A 的浮盈不该抵扣到 cross → NSF。
        assert_eq!(
            place(&mut api, 70003, LP, symbol_b, MARK_PRICE, OPEN_SIZE, OrderAction::Ask, OrderType::Gtc, MarginMode::Cross, LEVERAGE),
            CommandResultCode::Success
        );
        assert_eq!(
            place(&mut api, 70004, TRADER, symbol_b, MARK_PRICE, OPEN_SIZE, OrderAction::Bid, OrderType::Gtc, MarginMode::Cross, LEVERAGE),
            CommandResultCode::RiskNsf
        );
        assert!(api.user_position(TRADER, symbol_b).is_none(), "B NSF 未建仓");
        assert_conserved(&api);
    }

    /// isolatedCrossSubsidy_crossPnlAllowedIntoCrossCapacity：同上但 A 用 CROSS：
    ///   crossFreeMargin = A.PnL(5000) − A保证金(10) = 4990；required = 110 − 4990 = −4880 → Success。
    #[test]
    fn isolated_cross_subsidy_cross_pnl_allowed_into_cross_capacity() {
        let symbol_a = 7101;
        let symbol_b = 7102;
        let mut api = setup_two(symbol_a, symbol_b, MARK_PRICE);
        add_user_money(&mut api, TRADER, 110, 1);
        add_user_money(&mut api, LP, 10_000_000, 2);

        assert_eq!(
            place(&mut api, 80001, LP, symbol_a, MARK_PRICE, OPEN_SIZE, OrderAction::Ask, OrderType::Gtc, MarginMode::Cross, LEVERAGE),
            CommandResultCode::Success
        );
        assert_eq!(
            place(&mut api, 80002, TRADER, symbol_a, MARK_PRICE, OPEN_SIZE, OrderAction::Bid, OrderType::Gtc, MarginMode::Cross, LEVERAGE),
            CommandResultCode::Success
        );

        assert_eq!(api.set_mark_price(symbol_a, 2000, 0), CommandResultCode::Success);

        assert_eq!(
            place(&mut api, 80003, LP, symbol_b, MARK_PRICE, OPEN_SIZE, OrderAction::Ask, OrderType::Gtc, MarginMode::Cross, LEVERAGE),
            CommandResultCode::Success
        );
        assert_eq!(
            place(&mut api, 80004, TRADER, symbol_b, MARK_PRICE, OPEN_SIZE, OrderAction::Bid, OrderType::Gtc, MarginMode::Cross, LEVERAGE),
            CommandResultCode::Success
        );

        // CROSS 浮盈允许进 cross capacity：B 开出后 A、B 两条仓位都存在（对应 Java positions.size()==2）。
        assert!(api.user_position(TRADER, symbol_a).is_some(), "A CROSS 仓位存在");
        assert!(api.user_position(TRADER, symbol_b).is_some(), "B CROSS 仓位开出");
        assert_conserved(&api);
    }
}
