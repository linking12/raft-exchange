//! 对应 Java: `exchange.core2.core.processors.RiskEngine`（现货子集：
//! `placeOrderRiskCheck`/`placeOrder`/`placeExchangeOrder`，行 399–685，现货分支 633–685）。
//! 权威参考：`docs/superpowers/specs/2026-08-31-p3-spot-risk-reference.md` §2。
//!
//! # Ruling P3-B（borrow 设计）
//! `RiskEngine` 不持有 `UserProfileService`/`SymbolSpecificationProvider` 的所有权——方法按需
//! 借用调用方传入的 `&mut`/`&` 引用。本期单 shard、单线程，不做 Java `uidForThisHandler` 分片，
//! 视所有 uid 为本 shard（Task 10 引擎持有 ups/ssp 字段，逐命令借出）。

use crate::account::profile::UserProfile;
use crate::account::registry::{SymbolSpecificationProvider, UserProfileService};
use crate::api::command::OrderCommand;
use crate::api::enums::{
    CommandResultCode, MatcherEventType, OrderAction, OrderCommandType, OrderType, SymbolType,
};
use crate::api::event::MatcherTradeEvent;
use crate::api::spec::{CoreCurrencySpecification, CoreSymbolSpecification};
use crate::utils::arithmetic;

/// 对应 Java `RiskEngine`（现货子集）。本任务尚无需要持久化的字段——费率调整/手续费累计桶
/// 属 Task 8，届时在此结构体上新增字段。
#[derive(Debug, Default)]
pub struct RiskEngine {}

impl RiskEngine {
    pub fn new() -> Self {
        RiskEngine {}
    }

    /// 对应 Java `placeOrderRiskCheck`（399–420）：加载 user profile（缺失 → `AuthInvalidUser`）、
    /// 加载 symbol spec（缺失 → `InvalidSymbol`），现货分支转 [`Self::place_exchange_order`]。
    /// 非现货 symbol（期货/期权）本期未移植，`unimplemented!`（对应 Java `placeOrder` 434 行以下
    /// 的 margin 分支，属 P4 范围）。
    ///
    /// 省略 Java 的 `cfgIgnoreRiskProcessing` 开关——P3 未移植该配置项，恒走风控路径。
    pub fn place_order_risk_check(
        &mut self,
        cmd: &mut OrderCommand,
        ups: &mut UserProfileService,
        ssp: &SymbolSpecificationProvider,
    ) -> CommandResultCode {
        let user_profile = match ups.get_mut(cmd.uid) {
            Some(u) => u,
            None => return CommandResultCode::AuthInvalidUser,
        };
        let spec = match ssp.get_symbol(cmd.symbol) {
            Some(s) => s,
            None => return CommandResultCode::InvalidSymbol,
        };
        if spec.symbol_type != SymbolType::CurrencyExchangePair {
            unimplemented!("P4: futures/margin");
        }
        let currency = if matches!(cmd.action, Some(OrderAction::Bid)) {
            spec.quote_currency
        } else {
            spec.base_currency
        };
        let currency_spec = ssp
            .get_currency(currency)
            .unwrap_or_else(|| panic!("currency spec missing for currency {currency}"));
        self.place_exchange_order(cmd, user_profile, spec, currency_spec)
    }

