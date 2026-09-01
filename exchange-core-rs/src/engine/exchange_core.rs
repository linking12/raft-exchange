//! 对应 Java: `exchange.core2.core.ExchangeCore`（Disruptor 五段编排入口）。
//! 设计文档 §4：本期塌缩为**单线程确定性顺序管线**——`process_command` 就是
//! Java `for cmd in group { risk.preProcess; matching.process; risk.riskRelease }`
//! 的单命令等价物（单 shard，无 grouping 批边界、无 Journal 落盘）。
//!
//! # Ruling P3-B（borrow 结构）
//! `ExchangeCore` 把 `risk`/`matching`/`ups`/`ssp` 都作为**平级字段**直接持有（非
//! `Rc<RefCell<_>>`）。`process_command` 里一律写 `self.risk.xxx(cmd, &mut self.ups, &self.ssp)`
//! 这种"字段直接访问 + 方法调用"的形式——Rust 借用检查器把 `self.risk`/`self.ups`/`self.ssp`
//! 识别为互不重叠的字段借用，允许同时可变/不可变借用，前提是不经过一个先拿 `&mut self`
//! 再在内部重新借用各字段的中间方法（那样借用检查器只看到一次对整个 `self` 的借用，
//! 无法证明字段级别不重叠）。因此本文件的 `process_command` 刻意不拆分成
//! `fn r1(&mut self, ..)` / `fn r2(&mut self, ..)` 这类私有辅助方法。
use crate::account::registry::{SymbolSpecificationProvider, UserProfileService};
use crate::api::command::OrderCommand;
use crate::api::enums::OrderCommandType;
use crate::processors::matching_router::MatchingEngineRouter;
use crate::processors::risk::RiskEngine;

/// 对应 Java `ExchangeCore`（现货子集，单 shard）。
#[derive(Default)]
pub struct ExchangeCore {
    pub risk: RiskEngine,
    pub matching: MatchingEngineRouter,
    pub ups: UserProfileService,
    pub ssp: SymbolSpecificationProvider,
}

impl ExchangeCore {
    pub fn new() -> Self {
        ExchangeCore {
            risk: RiskEngine::new(),
            matching: MatchingEngineRouter::new(),
            ups: UserProfileService::new(),
            ssp: SymbolSpecificationProvider::new(),
        }
    }

    /// 确定性顺序管线（设计文档 §4）。对应 Java 主循环单命令处理：
    /// - 非交易命令（`is_non_trading()`）→ 整块委托 `RiskEngine` 非交易处理，**不进 ME**
    ///   （Task 9 review 修复点：非交易命令曾误路由进 matching router）。
    /// - 交易命令：R1（仅 `PlaceOrder` 调用 `place_order_risk_check`，其余
    ///   Cancel/Move/Reduce/OrderBookRequest 是 R1 no-op，直落 ME）→ ME
    ///   （`matching.process_order`，`PlaceOrder` 只有 R1 放行才真正撮合）→ R2
    ///   （`handler_risk_release`，读 `cmd.matcher_event` 结算/释放冻结）。
    pub fn process_command(&mut self, cmd: &mut OrderCommand) {
        if cmd.command.is_non_trading() {
            self.dispatch_non_trading(cmd);
            return;
        }

        // R1：仅 PlaceOrder 走风控冻结；Cancel/Move/Reduce/OrderBookRequest 无 R1 动作。
        if cmd.command == OrderCommandType::PlaceOrder {
            let rc = self.risk.place_order_risk_check(cmd, &mut self.ups, &self.ssp);
            cmd.result_code = Some(rc);
        }

        // ME：按 symbol 路由到 order book；PlaceOrder 只有 result_code==ValidForMatchingEngine
        // 才真正撮合（MatchingEngineRouter 内部门守，Task 9）。
        self.matching.process_order(cmd);

        // R2：结算成交 / 释放冻结，读 cmd.matcher_event 并消费之。
        self.risk.handler_risk_release(cmd, &mut self.ups, &self.ssp);
    }

