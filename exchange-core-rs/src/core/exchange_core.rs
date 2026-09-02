//! 对应 Java: `exchange.core2.core.ExchangeCore`（Disruptor 五段编排入口）。
//! 设计文档 §4：本期塌缩为**单线程确定性顺序管线**——`process_command` 镜像 Java
//! disruptor 的业务阶段编排：`RiskEngine.preProcessCommand`（R1）→
//! `MatchingEngineRouter.processOrder`（ME）→ `RiskEngine.handlerRiskRelease`（R2）
//! 的单命令等价物（单 shard，无 grouping 批边界、无 Journal 落盘；`GroupingProcessor`/
//! `TwoStepMasterProcessor`/`TwoStepSlaveProcessor` 是纯 disruptor 线程编排构造，单线程
//! 确定性管道下无对应语义，刻意不建模——见 `process_command` 方法文档）。
//!
//! # Ruling P3-B（borrow 结构）
//! `ExchangeCore` 把 `risk`/`matching`/`ups`/`ssp` 都作为**平级字段**直接持有（非
//! `Rc<RefCell<_>>`）。`process_command` 里一律写 `self.risk.xxx(cmd, &mut self.ups, &self.ssp)`
//! 这种"字段直接访问 + 方法调用"的形式——Rust 借用检查器把 `self.risk`/`self.ups`/`self.ssp`
//! 识别为互不重叠的字段借用，允许同时可变/不可变借用，前提是不经过一个先拿 `&mut self`
//! 再在内部重新借用各字段的中间方法（那样借用检查器只看到一次对整个 `self` 的借用，
//! 无法证明字段级别不重叠）。因此本文件的 `process_command` 刻意不拆分成
//! `fn r1(&mut self, ..)` / `fn r2(&mut self, ..)` 这类私有辅助方法。
use crate::core::processors::symbol_specification_provider::SymbolSpecificationProvider;
use crate::core::processors::user_profile_service::UserProfileService;
use crate::core::common::cmd::order_command::OrderCommand;
use crate::core::processors::matching_engine_router::MatchingEngineRouter;
use crate::core::processors::risk_engine::RiskEngine;

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

    /// 确定性顺序管线（设计文档 §4）。镜像 Java `ExchangeCore` 的 disruptor 阶段编排——
    /// Grouping → R1(`RiskEngine.preProcessCommand`) → ME(`MatchingEngineRouter.processOrder`)
    /// → R2(`RiskEngine.handlerRiskRelease`) → ResultsHandler：
    /// - **Grouping**：Java 里是 disruptor 的批边界处理器（决定一批命令何时切段落盘/发布），
    ///   纯线程编排概念，单线程确定性管道下没有对应语义，本移植不建模。
    /// - **R1** [`RiskEngine::pre_process_command`]：主 switch 路由——非交易命令
    ///   （`AddUser`/`BalanceAdjustment`/...）整块委托处理；`PlaceOrder` 走风控冻结；
    ///   Cancel/Move/Reduce/OrderBookRequest 等 R1 无动作。
    /// - **ME** [`MatchingEngineRouter::process_order`]：按 symbol 路由到 order book；
    ///   非交易命令在这里 no-op 短路（不查 book、不碰 `result_code`）；`PlaceOrder` 只有
    ///   R1 放行（`result_code == ValidForMatchingEngine`）才真正撮合。
    /// - **R2** [`RiskEngine::handler_risk_release`]：结算成交/释放冻结，读 `cmd.matcher_event`
    ///   并消费之；非交易命令在这里同样 no-op 短路。
    /// - **ResultsHandler**：Java 里是 disruptor 消费者，把结果发布/回调给调用方；本移植是
    ///   同步调用模型，`cmd.result_code`/`market_data` 等字段处理完就地可读，调用方
    ///   （`ExchangeApi`）直接读 `cmd`，无需独立的结果分发阶段。
    ///
    /// 现在**所有**命令都依次流过 R1→ME→R2 这三段（不再有"非交易命令整体跳过 ME/R2"的分支），
    /// 对齐 Java 全部命令都过 disruptor 三个处理器的结构；非交易命令在 ME/R2 各自的 no-op
    /// 守卫下与旧版"直接跳过"完全等价（见 `matching_router.rs`/`risk.rs` 对应守卫的注释）。
    pub fn process_command(&mut self, cmd: &mut OrderCommand) {
        self.risk.pre_process_command(cmd, &mut self.ups, &self.ssp); // R1
        self.matching.process_order(cmd); // ME
        self.risk.handler_risk_release(cmd, &mut self.ups, &self.ssp); // R2
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::core::common::cmd::order_command::OrderCommand;
    use crate::core::common::cmd::command_result_code::CommandResultCode;
    use crate::core::common::order_action::OrderAction;
    use crate::core::common::cmd::order_command_type::OrderCommandType;
    use crate::core::common::order_type::OrderType;
    use crate::core::common::symbol_type::SymbolType;
    use crate::core::common::core_currency_specification::CoreCurrencySpecification;
    use crate::core::common::core_symbol_specification::CoreSymbolSpecification;

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
            ..Default::default()
        }
    }

    fn seeded_core() -> ExchangeCore {
        let mut core = ExchangeCore::new();
        core.ssp.add_currency(CoreCurrencySpecification { currency: BASE, currency_scale_k: 1, ..Default::default() });
        core.ssp.add_currency(CoreCurrencySpecification { currency: QUOTE, currency_scale_k: 1, ..Default::default() });
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