    /// 对应 Java `placeExchangeOrder`（633–685）：现货下单冻结。BID 锁 quote，ASK 锁 base；
    /// 成功只累加 `exchange_locked`，`accounts` 不动。逐行对照参考文档 §2：
    /// - BID：reserve 价校验（BUDGET 要求 `reserve==price`，普通限价要求 `reserve>=price`，
    ///   否则 `RiskInvalidReserveBidPrice`）→ BUDGET 用
    ///   `calculate_amount_bid_taker_fee_for_budget(size, cmd.price, ...)`（`cmd.price` 是总预算），
    ///   普通限价用 `calculate_amount_bid_taker_fee(size, cmd.reserve_bid_price, ...)`（保守价，
    ///   非 `cmd.price`）→ `size_price_to_currency_scale` 缩放到 quote currency scale。
    /// - ASK：`is_ask_price_too_low` 守卫（→ `RiskAskPriceLowerThanFee`）→
    ///   `calculate_amount_ask(size) = size` → `symbol_to_currency_scale` 缩放到 base currency scale
    ///   （ASK 侧不预留 fee，从卖出 quote 收益里扣，属 R2）。
    /// - NSF：`accounts[currency] - exchange_locked[currency] - order_lock_amount < 0` →
    ///   `RiskNsf`（现货子集：无期货净保证金、无借贷抵押扣减，二者属 P4/Task 8+ 范围）。
    /// - 成功：`user_profile.add_to_locked(currency, +order_lock_amount)`，返回
    ///   `ValidForMatchingEngine`。
    fn place_exchange_order(
        &mut self,
        cmd: &OrderCommand,
        user_profile: &mut UserProfile,
        spec: &CoreSymbolSpecification,
        currency_spec: &CoreCurrencySpecification,
    ) -> CommandResultCode {
        let is_bid = matches!(cmd.action, Some(OrderAction::Bid));
        let currency = if is_bid { spec.quote_currency } else { spec.base_currency };
        let size = cmd.size;

        let order_lock_amount = if is_bid {
            let is_budget =
                matches!(cmd.order_type, Some(OrderType::FokBudget) | Some(OrderType::IocBudget));
            let raw = if is_budget {
                if cmd.reserve_bid_price != cmd.price {
                    return CommandResultCode::RiskInvalidReserveBidPrice;
                }
                arithmetic::calculate_amount_bid_taker_fee_for_budget(
                    size,
                    cmd.price,
                    spec.taker_fee,
                    spec.fee_scale_k,
                )
            } else {
                if cmd.reserve_bid_price < cmd.price {
                    return CommandResultCode::RiskInvalidReserveBidPrice;
                }
                arithmetic::calculate_amount_bid_taker_fee(
                    size,
                    cmd.reserve_bid_price,
                    spec.taker_fee,
                    spec.fee_scale_k,
                )
            };
            arithmetic::size_price_to_currency_scale(
                raw,
                spec.base_scale_k,
                spec.quote_scale_k,
                currency_spec.currency_scale_k,
            )
        } else {
            if arithmetic::is_ask_price_too_low(cmd.price, spec.taker_fee, spec.fee_scale_k) {
                return CommandResultCode::RiskAskPriceLowerThanFee;
            }
            let raw = arithmetic::calculate_amount_ask(size);
            arithmetic::symbol_to_currency_scale(raw, spec.base_scale_k, currency_spec.currency_scale_k)
        };

        let balance = user_profile.account(currency);
        let existing_locked = user_profile.locked(currency);
        // 现货子集：无 freeFuturesMargin（期货净保证金抵扣）、无 loanLocked（借贷抵押扣减）——
        // 二者分别属 P4（margin）与 Task 8+（loan）范围，参考文档 §2 已明确"spot: no futures
        // margin, no loan term"。
        if balance - existing_locked - order_lock_amount < 0 {
            return CommandResultCode::RiskNsf;
        }
        user_profile.add_to_locked(currency, order_lock_amount);
        CommandResultCode::ValidForMatchingEngine
    }

