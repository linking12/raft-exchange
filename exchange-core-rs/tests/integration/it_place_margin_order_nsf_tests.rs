//! 对应 Java 测试类 `ITPlaceMarginOrderNsfChecks.java` 的移植：验证期货下单 NSF（资金不足）
//! 校验中 `openLoss` 预留（BID 超付/ASK 贱卖/ONEWAY 反向大单 openingSize 截断）、order margin
//! 反向 pending 单不占额外保证金、以及 ISOLATED 仓位浮盈不应被其它 symbol 的 CROSS 新单当资本
//! 使用（cross-subsidy 隔离）等场景。注：Java 侧另有 `hedge_oppositeLegSubtractsSiblingIM`
//! （HEDGE crossFreeMargin 对侧腿扣减）测试，本文件未移植对应场景。
//!
//! Symbol 规格沿用 `futures_spec`：initMargin=1/initMarginScaleK=100（初始保证金率 1%/leverage）；
//! takerFee=20 fixed；maintenance bracket=(1000, 5)（维持保证金率 0.5%）；
//! maxLeverage bracket=(2000, 5)/(100000, 10)。
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

    const BASE: i32 = 1;
    const USD: i32 = 840;

    const SYMBOL: i32 = 5001;
    const LEVERAGE: i32 = 5;
    const MARK_PRICE: i64 = 1000;
    const OPEN_SIZE: i64 = 5;

    const TRADER: i64 = 1;
    const LP: i64 = 2;

    fn mm_table() -> BTreeMap<i64, i64> {
        BTreeMap::from([(1_000, 5), (100_000, 10)])
    }
    fn leverage_table() -> BTreeMap<i64, i64> {
        BTreeMap::from([(2_000, 5), (100_000, 10)])
    }

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

    fn setup_single(mark: i64) -> ExchangeApi {
        let mut api = ExchangeApi::new();
        api.add_currency(BASE, 1);
        api.add_currency(USD, 1);
        assert_eq!(api.add_futures_symbol(futures_spec(SYMBOL)), CommandResultCode::Success);
        assert_eq!(api.set_mark_price(SYMBOL, mark), CommandResultCode::Success);
        api
    }

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
            assert_eq!(total, 0, "futures global conservation broken: currency={cur} total={total}");
        }
    }

    // 对应 Java openLoss_bidAboveMark_rejectedByNSF() 场景（Doc §1 Open Loss）：
    // mark=1000, BID @ 2000 size=5 时 notional=10000, IM=20, openLoss=5×(2000-1000)=5000，
    // 总需 IM+openLoss+fee=5120；deposit=300 不足 → 应被 RISK_NSF 拦下（"开仓即爆仓"陷阱）。
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

    // 对应 Java openLoss_bidAboveMark_acceptedWithSufficientBalance() 场景：同上但 deposit=6000
    // (> 5120 需求)，应成功放行并开出 LONG 仓位。
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

        let pos = api.user_position(TRADER, SYMBOL).expect("a position must exist after opening");
        assert_eq!(pos.direction, PositionDirection::Long);
        assert_eq!(pos.open_volume, OPEN_SIZE);
        assert_conserved(&api);
    }

    // 对应 Java openLoss_askBelowMark_rejectedByNSF() 场景：openLoss 的对称情形——ASK 报价低于
    // mark（贱卖）同样要预留立即浮亏；mark=1000, ASK 5@500 需 IM(5)+fee(100)+openLoss(2500)=2605，
    // deposit=1000 不足 → RISK_NSF。
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

    // 对应 Java openLoss_onewayReverseOrder_truncatedToOpeningPortion() 场景：ONEWAY 反向大单的
    // openingSize 截断——LONG 5@1000 后挂 ASK 10@500 时，openLoss 只应对超出现有 openVolume 的
    // 部分（10-5=5）预留，而非按全部 size=10 计算，否则会误判 NSF。
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
        assert_eq!(api.user_position(TRADER, SYMBOL).unwrap().open_init_margin_sum, 10);

        assert_eq!(
            place(&mut api, 50003, TRADER, SYMBOL, 500, 10, OrderAction::Ask, OrderType::Gtc, MarginMode::Isolated, LEVERAGE),
            CommandResultCode::Success
        );
        assert_conserved(&api);
    }

    // 对应 Java orderMargin_reduceSideOffset_pureReduceNoExtraMargin() 场景（Doc §7 Order Margin）：
    // LONG 5@1000 后挂纯反向 ASK 5@1000（pure reduce）不应占用额外保证金——required margin 只剩
    // openInitMarginSum + fee，不叠加 pending IM。
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

        let pos = api.user_position(TRADER, SYMBOL).expect("a position must exist after opening");
        assert_eq!(pos.open_init_margin_sum, 10, "openInitMarginSum should be 10 after opening");
        assert_eq!(api.user_account(TRADER, USD), 115, "taker fee of 100 should already be deducted");

        assert_eq!(
            place(&mut api, 20003, TRADER, SYMBOL, MARK_PRICE, OPEN_SIZE, OrderAction::Ask, OrderType::Gtc, MarginMode::Isolated, LEVERAGE),
            CommandResultCode::Success
        );
        assert_conserved(&api);
    }

    // 对应 Java orderMargin_sameSideOpen_reservesPendingIM() 场景（Doc §7）：非 reduce 的同向加仓
    // pending 单应正常锁定 orderMargin——LONG 5@1000 再挂同向 BID 3@1000 时，pending_buy_size 应
    // 反映挂单量，持仓量（open_volume）在成交前保持不变。
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

        let pos = api.user_position(TRADER, SYMBOL).expect("position should still exist after the add-on order");
        assert_eq!(pos.pending_buy_size, 3);
        assert_eq!(pos.pending_sell_size, 0);
        assert_eq!(pos.pending_buy_avg_price, 500);
        assert_eq!(pos.open_volume, OPEN_SIZE, "unfilled order should not change open volume");
        assert_conserved(&api);
    }

    // 构造两个独立永续合约 symbol 且都已初始化 mark price，供跨 symbol 的 cross-subsidy 测试复用。
    fn setup_two(symbol_a: i32, symbol_b: i32, mark: i64) -> ExchangeApi {
        let mut api = ExchangeApi::new();
        api.add_currency(BASE, 1);
        api.add_currency(USD, 1);
        assert_eq!(api.add_futures_symbol(futures_spec(symbol_a)), CommandResultCode::Success);
        assert_eq!(api.add_futures_symbol(futures_spec(symbol_b)), CommandResultCode::Success);
        assert_eq!(api.set_mark_price(symbol_a, mark), CommandResultCode::Success);
        assert_eq!(api.set_mark_price(symbol_b, mark), CommandResultCode::Success);
        api
    }

    // 对应 Java isolatedCrossSubsidy_isolatedPnlBlockedFromCrossCapacity() 场景：ISOLATED
    // 仓位的浮盈不能被其它 symbol 的 CROSS 新单当资本使用——symbol A 用 ISOLATED 开仓并拉高
    // mark price 产生浮盈后，symbol B 的 CROSS 新单仍应因 A 的浮盈不计入 crossFreeMargin 而 NSF。
    #[test]
    fn isolated_cross_subsidy_isolated_pnl_blocked_from_cross_capacity() {
        let symbol_a = 7001;
        let symbol_b = 7002;
        let mut api = setup_two(symbol_a, symbol_b, MARK_PRICE);
        add_user_money(&mut api, TRADER, 110, 1);
        add_user_money(&mut api, LP, 10_000_000, 2);

        assert_eq!(
            place(&mut api, 70001, LP, symbol_a, MARK_PRICE, OPEN_SIZE, OrderAction::Ask, OrderType::Gtc, MarginMode::Cross, LEVERAGE),
            CommandResultCode::Success
        );
        assert_eq!(
            place(&mut api, 70002, TRADER, symbol_a, MARK_PRICE, OPEN_SIZE, OrderAction::Bid, OrderType::Gtc, MarginMode::Isolated, LEVERAGE),
            CommandResultCode::Success
        );

        let pos_a = api.user_position(TRADER, symbol_a).expect("a position on A must exist after opening");
        assert_eq!(pos_a.margin_mode, MarginMode::Isolated, "A should be ISOLATED");
        assert_eq!(pos_a.open_init_margin_sum, 10, "A LONG openInitMarginSum should be 10");
        assert_eq!(api.user_account(TRADER, USD), 10, "accounts should be 10 after the 100 fee is deducted");

        assert_eq!(api.set_mark_price(symbol_a, 2000), CommandResultCode::Success);

        assert_eq!(
            place(&mut api, 70003, LP, symbol_b, MARK_PRICE, OPEN_SIZE, OrderAction::Ask, OrderType::Gtc, MarginMode::Cross, LEVERAGE),
            CommandResultCode::Success
        );
        assert_eq!(
            place(&mut api, 70004, TRADER, symbol_b, MARK_PRICE, OPEN_SIZE, OrderAction::Bid, OrderType::Gtc, MarginMode::Cross, LEVERAGE),
            CommandResultCode::RiskNsf
        );
        assert!(api.user_position(TRADER, symbol_b).is_none(), "B should have no position after NSF rejection");
        assert_conserved(&api);
    }

    // 对应 Java isolatedCrossSubsidy_crossPnlAllowedIntoCrossCapacity() 场景：与上一测试对照——
    // symbol A 改用 CROSS 开仓时，其浮盈应该能被 symbol B 的 CROSS 新单当作资本使用，两笔都应成功。
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

        assert_eq!(api.set_mark_price(symbol_a, 2000), CommandResultCode::Success);

        assert_eq!(
            place(&mut api, 80003, LP, symbol_b, MARK_PRICE, OPEN_SIZE, OrderAction::Ask, OrderType::Gtc, MarginMode::Cross, LEVERAGE),
            CommandResultCode::Success
        );
        assert_eq!(
            place(&mut api, 80004, TRADER, symbol_b, MARK_PRICE, OPEN_SIZE, OrderAction::Bid, OrderType::Gtc, MarginMode::Cross, LEVERAGE),
            CommandResultCode::Success
        );

        assert!(api.user_position(TRADER, symbol_a).is_some(), "A CROSS position should exist");
        assert!(api.user_position(TRADER, symbol_b).is_some(), "B CROSS position should have been opened");
        assert_conserved(&api);
    }
}