    /// 对应 Java `RiskEngineCommandDispatcher.dispatch` 非交易分支（现货子集：
    /// `ADD_USER` / `BALANCE_ADJUSTMENT`）。`BINARY_DATA_COMMAND` 本移植未落地任何处理器
    /// （P3 现货子集未含二进制批量指令），归为 `MatchingUnsupportedCommand`，不 panic。
    fn dispatch_non_trading(&mut self, cmd: &mut OrderCommand) {
        use crate::api::enums::CommandResultCode;

        let rc = match cmd.command {
            OrderCommandType::AddUser => self.risk.add_user(cmd, &mut self.ups),
            OrderCommandType::BalanceAdjustment => self.risk.balance_adjustment(cmd, &mut self.ups),
            _ => CommandResultCode::MatchingUnsupportedCommand,
        };
        cmd.result_code = Some(rc);
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::api::command::OrderCommand;
    use crate::api::enums::{CommandResultCode, OrderAction, OrderType, SymbolType};
    use crate::api::spec::{CoreCurrencySpecification, CoreSymbolSpecification};

    const BASE: i32 = 1;
    const QUOTE: i32 = 2;
    const SYMBOL: i32 = 100;

    fn spot_spec() -> CoreSymbolSpecification {
        CoreSymbolSpecification {
            symbol_id: SYMBOL,
            symbol_type: SymbolType::CurrencyExchangePair,
            base_currency: BASE,
            quote_currency: QUOTE,
            base_scale_k: 1,
            quote_scale_k: 1,
            taker_fee: 0,
            maker_fee: 0,
            fee_scale_k: 0,
        }
    }

    fn seeded_core() -> ExchangeCore {
        let mut core = ExchangeCore::new();
        core.ssp.add_currency(CoreCurrencySpecification { currency: BASE, currency_scale_k: 1 });
        core.ssp.add_currency(CoreCurrencySpecification { currency: QUOTE, currency_scale_k: 1 });
        assert_eq!(core.ssp.add_symbol(spot_spec()), CommandResultCode::Success);
        core.matching.add_symbol(&spot_spec());
        core
    }

    #[test]
    fn non_trading_add_user_does_not_touch_matching_router() {
        let mut core = seeded_core();
        let mut cmd =
            OrderCommand { command: OrderCommandType::AddUser, uid: 1, ..Default::default() };

        core.process_command(&mut cmd);

        assert_eq!(cmd.result_code, Some(CommandResultCode::Success));
        assert!(core.ups.get(1).is_some());
        // 非交易命令不进 ME：cmd.market_data/matcher_event 均未被触碰。
        assert!(cmd.matcher_event.is_none());
        assert!(cmd.market_data.is_none());
    }

    #[test]
    fn non_trading_balance_adjustment_credits_account_and_hedges_adjustments() {
        let mut core = seeded_core();
        core.ups.add_empty_user_profile(1);
        let mut cmd = OrderCommand {
            command: OrderCommandType::BalanceAdjustment,
            uid: 1,
            symbol: QUOTE,
            price: 500,
            order_id: 42,
            ..Default::default()
        };

        core.process_command(&mut cmd);

        assert_eq!(cmd.result_code, Some(CommandResultCode::Success));
        assert_eq!(core.ups.get(1).unwrap().account(QUOTE), 500);
        assert_eq!(*core.risk.adjustments.get(&QUOTE).unwrap(), -500);
    }

    #[test]
    fn trading_place_order_risk_rejected_never_reaches_book() {
        let mut core = seeded_core();
        core.ups.add_empty_user_profile(1);
        // 无余额充值：BID 下单必 NSF。
        let mut cmd = OrderCommand {
            command: OrderCommandType::PlaceOrder,
            order_id: 1,
            symbol: SYMBOL,
            price: 50,
            size: 1000,
            reserve_bid_price: 50,
            action: Some(OrderAction::Bid),
            order_type: Some(OrderType::Gtc),
            uid: 1,
            ..Default::default()
        };

        core.process_command(&mut cmd);

        assert_eq!(cmd.result_code, Some(CommandResultCode::RiskNsf));
        // R2 应为 no-op（无 matcher_event）；locked 应保持 0（R1 拒绝，未冻结）。
        assert_eq!(core.ups.get(1).unwrap().locked(QUOTE), 0);

        // 用 OrderBookRequest 确认簿仍为空（ME 没有把这笔拒绝单挂上去）。
        let mut req = OrderCommand {
            command: OrderCommandType::OrderBookRequest,
            symbol: SYMBOL,
            size: 10,
            ..Default::default()
        };
        core.process_command(&mut req);
        let md = req.market_data.unwrap();
        assert!(md.bid_prices.is_empty());
    }

    #[test]
    fn trading_place_order_valid_reaches_book_and_locks_funds() {
        let mut core = seeded_core();
        core.ups.add_empty_user_profile(1);
        core.ups.get_mut(1).unwrap().add_to_account(QUOTE, 1_000_000);
        let mut cmd = OrderCommand {
            command: OrderCommandType::PlaceOrder,
            order_id: 1,
            symbol: SYMBOL,
            price: 50,
            size: 1000,
            reserve_bid_price: 50,
            action: Some(OrderAction::Bid),
            order_type: Some(OrderType::Gtc),
            uid: 1,
            ..Default::default()
        };

        core.process_command(&mut cmd);

        assert_eq!(cmd.result_code, Some(CommandResultCode::Success));
        // 无对手盘：resting on book，全额冻结（taker_fee=0 → 冻结额=notional=50000）。
        assert_eq!(core.ups.get(1).unwrap().locked(QUOTE), 50_000);

        let mut req = OrderCommand {
            command: OrderCommandType::OrderBookRequest,
            symbol: SYMBOL,
            size: 10,
            ..Default::default()
        };
        core.process_command(&mut req);
        let md = req.market_data.unwrap();
        assert_eq!(md.bid_prices, vec![50]);
        assert_eq!(md.bid_volumes, vec![1000]);
    }

    #[test]
    fn cancel_order_is_r1_no_op_and_releases_lock_via_r2() {
        let mut core = seeded_core();
        core.ups.add_empty_user_profile(1);
        core.ups.get_mut(1).unwrap().add_to_account(QUOTE, 1_000_000);
        let mut place = OrderCommand {
            command: OrderCommandType::PlaceOrder,
            order_id: 1,
            symbol: SYMBOL,
            price: 50,
            size: 1000,
            reserve_bid_price: 50,
            action: Some(OrderAction::Bid),
            order_type: Some(OrderType::Gtc),
            uid: 1,
            ..Default::default()
        };
        core.process_command(&mut place);
        assert_eq!(core.ups.get(1).unwrap().locked(QUOTE), 50_000);

        let mut cancel = OrderCommand {
            command: OrderCommandType::CancelOrder,
            order_id: 1,
            symbol: SYMBOL,
            uid: 1,
            ..Default::default()
        };
        core.process_command(&mut cancel);

        assert_eq!(cancel.result_code, Some(CommandResultCode::Success));
        assert_eq!(core.ups.get(1).unwrap().locked(QUOTE), 0, "R2 应释放全部冻结");
    }
}