    /// 对应 Java `handlerRiskRelease`（885–1023，现货分支 922–945）：R2 撮合后置分派骨架。
    /// 参考文档 §3。本任务只落地链头 REJECT/REDUCE 释放；链上剩余的 TRADE 事件（buy/sell 结算）
    /// 留给 Task 6/7（`// TODO` 占位，不在本任务实现）。
    ///
    /// 借用设计（vs. Java `takerUp = getUserProfileOrAddSuspended(cmd.uid)` 提前一次性取）：本期
    /// 单 shard 恒 `uidForThisHandler == true`，但如果在这里先 `ups.get_or_add_suspended(cmd.uid)`
    /// 拿到 `&mut UserProfile` 再传给 [`Self::handle_matcher_reject_reduce_event_exchange`]，
    /// 后续 Task 6/7 的 buy/sell 分支需要额外借出 maker profile 时会与这个长生命周期借用冲突。
    /// 故改为把 `&mut UserProfileService` 传进 helper，helper 内部按 uid 现取——本任务只涉及
    /// taker 自己（挂单主），单次 `get_or_add_suspended` 即可，不与其他借用重叠。
    pub fn handler_risk_release(
        &mut self,
        cmd: &mut OrderCommand,
        ups: &mut UserProfileService,
        ssp: &SymbolSpecificationProvider,
    ) {
        let mut mte = match cmd.matcher_event.take() {
            Some(m) => m,
            None => return,
        };
        if mte.event_type == MatcherEventType::BinaryEvent {
            cmd.matcher_event = Some(mte);
            return;
        }
        let spec = ssp
            .get_symbol(cmd.symbol)
            .unwrap_or_else(|| panic!("symbol spec missing for symbol {}", cmd.symbol))
            .clone();
        if spec.symbol_type != SymbolType::CurrencyExchangePair {
            unimplemented!("P4: futures R2");
        }
        let taker_sell = matches!(cmd.action, Some(OrderAction::Ask));

        // REJECT 总在链头；REDUCE 单独成事件，同样只可能出现在链头。
        let next: Option<Box<MatcherTradeEvent>> =
            if mte.event_type == MatcherEventType::Reduce
                || mte.event_type == MatcherEventType::Reject
            {
                let currency = if taker_sell { spec.base_currency } else { spec.quote_currency };
                let currency_spec = ssp
                    .get_currency(currency)
                    .unwrap_or_else(|| panic!("currency spec missing for currency {currency}"))
                    .clone();
                let taker_up = ups.get_or_add_suspended(cmd.uid);
                Self::handle_matcher_reject_reduce_event_exchange(
                    cmd,
                    &mte,
                    &spec,
                    &currency_spec,
                    taker_sell,
                    taker_up,
                );
                mte.next.take()
            } else {
                Some(mte)
            };

        if let Some(remaining) = next {
            // TODO(Task 6/7): taker_sell → handle_matcher_events_exchange_sell(cmd, remaining, &spec, ups)
            //                 !taker_sell → handle_matcher_events_exchange_buy(cmd, remaining, &spec, ups)
            cmd.matcher_event = Some(remaining);
        }
    }

    /// 对应 Java `handleMatcherRejectReduceEventExchange`（1094–1125）：撤单/拒单释放冻结，
    /// 只涉及单方（active 单的 owner），accounts 不动。参考文档 §3a：
    /// - `currency = taker_sell ? base : quote`。
    /// - ASK：`release = symbol_to_currency_scale(calculate_amount_ask(mte.size), ...)` = mte.size
    ///   缩放（残量按 base 数量直退，下单时也是按 size 直冻，无 fee 预留）。
    /// - BID 按订单类型：
    ///   - `PLACE_ORDER` + `FOK_BUDGET`：`calculate_amount_bid_taker_fee_for_budget(mte.size, mte.price, ...)`
    ///     （FOK 只可能整单成交或整单拒绝，`mte.price` 就是原始预算）。
    ///   - `IOC_BUDGET` 且 `mte.next.is_none()`（全拒，无前置成交）：
    ///     `calculate_amount_bid_taker_fee_for_budget(cmd.size, cmd.price, ...)`（释放整份预算）。
    ///   - `IOC_BUDGET` 有前置 TRADE（部分成交后剩余的 REDUCE）：`release_sp = 0`——BUY 结算
    ///     （Task 7）已经把整份预算的锁定释放过一次，这里再释放就是双重释放，破坏守恒。
    ///   - 普通限价：`calculate_amount_bid_taker_fee(mte.size, mte.bidder_hold_price, ...)`
    ///     （用下单时实际冻结所用的保守价 `bidder_hold_price`，而非 `cmd.price`）。
    ///   - 缩放：`size_price_to_currency_scale(release_sp, ...)`。
    /// - 应用：`user_profile.add_to_locked(currency, -release)`；accounts 不动。
    ///   （Java 侧 `release>0` 才发 `sendUnLockEvent`——P3 未移植事件总线，此处不发事件，
    ///   纯记账副作用。）
    fn handle_matcher_reject_reduce_event_exchange(
        cmd: &OrderCommand,
        mte: &MatcherTradeEvent,
        spec: &CoreSymbolSpecification,
        currency_spec: &CoreCurrencySpecification,
        taker_sell: bool,
        taker_up: &mut UserProfile,
    ) {
        let currency = if taker_sell { spec.base_currency } else { spec.quote_currency };

        let release = if taker_sell {
            let raw = arithmetic::calculate_amount_ask(mte.size);
            arithmetic::symbol_to_currency_scale(raw, spec.base_scale_k, currency_spec.currency_scale_k)
        } else {
            let is_fok_budget = cmd.command == OrderCommandType::PlaceOrder
                && matches!(cmd.order_type, Some(OrderType::FokBudget));
            let is_ioc_budget = matches!(cmd.order_type, Some(OrderType::IocBudget));
            let release_sp = if is_fok_budget {
                arithmetic::calculate_amount_bid_taker_fee_for_budget(
                    mte.size,
                    mte.price,
                    spec.taker_fee,
                    spec.fee_scale_k,
                )
            } else if is_ioc_budget && mte.next.is_none() {
                arithmetic::calculate_amount_bid_taker_fee_for_budget(
                    cmd.size,
                    cmd.price,
                    spec.taker_fee,
                    spec.fee_scale_k,
                )
            } else if is_ioc_budget {
                0
            } else {
                arithmetic::calculate_amount_bid_taker_fee(
                    mte.size,
                    mte.bidder_hold_price,
                    spec.taker_fee,
                    spec.fee_scale_k,
                )
            };
            arithmetic::size_price_to_currency_scale(
                release_sp,
                spec.base_scale_k,
                spec.quote_scale_k,
                currency_spec.currency_scale_k,
            )
        };

        taker_up.add_to_locked(currency, -release);
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::api::command::OrderCommand;
    use crate::api::enums::{OrderCommandType, SymbolType};

    const BASE: i32 = 1;
    const QUOTE: i32 = 2;
    const SYMBOL: i32 = 100;
    const UID: i64 = 7;

    fn spec_with_fee(taker_fee: i64, fee_scale_k: i64) -> CoreSymbolSpecification {
        CoreSymbolSpecification {
            symbol_id: SYMBOL,
            symbol_type: SymbolType::CurrencyExchangePair,
            base_currency: BASE,
            quote_currency: QUOTE,
            base_scale_k: 100,
            quote_scale_k: 1_000_000,
            taker_fee,
            maker_fee: 0,
            fee_scale_k,
        }
    }

    /// 搭建：一个现货 symbol（BASE/QUOTE，base_scale_k=100/quote_scale_k=1_000_000，
    /// 均非平凡缩放）+ 两种 currency spec（base currency_scale_k=100 对齐 base_scale_k，
    /// ASK 侧缩放天然恒等；quote currency_scale_k=1_000_000 < 乘积 scale 1e8，BID 侧缩放非恒等）
    /// + 一个已建档、按需充值的用户。
    fn setup(
        taker_fee: i64,
        fee_scale_k: i64,
        quote_balance: i64,
        base_balance: i64,
    ) -> (UserProfileService, SymbolSpecificationProvider) {
        let mut ssp = SymbolSpecificationProvider::new();
        assert_eq!(
            ssp.add_symbol(spec_with_fee(taker_fee, fee_scale_k)),
            CommandResultCode::Success
        );
        ssp.add_currency(CoreCurrencySpecification { currency: BASE, currency_scale_k: 100 });
        ssp.add_currency(CoreCurrencySpecification {
            currency: QUOTE,
            currency_scale_k: 1_000_000,
        });

        let mut ups = UserProfileService::new();
        assert_eq!(ups.add_empty_user_profile(UID), CommandResultCode::Success);
        let p = ups.get_mut(UID).unwrap();
        p.add_to_account(QUOTE, quote_balance);
        p.add_to_account(BASE, base_balance);
        (ups, ssp)
    }

    fn bid_cmd(size: i64, price: i64, reserve_bid_price: i64, order_type: OrderType) -> OrderCommand {
        OrderCommand {
            command: OrderCommandType::PlaceOrder,
            order_id: 1,
            symbol: SYMBOL,
            price,
            size,
            reserve_bid_price,
            action: Some(OrderAction::Bid),
            order_type: Some(order_type),
            uid: UID,
            ..Default::default()
        }
    }

    fn ask_cmd(size: i64, price: i64) -> OrderCommand {
        OrderCommand {
            command: OrderCommandType::PlaceOrder,
            order_id: 1,
            symbol: SYMBOL,
            price,
            size,
            reserve_bid_price: 0,
            action: Some(OrderAction::Ask),
            order_type: Some(OrderType::Gtc),
            uid: UID,
            ..Default::default()
        }
    }

    // ------------------------------------------------------------------
    // BID — 固定费
    // ------------------------------------------------------------------

    #[test]
    fn bid_limit_order_sufficient_balance_locks_notional_plus_fixed_fee() {
        let (mut ups, ssp) = setup(2, 0, 1_000_000, 0);
        let mut engine = RiskEngine::new();
        let mut cmd = bid_cmd(1000, 50, 50, OrderType::Gtc);

        let result = engine.place_order_risk_check(&mut cmd, &mut ups, &ssp);

        let raw = arithmetic::calculate_amount_bid_taker_fee(1000, 50, 2, 0);
        let expected = arithmetic::size_price_to_currency_scale(raw, 100, 1_000_000, 1_000_000);
        assert_eq!(result, CommandResultCode::ValidForMatchingEngine);
        let p = ups.get(UID).unwrap();
        assert_eq!(p.locked(QUOTE), expected);
        assert_eq!(p.account(QUOTE), 1_000_000, "accounts 不动");
    }

    #[test]
    fn bid_limit_order_insufficient_balance_returns_nsf_and_locks_nothing() {
        let (mut ups, ssp) = setup(2, 0, 100, 0);
        let mut engine = RiskEngine::new();
        let mut cmd = bid_cmd(1000, 50, 50, OrderType::Gtc);

        let result = engine.place_order_risk_check(&mut cmd, &mut ups, &ssp);

        assert_eq!(result, CommandResultCode::RiskNsf);
        let p = ups.get(UID).unwrap();
        assert_eq!(p.locked(QUOTE), 0);
        assert_eq!(p.account(QUOTE), 100);
    }

    #[test]
    fn bid_limit_order_reserve_less_than_price_returns_invalid_reserve_price() {
        let (mut ups, ssp) = setup(2, 0, 1_000_000, 0);
        let mut engine = RiskEngine::new();
        let mut cmd = bid_cmd(1000, 50, 49, OrderType::Gtc); // reserve(49) < price(50)

        let result = engine.place_order_risk_check(&mut cmd, &mut ups, &ssp);

        assert_eq!(result, CommandResultCode::RiskInvalidReserveBidPrice);
        assert_eq!(ups.get(UID).unwrap().locked(QUOTE), 0);
    }

    // ------------------------------------------------------------------
    // BID — 比例费
    // ------------------------------------------------------------------

    #[test]
    fn bid_limit_order_proportional_fee_locks_notional_plus_ceil_fee() {
        let (mut ups, ssp) = setup(500, 1_000_000, 1_000_000_000, 0);
        let mut engine = RiskEngine::new();
        let mut cmd = bid_cmd(1000, 50, 60, OrderType::Gtc); // reserve(60) > price(50)：保守价冻结

        let result = engine.place_order_risk_check(&mut cmd, &mut ups, &ssp);

        // 用保守价 reserve_bid_price=60（非 cmd.price=50）计算，逐字对照 §2。
        let raw = arithmetic::calculate_amount_bid_taker_fee(1000, 60, 500, 1_000_000);
        let expected = arithmetic::size_price_to_currency_scale(raw, 100, 1_000_000, 1_000_000);
        assert_eq!(result, CommandResultCode::ValidForMatchingEngine);
        assert_eq!(ups.get(UID).unwrap().locked(QUOTE), expected);
    }

    #[test]
    fn bid_budget_order_reserve_equals_price_locks_budget_plus_fee() {
        let (mut ups, ssp) = setup(500, 1_000_000, 1_000_000_000, 0);
        let mut engine = RiskEngine::new();
        // BUDGET 单：cmd.price 是总预算而非单价，reserve 必须严格等于 price。
        let mut cmd = bid_cmd(1000, 60_000, 60_000, OrderType::FokBudget);

        let result = engine.place_order_risk_check(&mut cmd, &mut ups, &ssp);

        let raw = arithmetic::calculate_amount_bid_taker_fee_for_budget(1000, 60_000, 500, 1_000_000);
        let expected = arithmetic::size_price_to_currency_scale(raw, 100, 1_000_000, 1_000_000);
        assert_eq!(result, CommandResultCode::ValidForMatchingEngine);
        assert_eq!(ups.get(UID).unwrap().locked(QUOTE), expected);
    }

    #[test]
    fn bid_budget_order_reserve_mismatch_returns_invalid_reserve_price() {
        let (mut ups, ssp) = setup(500, 1_000_000, 1_000_000_000, 0);
        let mut engine = RiskEngine::new();
        let mut cmd = bid_cmd(1000, 60_000, 60_001, OrderType::IocBudget); // reserve != price

        let result = engine.place_order_risk_check(&mut cmd, &mut ups, &ssp);

        assert_eq!(result, CommandResultCode::RiskInvalidReserveBidPrice);
        assert_eq!(ups.get(UID).unwrap().locked(QUOTE), 0);
    }

    // ------------------------------------------------------------------
    // ASK — 固定费 / 比例费
    // ------------------------------------------------------------------

    #[test]
    fn ask_order_locks_base_size_scaled_fixed_fee() {
        let (mut ups, ssp) = setup(2, 0, 0, 1_000_000);
        let mut engine = RiskEngine::new();
        let mut cmd = ask_cmd(1000, 50); // price(50) >= taker_fee(2)：不触发 too-low

        let result = engine.place_order_risk_check(&mut cmd, &mut ups, &ssp);

        let raw = arithmetic::calculate_amount_ask(1000);
        let expected = arithmetic::symbol_to_currency_scale(raw, 100, 100);
        assert_eq!(result, CommandResultCode::ValidForMatchingEngine);
        let p = ups.get(UID).unwrap();
        assert_eq!(p.locked(BASE), expected);
        assert_eq!(p.account(BASE), 1_000_000, "accounts 不动");
    }

    #[test]
    fn ask_order_locks_base_size_scaled_proportional_fee() {
        let (mut ups, ssp) = setup(500, 1_000_000, 0, 1_000_000);
        let mut engine = RiskEngine::new();
        let mut cmd = ask_cmd(1000, 50); // ceil_divide(1_000_000, 500)=2000，price(50) < 2000 会 too-low！

        // 用足够高的价格避免 too-low：ceil(fee_scale_k/taker_fee)=2000。
        cmd.price = 2000;
        let result = engine.place_order_risk_check(&mut cmd, &mut ups, &ssp);

        let raw = arithmetic::calculate_amount_ask(1000);
        let expected = arithmetic::symbol_to_currency_scale(raw, 100, 100);
        assert_eq!(result, CommandResultCode::ValidForMatchingEngine);
        assert_eq!(ups.get(UID).unwrap().locked(BASE), expected);
    }

    #[test]
    fn ask_price_too_low_fixed_fee_returns_error() {
        let (mut ups, ssp) = setup(5, 0, 0, 1_000_000);
        let mut engine = RiskEngine::new();
        let mut cmd = ask_cmd(1000, 1); // price(1) < taker_fee(5)

        let result = engine.place_order_risk_check(&mut cmd, &mut ups, &ssp);

        assert_eq!(result, CommandResultCode::RiskAskPriceLowerThanFee);
        assert_eq!(ups.get(UID).unwrap().locked(BASE), 0);
    }

    #[test]
    fn ask_price_too_low_proportional_fee_returns_error() {
        let (mut ups, ssp) = setup(500, 1_000_000, 0, 1_000_000);
        let mut engine = RiskEngine::new();
        // ceil_divide(1_000_000, 500) = 2000；price(1999) < 2000 触发 too-low。
        let mut cmd = ask_cmd(1000, 1999);

        let result = engine.place_order_risk_check(&mut cmd, &mut ups, &ssp);

        assert_eq!(result, CommandResultCode::RiskAskPriceLowerThanFee);
        assert_eq!(ups.get(UID).unwrap().locked(BASE), 0);
    }

    #[test]
    fn ask_order_insufficient_base_balance_returns_nsf() {
        let (mut ups, ssp) = setup(2, 0, 0, 10);
        let mut engine = RiskEngine::new();
        let mut cmd = ask_cmd(1000, 50);

        let result = engine.place_order_risk_check(&mut cmd, &mut ups, &ssp);

        assert_eq!(result, CommandResultCode::RiskNsf);
        let p = ups.get(UID).unwrap();
        assert_eq!(p.locked(BASE), 0);
        assert_eq!(p.account(BASE), 10);
    }

    // ------------------------------------------------------------------
    // 用户 / symbol 缺失
    // ------------------------------------------------------------------

    #[test]
    fn auth_invalid_user_when_profile_missing() {
        let (mut ups, ssp) = setup(2, 0, 1_000_000, 0);
        let mut engine = RiskEngine::new();
        let mut cmd = bid_cmd(1000, 50, 50, OrderType::Gtc);
        cmd.uid = 999; // 未建档用户

        let result = engine.place_order_risk_check(&mut cmd, &mut ups, &ssp);

        assert_eq!(result, CommandResultCode::AuthInvalidUser);
    }

    #[test]
    fn invalid_symbol_when_spec_missing() {
        let (mut ups, ssp) = setup(2, 0, 1_000_000, 0);
        let mut engine = RiskEngine::new();
        let mut cmd = bid_cmd(1000, 50, 50, OrderType::Gtc);
        cmd.symbol = 999; // 未注册 symbol

        let result = engine.place_order_risk_check(&mut cmd, &mut ups, &ssp);

        assert_eq!(result, CommandResultCode::InvalidSymbol);
    }

    // ------------------------------------------------------------------
    // R2 — handler_risk_release：reject/reduce 释放冻结
    // ------------------------------------------------------------------

    fn reject_or_reduce_event(
        event_type: MatcherEventType,
        size: i64,
        price: i64,
        bidder_hold_price: i64,
        next: Option<Box<MatcherTradeEvent>>,
    ) -> Box<MatcherTradeEvent> {
        Box::new(MatcherTradeEvent {
            event_type,
            active_order_completed: false,
            maker_order_id: 0,
            maker_order_completed: false,
            price,
            size,
            bid_gt_ask: false,
            bidder_hold_price,
            matched_order_uid: 0,
            next,
        })
    }

    #[test]
    fn bid_plain_limit_pure_reject_releases_full_lock_and_leaves_accounts_untouched() {
        let (mut ups, ssp) = setup(2, 0, 1_000_000, 0);
        let mut engine = RiskEngine::new();
        // 先下单，Task 4 冻结 quote（保守价 reserve_bid_price=50=price）。
        let mut cmd = bid_cmd(1000, 50, 50, OrderType::Gtc);
        let result = engine.place_order_risk_check(&mut cmd, &mut ups, &ssp);
        assert_eq!(result, CommandResultCode::ValidForMatchingEngine);
        let locked_after_place = ups.get(UID).unwrap().locked(QUOTE);
        assert!(locked_after_place > 0, "前置条件：下单必须产生非零冻结");

        // 撮合产生纯 REJECT：整单未成交，size = 订单原始 size，bidder_hold_price = 下单时的保守价。
        cmd.matcher_event =
            Some(reject_or_reduce_event(MatcherEventType::Reject, 1000, 50, 50, None));

        engine.handler_risk_release(&mut cmd, &mut ups, &ssp);

        let p = ups.get(UID).unwrap();
        assert_eq!(p.locked(QUOTE), 0, "纯 REJECT 应把冻结全额释放回 0");
        assert_eq!(p.account(QUOTE), 1_000_000, "accounts 不动");
        assert_eq!(p.account(BASE), 0, "accounts 不动");
        assert!(cmd.matcher_event.is_none(), "REJECT 是唯一事件，链头消费后应清空");
    }

    #[test]
    fn ask_order_reduce_remainder_releases_partial_lock_and_leaves_accounts_untouched() {
        let (mut ups, ssp) = setup(2, 0, 0, 1_000_000);
        let mut engine = RiskEngine::new();
        // 下单：ASK size=1000，锁 base（base_scale_k=100=currency_scale_k(BASE)，缩放恒等）。
        let mut cmd = ask_cmd(1000, 50);
        let result = engine.place_order_risk_check(&mut cmd, &mut ups, &ssp);
        assert_eq!(result, CommandResultCode::ValidForMatchingEngine);
        let locked_after_place = ups.get(UID).unwrap().locked(BASE);
        assert_eq!(locked_after_place, 1000);

        // 部分成交后剩余 300 未成交，REDUCE 释放对应 base 冻结。
        cmd.matcher_event =
            Some(reject_or_reduce_event(MatcherEventType::Reduce, 300, 50, 0, None));

        engine.handler_risk_release(&mut cmd, &mut ups, &ssp);

        let p = ups.get(UID).unwrap();
        assert_eq!(p.locked(BASE), locked_after_place - 300, "REDUCE 只释放剩余量对应的锁定");
        assert_eq!(p.account(BASE), 1_000_000, "accounts 不动");
        assert_eq!(p.account(QUOTE), 0, "accounts 不动");
        assert!(cmd.matcher_event.is_none(), "REDUCE 是唯一事件，链头消费后应清空");
    }

    #[test]
    fn bid_ioc_budget_full_reject_no_prior_trade_releases_full_budget() {
        let (mut ups, ssp) = setup(500, 1_000_000, 1_000_000_000, 0);
        let mut engine = RiskEngine::new();
        let mut cmd = bid_cmd(1000, 60_000, 60_000, OrderType::IocBudget);
        let result = engine.place_order_risk_check(&mut cmd, &mut ups, &ssp);
        assert_eq!(result, CommandResultCode::ValidForMatchingEngine);
        let locked_after_place = ups.get(UID).unwrap().locked(QUOTE);
        assert!(locked_after_place > 0);

        // 全拒：REJECT 是链上唯一事件（mte.next == None）→ 释放整份预算（用 cmd.size/cmd.price）。
        cmd.matcher_event =
            Some(reject_or_reduce_event(MatcherEventType::Reject, 1000, 60_000, 0, None));

        engine.handler_risk_release(&mut cmd, &mut ups, &ssp);

        assert_eq!(ups.get(UID).unwrap().locked(QUOTE), 0, "全拒应释放整份预算冻结");
    }

    #[test]
    fn bid_ioc_budget_partial_fill_then_reduce_releases_zero_to_avoid_double_release() {
        let (mut ups, ssp) = setup(500, 1_000_000, 1_000_000_000, 0);
        let mut engine = RiskEngine::new();
        let mut cmd = bid_cmd(1000, 60_000, 60_000, OrderType::IocBudget);
        let result = engine.place_order_risk_check(&mut cmd, &mut ups, &ssp);
        assert_eq!(result, CommandResultCode::ValidForMatchingEngine);
        let locked_after_place = ups.get(UID).unwrap().locked(QUOTE);
        assert!(locked_after_place > 0);

        // 部分成交：REJECT/REDUCE 链头后面挂着一个 TRADE（哨兵占位，字段内容对本测试无关，
        // 只用来让 mte.next.is_some()，触发"有前置成交"分支）。
        let trailing_trade =
            reject_or_reduce_event(MatcherEventType::Trade, 400, 55, 60_000, None);
        cmd.matcher_event = Some(reject_or_reduce_event(
            MatcherEventType::Reduce,
            600,
            60_000,
            0,
            Some(trailing_trade),
        ));

        engine.handler_risk_release(&mut cmd, &mut ups, &ssp);

        // release_sp = 0：BUY 结算（Task 7）已释放整份预算，这里不能重复释放；locked 不变。
        assert_eq!(
            ups.get(UID).unwrap().locked(QUOTE),
            locked_after_place,
            "部分成交残量的 REDUCE 在 IOC_BUDGET 下必须释放 0，避免双重释放"
        );
        // 链头 REDUCE 被消费，剩余 TRADE 链交给 Task 6/7（占位保留在 cmd.matcher_event 上）。
        assert!(cmd.matcher_event.is_some(), "剩余 TRADE 链应保留给 Task 6/7");
    }
}
